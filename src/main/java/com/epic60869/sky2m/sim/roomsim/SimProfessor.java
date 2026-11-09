package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Floor 3's boss, The Professor, and his four Guardians, as Hypixel runs the fight
 * (https://hypixelskyblock.minecraft.wiki/w/The_Professor):
 * <ol>
 * <li><b>The Guardians.</b> Four Guardians sit on the four diamond pads round the middle and The Professor watches
 * from his pillar behind his barrier. A dead Guardian revives {@link #reviveTicks} later at {@link #reviveShare} of
 * its health (10s / 80% on F3, 7s / 90% on M3), so all four have to be down at once to break the barrier. Each has
 * its own trick, and each Body Slams whoever stands next to it:
 * <ul>
 * <li><b>Healthy Guardian</b> - announces a Giga Heal that heals every living Guardian; each hit on it during the
 * countdown takes 5% off the heal and 20 hits cancel it (M3: it is invulnerable meanwhile, but the hits count).</li>
 * <li><b>Reinforced Guardian</b> - more health; spawns Rogue Guardians and throws TNT Rain on a timer that keeps
 * running through its deaths.</li>
 * <li><b>Chaos Guardian</b> - Discharge: lightning on everyone near it every 2s, scaled by its Anger Level, which
 * hits on it raise (max 6); at 6 it Overcharges and strikes rapidly.</li>
 * <li><b>Laser Guardian</b> - a laser every 0.75s, and a Focus Beam cycle: charge, a long fast beam, overheat.</li>
 * </ul>
 * Every telegraphed laser tracks its target while it charges, then LOCKS (turns bright) and fires down that line.</li>
 * <li><b>The Professor.</b> His barrier down, he comes off his pillar, walks at the nearest player and hits whoever
 * he reaches, and throws Prismarine Blizzard - prismarine that deals true damage, a share of max health.</li>
 * <li><b>The Guardian.</b> At a quarter of his health he absorbs the dead Guardians and transforms into a giant
 * Guardian over the middle: a laser every 0.75s, Rogue Guardians, TNT Rain and his own Giga Heal. If the fight runs
 * long the arena floods and boats appear.</li>
 * </ol>
 *
 * <p>The boss lines are Hypixel's, word for word (the three the mod reads drive Boss Phases, the splits and Clean
 * End). The [Sim] hints (Giga Heal countdown, Focus Beam) are the sim's own: Hypixel's wording is not on the wiki.
 */
final class SimProfessor extends SimBoss.Fight {

    // ------------------------------------------------------------------ health and timings

    /**
     * The Professor's health, 3,000,000 on F3 and 600,000,000 on M3, and his base damage, 5,976 / 135,000, which his
     * swing, the Guardian form's laser and its TNT deal (https://hypixelskyblock.minecraft.wiki/w/The_Professor).
     * Hypixel ends his second phase at a quarter of that health and gives the Guardian form no pool of its own, so
     * the form fights on with that last quarter ({@link #TRANSFORM_SHARE}).
     */
    private static double professorHealth() {
        return SimHealth.byMode(3_000_000, 600_000_000);
    }

    private static double professorDamage() {
        return SimHealth.byMode(5_976, 135_000);
    }

    /** The share of his health at which he transforms into the Guardian. */
    private static final double TRANSFORM_SHARE = 0.25;

    /** A dead Guardian comes back this long after it died (wiki: 10s, 7s on M3) ... */
    private static int reviveTicks(boolean master) {
        return master ? 140 : 200;
    }

    /** ... at this share of its health (wiki: 80%, 90% on M3). */
    private static double reviveShare(boolean master) {
        return master ? 0.9 : 0.8;
    }

    /** Body Slam: 2x the Guardian's base damage on F3, 6x on M3 (wiki). */
    private static double slamMultiplier(boolean master) {
        return master ? 6 : 2;
    }

    /** Body Slam reach and the wait between two slams. APPROXIMATE (no number on the wiki). */
    private static final double SLAM_RANGE = 3.5;
    private static final int SLAM_EVERY = 60;

    /** Giga Heal: announced 10s (8s M3) before it lands, every 60s (45s M3); heals 10M (210M M3) (wiki). */
    private static int gigaWarn(boolean master) {
        return master ? 160 : 200;
    }

    private static int gigaEvery(boolean master) {
        return master ? 900 : 1200;
    }

    private static double gigaHeal() {
        return SimHealth.byMode(10_000_000, 210_000_000);
    }

    /** Each hit takes 5% off the heal and 20 cancel it (wiki). */
    private static final int GIGA_CANCEL_HITS = 20;
    /** The first Giga Heal is announced this long after the Guardians wake. APPROXIMATE. */
    private static final int GIGA_FIRST = 400;

    /** TNT Rain: every ~40s (~30s M3), for 10s (wiki). */
    private static int tntEvery(boolean master) {
        return master ? 600 : 800;
    }

    private static final int TNT_RAIN_TICKS = 200;
    /** The Reinforced Guardian's TNT: 0.6x its base damage on F3, 0.8x on M3 (wiki). */
    private static double tntMultiplier(boolean master) {
        return master ? 0.8 : 0.6;
    }

    /** One TNT per player this often during a rain, and its blast radius. APPROXIMATE. */
    private static final int TNT_THROW_EVERY = 15;
    private static final double TNT_RADIUS = 3.5;

    /**
     * Rogue Guardians, from the Rogue Guardian page's two F3 and two M3 entries
     * (https://hypixelskyblock.minecraft.wiki/w/Rogue_Guardian): F3's Rogue 100,000 health / 2,376 damage and its
     * Elder 200,000 / 4,320; M3's are Elders from the start (The Professor's page), 50,000,000 / 55,800 (M3's
     * weaker entry, 20,000,000 / 45,000, would be a plain M3 Rogue, which never spawns). The page does not label
     * which entry is the Elder: the stronger one is taken to be. The spawn rate, cap, laser rate and when they turn
     * Elder are APPROXIMATE.
     */
    private static double rogueHealth(boolean elder) {
        return elder ? SimHealth.byMode(200_000, 50_000_000) : SimHealth.byMode(100_000, 20_000_000);
    }

    private static double rogueDamage(boolean elder) {
        return elder ? SimHealth.byMode(4_320, 55_800) : SimHealth.byMode(2_376, 45_000);
    }

    private static final int ROGUE_EVERY = 300;
    private static final int ROGUE_MAX = 4;
    private static final int ROGUE_BEAM_EVERY = 60;
    private static final int ROGUE_ELDER_AFTER = 600;
    private static final double ROGUE_REACH = 18;

    /**
     * Chaos Guardian Discharge: every 2s, lightning on players near it for 0.5x (0.7x M3) its base damage times its
     * Anger Level, max 6; Overcharge strikes for 12 times that multiplier (17,280 / 453,600) (wiki). The reach, hits per
     * Anger Level, and the Overcharge's charge, length and strike rate are APPROXIMATE.
     */
    private static double chaosMultiplier(boolean master) {
        return master ? 0.7 : 0.5;
    }

    private static final int CHAOS_EVERY = 40;
    private static final double CHAOS_REACH = 12;
    private static final int HITS_PER_ANGER = 6;
    private static final int OVERCHARGE_CHARGE = 40;
    private static final int OVERCHARGE_TICKS = 80;
    private static final int OVERCHARGE_STRIKE_EVERY = 8;

    /**
     * Laser Guardian: its Default Laser every 0.75s at base damage; the Focus Beam starts 10s in, charges 15s (10s M3)
     * with the default laser off, fires 60s (45s M3) every 0.5s for 0.1x (0.6x M3) base damage with longer reach,
     * then overheats for 10s; after a respawn the next charge starts 15s later (wiki). The reaches are APPROXIMATE.
     */
    private static final int LASER_EVERY = 15;
    private static final double LASER_REACH = 24;
    private static final double FOCUS_REACH = 40;
    private static final int FOCUS_FIRST = 200;
    private static final int FOCUS_COOL = 200;
    private static final int FOCUS_HIT_EVERY = 10;

    private static int focusCharge(boolean master) {
        return master ? 200 : 300;
    }

    private static int focusFire(boolean master) {
        return master ? 900 : 1200;
    }

    private static double focusMultiplier(boolean master) {
        return master ? 0.6 : 0.1;
    }

    /** Prismarine Blizzard: true damage, 5% of max health (15% M3) (wiki). Its rate and speed are APPROXIMATE. */
    private static double blizzardShare(boolean master) {
        return master ? 0.15 : 0.05;
    }

    /** The Guardian form's laser, every 0.75s at his base damage (wiki). */
    private static final int FORM_LASER_EVERY = 15;
    /** The arena floods this long into the Guardian form ("if the fight runs too long"). APPROXIMATE. */
    private static final int FLOOD_AFTER = 3600;
    private static final int FLOOD_PER_TICK = 40;

    private static final int INTRO_TICKS = 80;
    private static final int TRANSFORM_TICKS = 60;
    private static final int DYING_TICKS = 160;
    private static final double BEAM_LENGTH = 40;
    private static final double BEAM_WIDTH = 1.1;

    /** The four diamond pads round the middle, as offsets from the arena centre in source blocks (arena data). */
    private static final int[][] PADS = {{-7, -7}, {7, -7}, {-7, 7}, {7, 7}};
    /** The arena's real middle (the 5x5 pillar top) is a block south-east of the JSON's centre. */
    private static final int MIDDLE_DX = 0;
    private static final int MIDDLE_DZ = 1;

    /** Hypixel's lines while he revives a Guardian, word for word. */
    private static final String[] REVIVE_LINES = {
            "My technique is much more advanced than Scarf's..",
            "My barrier will hold as long as my Guardians are alive.",
            "Give up Adventurers, it only takes me a few seconds to revive my guardians.",
            "I can do this all day.",
            "Perfect offense and defense, I am INVINCIBLE!"};

    private enum Phase { INTRO, GUARDIANS, PROFESSOR, TRANSFORM, GUARDIAN_FORM, DYING, DEAD }

    private enum Focus { COOL, CHARGE, FIRE }

    /** The four Guardians, with Hypixel's health and base damage on F3 and M3 (wiki). */
    private enum Kind {
        HEALTHY("Healthy Guardian", 1_000_000, 120_000_000, 4_392, 81_000, 0xFF5555),
        REINFORCED("Reinforced Guardian", 1_200_000, 140_000_000, 5_040, 81_000, 0x55FF55),
        CHAOS("Chaos Guardian", 1_000_000, 120_000_000, 2_880, 54_000, 0xAA00AA),
        LASER("Laser Guardian", 1_000_000, 120_000_000, 4_320, 81_000, 0x55FFFF);

        final String display;
        final double healthF3;
        final double healthM3;
        final double damageF3;
        final double damageM3;
        final int colour;

        Kind(String display, double healthF3, double healthM3, double damageF3, double damageM3, int colour) {
            this.display = display;
            this.healthF3 = healthF3;
            this.healthM3 = healthM3;
            this.damageF3 = damageF3;
            this.damageM3 = damageM3;
            this.colour = colour;
        }

        double damage() {
            return SimHealth.byMode(damageF3, damageM3);
        }
    }

    /** One Guardian on its pad. */
    private static final class Pad {
        final Kind kind;
        final Vec3 at;
        Guardian body;
        SimBoss.Health health;
        int deadAt = -1;
        int nextSlam;
        // Laser Guardian
        int nextLaser;
        Focus focus = Focus.COOL;
        int focusUntil;
        int nextFocusHit;
        // Chaos Guardian
        int anger = 1;
        int angerHits;
        int nextDischarge;
        int overchargeAt = -1;
        int overchargeUntil = -1;

        Pad(Kind kind, Vec3 at) {
            this.kind = kind;
            this.at = at;
        }

        boolean alive() {
            return body != null && health != null && health.hp > 0;
        }
    }

    /** A Rogue Guardian (an Elder Rogue Guardian once {@link #elder}). */
    private static final class Rogue {
        final Vec3 home;
        final int bornAt;
        Guardian body;
        SimBoss.Health health;
        boolean elder;
        double damage;
        int nextBeam;

        Rogue(Vec3 home, int bornAt) {
            this.home = home;
            this.bornAt = bornAt;
        }
    }

    /**
     * A telegraphed beam: tracks {@code target} until {@code lockAt}, then fires along the locked line at
     * {@code fireAt}.
     */
    private static final class Beam {
        final Entity from;
        final ServerPlayer target;
        final int lockAt;
        final int fireAt;
        final double damage;
        final double push;
        final double lift;
        final int colour;
        Vec3 end;

        Beam(Entity from, ServerPlayer target, int lockAt, int fireAt, double damage, double push, double lift,
             int colour) {
            this.from = from;
            this.target = target;
            this.lockAt = lockAt;
            this.fireAt = fireAt;
            this.damage = damage;
            this.push = push;
            this.lift = lift;
            this.colour = colour;
        }
    }

    /** A Prismarine Blizzard shard: true damage of {@code share} of max health. */
    private record Shard(Vec3 pos, Vec3 vel, int dieAt, double share) {
    }

    /** A thrown TNT; the fight sets it off where it lands (it never reaches its own fuse). */
    private record Tnt(PrimedTnt entity, double damage, int dieAt) {
    }

    private Phase phase = Phase.INTRO;
    private int phaseTicks;
    /** Counts every fight tick, for timers that live across phases. */
    private int clock;
    private final List<Pad> pads = new ArrayList<>();
    private final List<Rogue> rogues = new ArrayList<>();
    private final List<Beam> beams = new ArrayList<>();
    private final List<Shard> shards = new ArrayList<>();
    private final List<Tnt> tnts = new ArrayList<>();
    private final SimBoss.Health health = new SimBoss.Health(professorHealth());
    private Zombie professor;
    private ElderGuardian guardianForm;
    private int nextBlizzard;
    private int nextMelee;
    private int nextFormLaser;
    private int protectedNote;
    private double orbit;
    /** The most Guardians down at once so far, so each count's line is said once. */
    private int mostDown;
    /** The second line of a Guardian-count pair and the clock it is due on, or null. */
    private String followUp;
    private int followUpAt;
    // Giga Heal (the Healthy Guardian's, then the Guardian form's)
    private int nextGiga = -1;
    private int gigaAt = -1;
    private int gigaHits;
    // TNT Rain and Rogue Guardians (the Reinforced Guardian's, then the Guardian form's)
    private int nextTnt = -1;
    private int tntUntil = -1;
    private int nextRogue = -1;
    // The flood
    private int floodAt = -1;
    private List<BlockPos> floodQueue;
    private final List<BlockPos> flooded = new ArrayList<>();
    private final List<Boat> boats = new ArrayList<>();

    SimProfessor(SimBossArena arena) {
        super(arena);
    }

    private static boolean master() {
        String f = SimState.floorLabel();
        return f != null && f.toUpperCase(java.util.Locale.ROOT).startsWith("M");
    }

    @Override
    String name() {
        return "The Professor";
    }

    @Override
    void start(ServerLevel level) {
        // Hypixel's first line, word for word: Boss Phases opens "Guardians" on it.
        SimBoss.say(level.getServer(), "The Professor", "I was burdened with terrible news recently...");
        spawnProfessor(level);
        spawnGuardians(level);
        for (Pad p : pads) {
            p.health.invulnerable = true;
        }
        health.invulnerable = true;
        phase = Phase.INTRO;
        phaseTicks = 0;
    }

    @Override
    void tick(ServerLevel level, int t) {
        phaseTicks++;
        clock++;
        boolean master = master();
        switch (phase) {
            case INTRO -> {
                if (phaseTicks == 30) {
                    SimBoss.say(level.getServer(), "The Professor", "My most talented student...");
                } else if (phaseTicks == 60) {
                    SimBoss.say(level.getServer(), "The Professor", "I'll show you real power!");
                }
                if (phaseTicks >= INTRO_TICKS) {
                    for (Pad p : pads) {
                        p.health.invulnerable = false;
                        p.nextLaser = clock + 20;
                        p.focusUntil = clock + FOCUS_FIRST;
                        p.nextDischarge = clock + CHAOS_EVERY;
                    }
                    nextGiga = clock + GIGA_FIRST;
                    nextTnt = clock + tntEvery(master);
                    nextRogue = clock + 200;
                    toPhase(Phase.GUARDIANS);
                }
            }
            case GUARDIANS -> {
                if (followUp != null && clock >= followUpAt) {
                    SimBoss.say(level.getServer(), "The Professor", followUp);
                    followUp = null;
                }
                tickGuardians(level, master);
                tickHealthyGiga(level, master);
                tickReinforced(level, master);
                if (livingGuardians() == 0) {
                    breakBarrier(level);
                } else {
                    tickRevives(level, master);
                }
            }
            case PROFESSOR -> {
                if (professor == null || professor.isRemoved()) {
                    spawnProfessor(level);
                }
                if (phaseTicks == 40) {
                    SimBoss.say(level.getServer(), "The Professor", "Even if you took my barrier down, I can still fight.");
                } else if (phaseTicks == 80) {
                    SimBoss.say(level.getServer(), "The Professor", "This time I'll be your opponent!");
                }
                chase(level, master);
                if (--nextBlizzard <= 0) {
                    blizzard(level, master);
                    nextBlizzard = master ? 45 : 60;
                }
                if (health.hp <= health.max * TRANSFORM_SHARE) {
                    transform(level);
                }
            }
            case TRANSFORM -> {
                Vec3 m = middle(level);
                level.sendParticles(ParticleTypes.BUBBLE, m.x, m.y + 3, m.z, 20, 1.5, 1.5, 1.5, 0.1);
                if (phaseTicks == 20) {
                    SimBoss.say(level.getServer(), "The Professor", "These Guardians aren't just for show I'm afraid!");
                } else if (phaseTicks == 40) {
                    SimBoss.say(level.getServer(), "The Professor",
                            "The process is irreversible, but I'll be stronger than a Wither now!");
                }
                if (phaseTicks >= TRANSFORM_TICKS) {
                    spawnGuardianForm(level, master);
                    toPhase(Phase.GUARDIAN_FORM);
                }
            }
            case GUARDIAN_FORM -> {
                if (guardianForm == null || guardianForm.isRemoved()) {
                    spawnGuardianForm(level, master);
                }
                floatForm(level, master);
                if (clock >= nextFormLaser) {
                    formLaser(level);
                    nextFormLaser = clock + FORM_LASER_EVERY;
                }
                tickFormGiga(level, master);
                tickFormRain(level, master);
                if (clock >= nextRogue) {
                    nextRogue = clock + ROGUE_EVERY;
                    if (guardianForm != null) {
                        spawnRogue(level, guardianForm.position().add(0, -1, 0), master);
                    }
                }
                tickFlood(level);
                if (health.hp <= 0) {
                    die(level);
                }
            }
            case DYING -> tickDying(level);
            default -> {
            }
        }
        if (phase != Phase.DYING && phase != Phase.DEAD) {
            tickRogues(level, master);
        }
        tickBeams(level);
        tickShards(level);
        tickTnt(level);
        nameTags();
        if (protectedNote > 0) {
            protectedNote--;
        }
    }

    private void toPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    // ------------------------------------------------------------------ spawning

    /** The middle of the arena, on top of the pillar. */
    private Vec3 middle(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre[0] + MIDDLE_DX, arena.centre[1], arena.centre[2] + MIDDLE_DZ);
        int y = ground(level, c.getX(), c.getZ(), c.getY() + 2);
        return new Vec3(c.getX() + 0.5, y, c.getZ() + 0.5);
    }

    /** The feet height of the first spot at or below {@code fromY} with something to stand on. */
    private static int ground(ServerLevel level, int x, int z, int fromY) {
        for (int y = fromY; y > fromY - 12; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) {
                return y;
            }
        }
        return fromY;
    }

    private void spawnProfessor(ServerLevel level) {
        Vec3 m = middle(level);
        Zombie z = new ProfessorBody(level);
        z.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        z.setHealth(1000);
        z.setNoAi(true);
        z.setPersistenceRequired();
        z.setCustomNameVisible(true);
        z.setGlowingTag(phase == Phase.INTRO || phase == Phase.GUARDIANS);
        z.snapTo(m.x, m.y, m.z, 180f, 0f);
        level.addFreshEntity(z);
        professor = z;
    }

    /** Puts the four Guardians on their pads at full health. */
    private void spawnGuardians(ServerLevel level) {
        discardGuardians();
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            int[] o = PADS[i];
            BlockPos at = arena.toWorld(arena.centre[0] + MIDDLE_DX + o[0], arena.centre[1],
                    arena.centre[2] + MIDDLE_DZ + o[1]);
            int y = ground(level, at.getX(), at.getZ(), at.getY());
            Pad pad = new Pad(kinds[i], new Vec3(at.getX() + 0.5, y + 0.4, at.getZ() + 0.5));
            pad.health = new SimBoss.Health(SimHealth.byMode(kinds[i].healthF3, kinds[i].healthM3));
            putBody(level, pad);
            pads.add(pad);
        }
    }

    private void putBody(ServerLevel level, Pad p) {
        Guardian g = newGuardian(level);
        g.setNoGravity(true);
        g.setPersistenceRequired();
        g.setCustomNameVisible(true);
        g.snapTo(p.at.x, p.at.y, p.at.z, 0f, 0f);
        level.addFreshEntity(g);
        p.body = g;
        level.sendParticles(ParticleTypes.BUBBLE, p.at.x, p.at.y + 0.5, p.at.z, 30, 0.6, 0.6, 0.6, 0.1);
    }

    private void spawnGuardianForm(ServerLevel level, boolean master) {
        Vec3 m = middle(level);
        ElderGuardian g = new ElderGuardian(EntityTypes.ELDER_GUARDIAN, level);
        g.setNoAi(true);
        g.setMoving(true);
        g.setNoGravity(true);
        g.setPersistenceRequired();
        g.setCustomNameVisible(true);
        g.getAttribute(Attributes.SCALE).setBaseValue(master ? 2.5 : 2.0);
        g.snapTo(m.x, m.y + 4, m.z, 0f, 0f);
        level.addFreshEntity(g);
        guardianForm = g;
        if (phase != Phase.GUARDIAN_FORM) {
            // First time in: the last quarter of his pool, and his own Giga Heal, TNT Rain, Rogues and flood clocks.
            health.hp = Math.max(1, Math.min(health.hp, health.max * TRANSFORM_SHARE));
            health.invulnerable = false;
            nextFormLaser = clock + 40;
            nextGiga = clock + gigaEvery(master);
            gigaAt = -1;
            nextTnt = clock + tntEvery(master) / 2;
            tntUntil = -1;
            nextRogue = clock + 100;
            floodAt = clock + FLOOD_AFTER;
            level.playSound(null, m.x, m.y, m.z, SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 1f, 0.8f);
        }
    }

    /**
     * A Guardian with no AI. Marked "moving" on purpose: vanilla's Guardian pricks whoever hits it with Thorns
     * unless it is moving, and that would be a second, unasked-for damage source on every swing.
     */
    private static Guardian newGuardian(ServerLevel level) {
        Guardian g = new Guardian(EntityTypes.GUARDIAN, level);
        g.setNoAi(true);
        g.setMoving(true);
        return g;
    }

    private static Guardian newElder(ServerLevel level) {
        ElderGuardian g = new ElderGuardian(EntityTypes.ELDER_GUARDIAN, level);
        g.setNoAi(true);
        g.setMoving(true);
        // A Rogue, not the boss: smaller than vanilla's Elder Guardian. APPROXIMATE (looks only).
        g.getAttribute(Attributes.SCALE).setBaseValue(0.6);
        return g;
    }

    // ------------------------------------------------------------------ phase 1: the Guardians

    private int livingGuardians() {
        int n = 0;
        for (Pad p : pads) {
            if (p.alive()) {
                n++;
            }
        }
        return n;
    }

    private Pad pad(Kind kind) {
        for (Pad p : pads) {
            if (p.kind == kind) {
                return p;
            }
        }
        return null;
    }

    private void tickGuardians(ServerLevel level, boolean master) {
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        for (Pad p : pads) {
            if (!p.alive()) {
                continue;
            }
            if (p.body.isRemoved()) {
                putBody(level, p);
            }
            // Face the nearest player, the way a Guardian's eye follows you.
            ServerPlayer near = nearest(players, p.body.position());
            if (near != null) {
                face(p.body, near.getEyePosition());
            }
            bodySlam(level, p, players, master);
            if (p.kind == Kind.LASER) {
                tickLaserGuardian(level, p, players, master);
            } else if (p.kind == Kind.CHAOS) {
                tickChaos(level, p, players, master);
            }
        }
    }

    /** Body Slam: whoever stands right next to a Guardian is hit for a multiple of its base damage and thrown. */
    private void bodySlam(ServerLevel level, Pad p, List<ServerPlayer> players, boolean master) {
        if (clock < p.nextSlam) {
            return;
        }
        for (ServerPlayer pl : players) {
            if (pl.position().distanceTo(p.body.position()) <= SLAM_RANGE) {
                hurtPlayer(level, pl, p.kind.damage() * slamMultiplier(master), pl.getX() - p.body.getX(),
                        pl.getZ() - p.body.getZ(), 1.6, 0.6);
                level.sendParticles(ParticleTypes.CLOUD, p.body.getX(), p.body.getY(), p.body.getZ(), 10, 0.5,
                        0.3, 0.5, 0.05);
                level.playSound(null, p.at.x, p.at.y, p.at.z, SoundEvents.GENERIC_EXPLODE,
                        SoundSource.HOSTILE, 0.5f, 1.6f);
                p.nextSlam = clock + SLAM_EVERY;
                return;
            }
        }
    }

    /** The Default Laser every 0.75s, and the Focus Beam cycle: cool down, charge (no default laser), fire. */
    private void tickLaserGuardian(ServerLevel level, Pad p, List<ServerPlayer> players, boolean master) {
        if (clock >= p.focusUntil) {
            switch (p.focus) {
                case COOL -> {
                    p.focus = Focus.CHARGE;
                    p.focusUntil = clock + focusCharge(master);
                    SimBoss.tell(level.getServer(), Component.literal("The Laser Guardian is charging its Focus Beam!")
                            .withStyle(ChatFormatting.AQUA));
                }
                case CHARGE -> {
                    p.focus = Focus.FIRE;
                    p.focusUntil = clock + focusFire(master);
                    p.nextFocusHit = clock;
                }
                case FIRE -> {
                    p.focus = Focus.COOL;
                    p.focusUntil = clock + FOCUS_COOL;
                    SimBoss.tell(level.getServer(), Component.literal("The Laser Guardian's Focus Beam overheated.")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
        }
        Vec3 eye = p.body.position().add(0, p.body.getBbHeight() / 2, 0);
        switch (p.focus) {
            case CHARGE -> {
                if (clock % 4 == 0) {
                    level.sendParticles(new DustParticleOptions(p.kind.colour, 1.5f), eye.x, eye.y, eye.z, 6,
                            0.5, 0.5, 0.5, 0);
                }
            }
            case FIRE -> {
                ServerPlayer target = nearestWithin(players, p.body.position(), FOCUS_REACH);
                if (target == null) {
                    return;
                }
                Vec3 chest = target.position().add(0, 1, 0);
                if (clock % 2 == 0) {
                    drawLine(level, eye, chest, new DustParticleOptions(p.kind.colour, 1.6f), 0.4);
                }
                if (clock >= p.nextFocusHit) {
                    p.nextFocusHit = clock + FOCUS_HIT_EVERY;
                    SimVitals.dealHypixel(level, target, level.damageSources().magic(),
                            p.kind.damage() * focusMultiplier(master), false);
                }
            }
            case COOL -> {
                if (clock >= p.nextLaser) {
                    p.nextLaser = clock + LASER_EVERY;
                    ServerPlayer target = nearestWithin(players, p.body.position(), LASER_REACH);
                    if (target != null) {
                        beams.add(new Beam(p.body, target, clock + 10, clock + 15, p.kind.damage(), 0.3, 0.2,
                                p.kind.colour));
                        level.playSound(null, p.at.x, p.at.y, p.at.z, SoundEvents.GUARDIAN_ATTACK,
                                SoundSource.HOSTILE, 0.5f, 1.4f);
                    }
                }
            }
        }
    }

    /** Discharge every 2s, scaled by Anger Level; at Anger 6, Overcharge: a charge, then rapid strikes. */
    private void tickChaos(ServerLevel level, Pad p, List<ServerPlayer> players, boolean master) {
        double unit = p.kind.damage() * chaosMultiplier(master);
        if (p.overchargeAt >= 0) {
            if (clock < p.overchargeAt) {
                if (clock % 3 == 0) {
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, p.body.getX(), p.body.getY() + 0.5,
                            p.body.getZ(), 12, 0.8, 0.8, 0.8, 0.2);
                }
                return;
            }
            if (clock < p.overchargeUntil) {
                if ((clock - p.overchargeAt) % OVERCHARGE_STRIKE_EVERY == 0) {
                    for (ServerPlayer pl : players) {
                        if (pl.position().distanceTo(p.body.position()) <= CHAOS_REACH) {
                            strike(level, pl, unit * 12);
                        }
                    }
                }
                return;
            }
            p.overchargeAt = -1;
            p.overchargeUntil = -1;
            p.nextDischarge = clock + CHAOS_EVERY;
        }
        if (clock >= p.nextDischarge) {
            p.nextDischarge = clock + CHAOS_EVERY;
            for (ServerPlayer pl : players) {
                if (pl.position().distanceTo(p.body.position()) <= CHAOS_REACH) {
                    strike(level, pl, unit * p.anger);
                }
            }
        }
    }

    /** Hits on the Chaos Guardian raise its Anger Level; at 6 it resets to 1 and Overcharges. */
    private void anger(ServerLevel level, Pad p) {
        if (p.overchargeAt >= 0) {
            return;
        }
        if (++p.angerHits < HITS_PER_ANGER) {
            return;
        }
        p.angerHits = 0;
        p.anger++;
        if (p.anger >= 6) {
            p.anger = 1;
            p.overchargeAt = clock + OVERCHARGE_CHARGE;
            p.overchargeUntil = p.overchargeAt + OVERCHARGE_TICKS;
            level.playSound(null, p.at.x, p.at.y, p.at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE,
                    1.2f, 0.6f);
        }
    }

    /** The Healthy Guardian's Giga Heal: a countdown, hits on it shrink the heal, 20 cancel it. */
    private void tickHealthyGiga(ServerLevel level, boolean master) {
        Pad healthy = pad(Kind.HEALTHY);
        if (healthy == null) {
            return;
        }
        if (gigaAt < 0) {
            if (nextGiga >= 0 && clock >= nextGiga) {
                nextGiga = clock + gigaEvery(master);
                if (healthy.alive()) {
                    gigaAt = clock + gigaWarn(master);
                    gigaHits = 0;
                    if (master) {
                        healthy.health.invulnerable = true;
                    }
                    SimBoss.tell(level.getServer(), Component.literal("The Healthy Guardian is casting Giga Heal! Hit it "
                            + GIGA_CANCEL_HITS + " times to stop it.").withStyle(ChatFormatting.LIGHT_PURPLE));
                }
            }
            return;
        }
        if (!healthy.alive() || gigaHits >= GIGA_CANCEL_HITS) {
            if (healthy.alive()) {
                SimBoss.tell(level.getServer(), Component.literal("Giga Heal interrupted!")
                        .withStyle(ChatFormatting.GREEN));
            }
            endGiga(healthy);
            return;
        }
        if ((gigaAt - clock) % 20 == 0) {
            actionBar(level, "Giga Heal in " + (gigaAt - clock) / 20 + "s - hits " + gigaHits + "/"
                    + GIGA_CANCEL_HITS, ChatFormatting.LIGHT_PURPLE);
        }
        if (clock % 4 == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, healthy.body.getX(), healthy.body.getY() + 0.5,
                    healthy.body.getZ(), 6, 0.6, 0.6, 0.6, 0);
        }
        if (clock >= gigaAt) {
            double amount = gigaHeal() * Math.max(0, 1 - 0.05 * gigaHits);
            for (Pad p : pads) {
                if (p.alive()) {
                    p.health.hp = Math.min(p.health.max, p.health.hp + amount);
                    level.sendParticles(ParticleTypes.HEART, p.at.x, p.at.y + 1.2, p.at.z, 6, 0.4, 0.3, 0.4, 0);
                }
            }
            level.playSound(null, healthy.at.x, healthy.at.y, healthy.at.z, SoundEvents.PLAYER_LEVELUP,
                    SoundSource.HOSTILE, 1f, 0.6f);
            SimBoss.say(level.getServer(), "The Professor",
                    "Another flawless Giga Heal. You are making this too easy for me!");
            endGiga(healthy);
        }
    }

    private void endGiga(Pad healthy) {
        gigaAt = -1;
        gigaHits = 0;
        if (healthy != null && healthy.health != null) {
            healthy.health.invulnerable = false;
        }
    }

    /**
     * The Reinforced Guardian's Rogue Guardians and TNT Rain. The TNT clock keeps running through its deaths (a rain
     * that started goes on for its 10s); a rain that comes due while it is dead is skipped (wiki).
     */
    private void tickReinforced(ServerLevel level, boolean master) {
        Pad r = pad(Kind.REINFORCED);
        if (r == null) {
            return;
        }
        if (nextTnt >= 0 && clock >= nextTnt) {
            nextTnt = clock + tntEvery(master);
            if (r.alive()) {
                startRain(level);
            }
        }
        if (clock < tntUntil && (tntUntil - clock) % TNT_THROW_EVERY == 0) {
            Vec3 from = r.alive() ? r.body.position().add(0, 1, 0) : r.at.add(0, 1, 0);
            throwTnt(level, from, r.kind.damage() * tntMultiplier(master));
        }
        if (nextRogue >= 0 && clock >= nextRogue) {
            nextRogue = clock + ROGUE_EVERY;
            if (r.alive()) {
                spawnRogue(level, r.at.add(0, 2, 0), master);
            }
        }
    }

    private void startRain(ServerLevel level) {
        tntUntil = clock + TNT_RAIN_TICKS;
        SimBoss.say(level.getServer(), "The Professor", "How do you like the taste of Gunpowder?");
    }

    /** Dead Guardians come back after a few seconds while any of the four still lives. */
    private void tickRevives(ServerLevel level, boolean master) {
        for (Pad p : pads) {
            if (p.alive() || p.deadAt < 0 || clock < p.deadAt + reviveTicks(master)) {
                continue;
            }
            p.deadAt = -1;
            p.health.hp = p.health.max * reviveShare(master);
            p.anger = 1;
            p.angerHits = 0;
            p.overchargeAt = -1;
            p.overchargeUntil = -1;
            p.nextDischarge = clock + CHAOS_EVERY;
            // Wiki: after a respawn the Focus Beam's cooldown starts 5s later, so its next charge is 15s off.
            p.focus = Focus.COOL;
            p.focusUntil = clock + 100 + FOCUS_COOL;
            p.nextLaser = clock + 20;
            putBody(level, p);
            level.playSound(null, p.at.x, p.at.y, p.at.z, SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.HOSTILE,
                    1f, 1.2f);
            // The wiki's revive lines: "Revive, (name)!" or one of the others. Which name Hypixel puts in (the
            // Guardian's full name is assumed) and how often each is said are not on the wiki.
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            SimBoss.say(level.getServer(), "The Professor", rnd.nextBoolean() ? "Revive, " + p.kind.display + "!"
                    : REVIVE_LINES[rnd.nextInt(REVIVE_LINES.length)]);
        }
    }

    private void killGuardian(ServerLevel level, Pad p) {
        if (p.body != null) {
            level.sendParticles(ParticleTypes.EXPLOSION, p.body.getX(), p.body.getY() + 0.5, p.body.getZ(), 2,
                    0.3, 0.3, 0.3, 0);
            level.playSound(null, p.body.getX(), p.body.getY(), p.body.getZ(), SoundEvents.GENERIC_EXPLODE,
                    SoundSource.HOSTILE, 0.7f, 1.4f);
            p.body.discard();
            p.body = null;
        }
        p.deadAt = clock;
        if (p.kind == Kind.HEALTHY && gigaAt >= 0) {
            endGiga(p);
        }
        beams.removeIf(b -> b.from == null || b.from.isRemoved());
        int down = pads.size() - livingGuardians();
        if (phase == Phase.GUARDIANS && down > mostDown && down < pads.size()) {
            mostDown = down;
            String line = switch (down) {
                case 1 -> "You took one of them down, but that won't be enough!";
                case 2 -> "My barrier is weakening, but it still holds strong!";
                default -> "What?! Nobody has managed to take three of my Guardians down before!";
            };
            SimBoss.say(level.getServer(), "The Professor", line);
            // Each count's second line (the wiki), a moment later (APPROXIMATE).
            followUp = switch (down) {
                case 1 -> "I'm afraid I can quickly revive any one of my Guardians!";
                case 2 -> "Two Guardians, impressive...";
                default -> "My Guardians!";
            };
            followUpAt = clock + 30;
        }
    }

    /** All four down at once: the barrier breaks and The Professor fights. */
    private void breakBarrier(ServerLevel level) {
        // Hypixel's line, word for word: Boss Phases moves to "Professor" on it.
        SimBoss.say(level.getServer(), "The Professor", "Oh? You found my Guardians' one weakness?");
        health.invulnerable = false;
        if (professor != null) {
            professor.setGlowingTag(false);
        }
        beams.removeIf(b -> b.from != null && b.from instanceof Guardian && !isRogueBody(b.from));
        endGiga(pad(Kind.HEALTHY));
        tntUntil = -1;
        nextBlizzard = 40;
        toPhase(Phase.PROFESSOR);
    }

    // ------------------------------------------------------------------ Rogue Guardians

    private void spawnRogue(ServerLevel level, Vec3 near, boolean master) {
        if (rogues.size() >= ROGUE_MAX) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Vec3 home = near.add(rnd.nextDouble() * 4 - 2, rnd.nextDouble(), rnd.nextDouble() * 4 - 2);
        Rogue r = new Rogue(home, clock);
        // Master Mode's Rogues are Elder Rogue Guardians from the start (wiki), with the wiki's M3 numbers.
        r.elder = master;
        r.health = new SimBoss.Health(rogueHealth(r.elder));
        r.damage = rogueDamage(r.elder);
        r.nextBeam = clock + ROGUE_BEAM_EVERY;
        putRogueBody(level, r);
        rogues.add(r);
    }

    private void putRogueBody(ServerLevel level, Rogue r) {
        Guardian g = r.elder ? newElder(level) : newGuardian(level);
        g.setNoGravity(true);
        g.setPersistenceRequired();
        g.setCustomNameVisible(true);
        g.snapTo(r.home.x, r.home.y, r.home.z, 0f, 0f);
        level.addFreshEntity(g);
        r.body = g;
        level.sendParticles(ParticleTypes.BUBBLE, r.home.x, r.home.y + 0.5, r.home.z, 20, 0.5, 0.5, 0.5, 0.1);
    }

    private void tickRogues(ServerLevel level, boolean master) {
        if (rogues.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        for (Rogue r : rogues) {
            if (!r.elder && clock >= r.bornAt + ROGUE_ELDER_AFTER) {
                // A Rogue that lives long enough becomes an Elder Rogue Guardian (wiki), with the Elder's numbers
                // and its health topped up by what it gained (when is APPROXIMATE).
                r.elder = true;
                double gained = rogueHealth(true) - r.health.max;
                r.health.max = rogueHealth(true);
                r.health.hp = Math.min(r.health.max, r.health.hp + Math.max(0, gained));
                r.damage = rogueDamage(true);
                if (r.body != null) {
                    r.body.discard();
                }
                putRogueBody(level, r);
            } else if (r.body == null || r.body.isRemoved()) {
                putRogueBody(level, r);
            }
            double y = r.home.y + Math.sin((clock + r.bornAt) * 0.07) * 0.4;
            r.body.snapTo(r.home.x, y, r.home.z, r.body.getYRot(), r.body.getXRot());
            ServerPlayer near = nearestWithin(players, r.body.position(), ROGUE_REACH);
            if (near != null) {
                face(r.body, near.getEyePosition());
                if (clock >= r.nextBeam) {
                    r.nextBeam = clock + ROGUE_BEAM_EVERY;
                    beams.add(new Beam(r.body, near, clock + 15, clock + 20, r.damage, 0.3, 0.2,
                            r.elder ? 0xFFAA00 : 0x5599FF));
                }
            }
        }
    }

    private Rogue rogue(Entity body) {
        for (Rogue r : rogues) {
            if (r.body == body) {
                return r;
            }
        }
        return null;
    }

    private boolean isRogueBody(Entity e) {
        return rogue(e) != null;
    }

    private void killRogue(ServerLevel level, Rogue r) {
        if (r.body != null) {
            level.sendParticles(ParticleTypes.POOF, r.body.getX(), r.body.getY() + 0.5, r.body.getZ(), 10,
                    0.3, 0.3, 0.3, 0.02);
            r.body.discard();
            r.body = null;
        }
        rogues.remove(r);
        beams.removeIf(b -> b.from == null || b.from.isRemoved());
    }

    private void discardRogues() {
        for (Rogue r : rogues) {
            if (r.body != null) {
                r.body.discard();
            }
        }
        rogues.clear();
    }

    // ------------------------------------------------------------------ TNT Rain

    /** Lobs a TNT at each player; it goes off where it lands, for {@code damage} round it. */
    private void throwTnt(ServerLevel level, Vec3 from, double damage) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            Vec3 to = p.position().add(rnd.nextDouble() * 4 - 2, 0, rnd.nextDouble() * 4 - 2);
            PrimedTnt tnt = new PrimedTnt(level, from.x, from.y, from.z, null);
            tnt.setFuse(400);
            // A lob that lands in about 25 ticks under TNT's gravity (0.04/tick); close enough with the drag.
            double flight = 25;
            tnt.setDeltaMovement((to.x - from.x) / flight, (to.y - from.y) / flight + 0.5 * 0.04 * flight,
                    (to.z - from.z) / flight);
            level.addFreshEntity(tnt);
            tnts.add(new Tnt(tnt, damage, clock + 60));
        }
        level.playSound(null, from.x, from.y, from.z, SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 0.8f, 1f);
    }

    private void tickTnt(ServerLevel level) {
        if (tnts.isEmpty()) {
            return;
        }
        List<Tnt> done = new ArrayList<>();
        for (Tnt t : tnts) {
            PrimedTnt e = t.entity();
            boolean landed = e.isRemoved() || e.onGround() || clock >= t.dieAt()
                    || e.tickCount > 3 && e.getDeltaMovement().lengthSqr() < 0.0004;
            if (!landed) {
                continue;
            }
            Vec3 at = e.position();
            e.discard();
            done.add(t);
            level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 0.8, 0.3, 0.8, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
                    0.8f, 1.1f);
            for (ServerPlayer p : SimBoss.playersInRing(level)) {
                if (p.position().distanceTo(at) <= TNT_RADIUS) {
                    hurtPlayer(level, p, t.damage(), p.getX() - at.x, p.getZ() - at.z, 0.8, 0.5);
                }
            }
        }
        tnts.removeAll(done);
    }

    private void discardTnt() {
        for (Tnt t : tnts) {
            t.entity().discard();
        }
        tnts.clear();
    }

    // ------------------------------------------------------------------ phase 2: The Professor

    /** Walks at the nearest player and hits whoever he reaches. Faster in Master Mode. */
    private void chase(ServerLevel level, boolean master) {
        if (professor == null) {
            return;
        }
        ServerPlayer target = nearest(SimBoss.playersInRing(level), professor.position());
        if (target == null) {
            return;
        }
        Vec3 from = professor.position();
        double dx = target.getX() - from.x;
        double dz = target.getZ() - from.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        if (d > 1.8) {
            double step = Math.min(d - 1.5, master ? 0.2 : 0.14);
            double nx = from.x + dx / d * step;
            double nz = from.z + dz / d * step;
            int y = ground(level, (int) Math.floor(nx), (int) Math.floor(nz), (int) Math.floor(from.y) + 1);
            if (Math.abs(y - from.y) <= 1.5) {
                professor.snapTo(nx, y, nz, yaw, 0f);
            } else {
                professor.snapTo(from.x, from.y, from.z, yaw, 0f);
            }
        } else {
            professor.snapTo(from.x, from.y, from.z, yaw, 0f);
        }
        professor.setYHeadRot(yaw);
        professor.setYBodyRot(yaw);
        if (nextMelee > 0) {
            nextMelee--;
        } else if (d < 2.6) {
            professor.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            hurtPlayer(level, target, professorDamage(), dx, dz, 0.5, 0.3);
            nextMelee = 20;
        }
    }

    /** Prismarine Blizzard: a prismarine shard at each player, true damage of a share of max health. */
    private void blizzard(ServerLevel level, boolean master) {
        if (professor == null) {
            return;
        }
        Vec3 from = professor.position().add(0, 1.5, 0);
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            Vec3 dir = p.position().add(0, 1, 0).subtract(from).normalize();
            shards.add(new Shard(from, dir.scale(master ? 0.9 : 0.7), clock + 60, blizzardShare(master)));
        }
        professor.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1f, 0.7f);
    }

    private void transform(ServerLevel level) {
        SimBoss.say(level.getServer(), "The Professor", "I see. You have forced me to use my ultimate technique.");
        health.invulnerable = true;
        shards.clear();
        beams.removeIf(b -> b.from == professor);
        if (professor != null) {
            level.sendParticles(ParticleTypes.LARGE_SMOKE, professor.getX(), professor.getY() + 1,
                    professor.getZ(), 30, 0.5, 1, 0.5, 0.05);
            professor.discard();
            professor = null;
        }
        // He absorbs the dead Guardians (wiki): they do not come back in the last phase.
        discardGuardians();
        Vec3 m = middle(level);
        level.playSound(null, m.x, m.y, m.z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 1.4f);
        toPhase(Phase.TRANSFORM);
    }

    // ------------------------------------------------------------------ phase 3: the Guardian

    /** Floats over the middle in a slow circle, looking at the nearest player. */
    private void floatForm(ServerLevel level, boolean master) {
        if (guardianForm == null) {
            return;
        }
        Vec3 m = middle(level);
        orbit += master ? 0.03 : 0.02;
        double x = m.x + Math.cos(orbit) * 3;
        double z = m.z + Math.sin(orbit) * 3;
        double y = m.y + 4 + Math.sin(clock * 0.05) * 0.5;
        guardianForm.snapTo(x, y, z, guardianForm.getYRot(), 0f);
        ServerPlayer near = nearest(SimBoss.playersInRing(level), guardianForm.position());
        if (near != null) {
            face(guardianForm, near.getEyePosition());
        }
    }

    /** His laser, every 0.75s, at one player in turn. */
    private void formLaser(ServerLevel level) {
        if (guardianForm == null) {
            return;
        }
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        if (players.isEmpty()) {
            return;
        }
        ServerPlayer p = players.get((clock / FORM_LASER_EVERY) % players.size());
        beams.add(new Beam(guardianForm, p, clock + 10, clock + 15, professorDamage(), 0.5, 0.3, 0xFF55FF));
        level.playSound(null, guardianForm.getX(), guardianForm.getY(), guardianForm.getZ(),
                SoundEvents.GUARDIAN_ATTACK, SoundSource.HOSTILE, 0.8f, 0.7f);
    }

    /**
     * His own Giga Heal: after a countdown he and every Rogue Guardian are fully healed (wiki). "Fully" is the last
     * quarter his Guardian form fights with. The wiki gives no rate or countdown for his: the Healthy Guardian's.
     */
    private void tickFormGiga(ServerLevel level, boolean master) {
        if (gigaAt < 0) {
            if (clock >= nextGiga) {
                nextGiga = clock + gigaEvery(master);
                gigaAt = clock + gigaWarn(master);
                SimBoss.say(level.getServer(), "The Professor", "These wounds are nothing for my Giga Heal!");
            }
            return;
        }
        if ((gigaAt - clock) % 20 == 0) {
            actionBar(level, "Giga Heal in " + (gigaAt - clock) / 20 + "s", ChatFormatting.LIGHT_PURPLE);
        }
        if (guardianForm != null && clock % 4 == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, guardianForm.getX(), guardianForm.getY() + 1,
                    guardianForm.getZ(), 10, 1.5, 1.5, 1.5, 0);
        }
        if (clock >= gigaAt) {
            gigaAt = -1;
            health.hp = Math.max(health.hp, health.max * TRANSFORM_SHARE);
            for (Rogue r : rogues) {
                r.health.hp = r.health.max;
            }
            SimBoss.say(level.getServer(), "The Professor",
                    "Another flawless Giga Heal. You are making this too easy for me!");
        }
    }

    /** His TNT Rain, at his base damage (wiki), on the Reinforced Guardian's clock (APPROXIMATE). */
    private void tickFormRain(ServerLevel level, boolean master) {
        if (clock >= nextTnt) {
            nextTnt = clock + tntEvery(master);
            startRain(level);
        }
        if (guardianForm != null && clock < tntUntil && (tntUntil - clock) % TNT_THROW_EVERY == 0) {
            throwTnt(level, guardianForm.position(), professorDamage());
        }
    }

    /** If the last phase runs long the arena floods (one block of water over the floor) and boats appear. */
    private void tickFlood(ServerLevel level) {
        if (floodAt < 0 || clock < floodAt) {
            return;
        }
        if (floodQueue == null) {
            floodQueue = floodSpots(level);
            SimBoss.tell(level.getServer(), Component.literal("The arena is flooding!")
                    .withStyle(ChatFormatting.AQUA));
            Vec3 m = middle(level);
            level.playSound(null, m.x, m.y, m.z, SoundEvents.AMBIENT_UNDERWATER_ENTER, SoundSource.HOSTILE, 1.5f,
                    0.8f);
            for (int[] o : PADS) {
                BlockPos at = arena.toWorld(arena.centre[0] + MIDDLE_DX + o[0], arena.centre[1],
                        arena.centre[2] + MIDDLE_DZ + o[1]);
                Boat b = new Boat(EntityTypes.OAK_BOAT, level, () -> Items.OAK_BOAT);
                b.snapTo(at.getX() + 0.5, ground(level, at.getX(), at.getZ(), at.getY() + 2) + 0.5,
                        at.getZ() + 0.5, 0f, 0f);
                level.addFreshEntity(b);
                boats.add(b);
            }
        }
        for (int i = 0; i < FLOOD_PER_TICK && !floodQueue.isEmpty(); i++) {
            BlockPos pos = floodQueue.remove(0);
            if (level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                flooded.add(pos);
            }
        }
    }

    /** Each column's feet block inside the ring, nearest the middle first; the outer edge left dry so it can't spill. */
    private List<BlockPos> floodSpots(ServerLevel level) {
        BlockPos c = arena.toWorld(arena.centre);
        int r = Math.max(1, arena.radius - 2);
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                int x = c.getX() + dx;
                int z = c.getZ() + dz;
                out.add(new BlockPos(x, ground(level, x, z, c.getY() + 4), z));
            }
        }
        out.sort((a, b) -> Double.compare(a.distSqr(c), b.distSqr(c)));
        return out;
    }

    private void unflood(ServerLevel level) {
        for (BlockPos pos : flooded) {
            if (level.getBlockState(pos).is(Blocks.WATER)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        flooded.clear();
        floodQueue = null;
        for (Boat b : boats) {
            b.discard();
        }
        boats.clear();
    }

    private void die(ServerLevel level) {
        // Hypixel's line, word for word: Boss Phases, the splits and Clean End read it as the boss down.
        SimBoss.say(level.getServer(), "The Professor", "What?! My Guardian power is unbeatable!");
        health.invulnerable = true;
        discardRogues();
        discardTnt();
        beams.clear();
        shards.clear();
        tntUntil = -1;
        gigaAt = -1;
        toPhase(Phase.DYING);
    }

    /** His last words, then Necron's lightning and Necron's lines (wiki), then the fight is over. */
    private void tickDying(ServerLevel level) {
        switch (phaseTicks) {
            case 30 -> SimBoss.say(level.getServer(), "The Professor", "How could you... I needed more power...");
            case 60 -> SimBoss.say(level.getServer(), "The Professor",
                    "I can't let my Master hear about this.. he'll kill m-");
            case 70 -> {
                if (guardianForm != null) {
                    LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, level);
                    bolt.setVisualOnly(true);
                    bolt.snapTo(guardianForm.getX(), guardianForm.getY(), guardianForm.getZ());
                    level.addFreshEntity(bolt);
                    level.sendParticles(ParticleTypes.EXPLOSION, guardianForm.getX(), guardianForm.getY() + 1,
                            guardianForm.getZ(), 5, 1, 1, 1, 0);
                    guardianForm.discard();
                    guardianForm = null;
                }
            }
            case 80 -> SimBoss.say(level.getServer(), "Necron", "That is enough, fool!");
            case 110 -> SimBoss.say(level.getServer(), "Necron", "Adventurers! Be careful of who you are messing with...");
            case 140 -> SimBoss.say(level.getServer(), "Necron", "Before I have to deal with you myself.");
            default -> {
            }
        }
        if (phaseTicks >= DYING_TICKS) {
            unflood(level);
            toPhase(Phase.DEAD);
        }
    }

    // ------------------------------------------------------------------ beams, shards, lightning

    private void tickBeams(ServerLevel level) {
        if (beams.isEmpty()) {
            return;
        }
        List<Beam> done = new ArrayList<>();
        for (Beam b : beams) {
            if (b.from == null || b.from.isRemoved() || b.target.isRemoved()) {
                done.add(b);
                continue;
            }
            Vec3 start = b.from.position().add(0, b.from.getBbHeight() / 2, 0);
            if (clock < b.lockAt || b.end == null) {
                // Tracking: the line follows him. Aimed through his chest and on past him.
                Vec3 aim = b.target.position().add(0, 1, 0).subtract(start);
                b.end = start.add(aim.normalize().scale(BEAM_LENGTH));
            }
            if (clock < b.fireAt) {
                if (clock % 2 == 0) {
                    boolean locked = clock >= b.lockAt;
                    drawLine(level, start, b.end, new DustParticleOptions(b.colour, locked ? 1.4f : 0.6f),
                            locked ? 0.5 : 1.2);
                }
                continue;
            }
            fire(level, b, start);
            done.add(b);
        }
        beams.removeAll(done);
    }

    private void fire(ServerLevel level, Beam b, Vec3 start) {
        drawLine(level, start, b.end, ParticleTypes.END_ROD, 0.8);
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            Vec3 chest = p.position().add(0, 1, 0);
            if (distanceToSegment(chest, start, b.end) <= BEAM_WIDTH) {
                Vec3 dir = b.end.subtract(start);
                hurtPlayer(level, p, b.damage, dir.x, dir.z, b.push, b.lift);
            }
        }
    }

    private void tickShards(ServerLevel level) {
        if (shards.isEmpty()) {
            return;
        }
        BlockParticleOption prismarine = new BlockParticleOption(ParticleTypes.BLOCK,
                Blocks.PRISMARINE.defaultBlockState());
        List<Shard> next = new ArrayList<>();
        List<ServerPlayer> players = SimBoss.playersInRing(level);
        for (Shard s : shards) {
            Vec3 pos = s.pos().add(s.vel());
            BlockPos block = BlockPos.containing(pos);
            if (clock >= s.dieAt() || !level.getBlockState(block).getCollisionShape(level, block).isEmpty()) {
                level.sendParticles(prismarine, pos.x, pos.y, pos.z, 8, 0.2, 0.2, 0.2, 0.05);
                continue;
            }
            level.sendParticles(prismarine, pos.x, pos.y, pos.z, 3, 0.1, 0.1, 0.1, 0);
            level.sendParticles(new DustParticleOptions(0x55CCAA, 1.2f), pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
            boolean hit = false;
            for (ServerPlayer p : players) {
                if (p.getBoundingBox().inflate(0.4).contains(pos)) {
                    SimVitals.dealMaxHealthShare(level, p, level.damageSources().magic(), s.share(), true);
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                next.add(new Shard(pos, s.vel(), s.dieAt(), s.share()));
            }
        }
        shards.clear();
        shards.addAll(next);
    }

    /** A lightning strike on a player: a bolt for show, the hit the fight's. */
    private static void strike(ServerLevel level, ServerPlayer p, double damage) {
        LightningBolt bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, level);
        bolt.setVisualOnly(true);
        bolt.snapTo(p.getX(), p.getY(), p.getZ());
        level.addFreshEntity(bolt);
        SimVitals.dealHypixel(level, p, level.damageSources().magic(), damage, false);
    }

    private static void drawLine(ServerLevel level, Vec3 a, Vec3 b,
                                 net.minecraft.core.particles.ParticleOptions particle, double step) {
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 0.01) {
            return;
        }
        Vec3 u = d.scale(1 / len);
        for (double s = 0; s <= len; s += step) {
            level.sendParticles(particle, a.x + u.x * s, a.y + u.y * s, a.z + u.z * s, 1, 0, 0, 0, 0);
        }
    }

    private static double distanceToSegment(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, p.subtract(a).dot(ab) / len2));
        return p.distanceTo(a.add(ab.scale(t)));
    }

    /**
     * Damage plus knockback along {@code (dx, dz)}: {@code damage} is Hypixel damage before the player's Defense
     * ({@link SimVitals#dealHypixel}).
     */
    private static void hurtPlayer(ServerLevel level, ServerPlayer p, double damage, double dx, double dz,
                                   double push, double lift) {
        SimVitals.dealHypixel(level, p, level.damageSources().magic(), damage, false);
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

    private static void actionBar(ServerLevel level, String text, ChatFormatting colour) {
        for (ServerPlayer p : SimBoss.playersInRing(level)) {
            p.sendSystemMessage(Component.literal(text).withStyle(colour), true);
        }
    }

    private static ServerPlayer nearest(List<ServerPlayer> players, Vec3 from) {
        ServerPlayer best = null;
        double bestD = Double.MAX_VALUE;
        for (ServerPlayer p : players) {
            double d = p.position().distanceToSqr(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static ServerPlayer nearestWithin(List<ServerPlayer> players, Vec3 from, double reach) {
        ServerPlayer p = nearest(players, from);
        return p != null && p.position().distanceTo(from) <= reach ? p : null;
    }

    private static void face(Mob mob, Vec3 at) {
        double dx = at.x - mob.getX();
        double dz = at.z - mob.getZ();
        double dy = at.y - mob.getEyeY();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mob.setYRot(yaw);
        mob.setXRot(pitch);
        mob.setYHeadRot(yaw);
        mob.setYBodyRot(yaw);
    }

    // ------------------------------------------------------------------ names and the bar

    private void nameTags() {
        for (Pad p : pads) {
            if (p.alive()) {
                String name = p.kind == Kind.CHAOS ? p.kind.display + " [Anger " + p.anger + "]" : p.kind.display;
                p.body.setCustomName(tag(name, p.health));
            }
        }
        for (Rogue r : rogues) {
            if (r.body != null) {
                r.body.setCustomName(tag(r.elder ? "Elder Rogue Guardian" : "Rogue Guardian", r.health));
            }
        }
        if (professor != null) {
            professor.setCustomName(tag("The Professor", health));
        }
        if (guardianForm != null) {
            guardianForm.setCustomName(tag("The Professor", health));
        }
    }

    private static Component tag(String name, SimBoss.Health h) {
        return Component.literal(name + " ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                .append(Component.literal(h.invulnerable ? "???" : SimBoss.shortHealth(h.hp))
                        .withStyle(ChatFormatting.GREEN).append(Component.literal("❤")
                                .withStyle(ChatFormatting.RED)));
    }

    @Override
    boolean isBoss(Entity entity) {
        if (entity == null) {
            return false;
        }
        if (entity == professor || entity == guardianForm) {
            return true;
        }
        for (Pad p : pads) {
            if (p.body == entity) {
                return true;
            }
        }
        return isRogueBody(entity);
    }

    @Override
    boolean hit(ServerLevel level, LivingEntity body, float amount) {
        for (Pad p : pads) {
            if (p.body == body) {
                if (!p.alive()) {
                    return false;
                }
                if (p.kind == Kind.HEALTHY && gigaAt >= 0) {
                    // Every hit during the countdown counts against the heal, even on M3's invulnerable Guardian.
                    gigaHits++;
                }
                boolean landed = p.health.hit(amount);
                if (landed && p.kind == Kind.CHAOS) {
                    anger(level, p);
                }
                if (landed && p.health.hp <= 0) {
                    killGuardian(level, p);
                }
                return landed;
            }
        }
        Rogue r = rogue(body);
        if (r != null) {
            boolean landed = r.health.hit(amount);
            if (landed && r.health.hp <= 0) {
                killRogue(level, r);
            }
            return landed;
        }
        if (body == professor && phase == Phase.GUARDIANS) {
            if (protectedNote == 0) {
                protectedNote = 40;
                for (ServerPlayer p : SimBoss.playersInRing(level)) {
                    p.sendSystemMessage(Component.literal("The Professor is protected by his Guardians!")
                            .withStyle(ChatFormatting.RED), true);
                }
            }
            return false;
        }
        return (phase == Phase.PROFESSOR || phase == Phase.GUARDIAN_FORM) && health.hit(amount);
    }

    @Override
    boolean defeated() {
        return phase == Phase.DEAD;
    }

    @Override
    void discard(ServerLevel level) {
        if (professor != null) {
            professor.discard();
            professor = null;
        }
        if (guardianForm != null) {
            guardianForm.discard();
            guardianForm = null;
        }
        discardGuardians();
        discardRogues();
        discardTnt();
        unflood(level);
        beams.clear();
        shards.clear();
    }

    private void discardGuardians() {
        for (Pad p : pads) {
            if (p.body != null) {
                p.body.discard();
                p.body = null;
            }
        }
        pads.clear();
    }

    @Override
    Component barName() {
        return switch (phase) {
            case INTRO, GUARDIANS -> Component.literal("The Professor's Guardians (" + livingGuardians() + " left)")
                    .withStyle(ChatFormatting.AQUA);
            case GUARDIAN_FORM, TRANSFORM, DYING -> Component.literal("The Professor (Guardian)")
                    .withStyle(ChatFormatting.LIGHT_PURPLE);
            default -> Component.literal("The Professor").withStyle(ChatFormatting.RED);
        };
    }

    @Override
    BossEvent.BossBarColor barColor() {
        return switch (phase) {
            case INTRO, GUARDIANS -> BossEvent.BossBarColor.BLUE;
            case GUARDIAN_FORM, TRANSFORM, DYING -> BossEvent.BossBarColor.PURPLE;
            default -> BossEvent.BossBarColor.RED;
        };
    }

    @Override
    float barProgress() {
        return switch (phase) {
            case INTRO -> 1f;
            case GUARDIANS -> {
                double hp = 0;
                double max = 0;
                for (Pad p : pads) {
                    hp += Math.max(0, p.health.hp);
                    max += p.health.max;
                }
                yield max <= 0 ? 0f : (float) (hp / max);
            }
            case TRANSFORM -> Math.min(1f, (float) phaseTicks / TRANSFORM_TICKS);
            default -> health.fraction();
        };
    }

    /** The Professor's body: no AI (the fight moves him), never despawns, never burns in the sim's daylight. */
    private static final class ProfessorBody extends Zombie {
        ProfessorBody(Level level) {
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
    }
}
