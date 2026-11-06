package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McEntities;
import com.epic60869.sky2m.sim.util.ModChat;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 2's boss, Scarf, the Apprentice Necromancer.
 *
 * <p>The fight in Hypixel's order (https://hypixelskyblock.minecraft.wiki/w/Scarf):
 * <ol>
 * <li><b>Phase 1, his Undead.</b> Scarf sits behind his barrier over the far end of the arena, untouchable, while
 * his four Undead fight - two of each on Master Mode:
 * <ul>
 * <li><b>Undead Warrior</b> - <b>Charge</b>: winds up, then rushes in a straight line through whoever is in the
 * way. The others call him in on whoever has been hurting them most.</li>
 * <li><b>Undead Archer</b> - <b>Arrow Barrage</b> in every direction; <b>teleports</b> itself away from a player who
 * gets close, or pulls a player in front of a Warrior for a Charge.</li>
 * <li><b>Undead Mage</b> - stands still to fire <b>Wither Skulls</b>, opens a <b>Magma Pit</b> under a player and
 * sends a <b>Tsunami</b> wave.</li>
 * <li><b>Undead Priest</b> - heals itself and the Undead near it (and, in phase 2, Scarf).</li>
 * </ul>
 * All of them also run at players to hit them.</li>
 * <li><b>Phase 2.</b> With all of them dead Scarf revives every one of them, stronger, and comes down to fight like
 * a Warrior - in melee - with two attacks of his own: <b>Skull Barrage</b> (he rises a little and fires explosive
 * skulls in a wide arc) and <b>Soul Sand Blizzard</b> (a trail of soul sand runs at a player; standing on it costs a
 * share of max health as true damage, plus Slowness). On Master Mode his damage resistance scales with how many of
 * his Undead are alive, and he is immune until at least four of them are dead.</li>
 * </ol>
 *
 * <p><b>Health and damage are Hypixel's</b> where the wiki has them: Scarf 1,000,000 / 375,000,000 and a base damage
 * (his melee) of 3,456 / 81,000; his Undead's health and hits from their table ({@link SimHealth#UNDEAD_WARRIOR} and
 * the rest: Warrior 500k / 75M, Archer 400k / 60M, Mage 400k / 60M, Priest 600k / 90M); the Warrior's Charge 864 /
 * 54,000, the Mage's Wither Skulls 1,094 / 29,640, Magma Pit 365 / 27,360 and Tsunami 608 / 22,800. Every hit is
 * before the player's Defense ({@link SimVitals#dealHypixel}). The wiki has no cooldowns, no heal amount, no Soul
 * Sand share, no number for Scarf's skulls or the Archer's barrage, and no measure of "stronger" - those are the
 * sim's guesses, each marked APPROXIMATE where it is set.
 *
 * <p>Every line is Hypixel's, word for word. Boss Phases opens "Minions" on the first one and "Scarf" on "Did you
 * forget? I was taught by the best! Let's dance." (the start of phase 2), so those must not change.
 */
final class SimScarf extends SimBoss.Fight {

    /** Scarf's health: 1,000,000 on F2, 375,000,000 on M2. */
    private static double bossHealth() {
        return SimHealth.byMode(1_000_000, 375_000_000);
    }

    /** Scarf's base damage, 3,456 / 81,000: his melee. */
    private static double scarfDamage() {
        return SimHealth.byMode(3_456, 81_000);
    }

    /** One of Scarf's explosive skulls: half his base damage. APPROXIMATE (the wiki gives no number). */
    private static double scarfSkull() {
        return scarfDamage() * 0.5;
    }

    /** The Warrior's Charge: 0.3x base (864) on F2, 1x base (54,000) on M2. */
    private static double chargeDamage() {
        return SimHealth.byMode(864, 54_000);
    }

    /** The Mage's Wither Skulls: 1,094 on F2, 29,640 on M2. */
    private static double mageSkull() {
        return SimHealth.byMode(1_094, 29_640);
    }

    /** The Mage's Magma Pit, per tick of burning: 365 on F2, 27,360 on M2. */
    private static double magmaPit() {
        return SimHealth.byMode(365, 27_360);
    }

    /** The Mage's Tsunami: 608 on F2, 22,800 on M2. */
    private static double tsunami() {
        return SimHealth.byMode(608, 22_800);
    }

    /** Soul Sand Blizzard: this share of max health as true damage, each half second on it. APPROXIMATE. */
    private static final double SAND_SHARE = 0.05;
    /** Phase 2's Undead are "stronger": hits and abilities times this, cooldowns divided by it. APPROXIMATE. */
    private static final double STRONGER = 1.25;

    /** His four kinds of Undead, and where each rises relative to the arena's centre in source coordinates. */
    private enum Guardian {
        WARRIOR("Undead Warrior", 0, -3),
        PRIEST("Undead Priest", 0, -8),
        MAGE("Undead Mage", -6, -6),
        ARCHER("Undead Archer", 6, -6);

        final String display;
        final int dx;
        final int dz;

        Guardian(String display, int dx, int dz) {
            this.display = display;
            this.dx = dx;
            this.dz = dz;
        }
    }

    private enum Phase { INTRO, MINIONS, RISE, FIGHT, DYING, DEAD }

    private static final String[] KILL_LINES = {"I'll make you take the %s's place!",
            "Oh, you killed my %s? No problem.", "Darn! My %s!", "RIP %s. F", "I expected better from you, %s."};
    private static final String[] BARRAGE_LINES = {"No more hiding!", "I'll send you to the Wither Realm!",
            "Just who do you think I am!", "Dodge this!", "Soon I'll be shooting your skull!", "Necro no Jutsu!",
            "Yare yare daze...", "I'm going to be the Wither King!", "Wither Ultra!"};
    private static final String[] HURT_LINES = {"OUCH!", "Oof"};
    private static final String[] TAUNT_LINES = {"You'd make a great Undead minion!",
            "I can't believe Bonzo lost to someone so weak!"};

    private static final int SHOT_LIFE = 70;
    private static final double SHOT_HIT_RADIUS = 1.1;
    /** How high over his spawn point he sits behind his barrier while his Undead fight. */
    private static final double PERCH_HEIGHT = 4;
    /** "Did you forget?..." to the fight proper: the Undead rise and he comes down. */
    private static final int DESCEND_TICKS = 40;

    private final boolean master = SimState.floorLabel().toUpperCase(Locale.ROOT).startsWith("M");
    private Phase phase = Phase.INTRO;
    private int phaseTicks;
    /** Ticks since the fight started; everything timed counts on this, it never resets. */
    private int now;
    private final SimBoss.Health health = new SimBoss.Health(bossHealth());
    private Zombie body;

    /** Every Undead: one of each kind, two on Master Mode (the wiki: "2 of each" in both phases). */
    private final List<Minion> minions = new ArrayList<>();
    /** Health of the Undead that are up, for the bar while they fight. */
    private double waveMaxHealth;
    private boolean phaseTwo;
    private boolean saidAlone;
    private boolean saidPriest;

    private final List<Shot> shots = new ArrayList<>();
    private final List<Pit> pits = new ArrayList<>();
    private final List<Wave> waves = new ArrayList<>();
    private final List<Trail> trails = new ArrayList<>();
    /** Soul sand laid by the Blizzard, with the block it replaced, put back when it melts away. */
    private final Map<BlockPos, Sand> sands = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastSandHit = new HashMap<>();

