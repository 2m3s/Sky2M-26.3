package com.epic60869.sky2m.sim.roomsim;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The enchantments (and the weapon reforges with a damage ability) in Hypixel's damage formula, for
 * {@link SimDamage#roll}. Values from hypixelskyblock.minecraft.wiki (each enchant's page and Damage Calculation),
 * read 2026-11-01.
 *
 * <p>Where each one goes in the formula (Damage Calculation's buckets):
 * <ul>
 * <li><b>Additive bucket</b> {@code 1 + sum/100} ({@link #additive}): Sharpness (melee), Power (arrows), Smite (vs
 *     Undead/Skeletal/Wither), Ender Slayer (vs Ender mobs), Gravity (vs Airborne: bats and the M7 dragons), Snipe
 *     ({@code k x distance/10}), First Strike (first melee hit on a mob), Triple-Strike (first three), Giant Killer,
 *     Prosecute, Execute, Titan Killer, Swarm ({@code k x min(enemies within 10, 10)}, the wiki marks its bucket
 *     [Confirm]), One For All, and Precise's +10% on an arrow's head hit. Magic gets only Execute and Swarm, the two
 *     the wiki says work on abilities.</li>
 * <li><b>Separate multipliers</b> ({@link #multiplier}, {@link #critMultiplier}): Thunderlord ({@code x(1 + k/100)}
 *     on every 3rd melee hit on the same mob, the lightning), Duplex ({@code x(1 + k/100)} for its second arrow, twice
 *     that without Piercing - the user's spec: "without Piercing your duplex arrow hits twice"), and on a crit only
 *     Fabled ({@code x U(1, 1.15)}) and Overload's mega crit ({@code x(1 + k/100)} with chance {@code (CC - 100)/100}).
 *     Loving armour is {@code x1.05} on abilities ({@link #magicMultiplier}).</li>
 * <li><b>Add damage</b>: Soul Eater, {@code + 2 x level x} the last killed mob's damage, added after the
 *     multipliers and multiplied only by the crit: on the next crit after a kill, which uses it up.</li>
 * <li><b>Over time and per hit</b> ({@link #after}): Venomous (stacks of saved damage dealing {@code k%} each per
 *     second for 5 s, up to 40, and a 4% slow - 20% cut by 80% in the Catacombs), Fire Aspect / Flame (a burn of
 *     {@code k%} of the hit per second, times Duplex's fire multiplier), Lethality (stacks of Defense shred on the
 *     target), Life Steal (flat heal per hit, not per Ferocity strike), Fatal Tempo (+{@code k%} Ferocity per hit up to
 *     +200%, gone 3 s after the last attack).</li>
 * <li><b>Stats</b>: Chimera copies the pet's stats into the sword's ({@link #chimera}). Vicious, Critical,
 *     Toxophilite and Overload's flat Crit Chance / Crit Damage are plain stats and are summed with the item's
 *     stats by the gear system ({@code SimItemBuild#stats}), never here.</li>
 * </ul>
 *
 * <p>Every counter is per player per target (First/Triple Strike, Thunderlord, Venomous stacks, the burn), so party
 * guests each have their own; Lethality and Duplex's fire are debuffs on the target, shared. Server thread only.
 */
public final class SimEnchants {

    // ------------------------------------------------------------------ the tables (index = level - 1)

    private static final double[] SHARPNESS = {5, 10, 15, 20, 30, 40, 50};
    /** Smite and Ender Slayer share Sharpness's table since 0.23.5. */
    private static final double[] MOB_SLAYER = SHARPNESS;
    private static final double[] GRAVITY = {5, 10, 15, 20, 30, 40};
    private static final double[] POWER = {8, 16, 24, 32, 40, 50, 65};
    private static final double[] GIANT_KILLER_K = {0.1, 0.2, 0.3, 0.4, 0.6, 0.9, 1.2};
    private static final double[] GIANT_KILLER_CAP = {5, 10, 15, 20, 30, 45, 65};
    private static final double[] PROSECUTE = {0.1, 0.2, 0.3, 0.4, 0.7, 1.0};
    /** Prosecute's cap. The wiki gives it for VI (Feb 2022); lower levels are capped the same here. */
    private static final double PROSECUTE_CAP = 40;
    private static final double[] EXECUTE = {0.2, 0.4, 0.6, 0.8, 1.0, 1.25};
    private static final double[] TITAN_KILLER_K = {2, 4, 6, 8, 12, 16, 20};
    private static final double[] TITAN_KILLER_CAP = {6, 12, 18, 24, 40, 60, 80};
    private static final double[] THUNDERLORD = {8, 16, 24, 32, 40, 50, 60};
    private static final double[] VENOMOUS = {0.2, 0.4, 0.6, 0.8, 1.2, 1.6, 2.0};
    private static final double[] LETHALITY = {1.2, 2.4, 3.6, 4.8, 6, 9};
    /** Fire Aspect: seconds and % of the hit per second. */
    private static final double[][] FIRE_ASPECT = {{3, 3}, {4, 6}, {4, 9}};
    private static final double[][] FLAME = {{3.5, 3}, {4, 6}};

    static final double PRECISE_HEAD = 10;
    static final double FABLED_MAX = 0.15;
    static final double LOVING = 1.05;
    static final double PIERCING_EXTRA = 0.25;
    static final double FATAL_TEMPO_CAP = 200;
    static final int VENOMOUS_MAX_STACKS = 40;
    static final int LETHALITY_MAX_STACKS = 4;
    static final int SWARM_MAX = 10;

    private static final int VENOMOUS_TICKS = 100;
    private static final int LETHALITY_TICKS = 80;
    private static final int FATAL_TEMPO_TICKS = 60;
    private static final int DUPLEX_FIRE_TICKS = 60 * 20;
    /** Venomous's slow: 20% at VII, "80% less in Catacombs" - and the sim is always the Catacombs. */
    private static final double VENOMOUS_SLOW = 0.2 * 0.2;
    private static final Identifier VENOMOUS_SLOW_ID = Identifier.fromNamespaceAndPath("sky2m", "sim_venomous");

    private static double table(double[] t, int level) {
        return level <= 0 ? 0 : t[Math.min(level, t.length) - 1];
    }

    // ------------------------------------------------------------------ one enchant's number (also for tests)

    public static double sharpness(int level) {
        return table(SHARPNESS, level);
    }

    public static double smite(int level) {
        return table(MOB_SLAYER, level);
    }

    public static double gravity(int level) {
        return table(GRAVITY, level);
    }

    public static double power(int level) {
        return table(POWER, level);
    }

    /** Snipe: {@code k%} per 10 blocks the arrow travelled (no cap, not rounded to whole steps). */
    public static double snipe(int level, double blocks) {
        return level <= 0 ? 0 : Math.min(4, level) * Math.max(0, blocks) / 10.0;
    }

    public static double firstStrike(int level) {
        return level <= 0 ? 0 : 25.0 * Math.min(5, level);
    }

    public static double tripleStrike(int level) {
        return level <= 0 ? 0 : 10.0 * Math.min(5, level);
    }

    /** Giant Killer: {@code min(k x (enemy - you)/(1% of you), cap)} when the target has more health than you. */
    public static double giantKiller(int level, double enemyHealth, double playerHealth) {
        if (level <= 0 || playerHealth <= 0 || !(enemyHealth > playerHealth)) {
            return 0;
        }
        double cap = table(GIANT_KILLER_CAP, level);
        if (Double.isInfinite(enemyHealth)) {
            return cap;
        }
        return Math.min(cap, table(GIANT_KILLER_K, level) * (enemyHealth - playerHealth) / (0.01 * playerHealth));
    }

    /** Prosecute: {@code k x} the target's current health in %, capped at 40. */
    public static double prosecute(int level, double healthFraction) {
        return level <= 0 ? 0 : Math.min(PROSECUTE_CAP, table(PROSECUTE, level) * clamp01(healthFraction) * 100);
    }

    /** Execute: {@code k x} the target's MISSING health in %. */
    public static double execute(int level, double healthFraction) {
        return level <= 0 ? 0 : table(EXECUTE, level) * (1 - clamp01(healthFraction)) * 100;
    }

    /** Titan Killer: {@code min(k x floor(Defense/100), cap)}. */
    public static double titanKiller(int level, double defense) {
        if (level <= 0 || defense <= 0) {
            return 0;
        }
        return Math.min(table(TITAN_KILLER_CAP, level), table(TITAN_KILLER_K, level) * Math.floor(defense / 100));
    }

    /** Swarm: {@code 2 x level}% per enemy within 10 blocks, at most 10 of them. */
    public static double swarm(int level, int enemies) {
        return level <= 0 ? 0 : 2.0 * Math.min(5, level) * Math.max(0, Math.min(SWARM_MAX, enemies));
    }

    /** Overload's mega-crit chance from Crit Chance: 0 below 100, {@code (CC - 100)/100} up to 200, then certain. */
    public static double megaCritChance(double critChance) {
        return critChance < 100 ? 0 : Math.min(1, (critChance - 100) / 100.0);
    }

    /** Overload's mega crit: {@code +10%} per level. */
    public static double overloadMega(int level) {
        return level <= 0 ? 0 : 10.0 * Math.min(5, level);
    }

    public static double thunderlord(int level) {
        return table(THUNDERLORD, level);
    }

    /** Duplex: the second arrow's share of the first, {@code 4%} per level. */
    public static double duplex(int level) {
        return level <= 0 ? 0 : 4.0 * Math.min(5, level);
    }

    /** Duplex: what fire damage on a hit target is multiplied by for 60 s ({@code 1.1}-{@code 1.5}). */
    public static double duplexFire(int level) {
        return level <= 0 ? 1 : 1 + 0.1 * Math.min(5, level);
    }

    /** Soul Eater: times the last killed mob's damage ({@code 2 x level}). */
    public static double soulEater(int level) {
        return level <= 0 ? 0 : 2.0 * Math.min(5, level);
    }

    /** Fatal Tempo: +{@code 10 x level}% Ferocity per hit. */
    public static double fatalTempo(int level) {
        return level <= 0 ? 0 : 10.0 * Math.min(5, level);
    }

    public static double venomous(int level) {
        return table(VENOMOUS, level);
    }

    public static double lethality(int level) {
        return table(LETHALITY, level);
    }

    /** Life Steal: flat health per hit since 0.26.1 (IV 9.6, V 12: 2.4 per level). */
    public static double lifeSteal(int level) {
        return level <= 0 ? 0 : 2.4 * Math.min(5, level);
    }

    /** Chimera: the share of the pet's stats copied, 20% per level. */
    public static double chimera(int level) {
        return level <= 0 ? 0 : 0.2 * Math.min(5, level);
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 1 : Math.max(0, Math.min(1, v));
    }

    // ------------------------------------------------------------------ reading a build

    /** An enchant's level on a build: Hypixel's id, its {@code ultimate_} form, and the old names (Duplex). */
    public static int level(SimItemBuild b, String name) {
        if (b == null || b.isEmpty()) {
            return 0;
        }
        int v = Math.max(b.enchant(name), b.enchant("ultimate_" + name));
        if ("duplex".equals(name)) {
            v = Math.max(v, b.enchant("ultimate_reiterate"));
        }
        return v;
    }

    /**
     * The build of the weapon a hit was made with: the held item if it is that weapon, else the first hotbar stack
     * of that id (a Terminator arrow landing after he swapped away, a class ability cast with the orb in hand).
     */
    static SimItemBuild buildFor(ServerPlayer sp, String weaponId) {
        if (sp == null) {
            return SimItemBuild.EMPTY;
        }
        SimItemBuild held = SimItemBuild.held(sp);
        if (weaponId == null || weaponId.equalsIgnoreCase(held.id())) {
            return held;
        }
        for (int i = 0; i < 9; i++) {
            SimItemBuild b = SimItemBuild.fromStack(sp.getInventory().getItem(i));
            if (weaponId.equalsIgnoreCase(b.id())) {
                return b;
            }
        }
        return SimItemBuild.EMPTY;
    }

    /**
     * Whether the weapon's damage enchants are known: a built item with enchantments. Then they replace the
     * profile's "Damage Bonus %" stand-in (which only ever stood for them, the pet and set bonuses); a bare item
     * keeps the stand-in so an unbuilt weapon hits as it always did.
     */
    public static boolean known(SimItemBuild b) {
        return b != null && !b.isEmpty() && !b.enchantments().isEmpty();
    }

    // ------------------------------------------------------------------ the target

    /** Hypixel's Undead (Smite's targets since Aug 2025, Skeletal and Wither mobs included). */
    static boolean undead(LivingEntity t) {
        if (t == null) {
            return false;
        }
        if (t.getType().builtInRegistryHolder().is(EntityTypeTags.UNDEAD)) {
            return true;
        }
        SimHealth.Pool pool = SimHealth.get(t);
        if (pool == null || pool.name == null) {
            return false;
        }
        String n = pool.name;
        if (n.contains("Undead") || n.contains("Skeleton") || n.contains("Zombie") || n.equals("Crypt Prince")) {
            return true;
        }
        for (String blood : SimWatcher.MOB_NAMES) {
            if (blood.equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Ender mobs: endermen, endermites and the sim's Fels. The M7 dragons are NOT counted (unconfirmed). */
    static boolean ender(LivingEntity t) {
        if (t instanceof Enderman || t instanceof Endermite) {
            return true;
        }
        SimHealth.Pool pool = SimHealth.get(t);
        return pool != null && "Fel".equals(pool.name);
    }

    /** Airborne (Gravity): Catacombs bats and the five M7 dragons. Withers are not Airborne. */
    static boolean airborne(LivingEntity t) {
        return t instanceof Bat || t instanceof SimNecronDragons.DragonBody;
    }

    /** The target's health now, as {current, max}; {@code max} is infinite for a boss body (always far above you). */
    static double[] health(LivingEntity t) {
        if (t == null) {
            return new double[]{0, 1};
        }
        SimHealth.Pool pool = SimHealth.get(t);
        if (pool != null) {
            return new double[]{pool.hp, pool.max};
        }
        Double boss = SimBoss.bossHealthFraction(t);
        if (boss != null) {
            return new double[]{boss, Double.POSITIVE_INFINITY};
        }
        return new double[]{t.getHealth(), Math.max(1, t.getMaxHealth())};
    }

    static double fraction(double[] hp) {
        if (Double.isInfinite(hp[1])) {
            return hp[0];
        }
        return hp[1] <= 0 ? 0 : hp[0] / hp[1];
    }

    /**
     * The target's Defense. Sim mobs have none of their own; a pool that takes less than all of a hit
     * ({@link SimHealth.Pool#taken}: the training dummy's {@code /simdummy} Defense, the armoured Lost Adventurer)
     * stands for {@code 100/(100 + Defense)}, so Defense is read back from that.
     */
    static double defense(LivingEntity t) {
        SimHealth.Pool pool = SimHealth.get(t);
        if (pool == null || pool.taken >= 1 || pool.taken <= 0) {
            return 0;
        }
        return 100.0 / pool.taken - 100.0;
    }

    /** The target's Defense with Lethality's shred on it. */
    static double shreddedDefense(LivingEntity t) {
        double def = defense(t);
        if (def <= 0) {
            return 0;
        }
        Shred s = SHRED.get(t.getUUID());
        if (s == null || s.until < t.level().getGameTime()) {
            return def;
        }
        return def * Math.max(0, 1 - s.perStack / 100.0 * s.stacks);
    }

    /**
     * What the hit is multiplied by so that the pool's own {@code 100/(100 + Defense)} ({@link SimHealth}, applied
     * after the roll) comes to Hypixel's {@code 100/(100 + shredded Defense)}: 1 without Lethality on the target.
     */
    static double lethalityCorrection(LivingEntity t) {
        double def = defense(t);
        if (def <= 0) {
            return 1;
        }
        return (100 + def) / (100 + shreddedDefense(t));
    }

    /** Enemies within 10 blocks of the player, for Swarm. */
    static int enemiesNear(ServerPlayer sp) {
        if (sp == null || !(sp.level() instanceof ServerLevel level)) {
            return 0;
        }
        AABB box = sp.getBoundingBox().inflate(10);
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e != sp
                && !(e instanceof ArmorStand) && !(e instanceof ServerPlayer)
                && (e instanceof Enemy || SimHealth.isPooled(e) || SimBoss.bossHealthFraction(e) != null))) {
            if (e.distanceToSqr(sp) <= 100 && ++n >= SWARM_MAX) {
                break;
            }
        }
        return n;
    }

    /** Whether an arrow struck the target's head (Precise): its height in the top quarter of the hitbox. */
    static boolean headHit(AbstractArrow arrow, LivingEntity t) {
        return arrow != null && t != null && arrow.getY() >= t.getY() + t.getBbHeight() * 0.75;
    }

    // ------------------------------------------------------------------ state

    private record Key(UUID player, UUID target) {
    }

    /** One player's running effects on one target. */
    private static final class Pair {
        ServerPlayer player;
        LivingEntity target;
        int meleeHits;
        int thunderHits;
        long lastTouched;
        /** Venomous: each stack's saved damage, newest last. */
        final ArrayDeque<Double> venom = new ArrayDeque<>();
        double venomPct;
        long venomUntil;
        /** The burn: damage per second until {@link #burnUntil}. */
        double burnPerSecond;
        long burnUntil;
    }

    /** Lethality and Duplex's fire on one target (debuffs, not per player). */
    private static final class Shred {
        int stacks;
        double perStack;
        long until;
        double fireMultiplier = 1;
        long fireUntil;
    }

    /** One player's Fatal Tempo and Soul Eater. */
    private static final class PlayerState {
        double tempo;
        long tempoUntil;
        /** Soul Eater's bonus waiting for the next crit: the last killed mob's damage. */
        double lastKillDamage;
    }

    private static final Map<Key, Pair> PAIRS = new HashMap<>();
    private static final Map<UUID, Shred> SHRED = new HashMap<>();
    private static final Map<UUID, PlayerState> PLAYERS = new HashMap<>();
    /** Arrows that have already hit something (Piercing's 25% on the next), by arrow id: game time of the hit. */
    private static final Map<UUID, Long> ARROW_HITS = new HashMap<>();

    static {
        // Registered when the class first loads - on the first rolled hit, before that hit can kill. Same pattern as
        // SimAccessories' Reaper Orb.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (SimState.isActive() && source.getEntity() instanceof ServerPlayer killer && entity != killer) {
                double dmg = SimHealth.hitOf(entity);
                if (dmg > 0) {
                    player(killer).lastKillDamage = dmg;
                }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(SimEnchants::tick);
    }

    private SimEnchants() {
    }

    private static PlayerState player(ServerPlayer sp) {
        return PLAYERS.computeIfAbsent(sp.getUUID(), k -> new PlayerState());
    }

    private static Pair pair(ServerPlayer sp, LivingEntity t) {
        Pair p = PAIRS.computeIfAbsent(new Key(sp.getUUID(), t.getUUID()), k -> new Pair());
        p.player = sp;
        p.target = t;
        p.lastTouched = t.level().getGameTime();
        return p;
    }

    private static Pair peek(ServerPlayer sp, LivingEntity t) {
        return sp == null || t == null ? null : PAIRS.get(new Key(sp.getUUID(), t.getUUID()));
    }

    /** Forgets everything (leaving the sim). */
    public static void reset() {
        PAIRS.clear();
        SHRED.clear();
        PLAYERS.clear();
        ARROW_HITS.clear();
    }

    // ------------------------------------------------------------------ the formula's pieces

    /**
     * Chimera: the share of the pet's stats the sword copies, as flat stats on the weapon (the pet's base stats and
     * its pet item's, not its perks). Zero for anything without Chimera.
     */
    static double chimeraStat(ServerPlayer sp, SimItemBuild weapon, SimStats.Stat stat) {
        int lvl = level(weapon, "chimera");
        if (lvl <= 0 || sp == null) {
            return 0;
        }
        Double v = SimGear.pet(sp).petBaseAndItemStats().get(stat);
        return v == null ? 0 : chimera(lvl) * v;
    }

    /**
     * The weapon's enchants in the additive bucket for this hit, in %. First/Triple Strike read the plain-hit
     * counter {@link #after} keeps, so an ability's hits (which never count) still get them on a fresh mob.
     *
     * @param playerHealth the attacker's max health, for Giant Killer
     */
    static double additive(ServerPlayer sp, SimItemBuild w, SimDamage.Type type, LivingEntity t, AbstractArrow arrow,
                           double playerHealth) {
        if (!known(w)) {
            return 0;
        }
        double sum = 0;
        if (type == SimDamage.Type.MAGIC) {
            double[] hp = health(t);
            sum += execute(level(w, "execute"), fraction(hp));
            sum += swarm(level(w, "swarm"), level(w, "swarm") > 0 ? enemiesNear(sp) : 0);
            return sum;
        }
        int ofa = level(w, "one_for_all");
        if (ofa > 0) {
            sum += 500;
        }
        if (type == SimDamage.Type.MELEE) {
            sum += sharpness(level(w, "sharpness"));
            Pair p = peek(sp, t);
            int hits = p == null ? 0 : p.meleeHits;
            if (hits == 0) {
                sum += firstStrike(level(w, "first_strike"));
            }
            if (hits < 3) {
                sum += tripleStrike(level(w, "triple_strike"));
            }
        } else {
            sum += power(level(w, "power"));
            if (sp != null && t != null) {
                sum += snipe(level(w, "snipe"), Math.sqrt(sp.distanceToSqr(t)));
            }
            if ("precise".equals(w.reforge()) && headHit(arrow, t)) {
                sum += PRECISE_HEAD;
            }
        }
        if (t != null) {
            if (undead(t)) {
                sum += smite(level(w, "smite"));
            }
            if (ender(t)) {
                sum += table(MOB_SLAYER, level(w, "ender_slayer"));
            }
            if (airborne(t)) {
                sum += gravity(level(w, "gravity"));
            }
            double[] hp = health(t);
            // A boss body's pool is not read here, only its bar: it is always far above a player's health.
            sum += giantKiller(level(w, "giant_killer"), Double.isInfinite(hp[1]) ? Double.POSITIVE_INFINITY : hp[0],
                    playerHealth);
            sum += prosecute(level(w, "prosecute"), fraction(hp));
            sum += execute(level(w, "execute"), fraction(hp));
            sum += titanKiller(level(w, "titan_killer"), shreddedDefense(t));
        }
        int swarm = level(w, "swarm");
        if (swarm > 0) {
            sum += swarm(swarm, enemiesNear(sp));
        }
        return sum;
    }

    /** Thunderlord and Duplex: the separate multipliers every hit gets (crit or not). */
    static double multiplier(ServerPlayer sp, SimItemBuild w, SimDamage.Type type, LivingEntity t, boolean plain) {
        if (!known(w)) {
            return 1;
        }
        double m = 1;
        if (type == SimDamage.Type.MELEE && plain) {
            int tl = level(w, "thunderlord");
            Pair p = peek(sp, t);
            if (tl > 0 && p != null && (p.thunderHits + 1) % 3 == 0) {
                m *= 1 + thunderlord(tl) / 100.0;
            }
        }
        if (type == SimDamage.Type.ARROW) {
            int dx = level(w, "duplex");
            if (dx > 0) {
                // The second arrow; without Piercing it hits twice (the user's spec - the wiki is silent).
                m *= 1 + duplex(dx) / 100.0 * (level(w, "piercing") > 0 ? 1 : 2);
            }
        }
        return m;
    }

    /** Piercing: an arrow that has already hit something hits the next for 25%. 1 otherwise. */
    static double piercingFactor(SimItemBuild w, AbstractArrow arrow, LivingEntity t) {
        // Nansorb Arrows replace Piercing's 25% cut with their own per-victim bonus (SimArrows).
        if (arrow == null || level(w, "piercing") <= 0 || SimArrows.nansorb(arrow)) {
            return 1;
        }
        long now = arrow.level().getGameTime();
        Long first = ARROW_HITS.putIfAbsent(arrow.getUUID(), now);
        return first == null ? 1 : PIERCING_EXTRA;
    }

    /** Fabled and Overload's mega crit: what a crit is multiplied by on top of Crit Damage. */
    static double critMultiplier(SimItemBuild w, double critChance, ThreadLocalRandom rng) {
        if (w == null || w.isEmpty()) {
            return 1;
        }
        double m = 1;
        if ("fabled".equals(w.reforge())) {
            m *= 1 + rng.nextDouble() * FABLED_MAX;
        }
        int ol = level(w, "overload");
        if (ol > 0 && rng.nextDouble() < megaCritChance(critChance)) {
            m *= 1 + overloadMega(ol) / 100.0;
        }
        return m;
    }

    /** Soul Eater's flat add on a crit: {@code 2 x level x} the last killed mob's damage, 0 without one. */
    static double soulEaterAdd(ServerPlayer sp, SimItemBuild w) {
        int se = level(w, "soul_eater");
        if (se <= 0 || sp == null) {
            return 0;
        }
        PlayerState p = PLAYERS.get(sp.getUUID());
        return p == null ? 0 : soulEater(se) * p.lastKillDamage;
    }

    /** Ferocity with Fatal Tempo's running bonus: {@code ferocity x (1 + tempo/100)}. */
    static double ferocity(ServerPlayer sp, SimItemBuild w, double ferocity, long now) {
        if (sp == null || level(w, "fatal_tempo") <= 0) {
            return ferocity;
        }
        PlayerState p = PLAYERS.get(sp.getUUID());
        if (p == null || p.tempoUntil < now) {
            return ferocity;
        }
        return ferocity * (1 + p.tempo / 100.0);
    }

    /** Loving on the chestplate: abilities x1.05. */
    static double magicMultiplier(ServerPlayer sp) {
        if (sp == null) {
            return 1;
        }
        var armour = SimItemBuild.armour(sp);
        return armour.size() > 1 && "loving".equals(armour.get(1).reforge()) ? LOVING : 1;
    }

    /**
     * After a plain hit has been rolled: counts it, stacks the effects and starts the damage over time.
     *
     * @param dotBase the hit before crit and the separate multipliers (Hypixel's "post-additive" damage), which
     *                Venomous saves and Fire Aspect / Flame burn a share of
     */
    static void after(ServerPlayer sp, SimItemBuild w, SimDamage.Type type, LivingEntity t, boolean crit,
                      double dotBase) {
        if (sp == null || t == null || type == SimDamage.Type.MAGIC) {
            return;
        }
        long now = t.level().getGameTime();
        PlayerState pl = player(sp);
        if (crit && level(w, "soul_eater") > 0) {
            pl.lastKillDamage = 0;
        }
        if (!known(w)) {
            return;
        }
        int ft = level(w, "fatal_tempo");
        if (ft > 0) {
            double base = pl.tempoUntil >= now ? pl.tempo : 0;
            pl.tempo = Math.min(FATAL_TEMPO_CAP, base + fatalTempo(ft));
            pl.tempoUntil = now + FATAL_TEMPO_TICKS;
        }
        Pair p = pair(sp, t);
        if (type == SimDamage.Type.MELEE) {
            p.meleeHits++;
            if (level(w, "thunderlord") > 0) {
                p.thunderHits++;
            }
            int ls = level(w, "life_steal");
            if (ls > 0) {
                SimVitals.heal(sp, lifeSteal(ls));
            }
            int leth = level(w, "lethality");
            if (leth > 0) {
                Shred s = SHRED.computeIfAbsent(t.getUUID(), k -> new Shred());
                s.stacks = s.until >= now ? Math.min(LETHALITY_MAX_STACKS, s.stacks + 1) : 1;
                s.perStack = Math.max(s.perStack, lethality(leth));
                s.until = now + LETHALITY_TICKS;
            }
            int ven = level(w, "venomous");
            if (ven > 0) {
                if (p.venomUntil < now) {
                    p.venom.clear();
                }
                if (p.venom.size() >= VENOMOUS_MAX_STACKS) {
                    p.venom.pollFirst();
                }
                p.venom.addLast(dotBase);
                p.venomPct = venomous(ven);
                p.venomUntil = now + VENOMOUS_TICKS;
                slow(t, true);
            }
            int fa = level(w, "fire_aspect");
            if (fa > 0) {
                double[] row = FIRE_ASPECT[Math.min(fa, FIRE_ASPECT.length) - 1];
                burn(p, t, dotBase * row[1] / 100.0, row[0], now);
            }
        } else {
            int dx = level(w, "duplex");
            if (dx > 0) {
                Shred s = SHRED.computeIfAbsent(t.getUUID(), k -> new Shred());
                s.fireMultiplier = Math.max(s.fireUntil >= now ? s.fireMultiplier : 1, duplexFire(dx));
                s.fireUntil = now + DUPLEX_FIRE_TICKS;
            }
            int fl = level(w, "flame");
            if (fl > 0) {
                double[] row = FLAME[Math.min(fl, FLAME.length) - 1];
                burn(p, t, dotBase * row[1] / 100.0, row[0], now);
            }
        }
    }

    private static void burn(Pair p, LivingEntity t, double perSecond, double seconds, long now) {
        p.burnPerSecond = p.burnUntil >= now ? Math.max(p.burnPerSecond, perSecond) : perSecond;
        p.burnUntil = now + Math.round(seconds * 20);
        // The flames are only a look: a pool or a boss refuses fire with nobody behind it.
        if (SimHealth.isPooled(t) || SimBoss.bossHealthFraction(t) != null) {
            t.setRemainingFireTicks(Math.max(t.getRemainingFireTicks(), (int) Math.round(seconds * 20)));
        }
    }

    private static void slow(LivingEntity t, boolean on) {
        AttributeInstance speed = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        if (on) {
            speed.addOrUpdateTransientModifier(new AttributeModifier(VENOMOUS_SLOW_ID, -VENOMOUS_SLOW,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        } else {
            speed.removeModifier(VENOMOUS_SLOW_ID);
        }
    }

    // ------------------------------------------------------------------ damage over time

    private static void tick(MinecraftServer server) {
        if (PAIRS.isEmpty() && SHRED.isEmpty() && ARROW_HITS.isEmpty()) {
            return;
        }
        if (!SimState.isActive()) {
            for (Pair p : PAIRS.values()) {
                if (p.target != null) {
                    slow(p.target, false);
                }
            }
            reset();
            return;
        }
        long now = server.overworld().getGameTime();
        ARROW_HITS.values().removeIf(at -> now - at > 200);
        SHRED.values().removeIf(s -> s.until < now && s.fireUntil < now);
        for (Iterator<Pair> it = PAIRS.values().iterator(); it.hasNext(); ) {
            Pair p = it.next();
            LivingEntity t = p.target;
            ServerPlayer sp = p.player;
            if (t == null || t.isRemoved() || !t.isAlive() || sp == null || sp.isRemoved()) {
                if (t != null) {
                    slow(t, false);
                }
                it.remove();
                continue;
            }
            long tnow = t.level().getGameTime();
            boolean venomOn = p.venomUntil >= tnow && !p.venom.isEmpty();
            boolean burnOn = p.burnUntil >= tnow && p.burnPerSecond > 0;
            if (!venomOn && !p.venom.isEmpty()) {
                p.venom.clear();
                slow(t, false);
            }
            if (!venomOn && !burnOn && tnow - p.lastTouched > 200) {
                it.remove();
                continue;
            }
            // Once a second, counted from the target's own clock.
            if (tnow % 20 != 0 || !(t.level() instanceof ServerLevel level)) {
                continue;
            }
            double dmg = 0;
            if (venomOn) {
                double saved = 0;
                for (double d : p.venom) {
                    saved += d;
                }
                dmg += saved * p.venomPct / 100.0;
            }
            if (burnOn) {
                Shred s = SHRED.get(t.getUUID());
                double fire = s != null && s.fireUntil >= tnow ? s.fireMultiplier : 1;
                dmg += p.burnPerSecond * fire;
            }
            if (dmg >= 1) {
                SimDamage.dealFixed(level, sp, t, dmg);
            }
        }
    }
}
