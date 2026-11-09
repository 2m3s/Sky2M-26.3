package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The running state behind the sim's Hypixel items (the {@link SimWeapons} abilities, {@link SimArrows}, the masks,
 * the Ham Radio and the flares): timed stat buffs, the Vitality pool, what an item does to a hit a player takes or
 * deals, and the debuffs an item leaves on a mob. Everything is per player, on the integrated server's thread, so a
 * sim party guest's items work exactly like the host's.
 *
 * <h2>Hooks for the rest of the sim</h2>
 * <ul>
 * <li>{@link #bonus(UUID)}: the stats the items add right now (Weirder Tuba, Ragnarock, Sword of Bad Health, Bonzo's
 *     Mask, a Jasper Power Scroll, the Ham Radio and its ring, a deployed flare and its Jalapeno Book). Meant to be
 *     added by {@code SimStats.effective} last, next to the talismans.</li>
 * <li>{@link #radioRingDamage(UUID)}: the Bluertooth Ring's +4 Damage, which only exists with a Ham Radio carried.</li>
 * <li>{@link #incoming}: a hit the player is about to take ({@code SimVitals}).</li>
 * <li>{@link #onPlayerHit}: a hit the player has just rolled on a mob ({@code SimHealth}, {@code SimBoss}).</li>
 * </ul>
 */
public final class SimItemEffects {

    // ------------------------------------------------------------------ ids

    public static final String HAM_RADIO_ID = "BLAZETEKK_HAM_RADIO";
    public static final String ALERT_FLARE_ID = "ALERT_FLARE";
    public static final String SOS_FLARE_ID = "SOS_FLARE";
    public static final String JALAPENO_BOOK_ID = "JALAPENO_BOOK";
    public static final String SPRING_BOOTS_ID = "SPRING_BOOTS";
    public static final String MENDER_CROWN_ID = "MENDER_CROWN";

    /** Hypixel's nine Ham Radio channels, in the order Tuning 4 Dummies cycles them. */
    public static final String[] RADIO_CHANNELS = {"610 AM", "S&R 40.5 FM", "SETI.930Mhz", "1512 AM",
            "Bluetooth 2.48 GHz", "2325 XM", "Infrared", "GPS.1227Mhz", "103.5 FM"};

    /** The Ham Radio's reach: 110 blocks since December 2024. */
    private static final double RADIO_RANGE = 110;

    /**
     * The ring's bonus to the radio: Bluertooth Ring +4 Damage (the user's ring; a Bluetooth Ring would be +3). They do
     * not stack, and signal strength does not change it.
     */
    public static final double RADIO_RING_DAMAGE = 4;

    /** A flare's reach and life: 40 blocks, 3 minutes. */
    private static final double FLARE_RANGE = 40;
    private static final int FLARE_TICKS = 3 * 60 * 20;

    /**
     * The Vitality pool. APPROXIMATE: Hypixel's 2026 Vitality resource has no published size or regeneration in the
     * research, so it is 100 plus the Vitality the radio and flares give, regenerating 2% of the pool a second like
     * mana.
     */
    private static final double BASE_VITALITY = 100;
    private static final double VITALITY_REGEN_PER_SECOND = 0.02;

    // ------------------------------------------------------------------ state

    /** Stats an item adds. Any field may be 0. */
    public record Bonus(double weaponDamage, double defense, double strength, double critChance, double critDamage,
                        double ferocity, double attackSpeed, double healthRegen) {
        public static final Bonus NONE = new Bonus(0, 0, 0, 0, 0, 0, 0, 0);

        Bonus plus(Bonus o) {
            return new Bonus(weaponDamage + o.weaponDamage, defense + o.defense, strength + o.strength,
                    critChance + o.critChance, critDamage + o.critDamage, ferocity + o.ferocity,
                    attackSpeed + o.attackSpeed, healthRegen + o.healthRegen);
        }

        static Bonus strength(double s) {
            return new Bonus(0, 0, s, 0, 0, 0, 0, 0);
        }
    }

    private record Buff(long until, Bonus bonus) {
    }

    /** A deployed Alert or SOS Flare. */
    private record Flare(UUID owner, ServerLevel level, Vec3 pos, long until, boolean sos, boolean jalapeno) {
    }

    static final class State {
        final Map<String, Buff> buffs = new ConcurrentHashMap<>();
        double vitality = -1;
        // Ragnarock's channel: completes at this tick unless a hit interrupts it.
        long ragnarockAt = -1;
        double ragnarockStrength;
        // Soul Esoward: invulnerable, then half damage.
        long esowardUntil;
        long esowardWeakUntil;
        // Wither Impact's Wither Shield.
        long witherShieldUntil;
        double witherShieldGiven;
        // Wither Cloak Sword's Creeper Veil.
        long veilUntil;
        long veilCooldownStart;
        // Gyrokinetic Wand.
        long alignedUntil;
        double alignedPending;
        long alignedDrainUntil;
        long manaSlowUntil;
        // Spirit Leap's immunity, cancelled when he deals damage.
        long leapImmuneUntil;
        // The masks' own recharges.
        long bonzoReadyAt;
        long spiritReadyAt;
        long spiritHealUntil;
        double spiritHealPerTick;
        // Spring Boots' charge, in ticks of sneaking.
        int springCharge;
        // The Ham Radio's signal (0, 0.5 or 1) and whether one is carried, refreshed every second.
        double radioFactor;
        boolean radioCarried;
        // Power scrolls' own cooldown (the Ruby one).
        long rubyScrollReadyAt;
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Flare> FLARES = new ConcurrentHashMap<>();

    /** One mob's debuffs from items. */
    static final class Debuff {
        long twilightUntil;
        long frozenUntil;
        boolean frozeAi;
        int shredStacks;
        long shredUntil;
        // Gravity Storm: held at this point until the tick.
        Vec3 heldAt;
        long heldUntil;
    }

    private static final Map<UUID, Debuff> DEBUFFS = new ConcurrentHashMap<>();
    /** The mobs a debuff is on, so the tick can find them (unfreeze, the Gravity Storm pull). */
    private static final Map<UUID, LivingEntity> DEBUFFED = new ConcurrentHashMap<>();

    private static boolean registered;

    private SimItemEffects() {
    }

    static State state(ServerPlayer sp) {
        return STATES.computeIfAbsent(sp.getUUID(), id -> new State());
    }

    static Debuff debuff(LivingEntity e) {
        DEBUFFED.put(e.getUUID(), e);
        return DEBUFFS.computeIfAbsent(e.getUUID(), id -> new Debuff());
    }

    private static long now() {
        return SimVitals.now();
    }

    /** Called once from {@link SimItems#register}: the tick, the masks' death saves and the arrows. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(SimItemEffects::tick);
        registerMaskSaves();
        SimArrows.register();
        SimWeapons.registerClient();
    }

    /** Everything forgotten, for leaving the sim or a new map. */
    public static void reset() {
        STATES.clear();
        FLARES.clear();
        for (Map.Entry<UUID, LivingEntity> e : DEBUFFED.entrySet()) {
            Debuff d = DEBUFFS.get(e.getKey());
            if (d != null && d.frozeAi && e.getValue() instanceof Mob m) {
                m.setNoAi(false);
            }
        }
        DEBUFFS.clear();
        DEBUFFED.clear();
        SimArrows.reset();
        SimWeapons.reset();
    }

    // ------------------------------------------------------------------ buffs

    /** Starts (or replaces - none of these stack with themselves) a timed buff. */
    static void addBuff(ServerPlayer sp, String key, int ticks, Bonus bonus) {
        state(sp).buffs.put(key, new Buff(now() + ticks, bonus));
    }

    static boolean hasBuff(ServerPlayer sp, String key) {
        Buff b = state(sp).buffs.get(key);
        return b != null && b.until > now();
    }

    /**
     * The stats this player's items add right now. HOOK for {@code SimStats.effective}: add each field to the summed
     * stat (weaponDamage to the held weapon's Damage). Any thread; null is the client's own player (a preview).
     */
    public static Bonus bonus(UUID who) {
        if (who == null) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            who = mc == null || mc.player == null ? null : mc.player.getUUID();
        }
        State s = who == null ? null : STATES.get(who);
        if (s == null) {
            return Bonus.NONE;
        }
        long now = now();
        Bonus out = Bonus.NONE;
        for (Buff b : s.buffs.values()) {
            if (b.until > now) {
                out = out.plus(b.bonus);
            }
        }
        // The Ham Radio: +60 Defense with exactly one other player on the channel, half alone, nothing with more.
        // Its +40 True Defense and +5 Combat Wisdom are not sim stats; its +10 Vitality is in maxVitality.
        out = out.plus(new Bonus(0, 60 * s.radioFactor, 0, 0, 0, 0, 0, 0));
        if (s.radioCarried) {
            out = out.plus(new Bonus(RADIO_RING_DAMAGE, 0, 0, 0, 0, 0, 0, 0));
        }
        Flare f = flareFor(who);
        if (f != null) {
            // Alert: +10 Ferocity. SOS: +10 Ferocity, +5 Attack Speed. Jalapeno Book: +5 Crit Damage, +1 Crit Chance.
            // Their True Defense is not a sim stat; the mana regeneration and Vitality are in the tick and the pool.
            out = out.plus(new Bonus(0, 0, 0, f.jalapeno ? 1 : 0, f.jalapeno ? 5 : 0, 10, f.sos ? 5 : 0, 0));
        }
        return out;
    }

    /**
     * The Bluertooth Ring's radio bonus: +4 Damage while he carries a Blazetekk Ham Radio, 0 otherwise. HOOK for
     * {@code SimAccessories} (which leaves the ring off): already inside {@link #bonus}'s weaponDamage, so a caller
     * that adds {@link #bonus} must not add this again.
     */
    public static double radioRingDamage(UUID who) {
        State s = who == null ? null : STATES.get(who);
        return s != null && s.radioCarried ? RADIO_RING_DAMAGE : 0;
    }

    // ------------------------------------------------------------------ vitality

    static double maxVitality(ServerPlayer sp) {
        State s = state(sp);
        double max = BASE_VITALITY + 10 * s.radioFactor;
        Flare f = flareFor(sp.getUUID());
        if (f != null) {
            max += f.sos ? 25 : 15;
        }
        return max;
    }

    static double vitality(ServerPlayer sp) {
        State s = state(sp);
        if (s.vitality < 0) {
            s.vitality = maxVitality(sp);
        }
        return s.vitality;
    }

    /** Spends Vitality, or says he has too little (Hypixel's mana line, for Vitality). */
    static boolean spendVitality(ServerPlayer sp, double amount) {
        if (!SimParty.profile(sp).manaCosts()) {
            return true;
        }
        if (vitality(sp) < amount) {
            sp.sendSystemMessage(Component.literal("§cYou do not have enough Vitality to do this!"));
            return false;
        }
        state(sp).vitality -= amount;
        return true;
    }

    static void giveVitality(ServerPlayer sp, double amount) {
        State s = state(sp);
        s.vitality = Math.min(maxVitality(sp), vitality(sp) + Math.max(0, amount));
    }

    // ------------------------------------------------------------------ hits he takes

    /**
     * A hit he is about to take, after Defense and the class passives. HOOK for {@code SimVitals.onPlayerDamage}.
     *
     * @param trueDamage whether it is true damage, which Soulward does not block
     * @return what is left of it
     */
    public static double incoming(ServerPlayer sp, double taken, boolean trueDamage) {
        if (taken <= 0) {
            return taken;
        }
        State s = state(sp);
        long now = now();
        if (s.ragnarockAt > now) {
            s.ragnarockAt = -1;
            sp.sendSystemMessage(Component.literal("§cYour Ragnarock was interrupted!"));
        }
        if (now < s.esowardUntil && !trueDamage) {
            return 0;
        }
        if (now < s.witherShieldUntil) {
            taken *= 0.9;
        }
        if (now < s.veilUntil) {
            taken = veil(sp, s, taken);
            if (taken <= 0) {
                return 0;
            }
        }
        if (now < s.alignedUntil) {
            // Cells Alignment: the hit is split and taken over the next 3 seconds; another hit refreshes the delay.
            s.alignedPending += taken;
            s.alignedDrainUntil = now + 60;
            return 0;
        }
        return taken;
    }

    /**
     * Creeper Veil: each layer blocks up to 1,000 damage (times the Dungeon Stat Boost in the Catacombs) and costs 30
     * Vitality; a hit bigger than a layer takes more. When the Vitality runs out the veil goes with a little knockback.
     */
    private static double veil(ServerPlayer sp, State s, double taken) {
        var p = SimParty.member(sp).profile();
        double perLayer = 1_000 * SimStats.dungeonMultiplier(SimState.floorLabel(), p.catacombs());
        while (taken > 0) {
            if (vitality(sp) < 30) {
                s.veilUntil = 0;
                sp.sendSystemMessage(Component.literal("§cYour Creeper Veil broke!"));
                com.epic60869.sky2m.sim.compat.McEntities.knockback(sp, 0.3, sp.getLookAngle().x, sp.getLookAngle().z);
                sp.syncVelocity = true;
                break;
            }
            s.vitality -= 30;
            taken -= perLayer;
        }
        return Math.max(0, taken);
    }

    /** Whether he may deal damage at all: not under Soulward or the Creeper Veil. */
    static boolean cannotAttack(ServerPlayer sp) {
        State s = state(sp);
        long now = now();
        return now < s.esowardUntil || now < s.veilUntil;
    }

    // ------------------------------------------------------------------ hits he deals

    /**
     * A hit he has just rolled on a mob (pooled or a boss). HOOK for {@code SimHealth.onDamage} and
     * {@code SimBoss.onDamage}: multiply the rolled hit by what this returns. Side effects: Spirit Leap's immunity ends,
     * the Halberd heals, the arrows and poisons land ({@link SimArrows}).
     *
     * @param baseTaken the mob's own damage-taken factor from its Defense ({@code 100 / (100 + def)}), 1 when none
     */
    public static SimDamage.Hit onPlayerHit(Player attacker, DamageSource source, LivingEntity target,
                                            SimDamage.Hit hit, double baseTaken) {
        if (!(attacker instanceof ServerPlayer sp) || target == null || hit == null) {
            return hit;
        }
        State s = state(sp);
        long now = now();
        if (now < s.leapImmuneUntil) {
            // Spirit Leap / Infinileap: the 1 second of immunity ends the moment he deals damage.
            SimVitals.Vitals v = SimVitals.of(sp);
            if (v.immuneUntil == s.leapImmuneUntil) {
                v.immuneUntil = now;
            }
            s.leapImmuneUntil = 0;
        }
        if (cannotAttack(sp)) {
            return new SimDamage.Hit(0, false, 1);
        }
        double mult = 1;
        if (now < s.esowardWeakUntil) {
            mult *= 0.5;
        }
        boolean arrow = source.getDirectEntity() instanceof AbstractArrow;
        double shred = 0;
        if (arrow) {
            SimArrows.Effect e = SimArrows.onArrowHit(sp, (AbstractArrow) source.getDirectEntity(), target);
            mult *= e.multiplier();
            shred += e.ignoreDefense();
        } else {
            String held = SimItemIds.skyblockId(sp.getMainHandItem());
            if (SimWeapons.HALBERD_ID.equals(held) && source.getDirectEntity() == sp) {
                // Halberd of the Shredded: heals 25 a hit (50 before 0.26.1) and deals 3.5x to Undead.
                SimVitals.heal(sp, 25);
                if (SimArrows.undead(target)) {
                    mult *= 3.5;
                }
            }
        }
        Debuff d = DEBUFFS.get(target.getUUID());
        if (d != null) {
            if (now < d.twilightUntil) {
                mult *= 1.10;   // Twilight Arrow Poison: +10% damage taken from everything
            }
            if (now < d.frozenUntil) {
                mult *= 1.10;   // Ice Spray: frozen mobs take 10% more from all sources
            }
            if (now < d.shredUntil) {
                shred += 0.10 * d.shredStacks;   // Last Breath: -10% of its Defense a stack
            }
        }
        if (shred > 0 && baseTaken > 0 && baseTaken < 1) {
            // The mob's Defense from its damage-taken factor, then the same rule with less of it.
            double def = 100.0 / baseTaken - 100.0;
            double after = def * Math.max(0, 1 - shred);
            mult *= (100.0 + def) / (100.0 + after);
        }
        return mult == 1 ? hit : new SimDamage.Hit(hit.perStrike() * mult, hit.crit(), hit.strikes());
    }

    /** Puts Last Breath's defense shred on a mob: one more stack (five at most), refreshed. */
    static void shred(LivingEntity target) {
        Debuff d = debuff(target);
        d.shredStacks = now() < d.shredUntil ? Math.min(5, d.shredStacks + 1) : 1;
        // APPROXIMATE: the wiki gives no duration for the stacks; 10 seconds from the last hit.
        d.shredUntil = now() + 200;
    }

    static void twilight(LivingEntity target) {
        debuff(target).twilightUntil = now() + 20 * 20;
    }

    static void freeze(LivingEntity target, int ticks) {
        Debuff d = debuff(target);
        d.frozenUntil = now() + ticks;
        if (target instanceof Mob m && !m.isNoAi()) {
            m.setNoAi(true);
            d.frozeAi = true;
        }
    }

    static void holdAt(LivingEntity target, Vec3 at, int ticks) {
        Debuff d = debuff(target);
        d.heldAt = at;
        d.heldUntil = now() + ticks;
    }

    // ------------------------------------------------------------------ abilities that set state

    /** Spirit Leap / Infinileap's 1 second of immunity, until he deals damage. */
    static void leapImmunity(ServerPlayer sp) {
        long until = now() + 20;
        SimVitals.immune(sp, 20);
        state(sp).leapImmuneUntil = until;
    }

    /**
     * Wither Impact's Wither Shield: -10% damage taken for 5 s and an absorption shield of
     * {@code (12 + Catacombs x 0.32) x 50} (1,400 at Catacombs 50), for 50 Vitality. Unused shield gives the Vitality
     * back when it ends (in proportion to what is left - APPROXIMATE).
     */
    static void witherShield(ServerPlayer sp) {
        if (vitality(sp) < 50 && SimParty.profile(sp).manaCosts()) {
            return;   // the teleport and the implosion still happen; only the shield needs the Vitality
        }
        spendVitality(sp, 50);
        int cata = Math.min(50, SimParty.member(sp).profile().catacombs());
        double shield = Math.min(1_400, (12 + cata * 0.32) * 50);
        SimVitals.addAbsorption(sp, shield, 100);
        State s = state(sp);
        s.witherShieldUntil = now() + 100;
        s.witherShieldGiven = shield;
    }

    static void startRagnarock(ServerPlayer sp, double strength) {
        State s = state(sp);
        s.ragnarockAt = now() + 60;
        s.ragnarockStrength = strength;
    }

    static void soulward(ServerPlayer sp) {
        State s = state(sp);
        s.esowardUntil = now() + 100;
        s.esowardWeakUntil = s.esowardUntil + 40;
    }

    /** @return whether the veil is now up (false: it was up and has been put down) */
    static boolean toggleVeil(ServerPlayer sp) {
        State s = state(sp);
        if (now() < s.veilUntil) {
            s.veilUntil = 0;
            // Putting it down early halves the cooldown.
            SimVitals.Vitals v = SimVitals.of(sp);
            Long until = v.cooldownUntil.get("Creeper Veil");
            if (until != null && until > now()) {
                v.cooldownUntil.put("Creeper Veil", now() + (until - now()) / 2);
            }
            return false;
        }
        s.veilUntil = now() + 200;
        return true;
    }

    static boolean veilUp(ServerPlayer sp) {
        return now() < state(sp).veilUntil;
    }

    static void align(ServerPlayer sp) {
        state(sp).alignedUntil = now() + 120;
    }

    static void slowMana(ServerPlayer sp, int ticks) {
        state(sp).manaSlowUntil = now() + ticks;
    }

    /**
     * A right-click ability with a power scroll on the item: Jasper +10 Strength for 5 s, Ruby +10 Health Regen for 5 s
     * (5 s cooldown; it healed 1% of missing health before 2026-06-10). Power scrolls do not stack.
     */
    static void powerScroll(ServerPlayer sp, ItemStack held) {
        CustomData data = held == null ? null : held.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        String scroll = data == null ? "" : data.copyTag().getStringOr("power_ability_scroll", "");
        if ("JASPER_POWER_SCROLL".equals(scroll)) {
            addBuff(sp, "Power Scroll", 100, Bonus.strength(10));
        } else if ("RUBY_POWER_SCROLL".equals(scroll)) {
            State s = state(sp);
            if (now() >= s.rubyScrollReadyAt) {
                s.rubyScrollReadyAt = now() + 100;
                addBuff(sp, "Power Scroll", 100, new Bonus(0, 0, 0, 0, 0, 0, 0, 10));
            }
        }
    }

    // ------------------------------------------------------------------ flares

    /** Deploys his flare at his feet; only one of his own is ever up. */
    static void deployFlare(ServerPlayer sp, boolean sos, boolean jalapeno) {
        Vec3 at = sp.position().add(0, 1, 0);
        FLARES.put(sp.getUUID(), new Flare(sp.getUUID(), (ServerLevel) sp.level(), at, now() + FLARE_TICKS, sos,
                jalapeno));
        ((ServerLevel) sp.level()).playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_LAUNCH,
                SoundSource.PLAYERS, 1f, 1f);
    }

    /**
     * The one deployable buff this player gets: Hypixel applies only one, so the strongest flare in range (an SOS over
     * an Alert, then the newest), within 40 blocks.
     */
    private static Flare flareFor(UUID who) {
        if (FLARES.isEmpty() || who == null) {
            return null;
        }
        MinecraftServer server = serverOf();
        ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(who);
        if (p == null) {
            return null;
        }
        Flare best = null;
        long now = now();
        for (Flare f : FLARES.values()) {
            if (f.until <= now || f.level != p.level() || f.pos.distanceToSqr(p.position()) > FLARE_RANGE * FLARE_RANGE) {
                continue;
            }
            if (best == null || (f.sos && !best.sos) || (f.sos == best.sos && f.until > best.until)) {
                best = f;
            }
        }
        return best;
    }

    private static MinecraftServer serverOf() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc == null ? null : mc.getSingleplayerServer();
    }

    // ------------------------------------------------------------------ the masks

    /**
     * Bonzo's Mask and the Spirit Mask as death saves, asked before anything else ({@link SimClassKit#registerDeathSave},
     * registered from {@link SimItems#register} before {@code SimDeath}'s own).
     * <ul>
     * <li><b>Bonzo's Mask</b>, Clownin' Around: instead of dying, damage immunity and +20 Strength for 3 s and full
     *     health. Cooldown 360 s minus 3.6 s per Catacombs level (180 s at 50); the same for the starred one.</li>
     * <li><b>Spirit Mask</b>, Second Wind: instead of dying, damage immunity (and +50 Speed, not a sim stat) for 3 s;
     *     the starred mask also heals 10% of your health over 5 s. Cooldown 30 s.</li>
     * </ul>
     */
    private static void registerMaskSaves() {
        SimClassKit.registerDeathSave(sp -> {
            String helmet = SimItemIds.skyblockId(sp.getItemBySlot(EquipmentSlot.HEAD));
            if (helmet == null) {
                return 0;
            }
            State s = state(sp);
            long now = now();
            if (helmet.equals(SimDeath.BONZO_MASK_ID) || helmet.equals("STARRED_BONZO_MASK")) {
                if (now < s.bonzoReadyAt) {
                    return 0;
                }
                int cata = Math.min(50, SimParty.member(sp).profile().catacombs());
                s.bonzoReadyAt = now + Math.round((360 - 3.6 * cata) * 20);
                SimVitals.immune(sp, 60);
                addBuff(sp, "Clownin' Around", 60, Bonus.strength(20));
                saved(sp, "§9Bonzo's Mask", "");
                return 1.0;
            }
            if (helmet.equals(SimDeath.SPIRIT_MASK_ID) || helmet.equals("STARRED_SPIRIT_MASK")) {
                if (now < s.spiritReadyAt) {
                    return 0;
                }
                s.spiritReadyAt = now + 30 * 20;
                SimVitals.immune(sp, 60);
                if (helmet.startsWith("STARRED_")) {
                    s.spiritHealUntil = now + 100;
                    s.spiritHealPerTick = SimVitals.of(sp).maxHealth * 0.10 / 100.0;
                }
                saved(sp, "§5Spirit Mask", "§aSecond Wind Activated! ");
                // One health left: "instead of dying", with the immunity to get out.
                double max = SimVitals.of(sp).maxHealth;
                return max <= 0 ? 0.01 : Math.max(1e-9, 1.0 / max);
            }
            return 0;
        });
    }

    private static void saved(ServerPlayer sp, String mask, String lead) {
        sp.sendSystemMessage(Component.literal(lead + "§aYour " + mask + " §asaved your life!"));
        if (sp.level() instanceof ServerLevel level) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.5f,
                    1.5f);
        }
    }

    /** Seconds until a mask can save him again, for tests and the HUD. {bonzo, spirit}. */
    public static int[] maskCooldowns(ServerPlayer sp) {
        State s = state(sp);
        long now = now();
        return new int[]{(int) Math.max(0, (s.bonzoReadyAt - now + 19) / 20),
                (int) Math.max(0, (s.spiritReadyAt - now + 19) / 20)};
    }

    // ------------------------------------------------------------------ the Ham Radio

    /** The channel a radio stack is on (its own tag, like Hypixel's). */
    static int radioChannel(ItemStack stack) {
        CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data == null ? 0 : Math.floorMod(data.copyTag().getIntOr("channel", 0), RADIO_CHANNELS.length);
    }

    /** Tuning 4 Dummies: the next channel. */
    static void tuneRadio(ServerPlayer sp, ItemStack stack) {
        int next = (radioChannel(stack) + 1) % RADIO_CHANNELS.length;
        CustomData data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        tag.putInt("channel", next);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, CustomData.of(tag));
        sp.sendSystemMessage(Component.literal("§aYou tuned your radio to §e" + RADIO_CHANNELS[next] + "§a."));
    }

    /** His radio's channel, or -1 when he carries none. */
    private static int carriedRadio(ServerPlayer sp) {
        for (ItemStack st : sp.getInventory().getNonEquipmentItems()) {
            if (HAM_RADIO_ID.equals(SimItemIds.skyblockId(st))) {
                return radioChannel(st);
            }
        }
        return -1;
    }

    private static void updateRadios(List<ServerPlayer> players) {
        Map<UUID, Integer> channel = new ConcurrentHashMap<>();
        for (ServerPlayer p : players) {
            int c = carriedRadio(p);
            if (c >= 0) {
                channel.put(p.getUUID(), c);
            }
        }
        for (ServerPlayer p : players) {
            State s = state(p);
            Integer mine = channel.get(p.getUUID());
            s.radioCarried = mine != null;
            if (mine == null) {
                s.radioFactor = 0;
                continue;
            }
            int on = 0;
            for (ServerPlayer o : players) {
                Integer theirs = channel.get(o.getUUID());
                if (theirs != null && theirs.equals(mine) && o.level() == p.level()
                        && o.distanceToSqr(p) <= RADIO_RANGE * RADIO_RANGE) {
                    on++;
                }
            }
            // Exactly two: full stats. Alone: half. Three or more: "Your radio lost signal."
            s.radioFactor = on == 2 ? 1.0 : on == 1 ? 0.5 : 0.0;
        }
    }

    // ------------------------------------------------------------------ the tick

    private static int tickCounter;

    private static void tick(MinecraftServer server) {
        if (!SimState.isActive()) {
            return;
        }
        long now = now();
        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (SimAbilities.simServer(sp)) {
                players.add(sp);
            }
        }
        if (players.isEmpty()) {
            return;
        }
        if (++tickCounter % 20 == 0) {
            updateRadios(players);
        }
        FLARES.values().removeIf(f -> f.until <= now);
        for (ServerPlayer sp : players) {
            State s = state(sp);
            SimVitals.Vitals v = SimVitals.of(sp);
            s.buffs.values().removeIf(b -> b.until <= now);
            // Vitality: none regenerates under the Creeper Veil.
            double maxVit = maxVitality(sp);
            if (now >= s.veilUntil) {
                s.vitality = Math.min(maxVit, vitality(sp) + maxVit * VITALITY_REGEN_PER_SECOND / 20.0);
            }
            s.vitality = Math.min(s.vitality, maxVit);
            // Mana: a flare's +50% / +125% base regeneration; Gravity Storm's 10x slower (90% of it taken back).
            double baseRegen = v.maxMana * 0.02 / 20.0;
            Flare f = flareFor(sp.getUUID());
            if (f != null) {
                v.mana = Math.min(v.maxMana, v.mana + baseRegen * (f.sos ? 1.25 : 0.5));
            }
            if (now < s.manaSlowUntil) {
                v.mana = Math.max(0, v.mana - baseRegen * 0.9);
            }
            // Ragnarock's channel completes.
            if (s.ragnarockAt > 0 && now >= s.ragnarockAt) {
                s.ragnarockAt = -1;
                addBuff(sp, "Ragnarock", 200, Bonus.strength(s.ragnarockStrength));
                sp.sendSystemMessage(Component.literal("§6Ragnarock §agranted you §c+"
                        + SimDamage.commas(s.ragnarockStrength) + " ❁ Strength §afor 10s!"));
                ((ServerLevel) sp.level()).playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.WITHER_SPAWN,
                        SoundSource.PLAYERS, 0.4f, 1.6f);
            }
            // Wither Shield ends: what is left of it comes back as Vitality.
            if (s.witherShieldUntil > 0 && now >= s.witherShieldUntil) {
                if (s.witherShieldGiven > 0) {
                    giveVitality(sp, 50 * Math.max(0, Math.min(1, v.absorption / s.witherShieldGiven)));
                }
                s.witherShieldUntil = 0;
                s.witherShieldGiven = 0;
            }
            // Cells Alignment's delayed damage, spread over what is left of the 3 seconds. APPROXIMATE: it never
            // takes the last health (it bypasses the death check).
            if (s.alignedPending > 0) {
                long left = Math.max(1, s.alignedDrainUntil - now);
                double chunk = s.alignedPending / left;
                s.alignedPending -= chunk;
                v.health = Math.max(1, v.health - chunk);
                if (s.alignedPending < 0.5) {
                    s.alignedPending = 0;
                }
            }
            // The starred Spirit Mask's heal.
            if (now < s.spiritHealUntil) {
                SimVitals.heal(sp, s.spiritHealPerTick);
            }
            if (now < s.veilUntil && now % 10 == 0 && sp.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, sp.getX(), sp.getY() + 1, sp.getZ(), 12, 0.8, 0.8,
                        0.8, 0.02);
            }
            springBoots(sp, s);
        }
        // Flares glow; frozen and held mobs.
        if (tickCounter % 10 == 0) {
            for (Flare fl : FLARES.values()) {
                fl.level.sendParticles(fl.sos ? ParticleTypes.FLAME : ParticleTypes.FIREWORK, fl.pos.x, fl.pos.y,
                        fl.pos.z, 6, 0.3, 0.6, 0.3, 0.01);
            }
        }
        for (Map.Entry<UUID, LivingEntity> e : new ArrayList<>(DEBUFFED.entrySet())) {
            LivingEntity mob = e.getValue();
            Debuff d = DEBUFFS.get(e.getKey());
            if (d == null || !mob.isAlive()) {
                DEBUFFED.remove(e.getKey());
                DEBUFFS.remove(e.getKey());
                continue;
            }
            if (d.frozeAi && now >= d.frozenUntil && mob instanceof Mob m) {
                m.setNoAi(false);
                d.frozeAi = false;
            }
            if (d.heldAt != null) {
                if (now >= d.heldUntil) {
                    d.heldAt = null;
                } else {
                    // Gravity Storm: pulled into the rift's centre and held there.
                    Vec3 to = mob.position().lerp(d.heldAt, 0.35);
                    mob.teleportTo(to.x, to.y, to.z);
                    mob.setDeltaMovement(Vec3.ZERO);
                }
            }
            if (d.heldAt == null && !d.frozeAi && now >= d.twilightUntil && now >= d.shredUntil
                    && now >= d.frozenUntil) {
                DEBUFFED.remove(e.getKey());
                DEBUFFS.remove(e.getKey());
            }
        }
        SimWeapons.tick(server);
    }

    /**
     * Spring Boots, To the moon!: sneaking on the ground charges a jump; letting go launches him, higher the longer he
     * sneaked. APPROXIMATE: the wiki gives no height or charge time, so 2 seconds of sneaking is the full charge and
     * a full jump rises about 25 blocks.
     */
    private static void springBoots(ServerPlayer sp, State s) {
        boolean wearing = SPRING_BOOTS_ID.equals(SimItemIds.skyblockId(sp.getItemBySlot(EquipmentSlot.FEET)));
        if (!wearing) {
            s.springCharge = 0;
            return;
        }
        sp.fallDistance = 0;   // Feather Falling XX
        if (sp.isShiftKeyDown() && sp.onGround()) {
            s.springCharge = Math.min(40, s.springCharge + 1);
            if (s.springCharge % 5 == 0 && sp.level() instanceof ServerLevel level) {
                level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.NOTE_BLOCK_PLING.value(),
                        SoundSource.PLAYERS, 0.6f, 0.5f + s.springCharge / 40f);
            }
            return;
        }
        if (s.springCharge >= 5 && !sp.isShiftKeyDown()) {
            double vy = 0.42 + 2.0 * s.springCharge / 40.0;
            sp.setDeltaMovement(sp.getDeltaMovement().x, vy, sp.getDeltaMovement().z);
            sp.syncVelocity = true;
        }
        s.springCharge = 0;
    }
}