    private int nextMelee;
    private int nextBarrage;
    private int nextBlizzard;
    private int nextTaunt;
    /** The Skull Barrage under way: ticks into it, or -1 when there is none. */
    private int barrageTicks = -1;
    private int lastImmuneNote = -1000;
    private int lastKillLine = -1000;
    private int lastHurtLine = -1000;
    private int lastHealLine = -1000;
    private int lastCallLine = -1000;

    /** One Undead and what it is doing. */
    private static final class Minion {
        final Guardian kind;
        final int copy;
        UUID id;
        boolean alive;
        Vec3 lastPos;
        double lastHp = -1;
        int lastHurt = -1000;
        /** Damage taken, by the player most likely to have dealt it, for the Help call. */
        final Map<UUID, Double> damageBy = new HashMap<>();
        int nextA;
        int nextB;
        int nextC;
        int nextHelp;
        /** The Mage standing still to fire its skulls: ticks left. */
        int rooted;
        UUID rootTarget;
        /** The Warrior's Charge: wind-up ticks left, then rush ticks left; -1 for neither. */
        int windup = -1;
        int rushing = -1;
        UUID chargeTarget;
        Vec3 chargeDir;
        final Set<UUID> chargeHit = new HashSet<>();

        Minion(Guardian kind, int copy) {
            this.kind = kind;
            this.copy = copy;
        }

        boolean charging() {
            return windup >= 0 || rushing >= 0;
        }
    }

    /** A skull or arrow in flight: hurts the first player it touches, or blows up when {@code blast} > 0. */
    private static final class Shot {
        Vec3 pos;
        final Vec3 vel;
        int life = SHOT_LIFE;
        final double damage;
        final double blast;
        final boolean arrow;
        /**
         * The players one volley (a Skull Barrage fan, an Arrow Barrage) has already hit, shared by its shots; null
         * for a single shot. The fan's skulls and blasts overlap near the target, and the sim's hits have no
         * invulnerability window, so without this one fan landed three or four times on the same player.
         */
        final Set<UUID> volley;

        Shot(Vec3 pos, Vec3 vel, double damage, double blast, boolean arrow) {
            this(pos, vel, damage, blast, arrow, null);
        }

        Shot(Vec3 pos, Vec3 vel, double damage, double blast, boolean arrow, Set<UUID> volley) {
            this.pos = pos;
            this.vel = vel;
            this.damage = damage;
            this.blast = blast;
            this.arrow = arrow;
            this.volley = volley;
        }
    }

    /** The Mage's Magma Pit: a warning ring, then burning ground from {@code burnAt} to {@code endAt}. */
    private record Pit(Vec3 at, double radius, int burnAt, int endAt, double damage) {
    }

    /** The Mage's Tsunami: a wall of water moving out along {@code dir}. */
    private static final class Wave {
        final Vec3 origin;
        final Vec3 dir;
        double dist;
        final double damage;
        final Set<UUID> hit = new HashSet<>();

        Wave(Vec3 origin, Vec3 dir, double damage) {
            this.origin = origin;
            this.dir = dir;
            this.damage = damage;
        }
    }

    /** A Soul Sand Blizzard trail running at a player, a block every couple of ticks. */
    private static final class Trail {
        Vec3 at;
        final UUID target;
        int steps;

        Trail(Vec3 at, UUID target, int steps) {
            this.at = at;
            this.target = target;
            this.steps = steps;
        }
    }

    private record Sand(BlockState original, int meltAt) {
    }

    SimScarf(SimBossArena arena) {
        super(arena);
        for (int copy = 0; copy < (master ? 2 : 1); copy++) {
            for (Guardian g : Guardian.values()) {
                minions.add(new Minion(g, copy));
            }
        }
    }

    @Override
    String name() {
        return "Scarf";
    }

