package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.roomsim.SimItemBuild.Gem;
import com.epic60869.sky2m.sim.roomsim.SimItemBuild.GemQuality;
import com.epic60869.sky2m.sim.roomsim.SimItemBuild.GemType;
import com.epic60869.sky2m.sim.roomsim.SimItemBuild.Rarity;
import com.epic60869.sky2m.sim.roomsim.SimStats.Stat;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The numbers behind {@link SimItemBuild}: each item's base stats, rarity, type and gemstone slots, the reforge,
 * gemstone, potato book and stat-enchantment tables, and the dungeon stat boost - and how they add up.
 *
 * <h2>Where the numbers come from</h2>
 * Hypixel's items resource first ({@link SimRealStats#item}: base stats, {@code tier}, {@code category},
 * {@code gemstone_slots}, {@code tiered_stats}); the NEU item repo's lore when the API has not loaded; and the
 * {@link #HAND} table below when neither has (an offline first launch) - a copy of the same API entries taken on
 * 2026-11-01 ({@code research/raw/items.json}), so offline numbers are Hypixel's too. Reforges are NEU's
 * {@code constants/reforgestones.json} / {@code reforges.json}; gemstones NEU's {@code gemstones} table as the
 * wiki's Gemstone pages list it; enchantments hypixelskyblock.minecraft.wiki (see {@code research/enchants.md}).
 *
 * <h2>How an item's stats add up ({@link #contribution})</h2>
 * <ol>
 * <li><b>Base</b>: the item's own stats - the {@code STARRED_} entry when fragged; a randomized drop (Skeleton
 *     Master) reads its floor tier's row of {@code tiered_stats} times (1 + quality%/100).</li>
 * <li><b>Reforge</b> at the item's rarity after a Recombobulator (and the quality roll's upgrade at 50%).</li>
 * <li><b>Gemstones</b>: each slot's gem, at the item's rarity, if it fits the slot (Combat: Ruby, Amethyst,
 *     Sapphire, Jasper, Onyx; Defensive: Ruby, Amethyst; a coloured slot only its own gem).</li>
 * <li><b>Potato books</b>: +4 Health +2 Defense per book on armour, +2 Strength +2 Damage on weapons (Hot and
 *     Fuming alike); doubled with a Blaze pet out (Fusion-Style Potato).</li>
 * <li><b>Stat enchantments</b> ({@link #statEnchant}): Growth, Protection, Big Brain, Smarty Pants, Art of Peace
 *     (stored as enchantment {@code art_of_peace}), Prosperity, Critical, Vicious, Toxophilite, Overload, The One,
 *     Cayenne. The damage enchants are stored only, for {@code SimEnchants}.</li>
 * <li><b>Diamond / Golden Necron Head</b>: the helmet's stats doubled on Floor VII and Master Floor VII.</li>
 * <li><b>The dungeon boost</b> ({@link #itemMultiplier}), for a dungeon item inside a dungeon:
 *     {@code x (1 + Catacombs bonus + 10% per star + 5% per Master Star in Master Mode + 5% General's Medallion)},
 *     added together, never on Crit Chance, Attack Speed or Ability Damage. The Catacombs bonus is Hypixel's table:
 *     15/20/25/31/38/46/55/65% for levels 1-8, then +10% a level (485% at 50). Outside a dungeon a starred dungeon
 *     item gets +2% a star. A Crimson Isle item (Ragnarock, Implosion Belt) gets its Crimson Essence stars instead,
 *     +2% a star everywhere (the Essence page's rule for non-dungeon stars; 10 stars = +20%), and no dungeon boost.
 *     <br><b>What the boost covers</b> - left open by the research: the wiki says "base stats", but the Catacombs
 *     page lists the boost by STAT (Health, Defense, Strength ...) not by source, and in game the in-dungeon number
 *     on a stat line is the whole line - base, reforge, books, gems and enchant stats together. So here the boost
 *     multiplies everything the item carries (steps 1-6). This is the reading that keeps one rule for every stat
 *     line; it is the sim's choice, not a documented Hypixel formula.</li>
 * <li><b>Adaptive Belt</b>'s class bonus (Berserk +10 Strength, Healer +10 Health, Mage +25 Intelligence, Tank
 *     +5 Health +10 Defense, Archer +2 Crit Chance +5 Crit Damage), added after the boost: it is an ability, not
 *     a stat line.</li>
 * </ol>
 */
public final class SimItemData {

    private SimItemData() {
    }

    // ------------------------------------------------------------------ item definitions

    /** One item as the data knows it. {@code real} is false for the offline hand table. */
    public record Def(String id, String name, Rarity tier, String category, boolean dungeon, Map<Stat, Double> stats,
                      double damage, List<String> gemSlots, Map<Stat, double[]> tiered, double[] tieredDamage,
                      boolean real) {
        public double get(Stat s) {
            Double v = stats.get(s);
            return v == null ? 0 : v;
        }
    }

    private static final Map<String, Def> HAND = new LinkedHashMap<>();

    /** One hand row: stats as "H=180 S=40 CD=30", "T" prefix for a tiered row; gem slots space-separated. */
    private static Def d(String id, String name, String tier, String category, boolean dungeon, String stats,
                         String slots) {
        Map<Stat, Double> m = new EnumMap<>(Stat.class);
        Map<Stat, double[]> tiered = new EnumMap<>(Stat.class);
        double damage = 0;
        double[] tieredDamage = null;
        for (String part : stats.trim().split("\\s+")) {
            if (part.isEmpty()) {
                continue;
            }
            String[] kv = part.split("=");
            boolean t = kv[0].startsWith("T");
            String key = t ? kv[0].substring(1) : kv[0];
            if (t) {
                String[] nums = kv[1].split(",");
                double[] arr = new double[nums.length];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = Double.parseDouble(nums[i]);
                }
                if (key.equals("DMG")) {
                    tieredDamage = arr;
                } else {
                    tiered.put(shortStat(key), arr);
                }
            } else if (key.equals("DMG")) {
                damage = Double.parseDouble(kv[1]);
            } else {
                m.put(shortStat(key), Double.parseDouble(kv[1]));
            }
        }
        Def def = new Def(id, name, Rarity.parse(tier), category, dungeon, Map.copyOf(m), damage,
                slots.isBlank() ? List.of() : List.of(slots.trim().split("\\s+")), Map.copyOf(tiered), tieredDamage,
                false);
        HAND.put(id, def);
        return def;
    }

    private static Stat shortStat(String k) {
        return switch (k) {
            case "H" -> Stat.HEALTH;
            case "D" -> Stat.DEFENSE;
            case "S" -> Stat.STRENGTH;
            case "CC" -> Stat.CRIT_CHANCE;
            case "CD" -> Stat.CRIT_DAMAGE;
            case "I" -> Stat.INTELLIGENCE;
            case "F" -> Stat.FEROCITY;
            case "AD" -> Stat.ABILITY_DAMAGE;
            case "AS" -> Stat.ATTACK_SPEED;
            default -> throw new IllegalArgumentException(k);
        };
    }

    /** Hypixel's items resource, 2026-11-01 (lastUpdated 1793311327754): the offline fallback. */
    private static final List<Def> HAND_ROWS = List.of(
            d("POWER_WITHER_HELMET", "Necron's Helmet", "LEGENDARY", "HELMET", true, "D=100 H=180 S=40 CD=30 I=30", "JASPER COMBAT"),
            d("POWER_WITHER_CHESTPLATE", "Necron's Chestplate", "LEGENDARY", "CHESTPLATE", true, "D=140 H=260 S=40 CD=30 I=10", "JASPER COMBAT"),
            d("POWER_WITHER_LEGGINGS", "Necron's Leggings", "LEGENDARY", "LEGGINGS", true, "D=125 H=230 S=40 CD=30 I=10", "JASPER COMBAT"),
            d("POWER_WITHER_BOOTS", "Necron's Boots", "LEGENDARY", "BOOTS", true, "D=85 H=145 S=40 CD=30 I=10", "JASPER COMBAT"),
            d("SPEED_WITHER_HELMET", "Maxor's Helmet", "LEGENDARY", "HELMET", true, "D=80 H=180 CD=45 I=15", "COMBAT COMBAT"),
            d("SPEED_WITHER_CHESTPLATE", "Maxor's Chestplate", "LEGENDARY", "CHESTPLATE", true, "D=120 H=260 CD=45 I=10", "COMBAT COMBAT"),
            d("SPEED_WITHER_LEGGINGS", "Maxor's Leggings", "LEGENDARY", "LEGGINGS", true, "D=105 H=230 CD=45 I=10", "COMBAT COMBAT"),
            d("SPEED_WITHER_BOOTS", "Maxor's Boots", "LEGENDARY", "BOOTS", true, "D=65 H=145 CD=45 I=10", "COMBAT COMBAT"),
            d("WISE_WITHER_HELMET", "Storm's Helmet", "LEGENDARY", "HELMET", true, "D=80 H=180 I=400", "SAPPHIRE COMBAT"),
            d("WISE_WITHER_CHESTPLATE", "Storm's Chestplate", "LEGENDARY", "CHESTPLATE", true, "D=120 H=260 I=250", "SAPPHIRE COMBAT"),
            d("WISE_WITHER_LEGGINGS", "Storm's Leggings", "LEGENDARY", "LEGGINGS", true, "D=105 H=230 I=250", "SAPPHIRE COMBAT"),
            d("WISE_WITHER_BOOTS", "Storm's Boots", "LEGENDARY", "BOOTS", true, "D=65 H=145 I=250", "SAPPHIRE COMBAT"),
            d("TANK_WITHER_HELMET", "Goldor's Helmet", "LEGENDARY", "HELMET", true, "D=180 H=210 I=30", "DEFENSIVE COMBAT"),
            d("TANK_WITHER_CHESTPLATE", "Goldor's Chestplate", "LEGENDARY", "CHESTPLATE", true, "D=275 H=310 I=20", "DEFENSIVE COMBAT"),
            d("TANK_WITHER_LEGGINGS", "Goldor's Leggings", "LEGENDARY", "LEGGINGS", true, "D=240 H=260 I=20", "DEFENSIVE COMBAT"),
            d("TANK_WITHER_BOOTS", "Goldor's Boots", "LEGENDARY", "BOOTS", true, "D=165 H=190 I=20", "DEFENSIVE COMBAT"),
            d("GOLD_NECRON_HEAD", "Golden Necron Head", "SPECIAL", "HELMET", true, "D=40 H=80 S=15", ""),
            d("DIAMOND_NECRON_HEAD", "Diamond Necron Head", "SPECIAL", "HELMET", true, "D=80 H=220 S=40", ""),
            d("SKELETON_MASTER_CHESTPLATE", "Skeleton Master Chestplate", "EPIC", "CHESTPLATE", true, "TD=42,45,49,54,58,63,69,74,81,88 TH=26,29,31,34,37,40,44,47,51,56 TCD=22,23,25,27,30,32,35,38,41,45 TCC=2,2,2,2,3,3,3,3,4,4", ""),
            d("WITHER_GOGGLES", "Wither Goggles", "EPIC", "HELMET", true, "AD=45 I=300", ""),
            d("SPIRIT_MASK", "Spirit Mask", "EPIC", "HELMET", true, "D=50 H=100 I=20", ""),
            d("STARRED_SPIRIT_MASK", "Spirit Mask", "LEGENDARY", "HELMET", true, "D=75 H=100 I=25", ""),
            d("BONZO_MASK", "Bonzo's Mask", "RARE", "HELMET", true, "D=100 H=100 I=100", ""),
            d("STARRED_BONZO_MASK", "Bonzo's Mask", "RARE", "HELMET", true, "D=100 H=125 I=150", ""),
            d("MENDER_CROWN", "Mender Crown", "EPIC", "HELMET", true, "H=270", ""),
            d("SPRING_BOOTS", "Spring Boots", "RARE", "BOOTS", false, "", ""),
            d("SUPERIOR_DRAGON_HELMET", "Superior Dragon Helmet", "LEGENDARY", "HELMET", true, "D=130 H=90 S=10 CD=10 CC=2 I=25", "COMBAT"),
            d("SUPERIOR_DRAGON_CHESTPLATE", "Superior Dragon Chestplate", "LEGENDARY", "CHESTPLATE", true, "D=190 H=150 S=10 CD=10 CC=2 I=25", "COMBAT"),
            d("SUPERIOR_DRAGON_LEGGINGS", "Superior Dragon Leggings", "LEGENDARY", "LEGGINGS", true, "D=170 H=130 S=10 CD=10 CC=2 I=25", "COMBAT"),
            d("SUPERIOR_DRAGON_BOOTS", "Superior Dragon Boots", "LEGENDARY", "BOOTS", true, "D=110 H=80 S=10 CD=10 CC=2 I=25", "COMBAT"),
            d("WISE_DRAGON_HELMET", "Wise Dragon Helmet", "LEGENDARY", "HELMET", true, "D=110 H=70 I=125", "SAPPHIRE"),
            d("WISE_DRAGON_CHESTPLATE", "Wise Dragon Chestplate", "LEGENDARY", "CHESTPLATE", true, "D=160 H=120 I=75", "SAPPHIRE"),
            d("WISE_DRAGON_LEGGINGS", "Wise Dragon Leggings", "LEGENDARY", "LEGGINGS", true, "D=140 H=100 I=75", "SAPPHIRE"),
            d("WISE_DRAGON_BOOTS", "Wise Dragon Boots", "LEGENDARY", "BOOTS", true, "D=90 H=60 I=75", "SAPPHIRE"),
            d("SHADOW_ASSASSIN_HELMET", "Shadow Assassin Helmet", "EPIC", "HELMET", true, "D=70 H=160 S=25 CD=25", "JASPER"),
            d("SHADOW_ASSASSIN_CHESTPLATE", "Shadow Assassin Chestplate", "EPIC", "CHESTPLATE", true, "D=110 H=240 S=25 CD=25", "JASPER"),
            d("SHADOW_ASSASSIN_LEGGINGS", "Shadow Assassin Leggings", "EPIC", "LEGGINGS", true, "D=95 H=210 S=25 CD=25", "JASPER"),
            d("SHADOW_ASSASSIN_BOOTS", "Shadow Assassin Boots", "EPIC", "BOOTS", true, "D=55 H=125 S=25 CD=25", "JASPER"),
            d("ADAPTIVE_HELMET", "Adaptive Helmet", "EPIC", "HELMET", true, "D=50 H=110 S=15 I=15", "DEFENSIVE"),
            d("ADAPTIVE_CHESTPLATE", "Adaptive Chestplate", "EPIC", "CHESTPLATE", true, "D=85 H=170 S=15 I=15", "DEFENSIVE"),
            d("ADAPTIVE_LEGGINGS", "Adaptive Leggings", "EPIC", "LEGGINGS", true, "D=65 H=145 S=15 I=15", "DEFENSIVE"),
            d("ADAPTIVE_BOOTS", "Adaptive Boots", "EPIC", "BOOTS", true, "D=45 H=90 S=15 I=15", "DEFENSIVE"),
            d("BONE_NECKLACE", "Bone Necklace", "EPIC", "NECKLACE", true, "D=35 CC=3", ""),
            d("STARRED_BONE_NECKLACE", "Bone Necklace", "EPIC", "NECKLACE", true, "D=45 CC=5", ""),
            d("BALLOON_SNAKE", "Balloon Snake", "RARE", "NECKLACE", true, "H=10 I=10", ""),
            d("SHADOW_ASSASSIN_CLOAK", "Shadow Assassin Cloak", "EPIC", "CLOAK", true, "S=20", ""),
            d("STARRED_SHADOW_ASSASSIN_CLOAK", "Shadow Assassin Cloak", "LEGENDARY", "CLOAK", true, "S=25", ""),
            d("VANQUISHED_GHAST_CLOAK", "Vanquished Ghast Cloak", "EPIC", "CLOAK", false, "H=10 D=10", ""),
            d("ADAPTIVE_BELT", "Adaptive Belt", "EPIC", "BELT", true, "S=5 H=10 D=5", ""),
            d("STARRED_ADAPTIVE_BELT", "Adaptive Belt", "EPIC", "BELT", true, "S=10 H=15 D=10", ""),
            d("IMPLOSION_BELT", "Implosion Belt", "EPIC", "BELT", false, "D=70", ""),
            d("DOJO_BLACK_BELT", "Black Belt", "LEGENDARY", "BELT", false, "H=35 D=25", ""),
            d("SOULWEAVER_GLOVES", "Soulweaver Gloves", "EPIC", "GLOVES", true, "S=10 CD=10", ""),
            d("TERMINATOR", "Terminator", "LEGENDARY", "BOW", true, "DMG=310 S=50 AS=40 CD=250", ""),
            d("LAST_BREATH", "Last Breath", "LEGENDARY", "BOW", true, "DMG=200 S=180 CD=50", ""),
            d("STARRED_LAST_BREATH", "Last Breath", "LEGENDARY", "BOW", true, "DMG=210 S=190 CD=50", ""),
            d("DEATH_BOW", "Death Bow", "EPIC", "BOW", true, "DMG=300", ""),
            d("AXE_OF_THE_SHREDDED", "Halberd of the Shredded", "LEGENDARY", "SWORD", false, "DMG=140 S=115", "JASPER COMBAT"),
            d("DARK_CLAYMORE", "Dark Claymore", "LEGENDARY", "LONGSWORD", true, "DMG=500 S=100 CD=100", "COMBAT COMBAT"),
            d("HYPERION", "Hyperion", "LEGENDARY", "SWORD", true, "DMG=260 S=150 F=30 I=350", "SAPPHIRE COMBAT"),
            d("VALKYRIE", "Valkyrie", "LEGENDARY", "SWORD", true, "DMG=270 S=145 F=60 I=60", "JASPER COMBAT"),
            d("SCYLLA", "Scylla", "LEGENDARY", "SWORD", true, "DMG=270 S=150 CD=35 CC=12 F=30 I=50", "COMBAT COMBAT"),
            d("ASTRAEA", "Astraea", "LEGENDARY", "SWORD", true, "D=250 DMG=270 S=150 F=30 I=50", "DEFENSIVE COMBAT"),
            d("RAGNAROCK_AXE", "Ragnarock", "RARE", "SWORD", false, "DMG=200 S=100", "COMBAT"),
            d("SOUL_WHIP", "Soul Whip", "EPIC", "SWORD", true, "DMG=145 S=175", "COMBAT"),
            d("FLAMING_FLAY", "Flaming Flay", "LEGENDARY", "SWORD", true, "DMG=200 S=250 F=25", "COMBAT COMBAT"),
            d("SWORD_OF_BAD_HEALTH", "Sword of Bad Health", "UNCOMMON", "SWORD", false, "DMG=200 S=50 CD=50", ""),
            d("ROGUE_SWORD", "Rogue Sword", "COMMON", "SWORD", true, "DMG=20", ""),
            d("WEIRDER_TUBA", "Weirder Tuba", "EPIC", "NONE", false, "", ""),
            d("BONZO_STAFF", "Bonzo's Staff", "RARE", "SWORD", true, "DMG=160 I=250", "SAPPHIRE"),
            d("STARRED_BONZO_STAFF", "Bonzo's Staff", "RARE", "SWORD", true, "DMG=160 I=300", "SAPPHIRE SAPPHIRE"),
            d("ICE_SPRAY_WAND", "Ice Spray Wand", "RARE", "SWORD", true, "TDMG=52,56,60,65,70,75,80,86,93,100 TI=118,128,139,152,165,179,195,212,230,250", "SAPPHIRE"),
            d("STARRED_ICE_SPRAY_WAND", "Ice Spray Wand", "RARE", "SWORD", true, "TDMG=60,64,69,74,80,86,92,99,107,115 TI=135,147,160,175,190,206,224,244,265,290", "SAPPHIRE"),
            d("GYROKINETIC_WAND", "Gyrokinetic Wand", "EPIC", "WAND", false, "", ""),
            d("GLOOMLOCK_GRIMOIRE", "Gloomlock Grimoire", "EPIC", "NONE", false, "", ""),
            d("SOUL_ESOWARD", "Soul Esoward", "RARE", "NONE", false, "", ""),
            d("WITHER_CLOAK", "Wither Cloak Sword", "EPIC", "SWORD", true, "D=250 DMG=190 S=135", ""),
            d("ASPECT_OF_THE_VOID", "Aspect of the Void", "EPIC", "SWORD", false, "DMG=120 S=100", "SAPPHIRE"),
            d("ASPECT_OF_THE_END", "Aspect of the End", "RARE", "SWORD", false, "DMG=100 S=100", "SAPPHIRE"),
            d("BAT_WAND", "Spirit Sceptre", "LEGENDARY", "SWORD", true, "DMG=180 I=300", "SAPPHIRE"),
            d("STARRED_BAT_WAND", "Spirit Sceptre", "LEGENDARY", "SWORD", true, "DMG=190 I=330", "SAPPHIRE"),
            d("JERRY_STAFF", "Jerry-chine Gun", "EPIC", "SWORD", false, "DMG=80 I=200", ""));

    /**
     * An item's definition: the API's numbers when loaded (its tier, type and slots too), else NEU's lore stats
     * with the hand table's tier and slots, else the hand table (logged once). Null for an id nothing knows.
     */
    public static Def def(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String key = id.toUpperCase(Locale.ROOT);
        Def hand = HAND.get(key);
        SimRealStats.ItemStats real = SimRealStats.item(key);
        if (real != null) {
            Rarity tier = Rarity.parse(real.tier());
            if (tier == null) {
                tier = hand != null ? hand.tier() : Rarity.COMMON;
            }
            String category = real.category() != null ? real.category() : hand != null ? hand.category() : "NONE";
            List<String> slots = real.category() != null ? real.gemSlots() : hand != null ? hand.gemSlots()
                    : List.of();
            Map<Stat, double[]> tiered = real.tiered() != null && !real.tiered().isEmpty() ? real.tiered()
                    : hand != null ? hand.tiered() : Map.of();
            double[] tieredDamage = real.tieredDamage() != null ? real.tieredDamage()
                    : hand != null ? hand.tieredDamage() : null;
            String name = real.name() != null ? real.name() : hand != null ? hand.name() : prettyId(key);
            return new Def(key, name, tier, category, real.dungeon(), real.stats(), real.damage(), slots, tiered,
                    tieredDamage, true);
        }
        if (hand != null) {
            SimRealStats.fallback("item " + key);
        }
        return hand;
    }

    private static String prettyId(String id) {
        StringBuilder out = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("_")) {
            if (!w.isEmpty()) {
                out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1));
            }
        }
        return out.toString();
    }

    /** Whether a fragged ({@code STARRED_}) version of this item exists. */
    public static boolean hasFragged(String id) {
        if (id == null || id.isEmpty() || id.startsWith("STARRED_")) {
            return false;
        }
        String s = "STARRED_" + id.toUpperCase(Locale.ROOT);
        return HAND.containsKey(s) || SimRealStats.item(s) != null;
    }

    private static final Set<String> CRIMSON = Set.of("RAGNAROCK_AXE", "IMPLOSION_BELT");
    private static final List<String> CRIMSON_PREFIXES = List.of("CRIMSON_", "AURORA_", "TERROR_", "FERVOR_",
            "HOLLOW_", "HOT_", "BURNING_", "FIERY_", "INFERNAL_");

    /**
     * Whether the item's stars are Crimson Isle (Crimson Essence) stars, not dungeon stars: the Ragnarock (the
     * user's spec), the Implosion Belt (its API entry lists 10 essence upgrades) and the Kuudra armour sets.
     */
    public static boolean crimson(String id) {
        if (id == null) {
            return false;
        }
        String k = id.toUpperCase(Locale.ROOT);
        if (CRIMSON.contains(k)) {
            return true;
        }
        if (!(k.endsWith("_HELMET") || k.endsWith("_CHESTPLATE") || k.endsWith("_LEGGINGS") || k.endsWith("_BOOTS"))) {
            return false;
        }
        for (String p : CRIMSON_PREFIXES) {
            if (k.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ full builds

    /** The Mage armour that takes the Mage's enchants and Onyx in its Combat slots. */
    private static boolean mageArmour(String id) {
        return id.startsWith("WISE_") || id.equals("WITHER_GOGGLES");
    }

    private static boolean tankArmour(String id) {
        return id.startsWith("TANK_");
    }

    /** A Mage weapon (Hyperion, Spirit Sceptre, Bonzo's Staff...): Sapphire and Onyx in its slots. */
    private static boolean mageWeapon(Def d) {
        return d.get(Stat.INTELLIGENCE) >= 200;
    }

    /**
     * An item as a maxed Hypixel player has it, so nothing has to be put on by hand: 10 stars (5 + 5 Master Stars) on
     * a dungeon item or 10 Crimson stars, Recombobulated, 10 Hot + 5 Fuming Potato Books, fragged when a fragged
     * version exists, perfect gemstones in every slot, and the reforge and enchantments of the user's own maxed
     * presets (Hyperion, 10-star Terminator and Necron sets): Ancient armour (Giant on Goldor's), Withered swords,
     * Spiritual bows, Blended/Waxed/Bloodshot equipment. A plain build for anything the sim has no data for.
     */
    public static SimItemBuild fullBuild(String id) {
        Def d = def(id);
        if (d == null || id == null) {
            return SimItemBuild.of(id == null ? "" : id);
        }
        String k = id.toUpperCase(Locale.ROOT);
        String cat = d.category();
        boolean armour = armourSlot(cat) >= 0;
        boolean equipment = equipmentSlot(cat) >= 0;
        boolean sword = "SWORD".equals(cat) || "LONGSWORD".equals(cat);
        boolean bow = "BOW".equals(cat);
        boolean mage = armour ? mageArmour(k) : mageWeapon(d);
        boolean tank = armour && tankArmour(k);

        String reforge = null;
        if (armour) {
            reforge = tank ? "giant" : "ancient";
        } else if (equipment) {
            reforge = switch (cat) {
                case "CLOAK" -> "waxed";
                case "BELT" -> "bloodshot";
                default -> "blended";
            };
        } else if (bow) {
            reforge = "spiritual";
        } else if (sword) {
            reforge = "withered";
        }
        if (reforge != null && !reforgeFits(reforge, cat)) {
            reforge = null;
        }

        List<SimItemBuild.Gem> gems = new ArrayList<>();
        for (String slot : d.gemSlots()) {
            gems.add(new SimItemBuild.Gem(gemFor(slot, mage, tank), SimItemBuild.GemQuality.PERFECT));
        }

        boolean books = armourBooks(cat) || weaponBooks(cat);
        Map<String, Integer> ench = new LinkedHashMap<>();
        if (armour) {
            if (tank) {
                ench.put("ultimate_last_stand", 5);
            } else {
                ench.put("ultimate_legion", 5);
            }
            ench.put("growth", 6);
            ench.put("protection", 6);
            if (mage) {
                ench.put("strong_mana", 5);
                ench.put("art_of_peace", 1);
            } else if (!tank) {
                ench.put("strong_mana", 6);
            }
        } else if ("NECKLACE".equals(cat)) {
            ench.put("ultimate_the_one", 5);
        } else if (sword) {
            ench.put("ultimate_chimera", 5);
            ench.put("sharpness", 7);
            ench.put("smite", 7);
            ench.put("critical", 7);
            ench.put("ender_slayer", 7);
            ench.put("titan_killer", 7);
            ench.put("thunderlord", 7);
            ench.put("venomous", 7);
            ench.put("prosecute", 6);
            ench.put("lethality", 6);
            ench.put("gravity", 6);
            ench.put("triple_strike", 5);
            ench.put("fire_aspect", 3);
        } else if (bow) {
            ench.put("ultimate_reiterate", 5);
            ench.put("power", 7);
            ench.put("gravity", 6);
            ench.put("overload", 5);
            ench.put("snipe", 3);
            ench.put("toxophilite", 1);
        }

        boolean recomb = !"NONE".equals(cat) && !"WAND".equals(cat);
        // Fragged only on what is worn: a held weapon's id would turn into STARRED_..., and its ability is found by
        // the plain id (SimAbilities' "BAT_WAND").
        boolean fragged = (armour || equipment) && hasFragged(d.id());
        SimItemBuild b = new SimItemBuild(d.id(), 0, 0, 0, reforge, List.copyOf(gems), books ? 10 : 0,
                books ? 5 : 0, recomb, fragged, 0, 0, Map.copyOf(ench), null);
        if (d.dungeon() || crimson(d.id())) {
            b = b.withStars(10);
        }
        if (k.equals("SKELETON_MASTER_CHESTPLATE")) {
            b = b.withSkeletonRoll(10, 50);
        }
        return b;
    }

    /** The perfect gem a maxed build puts in a slot. */
    private static SimItemBuild.GemType gemFor(String slot, boolean mage, boolean tank) {
        return switch (slot) {
            case "COMBAT", "UNIVERSAL" -> mage ? SimItemBuild.GemType.ONYX
                    : tank ? SimItemBuild.GemType.AMETHYST : SimItemBuild.GemType.JASPER;
            case "DEFENSIVE" -> tank ? SimItemBuild.GemType.AMETHYST : SimItemBuild.GemType.RUBY;
            case "OFFENSIVE" -> mage ? SimItemBuild.GemType.SAPPHIRE : SimItemBuild.GemType.JASPER;
            default -> {
                try {
                    yield SimItemBuild.GemType.valueOf(slot);
                } catch (IllegalArgumentException e) {
                    yield SimItemBuild.GemType.JASPER;
                }
            }
        };
    }

    /**
     * A ready-made preset's build, maxed: the preset's own choices stay (its reforge, enchantments, power scroll, the
     * gem types it picked and a Skeleton Master roll) and everything else is {@link #fullBuild}'s - full stars, the
     * Recombobulator, all Potato Books, fragged, and every gem slot filled with a perfect gem.
     */
    public static SimItemBuild maxed(SimItemBuild preset) {
        if (preset == null || preset.isEmpty()) {
            return preset;
        }
        SimItemBuild full = fullBuild(preset.id());
        Def d = def(preset.id());
        if (d == null) {
            return preset;
        }
        String reforge = preset.reforge() != null && reforgeFits(preset.reforge(), d.category())
                ? preset.reforge() : full.reforge();
        List<SimItemBuild.Gem> gems = new ArrayList<>();
        List<String> slots = d.gemSlots();
        for (int i = 0; i < slots.size(); i++) {
            SimItemBuild.GemType type = i < preset.gems().size() && fits(slots.get(i), preset.gems().get(i).type())
                    ? preset.gems().get(i).type() : full.gems().get(i).type();
            gems.add(new SimItemBuild.Gem(type, SimItemBuild.GemQuality.PERFECT));
        }
        Map<String, Integer> ench = preset.enchantments().isEmpty() ? full.enchantments() : preset.enchantments();
        int floor = preset.skeletonFloor() > 0 ? preset.skeletonFloor() : full.skeletonFloor();
        int quality = preset.skeletonQuality() > 0 ? preset.skeletonQuality() : full.skeletonQuality();
        return new SimItemBuild(full.id(), full.stars(), full.masterStars(), full.crimsonStars(), reforge,
                List.copyOf(gems), full.hotPotato(), full.fuming(), full.recombobulated(),
                full.fragged() || (preset.fragged() && (armourSlot(d.category()) >= 0
                        || equipmentSlot(d.category()) >= 0)), floor, quality, ench,
                preset.powerScroll() != null ? preset.powerScroll() : full.powerScroll());
    }

    // ------------------------------------------------------------------ slots

    /** Armour piece index (0 helmet .. 3 boots) for a category, or -1. */
    public static int armourSlot(String category) {
        if (category == null) {
            return -1;
        }
        return switch (category) {
            case "HELMET" -> 0;
            case "CHESTPLATE" -> 1;
            case "LEGGINGS" -> 2;
            case "BOOTS" -> 3;
            default -> -1;
        };
    }

    /** Equipment index (0 necklace, 1 cloak, 2 belt, 3 gloves/bracelet) for a category, or -1. */
    public static int equipmentSlot(String category) {
        if (category == null) {
            return -1;
        }
        return switch (category) {
            case "NECKLACE" -> 0;
            case "CLOAK" -> 1;
            case "BELT" -> 2;
            case "GLOVES", "BRACELET" -> 3;
            default -> -1;
        };
    }

    public static final String[] ARMOUR_SLOT_NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};
    public static final String[] EQUIPMENT_SLOT_NAMES = {"Necklace", "Cloak", "Belt", "Gloves"};

    /** The items the wardrobe offers for an armour slot (0-3), in table order. */
    public static List<String> armourOptions(int slot) {
        List<String> out = new ArrayList<>();
        for (Def d : HAND.values()) {
            if (armourSlot(d.category()) == slot && !d.id().startsWith("STARRED_")) {
                out.add(d.id());
            }
        }
        return out;
    }

    /** The items the wardrobe offers for an equipment slot (0-3). */
    public static List<String> equipmentOptions(int slot) {
        List<String> out = new ArrayList<>();
        for (Def d : HAND.values()) {
            if (equipmentSlot(d.category()) == slot && !d.id().startsWith("STARRED_")) {
                out.add(d.id());
            }
        }
        return out;
    }

    /** The gemstone slot types of an item ("JASPER", "COMBAT"...), in Hypixel's order. */
    public static List<String> gemSlots(String id) {
        Def d = def(id);
        return d == null ? List.of() : d.gemSlots();
    }

    /** Whether a gem fits a slot type. */
    public static boolean fits(String slot, GemType gem) {
        if (slot == null || gem == null) {
            return false;
        }
        return switch (slot) {
            case "COMBAT" -> gem != null;
            case "DEFENSIVE" -> gem == GemType.RUBY || gem == GemType.AMETHYST;
            case "OFFENSIVE" -> gem == GemType.SAPPHIRE || gem == GemType.JASPER;
            case "UNIVERSAL" -> true;
            default -> slot.equals(gem.name());
        };
    }

    // ------------------------------------------------------------------ reforges

    /** Which items a reforge goes on. */
    private enum ReforgeOn { ARMOUR, CHESTPLATE, EQUIPMENT, BELT, BOW, SWORD }

    /**
     * One reforge: per rarity COMMON..DIVINE (7 columns), each stat's value. NEU {@code reforgestones.json} and
     * {@code reforges.json}, copied 2026-11-01.
     */
    private record Reforge(String name, ReforgeOn on, Map<Stat, double[]> values, String bonus) {
    }

    private static final Map<String, Reforge> REFORGES = new LinkedHashMap<>();

    private static void reforge(String name, ReforgeOn on, String bonus, Object... statThenValues) {
        Map<Stat, double[]> m = new EnumMap<>(Stat.class);
        for (int i = 0; i < statThenValues.length; i += 2) {
            m.put((Stat) statThenValues[i], (double[]) statThenValues[i + 1]);
        }
        REFORGES.put(name.toLowerCase(Locale.ROOT), new Reforge(name, on, Map.copyOf(m), bonus));
    }

    private static double[] r(double... v) {
        return v;
    }

    static {
        // Armour
        reforge("Ancient", ReforgeOn.ARMOUR, "+1 Crit Damage per Catacombs level",
                Stat.HEALTH, r(7, 7, 7, 7, 7, 7, 7), Stat.DEFENSE, r(7, 7, 7, 7, 7, 7, 7),
                Stat.STRENGTH, r(4, 8, 12, 18, 25, 35, 50), Stat.CRIT_CHANCE, r(3, 5, 7, 9, 12, 15, 20),
                Stat.INTELLIGENCE, r(6, 9, 12, 16, 20, 25, 30));
        reforge("Necrotic", ReforgeOn.ARMOUR, "", Stat.INTELLIGENCE, r(30, 60, 90, 120, 150, 200, 250));
        reforge("Loving", ReforgeOn.CHESTPLATE, "+5% Ability Damage (multiplicative)",
                Stat.HEALTH, r(4, 5, 6, 8, 10, 14, 20), Stat.DEFENSE, r(4, 5, 6, 7, 10, 14, 20),
                Stat.INTELLIGENCE, r(20, 40, 60, 80, 100, 120, 140));
        reforge("Giant", ReforgeOn.ARMOUR, "", Stat.HEALTH, r(50, 60, 80, 120, 180, 240, 300));
        reforge("Fierce", ReforgeOn.ARMOUR, "", Stat.STRENGTH, r(2, 4, 6, 8, 10, 12, 14),
                Stat.CRIT_CHANCE, r(2, 3, 4, 5, 6, 8, 10), Stat.CRIT_DAMAGE, r(4, 7, 10, 14, 18, 24, 30));
        reforge("Empowered", ReforgeOn.ARMOUR, "+10 Mending in Dungeons", Stat.HEALTH,
                r(10, 15, 20, 25, 35, 50, 70), Stat.DEFENSE, r(10, 15, 20, 25, 35, 50, 70));
        // Equipment
        reforge("Blended", ReforgeOn.EQUIPMENT, "", Stat.HEALTH, r(1, 1, 2, 2, 2, 3, 3),
                Stat.DEFENSE, r(1, 1, 1, 2, 3, 3, 3), Stat.STRENGTH, r(0, 1, 1, 1, 2, 2, 2),
                Stat.CRIT_CHANCE, r(1, 1, 2, 2, 3, 3, 3), Stat.CRIT_DAMAGE, r(1, 2, 2, 2, 2, 2, 3),
                Stat.ATTACK_SPEED, r(1, 1, 1, 1, 1, 1, 1), Stat.INTELLIGENCE, r(1, 1, 1, 2, 2, 3, 3));
        reforge("Brilliant", ReforgeOn.EQUIPMENT, "", Stat.HEALTH, r(1, 1, 2, 3, 4, 5, 6),
                Stat.INTELLIGENCE, r(5, 6, 7, 9, 12, 15, 18));
        reforge("Waxed", ReforgeOn.EQUIPMENT, "", Stat.HEALTH, r(5, 6, 8, 10, 12, 15, 20),
                Stat.CRIT_CHANCE, r(2, 3, 4, 5, 6, 7, 8));
        reforge("Fortified", ReforgeOn.EQUIPMENT, "", Stat.DEFENSE, r(12, 14, 17, 20, 25, 30, 35));
        reforge("Bloodshot", ReforgeOn.BELT, "2.5% chance on kill to cocoon (not in Dungeons)",
                Stat.STRENGTH, r(1, 2, 3, 4, 5, 6, 7), Stat.CRIT_CHANCE, r(1, 2, 3, 4, 5, 6, 7),
                Stat.CRIT_DAMAGE, r(1, 2, 3, 4, 5, 6, 7));
        // Bows
        reforge("Spiritual", ReforgeOn.BOW, "10% chance on kill to spawn a Spirit Decoy in Dungeons",
                Stat.STRENGTH, r(4, 8, 14, 20, 28, 38, 50), Stat.CRIT_CHANCE, r(7, 8, 9, 10, 12, 14, 16),
                Stat.CRIT_DAMAGE, r(10, 15, 23, 37, 55, 75, 100));
        reforge("Precise", ReforgeOn.BOW, "+10% damage on head hits",
                Stat.STRENGTH, r(3, 7, 12, 18, 25, 34, 45), Stat.CRIT_CHANCE, r(8, 9, 10, 11, 13, 15, 18),
                Stat.CRIT_DAMAGE, r(5, 10, 18, 32, 50, 70, 90));
        reforge("Rapid", ReforgeOn.BOW, "", Stat.STRENGTH, r(2, 3, 4, 7, 10, 12, 14),
                Stat.CRIT_DAMAGE, r(35, 45, 55, 65, 75, 90, 105));
        // Swords
        reforge("Withered", ReforgeOn.SWORD, "+1 Strength per Catacombs level",
                Stat.STRENGTH, r(60, 75, 90, 110, 135, 170, 220));
        reforge("Fabled", ReforgeOn.SWORD, "Crits deal up to +15% more damage",
                Stat.STRENGTH, r(30, 35, 40, 50, 60, 75, 90), Stat.CRIT_DAMAGE, r(15, 20, 25, 32, 40, 50, 65));
        reforge("Heroic", ReforgeOn.SWORD, "", Stat.STRENGTH, r(15, 20, 25, 32, 40, 50, 60),
                Stat.ATTACK_SPEED, r(1, 2, 2, 3, 5, 7, 9), Stat.INTELLIGENCE, r(40, 50, 65, 80, 100, 125, 150));
    }

    /** Whether a reforge can go on an item of this category. */
    public static boolean reforgeFits(String reforge, String category) {
        Reforge rf = reforge == null ? null : REFORGES.get(reforge.toLowerCase(Locale.ROOT));
        if (rf == null || category == null) {
            return false;
        }
        return switch (rf.on()) {
            case ARMOUR -> armourSlot(category) >= 0;
            case CHESTPLATE -> "CHESTPLATE".equals(category);
            case EQUIPMENT -> equipmentSlot(category) >= 0;
            case BELT -> "BELT".equals(category);
            case BOW -> "BOW".equals(category);
            case SWORD -> "SWORD".equals(category) || "LONGSWORD".equals(category) || "FISHING_ROD".equals(category);
        };
    }

    /** The reforges the wardrobe offers for an item, in table order (no reforge first). */
    public static List<String> reforgeOptions(String id) {
        Def d = def(id);
        List<String> out = new ArrayList<>();
        out.add(null);
        if (d != null) {
            for (String k : REFORGES.keySet()) {
                if (reforgeFits(k, d.category())) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /** A reforge's display name ("Ancient"), or null. */
    public static String reforgeName(String reforge) {
        Reforge rf = reforge == null ? null : REFORGES.get(reforge.toLowerCase(Locale.ROOT));
        return rf == null ? null : rf.name();
    }

    /** A reforge's bonus text, or "". */
    public static String reforgeBonus(String reforge) {
        Reforge rf = reforge == null ? null : REFORGES.get(reforge.toLowerCase(Locale.ROOT));
        return rf == null ? "" : rf.bonus();
    }

    /**
     * Column for a rarity in the reforge table. SPECIAL items (the Necron heads) are undocumented: APPROXIMATE as
     * Legendary, Very Special as Mythic.
     */
    private static int reforgeColumn(Rarity r) {
        return switch (r) {
            case SPECIAL -> 4;
            case VERY_SPECIAL -> 5;
            default -> Math.min(6, r.ordinal());
        };
    }

    /** A reforge's stats on an item of this rarity and category (empty when it does not fit). */
    public static Map<Stat, Double> reforgeStats(String reforge, Rarity rarity, String category) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        if (!reforgeFits(reforge, category)) {
            return out;
        }
        Reforge rf = REFORGES.get(reforge.toLowerCase(Locale.ROOT));
        int col = reforgeColumn(rarity);
        for (Map.Entry<Stat, double[]> e : rf.values().entrySet()) {
            if (e.getValue()[col] != 0) {
                out.put(e.getKey(), e.getValue()[col]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ gemstones

    /**
     * Gem stat per quality (ROUGH..PERFECT) and rarity (COMMON..MYTHIC), NEU's gemstone table. The Epic, Legendary
     * and Mythic Flawless/Perfect values are the research's (armour-equipment.md); Divine has none in NEU and uses
     * Mythic's.
     */
    private static final Map<GemType, double[][]> GEMS = Map.of(
            GemType.JASPER, new double[][]{{1, 1, 1, 2, 2, 3}, {2, 2, 3, 4, 4, 5}, {3, 3, 4, 5, 6, 7},
                    {5, 6, 7, 8, 10, 12}, {6, 7, 9, 11, 13, 16}},
            GemType.SAPPHIRE, new double[][]{{2, 3, 4, 5, 6, 7}, {5, 5, 6, 7, 8, 10}, {7, 8, 9, 10, 11, 12},
                    {10, 11, 12, 14, 17, 20}, {12, 14, 17, 20, 24, 30}},
            GemType.ONYX, new double[][]{{1, 1, 2, 2, 3, 4}, {2, 2, 3, 3, 4, 5}, {3, 3, 4, 4, 5, 6},
                    {4, 5, 6, 7, 8, 10}, {6, 7, 8, 8, 10, 12}},
            GemType.AMETHYST, new double[][]{{1, 2, 3, 4, 5, 7}, {3, 4, 5, 6, 8, 10}, {4, 5, 6, 8, 10, 14},
                    {5, 7, 10, 14, 18, 22}, {6, 9, 13, 18, 24, 30}},
            GemType.RUBY, new double[][]{{1, 2, 3, 4, 5, 7}, {3, 4, 5, 6, 8, 10}, {4, 5, 6, 8, 10, 14},
                    {5, 7, 10, 14, 18, 22}, {6, 9, 13, 18, 24, 30}});

    /** One gemstone's stat on an item of this rarity. */
    public static double gemValue(GemType type, GemQuality quality, Rarity rarity) {
        int col = switch (rarity) {
            case DIVINE, VERY_SPECIAL -> 5;
            case SPECIAL -> 4;
            default -> Math.min(5, rarity.ordinal());
        };
        return GEMS.get(type)[quality.ordinal()][col];
    }

    // ------------------------------------------------------------------ enchantments that are plain stats

    /** Maxed collections assumed for The One (the sim has no collections): APPROXIMATE, a typical M7 player's. */
    public static final int THE_ONE_MAXED_COLLECTIONS = 60;
    /** Accessory Power digits assumed for Cayenne: 4 (1,000-9,999 MP), the wiki trivia's example. */
    public static final int CAYENNE_DIGITS = 4;

    private static final double[] GROWTH = {15, 30, 45, 60, 75, 90, 105};
    private static final double[] PROTECTION = {4, 8, 12, 16, 20, 25, 30};
    private static final double[] CRITICAL = {10, 20, 30, 40, 50, 70, 100};
    private static final double[] TOXOPHILITE = {3.7, 4.4, 5.1, 5.8, 6.5, 7.2, 7.9, 8.6, 9.3, 10};

    /** The stats one enchantment at a level adds, or empty for a damage/ability enchant. */
    public static Map<Stat, Double> statEnchant(String name, int level) {
        Map<Stat, Double> m = new EnumMap<>(Stat.class);
        if (name == null || level <= 0) {
            return m;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "growth" -> m.put(Stat.HEALTH, at(GROWTH, level));
            case "protection" -> m.put(Stat.DEFENSE, at(PROTECTION, level));
            case "big_brain" -> m.put(Stat.INTELLIGENCE, 5.0 * level);
            case "smarty_pants" -> m.put(Stat.INTELLIGENCE, 5.0 * level);
            case "art_of_peace" -> m.put(Stat.HEALTH, 40.0);
            case "prosperity" -> m.put(Stat.HEALTH, 3.0 * level);
            case "critical" -> m.put(Stat.CRIT_DAMAGE, at(CRITICAL, level));
            case "vicious" -> m.put(Stat.FEROCITY, (double) level);
            case "toxophilite" -> m.put(Stat.CRIT_CHANCE, at(TOXOPHILITE, level));
            case "overload" -> {
                m.put(Stat.CRIT_CHANCE, (double) level);
                m.put(Stat.CRIT_DAMAGE, (double) level);
            }
            case "ultimate_the_one" -> {
                // IV: +0.5 Health +0.1 Strength per maxed collection; V: +1 / +0.2.
                double per = level >= 5 ? 1.0 : 0.5;
                m.put(Stat.HEALTH, per * THE_ONE_MAXED_COLLECTIONS);
                m.put(Stat.STRENGTH, per * 0.2 * THE_ONE_MAXED_COLLECTIONS);
            }
            case "cayenne" -> m.put(Stat.HEALTH, (level >= 5 ? 1.0 : 0.8) * CAYENNE_DIGITS);
            default -> {
                // a damage enchant (stored for SimEnchants) or one the sim has no stat for
            }
        }
        return m;
    }

    private static double at(double[] table, int level) {
        return table[Math.max(1, Math.min(table.length, level)) - 1];
    }

    // ------------------------------------------------------------------ the dungeon boost

    /** Hypixel's Catacombs stat bonus for a level, as a fraction (4.85 at 50). */
    public static double catacombsBonus(int level) {
        double[] low = {0, 0.15, 0.20, 0.25, 0.31, 0.38, 0.46, 0.55, 0.65};
        int l = Math.max(0, Math.min(SimStats.MAX_LEVEL, level));
        return l <= 8 ? low[l] : 0.65 + 0.10 * (l - 8);
    }

    /** Whether a floor code is a dungeon floor ("E", "F1".."F7", "M1".."M7"). */
    public static boolean inDungeon(String floor) {
        return floor != null && !floor.isBlank();
    }

    /** What a build's boostable stats are multiplied by here. See the class doc. */
    public static double itemMultiplier(Def def, SimItemBuild b, SimItemBuild.Context ctx) {
        if (crimson(b.id())) {
            return 1 + 0.02 * b.crimsonStars();
        }
        if (def == null || !def.dungeon()) {
            return 1;
        }
        String floor = ctx == null ? null : ctx.floor();
        if (!inDungeon(floor)) {
            return 1 + 0.02 * b.stars();
        }
        double m = 1 + catacombsBonus(ctx.catacombs()) + 0.10 * b.stars() + SimAccessories.MEDALLION;
        if (SimStats.masterMode(floor)) {
            m += 0.05 * b.masterStars();
        }
        return m;
    }

    private static boolean boosted(Stat s) {
        return s != Stat.CRIT_CHANCE && s != Stat.ATTACK_SPEED && s != Stat.ABILITY_DAMAGE && s != Stat.DAMAGE_BONUS;
    }

    /** The build's rarity: the item's, +1 for a 50% quality roll, +1 for a Recombobulator. */
    public static Rarity rarity(SimItemBuild b) {
        Def d = def(b.dataId());
        Rarity r = d == null || d.tier() == null ? Rarity.COMMON : d.tier();
        if (d != null && !d.tiered().isEmpty() && b.skeletonQuality() >= 50) {
            r = r.next();
        }
        return b.recombobulated() ? r.next() : r;
    }

    /** Whether potato books on this category are armour books (+4 HP +2 Def) - else weapon books. */
    private static boolean armourBooks(String category) {
        return armourSlot(category) >= 0;
    }

    private static boolean weaponBooks(String category) {
        return "SWORD".equals(category) || "BOW".equals(category) || "LONGSWORD".equals(category)
                || "FISHING_ROD".equals(category);
    }

    /** {@link SimItemBuild#stats}: every step in the class doc. */
    static SimItemBuild.Contribution contribution(SimItemBuild b, SimItemBuild.Context ctx) {
        Def d = def(b.dataId());
        if (d == null) {
            return SimItemBuild.Contribution.NONE;
        }
        Rarity rarity = rarity(b);
        Map<Stat, Double> m = new EnumMap<>(Stat.class);
        double damage;
        // 1. base (a randomized drop by its floor tier and quality)
        if (!d.tiered().isEmpty() || d.tieredDamage() != null) {
            int tier = b.skeletonFloor() <= 0 ? 10 : b.skeletonFloor();
            double q = 1 + b.skeletonQuality() / 100.0;
            for (Map.Entry<Stat, double[]> e : d.tiered().entrySet()) {
                double[] a = e.getValue();
                m.merge(e.getKey(), a[Math.min(a.length, tier) - 1] * q, Double::sum);
            }
            double[] td = d.tieredDamage();
            damage = td == null ? d.damage() : td[Math.min(td.length, tier) - 1] * q;
            SimItemBuild.addAll(m, d.stats());
        } else {
            m.putAll(d.stats());
            damage = d.damage();
        }
        // 2. reforge at the final rarity
        SimItemBuild.addAll(m, reforgeStats(b.reforge(), rarity, d.category()));
        // 3. gemstones that fit their slots
        List<String> slots = d.gemSlots();
        for (int i = 0; i < b.gems().size() && i < slots.size(); i++) {
            Gem g = b.gems().get(i);
            if (fits(slots.get(i), g.type())) {
                m.merge(g.type().stat, gemValue(g.type(), g.quality(), rarity), Double::sum);
            }
        }
        // 4. potato books (doubled by a Blaze pet's Fusion-Style Potato)
        int books = b.hotPotato() + b.fuming();
        double bookMult = ctx != null && "BLAZE".equals(ctx.petId()) ? 2 : 1;
        if (books > 0 && armourBooks(d.category())) {
            m.merge(Stat.HEALTH, 4.0 * books * bookMult, Double::sum);
            m.merge(Stat.DEFENSE, 2.0 * books * bookMult, Double::sum);
        } else if (books > 0 && weaponBooks(d.category())) {
            m.merge(Stat.STRENGTH, 2.0 * books * bookMult, Double::sum);
            damage += 2.0 * books * bookMult;
        }
        // 5. stat enchantments
        for (Map.Entry<String, Integer> e : b.enchantments().entrySet()) {
            SimItemBuild.addAll(m, statEnchant(e.getKey(), e.getValue()));
        }
        // 6. the Necron heads double on Floor VII / Master Floor VII
        if ((b.id().equals("DIAMOND_NECRON_HEAD") || b.id().equals("GOLD_NECRON_HEAD")) && ctx != null
                && ctx.floor() != null && ctx.floor().toUpperCase(Locale.ROOT).endsWith("7")) {
            m.replaceAll((k, v) -> v * 2);
        }
        // 7. the dungeon (or Crimson) boost
        double mult = itemMultiplier(d, b, ctx);
        if (mult != 1) {
            m.replaceAll((k, v) -> boosted(k) ? v * mult : v);
            damage *= mult;
        }
        // 8. the Adaptive Belt's class bonus, after the boost
        if (b.id().equals("ADAPTIVE_BELT") && ctx != null && ctx.dungeonClass() != null) {
            switch (ctx.dungeonClass()) {
                case BERSERK -> m.merge(Stat.STRENGTH, 10.0, Double::sum);
                case HEALER -> m.merge(Stat.HEALTH, 10.0, Double::sum);
                case MAGE -> m.merge(Stat.INTELLIGENCE, 25.0, Double::sum);
                case TANK -> {
                    m.merge(Stat.HEALTH, 5.0, Double::sum);
                    m.merge(Stat.DEFENSE, 10.0, Double::sum);
                }
                case ARCHER -> {
                    m.merge(Stat.CRIT_CHANCE, 2.0, Double::sum);
                    m.merge(Stat.CRIT_DAMAGE, 5.0, Double::sum);
                }
            }
        }
        // Reforge bonuses that scale with Catacombs: Ancient's +1 Crit Damage and Withered's +1 Strength a level.
        if ("ancient".equals(b.reforge()) && reforgeFits("ancient", d.category()) && ctx != null) {
            m.merge(Stat.CRIT_DAMAGE, (double) Math.min(SimStats.MAX_LEVEL, ctx.catacombs()), Double::sum);
        }
        if ("withered".equals(b.reforge()) && reforgeFits("withered", d.category()) && ctx != null) {
            m.merge(Stat.STRENGTH, (double) Math.min(SimStats.MAX_LEVEL, ctx.catacombs()), Double::sum);
        }
        m.values().removeIf(v -> v == 0);
        return new SimItemBuild.Contribution(Map.copyOf(m), damage, rarity, mult, d.real());
    }

    // ------------------------------------------------------------------ names and lore

    private static final String MASTER = "➊➋➌➍➎";

    /** "Ancient Necron's Leggings ✪✪✪✪✪➎", "⚚ Waxed Shadow Assassin Cloak" - Hypixel's naming. */
    public static String displayName(SimItemBuild b) {
        Def d = def(b.dataId());
        String base = d == null ? prettyId(b.id()) : d.name();
        String rf = reforgeName(b.reforge());
        StringBuilder out = new StringBuilder();
        if (b.fragged() && hasFragged(b.id())) {
            out.append("⚚ ");
        }
        if (rf != null) {
            out.append(rf).append(' ');
        }
        out.append(base);
        return out.toString() + stars(b);
    }

    private static String stars(SimItemBuild b) {
        if (crimson(b.id())) {
            int n = b.crimsonStars();
            if (n == 0) {
                return "";
            }
            int red = Math.max(0, n - 5);
            return " §c" + "✪".repeat(red) + "§6" + "✪".repeat(Math.min(5, n) - red);
        }
        if (b.stars() == 0) {
            return "";
        }
        return " §6" + "✪".repeat(b.stars()) + (b.masterStars() > 0 ? "§c" + MASTER.charAt(b.masterStars() - 1) : "");
    }

    private static String nice(String ench) {
        String n = ench.startsWith("ultimate_") ? ench.substring(9) : ench;
        // Hypixel's lore name where it differs from the id: ultimate_reiterate is shown as Duplex.
        if (n.equals("reiterate")) {
            return "Duplex";
        }
        return prettyId(n);
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** What an item does beyond its stats, for the lore (and what is or is not simulated). */
    public static List<String> abilityLines(String id) {
        if (id == null) {
            return List.of();
        }
        return switch (id) {
            case "POWER_WITHER_HELMET", "POWER_WITHER_CHESTPLATE", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS",
                 "WISE_WITHER_HELMET", "WISE_WITHER_CHESTPLATE", "WISE_WITHER_LEGGINGS", "WISE_WITHER_BOOTS",
                 "TANK_WITHER_HELMET", "TANK_WITHER_CHESTPLATE", "TANK_WITHER_LEGGINGS", "TANK_WITHER_BOOTS" ->
                    List.of("§7Reduces the damage you take from", "§7withers by §c10%§7.");
            case "SPEED_WITHER_HELMET", "SPEED_WITHER_CHESTPLATE", "SPEED_WITHER_LEGGINGS", "SPEED_WITHER_BOOTS" ->
                    List.of("§7Reduces the damage you take from", "§7withers by §c10% §7and increases your",
                            "§7arrow damage by §a5%§7.");
            case "SKELETON_MASTER_CHESTPLATE" -> List.of("§7Increases the damage you deal", "§7with arrows by §c5%§7.",
                    "§7Your bows don't consume arrows.");
            case "GOLD_NECRON_HEAD", "DIAMOND_NECRON_HEAD" -> List.of("§7Doubles its stats on Floor VII", "§7and Master Floor VII.");
            case "BONE_NECKLACE" -> List.of("§6Ability: Gladiator's Will", "§7Gain §a+3❈ Defense §7for each enemy",
                    "§7within §a10 §7blocks, up to §a+30❈§7.", "§7Tank: 30 blocks, up to §a+60❈§7.");
            case "SHADOW_ASSASSIN_CLOAK" -> List.of("§7On teleport: your next melee hit", "§7within §a5s §7deals §c10% §7more damage.");
            case "ADAPTIVE_BELT" -> List.of("§7Grants additional bonuses based", "§7on your selected dungeon class!");
            case "SOULWEAVER_GLOVES" -> List.of("§7Summons a Haunted Skull every §a15s", "§7or on a melee kill (max §a3§7).",
                    "§7Mobs a skull hits deal §c5% §7less damage.");
            case "BALLOON_SNAKE" -> List.of("§7Grants permanent Jump Boost II.");
            case "IMPLOSION_BELT" -> List.of("§6Ability: Consolidated", "§7Increases all explosion damage", "§7dealt by §c25%§7.");
            case "VANQUISHED_GHAST_CLOAK" -> List.of("§6Ability: Blazing Restoration", "§7Restores §c+15❤ §7every second.");
            case "DOJO_BLACK_BELT" -> List.of("§7Reduces damage taken by §a6%§7.");
            default -> List.of();
        };
    }

    /**
     * The build's tooltip: name with stars, every stat as it is in the current sim floor (or M7) at the profile's
     * Catacombs level, the gems, enchantments, ability lines and the rarity line.
     */
    static void applyLore(ItemStack stack, SimItemBuild b) {
        Def d = def(b.dataId());
        SimStats.Profile p = SimStats.profile();
        String floor = SimState.floorLabel();
        if (floor == null || floor.isBlank()) {
            floor = "M7";
        }
        SimItemBuild.Contribution c = b.stats(new SimItemBuild.Context(floor, p.catacombs(), p.selected(), null));
        Rarity rarity = c.rarity();
        List<String> lore = new ArrayList<>();
        if (c.damage() > 0) {
            lore.add("§7Damage: §c+" + SimDamage.commas(c.damage()));
        }
        for (Stat s : Stat.values()) {
            double v = c.get(s);
            if (v != 0) {
                boolean percent = s == Stat.CRIT_CHANCE || s == Stat.CRIT_DAMAGE || s == Stat.ABILITY_DAMAGE
                        || s == Stat.ATTACK_SPEED;
                char col = s == Stat.DEFENSE || s == Stat.HEALTH ? 'a' : s == Stat.INTELLIGENCE ? 'a' : 'c';
                lore.add("§7" + s.label + ": §" + col + "+" + SimDamage.commas(v) + (percent ? "%" : ""));
            }
        }
        if (c.multiplier() != 1) {
            lore.add(String.format(Locale.ROOT, "§8(x%.2f %s)", c.multiplier(),
                    crimson(b.id()) ? "Crimson stars" : "in " + floor + " at Catacombs " + p.catacombs()));
        }
        if (!b.gems().isEmpty()) {
            StringBuilder g = new StringBuilder("§7Gems:");
            for (Gem gem : b.gems()) {
                g.append(' ').append(prettyId(gem.quality().name())).append(' ').append(prettyId(gem.type().name()));
            }
            lore.add(g.toString());
        }
        if (!b.enchantments().isEmpty()) {
            lore.add("");
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, Integer> e : b.enchantments().entrySet()) {
                String part = (e.getKey().startsWith("ultimate_") ? "§d§l" : "§9") + nice(e.getKey()) + " "
                        + roman(e.getValue());
                if (line.length() > 0 && LegacyLen.of(line) + LegacyLen.of(part) > 40) {
                    lore.add(line.toString());
                    line.setLength(0);
                }
                line.append(line.length() > 0 ? "§9, " : "").append(part);
            }
            if (line.length() > 0) {
                lore.add(line.toString());
            }
        }
        if (b.powerScroll() != null) {
            lore.add("§7Power Scroll: §f" + prettyId(b.powerScroll().replace("_POWER_SCROLL", "")));
        }
        List<String> ability = abilityLines(b.id());
        if (!ability.isEmpty()) {
            lore.add("");
            lore.addAll(ability);
        }
        String bonus = reforgeBonus(b.reforge());
        if (!bonus.isEmpty()) {
            lore.add("§9" + reforgeName(b.reforge()) + " Bonus: §7" + bonus);
        }
        lore.add("");
        lore.add(c.real() ? "§8Stats: Hypixel item data." : "§8Stats: built-in copy (item data not loaded).");
        String type = d == null ? "" : d.category().replace('_', ' ');
        lore.add("§" + rarity.colour + "§l" + rarity.label() + (d != null && d.dungeon() ? " DUNGEON " : " ") + type);
        SimItemLore.applyCustom(stack, "§" + rarity.colour + displayName(b), lore);
    }

    /** Visible length of a § string. */
    private static final class LegacyLen {
        static int of(CharSequence s) {
            return s.toString().replaceAll("§.", "").length();
        }
    }
}
