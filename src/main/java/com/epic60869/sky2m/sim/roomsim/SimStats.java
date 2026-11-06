package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.epic60869.sky2m.sim.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The player's Hypixel stats inside the Dungeon Sim: the profile he edits on {@code /simstats}, and what it comes
 * to inside a dungeon once the Catacombs and class bonuses are on it.
 *
 * <p>The sim has no gear, so the stats cannot be read off what he is wearing the way Hypixel works them out.
 * Instead he types in what his stats screen says outside a dungeon (or keeps the defaults, which look like a
 * typical M7-ready player), and {@link #effective} turns that into the in-dungeon numbers - plus whatever the held
 * sim weapon adds ({@link SimDamage#weapon}) - which {@link SimDamage} hits with and {@link SimVitals} keeps his
 * health, defense and mana from.
 *
 * <p>Saved to {@code sky2m-sim-stats.json} beside the sim's other settings ({@link SimLoadout}'s file and the
 * rest), read once and written on every change. Client thread writes; the server thread only reads the
 * immutable {@link Profile} snapshot, so there is nothing to lock.
 */
public final class SimStats {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ModPaths.config("sky2m-sim-stats.json");

    /**
     * The stats on the profile, with Hypixel's symbols and colours and a default each.
     *
     * <p>These are the BASE stats: what a typical M7-ready player has outside a dungeon WITHOUT his armour,
     * equipment, weapon and pet (skills, accessories, tuning and the rest) - not dungeon-boosted once gear is worn - about 1,400 health, 300 defense, 380 strength, 95 crit chance,
     * 280 crit damage and 700 intelligence. The armour set and pet picked in the wardrobe ({@link SimGear}) add on
     * top, so with the default Mage gear (Storm's Armor and a Sheep) it comes to roughly the 2,200 health, 670
     * defense and 1,800 intelligence such a player has. Before 2026-11-01 the defaults included the gear (2,500 /
     * 800 / 550 / 100 / 400 / 2,000); a profile saved then keeps its numbers and starts with no armour or pet
     * picked ({@link SimGear}), so nothing is counted twice. Speed is not here: {@code /speed} ({@link SimSpeed})
     * already owns it, and the stats screen edits that setting directly so there is one number, not two.
     *
     * <p>{@link #DAMAGE_BONUS} is not a stat Hypixel shows - it stands for the additive damage multipliers a real
     * weapon carries (Sharpness or Power, Giant Killer, Dragon Hunter, the pet, armour set bonuses), which the sim
     * cannot know one by one. 300% is roughly a good M7 weapon's worth. APPROXIMATE by nature.
     */
    public enum Stat {
        HEALTH("Health", "❤", 'c', 1_400),
        DEFENSE("Defense", "❈", 'a', 300),
        STRENGTH("Strength", "❁", 'c', 380),
        CRIT_CHANCE("Crit Chance", "☣", '9', 95),
        CRIT_DAMAGE("Crit Damage", "☠", '9', 280),
        INTELLIGENCE("Intelligence", "✎", 'b', 700),
        FEROCITY("Ferocity", "⫽", 'c', 30),
        ABILITY_DAMAGE("Ability Damage", "๑", 'c', 30),
        ATTACK_SPEED("Bonus Attack Speed", "⚔", 'e', 25),
        DAMAGE_BONUS("Damage Bonus %", "+", '6', 300);

        public final String label;
        public final String symbol;
        public final char colour;
        public final double defaultValue;

        Stat(String label, String symbol, char colour, double defaultValue) {
            this.label = label;
            this.symbol = symbol;
            this.colour = colour;
            this.defaultValue = defaultValue;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The five dungeon classes, in Hypixel's order. */
    public enum DungeonClass {
        ARCHER("Archer"), BERSERK("Berserk"), HEALER("Healer"), MAGE("Mage"), TANK("Tank");

        public final String label;

        DungeonClass(String label) {
            this.label = label;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Hypixel's Catacombs and class levels stop counting for stats at 50. */
    public static final int MAX_LEVEL = 50;

    private static final int DEFAULT_CATACOMBS = 45;
    private static final int DEFAULT_CLASS_LEVEL = 40;
    /** Mage, because the sim's left-click beam is the Mage's ({@link SimClass}). */
    private static final DungeonClass DEFAULT_CLASS = DungeonClass.MAGE;

    /**
     * One immutable copy of the profile: what the server thread reads.
     *
     * @param manaCosts whether abilities cost mana (on, as on Hypixel; off for pure route practice)
     */
    public record Profile(Map<Stat, Double> stats, int catacombs, Map<DungeonClass, Integer> classLevels,
                          DungeonClass selected, boolean manaCosts) {

        public double get(Stat stat) {
            Double v = stats.get(stat);
            return v == null ? stat.defaultValue : v;
        }

        public int classLevel(DungeonClass c) {
            Integer v = classLevels.get(c);
            return v == null ? DEFAULT_CLASS_LEVEL : v;
        }
    }

    private static volatile Profile profile;
    private static boolean loaded;

    /** {@link #defaults} with each class selected, built once: {@link #profile} hands these out on every hit. */
    private static final Profile[] DEFAULTS_BY_CLASS = new Profile[DungeonClass.values().length];

    static {
        for (DungeonClass c : DungeonClass.values()) {
            Profile d = defaults();
            DEFAULTS_BY_CLASS[c.ordinal()] = new Profile(d.stats(), d.catacombs(), d.classLevels(), c, d.manaCosts());
        }
    }

    private SimStats() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simstats")
                        .then(ClientCommands.literal("show").executes(ctx -> {
                            show();
                            return 1;
                        }))
                        .then(ClientCommands.literal("reset").executes(ctx -> {
                            resetToDefaults();
                            ModChat.send("Sim", ModChat.text("Stats reset to the defaults"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("import").executes(ctx -> {
                            importFromProfile(Minecraft.getInstance(), null);
                            return 1;
                        }))
                        .executes(ctx -> {
                            // Opened on the next tick: a command runs while the chat screen is still closing, and
                            // a screen set now would be closed with it.
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> SimStatsScreen.open(client,
                                    com.epic60869.sky2m.sim.compat.McCompat.screen(client)));
                            return 1;
                        })));
    }

    /**
     * The profile the sim plays with. Any thread.
     *
     * <p>Editing the stats ({@code /simstats} and the Player Stats screen) is staff-only, so a player who is not
     * logged in as staff plays with the default stats and only his own class ({@link #effectiveFor}) - even if a
     * stats file saved earlier holds custom numbers. That file is kept untouched for when he logs in again.
     */
    public static Profile profile() {
        return effectiveFor(stored(), com.epic60869.sky2m.features.sbc.SbcStaff.isStaff());
    }

    /** The saved profile, whatever the staff status: what the editors change. Any thread. */
    static Profile stored() {
        Profile p = profile;
        if (p == null) {
            load();
            p = profile;
        }
        return p;
    }

    /** What {@code saved} plays as: itself for staff, otherwise the defaults with its class. Pure. */
    public static Profile effectiveFor(Profile saved, boolean staff) {
        if (saved == null) {
            return defaults();
        }
        return staff ? saved : defaultsWithClass(saved.selected());
    }

    /** The default profile with {@code c} as the class (Mage when null). */
    public static Profile defaultsWithClass(DungeonClass c) {
        return DEFAULTS_BY_CLASS[(c == null ? DEFAULT_CLASS : c).ordinal()];
    }

    /** A profile with every value at its default. */
    public static Profile defaults() {
        Map<Stat, Double> stats = new EnumMap<>(Stat.class);
        for (Stat s : Stat.values()) {
            stats.put(s, s.defaultValue);
        }
        Map<DungeonClass, Integer> levels = new EnumMap<>(DungeonClass.class);
        for (DungeonClass c : DungeonClass.values()) {
            levels.put(c, DEFAULT_CLASS_LEVEL);
        }
        return new Profile(Map.copyOf(stats), DEFAULT_CATACOMBS, Map.copyOf(levels), DEFAULT_CLASS, true);
    }

    // ------------------------------------------------------------------ editing (client thread)

    public static void set(Stat stat, double value) {
        Profile p = stored();
        Map<Stat, Double> stats = new EnumMap<>(p.stats());
        stats.put(stat, clampStat(stat, value));
        replace(new Profile(Map.copyOf(stats), p.catacombs(), p.classLevels(), p.selected(), p.manaCosts()));
    }

    public static void setCatacombs(int level) {
        Profile p = stored();
        replace(new Profile(p.stats(), clampLevel(level), p.classLevels(), p.selected(), p.manaCosts()));
    }

    public static void setClassLevel(DungeonClass c, int level) {
        Profile p = stored();
        Map<DungeonClass, Integer> levels = new EnumMap<>(p.classLevels());
        levels.put(c, clampLevel(level));
        replace(new Profile(p.stats(), p.catacombs(), Map.copyOf(levels), p.selected(), p.manaCosts()));
    }

    public static void setSelected(DungeonClass c) {
        Profile p = stored();
        replace(new Profile(p.stats(), p.catacombs(), p.classLevels(), c, p.manaCosts()));
    }

    public static void setManaCosts(boolean on) {
        Profile p = stored();
        replace(new Profile(p.stats(), p.catacombs(), p.classLevels(), p.selected(), on));
    }

    public static void resetToDefaults() {
        replace(defaults());
    }

    /** Replaces the whole profile at once - loading a loadout preset ({@link SimLoadout}). */
    public static void setProfile(Profile next) {
        if (next == null) {
            return;
        }
        if (com.epic60869.sky2m.features.sbc.SbcStaff.isStaff()) {
            replace(next);
        } else {
            // Not staff: only the preset's class. The saved stats stay as they are.
            Profile p = stored();
            replace(new Profile(p.stats(), p.catacombs(), p.classLevels(), next.selected(), p.manaCosts()));
        }
    }

    /** Whether a stats file was there when the game started: a profile saved before the wardrobe existed. */
    static boolean savedBeforeThisLaunch() {
        return SAVED_AT_STARTUP;
    }

    private static final boolean SAVED_AT_STARTUP = Files.exists(FILE);

    /**
     * Whether the saved profile's base looks like one from before the wardrobe (2026-11-01), whose defaults INCLUDED
     * the gear (2,500 health, 800 defense): at least 2,000 health or 600 defense, well above today's no-gear 1,400 /
     * 300. Only then does {@link SimGear} start a staff player with no gear, so it is not counted twice. APPROXIMATE
     * by nature - the file carries no date or version - but a base typed that high stands for gear either way.
     */
    static boolean storedBaseIncludesGear() {
        Profile p = stored();
        return p != null && (p.get(Stat.HEALTH) >= 2_000 || p.get(Stat.DEFENSE) >= 600);
    }

    private static void replace(Profile next) {
        profile = next;
        persist();
        // A party guest's class and stats live on the host's server too.
        SimParty.sendProfile();
    }

    private static double clampStat(Stat stat, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return stat.defaultValue;
        }
        double max = stat == Stat.CRIT_CHANCE ? 1_000 : 1_000_000;
        // Health below 1 would make every hit lethal and the action bar divide by zero.
        double min = stat == Stat.HEALTH ? 1 : 0;
        return Math.max(min, Math.min(max, v));
    }

    private static int clampLevel(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    // ------------------------------------------------------------------ dungeon bonuses

    /**
     * Stats as they are inside the dungeon, weapon included. Everything a hit, the health pool and the action
     * bar need.
     *
     * @param mana the mana pool's size: Hypixel's 100 plus Intelligence
     * @param weaponDamage the held weapon's Damage stat, boosted like the rest of its stats
     */
    public record Effective(double weaponDamage, double health, double defense, double strength, double critChance, double critDamage,
                            double intelligence, double ferocity, double abilityDamage, double attackSpeed,
                            double damageBonus, double mana, double healthRegen, double arrowBonus,
                            double meleeBonus, DungeonClass selected, int classLevel) {
        /** Crit Chance for the crit roll: capped at 100, as a chance is. */
        @Override
        public double critChance() {
            return Math.min(100, critChance);
        }

        /** The uncapped Crit Chance total, for Overload's mega-crit ((CC - 100) / 100) and other past-100 rules. */
        public double critChanceRaw() {
            return critChance;
        }
    }

    /** Whether a floor code is Master Mode ("M1".."M7"). */
    public static boolean masterMode(String floor) {
        return floor != null && floor.toUpperCase(Locale.ROOT).startsWith("M");
    }

    /**
     * The OLD approximate dungeon multiplier, now used only for a profile that wears no gear at all (one saved before
     * the wardrobe, whose base stats include its gear - see {@link #effective}). Real gear is boosted by Hypixel's
     * formula per item instead ({@link SimItemData#itemMultiplier}).
     *
     * <p>On Hypixel a dungeon item's stats go up inside the Catacombs with its stars and with your Catacombs level,
     * and further in Master Mode. APPROXIMATE - Hypixel's exact table is not available offline: this assumes fully
     * starred gear (+50%) and then adds 1.5% per Catacombs level on the Entrance and Normal floors and 3% per level
     * in Master Mode, both capped at level 50. At the default Catacombs 45 that is x2.18 on F7 and x2.85 on M7.
     *
     * <p>The stars are Hypixel's five dungeon stars at +10% each (the +50%); master stars are not counted. Neither the
     * Hypixel items resource nor the NEU repo carries per-stat dungeon values (the API's {@code gear_score} and NEU's
     * "Gear Score" line are a single score), so this stays an approximation; {@link SimRealStats} only supplies which
     * items are dungeon items - the armour and weapon get the boost only then ({@link #effective}).
     *
     * <p>The whole profile is boosted, not only its dungeon-gear part: in a dungeon nearly all of a player's
     * health, defense, strength, crit damage and intelligence comes from dungeon armour and weapons. Crit chance,
     * ferocity, ability damage and attack speed are left as typed - they come mostly from reforges, pets and
     * accessories, and crit chance is capped at 100 anyway.
     */
    public static double dungeonMultiplier(String floor, int catacombs) {
        int level = clampLevel(catacombs);
        // x the General's Medallion every sim player wears (SimAccessories): +5% to dungeon items' base stats.
        return (1.0 + 0.5 + level * (masterMode(floor) ? 0.03 : 0.015)) * SimAccessories.dungeonItemMultiplier();
    }

    /** {@link #effective(Profile, String, String)} for the current profile and floor. */
    public static Effective effective(String heldId) {
        return effective(profile(), heldId, SimState.floorLabel());
    }

    /**
     * The in-dungeon stats for a profile, holding {@code heldId} (a sim item's skyblock id, or null), on
     * {@code floor}, wearing this client's gear for the profile's class ({@link SimGear#classGear}). The held
     * weapon's build is read off this client's inventory when it is there (stars, reforge and the rest), else it is
     * the plain item.
     *
     * <p>Class bonuses are the SELECTED class's only, as on Hypixel, and are added after the gear (they are not item
     * stats). APPROXIMATE per level, each class's passives reduced to the stats they give:
     * <ul>
     * <li>Archer: +1% arrow damage per level.</li>
     * <li>Berserk: +1% melee damage and +1 Strength per level.</li>
     * <li>Healer: +3 Health and +1% health regeneration per level.</li>
     * <li>Mage: +2 Intelligence and +0.25 Ability Damage per level (and the beam, {@link SimDamage}).</li>
     * <li>Tank: +3 Defense and +5 Health per level.</li>
     * </ul>
     */
    public static Effective effective(Profile p, String heldId, String floor) {
        SimGear.ClassGear g = SimGear.classGear(p.selected());
        return effective(p, g.armour(), g.equipment(), g.pet(), clientBuild(heldId), floor, null);
    }

    /**
     * The default profile of class {@code c} in that class's default wardrobe slot ({@link SimGear#defaultGear}: its
     * usual set, the dungeon equipment and pet, all maxed), on {@code floor}, holding nothing. What a fresh install
     * plays with; for the stats preview and the damage tests.
     */
    public static Effective defaultGearEffective(DungeonClass c, String floor) {
        SimGear.ClassGear g = SimGear.defaultGear(c);
        return effective(defaultsWithClass(c), g.armour(), g.equipment(), g.pet(), SimItemBuild.EMPTY, floor, null);
    }

    /**
     * {@link #effective(Profile, String, String)} for one player in the sim: the host's own profile, or what a
     * party guest sent ({@link SimParty#member}); the armour really in his armour slots (a mask put on by hand
     * counts), his worn equipment and pet, and the held item's build. Server thread.
     */
    static Effective effective(net.minecraft.server.level.ServerPlayer sp, String heldId, String floor) {
        SimParty.Member m = SimParty.member(sp);
        SimItemBuild held = SimItemBuild.held(sp);
        if (heldId == null) {
            held = SimItemBuild.EMPTY;
        } else if (!heldId.equalsIgnoreCase(held.id()) && !heldId.equalsIgnoreCase(held.dataId())) {
            // A weapon used from the hotbar while holding something else (a bow ability): its build if he has it.
            held = inventoryBuild(sp.getInventory(), heldId);
        }
        return effective(m.profile(), SimItemBuild.armour(sp), m.gear().equipment(), m.gear().pet(), held, floor, sp);
    }

    /** The build of the first {@code id} in an inventory, else the plain item. */
    static SimItemBuild inventoryBuild(net.minecraft.world.entity.player.Inventory inv, String id) {
        if (id == null) {
            return SimItemBuild.EMPTY;
        }
        if (inv != null) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                SimItemBuild b = SimItemBuild.fromStack(inv.getItem(i));
                if (id.equalsIgnoreCase(b.id()) || id.equalsIgnoreCase(b.dataId())) {
                    return b;
                }
            }
        }
        // Not carried: as /simitem would hand it over, maxed.
        return SimItemData.fullBuild(id);
    }

    private static SimItemBuild clientBuild(String id) {
        if (id == null) {
            return SimItemBuild.EMPTY;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            return inventoryBuild(mc == null || mc.player == null ? null : mc.player.getInventory(), id);
        } catch (RuntimeException | LinkageError e) {
            return SimItemBuild.of(id);
        }
    }

    /**
     * The whole sum. Order: base profile (boosted only when no gear is worn - see below), every armour, equipment and
     * weapon build ({@link SimItemBuild#stats}: base, reforge, gems, books, stat enchants, dungeon boost), the pet
     * with its item and flat perks, full-set bonuses, then the "+% all Combat stats" bucket (Ender Dragon's Superior,
     * Superior Blood, Legion - additive with each other since Aug 2022), the pets' % perks, the class, Ragnarok,
     * Rekindle, Web Battlefield, Gladiator's Will, blessings and finally the talismans ({@link SimAccessories}).
     *
     * <p><b>The profile's base is not dungeon-boosted</b> once gear is worn: Hypixel's boost is on dungeon ITEMS, and
     * the base stands for skills, accessories and the like. A profile from before the wardrobe counted its gear in
     * its base, and wears nothing: with no armour or equipment at all, the base keeps the old approximate boost
     * ({@link #dungeonMultiplier}), so such a profile plays as before.
     *
     * @param sp the player (Legion's party count, Gladiator's Will's mobs, Ragnarok and the rest), or null for a
     *           preview on this client
     */
    private static Effective effective(Profile p, java.util.List<SimItemBuild> armour,
                                       java.util.List<SimItemBuild> equipment, SimGear.PetBuild pet,
                                       SimItemBuild weapon, String floor, net.minecraft.server.level.ServerPlayer sp) {
        java.util.UUID who = sp == null ? null : sp.getUUID();
        SimItemBuild.Context ctx = new SimItemBuild.Context(floor, p.catacombs(), p.selected(), pet.id());
        boolean anyGear = false;
        for (SimItemBuild b : armour) {
            anyGear |= !b.isEmpty();
        }
        for (SimItemBuild b : equipment) {
            anyGear |= !b.isEmpty();
        }
        double baseMult = anyGear ? 1.0 : dungeonMultiplier(floor, p.catacombs());
        Map<Stat, Double> total = new EnumMap<>(Stat.class);
        for (Stat s : Stat.values()) {
            if (s == Stat.DAMAGE_BONUS) {
                continue;
            }
            boolean boostable = s == Stat.HEALTH || s == Stat.DEFENSE || s == Stat.STRENGTH || s == Stat.CRIT_DAMAGE
                    || s == Stat.INTELLIGENCE;
            total.put(s, p.get(s) * (boostable ? baseMult : 1.0));
        }
        // Armour (Adaptive pieces grow +2% per 5 Catacombs levels) and equipment.
        for (SimItemBuild b : armour) {
            double scale = SimGear.pieceScale(b.id(), p.catacombs());
            for (Map.Entry<Stat, Double> e : b.stats(ctx).stats().entrySet()) {
                total.merge(e.getKey(), e.getValue() * scale, Double::sum);
            }
        }
        for (SimItemBuild b : equipment) {
            SimItemBuild.addAll(total, b.stats(ctx).stats());
        }
        // The weapon: its build when the data knows it, else SimDamage's table (an unknown sim item).
        double weaponDamage;
        if (weapon.isEmpty() || SimItemData.def(weapon.dataId()) != null) {
            SimItemBuild.Contribution wc = weapon.stats(ctx);
            SimItemBuild.addAll(total, wc.stats());
            weaponDamage = wc.damage();
        } else {
            SimDamage.Weapon w = SimDamage.weapon(weapon.id());
            double wm = SimDamage.dungeonBoosted(weapon.id()) ? dungeonMultiplier(floor, p.catacombs()) : 1.0;
            total.merge(Stat.HEALTH, w.health() * wm, Double::sum);
            total.merge(Stat.DEFENSE, w.defense() * wm, Double::sum);
            total.merge(Stat.STRENGTH, w.strength() * wm, Double::sum);
            total.merge(Stat.CRIT_DAMAGE, w.critDamage() * wm, Double::sum);
            total.merge(Stat.INTELLIGENCE, w.intelligence() * wm, Double::sum);
            total.merge(Stat.CRIT_CHANCE, w.critChance(), Double::sum);
            total.merge(Stat.FEROCITY, w.ferocity(), Double::sum);
            total.merge(Stat.ATTACK_SPEED, w.attackSpeed(), Double::sum);
            weaponDamage = w.damage() * wm;
        }
        // The pet: base stats, its item, Shining Scales.
        SimItemBuild.addAll(total, pet.stats());
        // Full-set bonuses that are flat (Adaptive's Efficient Training).
        SimItemBuild.addAll(total, SimGear.setBonusFlat(armour, p.selected()));
        // "+% all Combat stats": Ender Dragon's Superior, Superior Blood, Legion (not Health) - one additive bucket.
        double combat = pet.combatPercent() + SimGear.setBonusPercent(armour);
        double legion = legionPercent(armour, sp);
        for (Stat s : Stat.values()) {
            if (s == Stat.DAMAGE_BONUS) {
                continue;
            }
            double pct = combat + (s == Stat.HEALTH ? 0 : legion) + pet.statPercent(s);
            if (pct != 0) {
                total.computeIfPresent(s, (k, v) -> v * (1 + pct / 100.0));
            }
        }
        double health = total.getOrDefault(Stat.HEALTH, 0.0);
        double defense = total.getOrDefault(Stat.DEFENSE, 0.0);
        if (pet.bulkHealthPerDefense() > 0) {
            // Blue Whale's Bulk: +1 Defense per 20 max Health.
            defense += Math.floor(health / pet.bulkHealthPerDefense());
        }
        double strength = total.getOrDefault(Stat.STRENGTH, 0.0);
        double critDamage = total.getOrDefault(Stat.CRIT_DAMAGE, 0.0);
        double intelligence = total.getOrDefault(Stat.INTELLIGENCE, 0.0);
        double critChance = total.getOrDefault(Stat.CRIT_CHANCE, 0.0);
        double ferocity = total.getOrDefault(Stat.FEROCITY, 0.0);
        double abilityDamage = total.getOrDefault(Stat.ABILITY_DAMAGE, 0.0);
        double attackSpeed = total.getOrDefault(Stat.ATTACK_SPEED, 0.0);
        double damageBonus = p.get(Stat.DAMAGE_BONUS);
        double regen = 0;
        // Maxor's and the Skeleton Master's arrow damage, in the Archer's additive arrow bucket.
        double arrowBonus = SimGear.arrowBonusPercent(armour);
        double meleeBonus = 0;
        int level = p.classLevel(p.selected());
        switch (p.selected()) {
            case ARCHER -> arrowBonus += level;
            case BERSERK -> {
                meleeBonus += level;
                strength += level;
            }
            case HEALER -> {
                health += 3.0 * level;
                regen += level;
            }
            case MAGE -> {
                intelligence += 2.0 * level;
                abilityDamage += 0.25 * level;
            }
            case TANK -> {
                defense += 3.0 * level;
                health += 5.0 * level;
            }
        }
        // A running Ragnarok's Strength and melee damage (SimClassKit), on top of the class bonuses above.
        strength += SimClassKit.bonusStrength(p.selected(), who, level);
        meleeBonus += SimClassKit.bonusMelee(p.selected(), who);
        // Phoenix Rekindle's Strength while it lasts, and Web Battlefield's stacks from a Mythic Spider's kills.
        strength += SimClassKit.rekindleStrength(who);
        strength += SimGear.webStrength(who);
        // Bone Necklace's Gladiator's Will: Defense per enemy nearby.
        defense += SimGear.gladiatorDefense(sp, equipment, p.selected());
        // Running item effects (SimItemEffects): Weirder Tuba, Ragnarock, Sword of Bad Health, Bonzo's Mask, power
        // scrolls, the Ham Radio and Bluertooth Ring, flares. The ring's Damage is already in weaponDamage.
        SimItemEffects.Bonus fx = SimItemEffects.bonus(who);
        weaponDamage += fx.weaponDamage();
        defense += fx.defense();
        strength += fx.strength();
        critChance += fx.critChance();
        critDamage += fx.critDamage();
        ferocity += fx.ferocity();
        attackSpeed += fx.attackSpeed();
        regen += fx.healthRegen();
        // The team's dungeon blessings (SimBlessings), last: Hypixel's (stat + flat) x (1 + percent).
        Map<Stat, double[]> blessings = SimBlessings.effects();
        health = SimBlessings.apply(Stat.HEALTH, health, blessings);
        defense = SimBlessings.apply(Stat.DEFENSE, defense, blessings);
        strength = SimBlessings.apply(Stat.STRENGTH, strength, blessings);
        critDamage = SimBlessings.apply(Stat.CRIT_DAMAGE, critDamage, blessings);
        intelligence = SimBlessings.apply(Stat.INTELLIGENCE, intelligence, blessings);
        regen += SimBlessings.bonusRegen();
        weaponDamage += weaponDamage > 0 ? SimBlessings.bonusDamage() : 0;
        // The Sheep's Dungeon Wizard: "+25% total mana while in dungeons" (NEU petnums otherNums[2]).
        double mana = 100 + intelligence;
        if ("SHEEP".equals(pet.id())) {
            mana *= 1 + pet.pet().perkNumber(2, 25) / 100.0;
        }
        // Crit Chance is stored uncapped (Effective.critChanceRaw, for Overload); critChance() caps it at 100.
        // The talismans every sim player wears (SimAccessories), last.
        return SimAccessories.apply(new Effective(weaponDamage, Math.max(1, health), defense, strength,
                critChance, critDamage, intelligence, ferocity, abilityDamage, attackSpeed, damageBonus,
                mana, regen, arrowBonus, meleeBonus, p.selected(), level), floor, who);
    }

    /**
     * Legion (Ultimate, armour): +0.07% per level of all Combat stats (not Health) per other player within 30
     * blocks, up to 20 - Legion V's 0.35% a player - summed over the pieces that carry it. Whether the wearer
     * counts is undocumented; he does not here. 0 for a preview (no world).
     */
    static double legionPercent(java.util.List<SimItemBuild> armour, net.minecraft.server.level.ServerPlayer sp) {
        int levels = 0;
        for (SimItemBuild b : armour) {
            levels += b.enchant("ultimate_legion");
        }
        if (levels == 0 || sp == null || sp.level().getServer() == null) {
            return 0;
        }
        int others = 0;
        for (net.minecraft.server.level.ServerPlayer o : sp.level().getServer().getPlayerList().getPlayers()) {
            if (o != sp && o.level() == sp.level() && o.distanceToSqr(sp) <= 30 * 30) {
                others++;
            }
        }
        return 0.07 * levels * Math.min(20, others);
    }

    // ------------------------------------------------------------------ import

    /**
     * Fills in the Catacombs level, the five class levels and the selected class from his real SkyBlock profile,
     * through the profile viewer's API ({@code ProfileApi}, the same call {@code /cata} uses). Needs the internet;
     * works from the sim like anywhere else, since it is an HTTP lookup and never touches a server.
     *
     * <p>Only LEVELS are imported. His real Strength or Crit Damage would mean adding up every piece of gear, every
     * accessory and the pet the way Hypixel's stats menu does, which nothing in the mod computes - so the stats
     * stay as he typed them.
     *
     * @param done run on the client thread once the profile is updated (or the lookup failed), or null
     */
    public static void importFromProfile(Minecraft client, Runnable done) {
        if (client.player == null) {
            return;
        }
        String name = client.player.getGameProfile().name();
        ModChat.send("Sim", ModChat.dim("Loading your Catacombs and class levels..."));
        com.epic60869.sky2m.features.profileviewer.ProfileApi.player(name).whenComplete((player, error) ->
                client.execute(() -> {
                    try {
                        if (error != null || player == null || player.profiles().isEmpty()) {
                            ModChat.send("Sim", ModChat.text("Couldn't load your profile: "),
                                    ModChat.value(com.epic60869.sky2m.features.profileviewer.ProfileApi
                                            .reason(error)));
                            return;
                        }
                        var chosen = player.profiles().getFirst();
                        for (var pr : player.profiles()) {
                            if (pr.selected()) {
                                chosen = pr;
                                break;
                            }
                        }
                        JsonObject member = chosen.member();
                        Profile p = stored();
                        int cata = com.epic60869.sky2m.features.profileviewer.ProfileStats.catacombs(member).level();
                        Map<DungeonClass, Integer> levels = new EnumMap<>(DungeonClass.class);
                        for (DungeonClass c : DungeonClass.values()) {
                            levels.put(c, clampLevel(com.epic60869.sky2m.features.profileviewer.ProfileStats
                                    .dungeonClass(member, c.key()).level()));
                        }
                        DungeonClass selected = p.selected();
                        String sel = selectedClass(member);
                        if (sel != null) {
                            for (DungeonClass c : DungeonClass.values()) {
                                if (c.key().equalsIgnoreCase(sel)) {
                                    selected = c;
                                }
                            }
                        }
                        replace(new Profile(p.stats(), clampLevel(cata), Map.copyOf(levels), selected,
                                p.manaCosts()));
                        ModChat.send("Sim", ModChat.text("Imported Catacombs "), ModChat.value(String.valueOf(
                                clampLevel(cata))), ModChat.text(" and your class levels (selected: "),
                                ModChat.value(selected.label), ModChat.text(")"));
                    } catch (RuntimeException e) {
                        LOGGER.warn("Sim stats import failed", e);
                        ModChat.send("Sim", ModChat.text("Couldn't read your dungeon levels from the profile."));
                    } finally {
                        if (done != null) {
                            done.run();
                        }
                    }
                }));
    }

    private static String selectedClass(JsonObject member) {
        if (member == null || !(member.get("dungeons") instanceof JsonObject dungeons)) {
            return null;
        }
        var v = dungeons.get("selected_dungeon_class");
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    // ------------------------------------------------------------------ chat

    private static void show() {
        Profile p = profile();
        Effective e = effective(p, null, SimState.floorLabel());
        StringBuilder line = new StringBuilder();
        for (Stat s : Stat.values()) {
            line.append(s.label).append(' ').append(SimDamage.commas(p.get(s))).append("  ");
        }
        ModChat.send("Sim", ModChat.text("Profile: "), ModChat.dim(line.toString().trim()));
        ModChat.send("Sim", ModChat.text("Catacombs "), ModChat.value(String.valueOf(p.catacombs())),
                ModChat.text(", " + p.selected().label + " "), ModChat.value(String.valueOf(p.classLevel(p.selected()))),
                ModChat.text(" - in this dungeon: "),
                ModChat.dim(String.format(Locale.ROOT, "%s❤ %s❈ %s❁ %s☠ %s✎", SimDamage.commas(e.health()),
                        SimDamage.commas(e.defense()), SimDamage.commas(e.strength()),
                        SimDamage.commas(e.critDamage()), SimDamage.commas(e.intelligence()))));
        ModChat.send("Sim", ModChat.dim("/simstats (screen) | show | reset | import"));
    }

    // ------------------------------------------------------------------ file

    private static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Profile base = defaults();
        if (!Files.exists(FILE)) {
            profile = base;
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<Stat, Double> stats = new EnumMap<>(base.stats());
            if (o.get("stats") instanceof JsonObject so) {
                for (Stat s : Stat.values()) {
                    if (so.has(s.key())) {
                        stats.put(s, clampStat(s, so.get(s.key()).getAsDouble()));
                    }
                }
            }
            Map<DungeonClass, Integer> levels = new EnumMap<>(base.classLevels());
            if (o.get("classes") instanceof JsonObject co) {
                for (DungeonClass c : DungeonClass.values()) {
                    if (co.has(c.key())) {
                        levels.put(c, clampLevel(co.get(c.key()).getAsInt()));
                    }
                }
            }
            DungeonClass selected = base.selected();
            if (o.has("selected")) {
                try {
                    selected = DungeonClass.valueOf(o.get("selected").getAsString().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // An unknown class in a hand-edited file keeps the default.
                }
            }
            int cata = o.has("catacombs") ? clampLevel(o.get("catacombs").getAsInt()) : base.catacombs();
            boolean mana = !o.has("manaCosts") || o.get("manaCosts").getAsBoolean();
            profile = new Profile(Map.copyOf(stats), cata, Map.copyOf(levels), selected, mana);
        } catch (Exception e) {
            LOGGER.warn("Could not read the sim stats", e);
            profile = base;
        }
    }

    /** A profile as JSON, the stats file's shape - also how a loadout preset stores one ({@link SimLoadout}). */
    static JsonObject toJson(Profile p) {
        JsonObject o = new JsonObject();
        JsonObject so = new JsonObject();
        for (Stat s : Stat.values()) {
            so.addProperty(s.key(), p.get(s));
        }
        o.add("stats", so);
        o.addProperty("catacombs", p.catacombs());
        JsonObject co = new JsonObject();
        for (DungeonClass c : DungeonClass.values()) {
            co.addProperty(c.key(), p.classLevel(c));
        }
        o.add("classes", co);
        o.addProperty("selected", p.selected().key());
        o.addProperty("manaCosts", p.manaCosts());
        return o;
    }

    /** {@link #toJson}'s inverse; anything missing keeps {@code base}'s value. */
    static Profile fromJson(JsonObject o, Profile base) {
        Map<Stat, Double> stats = new EnumMap<>(base.stats());
        if (o.get("stats") instanceof JsonObject so) {
            for (Stat s : Stat.values()) {
                if (so.has(s.key())) {
                    stats.put(s, clampStat(s, so.get(s.key()).getAsDouble()));
                }
            }
        }
        Map<DungeonClass, Integer> levels = new EnumMap<>(base.classLevels());
        if (o.get("classes") instanceof JsonObject co) {
            for (DungeonClass c : DungeonClass.values()) {
                if (co.has(c.key())) {
                    levels.put(c, clampLevel(co.get(c.key()).getAsInt()));
                }
            }
        }
        DungeonClass selected = base.selected();
        if (o.has("selected")) {
            DungeonClass c = classByName(o.get("selected").getAsString());
            if (c != null) {
                selected = c;
            }
        }
        int cata = o.has("catacombs") ? clampLevel(o.get("catacombs").getAsInt()) : base.catacombs();
        boolean mana = !o.has("manaCosts") || o.get("manaCosts").getAsBoolean();
        return new Profile(Map.copyOf(stats), cata, Map.copyOf(levels), selected, mana);
    }

    /** A class by its name or label ("mage", "Berserker" counts as Berserk), or null. */
    public static DungeonClass classByName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("berserker")) {
            n = "berserk";
        }
        for (DungeonClass c : DungeonClass.values()) {
            if (c.key().equals(n)) {
                return c;
            }
        }
        return null;
    }

    private static void persist() {
        Profile p = profile;
        if (p == null) {
            return;
        }
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject o = new JsonObject();
            JsonObject so = new JsonObject();
            for (Stat s : Stat.values()) {
                so.addProperty(s.key(), p.get(s));
            }
            o.add("stats", so);
            o.addProperty("catacombs", p.catacombs());
            JsonObject co = new JsonObject();
            for (DungeonClass c : DungeonClass.values()) {
                co.addProperty(c.key(), p.classLevel(c));
            }
            o.add("classes", co);
            o.addProperty("selected", p.selected().key());
            o.addProperty("manaCosts", p.manaCosts());
            Files.writeString(FILE, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the sim stats", e);
        }
    }
}
