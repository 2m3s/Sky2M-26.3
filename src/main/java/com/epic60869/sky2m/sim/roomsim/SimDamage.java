package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hypixel's damage formula for the Dungeon Sim, in place of the flat 10,000 every sim weapon used to hit for.
 *
 * <p>The weapons still hand vanilla a big number - a puzzle blaze or a secret bat has ordinary vanilla health and
 * must still die to one arrow - but a mob with a health POOL ({@link SimHealth}, and a boss's {@link SimBoss}
 * pool) ignores it: the pool asks {@link #roll} what the hit was really worth and takes that. So the number only
 * changes where Hypixel's numbers mean something.
 *
 * <h2>The formula (hypixelskyblock.minecraft.wiki, Damage)</h2>
 * <ul>
 * <li><b>Melee and arrows:</b> {@code (5 + weapon Damage) x (1 + Strength/100)}, times the additive multipliers
 * {@code (1 + bonus%/100)} (the profile's Damage Bonus plus the Archer's arrow bonus or the Berserker's melee
 * bonus), then a crit roll against Crit Chance multiplies by {@code (1 + Crit Damage/100)}. Ferocity adds extra
 * strikes of the same hit: one per full 100, and the remainder as a chance of one more. The Mage beam is one of
 * these melee hits, times {@link #mageBeamShare}.</li>
 * <li><b>Magic</b> (Wither Impact's Implosion, the Spirit Sceptre's Guided Bat):
 * {@code ability base x (1 + Intelligence/100 x scaling) x (1 + Ability Damage/100)}. Magic never crits.</li>
 * </ul>
 * Every stat in those is the IN-DUNGEON value from {@link SimStats#effective}, the held weapon's stats included.
 * A weapon with a known build ({@link SimItemBuild}) swaps the profile's Damage Bonus stand-in for its real
 * enchantments and reforge ability ({@link SimEnchants}); {@link #roll} has the whole formula in order.
 *
 * <p>How a hit is classified: an ability that wants its own rule wraps its {@code hurtServer} calls in
 * {@link #ability} or {@link #as} (Server thread, and the damage events fire synchronously inside
 * {@code hurtServer}, so a plain static is enough); otherwise a hit whose direct entity is an arrow is an arrow
 * (from the Terminator when it is one of its arrows), and anything else from a player is a melee swing with
 * the held item.
 *
 * <p>Every hit on a pool also puts up a damage number, the way Hypixel's invisible armour stands do: grey for a
 * normal hit, {@code ✧1,234,567✧} in white-to-red for a crit ({@link #showNumber}).
 */
public final class SimDamage {

    /** What kind of hit it was - picks the formula. */
    public enum Type { MELEE, ARROW, MAGIC }

    /**
     * One rolled hit.
     *
     * @param perStrike damage of one strike
     * @param strikes   1 plus the Ferocity extra strikes
     */
    public record Hit(double perStrike, boolean crit, int strikes) {
        public double total() {
            return perStrike * strikes;
        }
    }

    /**
     * A sim weapon's base stats, as its Hypixel tooltip lists them before stars, reforges and enchantments
     * (those are what the profile's Damage Bonus stands for).
     *
     * @param critQuarter the Terminator's "Divides your ☣ Crit Chance by 4!"
     */
    public record Weapon(double damage, double strength, double critChance, double critDamage, double intelligence,
                         double ferocity, double attackSpeed, double health, double defense, boolean critQuarter) {
    }

    private static final Weapon NONE = new Weapon(0, 0, 0, 0, 0, 0, 0, 0, 0, false);

    /**
     * Hypixel's base stats for the sim's weapons, from their tooltips (hypixelskyblock.minecraft.wiki, checked from
     * memory of the 2026 values - small balance changes since are possible, so treat these as approximate).
     * Anything not listed (a Superboom, a key, an empty hand) has no stats and punches for the formula's 5.
     */
    private static final Map<String, Weapon> WEAPONS = Map.of(
            "TERMINATOR", new Weapon(310, 50, 0, 250, 0, 0, 40, 0, 0, true),
            "HYPERION", new Weapon(260, 150, 0, 0, 350, 30, 0, 0, 0, false),
            "VALKYRIE", new Weapon(270, 145, 0, 0, 0, 60, 0, 0, 0, false),
            "SCYLLA", new Weapon(270, 150, 12, 35, 0, 35, 0, 0, 0, false),
            "ASTRAEA", new Weapon(270, 150, 0, 0, 0, 30, 0, 0, 250, false),
            "ASPECT_OF_THE_VOID", new Weapon(120, 100, 0, 0, 0, 0, 0, 0, 0, false),
            "ASPECT_OF_THE_END", new Weapon(100, 100, 0, 0, 0, 0, 0, 0, 0, false),
            "BAT_WAND", new Weapon(180, 0, 0, 0, 300, 0, 0, 0, 0, false));

    /**
     * A weapon's stats by skyblock id; no stats for anything else. The sim's weapons read Hypixel's real numbers
     * ({@link SimRealStats}: Hypixel's items resource, else the NEU item repo) when they have loaded, and the table
     * above otherwise (logged once). The Terminator's crit-chance quarter is the table's either way.
     */
    public static Weapon weapon(String skyblockId) {
        if (skyblockId == null) {
            return NONE;
        }
        String id = skyblockId.toUpperCase(Locale.ROOT);
        Weapon hand = WEAPONS.get(id);
        if (hand == null) {
            // The class setups' weapons (Last Breath, Halberd, Claymore, Ragnarock...) live in SimWeapons.
            Weapon setup = SimWeapons.weapon(id);
            return setup == null ? NONE : setup;
        }
        SimRealStats.ItemStats real = SimRealStats.item(id);
        if (real == null) {
            SimRealStats.fallback("weapon " + id);
            return hand;
        }
        return new Weapon(real.damage(), real.get(SimStats.Stat.STRENGTH), real.get(SimStats.Stat.CRIT_CHANCE),
                real.get(SimStats.Stat.CRIT_DAMAGE), real.get(SimStats.Stat.INTELLIGENCE),
                real.get(SimStats.Stat.FEROCITY), real.get(SimStats.Stat.ATTACK_SPEED), real.get(SimStats.Stat.HEALTH),
                real.get(SimStats.Stat.DEFENSE), hand.critQuarter());
    }

    /**
     * Whether Hypixel's dungeon boost applies to this weapon: a dungeon item, or one that converts into one (the
     * Terminator). The Aspect of the Void and of the End are not. Unknown (offline) counts as yes except for those two.
     */
    public static boolean dungeonBoosted(String skyblockId) {
        if (skyblockId == null) {
            return false;
        }
        String id = skyblockId.toUpperCase(Locale.ROOT);
        Boolean d = SimRealStats.dungeonItem(id);
        if (d != null) {
            return d;
        }
        return !id.equals("ASPECT_OF_THE_VOID") && !id.equals("ASPECT_OF_THE_END");
    }

    // ------------------------------------------------------------------ the abilities' own numbers

    /** Wither Impact's Implosion: 10,000 base, 0.3 Intelligence scaling (Hypixel's tooltip). */
    public static final double IMPLOSION_BASE = 10_000;
    public static final double IMPLOSION_SCALING = 0.3;

    /** The Spirit Sceptre's Guided Bat: 2,000 base, 0.2 scaling (Hypixel's tooltip). */
    public static final double GUIDED_BAT_BASE = 2_000;
    public static final double GUIDED_BAT_SCALING = 0.2;

    /**
     * The Mage beam's share of a melee hit. Mage Staff (hypixel-skyblock.fandom.com, Mage): "All your melee weapon
     * attacks become ranged. Those attacks deal 30% +5 of their melee Damage (increased by 0.09% * your
     * Intelligence)". So the beam is a MELEE hit with the held weapon - Strength, the enchants, the crit roll and
     * Crit Damage all count, which is why the wiki's Left-Click Mage builds Strength and Crit Damage - times
     * {@code 30% + 0.09% x Intelligence}. The Mage level reaches it through the class's Intelligence
     * ({@link SimStats#effective}); there is no separate per-level share.
     *
     * <p>It used to be a MAGIC hit on {@code (5 + Damage) x (30% + 0.6% per Mage level)} scaled by the full
     * {@code 1 + Intelligence/100} and Ability Damage: no Strength, no crit, no enchants, and Intelligence
     * multiplying the whole hit instead of adding 0.09% per point to the share.
     *
     * <p>APPROXIMATE: the wiki's "+5" is read as the melee formula's own {@code 5 + Damage}; its damage drop past 10
     * blocks has no number on the wiki and is not applied.
     */
    public static double mageBeamShare(double intelligence) {
        return 0.30 + 0.0009 * Math.max(0, intelligence);
    }

    // ------------------------------------------------------------------ hit context

    /** Set while an ability's hits are being dealt. Server thread only. */
    private static final class Context {
        final Type type;
        final String weaponId;
        final boolean alwaysCrit;
        final double base;
        final double scaling;
        /** What every hit is multiplied by - a class ability that hits for "150% of your arrow damage". */
        final double multiplier;
        /** A hit of exactly this much (Venomous, Fire Aspect, Flame ticks): no formula, no crit. 0 otherwise. */
        double fixed;
        /** The Mage beam: a melee swing in every way (it procs like one), times {@link #mageBeamShare}. */
        boolean beam;
        int count;
        double total;

        Context(Type type, String weaponId, boolean alwaysCrit, double base, double scaling) {
            this(type, weaponId, alwaysCrit, base, scaling, 1.0);
        }

        Context(Type type, String weaponId, boolean alwaysCrit, double base, double scaling, double multiplier) {
            this.type = type;
            this.weaponId = weaponId;
            this.alwaysCrit = alwaysCrit;
            this.base = base;
            this.scaling = scaling;
            this.multiplier = multiplier;
        }
    }

    private static Context current;

    private SimDamage() {
    }

    /**
     * Deals a magic ability's hits ({@code hits} runs the {@code hurtServer} calls), then says what it did the way
     * Hypixel does: "Your Implosion hit 3 enemies for 1,234,567.8 damage." Server thread.
     */
    public static void ability(ServerPlayer caster, String name, double base, double scaling, Runnable hits) {
        ability(caster, name, null, base, scaling, hits);
    }

    /**
     * {@link #ability} with the Intelligence of a named weapon rather than the held item - a class ability is cast
     * with the Dungeon Orb in hand, which has no stats, so its caster's best weapon stands in ({@link SimClassKit}).
     *
     * @return the damage dealt, all hits together
     */
    public static double ability(ServerPlayer caster, String name, String weaponId, double base, double scaling,
                                 Runnable hits) {
        Context ctx = new Context(Type.MAGIC, weaponId, false, base, scaling);
        run(ctx, hits);
        report(caster, name, ctx);
        return ctx.total;
    }

    /**
     * Deals melee or arrow hits worth {@code multiplier} times a normal hit with {@code weaponId}, and reports them
     * like {@link #ability}: the class abilities that hit for a share of your weapon's damage (Explosive Shot,
     * Throwing Axe, Rapid Fire). Server thread.
     *
     * @return the damage dealt, all hits together
     */
    public static double strike(ServerPlayer caster, String name, Type type, String weaponId, double multiplier,
                                Runnable hits) {
        Context ctx = new Context(type, weaponId, false, 0, 0, multiplier);
        run(ctx, hits);
        report(caster, name, ctx);
        return ctx.total;
    }

    /**
     * Deals the Mage beam's hit ({@code hits} runs the {@code hurtServer} call): a melee swing with the held weapon,
     * rolled and procced like one, times {@link #mageBeamShare}. No chat line - Hypixel prints none for a beam.
     * Server thread.
     */
    public static void mageBeam(Runnable hits) {
        Context ctx = new Context(Type.MELEE, null, false, 0, 0);
        ctx.beam = true;
        run(ctx, hits);
    }

    private static void report(ServerPlayer caster, String name, Context ctx) {
        if (caster != null && ctx.count > 0 && name != null) {
            caster.sendSystemMessage(Component.literal("§7Your §c" + name + " §7hit §c" + ctx.count + " §7"
                    + (ctx.count == 1 ? "enemy" : "enemies") + " for §c"
                    + String.format(Locale.US, "%,.1f", ctx.total) + " §7damage."));
        }
    }

    /**
     * Deals hits of a given type and weapon - Salvation (an arrow hit that always crits, with the Terminator) and
     * the like. Server thread.
     */
    public static void as(Type type, String weaponId, boolean alwaysCrit, Runnable hits) {
        run(new Context(type, weaponId, alwaysCrit, 0, 0), hits);
    }

    /**
     * Deals a damage-over-time tick of exactly {@code damage} to a pooled mob or boss body ({@link SimEnchants}:
     * Venomous, Fire Aspect, Flame) as this player's damage, so it shows a number, counts on a training dummy and
     * gets the kill. It is not rolled and procs nothing. Server thread.
     */
    static void dealFixed(ServerLevel level, ServerPlayer sp, LivingEntity target, double damage) {
        if (target == null || !target.isAlive() || damage <= 0) {
            return;
        }
        Context ctx = new Context(Type.MAGIC, null, false, 0, 0);
        ctx.fixed = damage;
        run(ctx, () -> target.hurtServer(level, level.damageSources().playerAttack(sp), 1f));
    }

    private static void run(Context ctx, Runnable hits) {
        Context before = current;
        current = ctx;
        try {
            hits.run();
        } finally {
            current = before;
        }
    }

    // ------------------------------------------------------------------ rolling

    /**
     * What this player's hit is worth on Hypixel. Called by the pools for every player hit they take.
     *
     * <p>The full formula, in Damage Calculation's order (the enchants' parts are {@link SimEnchants}'):
     * <pre>
     * melee / arrow:  (5 + Damage) x (1 + Strength/100)
     *                 x (1 + (class bonus + the enchants' additive bucket)/100)
     *                     - or the profile's Damage Bonus stand-in for a weapon with no known enchantments
     *                 x Thunderlord x Duplex
     *                 on a crit: (that x Fabled x Overload's mega crit + Soul Eater) x (1 + Crit Damage/100)
     * magic:          base x (1 + Intelligence/100 x scaling) x (1 + Ability Damage/100)
     *                 x (1 + (Execute + Swarm)/100) x Loving
     * then:           x the class passives (Lust for Blood...), x the talismans, x Lethality's Defense shred,
     *                 x Piercing's 25% on an arrow's later targets, rounded down; the pool then takes its
     *                 100/(100 + Defense).
     * </pre>
     * Ferocity repeats the strike (Fatal Tempo raising it), never on magic or on the M7 dragons. No sim boss has a
     * damage cap (the research lists none for the dungeon bosses), so none is applied.
     *
     * @param target the pooled entity, only for the floating number's position (may be null)
     */
    public static Hit roll(Player attacker, DamageSource source, LivingEntity target) {
        Context ctx = current;
        if (ctx != null && ctx.fixed > 0) {
            // A damage-over-time tick (SimEnchants): exactly its number.
            Hit tick = new Hit(Math.floor(ctx.fixed), false, 1);
            ctx.count++;
            ctx.total += tick.total();
            return tick;
        }
        String held = SimItemIds.skyblockId(attacker.getMainHandItem());
        Type type;
        String weaponId;
        AbstractArrow arrow = null;
        if (ctx != null) {
            type = ctx.type;
            weaponId = ctx.weaponId != null ? ctx.weaponId : held;
        } else if (source.getDirectEntity() instanceof AbstractArrow a) {
            type = Type.ARROW;
            arrow = a;
            weaponId = SimTerminator.shotYaw(a) != null ? SimTerminator.ITEM_ID : held;
        } else {
            type = Type.MELEE;
            weaponId = held;
        }
        ServerPlayer sp = attacker instanceof ServerPlayer s ? s : null;
        SimStats.Effective e = sp != null
                ? SimStats.effective(sp, weaponId, SimState.floorLabel())
                : SimStats.effective(SimStats.profile(), weaponId, SimState.floorLabel());
        // A plain swing or arrow - not an ability's hit, which is its own number and procs nothing per hit. The
        // Mage beam IS the swing ("all your melee weapon attacks become ranged"), so it procs like one.
        boolean plain = ctx == null || ctx.beam;
        SimItemBuild build = SimEnchants.buildFor(sp, weaponId);
        // Chimera: the pet's stats again, as the sword's own.
        double strength = e.strength() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.STRENGTH);
        double critDamage = e.critDamage() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.CRIT_DAMAGE);
        double critChanceStat = Math.min(100,
                e.critChance() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.CRIT_CHANCE));
        double intelligence = e.intelligence() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.INTELLIGENCE);
        double ferocityStat = e.ferocity() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.FEROCITY);
        double abilityDamage = e.abilityDamage() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.ABILITY_DAMAGE);
        Hit hit;
        double dotBase = 0;
        boolean crit = false;
        if (type == Type.MAGIC) {
            double base = ctx != null && ctx.base > 0 ? ctx.base : 0;
            double dmg = base * (1 + intelligence / 100.0 * (ctx == null ? 0 : ctx.scaling))
                    * (1 + abilityDamage / 100.0);
            dmg *= 1 + SimEnchants.additive(sp, build, type, target, null, e.health()) / 100.0;
            dmg *= SimEnchants.magicMultiplier(sp);
            hit = new Hit(dmg, false, 1);
        } else {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            double bonus = (type == Type.ARROW ? e.arrowBonus() : e.meleeBonus())
                    + (SimEnchants.known(build) ? SimEnchants.additive(sp, build, type, target, arrow, e.health())
                    : e.damageBonus());
            double dmg = (5 + e.weaponDamage()) * (1 + strength / 100.0) * (1 + bonus / 100.0);
            dotBase = dmg;
            dmg *= SimEnchants.multiplier(sp, build, type, target, plain);
            // The Shadow Assassin Cloak: the next melee hit after a teleport, x1.1 (SimGear's hook, spent here).
            if (type == Type.MELEE && plain && sp != null) {
                dmg *= 1 + SimGear.cloakMeleeBonus(sp, true);
            }
            double critChance = weapon(weaponId).critQuarter() ? critChanceStat / 4.0 : critChanceStat;
            // The Terminator always crits in the sim (its Crit Chance would be quartered on Hypixel): a practice
            // shot that crits sometimes makes every damage number a coin flip.
            crit = (ctx != null && ctx.alwaysCrit) || SimTerminator.ITEM_ID.equals(weaponId)
                    || rng.nextDouble() * 100 < critChance;
            if (crit) {
                // Overload's mega crit reads the Crit Chance past 100 (SimStats' uncapped total).
                double rawCrit = e.critChanceRaw() + SimEnchants.chimeraStat(sp, build, SimStats.Stat.CRIT_CHANCE);
                dmg *= SimEnchants.critMultiplier(build, weapon(weaponId).critQuarter() ? rawCrit / 4.0 : rawCrit,
                        rng);
                if (plain) {
                    dmg += SimEnchants.soulEaterAdd(sp, build);
                }
                dmg *= 1 + critDamage / 100.0;
            }
            // Ferocity, on melee and arrows alike: 100 is a guaranteed extra strike, the rest a chance of another.
            // Fatal Tempo raises it. Not on the M7 dragons (the user's spec: "Ferocity does not work against
            // dragons"). The wiki's Ferocity trivia says it is "disabled for Berserkers in dungeons", but the spec
            // recommends a Fatal Tempo Terminator to Berserkers against the Withers, so it is NOT disabled here.
            double fero = target instanceof SimNecronDragons.DragonBody ? 0
                    : Math.max(0, SimEnchants.ferocity(sp, build, ferocityStat,
                    target != null ? target.level().getGameTime() : 0));
            int strikes = 1 + (int) (fero / 100) + (rng.nextDouble() * 100 < fero % 100 ? 1 : 0);
            hit = new Hit(dmg, crit, strikes);
        }
        if (ctx != null && ctx.multiplier != 1.0) {
            hit = new Hit(hit.perStrike() * ctx.multiplier, hit.crit(), hit.strikes());
        }
        if (ctx != null && ctx.beam) {
            // The beam's share of the melee hit just rolled, crit included (Mage Staff).
            hit = new Hit(hit.perStrike() * mageBeamShare(intelligence), hit.crit(), hit.strikes());
        }
        // The class passives that act on a single hit (Lust for Blood, Doubleshot, Bouncy Arrows). Plain hits
        // only: an ability's hits are already its own number, and a bounce must not bounce again.
        if (plain && sp != null) {
            hit = SimClassKit.onPlayerHit(sp, type, weaponId, target, hit);
        }
        // The talismans every sim player wears (SimAccessories): Book of Progression, Draconic Artifact on dragons.
        hit = SimAccessories.onHit(hit, target);
        // Lethality's Defense shred (the pool applies the unshredded Defense afterwards) and Piercing's 25%.
        double after = SimEnchants.lethalityCorrection(target);
        if (type == Type.ARROW && plain) {
            after *= SimEnchants.piercingFactor(build, arrow, target);
        }
        // Hypixel rounds the final damage down.
        hit = new Hit(Math.floor(hit.perStrike() * after), hit.crit(), hit.strikes());
        if (plain && sp != null) {
            SimEnchants.after(sp, build, type, target, crit, dotBase);
        }
        if (ctx != null) {
            ctx.count++;
            ctx.total += hit.total();
        }
        return hit;
    }

    /** The magic formula for a given base and scaling, for tooltips and the stats screen's preview. */
    public static double magic(SimStats.Effective e, double base, double scaling) {
        return base * (1 + e.intelligence() / 100.0 * scaling) * (1 + e.abilityDamage() / 100.0);
    }

    /** The melee/arrow formula without the crit, for the stats screen's preview. */
    public static double normal(SimStats.Effective e, boolean arrow) {
        double bonus = e.damageBonus() + (arrow ? e.arrowBonus() : e.meleeBonus());
        return (5 + e.weaponDamage()) * (1 + e.strength() / 100.0) * (1 + bonus / 100.0);
    }

    // ------------------------------------------------------------------ damage numbers

    /** The colours a crit number runs through, left to right: white, yellow, gold, red - Hypixel's. */
    private static final char[] CRIT_COLOURS = {'f', 'f', 'e', '6', 'c', 'c'};

    /**
     * Floating damage numbers over a hit entity, one per strike. Server thread.
     *
     * <p>Hypixel shows damage as invisible armour stands with the number as their name, so the sim does the same -
     * which also means any client feature that reads Hypixel's damage stands (damage splash formatting and the
     * like) reads these.
     */
    public static void showNumber(ServerLevel level, LivingEntity target, Hit hit) {
        if (target == null || hit == null) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < hit.strikes(); i++) {
            double x = target.getX() + (rng.nextDouble() - 0.5) * 1.2;
            double y = target.getY() + target.getBbHeight() * 0.5 + rng.nextDouble() * 0.8 + i * 0.3;
            double z = target.getZ() + (rng.nextDouble() - 0.5) * 1.2;
            DamageTag tag = new DamageTag(level, x, y, z);
            tag.setCustomName(Component.literal(label(hit.perStrike(), hit.crit())));
            level.addFreshEntity(tag);
        }
    }

    /** One number's text: "§7123,456" or the crit gradient "✧1,234,567✧". */
    static String label(double damage, boolean crit) {
        String digits = commas(damage);
        if (!crit) {
            return "§7" + digits;
        }
        String text = "✧" + digits + "✧";
        StringBuilder out = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            int c = text.length() == 1 ? 0 : i * (CRIT_COLOURS.length - 1) / (text.length() - 1);
            out.append('§').append(CRIT_COLOURS[c]).append(text.charAt(i));
        }
        return out.toString();
    }

    /** A whole number with thousands separators, the way Hypixel writes damage and stats. */
    public static String commas(double v) {
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    /**
     * A damage number: an invisible marker stand that drifts up and goes after a second.
     *
     * <p>A subclass for the same reason as {@code SimTerminator.TerminatorArrow}: the type is still the vanilla
     * armour stand, so the client needs nothing registered, and the stand cleans itself up and is never written
     * to disk, so a world closed mid-fight does not come back with numbers hanging in the air.
     */
    private static final class DamageTag extends ArmorStand {
        private static final int LIFE_TICKS = 20;
        private int age;

        DamageTag(Level level, double x, double y, double z) {
            super(level, x, y, z);
            setInvisible(true);
            setNoGravity(true);
            setNoBasePlate(true);
            setPermanentlyInvulnerable(true);
            setSilent(true);
            byte flags = getEntityData().get(DATA_CLIENT_FLAGS);
            getEntityData().set(DATA_CLIENT_FLAGS, (byte) (flags | CLIENT_FLAG_MARKER));
            setCustomNameVisible(true);
        }

        @Override
        public void tick() {
            super.tick();
            setPos(getX(), getY() + 0.03, getZ());
            if (++age > LIFE_TICKS) {
                discard();
            }
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }
    }
}
