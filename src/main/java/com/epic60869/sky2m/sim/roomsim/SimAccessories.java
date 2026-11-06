package com.epic60869.sky2m.sim.roomsim;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The talismans every sim player wears: a fixed set whose effects are always on, for the host and every party
 * guest alike. They are not items - nothing is in anyone's accessory bag - only their buffs, added where the sim
 * sums its stats ({@link SimStats#effective}) and rolls its hits ({@link SimDamage#roll}).
 *
 * <p>Asked for by the user (2026-11-01): Book of Progression on its Damage ability, Master Skull - Tier 7, General's
 * Medallion, Infinipot, Speed Relic, Bluetooth Ring "only if radio", Draconic Artifact and Reaper Orb. Each one's
 * effect is Hypixel's, from its hypixelskyblock.minecraft.wiki page (fetched 2026-11-01):
 * <ul>
 * <li><b>Book of Progression</b>, Extra Infliction: "+0.01% damage dealt per SkyBlock Level", max +5% at Level 500.
 *     The sim has no SkyBlock Level, so it is the max: every hit x1.05.
 *     https://hypixelskyblock.minecraft.wiki/w/Book_of_Progression</li>
 * <li><b>Master Skull - Tier 7</b>: +10% Health and Strength in Master Mode only.
 *     https://hypixelskyblock.minecraft.wiki/w/Master_Skull_-_Tier_7</li>
 * <li><b>General's Medallion</b>: in the Catacombs, dungeon items' base stats +1% to +5% with the player's Dungeon
 *     Secrets (5% from 10,000). The sim keeps no lifetime secret count, so it is the max: folded into
 *     {@link SimStats#dungeonMultiplier}, which is what dungeon items are boosted by.
 *     https://hypixelskyblock.minecraft.wiki/w/General%27s_Medallion</li>
 * <li><b>Infinipot</b>: no stats; it drinks your selected Dungeon Potion as a floor starts. The sim has no dungeon
 *     potions, so nothing to apply. https://hypixelskyblock.minecraft.wiki/w/Infinipot</li>
 * <li><b>Speed Relic</b>: +7 Speed, +1% Attack Speed (+1 Fishing and Mining Speed). The Attack Speed is applied;
 *     the Speed is not, since sim speed is the absolute number set with {@code /speed} ({@link SimSpeed}) and
 *     adding 7 to it would break the routes timed at it. https://hypixelskyblock.minecraft.wiki/w/Speed_Relic</li>
 * <li><b>Bluetooth Ring</b>: -1 Intelligence, -2 Speed; outside the Rift +1 Damage with at least 6 players on the
 *     island, and +3 Damage to the Blazetekk Ham Radio (the "radio"). Neither can happen in the sim (a dungeon
 *     party is at most 5, and there is no Ham Radio), so it is left off entirely.
 *     https://hypixelskyblock.minecraft.wiki/w/Bluetooth_Ring</li>
 * <li><b>Draconic Artifact</b>: +3 Health, +5% damage dealt to Dragons - in the sim the M7 Wither King's dragons
 *     ({@link SimNecronDragons.DragonBody}). https://hypixelskyblock.minecraft.wiki/w/Draconic_Artifact</li>
 * <li><b>Reaper Orb</b>: +2 Strength per mob killed within the past 5 seconds; the wiki gives no cap, so none is
 *     applied. https://hypixelskyblock.minecraft.wiki/w/Reaper_Orb</li>
 * </ul>
 * Hypixel's Accessory (Magical) Power from these is not modelled: the sim has no Magical Power or power-stone stats.
 */
public final class SimAccessories {

    /** Book of Progression's Extra Infliction at its max (SkyBlock Level 500): +5% damage dealt. */
    public static final double BOOK_DAMAGE = 0.05;
    /** Master Skull - Tier 7: +10% Health and Strength in Master Mode. */
    public static final double MASTER_SKULL = 0.10;
    /** General's Medallion at 10,000+ secrets: dungeon items' base stats +5%. */
    public static final double MEDALLION = 0.05;
    /** Speed Relic's Bonus Attack Speed. */
    public static final double SPEED_RELIC_ATTACK_SPEED = 1;
    /** Draconic Artifact's Health. */
    public static final double DRACONIC_HEALTH = 3;
    /** Draconic Artifact: +5% damage dealt to Dragons. */
    public static final double DRACONIC_DRAGON_DAMAGE = 0.05;
    /** Reaper Orb: Strength per mob killed in the window. */
    public static final double REAPER_STRENGTH_PER_KILL = 2;
    /** Reaper Orb's window, 5 seconds. */
    private static final long REAPER_WINDOW_NANOS = 5_000_000_000L;

    /** Each player's recent kill times (System.nanoTime), for the Reaper Orb. */
    private static final Map<UUID, ArrayDeque<Long>> KILLS = new HashMap<>();

    static {
        // Registered when the class first loads - the first stat sum, before any hit can kill - so it needs no
        // line in anyone's register(). Sim mobs die the vanilla way on their killing blow (SimHealth).
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (SimState.isActive() && source.getEntity() instanceof ServerPlayer killer && entity != killer) {
                recordKill(killer.getUUID(), System.nanoTime());
            }
        });
    }

    private SimAccessories() {
    }

    /** What dungeon items' stats are multiplied by on top of the stars and Catacombs level: the Medallion. */
    public static double dungeonItemMultiplier() {
        return 1 + MEDALLION;
    }

    /**
     * The talismans' stats on top of everything else {@link SimStats#effective} summed: Draconic's Health, the
     * Speed Relic's Attack Speed, the Reaper Orb's Strength, then Master Skull's +10% in Master Mode.
     *
     * @param who the player (for the Reaper Orb's kills), or null for a preview
     */
    public static SimStats.Effective apply(SimStats.Effective e, String floor, UUID who) {
        double health = e.health() + DRACONIC_HEALTH;
        double strength = e.strength() + reaperStrength(who, System.nanoTime());
        if (SimStats.masterMode(floor)) {
            health *= 1 + MASTER_SKULL;
            strength *= 1 + MASTER_SKULL;
        }
        return new SimStats.Effective(e.weaponDamage(), Math.max(1, health), e.defense(), strength, e.critChanceRaw(),
                e.critDamage(), e.intelligence(), e.ferocity(), e.abilityDamage(),
                e.attackSpeed() + SPEED_RELIC_ATTACK_SPEED, e.damageBonus(), e.mana(), e.healthRegen(),
                e.arrowBonus(), e.meleeBonus(), e.selected(), e.classLevel());
    }

    /** What a rolled hit is multiplied by: the Book's +5% always, Draconic's +5% on the M7 dragons. */
    public static double hitMultiplier(LivingEntity target) {
        double m = 1 + BOOK_DAMAGE;
        if (target instanceof SimNecronDragons.DragonBody) {
            m *= 1 + DRACONIC_DRAGON_DAMAGE;
        }
        return m;
    }

    /** {@link SimDamage#roll}'s hook: the hit with {@link #hitMultiplier} on every strike. */
    static SimDamage.Hit onHit(SimDamage.Hit hit, LivingEntity target) {
        return new SimDamage.Hit(hit.perStrike() * hitMultiplier(target), hit.crit(), hit.strikes());
    }

    static synchronized void recordKill(UUID who, long now) {
        ArrayDeque<Long> q = KILLS.computeIfAbsent(who, k -> new ArrayDeque<>());
        q.addLast(now);
        prune(q, now);
    }

    /** The Reaper Orb's Strength right now: +2 per kill in the last 5 seconds. */
    static synchronized double reaperStrength(UUID who, long now) {
        if (who == null) {
            return 0;
        }
        ArrayDeque<Long> q = KILLS.get(who);
        if (q == null) {
            return 0;
        }
        prune(q, now);
        if (q.isEmpty()) {
            KILLS.remove(who);
            return 0;
        }
        return q.size() * REAPER_STRENGTH_PER_KILL;
    }

    private static void prune(ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > REAPER_WINDOW_NANOS) {
            q.removeFirst();
        }
    }

    /** For tests: forgets every Reaper Orb kill. */
    static synchronized void clearKills() {
        KILLS.clear();
    }

    /**
     * One line for the stats screen's preview: "Talismans: Book of Progression +5% dmg, ...". {@code floor} decides
     * whether Master Skull's +10% is shown as active.
     */
    public static String summary(String floor) {
        return "Talismans: Book of Progression +5% dmg, Master Skull T7 +10%❤❁"
                + (SimStats.masterMode(floor) ? "" : " (Master Mode only)")
                + ", General's Medallion +5% dungeon stats, Speed Relic +1⚔, Draconic +3❤ & +5% vs dragons,"
                + " Reaper Orb +2❁ per kill (5s), Infinipot";
    }
}
