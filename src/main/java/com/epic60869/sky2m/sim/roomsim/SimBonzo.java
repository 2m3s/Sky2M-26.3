package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 1's boss, Bonzo, in his circus ring.
 *
 * <p>The fight in Hypixel's order (https://hypixelskyblock.minecraft.wiki/w/Bonzo):
 * <ol>
 * <li><b>Phase 1.</b> Bonzo does not fight himself. He runs round the ring away from the players (hopping up onto
 * the short pillars round its edge as he goes), keeps summoning <b>Undead</b> - ordinary chasing zombies - and fires
 * <b>Wither Skulls</b> at a player. No melee, no balloons.</li>
 * <li><b>"Oh I'm dead!" ... "Sike!"</b> At 0 health he goes down, is untouchable for a moment and comes back healed
 * to full.</li>
 * <li><b>Phase 2.</b> Now he fights: he walks at the nearest player and hits in <b>melee</b>, throws an aimed
 * <b>balloon</b> at one player (it does what a Wither Skull does), and every so often runs to the middle for a
 * <b>Balloon Barrage</b> - balloons out in every direction. A balloon stops on the first block it meets, so the
 * answer is to put a pillar between you and him.</li>
 * </ol>
 *
 * <p><b>Health and damage are Hypixel's</b>: each life 250,000 on F1 and 200,000,000 on M1, a Wither Skull or
 * balloon 1,598 / 22,500, his melee his base damage of 2,664 / 45,000, and the Undead 45,000 / 15,000,000 health with
 * a 1,440 / 36,000 hit ({@link SimHealth#BONZO_UNDEAD}); all before the player's Defense
 * ({@link SimVitals#dealHypixel}). The wiki gives no cooldowns, so every timing here is APPROXIMATE.
 *
 * <p><b>Master Mode (M1)</b>, as the wiki has it: Undead come more often, the skulls fire more quickly, and his
 * balloons tend to go off at an upward angle (here: lobbed high, falling back down onto the target - which also
 * reaches a player on a pillar). How much more often and how much faster are the sim's guesses.
 *
 * <p>Every line is Hypixel's, word for word. Boss Phases opens "Bonzo 1" on the first and "Bonzo 2" on "Oh I'm
 * dead!", so those two must not change.
 */
final class SimBonzo extends SimBoss.Fight {

    /**
     * A life's health: 250,000 on F1, 200,000,000 on M1. The second life is the same bar again - Hypixel's Bonzo
     * "heals to max". /boss bonzo from another floor uses the number for that floor's mode.
     */
    private static double lifeHealth() {
        return SimHealth.byMode(250_000, 200_000_000);
    }

    /** A Wither Skull or a balloon: 1,598 on F1, 22,500 on M1 (the wiki: a balloon is "identical to" a skull). */
    private static double skullDamage() {
        return SimHealth.byMode(1_598, 22_500);
    }

    /** His base damage, which his phase 2 melee deals: 2,664 on F1, 45,000 on M1. */
    private static double baseDamage() {
        return SimHealth.byMode(2_664, 45_000);
    }

    /** "Oh I'm dead!" to "Sike!": untouchable for this long. APPROXIMATE. */
    private static final int REVIVE_TICKS = 50;
    private static final int SHOT_LIFE = 80;
    private static final double SHOT_HIT_RADIUS = 1.0;
    /** A Balloon Barrage: this many waves, this far apart, this many balloons a wave. APPROXIMATE. */
    private static final int BARRAGE_WAVES = 5;
    private static final int BARRAGE_WAVE_GAP = 10;
    private static final int BARRAGE_BALLOONS = 20;

    private enum Phase { INTRO, LIFE_ONE, REVIVING, LIFE_TWO, DYING, DEAD }

    private static final String[] PHASE_ONE_LINES = {"Stop.", "You're not funny.", "Wait..", "Cringe.",
            "Give me 1 sec..", "Lame.", "Leave me alone!", "Fight my Undeads instead.", "Why are you running?",
            "Cheater!", "Hacker!", "Not fair!", "OUCH!"};
    private static final String[] BARRAGE_LINES = {"All I have are positive thoughts.", "Do you want a balloon?",
            "Run, run, run, RUN!", "Weeeeeeeee"};
    /** The wiki's three options on entering phase 2: the first is said as its three lines together. */
    private static final String[][] PHASE_TWO_LINES = {
            {"Hoho, looks like you killed me!", "You fools!", "You fell victim to one of the classic blunders!"},
            {"Check mate."},
            {"I can revive myself and become much stronger!"}};

    /** Fixed when the fight is made: the floor does not change mid-fight. */
    private final boolean master = SimState.floorLabel().toUpperCase(java.util.Locale.ROOT).startsWith("M");
    /** Undead up at once: more on Master Mode. APPROXIMATE. */
    private final int maxUndead = master ? 10 : 6;
    private Phase phase = Phase.INTRO;
    private int phaseTicks;
    /** Ticks since the fight started; shots count on it, it never resets. */
    private int now;
    private final SimBoss.Health health = new SimBoss.Health(lifeHealth());
    private Zombie body;
    private final List<UUID> undead = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    private int nextSkull;
    private int nextUndead;
    private int nextBalloon;
    private int nextBarrage;
    private int nextMelee;
    private int lastTaunt = -1000;
    /** Where he is running to in phase 1; null to pick a new spot. */
    private Vec3 runTo;
    /** The Balloon Barrage under way: ticks into it, or -1 when there is none. */
    private int barrageTicks = -1;
    private double barrageSpin;
    /** Ticks spent walking to the middle for a barrage; he starts where he is if he cannot get there. */
    private int barrageWalk;

    /** A Wither Skull or a balloon in flight. Hurts the first player it touches and stops on any block. */
    private static final class Shot {
        Vec3 pos;
        Vec3 vel;
        int life = SHOT_LIFE;
        final boolean balloon;
        /** Pull per tick for a lobbed Master Mode balloon; 0 flies straight. */
        final double gravity;
        /**
         * The players one Balloon Barrage wave has already hit, shared by every balloon of the wave; null for a
         * single shot. Close to Bonzo the wave's balloons overlap, and the sim's hits have no invulnerability window,
         * so without this one wave landed several times over on whoever stood next to him.
         */
        final java.util.Set<UUID> volley;

        Shot(Vec3 pos, Vec3 vel, boolean balloon, double gravity) {
            this(pos, vel, balloon, gravity, null);
        }

        Shot(Vec3 pos, Vec3 vel, boolean balloon, double gravity, java.util.Set<UUID> volley) {
            this.pos = pos;
            this.vel = vel;
            this.balloon = balloon;
            this.gravity = gravity;
            this.volley = volley;
        }
    }

    SimBonzo(SimBossArena arena) {
        super(arena);
    }

    @Override
    String name() {
        return "Bonzo";
    }

    @Override
    void start(ServerLevel level) {
        // Hypixel's first line, word for word: Boss Phases opens "Bonzo 1" on it.
        SimBoss.say(level.getServer(), "Bonzo", "Gratz for making it this far, but I'm basically unbeatable.");
        spawnBody(level);
        health.invulnerable = true;
        toPhase(Phase.INTRO);
    }

    @Override
    void tick(ServerLevel level, int t) {
        now = t;
        phaseTicks++;
        if ((body == null || body.isRemoved()) && phase != Phase.DYING && phase != Phase.DEAD) {
            spawnBody(level);
        }
        switch (phase) {
            case INTRO -> {
                // The rest of Hypixel's opening, a couple of seconds apart (the spacing is APPROXIMATE).
                if (phaseTicks == 40) {
                    SimBoss.say(level.getServer(), "Bonzo", "I don't even need to fight, this is the life!");
                } else if (phaseTicks == 80) {
                    SimBoss.say(level.getServer(), "Bonzo", "I can summon lots of undead! Check this out.");
                    health.invulnerable = false;
                    summonUndead(level, master ? 4 : 3);
                    nextUndead = undeadInterval();
                    nextSkull = 30;
                    toPhase(Phase.LIFE_ONE);
                }
            }
            case LIFE_ONE -> {
                run(level);
                if (--nextUndead <= 0) {
                    summonUndead(level, master ? 3 : 2);
                    nextUndead = undeadInterval();
                }
                if (--nextSkull <= 0) {
                    ServerPlayer target = randomPlayer(level);
                    if (target != null && body != null) {
                        shoot(level, target, false);
                    }
                    // Master Mode "fires more quickly" (the wiki); both cadences APPROXIMATE.
                    nextSkull = master ? 25 : 40;
                }
                if (health.hp <= 0) {
                    fallDown(level);
                }
            }
            case REVIVING -> {
                if (body != null) {
                    level.sendParticles(ParticleTypes.WITCH, body.getX(), body.getY() + 1, body.getZ(), 8,
                            0.4, 0.8, 0.4, 0.05);
                }
                if (phaseTicks >= REVIVE_TICKS) {
                    sike(level);
                }
            }
            case LIFE_TWO -> lifeTwo(level);
            case DYING -> {
                // Hypixel's closing lines after the kill, then the fight is over.
                if (phaseTicks == 30) {
                    SimBoss.say(level.getServer(), "Bonzo", "But my masters are a lot stronger..");
                } else if (phaseTicks == 60) {
                    SimBoss.say(level.getServer(), "Bonzo", "Just you wait...");
                    toPhase(Phase.DEAD);
                }
            }
            default -> {
            }
        }
        tickShots(level);
        nameTag();
    }

    private void toPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    private int undeadInterval() {
        // Master Mode's Undead "spawn more often" (the wiki); both intervals APPROXIMATE.
        return master ? 180 : 280;
    }

    // ------------------------------------------------------------------ the two lives

    /** End of phase 1: Hypixel's "Oh I'm dead!" - Boss Phases starts "Bonzo 2" on it. */
    private void fallDown(ServerLevel level) {
        SimBoss.say(level.getServer(), "Bonzo", "Oh I'm dead!");
        health.invulnerable = true;
        if (body != null) {
            body.setGlowingTag(true);
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.WITHER_SPAWN,
                    SoundSource.HOSTILE, 0.6f, 1.6f);
        }
        shots.clear();
        toPhase(Phase.REVIVING);
    }

    /** "Sike!": back to full health, and phase 2 begins. */
    private void sike(ServerLevel level) {
        SimBoss.say(level.getServer(), "Bonzo", "Sike!");
        for (String line : PHASE_TWO_LINES[ThreadLocalRandom.current().nextInt(PHASE_TWO_LINES.length)]) {
            SimBoss.say(level.getServer(), "Bonzo", line);
        }
        health.max = lifeHealth();
        health.hp = health.max;
        health.invulnerable = false;
        if (body != null) {
            body.setGlowingTag(false);
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, body.getX(), body.getY() + 1, body.getZ(), 30,
                    0.5, 0.8, 0.5, 0.3);
        }
        nextBalloon = 40;
        nextBarrage = 200;
        nextMelee = 20;
        toPhase(Phase.LIFE_TWO);
    }

    private void lifeTwo(ServerLevel level) {
        if (body == null) {
            return;
        }
        if (barrageTicks >= 0) {
            barrage(level);
        } else {
            chase(level);
            if (--nextBalloon <= 0) {
                ServerPlayer target = randomPlayer(level);
                if (target != null) {
                    shoot(level, target, true);
                }
                nextBalloon = master ? 35 : 50; // APPROXIMATE
            }
            if (--nextBarrage <= 0) {
                barrageTicks = 0;
                barrageWalk = 0;
                barrageSpin = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
                SimBoss.say(level.getServer(), "Bonzo", pick(BARRAGE_LINES));
            }
        }
        if (health.hp <= 0) {
            die(level);
        }
    }

    private void die(ServerLevel level) {
        SimBoss.say(level.getServer(), "Bonzo", "Alright, maybe I'm just weak after all..");
        if (body != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, body.getX(), body.getY() + 1, body.getZ(), 3,
                    0.5, 0.5, 0.5, 0);
            body.discard();
            body = null;
        }
        discardUndead(level);
        shots.clear();
        barrageTicks = -1;
        toPhase(Phase.DYING);
    }

    // ------------------------------------------------------------------ moving

    private void spawnBody(ServerLevel level) {
        BlockPos at = arena.toWorld(arena.bossSpawn);
        Zombie z = new BossBody(level);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        z.setHealth(1000);
        z.setNoAi(true);
        z.setNoGravity(true);
        z.setPersistenceRequired();
        z.setCustomNameVisible(true);
        z.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        z.setGlowingTag(health.invulnerable && phase == Phase.REVIVING);
        level.addFreshEntity(z);
        body = z;
        nameTag();
    }

    /**
     * Phase 1: runs from spot to spot round the ring, each one picked away from the nearest player, hopping up and
     * down whatever is in the way - the short pillars round the edge included.
     */
    private void run(ServerLevel level) {
        if (body == null) {
            return;
        }
        Vec3 from = body.position();
        if (runTo == null || horizontal(from, runTo) < 1.0 || phaseTicks % 100 == 0) {
            runTo = pickRunSpot(level);
        }
        step(level, runTo, master ? 0.3 : 0.25);
        face(level, body.position());
    }

    /** A spot in the ring, the best of a few random ones by distance from the nearest player. */
    private Vec3 pickRunSpot(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Vec3 best = null;
        double bestScore = -1;
        for (int i = 0; i < 6; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double d = 4 + rnd.nextDouble() * Math.max(1, arena.radius - 6);
            Vec3 spot = new Vec3(c.getX() + 0.5 + Math.cos(a) * d, c.getY(), c.getZ() + 0.5 + Math.sin(a) * d);
            ServerPlayer p = nearest(level, spot);
            double score = p == null ? 0 : horizontal(p.position(), spot);
            if (score > bestScore) {
                bestScore = score;
                best = spot;
            }
        }
        return best;
    }

    /** Phase 2: walks at the nearest player and hits whoever he reaches. */
    private void chase(ServerLevel level) {
        ServerPlayer target = nearest(level, body.position());
        if (target == null) {
            return;
        }
        double d = horizontal(body.position(), target.position());
        if (d > 1.8) {
            step(level, target.position(), master ? 0.2 : 0.16);
        }
        face(level, body.position());
        if (nextMelee > 0) {
            nextMelee--;
        } else if (d < 2.6 && Math.abs(target.getY() - body.getY()) < 2.5) {
            body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            hurtPlayer(level, target, baseDamage(), target.getX() - body.getX(), target.getZ() - body.getZ(),
                    0.5, 0.3);
            nextMelee = 20; // APPROXIMATE: a swing a second
        }
    }

    /**
     * One step of {@code speed} blocks toward {@code to}, onto whatever ground is there. A rise of up to a pillar's
     * height is a hop rather than a wall (Bonzo gets on top of them on Hypixel); anything taller stops him.
     */
    private void step(ServerLevel level, Vec3 to, double speed) {
        Vec3 from = body.position();
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01) {
            return;
        }
        double s = Math.min(d, speed);
        double nx = from.x + dx / d * s;
        double nz = from.z + dz / d * s;
        int ground = ground(level, (int) Math.floor(nx), (int) Math.floor(nz), (int) Math.floor(from.y) + 5);
        if (ground - from.y > 4.5 || from.y - ground > 6 || !open(level, nx, ground, nz)) {
            runTo = null;
            return;
        }
        // Rise or fall half a block a tick at most, so a hop onto a pillar is a hop and not a blink.
        double ny = from.y + Math.max(-0.5, Math.min(0.5, ground - from.y));
        body.snapTo(nx, ny, nz, body.getYRot(), 0f);
    }

    private void face(ServerLevel level, Vec3 from) {
        ServerPlayer target = nearest(level, from);
        if (target == null || body == null) {
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(target.getZ() - from.z, target.getX() - from.x)) - 90);
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.setYBodyRot(yaw);
    }

    /** Whether the two blocks from {@code y} up at a column are free to stand in (a wall taller than a hop is not). */
    private static boolean open(ServerLevel level, double x, int y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** The standing height at a column: the first open block with something solid under it, from {@code fromY} down. */
    private static int ground(ServerLevel level, int x, int z, int fromY) {
        for (int y = fromY; y > fromY - 14; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return y;
            }
        }
        return fromY - 5;
    }

    // ------------------------------------------------------------------ skulls and balloons

    /**
     * A Wither Skull (phase 1) or a balloon (phase 2) at one player. A Master Mode balloon goes up at an angle and
     * falls back onto him (APPROXIMATE: the wiki only says they "tend to fire at an upward angle").
     */
    private void shoot(ServerLevel level, ServerPlayer target, boolean balloon) {
        Vec3 from = body.position().add(0, 1.6, 0);
        Vec3 aim = target.position().add(0, 1.0, 0).subtract(from);
        if (aim.lengthSqr() < 0.01) {
            return;
        }
        body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        if (balloon && master) {
            // A lob: flight time from the distance, the upward speed to land on him in that time.
            double g = 0.03;
            double flat = Math.sqrt(aim.x * aim.x + aim.z * aim.z);
            double ticks = Math.max(10, flat / 0.6);
            Vec3 vel = new Vec3(aim.x / ticks, aim.y / ticks + g * ticks / 2, aim.z / ticks);
            shots.add(new Shot(from, vel, true, g));
        } else {
            // Master Mode's skulls are faster (the wiki); both speeds APPROXIMATE.
            double speed = balloon ? 0.6 : master ? 1.0 : 0.7;
            shots.add(new Shot(from, aim.normalize().scale(speed), balloon, 0));
        }
        level.playSound(null, from.x, from.y, from.z,
                balloon ? SoundEvents.FIREWORK_ROCKET_LAUNCH : SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE,
                balloon ? 1f : 0.6f, balloon ? 0.8f : 1.2f);
    }

    /**
     * The Balloon Barrage: he runs to the middle of the ring, then sends waves of balloons out in every direction,
     * each wave turned a little from the last so the gaps move. A balloon stops on a block, so a pillar between him
     * and you is the cover. Some go out a little upward, which is what reaches a player standing on a pillar.
     */
    private void barrage(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        Vec3 mid = new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        if (horizontal(body.position(), mid) > 1.0 && barrageTicks == 0 && barrageWalk++ < 80) {
            step(level, mid, 0.45);
            face(level, body.position());
            return;
        }
        barrageTicks++;
        int t = barrageTicks - 15;
        if (t == 0) {
            level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.FIREWORK_ROCKET_LARGE_BLAST,
                    SoundSource.HOSTILE, 1.2f, 0.7f);
        }
        if (t >= 0 && t % BARRAGE_WAVE_GAP == 0 && t / BARRAGE_WAVE_GAP < BARRAGE_WAVES) {
            int wave = t / BARRAGE_WAVE_GAP;
            Vec3 from = body.position().add(0, 1.6, 0);
            java.util.Set<UUID> hitThisWave = new java.util.HashSet<>();
            for (int i = 0; i < BARRAGE_BALLOONS; i++) {
                double a = barrageSpin + Math.PI * 2 * (i + (wave % 2) * 0.5) / BARRAGE_BALLOONS;
                double up = i % 3 == 0 ? 0.12 : -0.02;
                Vec3 dir = new Vec3(Math.cos(a), up, Math.sin(a)).normalize();
                shots.add(new Shot(from, dir.scale(0.55), true, 0, hitThisWave));
            }
            body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.playSound(null, from.x, from.y, from.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE,
                    1f, 0.9f + wave * 0.1f);
        }
        if (t >= BARRAGE_WAVES * BARRAGE_WAVE_GAP + 10) {
            barrageTicks = -1;
            nextBarrage = master ? 260 : 340; // APPROXIMATE
            nextBalloon = 30;
        }
    }

    private void tickShots(ServerLevel level) {
        if (shots.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        DustParticleOptions[] colours = {new DustParticleOptions(0xFF3030, 1.4f),
                new DustParticleOptions(0x3080FF, 1.4f), new DustParticleOptions(0xFFD030, 1.4f)};
        List<Shot> done = new ArrayList<>();
        for (Shot s : shots) {
            boolean gone = --s.life <= 0;
            // Two half steps a tick, so a shot cannot skip over a player or through a thin wall.
            for (int step = 0; step < 2 && !gone; step++) {
                s.pos = s.pos.add(s.vel.scale(0.5));
                BlockPos bp = BlockPos.containing(s.pos);
                if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                    pop(level, s);
                    gone = true;
                    break;
                }
                for (ServerPlayer p : players) {
                    if (p.position().add(0, 1.0, 0).distanceToSqr(s.pos) <= SHOT_HIT_RADIUS * SHOT_HIT_RADIUS) {
                        // One hit per player per barrage wave (the balloon still pops on him).
                        if (s.volley == null || s.volley.add(p.getUUID())) {
                            hurtPlayer(level, p, skullDamage(), s.vel.x, s.vel.z, 0.5, 0.3);
                        }
                        pop(level, s);
                        gone = true;
                        break;
                    }
                }
            }
            if (s.gravity > 0) {
                s.vel = s.vel.add(0, -s.gravity, 0);
            }
            if (gone) {
                done.add(s);
                continue;
            }
            if (s.balloon) {
                DustParticleOptions colour = colours[Math.floorMod(System.identityHashCode(s), colours.length)];
                level.sendParticles(colour, s.pos.x, s.pos.y, s.pos.z, 3, 0.12, 0.12, 0.12, 0);
            } else {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, s.pos.x, s.pos.y, s.pos.z, 2, 0.05, 0.05, 0.05, 0.01);
                level.sendParticles(ParticleTypes.SMOKE, s.pos.x, s.pos.y, s.pos.z, 1, 0.05, 0.05, 0.05, 0.01);
            }
        }
        shots.removeAll(done);
    }

    private static void pop(ServerLevel level, Shot s) {
        level.sendParticles(s.balloon ? ParticleTypes.POOF : ParticleTypes.SMOKE, s.pos.x, s.pos.y, s.pos.z, 6,
                0.15, 0.15, 0.15, 0.02);
    }

    /**
     * Damage plus knockback away from a point: {@code damage} is Hypixel damage before the player's Defense
     * ({@link SimVitals#dealHypixel}).
     */
    private static void hurtPlayer(ServerLevel level, ServerPlayer p, double damage, double dx, double dz,
                                   double push, double lift) {
        DamageSource src = level.damageSources().magic();
        SimVitals.dealHypixel(level, p, src, damage, false);
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            double a = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            dx = Math.cos(a);
            dz = Math.sin(a);
            len = 1;
        }
        p.setDeltaMovement(dx / len * push, lift, dz / len * push);
        p.syncVelocity = true;
    }

    // ------------------------------------------------------------------ the Undead

    private void summonUndead(ServerLevel level, int count) {
        undead.removeIf(id -> {
            Entity e = level.getEntity(id);
            return e == null || !e.isAlive();
        });
        BlockPos c = arena.toWorld(arena.centre);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // Hypixel's Undead: registered as "Undead", the pool takes the wiki's health and hit for F1/M1
        // (SimHealth.BONZO_UNDEAD) whatever is asked for here.
        String floor = SimState.floorLabel();
        SimHealth.Role role = master ? SimHealth.Role.STARRED : SimHealth.Role.REGULAR;
        for (int i = 0; i < count && undead.size() < maxUndead; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double d = 8 + rnd.nextDouble() * (arena.radius - 12);
            Undead u = new Undead(level);
            u.setPersistenceRequired();
            u.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(SimHealth.meleeDamage(floor, role));
            if (master) {
                u.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.28);
            }
            u.snapTo(c.getX() + 0.5 + Math.cos(a) * d, c.getY(), c.getZ() + 0.5 + Math.sin(a) * d,
                    rnd.nextFloat() * 360, 0f);
            ServerPlayer target = nearest(level, u.position());
            if (target != null) {
                u.setTarget(target);
            }
            if (!level.addFreshEntity(u)) {
                continue;
            }
            // The pool writes "Undead 3k❤" into the zombie's own name.
            SimHealth.register(u, SimHealth.maxHealth(floor, role), "Undead", false, null);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, u.getX(), u.getY() + 1, u.getZ(), 10, 0.3, 0.5, 0.3,
                    0.02);
            undead.add(u.getUUID());
        }
    }

    private void discardUndead(ServerLevel level) {
        for (UUID id : undead) {
            Entity e = level.getEntity(id);
            if (e != null) {
                e.discard();
            }
        }
        undead.clear();
    }

    // ------------------------------------------------------------------ shared bits

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    private static ServerPlayer nearest(ServerLevel level, Vec3 from) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            double d = p.position().distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static ServerPlayer randomPlayer(ServerLevel level) {
        List<ServerPlayer> in = SimBoss.playersInRing(level);
        return in.isEmpty() ? null : in.get(ThreadLocalRandom.current().nextInt(in.size()));
    }

    private void nameTag() {
        if (body == null) {
            return;
        }
        Component tag = Component.literal("Bonzo ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(health.invulnerable ? "???" : SimBoss.shortHealth(health.hp))
                        .withStyle(ChatFormatting.GREEN).append(Component.literal("❤")
                                .withStyle(ChatFormatting.RED)));
        body.setCustomName(tag);
    }

    @Override
    boolean isBoss(Entity entity) {
        return entity == body;
    }

    @Override
    boolean hit(ServerLevel level, LivingEntity hitBody, float amount) {
        boolean landed = (phase == Phase.LIFE_ONE || phase == Phase.LIFE_TWO) && health.hit(amount);
        // Phase 1's random lines, one now and then as he gets hit (how often is APPROXIMATE).
        if (landed && phase == Phase.LIFE_ONE && health.hp > 0 && now - lastTaunt > 120
                && ThreadLocalRandom.current().nextInt(3) == 0) {
            lastTaunt = now;
            SimBoss.say(level.getServer(), "Bonzo", pick(PHASE_ONE_LINES));
        }
        return landed;
    }

    @Override
    boolean defeated() {
        return phase == Phase.DEAD;
    }

    @Override
    void discard(ServerLevel level) {
        if (body != null) {
            body.discard();
            body = null;
        }
        discardUndead(level);
        shots.clear();
    }

    private boolean secondLife() {
        return phase == Phase.LIFE_TWO || phase == Phase.REVIVING || phase == Phase.DYING;
    }

    @Override
    Component barName() {
        return Component.literal("Bonzo" + (secondLife() ? " (2nd life)" : "")).withStyle(ChatFormatting.RED);
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return secondLife() ? BossEvent.BossBarColor.PURPLE : BossEvent.BossBarColor.RED;
    }

    @Override
    float barProgress() {
        return phase == Phase.INTRO ? 1f : phase == Phase.REVIVING
                ? Math.min(1f, (float) phaseTicks / REVIVE_TICKS) : health.fraction();
    }

    /** Bonzo's body: no AI (the fight moves him), never despawns, never burns in the sim's daylight. */
    private static final class BossBody extends Zombie {
        BossBody(Level level) {
            super(level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean isSunSensitive() {
            return false;
        }

        @Override
        protected boolean convertsInWater() {
            return false;
        }

        @Override
        public void igniteForTicks(int ticks) {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }
    }

    /**
     * Bonzo's adds: ordinary chasing zombies that never despawn and never burn, with a {@link SimHealth} pool.
     * Daylight burning comes from an entity tag rather than {@code isSunSensitive} in 26.2, so the fire itself is
     * refused too, the way the room mobs refuse it.
     */
    private static final class Undead extends Zombie {
        Undead(Level level) {
            super(level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean isSunSensitive() {
            return false;
        }

        @Override
        protected boolean convertsInWater() {
            return false;
        }

        @Override
        public void igniteForTicks(int ticks) {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }
    }
}
