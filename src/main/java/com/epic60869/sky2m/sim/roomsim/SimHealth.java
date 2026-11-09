package com.epic60869.sky2m.sim.roomsim;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel-sized health for the Dungeon Sim's mobs.
 *
 * <p>Vanilla caps {@code MAX_HEALTH} at 1,024, so a Master Mode mob with a million health cannot be an ordinary
 * mob with a big number on it. The same answer {@link SimBoss} uses for its bosses is used here for every room mob: the
 * health lives in a {@link Pool} of our own, a player's hit subtracts from the pool and the vanilla hit is
 * refused (with the red flash and hurt sound a real hit gives), and only the hit that empties the pool is let
 * through - with the mob's vanilla health dropped to almost nothing first - so the mob dies a normal vanilla
 * death: kill credit, death animation, and the starred-death bookkeeping in {@link SimMobs} all see an ordinary
 * kill.
 *
 * <p>What a hit takes off the pool is NOT the vanilla damage the weapon dealt (the sim's weapons still hand vanilla
 * 10,000 so that unpooled things - puzzle blazes, bats - die to one hit) but what it is worth on Hypixel, rolled
 * by {@link SimDamage#roll} from the player's stats ({@link SimStats}), and each hit puts up a damage number.
 *
 * <p>A pool can also be a training dummy ({@code /simdummy}, {@link SimMobs#spawnTrainingDummy}): it never dies,
 * fills back up when emptied, and shows its DPS over the last five seconds beside its health.
 *
 * <p>Only players do damage. Anything with no attacker behind it (suffocating in a low ceiling, fall damage
 * now that mobs walk, water) is shrugged off, the way {@code SimMobs.environmental} always has; so is one mob
 * hitting another, which with real AI would otherwise mean a skeleton's stray arrows clearing the room for you.
 *
 * <p>Each pool has a label: the invisible armour stand above the mob (the "✯ Name 1.2M❤" stand starred mobs
 * carry, which the Starred Mobs feature and the room-clear counters read) or, for a mob that is already named,
 * the mob itself. The label shows the pool's live health and the stand is moved above its mob every tick, so a
 * mob that walks off takes its name with it.
 *
 * <p>Boss bodies are not pooled here: {@link SimBoss#onDamage} owns those, and an entity is only ever registered
 * by {@link SimMobs}, which never touches a boss.
 *
 * <p>Server thread only, except {@link #forget}, which only clears maps.
 */
public final class SimHealth {

    /** What kind of sim mob a pool belongs to; picks the row of the health and damage tables. */
    public enum Role { REGULAR, STARRED, MINIBOSS }

    /** Floor codes as {@link SimState#floorLabel} holds them, in the order the tables below are written. */
    private static final String[] FLOORS = {
            "E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};

    /** "No wiki row for this floor": {@link #fill} replaces it with the nearest floor of the same mode that has one. */
    private static final double N = Double.NaN;

    /** One Hypixel mob's health and base damage (before Defense) per floor, in {@link #FLOORS} order, gaps filled. */
    record MobStats(double[] health, double[] damage) {
        double health(int tier) {
            return health[tier];
        }

        double damage(int tier) {
            return damage[tier];
        }
    }

    private static MobStats stats(double[] health, double[] damage) {
        return new MobStats(fill(health), fill(damage));
    }

    /**
     * Fills a floor with no wiki row (a mob that does not spawn there but that the sim can still place there - a Fel
     * on F1, a King Midas on M1) from the nearest floor of the same mode that has one: Normal floors from Normal
     * floors (E-F7), Master Mode from Master Mode (M1-M7). APPROXIMATE for those floors by construction.
     */
    private static double[] fill(double[] raw) {
        double[] out = raw.clone();
        fillRange(raw, out, 0, 8);
        fillRange(raw, out, 8, 15);
        return out;
    }

    private static void fillRange(double[] raw, double[] out, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Double.isNaN(raw[i])) {
                continue;
            }
            for (int d = 1; d < to - from; d++) {
                if (i + d < to && !Double.isNaN(raw[i + d])) {
                    out[i] = raw[i + d];
                    break;
                }
                if (i - d >= from && !Double.isNaN(raw[i - d])) {
                    out[i] = raw[i - d];
                    break;
                }
            }
        }
    }

    // Hypixel's base stats, from each mob's "Base Stats" table on the official wiki (hypixelskyblock.minecraft.wiki,
    // read 2026-11-01). Hypixel gives a starred mob the same stats as an unstarred one of its type - the star only
    // marks the mobs a room clear needs (https://hypixelskyblock.minecraft.wiki/w/Dungeon_Mobs) - so the sim does
    // too. Hypixel's live numbers also climb a little as more of the dungeon is explored and with a mob's
    // attributes (Healthy, Fortified); these are the base.

    /**
     * The sim's zombie: the Zombie Grunt on E, F1, F2, M1 and M2 (https://hypixelskyblock.minecraft.wiki/w/Zombie_Grunt)
     * and the Zombie Soldier from F3/M3 (https://hypixelskyblock.minecraft.wiki/w/Zombie_Soldier), the commonest
     * zombie of each floor.
     */
    static final MobStats ZOMBIE = stats(
            new double[]{7_000, 24_000, 32_400, 77_000, 156_000, 240_000, 500_000, 1_200_000,
                    1_600_000, 3_000_000, 6_000_000, 8_750_000, 12_000_000, 18_000_000, 36_000_000},
            new double[]{201, 244, 336, 738, 1_004, 2_217, 3_456, 8_640,
                    12_600, 21_600, 39_600, 56_250, 75_600, 100_800, 111_600});

    /**
     * The sim's skeleton: the Skeleton Grunt on the Entrance (https://hypixelskyblock.minecraft.wiki/w/Skeleton_Grunt)
     * and the Skeleton Soldier from F1 (https://hypixelskyblock.minecraft.wiki/w/Skeleton_Soldier). Its arrows hit
     * for its damage too.
     */
    static final MobStats SKELETON = stats(
            new double[]{14_000, 22_400, 31_200, 49_500, 96_000, 187_500, 360_000, 750_000,
                    1_600_000, 3_000_000, 7_200_000, 8_400_000, 9_600_000, 14_400_000, 28_000_000},
            new double[]{226, 324, 453, 864, 1_069, 2_620, 4_032, 10_440,
                    16_200, 27_000, 60_750, 67_500, 74_250, 94_500, 108_000});

    /** Fels spawn from F5/M5 (https://hypixelskyblock.minecraft.wiki/w/Fels); lower floors take F5's or M5's. */
    static final MobStats FEL = stats(
            new double[]{N, N, N, N, N, 450_000, 700_000, 1_500_000, N, N, N, N, 45_000_000, 80_000_000, 160_000_000},
            new double[]{N, N, N, N, N, 3_919, 5_376, 14_000, N, N, N, N, 105_000, 140_000, 168_000});

    /** The Mimic, F6/M6 and up (https://hypixelskyblock.minecraft.wiki/w/Mimic); lower floors take F6's or M6's. */
    static final MobStats MIMIC = stats(
            new double[]{N, N, N, N, N, N, 1_500_000, 3_000_000, N, N, N, N, N, 150_000_000, 180_000_000},
            new double[]{N, N, N, N, N, N, 8_640, 17_280, N, N, N, N, N, 135_000, 162_000});

    /**
     * A crypt's undead (https://hypixelskyblock.minecraft.wiki/w/Crypt_Undead): health per floor, one flat damage per
     * mode (936 Normal, 11,700 Master Mode).
     */
    static final MobStats CRYPT_UNDEAD = stats(
            new double[]{22_500, 45_000, 67_500, 101_250, 151_875, 227_812, 341_718, 512_578,
                    800_000, 1_200_000, 1_800_000, 2_700_000, 4_050_000, 6_075_000, 9_112_500},
            new double[]{936, 936, 936, 936, 936, 936, 936, 936,
                    11_700, 11_700, 11_700, 11_700, 11_700, 11_700, 11_700});

    /**
     * The Watcher's undead (blood mobs). The Revoker (https://hypixelskyblock.minecraft.wiki/w/Revoker) and the Psycho
     * (https://hypixelskyblock.minecraft.wiki/w/Psycho) have identical tables, which the other blood mobs are taken
     * to share (their own pages were not checked). Hypixel randomizes each one's max health around these.
     */
    static final MobStats BLOOD = stats(
            new double[]{14_000, 17_000, 32_000, 41_000, 60_000, 60_000, 75_000, 250_000,
                    800_000, 900_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, 6_000_000},
            new double[]{972, 1_440, 2_340, 2_592, 3_240, 3_960, 5_760, 12_960,
                    48_600, 56_700, 64_800, 72_900, 81_000, 89_100, 97_200});

    // Minibosses: each wiki table lists stats by mob level, and a floor can spawn two levels; the sim takes the
    // highest level listed for the floor. A miniboss that does not spawn on a floor (no Shadow Assassin before F3)
    // takes the nearest floor's numbers.

    /** Lost Adventurer (Young/Unstable/Holy), https://hypixelskyblock.minecraft.wiki/w/Lost_Adventurer. */
    static final MobStats LOST_ADVENTURER = stats(
            new double[]{130_000, 130_000, 150_000, 302_500, 660_000, 1_200_000, 2_600_000, 6_600_000,
                    14_250_000, 24_000_000, 48_000_000, 54_000_000, 60_000_000, 92_000_000, 150_000_000},
            new double[]{539, 539, 712, 1_440, 1_798, 3_729, 5_702, 14_400,
                    37_800, 63_000, 121_500, 135_000, 148_500, 216_000, 216_000});

    /** Frozen Adventurer, F4/M4 and up, https://hypixelskyblock.minecraft.wiki/w/Frozen_Adventurer. */
    static final MobStats FROZEN_ADVENTURER = stats(
            new double[]{N, N, N, N, 825_000, 1_600_000, 2_600_000, 6_600_000,
                    N, N, N, 60_000_000, 66_000_000, 134_000_000, 160_000_000},
            new double[]{N, N, N, N, 2_331, 3_774, 6_300, 13_200, N, N, N, 123_750, 135_000, 210_000, 180_000});

    /** Shadow Assassin, F3/M3 and up, https://hypixelskyblock.minecraft.wiki/w/Shadow_Assassin. */
    static final MobStats SHADOW_ASSASSIN = stats(
            new double[]{N, N, N, 605_000, 1_200_000, 2_250_000, 4_200_000, 10_000_000,
                    N, N, 55_000_000, 75_000_000, 84_000_000, 124_000_000, 140_000_000},
            new double[]{N, N, N, 1_072, 1_208, 2_440, 4_537, 10_080, N, N, 91_875, 126_000, 141_750, 210_000, 210_000});

    /** Angry Archaeologist, https://hypixelskyblock.minecraft.wiki/w/Angry_Archaeologist. */
    static final MobStats ANGRY_ARCHAEOLOGIST = stats(
            new double[]{21_000, 21_000, 33_000, 135_000, 240_000, 320_000, 420_000, 900_000,
                    1_875_000, 3_000_000, 6_000_000, 6_900_000, 7_800_000, 12_000_000, 18_000_000},
            new double[]{466, 466, 1_026, 2_419, 3_340, 6_400, 7_560, 17_600,
                    36_450, 57_600, 102_600, 108_000, 116_100, 165_600, 165_600});

    /**
     * King Midas, F3/M3 and up, https://hypixelskyblock.minecraft.wiki/w/King_Midas. The page's Master Mode damage
     * disagrees with its own 0.26.1 changelog (46,800-136,800); the infobox numbers are used.
     */
    static final MobStats KING_MIDAS = stats(
            new double[]{N, N, N, 165_000, 480_000, 1_050_000, 2_100_000, 4_300_000,
                    N, N, 8_600_000, 12_000_000, 15_600_000, 22_400_000, 35_000_000},
            new double[]{N, N, N, 1_692, 2_332, 4_536, 6_912, 18_720, N, N, 52_000, 75_500, 102_000, 140_000, 152_000});

    /**
     * Bonzo's summoned Undead, F1/M1 (https://hypixelskyblock.minecraft.wiki/w/Bonzo): 45,000 / 15,000,000 health,
     * 1,440 / 36,000 damage. Other floors (a /boss from elsewhere) take F1's or M1's.
     */
    static final MobStats BONZO_UNDEAD = stats(
            new double[]{N, 45_000, N, N, N, N, N, N, 15_000_000, N, N, N, N, N, N},
            new double[]{N, 1_440, N, N, N, N, N, N, 36_000, N, N, N, N, N, N});

    /** Scarf's four Undead, F2/M2 (https://hypixelskyblock.minecraft.wiki/w/Scarf); other floors take F2's or M2's. */
    static final MobStats UNDEAD_WARRIOR = scarf(500_000, 75_000_000, 2_880, 54_000);
    static final MobStats UNDEAD_ARCHER = scarf(400_000, 60_000_000, 2_752, 51_600);
    static final MobStats UNDEAD_MAGE = scarf(400_000, 60_000_000, 2_432, 45_600);
    static final MobStats UNDEAD_PRIEST = scarf(600_000, 90_000_000, 1_920, 36_000);

    /**
     * Sadan's Terracottas, F6/M6 (https://hypixelskyblock.minecraft.wiki/w/Terracotta): 12,000,000 / 250,000,000
     * health, 9,900 / 95,900 damage. Other floors take F6's or M6's.
     */
    static final MobStats TERRACOTTA = stats(
            new double[]{N, N, N, N, N, N, 12_000_000, N, N, N, N, N, N, 250_000_000, N},
            new double[]{N, N, N, N, N, N, 9_900, N, N, N, N, N, N, 95_900, N});

    private static MobStats scarf(double f2, double m2, double f2Damage, double m2Damage) {
        return stats(new double[]{N, N, f2, N, N, N, N, N, N, m2, N, N, N, N, N},
                new double[]{N, N, f2Damage, N, N, N, N, N, N, m2Damage, N, N, N, N, N});
    }

    /**
     * The Hypixel mob a sim mob stands for, by the name its pool is registered under (SimMobs registers a room mob
     * under its entity type's name, "Zombie" or "Skeleton"). Null for anything without a wiki table - the training
     * dummy, whose health is the player's choice.
     */
    static MobStats statsFor(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "Zombie":
                return ZOMBIE;
            case "Skeleton":
                return SKELETON;
            case "Fel":
                return FEL;
            case "Mimic":
                return MIMIC;
            case "Crypt Undead":
            case "Crypt Prince":
                return CRYPT_UNDEAD;
            case "Lost Adventurer":
            case "Redstone Warrior":
                // The Redstone Warrior's wiki table matches the Lost Adventurer's row for row
                // (https://hypixelskyblock.minecraft.wiki/w/Redstone_Warrior).
                return LOST_ADVENTURER;
            case "Frozen":
            case "Frozen Adventurer":
                return FROZEN_ADVENTURER;
            case "Shadow Assassin":
                return SHADOW_ASSASSIN;
            case "Angry Archaeologist":
                return ANGRY_ARCHAEOLOGIST;
            case "King Midas":
                return KING_MIDAS;
            case "Undead":
                return BONZO_UNDEAD;
            case "Undead Warrior":
                return UNDEAD_WARRIOR;
            case "Undead Archer":
                return UNDEAD_ARCHER;
            case "Undead Mage":
                return UNDEAD_MAGE;
            case "Undead Priest":
                return UNDEAD_PRIEST;
            case "Terracotta":
                return TERRACOTTA;
            default:
                break;
        }
        for (String blood : SimWatcher.MOB_NAMES) {
            if (blood.equals(name)) {
                return BLOOD;
            }
        }
        return null;
    }

    /** A named sim mob's Hypixel health on this floor ({@link #statsFor}); NaN when it has no table. */
    public static double healthOf(String name, String floor) {
        MobStats st = statsFor(name);
        return st == null ? Double.NaN : st.health(tier(floor));
    }

    /** A named sim mob's Hypixel base damage (before Defense) on this floor; NaN when it has no table. */
    public static double damageOf(String name, String floor) {
        MobStats st = statsFor(name);
        return st == null ? Double.NaN : st.damage(tier(floor));
    }

    /**
     * Vanilla melee damage per floor, in half-hearts BEFORE the difficulty scaling vanilla applies to mob hits (the
     * sim runs on EASY, which turns {@code d} into {@code min(d, d / 2 + 1)}). A pooled mob's hit on a player is NOT
     * this but its pool's Hypixel {@link Pool#hit} ({@link SimVitals}); this is only what the vanilla attack carries,
     * and what a hit without a Hypixel number is scaled from ({@link SimVitals#hypixelDamage}).
     */
    private static final float[] MELEE = {2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 10};

    private static final Map<UUID, Pool> POOLS = new ConcurrentHashMap<>();

    /**
     * Hypixel hits for boss-fight mobs that have no pool here (their health is a {@link SimBoss.Health} of the
     * fight's): Thorn's spirit animals, the Wither King's dragons. {@link SimVitals} reads it as it reads a pool's
     * {@link Pool#hit}.
     */
    private static final Map<UUID, Double> BOSS_HITS = new ConcurrentHashMap<>();

    /** Gives a boss-fight mob its Hypixel hit on a player (before Defense). Server thread. */
    static void setBossHit(Entity mob, double hit) {
        if (mob != null && hit > 0) {
            BOSS_HITS.put(mob.getUUID(), hit);
        }
    }

    /**
     * Per-mob multipliers on a hit that lands on a Tank ({@link SimVitals}): Thorn's wolf does 3.5x to Tanks on
     * Hypixel (https://hypixelskyblock.minecraft.wiki/w/Thorn). Cleared with the pools ({@link #forget}).
     */
    private static final Map<UUID, Double> TANK_MULTIPLIERS = new ConcurrentHashMap<>();

    /**
     * Makes this mob's hits on a Tank (the hit player's class, {@link SimParty#dungeonClass}) {@code multiplier}
     * times as strong, before Defense. A non-positive or non-finite value removes it.
     * Server thread.
     */
    static void setTankMultiplier(Entity mob, double multiplier) {
        if (mob == null) {
            return;
        }
        if (!(multiplier > 0) || Double.isInfinite(multiplier)) {
            TANK_MULTIPLIERS.remove(mob.getUUID());
        } else {
            TANK_MULTIPLIERS.put(mob.getUUID(), multiplier);
        }
    }

    /** {@link #setTankMultiplier}'s value for this mob, 1 when none was set. */
    static double tankMultiplier(Entity mob) {
        if (mob == null) {
            return 1;
        }
        Double m = TANK_MULTIPLIERS.get(mob.getUUID());
        return m == null ? 1 : m;
    }

    /** A sim mob's Hypixel hit on a player: its pool's, a boss mob's ({@link #setBossHit}), or 0 for none. */
    static double hitOf(Entity mob) {
        if (mob == null) {
            return 0;
        }
        Pool pool = POOLS.get(mob.getUUID());
        if (pool != null && pool.hit > 0) {
            return pool.hit;
        }
        Double boss = BOSS_HITS.get(mob.getUUID());
        return boss == null ? 0 : boss;
    }

    /** The window a training dummy's DPS is averaged over: five seconds. */
    static final int DPS_WINDOW_TICKS = 100;

    private SimHealth() {
    }

    /** The row of the tables for a floor code; anything unknown counts as F7, the sim's default floor. */
    static int tier(String floor) {
        if (floor != null) {
            String f = floor.toUpperCase(Locale.ROOT);
            for (int i = 0; i < FLOORS.length; i++) {
                if (FLOORS[i].equals(f)) {
                    return i;
                }
            }
        }
        return 7;
    }

    /** Whether a floor code is a Master Mode floor (M1-M7). */
    static boolean master(String floor) {
        return tier(floor) >= 8;
    }

    /**
     * Picks a floor's value from a boss's two wiki numbers: the Normal one on E-F7, the Master Mode one on M1-M7.
     * A boss fought off its own floor (/boss from elsewhere) keeps its own floor's numbers for the mode.
     */
    static double byMode(double normal, double master) {
        return master(SimState.floorLabel()) ? master : normal;
    }

    /**
     * A sim mob's health on that floor: the sim's zombie ({@link #ZOMBIE}) for a regular or starred mob (Hypixel
     * gives them the same stats), the Lost Adventurer for a miniboss. A mob registered under a name with its own
     * table ({@link #statsFor}) gets that table's number instead, whatever was asked for here.
     */
    public static double maxHealth(String floor, Role role) {
        int t = tier(floor);
        return role == Role.MINIBOSS ? LOST_ADVENTURER.health(t) : ZOMBIE.health(t);
    }

    /** A sim mob's vanilla melee damage on that floor (before difficulty scaling); see {@link #MELEE}. */
    public static float meleeDamage(String floor, Role role) {
        float base = MELEE[tier(floor)];
        return switch (role) {
            case REGULAR -> base;
            case STARRED -> base * 1.25f;
            case MINIBOSS -> base * 1.5f;
        };
    }

    /**
     * Health the way Hypixel writes it on a name tag: 1.5k, 337.5k, 250k, 1.2M, 6.6M. One decimal, dropped when
     * it is a zero.
     */
    public static String shortHealth(double hp) {
        if (hp >= 1_000_000) {
            return trim(hp / 1_000_000) + "M";
        }
        if (hp >= 1_000) {
            return trim(hp / 1_000) + "k";
        }
        return String.valueOf((long) Math.ceil(Math.max(0, hp)));
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** One mob's health. Every field is touched on the server thread only. */
    static final class Pool {
        final UUID id;
        LivingEntity mob;
        final String name;
        final boolean starred;
        double max;
        double hp;
        /**
         * The mob's own Hypixel hit on a player, before Defense ({@link SimVitals}): its melee swing or arrow. 0 for a
         * mob without a wiki table, whose vanilla hit is scaled instead.
         */
        double hit;
        /** Fraction of each hit that lands: below 1 for an armoured mob (the Lost Adventurer). */
        double taken = 1.0;
        /** Refuses hits outright while true. */
        boolean invulnerable;
        /** Survive the first hit that would empty the pool on 1 health, once - for a mob that heals when low. */
        boolean surviveOnce;
        /** Hide the label (the Shadow Assassin while it is invisible). */
        boolean hideLabel;
        /** The killing hit has been let through; the vanilla death is in progress. */
        boolean dying;
        /** The stand above the mob, or null when the mob carries its own name ({@link #selfLabel}). */
        UUID standId;
        Entity stand;
        boolean selfLabel;
        boolean dirty = true;
        private boolean labelShown = true;
        /** A training dummy: refills instead of dying, and keeps {@link #recent} for its DPS. */
        boolean dummy;
        /** A dummy's hits as {game time, damage}, newest last, kept for {@link #DPS_WINDOW_TICKS}. */
        final java.util.ArrayDeque<double[]> recent = new java.util.ArrayDeque<>();
        /** Game time of the first hit since a dummy was last full, for "emptied in 4.2s". */
        long firstHitTime = -1;

        Pool(LivingEntity mob, double max, String name, boolean starred) {
            this.id = mob.getUUID();
            this.mob = mob;
            this.max = max;
            this.hp = max;
            this.name = name;
            this.starred = starred;
        }

        double fraction() {
            return max <= 0 ? 0 : hp / max;
        }

        void heal(double amount) {
            if (dying) {
                return;
            }
            hp = Math.min(max, hp + amount);
            dirty = true;
        }

        Component label() {
            // Hypixel's stands lead with a space (" ✯ Lost Adventurer 337.5k❤"); a mob's own name does not.
            MutableComponent out = Component.literal(selfLabel ? "" : " ");
            if (starred) {
                out.append(Component.literal("✯ ").withStyle(ChatFormatting.GOLD));
            }
            ChatFormatting colour = fraction() > 0.5 ? ChatFormatting.GREEN
                    : fraction() > 0.25 ? ChatFormatting.YELLOW : ChatFormatting.RED;
            out.append(Component.literal(name + " ").withStyle(ChatFormatting.RED))
                    .append(Component.literal(shortHealth(hp)).withStyle(colour))
                    .append(Component.literal("❤").withStyle(ChatFormatting.RED));
            if (dummy) {
                out.append(Component.literal("  " + shortHealth(dps()) + " DPS").withStyle(ChatFormatting.YELLOW));
            }
            return out;
        }

        /** A dummy's damage per second over the last five seconds (or since its first hit, if sooner). */
        double dps() {
            if (recent.isEmpty() || mob == null) {
                return 0;
            }
            long now = mob.level().getGameTime();
            double sum = 0;
            for (double[] h : recent) {
                sum += h[1];
            }
            double seconds = (now - recent.peekFirst()[0]) / 20.0;
            return sum / Math.max(1.0, Math.min(DPS_WINDOW_TICKS / 20.0, seconds));
        }

        void prune(long now) {
            while (!recent.isEmpty() && now - recent.peekFirst()[0] > DPS_WINDOW_TICKS) {
                recent.pollFirst();
            }
        }
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SimHealth::onDamage);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (SimState.isActive() && !POOLS.isEmpty()) {
                tick(server.overworld());
            }
        });
    }

    /**
     * Gives a mob a pool. Server thread; call after the mob is in the level.
     *
     * @param stand the label stand above it (already added to the level), or null to write the health into the
     *              mob's own name instead
     */
    static Pool register(LivingEntity mob, double max, String name, boolean starred, Entity stand) {
        // A mob with a Hypixel table ({@link #statsFor}) gets that floor's real health and hit, whatever the caller
        // sized it at - so SimMobs, SimWatcher, Bonzo's and Scarf's summons all get the wiki's numbers.
        MobStats real = statsFor(name);
        int t = tier(SimState.floorLabel());
        Pool pool = new Pool(mob, real != null ? real.health(t) : max, name, starred);
        if (real != null) {
            pool.hit = real.damage(t);
        }
        if (stand != null) {
            pool.stand = stand;
            pool.standId = stand.getUUID();
        } else {
            pool.selfLabel = true;
        }
        POOLS.put(pool.id, pool);
        writeLabel(pool);
        return pool;
    }

    /** The pool of a sim mob, or null. */
    static Pool get(Entity entity) {
        return entity == null ? null : POOLS.get(entity.getUUID());
    }

    /** Whether this entity is a sim mob with a health pool. */
    public static boolean isPooled(Entity entity) {
        return get(entity) != null;
    }

    /** Forgets every pool, for {@link SimMobs#clear} and a rebuilt floor. The entities are SimMobs' to remove. */
    static void forget() {
        POOLS.clear();
        BOSS_HITS.clear();
        TANK_MULTIPLIERS.clear();
    }

    /**
     * Turns a player's hit into pool damage. Returns false (refusing the vanilla hit) for everything but the
     * killing blow; never touches an entity without a pool.
     */
    private static boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!SimState.isActive()) {
            return true;
        }
        Pool pool = POOLS.get(entity.getUUID());
        if (pool == null) {
            return true;
        }
        if (pool.dying) {
            return true;
        }
        if (!(source.getEntity() instanceof Player attacker)) {
            // The environment (no attacker at all) and other mobs. See the class doc.
            return false;
        }
        if (pool.invulnerable || amount <= 0 || SimDeath.isGhostAttack(source)) {
            // A ghost's arrows, abilities and beams land nothing, like his melee (SimDeath).
            return false;
        }
        pool.mob = entity;
        // Hypixel's damage for this hit from his stats, not the weapon's vanilla number. See the class doc.
        SimDamage.Hit rolled = SimDamage.roll(attacker, source, entity);
        // The items' effects on the hit (SimItemEffects): poisons, arrows, Last Breath, Ice Spray, the Halberd.
        rolled = SimItemEffects.onPlayerHit(attacker, source, entity, rolled, pool.taken);
        SimDamage.Hit hit = new SimDamage.Hit(rolled.perStrike() * pool.taken, rolled.crit(), rolled.strikes());
        double dealt = hit.total();
        if (entity.level() instanceof ServerLevel numbers) {
            SimDamage.showNumber(numbers, entity, hit);
        }
        if (pool.dummy) {
            long now = entity.level().getGameTime();
            if (pool.firstHitTime < 0) {
                pool.firstHitTime = now;
            }
            pool.recent.addLast(new double[]{now, dealt});
            pool.prune(now);
            pool.hp -= dealt;
            if (pool.hp <= 0) {
                double seconds = Math.max(0.05, (now - pool.firstHitTime) / 20.0);
                attacker.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "§6Training Dummy §7emptied in §e%.2fs §7(%s ❤ at §e%s §7DPS) - refilled.", seconds,
                        shortHealth(pool.max), shortHealth(pool.max / seconds))));
                pool.hp = pool.max;
                pool.firstHitTime = -1;
            }
            pool.dirty = true;
            if (entity.level() instanceof ServerLevel level) {
                level.broadcastDamageEvent(entity, source);
            }
            return false;
        }
        pool.hp -= dealt;
        pool.dirty = true;
        if (pool.hp <= 0 && pool.surviveOnce) {
            pool.surviveOnce = false;
            pool.hp = 1;
        }
        if (pool.hp > 0) {
            if (entity.level() instanceof ServerLevel level) {
                level.broadcastDamageEvent(entity, source);
            }
            if (entity instanceof Mob m && m.getTarget() == null && !attacker.isCreative()
                    && !attacker.isSpectator()) {
                m.setTarget(attacker);
            }
            return false;
        }
        // The killing blow: let it through onto almost no vanilla health, so it kills the vanilla way.
        pool.hp = 0;
        pool.dying = true;
        writeLabel(pool);
        entity.setHealth(Math.min(entity.getHealth(), 0.01f));
        return true;
    }

    /** Keeps each stand over its mob and its name current, and drops the pools of mobs that are gone. */
    private static void tick(ServerLevel level) {
        for (var it = POOLS.values().iterator(); it.hasNext(); ) {
            Pool pool = it.next();
            LivingEntity mob = pool.mob;
            if (mob.isRemoved()) {
                Entity.RemovalReason reason = mob.getRemovalReason();
                if (reason != null && !reason.shouldDestroy()) {
                    // Unloaded with its chunk, not dead: a reloaded mob is a new object with the same id.
                    if (level.getEntity(pool.id) instanceof LivingEntity reloaded) {
                        pool.mob = reloaded;
                        mob = reloaded;
                    } else {
                        continue;
                    }
                } else {
                    discardStand(level, pool);
                    it.remove();
                    continue;
                }
            }
            if (!mob.isAlive()) {
                discardStand(level, pool);
                it.remove();
                continue;
            }
            Entity stand = stand(level, pool);
            if (stand != null) {
                double y = mob.getY() + mob.getBbHeight() + 0.1;
                if (stand.distanceToSqr(mob.getX(), y, mob.getZ()) > 0.0004) {
                    stand.setPos(mob.getX(), y, mob.getZ());
                }
                if (pool.labelShown == pool.hideLabel) {
                    pool.labelShown = !pool.hideLabel;
                    stand.setCustomNameVisible(pool.labelShown);
                }
            }
            if (pool.dummy && level.getGameTime() % 10 == 0) {
                // Its DPS falls off as old hits leave the window, with or without new ones.
                pool.prune(level.getGameTime());
                pool.dirty = true;
            }
            if (pool.dirty) {
                writeLabel(pool);
            }
        }
    }

    private static Entity stand(ServerLevel level, Pool pool) {
        if (pool.selfLabel || pool.standId == null) {
            return null;
        }
        if (pool.stand == null || pool.stand.isRemoved()) {
            pool.stand = level.getEntity(pool.standId);
        }
        return pool.stand;
    }

    private static void writeLabel(Pool pool) {
        pool.dirty = false;
        if (pool.selfLabel) {
            pool.mob.setCustomName(pool.label());
            pool.mob.setCustomNameVisible(!pool.hideLabel);
        } else if (pool.stand != null && !pool.stand.isRemoved()) {
            pool.stand.setCustomName(pool.label());
        }
    }

    private static void discardStand(ServerLevel level, Pool pool) {
        Entity stand = stand(level, pool);
        if (stand != null) {
            stand.discard();
        }
    }
}
