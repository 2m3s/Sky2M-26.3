package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The player's Hypixel health and mana inside the Dungeon Sim.
 *
 * <p>Vanilla's twenty health cannot carry a Master Mode fight, so the sim keeps its own pools sized from the
 * stats profile ({@link SimStats#effective}: thousands of health, 100 + Intelligence mana), and vanilla's hearts
 * only MIRROR the health pool as a fraction - half the pool is half the hearts - the way Hypixel's hearts do.
 *
 * <p><b>Taking damage.</b> A hit from a mob (melee, a skeleton's arrow, a miniboss's or a boss's magic) is turned
 * into Hypixel damage for the floor ({@link #hypixelDamage}), reduced by Defense ({@code def / (def + 100)}, Hypixel's
 * rule) and taken off the pool; the vanilla hit is refused, with its red flash, sound and knockback put back by
 * hand. When the pool empties he dies the Hypixel way ({@link SimDeath}): a ghost until revived, or sent home in the
 * instant-teleport mode.
 *
 * <p><b>Regeneration</b>, every tick in small steps: health at Hypixel's {@code 1.5 + 1% of max} every two seconds
 * (more for a Healer), mana at 2% of max a second.
 *
 * <p><b>Mana and cooldowns</b> for the sim's abilities: {@link #cast} spends mana and starts a cooldown, or refuses
 * with Hypixel's own lines ("You do not have enough mana to do this!", "This ability is on cooldown for 3s."),
 * which the mod's own Ability Spam filter and Item Cooldowns already read.
 *
 * <p>All state is the integrated server's; everything here runs on the server thread.
 */
public final class SimVitals {

    /**
     * A regular room mob's hit per floor, in Hypixel damage BEFORE defense: what one zombie swing is worth.
     *
     * <p>Hypixel's own numbers for the sim's stand-in zombie, the same mob {@link SimHealth} takes its health from:
     * the Zombie Grunt on the Entrance, F1, F2, M1 and M2 (https://hypixelskyblock.minecraft.wiki/w/Zombie_Grunt) and
     * the Zombie Soldier from F3/M3 (https://hypixelskyblock.minecraft.wiki/w/Zombie_Soldier). A pooled mob's own hit
     * comes from its pool ({@link SimHealth.Pool#hit}); this table is only the fallback for a hit that has neither a
     * pool nor an exact number ({@link #dealHypixel}), scaled from its vanilla damage by {@link #hypixelDamage}, and
     * the stats screen's preview.
     */
    private static final double[] MOB_HIT = {
            201, 244, 336, 738, 1_004, 2_217, 3_456, 8_640,
            12_600, 21_600, 39_600, 56_250, 75_600, 100_800, 111_600};

    /**
     * The exact Hypixel damage of the hit being dealt by {@link #dealHypixel}, or NaN when the hit is an ordinary one.
     * Server thread only, set and cleared around one {@code hurtServer} call.
     */
    private static double pendingHit = Double.NaN;
    /** Whether {@link #pendingHit} is true damage, which Defense does not reduce. */
    private static boolean pendingTrue;

    /** How long the "-150 Mana (Wither Impact)" note replaces Defense on the action bar, in ticks. */
    private static final int CAST_NOTE_TICKS = 40;

    /** One player's pools. Server thread only. */
    static final class Vitals {
        double health = -1;
        double mana = -1;
        double maxHealth = 1;
        double maxMana = 100;
        double defense;
        final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
        String castNote;
        int castNoteTicks;
        /** Extra health on top of the pool, taken first - the Healer's Wish. Gone at {@link #absorptionUntil}. */
        double absorption;
        long absorptionUntil;
        /** No damage at all until this tick - a death save's short immunity. */
        long immuneUntil;
        /**
         * The last hits that landed, by {@link #hitKey}: {@code {server tick, Hypixel damage before Defense}}. The
         * sim's stand-in for vanilla's hurt immunity, see {@link #throughHitWindow}.
         */
        final Map<String, double[]> lastHits = new java.util.HashMap<>();
    }

    /**
     * Vanilla's hurt immunity, for ordinary hits: a mob's swing, arrow or potion within this many ticks of the last
     * one THAT mob landed counts only for what it adds on top (vanilla's own rule: a bigger hit inside the window
     * takes the difference, a smaller one nothing). Ten ticks, vanilla's half second.
     *
     * <p>{@link #onPlayerDamage} refuses every vanilla hit so the pool can take it instead, and a refused hit never
     * starts vanilla's {@code invulnerableTime} - so nothing limited how often a hit could land. Per attacker, not
     * per player as vanilla has it: on Hypixel a room of mobs each lands its own hit, and one shared window would
     * have made five zombies hit like one.
     */
    static final int HIT_WINDOW_TICKS = 10;

    /**
     * The same window for the exact-number attacks ({@link #dealHypixel}): the same attack (same source, damage type
     * and number) landing on the same player again within this many ticks is dropped. Shorter than
     * {@link #HIT_WINDOW_TICKS} on purpose: the bosses' own ticking attacks already pace themselves (Scarf's Soul
     * Sand and Sadan's L.A.S.R. every 10 ticks, the Professor's lasers every 15, Necron's skulls every 8), and what is
     * caught here is the overlap - two balloons, three skulls or a blast and its echo landing in the same instant,
     * which all hit in full before. APPROXIMATE: Hypixel's own rule for its ability damage is not documented.
     */
    static final int EXACT_HIT_WINDOW_TICKS = 5;

    /**
     * The most an unknown hit (no pool, no exact number) is worth, in regular-mob hits of the floor
     * ({@link #MOB_HIT}). APPROXIMATE: a cap, not a Hypixel number - see {@link #hypixelDamage}.
     */
    static final double MAX_SCALED_MOB_HITS = 3;

    private static final Map<UUID, Vitals> VITALS = new ConcurrentHashMap<>();
    private static long serverTicks;

    private SimVitals() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SimVitals::onPlayerDamage);
        ServerTickEvents.END_SERVER_TICK.register(SimVitals::tick);
    }

    /** Forgets every pool and cooldown, for leaving the sim. The next tick starts everyone full. */
    public static void reset() {
        VITALS.clear();
        SimClassKit.reset();
    }

    /** The server tick count the cooldowns run on. */
    static long now() {
        return serverTicks;
    }

    /** This player's pools, sized from his stats on first use so nothing ever sees an unsized pool. */
    static Vitals of(ServerPlayer sp) {
        Vitals v = VITALS.computeIfAbsent(sp.getUUID(), id -> new Vitals());
        if (v.health < 0) {
            // A new session (or a new player in it) starts in his wardrobe's armour, not in empty armour slots; the
            // pools below are sized with it on (SimGear.dressIfBare).
            SimGear.dressIfBare(sp);
            update(sp, v);
            v.health = v.maxHealth;
            v.mana = v.maxMana;
        }
        return v;
    }

    /** Resizes the pools from the stats and the held item (Hypixel's max mana moves with the held item's
     *  Intelligence too). Returns the stats used. */
    private static SimStats.Effective update(ServerPlayer sp, Vitals v) {
        SimStats.Effective e = SimStats.effective(sp, SimItemIds.skyblockId(sp.getMainHandItem()),
                SimState.floorLabel());
        // A full pool stays full when the max grows (armour going on after joining), instead of regenerating up.
        if (v.health >= v.maxHealth && e.health() > v.maxHealth) {
            v.health = e.health();
        }
        v.maxHealth = e.health();
        v.maxMana = e.mana();
        v.defense = e.defense();
        return e;
    }

    /** Sets the health pool to a share of its size, for a revive. Server thread. */
    static void setHealthShare(ServerPlayer sp, double share) {
        Vitals v = of(sp);
        v.health = Math.max(1, v.maxHealth * Math.max(0, Math.min(1, share)));
        mirrorHearts(sp, v);
    }

    /** Fills a player's health and mana, after a rescue or a new floor. */
    static void refill(ServerPlayer sp) {
        Vitals v = of(sp);
        v.health = v.maxHealth;
        v.mana = v.maxMana;
    }

    // ------------------------------------------------------------------ the tick

    private static void tick(MinecraftServer server) {
        serverTicks++;
        if (!SimState.isActive()) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!SimAbilities.simServer(sp)) {
                return;
            }
            Vitals v = of(sp);
            SimStats.Effective e = update(sp, v);
            if (SimDeath.isGhost(sp)) {
                // A ghost has no health to regenerate (SimDeath brings it back on a revive).
                v.health = 0;
                v.absorption = 0;
                mirrorHearts(sp, v);
                continue;
            }
            // Hypixel: 1.5 + 1% of max health every 2 seconds (40 ticks), more for a Healer; mana 2% of max a second.
            double healthPer2s = (1.5 + v.maxHealth * 0.01) * (1 + e.healthRegen() / 100.0);
            v.health = Math.min(v.maxHealth, v.health + healthPer2s / 40.0);
            v.mana = Math.min(v.maxMana, v.mana + v.maxMana * 0.02 / 20.0);
            if (v.castNoteTicks > 0 && --v.castNoteTicks == 0) {
                v.castNote = null;
            }
            if (v.absorption > 0 && serverTicks >= v.absorptionUntil) {
                v.absorption = 0;
            }
            mirrorHearts(sp, v);
        }
    }

    /** Vanilla hearts as the pool's fraction. Never below half a heart: only an empty pool ends a life. */
    private static void mirrorHearts(ServerPlayer sp, Vitals v) {
        if (sp.isCreative() || sp.isSpectator() || !sp.isAlive()) {
            return;
        }
        float want = (float) Math.max(1.0, Math.round(v.health / v.maxHealth * sp.getMaxHealth() * 2) / 2.0);
        if (Math.abs(sp.getHealth() - want) >= 0.5f) {
            sp.setHealth(Math.min(sp.getMaxHealth(), want));
        }
    }

    // ------------------------------------------------------------------ damage taken

    /**
     * A mob's hit in Hypixel damage: its vanilla damage over a regular mob's vanilla melee hit on this floor
     * (after EASY's {@code min(d, d / 2 + 1)}, which vanilla applies before the damage event fires), times
     * {@link #MOB_HIT}. So a starred mob's harder swing, a skeleton's arrow and a miniboss's ice spray all keep
     * the size they have relative to a zombie's swing.
     *
     * <p>Only for a hit with neither a pool's number nor an exact one. Capped at {@link #MAX_SCALED_MOB_HITS} of the
     * floor's mob hit: a vanilla number that has nothing to do with a swing - an explosion's 20 and more, a wither
     * skull, a boss body's attack attribute - scaled the same way came to ten or more zombie hits in one.
     */
    public static double hypixelDamage(float vanillaAmount, String floor) {
        float ref = SimHealth.meleeDamage(floor, SimHealth.Role.REGULAR);
        float easy = Math.min(ref, ref / 2f + 1f);
        double mobHit = MOB_HIT[SimHealth.tier(floor)];
        return Math.min(MAX_SCALED_MOB_HITS * mobHit, vanillaAmount / Math.max(0.5f, easy) * mobHit);
    }

    /**
     * Which hit this is, for the hurt-immunity windows: the attacker (else the projectile, else nothing) and the
     * damage type, plus the number for an exact attack, so a boss's different attacks never block each other.
     */
    private static String hitKey(DamageSource source, Entity attacker, boolean exact, double raw) {
        Entity who = attacker != null ? attacker : source.getDirectEntity();
        String key = (who == null ? "-" : who.getUUID().toString()) + '|' + source.getMsgId();
        return exact ? "x|" + key + '|' + Math.round(raw) : key;
    }

    /**
     * What of {@code raw} (Hypixel damage before Defense) gets through the hurt-immunity window for this key, or 0
     * when it is all blocked. Records the hit. See {@link #HIT_WINDOW_TICKS} and {@link #EXACT_HIT_WINDOW_TICKS}.
     */
    private static double throughHitWindow(Vitals v, String key, boolean exact, double raw) {
        if (v.lastHits.size() > 64) {
            long cutoff = serverTicks - HIT_WINDOW_TICKS;
            v.lastHits.values().removeIf(h -> h[0] < cutoff);
        }
        double[] last = v.lastHits.get(key);
        int window = exact ? EXACT_HIT_WINDOW_TICKS : HIT_WINDOW_TICKS;
        if (last == null || serverTicks - (long) last[0] >= window) {
            v.lastHits.put(key, new double[]{serverTicks, raw});
            return raw;
        }
        if (exact || raw <= last[1]) {
            return 0;
        }
        // Vanilla: a harder hit inside the window lands for the difference, and the window keeps its start.
        double extra = raw - last[1];
        last[1] = raw;
        return extra;
    }

    /**
     * Deals one hit of exactly {@code hypixel} Hypixel damage (before Defense, or past it when {@code trueDamage})
     * to a sim player, through the ordinary vanilla hurt so the red flash, sound, knockback and death all happen as
     * for any other hit. For bosses and minibosses whose attacks have a number on the wiki. Server thread.
     *
     * @return whether the vanilla hurt went through (false for a ghost, an immune player, creative)
     */
    public static boolean dealHypixel(ServerLevel level, ServerPlayer p, DamageSource source, double hypixel,
                                      boolean trueDamage) {
        double before = pendingHit;
        boolean beforeTrue = pendingTrue;
        pendingHit = Math.max(0, hypixel);
        pendingTrue = trueDamage;
        try {
            return p.hurtServer(level, source, 1f);
        } finally {
            pendingHit = before;
            pendingTrue = beforeTrue;
        }
    }

    /** {@link #dealHypixel} as a share of the player's max health - Hypixel's "% of max Health" attacks. */
    public static boolean dealMaxHealthShare(ServerLevel level, ServerPlayer p, DamageSource source, double share,
                                             boolean trueDamage) {
        return dealHypixel(level, p, source, of(p).maxHealth * Math.max(0, share), trueDamage);
    }

    /** Defense's share of a hit that gets through: Hypixel's {@code 1 - def / (def + 100)}. */
    public static double throughDefense(double defense) {
        return 1.0 - Math.max(0, defense) / (Math.max(0, defense) + 100.0);
    }

    /**
     * Mob damage onto the pool. Only mob and magic hits: /kill and
     * the void are left to vanilla, and fall, fire and drowning are refused by {@link SimSurvival} already.
     */
    private static boolean onPlayerDamage(LivingEntity entity, DamageSource source, float amount) {
        if (!(entity instanceof ServerPlayer sp) || !SimState.isActive() || !SimAbilities.simServer(sp)) {
            return true;
        }
        if (sp.isCreative() || sp.isSpectator() || amount <= 0) {
            return true;
        }
        if (SimDeath.isGhost(sp)) {
            return false;
        }
        Entity attacker = source.getEntity();
        if (attacker instanceof Player) {
            // No friendly fire, as on Hypixel: a teammate's swing, arrow or explosion went to vanilla damage, killing
            // him ("killed by Player") and wearing his armour down until the pieces broke. His own explosions
            // (Superboom, Explosive Shot) do not hurt him either.
            return false;
        }
        boolean fromMob = attacker instanceof LivingEntity && !(attacker instanceof Player);
        boolean magic = attacker == null && (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC));
        if (!fromMob && !magic && Double.isNaN(pendingHit)) {
            return true;
        }
        if (source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypeTags.IS_FIRE)) {
            return true;    // SimSurvival's
        }
        Vitals v = of(sp);
        if (serverTicks < v.immuneUntil) {
            return false;
        }
        boolean exact = !Double.isNaN(pendingHit);
        double raw;
        if (exact) {
            // A boss or miniboss attack with Hypixel's own number (dealHypixel).
            raw = pendingHit;
        } else {
            // A pooled mob's swing or arrow carries its own Hypixel hit (SimHealth.Pool#hit); anything else is
            // scaled from its vanilla damage.
            double own = SimHealth.hitOf(attacker);
            raw = own > 0 ? own : hypixelDamage(amount, SimState.floorLabel());
        }
        // A mob that hits Tanks harder (Thorn's wolf, 3.5x: SimHealth.setTankMultiplier).
        if (attacker != null && SimParty.dungeonClass(sp) == SimStats.DungeonClass.TANK) {
            raw *= SimHealth.tankMultiplier(attacker);
        }
        // The hurt-immunity window vanilla would have kept, had the hit not been refused (HIT_WINDOW_TICKS).
        raw = throughHitWindow(v, hitKey(source, attacker, exact, raw), exact, raw);
        if (raw <= 0) {
            return false;
        }
        // Defense: Hypixel's def / (def + 100). True damage ignores it.
        double taken = exact && pendingTrue ? raw : raw * throughDefense(v.defense);
        // The class passives and ultimates that cut a hit (Castle of Stone, Protective Barrier, Bone Plating).
        taken = SimClassKit.incoming(sp, v.health, v.maxHealth, taken);
        // What the worn gear takes off (SimGear): the Wither sets' -10% per piece against withers, the Black Belt's
        // -6%, a Soulweaver skull's -5%, and the Blue Whale's Ingest (+5% damage reduction while absorbing).
        java.util.List<SimItemBuild> worn = SimItemBuild.armour(sp);
        if (fromWither(attacker)) {
            taken *= 1 - SimGear.witherReduction(worn);
        }
        taken = SimGear.beltIncoming(sp, taken);
        if (fromMob) {
            taken = SimGear.soulweaverIncoming(sp, taken);
        }
        if (v.absorption > 0 && "BLUE_WHALE".equals(SimGear.pet(sp).id())) {
            taken *= 0.95;
        }
        // The items' defences (SimItemEffects): Soulward, the Creeper Veil, Wither Shield, Cells Alignment; a hit
        // also breaks a Ragnarock channel.
        taken = SimItemEffects.incoming(sp, taken, !Double.isNaN(pendingHit) && pendingTrue);
        // Tank, Diversion: a living Tank teammate within 30 blocks takes his share of the hit (SimClassKit).
        taken = divert(sp, taken);
        if (v.absorption > 0) {
            double soaked = Math.min(v.absorption, taken);
            v.absorption -= soaked;
            taken -= soaked;
        }
        v.health -= taken;
        if (v.health <= 0) {
            // A pet's or a hook's save (the Phoenix, SimClassKit.registerDeathSave): alive again on the share of
            // the pool the save gives back.
            double saved = SimClassKit.tryDeathSave(sp);
            if (saved > 0) {
                v.health = Math.max(1, v.maxHealth * Math.min(1, saved));
                mirrorHearts(sp, v);
                return false;
            }
        }
        if (v.health <= 0) {
            // A Hypixel death: a ghost until revived, or the instant teleport home (SimDeath's setting).
            SimDeath.die(sp, source);
            return false;
        }
        if (sp.level() instanceof ServerLevel level) {
            // The red flash and hurt sound of a real hit, without the hit.
            level.broadcastDamageEvent(sp, source);
        }
        Entity pusher = source.getDirectEntity() != null ? source.getDirectEntity() : attacker;
        if (pusher != null) {
            com.epic60869.sky2m.sim.compat.McEntities.knockback(sp, 0.4, pusher.getX() - sp.getX(), pusher.getZ() - sp.getZ());
            sp.syncVelocity = true;
        }
        mirrorHearts(sp, v);
        return false;
    }

    /**
     * Whether a hit counts as one from a wither for the Wither sets' "-10% damage from withers" per piece.
     *
     * <p>A vanilla Wither attacker, or - since the F7/M7 fight (SimNecron) deals every attack of Maxor, Storm, Goldor,
     * Necron and the King through {@link #dealHypixel} with an attacker-less magic source - any attacker-less hit
     * while a Floor VII boss room is up. Before, only the first counted, which no attack in the sim is: Necron's
     * armour took nothing off in the one fight it is made for. APPROXIMATE: the dragons of the last phase are
     * counted too, which the sim cannot tell apart from the King's own hits.
     */
    private static boolean fromWither(Entity attacker) {
        if (attacker instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
            return true;
        }
        int tier = SimHealth.tier(SimState.floorLabel());
        return attacker == null && (tier == 7 || tier == 14) && SimBoss.isActive();
    }

    /**
     * Tank, Diversion, now that there are teammates: the nearest living Tank within 30 blocks (not the one hit)
     * takes {@link SimClassKit#diversionShare} of the hit off his own pool, never down past 1. Server thread.
     */
    private static double divert(ServerPlayer sp, double taken) {
        if (taken <= 0 || sp.level().getServer() == null) {
            return taken;
        }
        ServerPlayer tank = null;
        double best = 30 * 30;
        for (ServerPlayer p : sp.level().getServer().getPlayerList().getPlayers()) {
            if (p == sp || p.level() != sp.level() || SimDeath.isGhost(p)
                    || SimParty.dungeonClass(p) != SimStats.DungeonClass.TANK) {
                continue;
            }
            double d = p.distanceToSqr(sp);
            if (d < best) {
                best = d;
                tank = p;
            }
        }
        if (tank == null) {
            return taken;
        }
        double share = taken * SimClassKit.diversionShare(tank);
        Vitals tv = of(tank);
        share = Math.min(share, Math.max(0, tv.health - 1));
        tv.health -= share;
        mirrorHearts(tank, tv);
        return taken - share;
    }

    // ------------------------------------------------------------------ abilities

    /**
     * Spends an ability's mana and starts its cooldown, or refuses with Hypixel's message. Server thread.
     *
     * @param ability       the ability's name, for the action bar note and the cooldown key
     * @param manaCost      mana it costs (ignored when the profile's mana costs are off)
     * @param cooldownTicks its cooldown, 0 for none
     * @return whether it may fire
     */
    public static boolean cast(ServerPlayer sp, String ability, double manaCost, int cooldownTicks) {
        Vitals v = of(sp);
        // The Mage's Efficient Spells and Ultimate Wise style passives and the Sheep pet (SimClassKit).
        manaCost *= SimClassKit.manaCostMultiplier(sp);
        cooldownTicks = (int) Math.round(cooldownTicks * SimClassKit.cooldownMultiplier(sp, ability));
        Long until = v.cooldownUntil.get(ability);
        if (until != null && until > serverTicks) {
            long left = until - serverTicks;
            sp.sendSystemMessage(Component.literal("§cThis ability is on cooldown for "
                    + Math.max(1, (left + 19) / 20) + "s."));
            return false;
        }
        boolean payMana = SimParty.profile(sp).manaCosts() && manaCost > 0;
        if (payMana) {
            if (v.mana < manaCost) {
                sp.sendSystemMessage(Component.literal("§cYou do not have enough mana to do this!"));
                return false;
            }
            v.mana -= manaCost;
            v.castNote = "§b-" + SimDamage.commas(manaCost) + " Mana (§6" + ability + "§b)";
            v.castNoteTicks = CAST_NOTE_TICKS;
        }
        if (cooldownTicks > 0) {
            v.cooldownUntil.put(ability, serverTicks + cooldownTicks);
        }
        return true;
    }

    /** Seconds left on an ability's cooldown, 0 when it is ready. */
    public static int cooldownSeconds(ServerPlayer sp, String ability) {
        Long until = of(sp).cooldownUntil.get(ability);
        return until == null || until <= serverTicks ? 0 : (int) Math.max(1, (until - serverTicks + 19) / 20);
    }

    /** Heals the pool, never past full. Server thread. */
    public static void heal(ServerPlayer sp, double amount) {
        Vitals v = of(sp);
        if (amount > 0 && v.health > 0) {
            v.health = Math.min(v.maxHealth, v.health + amount);
            mirrorHearts(sp, v);
        }
    }

    /** Heals the pool by a fraction of its size. */
    public static void healFraction(ServerPlayer sp, double fraction) {
        heal(sp, of(sp).maxHealth * fraction);
    }

    /** Sets the absorption on top of the pool for {@code ticks} (the larger of it and what is left wins). */
    public static void addAbsorption(ServerPlayer sp, double amount, int ticks) {
        Vitals v = of(sp);
        v.absorption = Math.max(v.absorption, amount);
        v.absorptionUntil = serverTicks + ticks;
    }

    /** No damage for {@code ticks}, for a death save. */
    public static void immune(ServerPlayer sp, int ticks) {
        of(sp).immuneUntil = serverTicks + ticks;
    }

    /** Health as a fraction of the pool, for the passives that care (Protective Barrier). */
    public static double healthFraction(ServerPlayer sp) {
        Vitals v = of(sp);
        return v.maxHealth <= 0 ? 1 : v.health / v.maxHealth;
    }

    /** Gives mana back for a cast that turned out to do nothing (an etherwarp with no target). */
    static void refund(ServerPlayer sp, double mana) {
        if (!SimParty.profile(sp).manaCosts() || mana <= 0) {
            return;
        }
        Vitals v = of(sp);
        v.mana = Math.min(v.maxMana, v.mana + mana);
        v.castNote = null;
        v.castNoteTicks = 0;
    }

    // ------------------------------------------------------------------ action bar

    /**
     * The health, defense and mana parts of Hypixel's action bar for this player, around an optional middle part
     * (the room's "x/y Secrets"):
     *
     * <pre>§c3,251/3,251❤     §a1,055§a❈ Defense     §77/9 Secrets     §b1,201/1,201✎ Mana</pre>
     *
     * The exact shape every action-bar reader in the mod already parses on Hypixel (Status Bars, Low Stats, the
     * Secrets display). For two seconds after a cast the Defense part reads "-150 Mana (Wither Impact)" instead,
     * as Hypixel's does.
     */
    static String actionBar(ServerPlayer sp, String middle) {
        Vitals v = of(sp);
        StringBuilder out = new StringBuilder();
        // Absorption shows the way Hypixel shows it: the health part in gold, absorption included.
        out.append(v.absorption > 0 ? "§6" : "§c").append(SimDamage.commas(Math.max(0, v.health + v.absorption)))
                .append('/').append(SimDamage.commas(v.maxHealth)).append('❤');
        out.append("     ");
        if (v.castNote != null) {
            out.append(v.castNote);
        } else {
            out.append("§a").append(SimDamage.commas(v.defense)).append("§a❈ Defense");
        }
        if (middle != null && !middle.isEmpty()) {
            out.append("     ").append(middle);
        }
        out.append("     §b").append(SimDamage.commas(Math.max(0, v.mana))).append('/')
                .append(SimDamage.commas(v.maxMana)).append("✎ Mana");
        return out.toString();
    }

    /** Current health and mana, for tests and the stats screen. {@code {health, maxHealth, mana, maxMana}}. */
    public static double[] snapshot(UUID player) {
        Vitals v = VITALS.get(player);
        return v == null ? null : new double[]{v.health, v.maxHealth, v.mana, v.maxMana};
    }

    /** The floor's mob hit before defense, for the stats screen's preview. */
    public static double mobHit(String floor) {
        return MOB_HIT[SimHealth.tier(floor == null ? "" : floor.toUpperCase(Locale.ROOT))];
    }
}
