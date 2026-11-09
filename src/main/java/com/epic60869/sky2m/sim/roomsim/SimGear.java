package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.compat.McCompat;
import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.epic60869.sky2m.sim.util.ModPaths;
import com.epic60869.sky2m.sim.util.SimItemIds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Dungeon Sim's wardrobe, equipment and pets: per class, up to nine wardrobe SLOTS like Hypixel's wardrobe page
 * (each a full per-piece armour build plus its own equipment set), the slot that is on, the pet out and the class's
 * Pet Rules ({@link SimPetRules}).
 *
 * <h2>API</h2>
 * <ul>
 * <li>{@link #classGear(SimStats.DungeonClass)} (this client's) / {@link #gear(ServerPlayer)} (any player, guests
 *     included): a {@link ClassGear}.</li>
 * <li>{@link #setWardrobeSlot(SimStats.DungeonClass, int, List, List, String)} and its {@link ServerPlayer} twin:
 *     fill one slot (0-8) with four armour and four equipment {@link SimItemBuild}s.</li>
 * <li>{@link #equipSlot(ServerPlayer, int)} / {@link #equipSlot(Minecraft, int)}: swap to a slot - armour into the
 *     armour slots, equipment on - instantly, server-side. {@link #activeSlot(ServerPlayer)}.</li>
 * <li>{@link #pet(ServerPlayer)}: the player's {@link PetBuild}; {@link PetBuild#petBaseAndItemStats()} is what
 *     Chimera copies (base + pet item, no perks).</li>
 * <li>Hooks for the damage code: {@link #markTeleport}/{@link #cloakMeleeBonus} (Shadow Assassin Cloak),
 *     {@link #arrowBonusPercent}, {@link #witherReduction}, {@link #implosionBonus}.</li>
 * </ul>
 *
 * <p>How the stats add up is {@link SimItemData} (per item) and {@link SimStats#effective} (the whole player).
 * Full-set bonuses need all four pieces of the same set: Superior Blood (+5% Combat stats), Wise Blood (2/3 mana
 * costs), Efficient Training (Adaptive); Witherborn (Necron's/Storm's/Goldor's/Maxor's) spawns a wither minion,
 * which the sim does not model. A Diamond Necron Head on Necron's chest/legs/boots is NOT a full set, as on Hypixel.
 *
 * <p>Saved to {@code sky2m-sim-gear.json}. A gear file from before the slots (one armour set per class) becomes slot
 * 1 with that set at 10 stars - the +50% the old approximation assumed; a stats profile saved before the wardrobe
 * existed carried its gear inside its stats, so on that first launch every class starts empty.
 */
public final class SimGear {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ModPaths.config("sky2m-sim-gear.json");

    /** Wardrobe slots per class: Hypixel's first page. */
    public static final int SLOTS = 9;
    /**
     * A wardrobe swap's guard: Hypixel publishes no wardrobe cooldown (none found in the wiki or forums), so only a
     * double-click guard of 2 ticks, the same as Autopet's.
     */
    public static final int WARDROBE_SWAP_TICKS = 2;

    /** What a pet does beyond its stats, where the sim can say. */
    public enum Perk {
        NONE,
        /** Spirit: a save from the first death of a run. A HOOK for the death system - see {@link SimClassKit}. */
        FIRST_DEATH_SAVE,
        /** Phoenix: Rekindle survives a lethal hit, then a 60 s cooldown ({@link SimClassKit#tryDeathSave}). */
        DEATH_SAVE,
        /** Sheep: abilities cost 20% less mana. */
        MANA_SAVER,
        /** Black Cat: speed and magic find, which the sim does not model (speed is /speed's). */
        SPEED_MAGIC_FIND,
        /** Stat perks applied in {@link SimStats#effective} (Golden Dragon, Ender Dragon, Blue Whale, Spider...). */
        STATS
    }

    // ------------------------------------------------------------------ armour set templates

    /**
     * One armour set as a template: what clicking it in the wardrobe puts in the slot's four pieces.
     *
     * @param idPrefix Hypixel's item id without the slot ({@code POWER_WITHER} makes {@code POWER_WITHER_HELMET})
     * @param colours  leather dye per piece, helmet to boots
     * @param classes  the classes it is usually worn on, marked in the wardrobe
     */
    public record Armour(String id, String name, String idPrefix, char colour, String rarity, int[] colours,
                         Set<SimStats.DungeonClass> classes, String bonus) {
        /** The four pieces' BASE stats added up (no stars or modifiers), Hypixel's data or the built-in copy. */
        public double stat(SimStats.Stat s) {
            Double v = totals().get(s);
            return v == null ? 0 : v;
        }

        /** Whether these numbers are Hypixel's live data (true) or the built-in copy (false). */
        public boolean real() {
            if (NONE_ID.equals(id)) {
                return true;
            }
            for (int i = 0; i < 4; i++) {
                SimItemData.Def d = SimItemData.def(pieceId(this, i));
                if (d == null || !d.real()) {
                    return false;
                }
            }
            return true;
        }

        /** Every base stat of the set. */
        public Map<SimStats.Stat, Double> totals() {
            Map<SimStats.Stat, Double> out = new EnumMap<>(SimStats.Stat.class);
            if (NONE_ID.equals(id)) {
                return out;
            }
            for (int i = 0; i < 4; i++) {
                SimItemData.Def d = SimItemData.def(pieceId(this, i));
                if (d != null) {
                    SimItemBuild.addAll(out, d.stats());
                }
            }
            return out;
        }

        public String pieceName(int piece) {
            SimItemData.Def d = SimItemData.def(pieceId(this, piece));
            return d != null ? d.name() : name + " " + SimItemData.ARMOUR_SLOT_NAMES[piece];
        }

        /** The four pieces, maxed ({@link SimItemData#fullBuild}). */
        public List<SimItemBuild> pieces() {
            if (NONE_ID.equals(id)) {
                return EMPTY_FOUR;
            }
            List<SimItemBuild> out = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                out.add(SimItemData.fullBuild(pieceId(this, i)));
            }
            return List.copyOf(out);
        }
    }

    /** The piece ids of a set, helmet to boots. */
    static String pieceId(Armour set, int piece) {
        return set.idPrefix() + "_" + PIECE_IDS[piece];
    }

    /** Which set a piece id belongs to, or null (a Necron head, a mask, a Skeleton Master piece...). */
    static Armour setOf(String pieceId) {
        if (pieceId == null) {
            return null;
        }
        for (Armour a : ARMOUR) {
            for (int i = 0; i < 4; i++) {
                if (pieceId.equals(pieceId(a, i))) {
                    return a;
                }
            }
        }
        return null;
    }

    /** The set all four pieces belong to (a full set), or null. */
    public static Armour fullSet(List<SimItemBuild> armour) {
        if (armour == null || armour.size() < 4) {
            return null;
        }
        Armour first = setOf(armour.get(0).id());
        if (first == null) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            if (!armour.get(i).id().equals(pieceId(first, i))) {
                return null;
            }
        }
        return first;
    }

    /** Superior Dragon's Superior Blood: "+5% Combat stats and Magic Find" with the full set (else 0). */
    static double setBonusPercent(List<SimItemBuild> armour) {
        Armour set = fullSet(armour);
        return set != null && "SUPERIOR_DRAGON".equals(set.id()) ? 5 : 0;
    }

    /**
     * Adaptive's Efficient Training, per class, with the full set (NEU repo lore, ADAPTIVE_CHESTPLATE): Berserk +20
     * Strength, Healer +40 Health, Mage +50 Intelligence, Tank +30 Defense, Archer +5 Crit Chance and +15 Crit
     * Damage, per piece (x4). Mending and the Tank's damage reduction are not modelled.
     */
    static Map<SimStats.Stat, Double> setBonusFlat(List<SimItemBuild> armour, SimStats.DungeonClass c) {
        Armour set = fullSet(armour);
        if (set == null || !"ADAPTIVE".equals(set.id()) || c == null) {
            return Map.of();
        }
        return switch (c) {
            case BERSERK -> Map.of(SimStats.Stat.STRENGTH, 80.0);
            case HEALER -> Map.of(SimStats.Stat.HEALTH, 160.0);
            case MAGE -> Map.of(SimStats.Stat.INTELLIGENCE, 200.0);
            case TANK -> Map.of(SimStats.Stat.DEFENSE, 120.0);
            case ARCHER -> Map.of(SimStats.Stat.CRIT_CHANCE, 20.0, SimStats.Stat.CRIT_DAMAGE, 60.0);
        };
    }

    /** Adaptive's "Every 5 Catacombs levels, this armor piece gains +2% stats" on an Adaptive piece. */
    static double pieceScale(String pieceId, int catacombs) {
        return pieceId != null && pieceId.startsWith("ADAPTIVE_") && !pieceId.equals("ADAPTIVE_BELT")
                ? 1.0 + 0.02 * (Math.max(0, catacombs) / 5) : 1.0;
    }

    /** Wise Dragon's Wise Blood: "Abilities have 2/3 of the mana cost" with the full set. */
    static double manaCostMultiplier(ClassGear gear) {
        Armour set = gear == null ? null : fullSet(gear.armour());
        return set != null && "WISE_DRAGON".equals(set.id()) ? 2.0 / 3.0 : 1.0;
    }

    /**
     * Arrow damage % the armour adds (an additive bucket, like the Archer's): Maxor's "+5% arrow damage per piece"
     * and the Skeleton Master's "Increases the damage you deal with arrows by 5%" per piece.
     */
    public static double arrowBonusPercent(List<SimItemBuild> armour) {
        double out = 0;
        for (SimItemBuild b : armour) {
            if (b.id().startsWith("SPEED_WITHER_") || b.id().startsWith("SKELETON_MASTER_")) {
                out += 5;
            }
        }
        return out;
    }

    /** The share of a wither's hit taken off: 10% per Necron's/Storm's/Goldor's/Maxor's piece. */
    public static double witherReduction(List<SimItemBuild> armour) {
        double out = 0;
        for (SimItemBuild b : armour) {
            String id = b.id();
            if (id.startsWith("POWER_WITHER_") || id.startsWith("WISE_WITHER_") || id.startsWith("TANK_WITHER_")
                    || id.startsWith("SPEED_WITHER_")) {
                out += 0.10;
            }
        }
        return Math.min(1, out);
    }

    /** Implosion Belt's Consolidated: "+25% explosion damage dealt" (0 without the belt). For explosions' code. */
    public static double implosionBonus(ServerPlayer sp) {
        return wears(gear(sp).equipment(), "IMPLOSION_BELT") ? 0.25 : 0;
    }

    static boolean wears(List<SimItemBuild> builds, String id) {
        for (SimItemBuild b : builds) {
            if (b.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] PIECE_IDS = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
    private static final Item[] PIECE_ITEMS = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
            Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
    private static final EquipmentSlot[] PIECE_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public static final String NONE_ID = "NONE";
    static final List<SimItemBuild> EMPTY_FOUR = List.of(SimItemBuild.EMPTY, SimItemBuild.EMPTY, SimItemBuild.EMPTY,
            SimItemBuild.EMPTY);

    public static final Armour NO_ARMOUR = new Armour(NONE_ID, "No Armor", "", '7', "", new int[4], Set.of(), "");
    /** Not a set: the slot holds pieces of different sets (or none of any set). */
    public static final Armour MIXED = new Armour("MIXED", "Mixed Armor", "", 'f', "", new int[4], Set.of(), "");

    private static final Set<SimStats.DungeonClass> ALL = EnumSet.allOf(SimStats.DungeonClass.class);

    /** Every set template, in wardrobe order. */
    public static final List<Armour> ARMOUR = List.of(
            new Armour("NECRON", "Necron's Armor", "POWER_WITHER", '6', "LEGENDARY",
                    new int[]{0xE7413C, 0xE7413C, 0xE75C3C, 0xE76E3C}, EnumSet.of(SimStats.DungeonClass.BERSERK,
                    SimStats.DungeonClass.ARCHER), "Witherborn"),
            new Armour("STORM", "Storm's Armor", "WISE_WITHER", '6', "LEGENDARY",
                    new int[]{0x1793C4, 0x1793C4, 0x17A8C4, 0x1CD4E4}, EnumSet.of(SimStats.DungeonClass.MAGE,
                    SimStats.DungeonClass.HEALER), "Witherborn"),
            new Armour("GOLDOR", "Goldor's Armor", "TANK_WITHER", '6', "LEGENDARY",
                    new int[]{0x45413C, 0x45413C, 0x65605A, 0x88837E}, EnumSet.of(SimStats.DungeonClass.TANK),
                    "Witherborn"),
            new Armour("MAXOR", "Maxor's Armor", "SPEED_WITHER", '6', "LEGENDARY",
                    new int[]{0x4A14B7, 0x4A14B7, 0x5D2FB9, 0x8969C8}, EnumSet.of(SimStats.DungeonClass.ARCHER),
                    "Witherborn"),
            new Armour("SHADOW_ASSASSIN", "Shadow Assassin Armor", "SHADOW_ASSASSIN", '5', "EPIC",
                    new int[]{0x000000, 0x000000, 0x000000, 0x000000}, EnumSet.of(SimStats.DungeonClass.BERSERK),
                    "Shadow Assassin"),
            new Armour("WISE_DRAGON", "Wise Dragon Armor", "WISE_DRAGON", '6', "LEGENDARY",
                    new int[]{0x29F0E9, 0x29F0E9, 0x29F0E9, 0x29F0E9}, EnumSet.of(SimStats.DungeonClass.MAGE,
                    SimStats.DungeonClass.HEALER), "Wise Blood"),
            new Armour("SUPERIOR_DRAGON", "Superior Dragon Armor", "SUPERIOR_DRAGON", '6', "LEGENDARY",
                    new int[]{0xF2DF11, 0xF2DF11, 0xF2DF11, 0xF25D18}, ALL, "Superior Blood"),
            new Armour("ADAPTIVE", "Adaptive Armor", "ADAPTIVE", '5', "EPIC",
                    new int[]{0x3ABE78, 0x3ABE78, 0x3ABE78, 0x3ABE78}, ALL, "Efficient Training"));

    // ------------------------------------------------------------------ pets

    /**
     * One pet as the sim keeps it, at its {@link #level} and {@link #rarity} (the spec's: Golden Dragon 200, Spider
     * Mythic, Phoenix Special, the rest Legendary 100).
     *
     * @param stats the built-in copy of its stats at that level, used until NEU's petnums.json has loaded
     */
    public record Pet(String id, String name, char colour, Item icon, String rarity, int level,
                      Map<SimStats.Stat, Double> stats, Set<SimStats.DungeonClass> classes, Perk perk,
                      String perkText) {
        /** The pet's own stats: NEU's petnums.json when loaded, else the built-in copy. No pet item, no perks. */
        public double stat(SimStats.Stat s) {
            Double v = totals().get(s);
            return v == null ? 0 : v;
        }

        /** NEU's numbers for this pet at its level and rarity, or null. */
        public SimRealStats.PetStats real() {
            return NONE_ID.equals(id) ? null : SimRealStats.pet(id, rarity, level);
        }

        /** Every stat of the pet, as {@link #stat} reads them. */
        public Map<SimStats.Stat, Double> totals() {
            SimRealStats.PetStats real = real();
            if (real != null) {
                return real.stats();
            }
            if (!NONE_ID.equals(id)) {
                SimRealStats.fallback("pet " + id);
            }
            return stats;
        }

        /** Perk number {@code {i}} from NEU's otherNums, or {@code fallback}. */
        public double perkNumber(int i, double fallback) {
            SimRealStats.PetStats real = real();
            return real == null ? fallback : real.other(i, fallback);
        }
    }

    /**
     * Pet items (hypixelskyblock.minecraft.wiki and NEU's item files, see research/pets.md). Multipliers act on the
     * pet's own base stats only, never its perks.
     */
    public enum PetItem {
        NONE("No Pet Item", ""),
        UNALLOYED_SPEED("Unalloyed Speed", "+50 Speed cap"),
        ANTIQUE_REMEDIES("Antique Remedies", "Pet's Strength +80%"),
        HEPHAESTUS_REMEDIES("Hephaestus Remedies", "Pet's Strength +100%"),
        GOLD_CLAWS("Gold Claws", "Pet's Crit Chance and Crit Damage +50%"),
        HEPHAESTUS_RELIC("Hephaestus Relic", "All pet stats +50%"),
        SERRATED_CLAWS("Serrated Claws", "+25 Crit Damage"),
        CROCHET_TIGER_PLUSHIE("Crochet Tiger Plushie", "+35 Attack Speed"),
        HEPHAESTUS_PLUSHIE("Hephaestus Plushie", "+50 Attack Speed");

        public final String label;
        public final String effect;

        PetItem(String label, String effect) {
            this.label = label;
            this.effect = effect;
        }

        public static PetItem parse(String s) {
            if (s == null || s.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    /** Assumed Gold Collection digits for Shining Scales (+15 Strength a digit, capped at 9 = 100M+). */
    public static final int GOLD_COLLECTION_DIGITS = 9;
    /** Assumed coins in the bank for Legendary Treasure (+0.25% damage per 1M, max 250%): 1B, the cap. */
    public static final double BANK_MILLIONS = 1_000;

    /** A pet with its pet item: what is out, and what Pet Rules swap between. */
    public record PetBuild(Pet pet, PetItem item) {
        public static final PetBuild NONE = new PetBuild(NO_PET_DEF(), PetItem.NONE);

        public PetBuild {
            pet = pet == null ? NO_PET_DEF() : pet;
            item = item == null ? PetItem.NONE : item;
        }

        public String id() {
            return pet.id();
        }

        public boolean sameAs(PetBuild o) {
            return o != null && pet.id().equals(o.pet.id()) && item == o.item;
        }

        public String label() {
            return pet.name() + (item == PetItem.NONE ? "" : " (" + item.label + ")");
        }

        /**
         * The pet's base stats with its pet item applied - what Chimera copies ("including stats added by Pet
         * Items", not perks). Remedies multiply Strength (x1.8 / x2), Gold Claws Crit Chance and Damage (x1.5),
         * Hephaestus Relic every stat (x1.5); Serrated Claws and the plushies add flat.
         */
        public Map<SimStats.Stat, Double> petBaseAndItemStats() {
            Map<SimStats.Stat, Double> m = new EnumMap<>(SimStats.Stat.class);
            m.putAll(pet.totals());
            switch (item) {
                case ANTIQUE_REMEDIES -> m.computeIfPresent(SimStats.Stat.STRENGTH, (k, v) -> v * 1.8);
                case HEPHAESTUS_REMEDIES -> m.computeIfPresent(SimStats.Stat.STRENGTH, (k, v) -> v * 2.0);
                case GOLD_CLAWS -> {
                    m.computeIfPresent(SimStats.Stat.CRIT_CHANCE, (k, v) -> v * 1.5);
                    m.computeIfPresent(SimStats.Stat.CRIT_DAMAGE, (k, v) -> v * 1.5);
                }
                case HEPHAESTUS_RELIC -> m.replaceAll((k, v) -> v * 1.5);
                case SERRATED_CLAWS -> m.merge(SimStats.Stat.CRIT_DAMAGE, 25.0, Double::sum);
                case CROCHET_TIGER_PLUSHIE -> m.merge(SimStats.Stat.ATTACK_SPEED, 35.0, Double::sum);
                case HEPHAESTUS_PLUSHIE -> m.merge(SimStats.Stat.ATTACK_SPEED, 50.0, Double::sum);
                default -> {
                }
            }
            return m;
        }

        /**
         * Everything the pet adds as flat stats: {@link #petBaseAndItemStats} plus its flat stat perks - the Golden
         * Dragon's Shining Scales (+15 Strength per Gold Collection digit, {@link #GOLD_COLLECTION_DIGITS} assumed).
         * The percentage perks are {@link #statPercent}.
         */
        public Map<SimStats.Stat, Double> stats() {
            Map<SimStats.Stat, Double> m = petBaseAndItemStats();
            if ("GOLDEN_DRAGON".equals(pet.id()) && pet.level() > 100) {
                m.merge(SimStats.Stat.STRENGTH, 15.0 * GOLD_COLLECTION_DIGITS * (pet.level() - 100) / 100.0,
                        Double::sum);
            }
            return m;
        }

        /**
         * The pet's "+X% all Combat stats" perk, added to the same bucket as Legion and Superior Blood (additive
         * since Aug 2022): the Ender Dragon's Superior, +10% (NEU otherNums[3]).
         */
        public double combatPercent() {
            return "ENDER_DRAGON".equals(pet.id()) ? pet.perkNumber(3, 10) : 0;
        }

        /**
         * A perk's % on one stat: Golden Dragon Dragon's Greed +5% Strength (its cap - +0.5% per 5 Magic Find, the
         * sim has no MF, so a dungeon player's 50+ is assumed); Blue Whale Archimedes +20% Health.
         */
        public double statPercent(SimStats.Stat s) {
            if ("GOLDEN_DRAGON".equals(pet.id()) && s == SimStats.Stat.STRENGTH) {
                return 5.0 * Math.max(0, pet.level() - 100) / 100.0;
            }
            if ("BLUE_WHALE".equals(pet.id()) && s == SimStats.Stat.HEALTH) {
                return 0.2 * pet.level();
            }
            return 0;
        }

        /** Blue Whale's Bulk: +1 Defense per this much max Health (0 = none). */
        public double bulkHealthPerDefense() {
            return "BLUE_WHALE".equals(pet.id()) ? 20 : 0;
        }

        /** Golden Dragon's Legendary Treasure: +% damage (additive), {@link #BANK_MILLIONS} assumed. For SimEnchants. */
        public double legendaryTreasurePercent() {
            if (!"GOLDEN_DRAGON".equals(pet.id()) || pet.level() <= 100) {
                return 0;
            }
            double per = 0.25 * (pet.level() - 100) / 100.0;
            return Math.min(per * BANK_MILLIONS, 250.0 * (pet.level() - 100) / 100.0);
        }

        /** Ender Dragon's End Strike: +% damage to Ender mobs. */
        public double endStrikePercent() {
            return "ENDER_DRAGON".equals(pet.id()) ? 2.0 * pet.level() : 0;
        }

        /** Phoenix Fourth Flare: on the 4th melee strike, ignite for this many x Crit Damage a second... */
        public double fourthFlareCritMultiplier() {
            return "PHOENIX".equals(pet.id()) ? 1 + 12.0 * (pet.level() - 1) / 99.0 : 0;
        }

        /** ...for this many seconds (2 s at level 1, 4 s at 100). */
        public double fourthFlareSeconds() {
            return "PHOENIX".equals(pet.id()) ? 2 + 2.0 * (pet.level() - 1) / 99.0 : 0;
        }

        /** Phoenix Rekindle: Strength while immune, and how long (20 Str for 4 s at level 100; 60 s cooldown). */
        public double rekindleStrength() {
            return "PHOENIX".equals(pet.id()) ? 10 + 10.0 * (pet.level() - 1) / 99.0 : 0;
        }

        public int rekindleTicks() {
            return "PHOENIX".equals(pet.id()) ? (int) Math.round(20 * (2 + 2.0 * (pet.level() - 1) / 99.0)) : 0;
        }

        /** Jellyfish: Power Orbs cost this share less mana in dungeons (50%). */
        public double powerOrbManaSaving() {
            return "JELLYFISH".equals(pet.id()) ? 0.005 * pet.level() : 0;
        }

        /** Jellyfish (Legendary): Dungeon Potions this share more effective (50%). */
        public double dungeonPotionBonus() {
            return "JELLYFISH".equals(pet.id()) && "LEGENDARY".equals(pet.rarity()) ? 0.005 * pet.level() : 0;
        }

        /** Speed-cap raise (Black Cat's Hunter +100, Unalloyed Speed +50) - for /speed's cap, not a sim stat. */
        public double speedCapBonus() {
            return ("BLACK_CAT".equals(pet.id()) ? pet.level() : 0) + (item == PetItem.UNALLOYED_SPEED ? 50 : 0);
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", pet.id());
            if (item != PetItem.NONE) {
                o.addProperty("item", item.name());
            }
            return o;
        }

        public static PetBuild fromJson(JsonElement el) {
            if (el == null || el.isJsonNull()) {
                return NONE;
            }
            if (el.isJsonPrimitive()) {
                return new PetBuild(SimGear.pet(el.getAsString()), PetItem.NONE);
            }
            JsonObject o = el.getAsJsonObject();
            return new PetBuild(SimGear.pet(o.has("id") ? o.get("id").getAsString() : NONE_ID),
                    PetItem.parse(o.has("item") ? o.get("item").getAsString() : null));
        }
    }

    private static Pet noPet;

    private static Pet NO_PET_DEF() {
        if (noPet == null) {
            noPet = new Pet(NONE_ID, "No Pet", '7', Items.BARRIER, "", 0, Map.of(), Set.of(), Perk.NONE, "");
        }
        return noPet;
    }

    public static final Pet NO_PET = NO_PET_DEF();

    private static Map<SimStats.Stat, Double> st(Object... kv) {
        Map<SimStats.Stat, Double> m = new EnumMap<>(SimStats.Stat.class);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((SimStats.Stat) kv[i], ((Number) kv[i + 1]).doubleValue());
        }
        return Map.copyOf(m);
    }

    /**
     * Every pet, in the pets menu's order. The built-in stats are NEU petnums.json's at the pet's level (offline
     * fallback); the perk texts are research/pets.md's.
     */
    public static final List<Pet> PETS = List.of(
            new Pet("GOLDEN_DRAGON", "Golden Dragon", '6', Items.GOLD_BLOCK, "LEGENDARY", 200,
                    st(SimStats.Stat.STRENGTH, 50, SimStats.Stat.ATTACK_SPEED, 50), ALL, Perk.STATS,
                    "Shining Scales +135 Str, Dragon's Greed +5% Str, Legendary Treasure"),
            new Pet("BLACK_CAT", "Black Cat", '6', Items.WOOL.black(), "LEGENDARY", 100,
                    st(SimStats.Stat.INTELLIGENCE, 100), ALL, Perk.SPEED_MAGIC_FIND,
                    "Hunter: +100 Speed and Speed cap (speed is /speed's)"),
            new Pet("JELLYFISH", "Jellyfish", '6', Items.SLIME_BALL, "LEGENDARY", 100,
                    st(SimStats.Stat.HEALTH, 200), ALL, Perk.STATS,
                    "Power Orbs -50% mana, Wish cooldown, Dungeon Potions +50%"),
            new Pet("ENDER_DRAGON", "Ender Dragon", '6', Items.DRAGON_HEAD, "LEGENDARY", 100,
                    st(SimStats.Stat.STRENGTH, 50, SimStats.Stat.CRIT_CHANCE, 10, SimStats.Stat.CRIT_DAMAGE, 50),
                    EnumSet.of(SimStats.DungeonClass.ARCHER, SimStats.DungeonClass.BERSERK), Perk.STATS,
                    "Superior: +10% Combat stats; End Strike +200% vs Ender mobs"),
            new Pet("PHOENIX", "Phoenix", 'c', Items.BLAZE_POWDER, "SPECIAL", 100,
                    st(SimStats.Stat.STRENGTH, 60, SimStats.Stat.INTELLIGENCE, 150), ALL, Perk.DEATH_SAVE,
                    "Rekindle: survive a lethal hit, immune +20 Str for 4s, 60s cooldown"),
            new Pet("BLAZE", "Blaze", '6', Items.BLAZE_ROD, "LEGENDARY", 100,
                    st(SimStats.Stat.DEFENSE, 30, SimStats.Stat.INTELLIGENCE, 100),
                    EnumSet.of(SimStats.DungeonClass.ARCHER), Perk.STATS,
                    "Fusion-Style Potato: potato books doubled; Bling Armor"),
            new Pet("SPIDER", "Spider", 'd', Items.SPIDER_EYE, "MYTHIC", 100,
                    st(SimStats.Stat.STRENGTH, 50, SimStats.Stat.CRIT_CHANCE, 10),
                    EnumSet.of(SimStats.DungeonClass.BERSERK), Perk.STATS,
                    "Web Battlefield: kills give +6 Str to players within 20 (x10, 40s)"),
            new Pet("BLUE_WHALE", "Blue Whale", '6', Items.COD, "LEGENDARY", 100,
                    st(SimStats.Stat.HEALTH, 200), EnumSet.of(SimStats.DungeonClass.TANK), Perk.STATS,
                    "Archimedes +20% Health, Bulk +1 Def per 20 Health, Ingest"),
            new Pet("SPIRIT", "Spirit", '6', Items.GHAST_TEAR, "LEGENDARY", 100,
                    st(SimStats.Stat.INTELLIGENCE, 300), EnumSet.of(SimStats.DungeonClass.MAGE,
                    SimStats.DungeonClass.HEALER), Perk.FIRST_DEATH_SAVE,
                    "Spirit Assistance: helps you on your first death in a run"),
            new Pet("SHEEP", "Sheep", '6', Items.WOOL.white(), "LEGENDARY", 100,
                    st(SimStats.Stat.INTELLIGENCE, 100, SimStats.Stat.ABILITY_DAMAGE, 10),
                    EnumSet.of(SimStats.DungeonClass.MAGE), Perk.MANA_SAVER,
                    "Mana Saver and Dungeon Wizard (more mana in dungeons)"),
            new Pet("BABY_YETI", "Baby Yeti", '6', Items.SNOW_BLOCK, "LEGENDARY", 100,
                    st(SimStats.Stat.STRENGTH, 40, SimStats.Stat.DEFENSE, 200),
                    EnumSet.of(SimStats.DungeonClass.TANK), Perk.NONE, "Strength (its other perks are fishing ones)"),
            new Pet("WITHER_SKELETON", "Wither Skeleton", '6', Items.WITHER_SKELETON_SKULL, "LEGENDARY", 100,
                    st(SimStats.Stat.STRENGTH, 25, SimStats.Stat.CRIT_CHANCE, 5, SimStats.Stat.CRIT_DAMAGE, 25,
                            SimStats.Stat.DEFENSE, 25, SimStats.Stat.INTELLIGENCE, 25), ALL, Perk.NONE,
                    "A bit of every stat"));

    /** Each class's usual set, for a fresh install and the default presets ({@link SimLoadout}). */
    static String defaultArmour(SimStats.DungeonClass c) {
        return switch (c) {
            case ARCHER -> "MAXOR";
            case BERSERK -> "NECRON";
            case HEALER -> "SUPERIOR_DRAGON";
            case MAGE -> "STORM";
            case TANK -> "GOLDOR";
        };
    }

    static String defaultPet(SimStats.DungeonClass c) {
        return switch (c) {
            case ARCHER -> "ENDER_DRAGON";
            case BERSERK -> "GOLDEN_DRAGON";
            case HEALER -> "PHOENIX";
            case MAGE -> "SHEEP";
            case TANK -> "BABY_YETI";
        };
    }

    // ------------------------------------------------------------------ the wardrobe

    /** One wardrobe slot: a name, four armour builds (helmet..boots) and four equipment builds. */
    public record WardrobeSlot(String name, List<SimItemBuild> armour, List<SimItemBuild> equipment) {
        public static final WardrobeSlot EMPTY = new WardrobeSlot("", EMPTY_FOUR, EMPTY_FOUR);

        public WardrobeSlot {
            name = name == null ? "" : name;
            armour = four(armour);
            equipment = four(equipment);
        }

        public boolean isEmpty() {
            for (int i = 0; i < 4; i++) {
                if (!armour.get(i).isEmpty() || !equipment.get(i).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        public WardrobeSlot withArmour(int piece, SimItemBuild b) {
            List<SimItemBuild> a = new ArrayList<>(armour);
            a.set(piece, b == null ? SimItemBuild.EMPTY : b);
            return new WardrobeSlot(name, a, equipment);
        }

        public WardrobeSlot withEquipment(int piece, SimItemBuild b) {
            List<SimItemBuild> e = new ArrayList<>(equipment);
            e.set(piece, b == null ? SimItemBuild.EMPTY : b);
            return new WardrobeSlot(name, armour, e);
        }

        /** "Necron's Armor" when it is a full set, else the slot's name or "Mixed Armor". */
        public String label() {
            if (!name.isBlank()) {
                return name;
            }
            Armour set = fullSet(armour);
            return set != null ? set.name() : isEmpty() ? "Empty" : MIXED.name();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            JsonArray a = new JsonArray();
            armour.forEach(b -> a.add(b.isEmpty() ? new JsonObject() : b.toJson()));
            o.add("armour", a);
            JsonArray e = new JsonArray();
            equipment.forEach(b -> e.add(b.isEmpty() ? new JsonObject() : b.toJson()));
            o.add("equipment", e);
            return o;
        }

        static WardrobeSlot fromJson(JsonElement el) {
            if (!(el instanceof JsonObject o)) {
                return EMPTY;
            }
            return new WardrobeSlot(o.has("name") ? o.get("name").getAsString() : "", builds(o.get("armour"), true),
                    builds(o.get("equipment"), false));
        }

        private static List<SimItemBuild> builds(JsonElement el, boolean armour) {
            List<SimItemBuild> out = new ArrayList<>();
            if (el instanceof JsonArray a) {
                for (int i = 0; i < a.size() && i < 4; i++) {
                    out.add(clean(SimItemBuild.fromJson(a.get(i)), i, armour));
                }
            }
            return out;
        }
    }

    /** A build only where it fits: an armour piece in its own slot, equipment in its own (else empty). */
    static SimItemBuild clean(SimItemBuild b, int slot, boolean armour) {
        if (b == null || b.isEmpty()) {
            return SimItemBuild.EMPTY;
        }
        SimItemData.Def d = SimItemData.def(b.id());
        if (d == null) {
            return SimItemBuild.EMPTY;
        }
        int at = armour ? SimItemData.armourSlot(d.category()) : SimItemData.equipmentSlot(d.category());
        return at == slot ? b : SimItemBuild.EMPTY;
    }

    static List<SimItemBuild> four(List<SimItemBuild> in) {
        List<SimItemBuild> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            SimItemBuild b = in != null && i < in.size() ? in.get(i) : null;
            out.add(b == null ? SimItemBuild.EMPTY : b);
        }
        return List.copyOf(out);
    }

    /**
     * One class's gear: its nine wardrobe slots, which one is on ({@code active}, -1 for none), the pet out, the
     * Pet Rule and the item each pet last held (so picking a pet again brings its item back).
     */
    public record ClassGear(List<WardrobeSlot> slots, int active, PetBuild pet, SimPetRules.Rule rule,
                            Map<String, PetItem> petItems) {
        public ClassGear {
            List<WardrobeSlot> s = new ArrayList<>(SLOTS);
            for (int i = 0; i < SLOTS; i++) {
                WardrobeSlot w = slots != null && i < slots.size() ? slots.get(i) : null;
                s.add(w == null ? WardrobeSlot.EMPTY : w);
            }
            slots = List.copyOf(s);
            active = active < -1 || active >= SLOTS ? -1 : active;
            pet = pet == null ? PetBuild.NONE : pet;
            rule = rule == null ? SimPetRules.Rule.OFF : rule;
            petItems = petItems == null ? Map.of() : Map.copyOf(petItems);
        }

        public static final ClassGear EMPTY = new ClassGear(List.of(), -1, PetBuild.NONE, SimPetRules.Rule.OFF,
                Map.of());

        public WardrobeSlot activeSlot() {
            return active < 0 ? WardrobeSlot.EMPTY : slots.get(active);
        }

        /** The worn armour builds, helmet to boots. */
        public List<SimItemBuild> armour() {
            return activeSlot().armour();
        }

        /** The worn equipment builds: necklace, cloak, belt, gloves. */
        public List<SimItemBuild> equipment() {
            return activeSlot().equipment();
        }

        public ClassGear withSlot(int i, WardrobeSlot w) {
            List<WardrobeSlot> s = new ArrayList<>(slots);
            s.set(i, w == null ? WardrobeSlot.EMPTY : w);
            return new ClassGear(s, active, pet, rule, petItems);
        }

        public ClassGear withActive(int i) {
            return new ClassGear(slots, i, pet, rule, petItems);
        }

        public ClassGear withPet(PetBuild p) {
            Map<String, PetItem> items = new HashMap<>(petItems);
            if (p != null && !NONE_ID.equals(p.id())) {
                items.put(p.id(), p.item());
            }
            return new ClassGear(slots, active, p, rule, items);
        }

        public ClassGear withRule(SimPetRules.Rule r) {
            return new ClassGear(slots, active, pet, r, petItems);
        }

        /** The item this pet holds when picked again (its last one). */
        public PetItem itemFor(String petId) {
            return petItems.getOrDefault(petId, defaultPetItem(petId));
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            slots.forEach(w -> a.add(w.toJson()));
            o.add("slots", a);
            o.addProperty("active", active);
            o.add("pet", pet.toJson());
            o.add("petRule", rule.toJson());
            JsonObject items = new JsonObject();
            petItems.forEach((k, v) -> items.addProperty(k, v.name()));
            o.add("petItems", items);
            return o;
        }

        public static ClassGear fromJson(JsonObject o) {
            List<WardrobeSlot> slots = new ArrayList<>();
            if (o.get("slots") instanceof JsonArray a) {
                for (JsonElement e : a) {
                    slots.add(WardrobeSlot.fromJson(e));
                }
            }
            Map<String, PetItem> items = new HashMap<>();
            if (o.get("petItems") instanceof JsonObject io) {
                for (var e : io.entrySet()) {
                    items.put(e.getKey(), PetItem.parse(e.getValue().getAsString()));
                }
            }
            return new ClassGear(slots, o.has("active") ? o.get("active").getAsInt() : -1,
                    PetBuild.fromJson(o.get("pet")), SimPetRules.Rule.fromJson(o.get("petRule")), items);
        }
    }

    private static final Map<SimStats.DungeonClass, ClassGear> GEAR = new EnumMap<>(SimStats.DungeonClass.class);
    private static volatile boolean loaded;

    private SimGear() {
    }

    // ------------------------------------------------------------------ registration

    private static net.minecraft.client.KeyMapping swapKey;

    public static void register() {
        // Read now, while "was there a stats file before this launch" still means what it says.
        load();
        // The pets' real numbers (NEU petnums.json), fetched in the background now so the first stats screen has them.
        SimRealStats.preload();
        SimPetRules.register();
        // "Swap wardrobe slot": cycles to the class's next filled slot, for the Mage's LCM <-> RCM mid-run.
        try {
            swapKey = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
                    new net.minecraft.client.KeyMapping("key.sky2m.sim_wardrobe_swap",
                            com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD,
                            com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(),
                            com.epic60869.sky2m.input.Sky2MKeyMappings.CATEGORY));
        } catch (RuntimeException e) {
            LOGGER.warn("Sim gear: could not register the wardrobe swap key", e);
        }
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (swapKey != null && swapKey.consumeClick()) {
                if (SimState.canAct(client) && McCompat.screen(client) == null) {
                    cycleSlot(client);
                }
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (SimState.isActive()) {
                tickSoulweaver(server);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (SimState.isActive() && source.getEntity() instanceof ServerPlayer killer && entity != killer
                    && !(entity instanceof Player)) {
                onKill(killer, entity, source);
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(ClientCommands.literal("simwardrobe")
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .then(ClientCommands.argument("slot", com.mojang.brigadier.arguments.IntegerArgumentType
                            .integer(1, SLOTS)).executes(ctx -> {
                                equipSlot(Minecraft.getInstance(), com.mojang.brigadier.arguments.IntegerArgumentType
                                        .getInteger(ctx, "slot") - 1);
                                return 1;
                            }))
                    .executes(ctx -> {
                        open(false);
                        return 1;
                    }));
            dispatcher.register(ClientCommands.literal("simpets")
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .executes(ctx -> {
                        open(true);
                        return 1;
                    }));
        });
    }

    private static void open(boolean pets) {
        Minecraft client = Minecraft.getInstance();
        // Next tick: the chat screen is still closing.
        client.execute(() -> McCompat.setScreen(client, new SimWardrobeScreen(McCompat.screen(client), pets)));
    }

    // ------------------------------------------------------------------ lookups

    /** A set template by id, or {@link #NO_ARMOUR}. */
    public static Armour armour(String id) {
        for (Armour a : ARMOUR) {
            if (a.id().equalsIgnoreCase(id)) {
                return a;
            }
        }
        return "MIXED".equalsIgnoreCase(id) ? MIXED : NO_ARMOUR;
    }

    public static Pet pet(String id) {
        for (Pet p : PETS) {
            if (p.id().equalsIgnoreCase(id)) {
                return p;
            }
        }
        return NO_PET;
    }

    /** This client's gear for a class. Any thread. */
    public static ClassGear classGear(SimStats.DungeonClass c) {
        load();
        synchronized (GEAR) {
            return playable(c, GEAR.getOrDefault(c, ClassGear.EMPTY));
        }
    }

    /** Default gear per class, built once ({@link #defaultGear} builds every piece maxed, and gear is read per hit). */
    private static final Map<SimStats.DungeonClass, ClassGear> DEFAULT_GEAR = new EnumMap<>(SimStats.DungeonClass.class);

    /**
     * What a class's saved gear plays as: itself, except that a player who is not staff and has nothing at all in the
     * class's wardrobe (every slot empty, no pet) plays the class's default gear ({@link #defaultGear}).
     *
     * <p>A non-staff player always plays the default stats ({@link SimStats#profile}), whose base is WITHOUT gear and
     * expects the wardrobe's set on top. But {@link #load} gave every class EMPTY gear whenever a stats file was
     * already there when the game started - the rule for a staff profile from before the wardrobe, whose typed base
     * already counted its gear - and a gear file saved after that kept the EMPTY classes. So a non-staff player who
     * had ever used the sim before played every floor in 1,400 health and 300 Defense (about 3,200 and 700 with the
     * old no-gear boost). Decided here, on each read, and not at load: staff status is not known yet when the gear
     * first loads (it arrives after the Sky2M login), and logging in as staff later gives the saved EMPTY gear back.
     * Changing the gear ({@link #update}) starts from what is played, so it saves the defaults with the change.
     */
    private static ClassGear playable(SimStats.DungeonClass c, ClassGear stored) {
        if (!bare(stored) || com.epic60869.sky2m.features.sbc.SbcStaff.isStaff()) {
            return stored;
        }
        return DEFAULT_GEAR.computeIfAbsent(c, SimGear::defaultGear);
    }

    /** Nothing in any wardrobe slot and no pet out: gear never set up (or the old EMPTY start). */
    private static boolean bare(ClassGear g) {
        if (g == null) {
            return true;
        }
        for (WardrobeSlot w : g.slots()) {
            if (w != null && !w.isEmpty()) {
                return false;
            }
        }
        return NONE_ID.equals(g.pet().id());
    }

    /** Any player's gear (a guest's as he sent it). Server thread. */
    public static ClassGear gear(ServerPlayer sp) {
        return SimParty.member(sp).gear();
    }

    /** The set template the class's active slot is (a full set), {@link #MIXED}, or {@link #NO_ARMOUR}. */
    public static Armour armourFor(SimStats.DungeonClass c) {
        List<SimItemBuild> a = classGear(c).armour();
        Armour set = fullSet(a);
        if (set != null) {
            return set;
        }
        for (SimItemBuild b : a) {
            if (!b.isEmpty()) {
                return MIXED;
            }
        }
        return NO_ARMOUR;
    }

    public static Pet petFor(SimStats.DungeonClass c) {
        return classGear(c).pet().pet();
    }

    public static PetBuild petBuildFor(SimStats.DungeonClass c) {
        return classGear(c).pet();
    }

    /** The selected class's pet - what the perks read. */
    public static Pet currentPet() {
        return petFor(SimStats.profile().selected());
    }

    /** A player's pet and pet item right now. Server thread. */
    public static PetBuild pet(ServerPlayer sp) {
        return gear(sp).pet();
    }

    public static int activeSlot(SimStats.DungeonClass c) {
        return classGear(c).active();
    }

    public static int activeSlot(ServerPlayer sp) {
        return gear(sp).active();
    }

    // ------------------------------------------------------------------ changing (client thread unless said)

    /** Replaces a class's whole gear (presets). */
    public static void setClassGear(SimStats.DungeonClass c, ClassGear g) {
        load();
        synchronized (GEAR) {
            GEAR.put(c, g == null ? ClassGear.EMPTY : g);
        }
        changed();
    }

    private static void update(SimStats.DungeonClass c, java.util.function.UnaryOperator<ClassGear> f) {
        load();
        synchronized (GEAR) {
            GEAR.put(c, f.apply(playable(c, GEAR.getOrDefault(c, ClassGear.EMPTY))));
        }
        changed();
    }

    /** Saved, and a party guest's wardrobe sent to the host. Off the client thread it is queued onto it. */
    private static void changed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isSameThread()) {
            persist();
            SimParty.sendProfile();
        } else {
            mc.execute(() -> {
                persist();
                SimParty.sendProfile();
            });
        }
    }

    /**
     * Fills one of a class's wardrobe slots (0-8): four armour builds (helmet..boots) and four equipment builds
     * (necklace, cloak, belt, gloves), any of which may be {@link SimItemBuild#EMPTY}. A build in the wrong slot is
     * dropped. Does not equip it ({@link #equipSlot}).
     */
    public static void setWardrobeSlot(SimStats.DungeonClass c, int slot, List<SimItemBuild> armour,
                                       List<SimItemBuild> equipment, String name) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        WardrobeSlot w = slotOf(armour, equipment, name);
        update(c, g -> g.withSlot(slot, w));
    }

    private static WardrobeSlot slotOf(List<SimItemBuild> armour, List<SimItemBuild> equipment, String name) {
        List<SimItemBuild> a = new ArrayList<>(4);
        List<SimItemBuild> e = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            a.add(clean(armour != null && i < armour.size() ? armour.get(i) : null, i, true));
            e.add(clean(equipment != null && i < equipment.size() ? equipment.get(i) : null, i, false));
        }
        return new WardrobeSlot(name, a, e);
    }

    /**
     * {@link #setWardrobeSlot(SimStats.DungeonClass, int, List, List, String)} for one player, on the server: the
     * host's own wardrobe (for his selected class), or a guest's entry on the host. Server thread.
     */
    public static void setWardrobeSlot(ServerPlayer sp, int slot, List<SimItemBuild> armour,
                                       List<SimItemBuild> equipment, String name) {
        if (slot < 0 || slot >= SLOTS || sp == null) {
            return;
        }
        WardrobeSlot w = slotOf(armour, equipment, name);
        if (SimParty.isHost(sp)) {
            update(SimStats.profile().selected(), g -> g.withSlot(slot, w));
        } else {
            SimParty.updateGear(sp, g -> g.withSlot(slot, w));
        }
        if (activeSlot(sp) == slot) {
            equipOnServer(sp, gear(sp));
        }
    }

    /** Changes one piece of the class's active slot (the wardrobe screen's editor). */
    public static void setPiece(SimStats.DungeonClass c, int slot, boolean armour, int piece, SimItemBuild b) {
        if (slot < 0 || slot >= SLOTS) {
            return;
        }
        SimItemBuild clean = clean(b, piece, armour);
        update(c, g -> g.withSlot(slot, armour ? g.slots().get(slot).withArmour(piece, clean)
                : g.slots().get(slot).withEquipment(piece, clean)));
    }

    /**
     * The old one-set-per-class call (loadouts): puts a set template's four pieces, 10-starred, in the active slot
     * (slot 1 when none is on) and wears it. "NONE" empties the slot's armour.
     */
    public static void setArmour(SimStats.DungeonClass c, String setId) {
        Armour set = armour(setId);
        if (set == MIXED) {
            return;
        }
        List<SimItemBuild> pieces = new ArrayList<>();
        for (SimItemBuild b : set.pieces()) {
            pieces.add(b.isEmpty() ? b : b.withStars(10));
        }
        update(c, g -> {
            int slot = g.active() < 0 ? 0 : g.active();
            WardrobeSlot w = g.slots().get(slot);
            return g.withSlot(slot, new WardrobeSlot(w.name(), pieces, w.equipment())).withActive(slot);
        });
    }

    /** Puts a pet out for a class, holding the item it last held. */
    public static void setPet(SimStats.DungeonClass c, String id) {
        update(c, g -> g.withPet(new PetBuild(pet(id), g.itemFor(pet(id).id()))));
    }

    public static void setPet(SimStats.DungeonClass c, PetBuild p) {
        update(c, g -> g.withPet(p));
    }

    public static void setPetRule(SimStats.DungeonClass c, SimPetRules.Rule r) {
        update(c, g -> g.withRule(r));
    }

    /**
     * A Pet Rules swap, on the server: the host's own pet (saved, like Hypixel's), or a guest's on the host (his
     * client is told through his own cast too - {@link SimParty}). Server thread.
     */
    static void swapPetOnServer(ServerPlayer sp, PetBuild p) {
        if (SimParty.isHost(sp)) {
            update(SimStats.profile().selected(), g -> g.withPet(p));
        } else {
            SimParty.updateGear(sp, g -> g.withPet(p));
        }
    }

    // ------------------------------------------------------------------ swapping slots

    private static final Map<UUID, Long> LAST_WARDROBE_SWAP = new ConcurrentHashMap<>();

    /**
     * Equips one of a player's wardrobe slots on the server: its armour into the armour slots and its equipment on.
     * {@code -1} takes the worn slot off (clicking the equipped slot on Hypixel's wardrobe). Server thread.
     *
     * @return whether anything changed
     */
    public static boolean equipSlot(ServerPlayer sp, int slot) {
        if (sp == null || slot < -1 || slot >= SLOTS) {
            return false;
        }
        long now = SimVitals.now();
        Long last = LAST_WARDROBE_SWAP.get(sp.getUUID());
        if (last != null && now - last < WARDROBE_SWAP_TICKS) {
            return false;
        }
        LAST_WARDROBE_SWAP.put(sp.getUUID(), now);
        int target = slot;
        int was = activeSlot(sp);
        if (SimParty.isHost(sp)) {
            update(SimStats.profile().selected(), g -> g.withActive(target));
        } else {
            SimParty.updateGear(sp, g -> g.withActive(target));
        }
        ClassGear g = gear(sp);
        equipOnServer(sp, g);
        sp.sendSystemMessage(Component.literal(target < 0 ? "§eYou unequipped wardrobe slot " + (was + 1) + "."
                : "§aEquipped wardrobe slot " + (target + 1) + "§7 (" + g.activeSlot().label() + ")"));
        return true;
    }

    /** {@link #equipSlot(ServerPlayer, int)} from this client: on the server, or sent to the host as a guest. */
    public static void equipSlot(Minecraft client, int slot) {
        if (!SimState.canAct(client) || client.player == null) {
            return;
        }
        if (SimParty.guest()) {
            // A party guest: his own saved wardrobe follows, and the host's server does the swap.
            update(SimStats.profile().selected(), g -> g.withActive(slot));
            SimParty.sendAction("wardrobe", String.valueOf(slot));
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        UUID id = client.player.getUUID();
        server.execute(() -> equipSlot(server.getPlayerList().getPlayer(id), slot));
    }

    /** The swap key: the next filled slot after the one on (wrapping). */
    static void cycleSlot(Minecraft client) {
        ClassGear g = classGear(SimStats.profile().selected());
        for (int k = 1; k <= SLOTS; k++) {
            int i = (Math.max(-1, g.active()) + k + SLOTS) % SLOTS;
            if (!g.slots().get(i).isEmpty()) {
                if (i != g.active()) {
                    equipSlot(client, i);
                }
                return;
            }
        }
        ModChat.send("Sim", ModChat.text("Your wardrobe is empty - fill a slot in "), ModChat.value("/simwardrobe"));
    }

    // ------------------------------------------------------------------ wearing it

    /**
     * Puts the selected class's active slot in the armour slots, on the server's own player. A wardrobe piece
     * already there is replaced; anything else is moved to the inventory first.
     */
    public static void equip(Minecraft client) {
        if (!SimState.canAct(client) || client.player == null) {
            return;
        }
        if (SimParty.guest()) {
            // A party guest: his class and wardrobe go to the host, whose server dresses him (SimParty).
            SimParty.sendProfile();
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ClassGear g = classGear(SimStats.profile().selected());
        UUID id = client.player.getUUID();
        server.execute(() -> equipOnServer(server.getPlayerList().getPlayer(id), g));
    }

    /** Puts a gear's active slot in one player's armour slots, the same rule as {@link #equip}. Server thread. */
    static void equipOnServer(ServerPlayer sp, ClassGear g) {
        if (sp == null) {
            return;
        }
        List<SimItemBuild> armour = g.armour();
        for (int i = 0; i < 4; i++) {
            ItemStack piece = buildPiece(armour.get(i), i);
            ItemStack worn = sp.getItemBySlot(PIECE_SLOTS[i]);
            if (!worn.isEmpty() && !isSimArmour(worn)) {
                if (piece.isEmpty()) {
                    continue;
                }
                // A mask put on by hand: back to the inventory, not destroyed.
                ItemStack keep = worn.copy();
                if (!sp.getInventory().add(keep)) {
                    sp.drop(keep, false, net.minecraft.util.Prediction.SERVER_ONLY);
                }
            }
            sp.setItemSlot(PIECE_SLOTS[i], piece);
        }
    }

    /**
     * Puts the wardrobe's active slot on a player who has none of it on, at the start of a sim session and of each
     * new floor. Server thread.
     *
     * <p>The armour used to go on only when he picked a class, swapped a wardrobe slot or loaded a loadout. A fresh
     * sim world (the void-world rebuild deletes the old one) or a new player file starts with empty armour slots,
     * so he played the whole floor in his base stats alone - 1,400 health and 300 Defense with the defaults, his
     * "max gear" sitting in the wardrobe uncounted ({@link SimStats#effective} reads the real armour slots) - and on
     * the floor's numbers that is a ton of damage. Only when NO armour slot holds a wardrobe piece, so a mask he
     * swapped on by hand, or a slot he took off on purpose (the wardrobe's unequip, active slot -1), is left alone.
     *
     * @return whether anything was put on
     */
    static boolean dressIfBare(ServerPlayer sp) {
        if (sp == null) {
            return false;
        }
        for (EquipmentSlot slot : PIECE_SLOTS) {
            ItemStack worn = sp.getItemBySlot(slot);
            if (!worn.isEmpty() && isSimArmour(worn)) {
                return false;
            }
        }
        ClassGear g = gear(sp);
        if (g.active() < 0) {
            return false;
        }
        boolean any = false;
        for (SimItemBuild b : g.armour()) {
            any |= b != null && !b.isEmpty();
        }
        if (!any) {
            return false;
        }
        equipOnServer(sp, g);
        return true;
    }

    /** Whether a stack is one of the wardrobe's pieces (it carries the build mark, or an old set's id). */
    static boolean isSimArmour(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().contains(SimItemBuild.BUILD_MARK)) {
            return true;
        }
        String id = SimItemIds.skyblockId(stack);
        if (id == null) {
            return false;
        }
        for (Armour a : ARMOUR) {
            if (id.startsWith(a.idPrefix() + "_")) {
                return true;
            }
        }
        return false;
    }

    /** One armour piece as a stack: the build on it, its tooltip from the build. */
    public static ItemStack buildPiece(SimItemBuild b, int piece) {
        if (b == null || b.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack;
        Armour set = setOf(b.id());
        if (b.id().equals(SimDeath.BONZO_MASK_ID) || b.id().equals(SimDeath.SPIRIT_MASK_ID)) {
            // The sim's own masks, so the death saves (SimDeath) see them.
            stack = SimItems.build(b.id());
            if (stack.isEmpty()) {
                stack = new ItemStack(Items.PLAYER_HEAD);
            }
        } else if (set != null || piece > 0) {
            stack = new ItemStack(PIECE_ITEMS[piece]);
            int colour = set != null ? set.colours()[piece] : b.id().startsWith("SKELETON_MASTER") ? 0xE3E3E3
                    : 0x7F7F7F;
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
        } else {
            // Hypixel's other helmets are skulls (Necron heads, Goggles, Mender Crown); a plain head here.
            stack = new ItemStack(b.id().contains("NECRON_HEAD") ? Items.WITHER_SKELETON_SKULL : Items.PLAYER_HEAD);
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("id", b.dataId());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack = b.applyTo(stack);
        // A skull helmet (Necron heads, Wither Goggles, Mender Crown) wears Hypixel's own head texture.
        return SimItems.isSkull(stack) ? SimHeads.apply(stack, b.id()) : stack;
    }

    /** {@link #buildPiece(SimItemBuild, int)} for a set template's piece (plain), the wardrobe's set row. */
    public static ItemStack buildPiece(Armour set, int piece) {
        return set == NO_ARMOUR || set == MIXED ? new ItemStack(Items.BARRIER)
                : buildPiece(SimItemBuild.of(pieceId(set, piece)), piece);
    }

    /** An equipment piece as an icon (equipment has no slot to wear it in - Hypixel's is a menu too). */
    public static ItemStack equipmentIcon(SimItemBuild b) {
        if (b == null || b.isEmpty()) {
            return ItemStack.EMPTY;
        }
        SimItemData.Def d = SimItemData.def(b.id());
        Item icon = d == null ? Items.PAPER : switch (d.category()) {
            case "NECKLACE" -> b.id().equals("BALLOON_SNAKE") ? Items.LEAD : Items.BONE;
            case "CLOAK" -> Items.FEATHER;
            case "BELT" -> Items.LEATHER;
            case "GLOVES" -> Items.PHANTOM_MEMBRANE;
            default -> Items.PAPER;
        };
        ItemStack stack = new ItemStack(icon);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", b.dataId());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        // Most of Hypixel's equipment is a textured head; the stand-in icon stays for anything the repo has none for.
        return SimHeads.apply(b.applyTo(stack), b.id());
    }

    /** The pet as an icon for the pets menu, with its item. */
    public static ItemStack petIcon(PetBuild pb) {
        Pet pet = pb.pet();
        ItemStack stack = new ItemStack(pet.icon());
        List<String> lore = new ArrayList<>();
        for (Map.Entry<SimStats.Stat, Double> e : pb.stats().entrySet()) {
            if (e.getValue() > 0) {
                lore.add("§7" + e.getKey().label + ": §a+" + SimDamage.commas(e.getValue()));
            }
        }
        if (!pet.perkText().isEmpty()) {
            lore.add("");
            lore.add("§6" + pet.perkText());
        }
        if (pb.item() != PetItem.NONE) {
            lore.add("");
            lore.add("§6Held Item: §9" + pb.item().label);
            lore.add("§7" + pb.item().effect);
        }
        if (!NONE_ID.equals(pet.id())) {
            lore.add("");
            SimRealStats.PetStats real = pet.real();
            lore.add(real != null ? "§8Stats: NEU repo petnums.json (" + real.rarity() + ")."
                    : "§8Stats: built-in copy (repo not loaded).");
            lore.add("§" + pet.colour() + "§l" + pet.rarity().replace('_', ' '));
        }
        SimItemLore.applyCustom(stack, "§7[Lvl " + pet.level() + "] §" + pet.colour() + pet.name(), lore);
        // Hypixel's own pet head, not the vanilla stand-in.
        return SimHeads.withTexture(stack, SimHeads.petTexture(pet.id(), pet.rarity()));
    }

    public static ItemStack petIcon(Pet pet) {
        return petIcon(new PetBuild(pet, PetItem.NONE));
    }

    /** A short "+815❤ +430❈ +160❁" of what gear adds, for the stats screen. */
    static String summary(Map<SimStats.Stat, Double> stats) {
        StringBuilder out = new StringBuilder();
        for (SimStats.Stat s : SimStats.Stat.values()) {
            Double v = stats.get(s);
            if (v != null && v > 0) {
                out.append('+').append(SimDamage.commas(v)).append(s.symbol).append(' ');
            }
        }
        return out.length() == 0 ? "nothing" : out.toString().trim();
    }

    /** What a class's worn armour, equipment and pet add on this floor, stat by stat (the stats screen). */
    static Map<SimStats.Stat, Double> gearTotals(SimStats.DungeonClass c, String floor) {
        ClassGear g = classGear(c);
        SimStats.Profile p = SimStats.profile();
        SimItemBuild.Context ctx = new SimItemBuild.Context(floor, p.catacombs(), c, g.pet().id());
        Map<SimStats.Stat, Double> out = new EnumMap<>(SimStats.Stat.class);
        for (SimItemBuild b : g.armour()) {
            SimItemBuild.addAll(out, b.stats(ctx).stats());
        }
        for (SimItemBuild b : g.equipment()) {
            SimItemBuild.addAll(out, b.stats(ctx).stats());
        }
        SimItemBuild.addAll(out, g.pet().stats());
        return out;
    }

    static Map<SimStats.Stat, Double> gearTotals(SimStats.DungeonClass c) {
        return gearTotals(c, "M7");
    }

    // ------------------------------------------------------------------ item abilities

    /** When each player last teleported (server tick), for the Shadow Assassin Cloak. */
    private static final Map<UUID, Long> TELEPORTED = new ConcurrentHashMap<>();
    private static final int CLOAK_WINDOW_TICKS = 5 * 20;

    /** Call when a player teleports (Instant Transmission, etherwarp, Wither Impact...). Server thread. */
    public static void markTeleport(ServerPlayer sp) {
        if (sp != null) {
            TELEPORTED.put(sp.getUUID(), SimVitals.now());
        }
    }

    /**
     * The Shadow Assassin Cloak's "On teleport: your next melee hit within 5s deals 10% more damage": 0.10 when it
     * applies to this melee hit (spent when {@code consume}), else 0. A multiplicative x1.1 for the damage code.
     */
    public static double cloakMeleeBonus(ServerPlayer sp, boolean consume) {
        if (sp == null || !wears(gear(sp).equipment(), "SHADOW_ASSASSIN_CLOAK")) {
            return 0;
        }
        Long at = TELEPORTED.get(sp.getUUID());
        if (at == null || SimVitals.now() - at > CLOAK_WINDOW_TICKS) {
            return 0;
        }
        if (consume) {
            TELEPORTED.remove(sp.getUUID());
        }
        return 0.10;
    }

    /** Soulweaver Gloves' Haunted Skulls per player (max 3). */
    private static final Map<UUID, int[]> SKULLS = new ConcurrentHashMap<>();
    private static final int SKULL_TICKS = 15 * 20;
    public static final int MAX_SKULLS = 3;

    private static void tickSoulweaver(net.minecraft.server.MinecraftServer server) {
        if (SimVitals.now() % SKULL_TICKS != 0) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (wears(gear(sp).equipment(), "SOULWEAVER_GLOVES")) {
                addSkull(sp);
            }
        }
    }

    private static void addSkull(ServerPlayer sp) {
        int[] n = SKULLS.computeIfAbsent(sp.getUUID(), k -> new int[1]);
        n[0] = Math.min(MAX_SKULLS, n[0] + 1);
    }

    public static int skulls(ServerPlayer sp) {
        int[] n = SKULLS.get(sp.getUUID());
        return n == null ? 0 : n[0];
    }

    /**
     * APPROXIMATE Soulweaver Gloves: a skull is spent on the next mob that hits you, which then "deals 5% less
     * damage" - so the hit is x0.95. Hypixel's skulls orbit and hit mobs on their own (stunning them 2 s); the sim
     * has no orbit, so the effect is folded into the hit taken. Server thread.
     */
    static double soulweaverIncoming(ServerPlayer sp, double taken) {
        int[] n = SKULLS.get(sp.getUUID());
        if (n == null || n[0] <= 0 || !wears(gear(sp).equipment(), "SOULWEAVER_GLOVES")) {
            return taken;
        }
        n[0]--;
        return taken * 0.95;
    }

    /** Black Belt: "Reduces damage taken by 6%". */
    static double beltIncoming(ServerPlayer sp, double taken) {
        return wears(gear(sp).equipment(), "DOJO_BLACK_BELT") ? taken * 0.94 : taken;
    }

    /** Spider's Web Battlefield stacks per player: expiry ticks (each +6 Strength, max 10, 40 s). */
    private static final Map<UUID, ArrayDeque<Long>> WEB = new ConcurrentHashMap<>();
    private static final int WEB_TICKS = 40 * 20;
    private static final int WEB_MAX = 10;

    private static void onKill(ServerPlayer killer, LivingEntity dead, net.minecraft.world.damagesource.DamageSource src) {
        // Soulweaver: a skull on a melee kill.
        if (src.getDirectEntity() == killer && wears(gear(killer).equipment(), "SOULWEAVER_GLOVES")) {
            addSkull(killer);
        }
        // Mythic Spider: every player within 20 blocks of the kill gets a stack.
        PetBuild pet = gear(killer).pet();
        if ("SPIDER".equals(pet.id()) && "MYTHIC".equals(pet.pet().rarity()) && killer.level().getServer() != null) {
            long until = SimVitals.now() + WEB_TICKS;
            for (ServerPlayer p : killer.level().getServer().getPlayerList().getPlayers()) {
                if (p.level() == dead.level() && p.distanceToSqr(dead) <= 20 * 20) {
                    ArrayDeque<Long> q = WEB.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
                    synchronized (q) {
                        q.addLast(until);
                        while (q.size() > WEB_MAX) {
                            q.removeFirst();
                        }
                    }
                }
            }
        }
    }

    /** Web Battlefield's Strength on this player now: +6 per live stack (the stack's level-100 value). */
    static double webStrength(UUID who) {
        ArrayDeque<Long> q = who == null ? null : WEB.get(who);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            long now = SimVitals.now();
            while (!q.isEmpty() && q.peekFirst() < now) {
                q.removeFirst();
            }
            return 6.0 * q.size();
        }
    }

    /**
     * Bone Necklace's Gladiator's Will: +3 Defense per enemy within 10 blocks, up to +30 (as Tank in a dungeon:
     * 30 blocks, up to +60). Server thread.
     */
    static double gladiatorDefense(ServerPlayer sp, List<SimItemBuild> equipment, SimStats.DungeonClass c) {
        if (sp == null || !wears(equipment, "BONE_NECKLACE")) {
            return 0;
        }
        boolean tank = c == SimStats.DungeonClass.TANK;
        double r = tank ? 30 : 10;
        int n = sp.level().getEntitiesOfClass(LivingEntity.class, sp.getBoundingBox().inflate(r),
                e -> e.isAlive() && !(e instanceof Player) && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand)
                        && e.distanceToSqr(sp) <= r * r).size();
        return Math.min(tank ? 60 : 30, 3.0 * n);
    }

    /** For tests: forgets every running hook. */
    static void clearHooks() {
        TELEPORTED.clear();
        SKULLS.clear();
        WEB.clear();
        LAST_WARDROBE_SWAP.clear();
    }

    // ------------------------------------------------------------------ defaults

    /** A fresh install's slot 1 per class: its usual set and the dungeon equipment, maxed, and its pet with its item. */
    static ClassGear defaultGear(SimStats.DungeonClass c) {
        List<SimItemBuild> armour = new ArrayList<>(armour(defaultArmour(c)).pieces());
        List<SimItemBuild> equipment = List.of(SimItemData.fullBuild("BONE_NECKLACE"),
                SimItemData.fullBuild("SHADOW_ASSASSIN_CLOAK"), SimItemData.fullBuild("ADAPTIVE_BELT"),
                SimItemData.fullBuild("SOULWEAVER_GLOVES"));
        Pet p = pet(defaultPet(c));
        return ClassGear.EMPTY.withSlot(0, new WardrobeSlot("", armour, equipment)).withActive(0)
                .withPet(new PetBuild(p, defaultPetItem(p.id())));
    }

    /**
     * The pet item a pet holds until one is picked: the user's presets' choices (Golden Dragon Antique Remedies,
     * Ender Dragon Gold Claws, Black Cat Unalloyed Speed, Blue Whale Crochet Tiger Plushie), else the Hephaestus
     * Relic (all pet stats +50%).
     */
    static PetItem defaultPetItem(String petId) {
        if (petId == null || NONE_ID.equals(petId)) {
            return PetItem.NONE;
        }
        return switch (petId) {
            case "GOLDEN_DRAGON" -> PetItem.ANTIQUE_REMEDIES;
            case "ENDER_DRAGON" -> PetItem.GOLD_CLAWS;
            case "BLACK_CAT" -> PetItem.UNALLOYED_SPEED;
            case "BLUE_WHALE" -> PetItem.CROCHET_TIGER_PLUSHIE;
            default -> PetItem.HEPHAESTUS_RELIC;
        };
    }

    // ------------------------------------------------------------------ file

    private static void load() {
        if (loaded) {
            return;
        }
        synchronized (GEAR) {
            if (loaded) {
                return;
            }
            // A fresh install gets each class's usual gear; a stats profile from before the wardrobe already
            // counted its gear, so it starts with none (see SimStats.Stat) - but only a profile whose saved base
            // really looks like it includes gear (SimStats.storedBaseIncludesGear), not any stats file at all. A
            // player who is not staff plays the defaults and gets the default gear either way (playable).
            boolean fresh = !Files.exists(FILE)
                    && !(SimStats.savedBeforeThisLaunch() && SimStats.storedBaseIncludesGear());
            for (SimStats.DungeonClass c : SimStats.DungeonClass.values()) {
                GEAR.put(c, fresh ? defaultGear(c) : ClassGear.EMPTY);
            }
            if (Files.exists(FILE)) {
                try {
                    JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    for (SimStats.DungeonClass c : SimStats.DungeonClass.values()) {
                        String key = c.name().toLowerCase(Locale.ROOT);
                        if (!(o.get(key) instanceof JsonObject co)) {
                            continue;
                        }
                        if (co.has("slots")) {
                            GEAR.put(c, ClassGear.fromJson(co));
                        } else {
                            // The one-set-per-class file: that set in slot 1, starred as the old approximation was.
                            ClassGear g = ClassGear.EMPTY;
                            Armour set = co.has("armour") ? armour(co.get("armour").getAsString()) : NO_ARMOUR;
                            if (set != NO_ARMOUR && set != MIXED) {
                                List<SimItemBuild> pieces = new ArrayList<>();
                                for (SimItemBuild b : set.pieces()) {
                                    pieces.add(b.withStars(10));
                                }
                                g = g.withSlot(0, new WardrobeSlot("", pieces, EMPTY_FOUR)).withActive(0);
                            }
                            if (co.has("pet")) {
                                g = g.withPet(new PetBuild(pet(co.get("pet").getAsString()), PetItem.NONE));
                            }
                            GEAR.put(c, g);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not read the sim gear", e);
                }
            }
            loaded = true;
        }
    }

    private static void persist() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject o = new JsonObject();
            synchronized (GEAR) {
                for (SimStats.DungeonClass c : SimStats.DungeonClass.values()) {
                    o.add(c.name().toLowerCase(Locale.ROOT), GEAR.getOrDefault(c, ClassGear.EMPTY).toJson());
                }
            }
            Files.writeString(FILE, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not save the sim gear", e);
        }
    }

    /** Says what a class now wears, after a change. */
    static void announce(SimStats.DungeonClass c) {
        ClassGear g = classGear(c);
        ModChat.send("Sim", ModChat.text(c.label + ": "), ModChat.value(g.activeSlot().label()),
                ModChat.text(" + "), ModChat.value(g.pet().label()));
    }
}