    @Override
    void start(ServerLevel level) {
        // Hypixel's first line, word for word: Boss Phases opens "Minions" on it, the splits "Scarf's minions".
        SimBoss.say(level.getServer(), "Scarf", "This is where the journey ends for you, Adventurers.");
        health.invulnerable = true;
        spawnBody(level);
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
                perch(level);
                // Hypixel's opening, a couple of seconds a line (the spacing is APPROXIMATE).
                if (phaseTicks == 40) {
                    SimBoss.say(level.getServer(), "Scarf",
                            "The last few who tried fighting me are now in those Crypts.");
                } else if (phaseTicks == 80) {
                    SimBoss.say(level.getServer(), "Scarf",
                            "If you can beat my Undeads, I'll personally grant you the privilege to replace them.");
                } else if (phaseTicks >= 120) {
                    SimBoss.say(level.getServer(), "Scarf", "ARISE, MY CREATIONS!");
                    for (Minion m : minions) {
                        raise(level, m, spawnSpot(m), false);
                    }
                    waveMaxHealth = waveHealth(level, true);
                    toPhase(Phase.MINIONS);
                }
            }
            case MINIONS -> {
                perch(level);
                if (phaseTicks == 30) {
                    SimBoss.say(level.getServer(), "Scarf", "This should be interesting.");
                }
                tickMinions(level);
                if (allDown()) {
                    SimBoss.say(level.getServer(), "Scarf", "Those toys are not strong enough I see.");
                    toPhase(Phase.RISE);
                }
            }
            case RISE -> rise(level);
            case FIGHT -> fight(level);
            case DYING -> {
                // Hypixel's lines for a won fight, then it is over.
                if (phaseTicks == 30) {
                    SimBoss.say(level.getServer(), "Scarf", "You'll never beat my teacher..");
                } else if (phaseTicks == 60) {
                    SimBoss.say(level.getServer(), "Scarf", "His technique.. is too advanced..");
                    toPhase(Phase.DEAD);
                }
            }
            default -> {
            }
        }
        tickShots(level);
        tickPits(level);
        tickWaves(level);
        tickBlizzard(level);
        nameTag();
    }

    private void toPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    /** A cooldown, shortened for Master Mode and for phase 2's stronger Undead. APPROXIMATE. */
    private int cd(int base) {
        double pace = (master ? 0.8 : 1.0) / (phaseTwo ? STRONGER : 1.0);
        return Math.max(10, (int) Math.round(base * pace));
    }

    private double strength() {
        return phaseTwo ? STRONGER : 1.0;
    }

    // ------------------------------------------------------------------ Scarf

    private void spawnBody(ServerLevel level) {
        BlockPos at = arena.toWorld(arena.bossSpawn);
        Zombie z = new BossBody(level);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        z.setHealth(1000);
        z.setNoAi(true);
        z.setNoGravity(true);
        z.setPersistenceRequired();
        z.setCustomNameVisible(true);
        z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD));
        double y = phase == Phase.FIGHT ? ground(level, at.getX(), at.getZ(), at.getY() + 4) : at.getY() + PERCH_HEIGHT;
        z.snapTo(at.getX() + 0.5, y, at.getZ() + 0.5, 0f, 0f);
        z.setGlowingTag(health.invulnerable);
        level.addFreshEntity(z);
        body = z;
        nameTag();
    }

    /** Sits behind his barrier over his spawn point facing the nearest player, glowing while he cannot be hurt. */
    private void perch(ServerLevel level) {
        if (body == null) {
            return;
        }
        BlockPos at = arena.toWorld(arena.bossSpawn);
        double x = at.getX() + 0.5;
        double y = at.getY() + PERCH_HEIGHT + Math.sin(now * 0.08) * 0.3;
        double z = at.getZ() + 0.5;
        float yaw = face(level, x, z, body.getYRot());
        body.snapTo(x, y, z, yaw, 0f);
        body.setYHeadRot(yaw);
        body.setYBodyRot(yaw);
        body.setGlowingTag(true);
        if (now % 10 == 0) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 0.2, z, 4, 0.4, 0.1, 0.4, 0.01);
            // The barrier in front of him: a thin sheet of light between him and the arena.
            Vec3 toward = Vec3.atBottomCenterOf(arena.toWorld(arena.centre)).subtract(x, 0, z);
            if (toward.horizontalDistanceSqr() > 0.01) {
                Vec3 d = new Vec3(toward.x, 0, toward.z).normalize();
                Vec3 side = new Vec3(-d.z, 0, d.x);
                for (int i = -4; i <= 4; i++) {
                    level.sendParticles(ParticleTypes.END_ROD, x + d.x * 2 + side.x * i * 0.6, y + 0.5,
                            z + d.z * 2 + side.z * i * 0.6, 1, 0, 0.6, 0, 0);
                }
            }
        }
    }

    /** "Those toys..." to the fight proper: Hypixel's lines, every Undead back up, and Scarf coming down. */
    private void rise(ServerLevel level) {
        if (phaseTicks < 80) {
            perch(level);
        }
        if (phaseTicks == 40) {
            SimBoss.say(level.getServer(), "Scarf", "Don't get too excited though.");
        } else if (phaseTicks == 80) {
            // Hypixel's start of phase 2, word for word: Boss Phases opens "Scarf" on it.
            SimBoss.say(level.getServer(), "Scarf", "Did you forget? I was taught by the best! Let's dance.");
            phaseTwo = true;
            for (Minion m : minions) {
                raise(level, m, m.lastPos != null ? BlockPos.containing(m.lastPos) : spawnSpot(m), true);
            }
            waveMaxHealth = waveHealth(level, true);
        } else if (phaseTicks > 80 && body != null) {
            // Floats down from behind his barrier onto the arena floor.
            BlockPos spawn = arena.toWorld(arena.bossSpawn);
            int floor = ground(level, spawn.getX(), spawn.getZ(), spawn.getY() + 4);
            double k = Math.max(0, 1 - (double) (phaseTicks - 80) / DESCEND_TICKS);
            double y = floor + (spawn.getY() + PERCH_HEIGHT - floor) * k;
            body.snapTo(spawn.getX() + 0.5, y, spawn.getZ() + 0.5, body.getYRot(), 0f);
            level.sendParticles(ParticleTypes.SOUL, body.getX(), body.getY() + 1, body.getZ(), 2, 0.3, 0.5, 0.3, 0.01);
        }
        if (phaseTicks > 80) {
            tickMinions(level);
        }
        if (phaseTicks >= 80 + DESCEND_TICKS) {
            nextMelee = 20;
            nextBarrage = 120;
            nextBlizzard = 200;
            nextTaunt = 600;
            toPhase(Phase.FIGHT);
        }
    }

    /** Phase 2: his Undead, his melee, his Skull Barrage and Soul Sand Blizzard. */
    private void fight(ServerLevel level) {
        if (body == null) {
            return;
        }
        tickMinions(level);
        // Master Mode: immune until at least four Undead are dead (the wiki); the rest is in hit().
        boolean shielded = master && deadCount() < 4;
        health.invulnerable = shielded;
        body.setGlowingTag(shielded);

        if (barrageTicks >= 0) {
            skullBarrage(level);
        } else {
            chase(level);
            if (--nextBarrage <= 0) {
                barrageTicks = 0;
                SimBoss.say(level.getServer(), "Scarf", pick(BARRAGE_LINES));
            }
            if (--nextBlizzard <= 0) {
                blizzard(level);
                nextBlizzard = master ? 180 : 240; // APPROXIMATE
            }
        }
        if (--nextTaunt <= 0) {
            SimBoss.say(level.getServer(), "Scarf", pick(TAUNT_LINES));
            nextTaunt = 600 + ThreadLocalRandom.current().nextInt(400);
        }
        if (!saidAlone && allDown()) {
            saidAlone = true;
            SimBoss.say(level.getServer(), "Scarf", "Fine then! I'll take you myself!");
        }
        if (health.hp <= 0) {
            die(level);
        }
    }

    private void die(ServerLevel level) {
        SimBoss.say(level.getServer(), "Scarf", "Whatever...");
        if (body != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, body.getX(), body.getY() + 1, body.getZ(), 3,
                    0.5, 0.5, 0.5, 0);
            body.discard();
            body = null;
        }
        clearAll(level);
        toPhase(Phase.DYING);
    }

    /** Walks at the nearest player and hits him, the way his Warrior does. */
    private void chase(ServerLevel level) {
        ServerPlayer target = nearest(level, body.position());
        if (target == null) {
            return;
        }
        double d = horizontal(body.position(), target.position());
        if (d > 1.8) {
            Vec3 next = step(level, body.position(), target.position(), master ? 0.22 : 0.18, 0);
            if (next != null) {
                body.snapTo(next.x, next.y, next.z, body.getYRot(), 0f);
            }
        }
        turn(body, target.getX() - body.getX(), target.getZ() - body.getZ());
        if (nextMelee > 0) {
            nextMelee--;
        } else if (d < 2.6 && Math.abs(target.getY() - body.getY()) < 2.5) {
            body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            hurtPlayer(level, target, scarfDamage(), target.getX() - body.getX(), target.getZ() - body.getZ(),
                    0.6, 0.3);
            nextMelee = 20; // APPROXIMATE: a swing a second
            if (now - lastHurtLine > 200 && ThreadLocalRandom.current().nextInt(4) == 0) {
                lastHurtLine = now;
                SimBoss.say(level.getServer(), "Scarf", "Back off!");
            }
        }
    }

    /**
     * Skull Barrage: he rises a little off the ground and fires explosive skulls in a wide arc at a player, a fan
     * every half second or so. Wide enough that many miss - and land on whoever stands near the target. The count,
     * arc and timing are APPROXIMATE.
     */
    private void skullBarrage(ServerLevel level) {
        barrageTicks++;
        int floor = ground(level, (int) Math.floor(body.getX()), (int) Math.floor(body.getZ()),
                (int) Math.floor(body.getY()) + 1);
        double y = body.getY() + Math.max(-0.2, Math.min(0.2, floor + 2.0 - body.getY()));
        ServerPlayer target = nearest(level, body.position());
        if (target != null) {
            turn(body, target.getX() - body.getX(), target.getZ() - body.getZ());
        }
        body.snapTo(body.getX(), y, body.getZ(), body.getYRot(), 0f);
        level.sendParticles(ParticleTypes.SOUL, body.getX(), body.getY(), body.getZ(), 2, 0.3, 0.1, 0.3, 0.01);
        int waves = master ? 6 : 4;
        if (barrageTicks >= 15 && (barrageTicks - 15) % 12 == 0 && (barrageTicks - 15) / 12 < waves) {
            ServerPlayer aim = randomPlayer(level);
            if (aim != null) {
                Vec3 from = body.position().add(0, 1.6, 0);
                Vec3 dir = aim.position().add(0, 1.0, 0).subtract(from);
                if (dir.lengthSqr() > 0.01) {
                    dir = dir.normalize();
                    int n = master ? 9 : 7;
                    Set<UUID> fan = new HashSet<>();
                    for (int i = 0; i < n; i++) {
                        double spread = Math.toRadians(-55 + 110.0 * i / (n - 1));
                        Vec3 d = rotate(dir, spread);
                        shots.add(new Shot(from, d.scale(0.8), scarfSkull(), 2.5, false, fan));
                    }
                    body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                    level.playSound(null, from.x, from.y, from.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE,
                            0.8f, 1f);
                }
            }
        }
        if (barrageTicks >= 15 + waves * 12 + 10) {
            barrageTicks = -1;
            nextBarrage = master ? 220 : 300; // APPROXIMATE
            nextMelee = 10;
        }
    }

    /** Soul Sand Blizzard: a trail of soul sand from Scarf running at a player. */
    private void blizzard(ServerLevel level) {
        ServerPlayer target = randomPlayer(level);
        if (target == null) {
            return;
        }
        trails.add(new Trail(body.position(), target.getUUID(), 24));
        level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.SOUL_ESCAPE.value(),
                SoundSource.HOSTILE, 1.5f, 0.6f);
        body.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
    }

    private void tickBlizzard(ServerLevel level) {
        if (!trails.isEmpty() && now % 2 == 0) {
            for (var it = trails.iterator(); it.hasNext(); ) {
                Trail tr = it.next();
                if (--tr.steps < 0) {
                    it.remove();
                    continue;
                }
                // Homes on its player, a block a step.
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(tr.target);
                if (p != null) {
                    Vec3 to = p.position().subtract(tr.at);
                    double len = Math.sqrt(to.x * to.x + to.z * to.z);
                    if (len > 0.5) {
                        tr.at = tr.at.add(to.x / len, 0, to.z / len);
                    }
                }
                laySand(level, tr.at);
                // A little either side, so it cannot be stepped over.
                laySand(level, tr.at.add(0.8, 0, 0));
                laySand(level, tr.at.add(0, 0, 0.8));
            }
        }
        if (sands.isEmpty()) {
            return;
        }
        for (var it = sands.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().meltAt() <= now) {
                if (level.getBlockState(e.getKey()).is(Blocks.SOUL_SAND)) {
                    level.setBlock(e.getKey(), e.getValue().original(), 3);
                }
                it.remove();
            } else if (now % 8 == 0) {
                BlockPos b = e.getKey();
                level.sendParticles(ParticleTypes.SNOWFLAKE, b.getX() + 0.5, b.getY() + 1.2, b.getZ() + 0.5, 1,
                        0.3, 0.2, 0.3, 0.01);
            }
        }
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            BlockPos under = BlockPos.containing(p.getX(), p.getY() - 0.3, p.getZ());
            if (!sands.containsKey(under)) {
                continue;
            }
            Integer last = lastSandHit.get(p.getUUID());
            if (last != null && now - last < 10) {
                continue;
            }
            lastSandHit.put(p.getUUID(), now);
            // A share of max health as true damage (the share is APPROXIMATE), and Slowness.
            SimVitals.dealMaxHealthShare(level, p, level.damageSources().magic(), SAND_SHARE, true);
            p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 40, 1));
        }
    }

    /** Turns the floor block under a spot to soul sand for a few seconds. */
    private void laySand(ServerLevel level, Vec3 at) {
        int feet = ground(level, (int) Math.floor(at.x), (int) Math.floor(at.z), (int) Math.floor(at.y) + 2);
        BlockPos floor = new BlockPos((int) Math.floor(at.x), feet - 1, (int) Math.floor(at.z));
        int melt = now + 120; // APPROXIMATE: six seconds
        Sand had = sands.get(floor);
        if (had != null) {
            sands.put(floor, new Sand(had.original(), melt));
            return;
        }
        BlockState state = level.getBlockState(floor);
        if (state.isAir() || state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, floor)
                || !inArena(floor.getX() + 0.5, floor.getZ() + 0.5)) {
            return;
        }
        sands.put(floor, new Sand(state, melt));
        level.setBlock(floor, Blocks.SOUL_SAND.defaultBlockState(), 3);
        level.sendParticles(ParticleTypes.SOUL, floor.getX() + 0.5, floor.getY() + 1.1, floor.getZ() + 0.5, 2,
                0.3, 0.1, 0.3, 0.01);
    }

    private void restoreSand(ServerLevel level) {
        for (var e : sands.entrySet()) {
            if (level.getBlockState(e.getKey()).is(Blocks.SOUL_SAND)) {
                level.setBlock(e.getKey(), e.getValue().original(), 3);
            }
        }
        sands.clear();
        trails.clear();
    }

    // ------------------------------------------------------------------ the Undead

    private BlockPos spawnSpot(Minion m) {
        BlockPos c = arena.toWorld(arena.centre);
        return c.offset(m.kind.dx + m.copy * 2, 0, m.kind.dz + m.copy * 2);
    }

    /** Raises one Undead with its pool and its kit; phase 2's come back stronger. */
    private void raise(ServerLevel level, Minion m, BlockPos at, boolean stronger) {
        String floor = SimState.floorLabel();
        Mob mob = switch (m.kind) {
            case ARCHER -> {
                Archer s = new Archer(McEntities.SKELETON, level);
                s.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                yield s;
            }
            case WARRIOR -> {
                UndeadZombie z = new UndeadZombie(level);
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(master ? 0.32 : 0.29);
                yield z;
            }
            case PRIEST -> {
                UndeadZombie z = new UndeadZombie(level);
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_HOE));
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.22);
                yield z;
            }
            case MAGE -> {
                UndeadZombie z = new UndeadZombie(level);
                z.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BLAZE_ROD));
                z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
                z.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.22);
                yield z;
            }
        };
        mob.setPersistenceRequired();
        var attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(SimHealth.meleeDamage(floor, SimHealth.Role.STARRED));
        }
        var range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(32);
        }
        var reinforcements = mob.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        if (reinforcements != null) {
            reinforcements.setBaseValue(0);
        }
        if (stronger) {
            var speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(speed.getBaseValue() * 1.1); // APPROXIMATE
            }
        }
        if (mob instanceof Archer s) {
            s.reassessWeaponGoal();
        }
        mob.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 180f, 0f);
        ServerPlayer target = nearest(level, mob.position());
        if (target != null) {
            mob.setTarget(target);
        }
        if (!level.addFreshEntity(mob)) {
            return;
        }
        // Registered by its Hypixel name, the pool takes the wiki's health and hit (SimHealth.statsFor).
        SimHealth.Pool pool = SimHealth.register(mob, SimHealth.maxHealth(floor, SimHealth.Role.STARRED),
                m.kind.display, false, null);
        if (stronger && pool != null) {
            pool.hit *= STRONGER;
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, mob.getX(), mob.getY() + 1, mob.getZ(), 15, 0.3, 0.6, 0.3,
                0.02);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE,
                SoundSource.HOSTILE, 0.5f, 1.4f);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        m.id = mob.getUUID();
        m.alive = true;
        m.lastHp = -1;
        m.damageBy.clear();
        m.rooted = 0;
        m.windup = -1;
        m.rushing = -1;
        // Staggered so the four do not all go off on the same tick.
        m.nextA = 60 + rnd.nextInt(60);
        m.nextB = 120 + rnd.nextInt(80);
        m.nextC = 180 + rnd.nextInt(100);
        m.nextHelp = 100;
    }

    private static Mob mob(ServerLevel level, Minion m) {
        return m.id != null && level.getEntity(m.id) instanceof Mob mob && mob.isAlive() ? mob : null;
    }

    /** Deaths, damage bookkeeping and every Undead's abilities. */
    private void tickMinions(ServerLevel level) {
        for (Minion m : minions) {
            if (!m.alive) {
                continue;
            }
            Mob mob = mob(level, m);
            if (mob == null) {
                died(level, m);
                continue;
            }
            m.lastPos = mob.position();
            if (!m.charging() && m.rooted <= 0 && mob.getTarget() == null) {
                ServerPlayer target = nearest(level, mob.position());
                if (target != null) {
                    mob.setTarget(target);
                }
            }
            SimHealth.Pool pool = SimHealth.get(mob);
            if (pool != null) {
                if (m.lastHp >= 0 && pool.hp < m.lastHp) {
                    ServerPlayer by = likelyAttacker(level, mob);
                    if (by != null) {
                        m.damageBy.merge(by.getUUID(), m.lastHp - pool.hp, Double::sum);
                    }
                    m.lastHurt = now;
                }
                m.lastHp = pool.hp;
            }
            switch (m.kind) {
                case WARRIOR -> warrior(level, m, mob);
                case ARCHER -> archer(level, m, mob);
                case MAGE -> mage(level, m, mob);
                case PRIEST -> priest(level, m, mob);
            }
            if (m.kind != Guardian.WARRIOR) {
                help(level, m);
            }
        }
    }

    private void died(ServerLevel level, Minion m) {
        m.alive = false;
        m.windup = -1;
        m.rushing = -1;
        m.rooted = 0;
        if (now - lastKillLine > 40) {
            lastKillLine = now;
            SimBoss.say(level.getServer(), "Scarf", String.format(Locale.ROOT, pick(KILL_LINES), m.kind.display));
        }
        if (phaseTwo && m.kind == Guardian.PRIEST && !saidPriest && !anyAlive(Guardian.PRIEST)) {
            saidPriest = true;
            SimBoss.say(level.getServer(), "Scarf", "Where is my Priest!");
        }
    }

    /**
     * The player who most likely landed the hit on {@code mob}: the pools do not record who hit them, so the one
     * looking straight at it (or, failing that, the nearest) takes the credit. APPROXIMATE, and exact solo.
     */
    private static ServerPlayer likelyAttacker(ServerLevel level, Mob mob) {
        ServerPlayer best = null;
        double bestScore = -Double.MAX_VALUE;
        Vec3 at = mob.position().add(0, mob.getBbHeight() / 2, 0);
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            Vec3 to = at.subtract(p.getEyePosition());
            double dist = to.length();
            double aim = dist < 0.01 ? 1 : p.getLookAngle().dot(to.scale(1 / dist));
            double score = aim - dist * 0.02;
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** Help: the Archer, the Mage and the Priest call a Warrior to Charge whoever has hurt them most. */
    private void help(ServerLevel level, Minion m) {
        if (--m.nextHelp > 0 || m.damageBy.isEmpty() || now - m.lastHurt > 60) {
            return;
        }
        UUID top = null;
        double most = 0;
        for (var e : m.damageBy.entrySet()) {
            if (e.getValue() > most) {
                most = e.getValue();
                top = e.getKey();
            }
        }
        ServerPlayer target = top == null ? null : level.getServer().getPlayerList().getPlayer(top);
        Minion warrior = freeWarrior(level);
        if (target == null || warrior == null || !SimBoss.playersInRing(level).contains(target)) {
            return;
        }
        m.nextHelp = cd(240); // APPROXIMATE
        startCharge(level, warrior, mob(level, warrior), target, 20);
        if (phaseTwo && now - lastCallLine > 200) {
            lastCallLine = now;
            SimBoss.say(level.getServer(), "Scarf", "Warrior! Charge this fool!");
        }
    }

    private Minion freeWarrior(ServerLevel level) {
        for (Minion w : minions) {
            if (w.kind == Guardian.WARRIOR && w.alive && !w.charging() && mob(level, w) != null) {
                return w;
            }
        }
        return null;
    }

    // ---- the Warrior

    private void warrior(ServerLevel level, Minion m, Mob mob) {
        if (m.charging()) {
            tickCharge(level, m, mob);
            return;
        }
        if (--m.nextA <= 0) {
            ServerPlayer target = mob.getTarget() instanceof ServerPlayer sp ? sp : nearest(level, mob.position());
            if (target != null && target.distanceToSqr(mob) < 20 * 20) {
                startCharge(level, m, mob, target, 20);
            }
            m.nextA = cd(160); // APPROXIMATE
        }
    }

    /** Stops, roars and shows the line he will run down; then {@link #tickCharge} runs it. */
    private void startCharge(ServerLevel level, Minion m, Mob mob, ServerPlayer target, int windup) {
        if (mob == null) {
            return;
        }
        m.windup = windup;
        m.rushing = -1;
        m.chargeTarget = target.getUUID();
        m.chargeDir = null;
        mob.setNoAi(true);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE,
                0.8f, 1.3f);
    }

    private void tickCharge(ServerLevel level, Minion m, Mob mob) {
        if (m.windup >= 0) {
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(m.chargeTarget);
            if (target != null) {
                Vec3 to = target.position().subtract(mob.position());
                double len = Math.sqrt(to.x * to.x + to.z * to.z);
                if (len > 0.1) {
                    // Tracks him through the wind-up, then locks for the last few ticks: that is the dodge.
                    if (m.windup > 6 || m.chargeDir == null) {
                        m.chargeDir = new Vec3(to.x / len, 0, to.z / len);
                    }
                    turn(mob, m.chargeDir.x, m.chargeDir.z);
                }
            }
            if (m.chargeDir != null && now % 2 == 0) {
                DustParticleOptions red = new DustParticleOptions(m.windup <= 6 ? 0xFF2020 : 0xFF9090, 1f);
                for (int i = 1; i <= 12; i++) {
                    level.sendParticles(red, mob.getX() + m.chargeDir.x * i, mob.getY() + 0.2,
                            mob.getZ() + m.chargeDir.z * i, 1, 0, 0, 0, 0);
                }
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getY() + 2.2, mob.getZ(), 1,
                        0.2, 0.1, 0.2, 0);
            }
            if (--m.windup < 0) {
                if (m.chargeDir == null) {
                    endCharge(m, mob);
                    return;
                }
                m.rushing = 14; // APPROXIMATE: about 12 blocks
                m.chargeHit.clear();
            }
            return;
        }
        Vec3 next = step(level, mob.position(), mob.position().add(m.chargeDir.scale(5)), 0.9, 1.2);
        if (next == null || --m.rushing < 0) {
            endCharge(m, mob);
            return;
        }
        mob.snapTo(next.x, next.y, next.z, mob.getYRot(), 0f);
        level.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + 0.2, mob.getZ(), 3, 0.2, 0.1, 0.2, 0.02);
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            if (!m.chargeHit.contains(p.getUUID()) && horizontal(p.position(), mob.position()) < 1.5
                    && Math.abs(p.getY() - mob.getY()) < 2) {
                m.chargeHit.add(p.getUUID());
                hurtPlayer(level, p, chargeDamage() * strength(), m.chargeDir.x, m.chargeDir.z, 1.4, 0.5);
            }
        }
    }

    private static void endCharge(Minion m, Mob mob) {
        m.windup = -1;
        m.rushing = -1;
        mob.setNoAi(false);
    }

    // ---- the Archer

    private void archer(ServerLevel level, Minion m, Mob mob) {
        if (--m.nextA <= 0) {
            arrowBarrage(level, mob);
            m.nextA = cd(200); // APPROXIMATE
        }
        if (--m.nextB <= 0) {
            m.nextB = cd(220); // APPROXIMATE
            ServerPlayer close = nearest(level, mob.position());
            if (close != null && close.distanceToSqr(mob) < 5 * 5) {
                // Away from danger.
                Vec3 to = floorSpot(level, close.position(), 9, 14);
                if (to != null) {
                    blink(level, mob.position(), to);
                    mob.snapTo(to.x, to.y, to.z, mob.getYRot(), 0f);
                    mob.getNavigation().stop();
                }
            } else {
                // A player into danger: in front of a Warrior, who Charges him.
                Minion warrior = freeWarrior(level);
                ServerPlayer victim = randomPlayer(level);
                Mob wMob = warrior == null ? null : mob(level, warrior);
                if (wMob != null && victim != null && ThreadLocalRandom.current().nextBoolean()) {
                    Vec3 to = floorSpot(level, wMob.position(), 5, 7);
                    if (to != null) {
                        blink(level, victim.position(), to);
                        victim.teleportTo(level, to.x, to.y, to.z, Set.of(), victim.getYRot(), victim.getXRot(),
                                false);
                        startCharge(level, warrior, wMob, victim, 12);
                    }
                }
            }
        }
    }

    /** Arrows out in every direction from the Archer. Half its own hit each (APPROXIMATE: no wiki number). */
    private void arrowBarrage(ServerLevel level, Mob mob) {
        double damage = SimHealth.hitOf(mob) * 0.5;
        if (damage <= 0) {
            damage = SimHealth.byMode(2_752, 51_600) * 0.5;
        }
        Vec3 from = mob.position().add(0, 1.5, 0);
        double off = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
        int n = 24;
        Set<UUID> volley = new HashSet<>();
        for (int i = 0; i < n; i++) {
            double a = off + Math.PI * 2 * i / n;
            Vec3 dir = new Vec3(Math.cos(a), 0.04, Math.sin(a)).normalize();
            shots.add(new Shot(from, dir.scale(1.1), damage, 0, true, volley));
        }
        mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.2f, 0.8f);
    }

    // ---- the Mage

    private void mage(ServerLevel level, Minion m, Mob mob) {
        if (m.rooted > 0) {
            // Stands still while it fires its skulls.
            m.rooted--;
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(m.rootTarget);
            if (target != null) {
                turn(mob, target.getX() - mob.getX(), target.getZ() - mob.getZ());
                if (m.rooted % 8 == 4) {
                    mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                    Vec3 from = mob.position().add(0, 1.5, 0);
                    Vec3 dir = target.position().add(0, 1.0, 0).subtract(from);
                    if (dir.lengthSqr() > 0.01) {
                        shots.add(new Shot(from, dir.normalize().scale(0.7), mageSkull() * strength(), 0, false));
                        level.playSound(null, from.x, from.y, from.z, SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE,
                                0.5f, 1.3f);
                    }
                }
            }
            if (m.rooted <= 0) {
                mob.setNoAi(false);
            }
            return;
        }
        ServerPlayer target = nearest(level, mob.position());
        if (target == null || target.distanceToSqr(mob) > 24 * 24) {
            return;
        }
        if (--m.nextA <= 0) {
            m.nextA = cd(90); // APPROXIMATE
            m.rooted = master ? 26 : 18;
            m.rootTarget = target.getUUID();
            mob.setNoAi(true);
            return;
        }
        if (--m.nextB <= 0) {
            m.nextB = cd(240); // APPROXIMATE
            // Magma Pit: a warning ring under him, then burning ground.
            int feet = ground(level, (int) Math.floor(target.getX()), (int) Math.floor(target.getZ()),
                    (int) Math.floor(target.getY()) + 1);
            Vec3 at = new Vec3(target.getX(), feet, target.getZ());
            pits.add(new Pit(at, master ? 3.0 : 2.5, now + 25, now + 125, magmaPit() * strength()));
            mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.LAVA_AMBIENT, SoundSource.HOSTILE, 1.2f, 0.8f);
        }
        if (--m.nextC <= 0) {
            m.nextC = cd(300); // APPROXIMATE
            Vec3 to = target.position().subtract(mob.position());
            double len = Math.sqrt(to.x * to.x + to.z * to.z);
            if (len > 0.1) {
                waves.add(new Wave(mob.position(), new Vec3(to.x / len, 0, to.z / len), tsunami() * strength()));
                mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GENERIC_SPLASH,
                        SoundSource.HOSTILE, 1.5f, 0.6f);
            }
        }
    }

    // ---- the Priest

    private void priest(ServerLevel level, Minion m, Mob mob) {
        if (--m.nextA > 0) {
            return;
        }
        m.nextA = cd(120); // APPROXIMATE
        // Heals itself and the Undead near it a share of their health (APPROXIMATE: the wiki has no amount).
        double share = master ? 0.1 : 0.08;
        for (Minion other : minions) {
            Mob o = other.alive ? mob(level, other) : null;
            SimHealth.Pool pool = o == null ? null : SimHealth.get(o);
            if (pool == null || pool.hp >= pool.max || o.distanceToSqr(mob) > 12 * 12) {
                continue;
            }
            pool.heal(pool.max * share);
            other.lastHp = pool.hp;
            level.sendParticles(ParticleTypes.HEART, o.getX(), o.getY() + 2, o.getZ(), 4, 0.4, 0.3, 0.4, 0.1);
        }
        // And Scarf, once he is fighting (APPROXIMATE: 2% of his health).
        if (phase == Phase.FIGHT && body != null && body.distanceToSqr(mob) < 12 * 12 && health.hp < health.max) {
            health.hp = Math.min(health.max, health.hp + health.max * 0.02);
            level.sendParticles(ParticleTypes.HEART, body.getX(), body.getY() + 2, body.getZ(), 6, 0.4, 0.3, 0.4,
                    0.1);
        }
        mob.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mob.getX(), mob.getY() + 1, mob.getZ(), 20,
                1.5, 0.8, 1.5, 0.1);
    }

    private boolean anyAlive(Guardian kind) {
        for (Minion m : minions) {
            if (m.kind == kind && m.alive) {
                return true;
            }
        }
        return false;
    }

    private boolean allDown() {
        return deadCount() == minions.size();
    }

    private int deadCount() {
        int n = 0;
        for (Minion m : minions) {
            if (!m.alive) {
                n++;
            }
        }
        return n;
    }

    /** The living Undead's health, summed; their max when {@code max}. */
    private double waveHealth(ServerLevel level, boolean max) {
        double sum = 0;
        for (Minion m : minions) {
            Mob mob = m.alive ? mob(level, m) : null;
            SimHealth.Pool pool = mob == null ? null : SimHealth.get(mob);
            if (pool != null) {
                sum += max ? pool.max : Math.max(0, pool.hp);
            }
        }
        return sum;
    }

    private void discardMinions(ServerLevel level) {
        for (Minion m : minions) {
            if (m.id != null) {
                Entity e = level.getEntity(m.id);
                if (e != null) {
                    e.discard();
                }
            }
            m.alive = false;
            m.id = null;
        }
    }

    // ------------------------------------------------------------------ shots, pits, waves

    private void tickShots(ServerLevel level) {
        if (shots.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        List<Shot> done = new ArrayList<>();
        for (Shot s : shots) {
            boolean gone = --s.life <= 0;
            // Two half steps a tick, so a shot cannot skip over a player or through a thin wall.
            for (int step = 0; step < 2 && !gone; step++) {
                s.pos = s.pos.add(s.vel.scale(0.5));
                BlockPos bp = BlockPos.containing(s.pos);
                if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                    if (s.blast > 0) {
                        explode(level, s.pos, s.blast, s.damage, s.volley);
                    } else {
                        level.sendParticles(ParticleTypes.SMOKE, s.pos.x, s.pos.y, s.pos.z, 4, 0.1, 0.1, 0.1, 0.02);
                    }
                    gone = true;
                    break;
                }
                for (ServerPlayer p : players) {
                    if (p.position().add(0, 1.0, 0).distanceToSqr(s.pos) <= SHOT_HIT_RADIUS * SHOT_HIT_RADIUS) {
                        if (s.blast > 0) {
                            explode(level, s.pos, s.blast, s.damage, s.volley);
                        } else if (s.volley == null || s.volley.add(p.getUUID())) {
                            hurtPlayer(level, p, s.damage, s.vel.x, s.vel.z, 0.4, 0.2);
                        }
                        gone = true;
                        break;
                    }
                }
            }
            if (gone) {
                done.add(s);
                continue;
            }
            if (s.arrow) {
                level.sendParticles(ParticleTypes.CRIT, s.pos.x, s.pos.y, s.pos.z, 1, 0, 0, 0, 0);
            } else {
                level.sendParticles(ParticleTypes.LARGE_SMOKE, s.pos.x, s.pos.y, s.pos.z, 1, 0.05, 0.05, 0.05, 0.01);
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, s.pos.x, s.pos.y, s.pos.z, 1, 0.05, 0.05, 0.05,
                        0.01);
            }
        }
        shots.removeAll(done);
    }

    /** A skull's blast: hurts everyone in {@code radius}, once per player per {@code volley} (null: no limit). */
    private static void explode(ServerLevel level, Vec3 at, double radius, double damage, Set<UUID> volley) {
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, radius / 3, 0.3, radius / 3, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 0.8f, 1.2f);
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            Vec3 d = p.position().add(0, 1.0, 0).subtract(at);
            if (d.lengthSqr() <= radius * radius && (volley == null || volley.add(p.getUUID()))) {
                hurtPlayer(level, p, damage, d.x, d.z, 0.8, 0.5);
            }
        }
    }

    private void tickPits(ServerLevel level) {
        if (pits.isEmpty()) {
            return;
        }
        DustParticleOptions orange = new DustParticleOptions(0xFF6010, 1.2f);
        for (var it = pits.iterator(); it.hasNext(); ) {
            Pit p = it.next();
            if (now >= p.endAt()) {
                it.remove();
                continue;
            }
            if (now < p.burnAt()) {
                if (now % 3 == 0) {
                    int n = (int) Math.max(16, p.radius() * 6);
                    for (int i = 0; i < n; i++) {
                        double a = Math.PI * 2 * i / n;
                        level.sendParticles(orange, p.at().x + Math.cos(a) * p.radius(), p.at().y + 0.1,
                                p.at().z + Math.sin(a) * p.radius(), 1, 0, 0, 0, 0);
                    }
                }
                continue;
            }
            if (now % 4 == 0) {
                level.sendParticles(ParticleTypes.LAVA, p.at().x, p.at().y + 0.1, p.at().z, 3, p.radius() / 2, 0,
                        p.radius() / 2, 0);
                level.sendParticles(ParticleTypes.FLAME, p.at().x, p.at().y + 0.2, p.at().z, 6, p.radius() / 2,
                        0.1, p.radius() / 2, 0.01);
            }
            // Burns every half second while you stand in it (APPROXIMATE rate).
            if ((now - p.burnAt()) % 10 == 0) {
                for (ServerPlayer pl : SimBoss.playersInRing(level)) {
                    if (horizontal(pl.position(), p.at()) <= p.radius() && Math.abs(pl.getY() - p.at().y) < 2) {
                        SimVitals.dealHypixel(level, pl, level.damageSources().magic(), p.damage(), false);
                    }
                }
            }
        }
    }

    private void tickWaves(ServerLevel level) {
        if (waves.isEmpty()) {
            return;
        }
        double half = 2.5;
        for (var it = waves.iterator(); it.hasNext(); ) {
            Wave w = it.next();
            w.dist += 0.55;
            if (w.dist > 22) {
                it.remove();
                continue;
            }
            Vec3 side = new Vec3(-w.dir.z, 0, w.dir.x);
            Vec3 front = w.origin.add(w.dir.scale(w.dist));
            for (double s = -half; s <= half; s += 0.5) {
                level.sendParticles(ParticleTypes.SPLASH, front.x + side.x * s, front.y + 0.6, front.z + side.z * s,
                        2, 0.1, 0.4, 0.1, 0);
            }
            if (now % 2 == 0) {
                level.sendParticles(ParticleTypes.BUBBLE_POP, front.x, front.y + 1, front.z, 6, half / 2, 0.4,
                        half / 2, 0.02);
            }
            for (ServerPlayer p : SimBoss.playersInRing(level)) {
                if (w.hit.contains(p.getUUID())) {
                    continue;
                }
                Vec3 rel = p.position().subtract(w.origin);
                double along = rel.x * w.dir.x + rel.z * w.dir.z;
                double across = rel.x * side.x + rel.z * side.z;
                if (Math.abs(along - w.dist) < 0.9 && Math.abs(across) <= half && Math.abs(rel.y) < 2.5) {
                    w.hit.add(p.getUUID());
                    hurtPlayer(level, p, w.damage, w.dir.x, w.dir.z, 1.3, 0.45);
                }
            }
        }
    }

    // ------------------------------------------------------------------ shared bits

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

    /**
     * One step of {@code speed} blocks from {@code from} toward {@code to}, onto the ground there; null if that
     * would climb or drop more than {@code maxRise} (0: a block, the way a walker steps up) or leave the arena.
     */
    private Vec3 step(ServerLevel level, Vec3 from, Vec3 to, double speed, double maxRise) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.01) {
            return null;
        }
        double s = Math.min(d, speed);
        double nx = from.x + dx / d * s;
        double nz = from.z + dz / d * s;
        if (!inArena(nx, nz)) {
            return null;
        }
        int y = ground(level, (int) Math.floor(nx), (int) Math.floor(nz), (int) Math.floor(from.y) + 1);
        double rise = maxRise > 0 ? maxRise : 1.5;
        if (Math.abs(y - from.y) > rise || !open(level, nx, y, nz)) {
            return null;
        }
        return new Vec3(nx, y, nz);
    }

    /** Whether the two blocks from {@code y} up at a column are free to stand in (a wall taller than a hop is not). */
    private static boolean open(ServerLevel level, double x, int y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    /** The standing height at a column: the first open block with something solid under it, from {@code fromY} down. */
    private static int ground(ServerLevel level, int x, int z, int fromY) {
        for (int y = fromY; y > fromY - 12; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return y;
            }
        }
        return fromY;
    }

    /** A spot on open floor {@code min}-{@code max} blocks from {@code around}, inside the arena; null if none. */
    private Vec3 floorSpot(ServerLevel level, Vec3 around, double min, double max) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int floorY = arena.toWorld(arena.centre).getY();
        for (int attempt = 0; attempt < 10; attempt++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * (max - min);
            double x = around.x + Math.cos(a) * d;
            double z = around.z + Math.sin(a) * d;
            if (!inArena(x, z)) {
                continue;
            }
            int y = ground(level, (int) Math.floor(x), (int) Math.floor(z), floorY + 3);
            if (Math.abs(y - floorY) <= 3 && open(level, x, y, z)) {
                return new Vec3(Math.floor(x) + 0.5, y, Math.floor(z) + 0.5);
            }
        }
        return null;
    }

    private boolean inArena(double x, double z) {
        BlockPos c = arena.toWorld(arena.centre);
        double dx = x - (c.getX() + 0.5);
        double dz = z - (c.getZ() + 0.5);
        double r = arena.radius - 1;
        return dx * dx + dz * dz <= r * r;
    }

    private static void blink(ServerLevel level, Vec3 from, Vec3 to) {
        level.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1, from.z, 30, 0.4, 0.8, 0.4, 0.3);
        level.sendParticles(ParticleTypes.PORTAL, to.x, to.y + 1, to.z, 30, 0.4, 0.8, 0.4, 0.3);
        level.playSound(null, to.x, to.y, to.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1f, 0.8f);
    }

    private static Vec3 rotate(Vec3 v, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos).normalize();
    }

    private static void turn(Mob mob, double dx, double dz) {
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    private float face(ServerLevel level, double x, double z, float fallback) {
        ServerPlayer target = nearest(level, new Vec3(x, 0, z));
        if (target == null) {
            return fallback;
        }
        return (float) (Math.toDegrees(Math.atan2(target.getZ() - z, target.getX() - x)) - 90);
    }

    private static ServerPlayer nearest(ServerLevel level, Vec3 from) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            double dx = p.getX() - from.x;
            double dz = p.getZ() - from.z;
            double d = dx * dx + dz * dz;
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
        Component tag = Component.literal("Scarf ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(health.invulnerable ? "???" : SimBoss.shortHealth(health.hp))
                        .withStyle(ChatFormatting.GREEN).append(Component.literal("❤")
                                .withStyle(ChatFormatting.RED)));
        body.setCustomName(tag);
    }

    private void clearAll(ServerLevel level) {
        discardMinions(level);
        shots.clear();
        pits.clear();
        waves.clear();
        restoreSand(level);
        barrageTicks = -1;
    }

    @Override
    boolean isBoss(Entity entity) {
        return entity == body;
    }

    /**
     * A hit on Scarf. On Master Mode his damage resistance scales with how many Undead are alive (the wiki); here
     * the share that lands is the share of them that is dead (APPROXIMATE), none until four are.
     */
    @Override
    boolean hit(ServerLevel level, LivingEntity hitBody, float amount) {
        if (phase == Phase.FIGHT && !health.invulnerable) {
            double taken = master ? (double) deadCount() / minions.size() : 1.0;
            float dealt = (float) (amount * taken);
            if (!health.hit(dealt)) {
                return false;
            }
            if (dealt >= health.max * 0.02 && anyAlive(Guardian.PRIEST) && now - lastHealLine > 200) {
                // "After dealing major damage to Scarf" (the threshold is APPROXIMATE) - and his Priest heals.
                lastHealLine = now;
                SimBoss.say(level.getServer(), "Scarf", "That was nothing my Priest can't heal!");
                for (Minion m : minions) {
                    if (m.kind == Guardian.PRIEST && m.alive) {
                        m.nextA = 0;
                    }
                }
            } else if (health.hp > 0 && now - lastHurtLine > 160 && ThreadLocalRandom.current().nextInt(4) == 0) {
                lastHurtLine = now;
                SimBoss.say(level.getServer(), "Scarf", pick(HURT_LINES));
            }
            return true;
        }
        if (phase != Phase.DEAD && phase != Phase.DYING && now - lastImmuneNote > 60) {
            lastImmuneNote = now;
            SimBoss.tell(level.getServer(), ModChat.text(phase == Phase.FIGHT
                    ? "Scarf is immune until 4 of his Undead are dead."
                    : "Scarf can't be hurt while his Undead stand."));
        }
        return false;
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
        clearAll(level);
    }

    /** While his Undead fight the bar is theirs: "Scarf's Undead (3/4)", their summed health. */
    private boolean waveBar() {
        return phase == Phase.MINIONS || (phase == Phase.FIGHT && health.invulnerable);
    }

    @Override
    Component barName() {
        if (waveBar()) {
            int up = minions.size() - deadCount();
            return Component.literal("Scarf's Undead (" + up + "/" + minions.size() + ")")
                    .withStyle(ChatFormatting.GOLD);
        }
        return Component.literal("Scarf").withStyle(ChatFormatting.RED);
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return waveBar() ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED;
    }

    @Override
    float barProgress() {
        if (phase == Phase.INTRO || phase == Phase.RISE) {
            return 1f;
        }
        if (waveBar() && body != null && body.level() instanceof ServerLevel level) {
            return waveMaxHealth <= 0 ? 0f : (float) (waveHealth(level, false) / waveMaxHealth);
        }
        return health.fraction();
    }

    /** Scarf's body: no AI (the fight moves him), never despawns, never burns in the sim's daylight. */
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
     * The Warrior, the Priest and the Mage: zombies that never despawn or burn, and drop nothing they are holding
     * (an Undead Warrior's iron sword is not loot).
     */
    private static final class UndeadZombie extends Zombie {
        UndeadZombie(Level level) {
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

        @Override
        public void die(DamageSource source) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                setItemSlot(slot, ItemStack.EMPTY);
            }
            super.die(source);
        }
    }

    /** The Archer: a skeleton with a bow whose arrows hit for the floor, like the room skeletons. */
    private static final class Archer extends Skeleton {
        Archer(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        /** Half the attack damage times the bow's speed lands about where a zombie's swing does (see SimMobs). */
        @Override
        protected net.minecraft.world.entity.projectile.arrow.AbstractArrow getArrow(ItemStack arrow, float power,
                                                                                    ItemStack weapon) {
            var shot = super.getArrow(arrow, power, weapon);
            shot.setBaseDamage(getAttributeValue(Attributes.ATTACK_DAMAGE) / 2.0);
            return shot;
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public void igniteForTicks(int ticks) {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }

        @Override
        public void die(DamageSource source) {
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            super.die(source);
        }
    }
}
