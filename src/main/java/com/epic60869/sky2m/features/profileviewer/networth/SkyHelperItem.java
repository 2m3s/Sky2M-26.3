package com.epic60869.sky2m.features.profileviewer.networth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One SkyBlock item's networth, ported from SkyHelper-Networth (MIT, https://github.com/Altpapier/SkyHelper-Networth,
 * v2.11.1): calculators/SkyBlockItemNetworthCalculator.js, helpers/SkyBlockItemNetworthHelper.js, every handler in
 * calculators/handlers/ (in the order of helpers/handlers.js), helper/essenceStars.js and the constants. The item is
 * the NBT tree {@link Nbt} reads; {@code price} is what the handlers add, {@code basePrice} the item itself.
 */
final class SkyHelperItem {
    // constants/applicationWorth.js
    static final double ENRICHMENT = 0.5, FARMING_FOR_DUMMIES = 0.5, OVERCLOCKER = 0.9, GEMSTONE_POWER_SCROLL = 0.5, WOOD_SINGULARITY = 0.5,
        ART_OF_WAR = 0.6, FUMING_POTATO_BOOK = 0.6, GEMSTONE_SLOTS_WORTH = 0.6, RUNES = 0.6, TUNED_TRANSMISSION = 0.7, POCKET_SACK = 0.7,
        ESSENCE = 0.75, SILEX = 0.75, ART_OF_PEACE = 0.8, DIVAN_POWDER_COATING = 0.8, ENCHANTMENT_UPGRADE = 0.8, JALAPENO_BOOK = 0.8,
        MANA_DISINTEGRATOR = 0.8, RECOMBOBULATOR = 0.8, THUNDER_IN_A_BOTTLE = 0.8, ENCHANTMENTS = 0.85, SHENS_AUCTION = 0.85, BOOSTER = 0.8,
        DYE = 0.9, GEMSTONE_CHAMBERS = 0.9, ROD_PART = 1, DRILL_PART = 1, ETHERWARP = 1, MASTER_STAR = 1, GEMSTONE = 1, HOT_POTATO_BOOK = 1,
        ABILITY_SCROLL = 1, POLARVOID_BOOK = 1, REFORGE = 1, SOULBOUND_SKINS = 0.8;
    static final Map<String, Double> ENCHANTMENTS_WORTH = Map.of("COUNTER_STRIKE", 0.2, "BIG_BRAIN", 0.35, "ULTIMATE_INFERNO", 0.35,
        "OVERLOAD", 0.35, "ULTIMATE_SOUL_EATER", 0.35, "ULTIMATE_FATAL_TEMPO", 0.65);

    // constants/misc.js
    private static final Map<String, List<String>> BLOCKED_ENCHANTMENTS = Map.of(
        "BONE_BOOMERANG", List.of("OVERLOAD", "POWER", "ULTIMATE_SOUL_EATER"), "DEATH_BOW", List.of("OVERLOAD", "POWER", "ULTIMATE_SOUL_EATER"),
        "GARDENING_AXE", List.of("REPLENISH"), "GARDENING_HOE", List.of("REPLENISH"), "ADVANCED_GARDENING_AXE", List.of("REPLENISH"),
        "ADVANCED_GARDENING_HOE", List.of("REPLENISH"));
    private static final Map<String, Integer> IGNORED_ENCHANTMENTS = Map.of("SCAVENGER", 5);
    private static final Set<String> STACKING_ENCHANTMENTS = Set.of("EXPERTISE", "COMPACT", "ABSORB", "CULTIVATING", "CHAMPION", "HECATOMB", "TOXOPHILITE");
    private static final Set<String> IGNORE_SILEX = Set.of("PROMISING_SPADE", "PROMISING_AXE");
    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};
    private static final Set<String> RECOMB_CATEGORIES = Set.of("ACCESSORY", "NECKLACE", "GLOVES", "BRACELET", "BELT", "CLOAK", "VACUUM");
    private static final Set<String> RECOMB_IDS = Set.of("DIVAN_HELMET", "DIVAN_CHESTPLATE", "DIVAN_LEGGINGS", "DIVAN_BOOTS", "FERMENTO_HELMET",
        "FERMENTO_CHESTPLATE", "FERMENTO_LEGGINGS", "FERMENTO_BOOTS", "SHADOW_ASSASSIN_CLOAK", "STARRED_SHADOW_ASSASSIN_CLOAK");
    private static final String[] ENRICHMENTS = {"TALISMAN_ENRICHMENT_CRITICAL_CHANCE", "TALISMAN_ENRICHMENT_CRITICAL_DAMAGE",
        "TALISMAN_ENRICHMENT_DEFENSE", "TALISMAN_ENRICHMENT_HEALTH", "TALISMAN_ENRICHMENT_INTELLIGENCE", "TALISMAN_ENRICHMENT_MAGIC_FIND",
        "TALISMAN_ENRICHMENT_WALK_SPEED", "TALISMAN_ENRICHMENT_STRENGTH", "TALISMAN_ENRICHMENT_ATTACK_SPEED", "TALISMAN_ENRICHMENT_FEROCITY",
        "TALISMAN_ENRICHMENT_SEA_CREATURE_CHANCE"};
    private static final List<String> GEMSTONE_SLOTS = List.of("COMBAT", "OFFENSIVE", "DEFENSIVE", "MINING", "UNIVERSAL", "CHISEL");
    private static final Pattern DIVANS = Pattern.compile("^DIVAN_(HELMET|CHESTPLATE|LEGGINGS|BOOTS|PENDANT)$");
    private static final Pattern KUUDRA_ARMOUR = Pattern.compile("^((HOT|FIERY|BURNING|INFERNAL)_)?(AURORA|CRIMSON|TERROR|HOLLOW|FERVOR)_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)$");
    private static final String[] DRILL_PARTS = {"drill_part_upgrade_module", "drill_part_fuel_tank", "drill_part_engine"};
    private static final String[] ROD_PARTS = {"line", "hook", "sinker"};
    /** constants/pets.js TIERS, which the booster handler counts tiers with. */
    static final List<String> TIERS = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL", "ULTIMATE");

    private record Upgrade(String item, int tier, boolean oncePerItem) {}

    private static final List<Upgrade> TURBO_CROP = List.of(new Upgrade("TURBO_GOURD", 6, true), new Upgrade("ENCHANTED_TURBO_GOURD", 7, true));
    private static final Map<String, List<Upgrade>> ENCHANTMENT_UPGRADES = new LinkedHashMap<>();

    static {
        ENCHANTMENT_UPGRADES.put("SCAVENGER", List.of(new Upgrade("GOLDEN_BOUNTY", 6, false)));
        ENCHANTMENT_UPGRADES.put("PESTERMINATOR", List.of(new Upgrade("PESTHUNTING_GUIDE", 6, false)));
        ENCHANTMENT_UPGRADES.put("LUCK_OF_THE_SEA", List.of(new Upgrade("GOLD_BOTTLE_CAP", 7, false)));
        ENCHANTMENT_UPGRADES.put("PISCARY", List.of(new Upgrade("TROUBLED_BUBBLE", 7, false)));
        ENCHANTMENT_UPGRADES.put("FRAIL", List.of(new Upgrade("SEVERED_PINCER", 7, false)));
        ENCHANTMENT_UPGRADES.put("SPIKED_HOOK", List.of(new Upgrade("OCTOPUS_TENDRIL", 7, false)));
        ENCHANTMENT_UPGRADES.put("CHARM", List.of(new Upgrade("CHAIN_END_TIMES", 6, false)));
        ENCHANTMENT_UPGRADES.put("SMITE", List.of(new Upgrade("SEVERED_HAND", 7, false)));
        ENCHANTMENT_UPGRADES.put("ENDER_SLAYER", List.of(new Upgrade("ENDSTONE_IDOL", 7, false)));
        ENCHANTMENT_UPGRADES.put("BANE_OF_ARTHROPODS", List.of(new Upgrade("ENSNARED_SNAIL", 7, false)));
        ENCHANTMENT_UPGRADES.put("VENOMOUS", List.of(new Upgrade("FATEFUL_STINGER", 7, false)));
        for (String crop : new String[]{"WHEAT", "CARROT", "POTATO", "PUMPKIN", "MELON", "MUSHROOMS", "COCOA", "CACTI", "CANE", "WARTS", "SUNFLOWER", "MOONFLOWER", "ROSE"}) {
            ENCHANTMENT_UPGRADES.put("TURBO_" + crop, TURBO_CROP);
        }
        ENCHANTMENT_UPGRADES.put("THORNS", List.of(new Upgrade("PRICKLY_CREEPER", 4, false)));
        ENCHANTMENT_UPGRADES.put("SCUBA", List.of(new Upgrade("VIBRANT_CORAL", 6, false)));
        ENCHANTMENT_UPGRADES.put("FOREST_PLEDGE", List.of(new Upgrade("WATER_HYACINTH", 6, false)));
        ENCHANTMENT_UPGRADES.put("KARMA", List.of(new Upgrade("FORESTS_FAVOR", 6, false)));
        ENCHANTMENT_UPGRADES.put("STEALTH", List.of(new Upgrade("DISTANT_ECHO", 6, false)));
    }

    // constants/reforges.js
    private static final Map<String, String> REFORGES = new LinkedHashMap<>();

    static {
        String[] pairs = {"stiff", "HARDENED_WOOD", "trashy", "OVERFLOWING_TRASH_CAN", "salty", "SALT_CUBE", "aote_stone", "AOTE_STONE",
            "blazing", "BLAZEN_SPHERE", "waxed", "BLAZE_WAX", "rooted", "BURROWING_SPORES", "calcified", "CALCIFIED_HEART", "candied", "CANDY_CORN",
            "perfect", "DIAMOND_ATOM", "fleet", "DIAMONITE", "fabled", "DRAGON_CLAW", "spiked", "DRAGON_SCALE", "royal", "DWARVEN_TREASURE",
            "warped", "ENDSTONE_GEODE", "coldfusion", "ENTROPY_SUPPRESSOR", "blooming", "FLOWERING_BOUQUET", "fanged", "FULL_JAW_FANGING_KIT",
            "jaded", "JADERALD", "jerry_stone", "JERRY_STONE", "magnetic", "LAPIS_CRYSTAL", "earthy", "LARGE_WALNUT", "groovy", "MANGROVE_GEM",
            "fortified", "METEOR_SHARD", "gilded", "MIDAS_JEWEL", "cubic", "MOLTEN_CUBE", "moonglade", "MOONGLADE_JEWEL", "lunar", "MOONSTONE",
            "necrotic", "NECROMANCER_BROOCH", "fruitful", "ONYX", "precise", "OPTICAL_LENS", "mossy", "OVERGROWN_GRASS", "pitchin", "PITCHIN_KOI",
            "undead", "PREMIUM_FLESH", "blood_soaked", "PRESUMED_GALLON_OF_RED_PAINT", "mithraic", "PURE_MITHRIL", "reinforced", "RARE_DIAMOND",
            "ridiculous", "RED_NOSE", "loving", "RED_SCARF", "auspicious", "ROCK_GEMSTONE", "treacherous", "RUSTY_ANCHOR", "headstrong", "SALMON_OPAL",
            "strengthened", "SEARING_STONE", "glistening", "SHINY_PRISM", "bustling", "SKYMART_BROCHURE", "spiritual", "SPIRIT_DECOY",
            "squeaky", "SQUEAKY_TOY", "sunny", "SUNSTONE", "suspicious", "SUSPICIOUS_VIAL", "snowy", "TERRY_SNOWGLOBE", "dimensional", "TITANIUM_TESSERACT",
            "ambered", "AMBER_MATERIAL", "beady", "BEADY_EYES", "blessed", "BLESSED_FRUIT", "bulky", "BULKY_STONE", "buzzing", "CLIPPED_WINGS",
            "erudite", "DAEDALUS_NOTES", "submerged", "DEEP_SEA_ORB", "renowned", "DRAGON_HORN", "festive", "FROZEN_BAUBLE", "giant", "GIANT_TOOTH",
            "lustrous", "GLEAMING_CRYSTAL", "bountiful", "GOLDEN_BALL", "chomp", "KUUDRA_MANDIBLE", "lucky", "LUCKY_DICE", "mantid", "MANTID_CLAW",
            "stellar", "PETRIFIED_STARFALL", "scraped", "POCKET_ICEBERG", "ancient", "PRECURSOR_GEAR", "refined", "REFINED_AMBER",
            "empowered", "SADAN_BROOCH", "withered", "WITHER_BLOOD", "glacial", "FRIGID_HUSK", "heated", "HOT_STUFF", "blood_shot", "SHRIVELED_CORNEA",
            "overpriced", "OVERPRICED_DRINK", "deep_fried", "HASHBROWN", "geometric", "GEOMETRIC_ODDITY", "thorny", "BLOOMING_THORNS",
            "sticky", "EXTREMELY_MILD_ADHESIVE", "majestic", "MORNING_DEW", "marshy", "MARSHROOM", "dirty", "DIRT_BOTTLE", "moil", "MOIL_LOG",
            "toil", "TOIL_LOG", "greater_spook", "BOO_STONE"};
        for (int i = 0; i < pairs.length; i += 2) REFORGES.put(pairs[i], pairs[i + 1]);
    }

    // constants/prestiges.js: each Kuudra armour piece and the pieces it was upgraded from.
    private static final Map<String, List<String>> PRESTIGES = new LinkedHashMap<>();

    static {
        String[] prefixes = {"", "HOT_", "BURNING_", "FIERY_", "INFERNAL_"};
        for (String set : new String[]{"CRIMSON", "TERROR", "FERVOR", "HOLLOW", "AURORA"}) {
            for (String piece : new String[]{"CHESTPLATE", "HELMET", "LEGGINGS", "BOOTS"}) {
                for (int p = 1; p < prefixes.length; p++) {
                    List<String> previous = new ArrayList<>();
                    for (int q = p - 1; q >= 0; q--) previous.add(prefixes[q] + set + "_" + piece);
                    PRESTIGES.put(prefixes[p] + set + "_" + piece, previous);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ JavaScript-like reads

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** JavaScript's Number(): NaN for missing or non-numbers. */
    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof String s) {
            try {
                return s.isBlank() ? 0 : Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }

    static String str(Object o) {
        return o == null ? null : o instanceof String s ? s : String.valueOf(o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? n.longValue() : o);
    }

    /** {@code collection.includes(value)}, false for null (immutable collections throw on null). */
    static boolean in(java.util.Collection<?> collection, Object value) {
        return value != null && collection.contains(value);
    }

    static double price(Map<String, Double> prices, String id) {
        Double p = id == null ? null : prices.get(id);
        return p == null || p.isNaN() ? 0 : p;
    }

    /** {@code prices[id]} is set and not 0 (JavaScript truthiness). */
    static boolean has(Map<String, Double> prices, String id) {
        return price(prices, id) != 0;
    }

    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String word : s.toLowerCase(Locale.ROOT).replace('_', ' ').split(" ", -1)) {
            if (!out.isEmpty()) out.append(' ');
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ the item

    private final Map<String, Object> ea;
    final String baseItemId;
    String itemId;
    private final JsonObject skyblockItem;
    private final List<String> lore = new ArrayList<>();
    private final double count;
    double basePrice;
    double price;

    private SkyHelperItem(Map<String, Object> itemData, Map<String, JsonObject> items) {
        Map<String, Object> tag = map(itemData.get("tag"));
        Map<String, Object> extra = tag == null ? null : map(tag.get("ExtraAttributes"));
        ea = extra == null ? new LinkedHashMap<>() : extra;
        itemId = str(ea.get("id"));
        baseItemId = itemId;
        JsonObject info = itemId == null ? null : items.get(itemId);
        skyblockItem = info == null ? new JsonObject() : info;
        Map<String, Object> display = tag == null ? null : map(tag.get("display"));
        List<Object> loreList = display == null ? null : list(display.get("Lore"));
        if (loreList != null) for (Object line : loreList) lore.add(String.valueOf(line));
        double c = num(itemData.get("Count"));
        count = Double.isNaN(c) ? 1 : c;
    }

    /** An item is valued when it has a display name, like SkyHelper's validation; otherwise null. */
    static SkyHelperItem of(Map<String, Object> itemData, Map<String, JsonObject> items) {
        Map<String, Object> tag = map(itemData.get("tag"));
        Map<String, Object> display = tag == null ? null : map(tag.get("display"));
        if (display == null || display.get("Name") == null) return null;
        return new SkyHelperItem(itemData, items);
    }

    /** The item's networth: its base price and everything applied to it. */
    double networth(Map<String, Double> prices, Map<String, JsonObject> items) {
        itemId = customId(prices);
        basePrice = price(prices, itemId) * count;
        price = 0;
        pickonimbus();
        prestige(prices, items);
        shensAuction();
        midasWeapon(prices);
        avariceCoins(prices);
        enchantedBook(prices);
        itemEnchantments(prices);
        multiplied(prices, "sack_pss", "POCKET_SACK_IN_A_SACK", POCKET_SACK);
        multiplied(prices, "wood_singularity_count", "WOOD_SINGULARITY", WOOD_SINGULARITY);
        multiplied(prices, "jalapeno_count", "JALAPENO_BOOK", JALAPENO_BOOK);
        multiplied(prices, "tuned_transmission", "TRANSMISSION_TUNER", TUNED_TRANSMISSION);
        multiplied(prices, "mana_disintegrator_count", "MANA_DISINTEGRATOR", MANA_DISINTEGRATOR);
        pulseRing(prices);
        rune(prices);
        potatoBooks(prices);
        if (truthy(ea.get("dye_item"))) price += price(prices, str(ea.get("dye_item")).toUpperCase(Locale.ROOT)) * DYE;
        multiplied(prices, "art_of_war_count", "THE_ART_OF_WAR", ART_OF_WAR);
        multiplied(prices, "artOfPeaceApplied", "THE_ART_OF_PEACE", ART_OF_PEACE);
        multiplied(prices, "farming_for_dummies_count", "FARMING_FOR_DUMMIES", FARMING_FOR_DUMMIES);
        multiplied(prices, "polarvoid", "POLARVOID_BOOK", POLARVOID_BOOK);
        if (num(ea.get("divan_powder_coating")) > 0) price += price(prices, "DIVAN_POWDER_COATING") * DIVAN_POWDER_COATING;
        enrichment(prices);
        recombobulator(prices);
        gemstones(prices);
        if (truthy(ea.get("power_ability_scroll"))) price += price(prices, str(ea.get("power_ability_scroll"))) * GEMSTONE_POWER_SCROLL;
        if (truthy(ea.get("modifier")) && !"ACCESSORY".equals(category())) {
            String reforge = REFORGES.get(str(ea.get("modifier")));
            if (reforge != null) price += price(prices, reforge) * REFORGE;
        }
        masterStars(prices);
        essenceStars(prices);
        abilityScrolls(prices);
        drillParts(prices);
        if (truthy(ea.get("ethermerge"))) price += price(prices, "ETHERWARP_CONDUIT") * ETHERWARP;
        newYearCakeBag(prices);
        soulboundSkin(prices);
        rodParts(prices);
        boosters(prices);
        multiplied(prices, "levelable_overclocks", "OVERCLOCKER_3000", OVERCLOCKER);
        double total = price + basePrice;
        return Double.isNaN(total) ? 0 : total;
    }

    private String category() {
        JsonElement c = skyblockItem.get("category");
        return c == null || c.isJsonNull() ? null : c.getAsString();
    }

    /** An "X applied N times" handler: the item's price times the count times its worth. */
    private void multiplied(Map<String, Double> prices, String key, String id, double worth) {
        double n = num(ea.get(key));
        if (n > 0) price += price(prices, id) * n * worth;
    }

    // ------------------------------------------------------------------------------------------------ helpers/SkyBlockItemNetworthHelper.js

    private boolean isRune() {
        Map<String, Object> runes = map(ea.get("runes"));
        return "RUNE".equals(baseItemId) && runes != null && !runes.isEmpty();
    }

    private boolean isUniqueRune() {
        Map<String, Object> runes = map(ea.get("runes"));
        return "UNIQUE_RUNE".equals(baseItemId) && runes != null && !runes.isEmpty();
    }

    private boolean isSoulbound() {
        return truthy(ea.get("donated_museum")) || lore.contains("§8§l* §8Co-op Soulbound §8§l*") || lore.contains("§8§l* §8Soulbound §8§l*");
    }

    private String customId(Map<String, Double> prices) {
        String id = baseItemId;
        if (id == null) return null;
        Object skin = ea.get("skin");
        if (truthy(skin)) {
            String skinned = id + "_SKINNED_" + str(skin);
            Double base = prices.get(id);
            if (has(prices, skinned) && base != null && price(prices, skinned) > base) return skinned;
        }
        if (id.equals("PARTY_HAT_SLOTH") && truthy(ea.get("party_hat_emoji"))) {
            String hat = id + "_" + str(ea.get("party_hat_emoji")).toUpperCase(Locale.ROOT);
            if (has(prices, hat)) return hat;
        }
        if (isUniqueRune() || isRune()) {
            Map.Entry<String, Object> rune = map(ea.get("runes")).entrySet().iterator().next();
            return ("RUNE_" + rune.getKey() + "_" + str(rune.getValue())).toUpperCase(Locale.ROOT);
        }
        if (id.equals("NEW_YEAR_CAKE")) return "NEW_YEAR_CAKE_" + str(ea.get("new_years_cake"));
        if (List.of("PARTY_HAT_CRAB", "PARTY_HAT_CRAB_ANIMATED", "BALLOON_HAT_2024", "BALLOON_HAT_2025").contains(id) && truthy(ea.get("party_hat_color"))) {
            return id + "_" + str(ea.get("party_hat_color")).toUpperCase(Locale.ROOT);
        }
        if (id.equals("DCTR_SPACE_HELM") && ea.get("edition") != null) return "DCTR_SPACE_HELM_EDITIONED";
        if (id.equals("CREATIVE_MIND") && !truthy(ea.get("edition"))) return "CREATIVE_MIND_UNEDITIONED";
        if (id.equals("ANCIENT_ELEVATOR") && ea.get("edition") != null) return "ANCIENT_ELEVATOR_EDITIONED";
        if (truthy(ea.get("is_shiny")) && has(prices, id + "_SHINY")) return id + "_SHINY";
        if (id.startsWith("STARRED_") && !has(prices, id) && has(prices, id.substring("STARRED_".length()))) return id.substring("STARRED_".length());
        return id;
    }

    // ------------------------------------------------------------------------------------------------ handlers

    private void pickonimbus() {
        double durability = num(ea.get("pickonimbus_durability"));
        if ("PICKONIMBUS".equals(itemId) && durability < 2000) price += basePrice * (durability / 2000 - 1);
    }

    private void prestige(Map<String, Double> prices, Map<String, JsonObject> items) {
        List<String> chain = itemId == null ? null : PRESTIGES.get(itemId);
        if (chain == null || has(prices, itemId)) return;
        for (String prestigeItem : chain) {
            JsonObject found = items.get(prestigeItem);
            if (found != null && found.has("upgrade_costs")) price += starCosts(prices, found.getAsJsonArray("upgrade_costs"), prestigeItem);
            if (found != null && found.has("prestige") && found.getAsJsonObject("prestige").has("costs")) {
                price += starCosts(prices, found.getAsJsonObject("prestige").getAsJsonArray("costs"), prestigeItem);
            }
            if (has(prices, prestigeItem)) {
                price += price(prices, prestigeItem);
                break;
            }
        }
    }

    private void shensAuction() {
        if (ea.get("price") == null || ea.get("auction") == null || ea.get("bid") == null) return;
        double paid = num(ea.get("price")) * SHENS_AUCTION;
        if (paid > basePrice) basePrice = paid;
    }

    private void midasWeapon(Map<String, Double> prices) {
        record Midas(double maxBid, String type) {}
        Midas midas = switch (itemId == null ? "" : itemId) {
            case "MIDAS_SWORD" -> new Midas(50_000_000, "MIDAS_SWORD_50M");
            case "STARRED_MIDAS_SWORD" -> new Midas(250_000_000, "STARRED_MIDAS_SWORD_250M");
            case "MIDAS_STAFF" -> new Midas(100_000_000, "MIDAS_STAFF_100M");
            case "STARRED_MIDAS_STAFF" -> new Midas(500_000_000, "STARRED_MIDAS_STAFF_500M");
            default -> null;
        };
        if (midas == null) return;
        double winning = ea.get("winning_bid") == null ? 0 : num(ea.get("winning_bid"));
        double extra = ea.get("additional_coins") == null ? 0 : num(ea.get("additional_coins"));
        if (winning + extra >= midas.maxBid() && has(prices, midas.type())) basePrice = price(prices, midas.type());
    }

    private void avariceCoins(Map<String, Double> prices) {
        double collected = num(ea.get("collected_coins"));
        if (!(collected > 0)) return;
        double zero = price(prices, "CROWN_OF_AVARICE"), billion = price(prices, "CROWN_OF_AVARICE_1B");
        basePrice = zero + (billion - zero) * (Math.min(collected, 1_000_000_000) / 1_000_000_000);
    }

    private void enchantedBook(Map<String, Double> prices) {
        Map<String, Object> enchants = map(ea.get("enchantments"));
        if (!"ENCHANTED_BOOK".equals(itemId) || enchants == null || enchants.isEmpty()) return;
        boolean single = enchants.size() == 1;
        double total = 0;
        for (Map.Entry<String, Object> e : enchants.entrySet()) {
            double p = price(prices, "ENCHANTMENT_" + e.getKey().toUpperCase(Locale.ROOT) + "_" + str(e.getValue()));
            if (p == 0) continue;
            total += p * (single ? 1 : ENCHANTMENTS);
        }
        if (total != 0) basePrice = total;
    }

    private void itemEnchantments(Map<String, Double> prices) {
        Map<String, Object> enchants = map(ea.get("enchantments"));
        if ("ENCHANTED_BOOK".equals(itemId) || enchants == null || enchants.isEmpty()) return;
        Set<String> appliedOnce = new HashSet<>();
        for (Map.Entry<String, Object> e : enchants.entrySet()) {
            String name = e.getKey().toUpperCase(Locale.ROOT);
            double value = num(e.getValue());
            List<String> blocked = itemId == null ? null : BLOCKED_ENCHANTMENTS.get(itemId);
            if (blocked != null && blocked.contains(name)) continue;
            Integer ignored = IGNORED_ENCHANTMENTS.get(name);
            if (ignored != null && ignored == value) continue;
            if (STACKING_ENCHANTMENTS.contains(name)) value = 1;
            if (name.equals("EFFICIENCY") && value >= 6 && !in(IGNORE_SILEX, itemId)) {
                double level = value - ("STONK_PICKAXE".equals(itemId) ? 6 : 5);
                if (level > 0) price += price(prices, "SIL_EX") * level * SILEX;
            }
            for (Upgrade up : ENCHANTMENT_UPGRADES.getOrDefault(name, List.of())) {
                if (value >= up.tier() && (!up.oncePerItem() || !appliedOnce.contains(up.item()))) {
                    price += price(prices, up.item()) * ENCHANTMENT_UPGRADE;
                    if (up.oncePerItem()) appliedOnce.add(up.item());
                }
            }
            String level = value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
            price += price(prices, "ENCHANTMENT_" + name + "_" + level) * ENCHANTMENTS_WORTH.getOrDefault(name, ENCHANTMENTS);
        }
    }

    private void pulseRing(Map<String, Double> prices) {
        double charge = num(ea.get("thunder_charge"));
        if (!"PULSE_RING".equals(itemId) || !(charge > 0)) return;
        double upgrades = Math.floor(Math.min(charge, 5_000_000) / 50_000);
        price += price(prices, "THUNDER_IN_A_BOTTLE") * upgrades * THUNDER_IN_A_BOTTLE;
    }

    private void rune(Map<String, Double> prices) {
        Map<String, Object> runes = map(ea.get("runes"));
        if (runes == null || runes.isEmpty() || itemId == null || itemId.startsWith("RUNE")) return;
        Map.Entry<String, Object> rune = runes.entrySet().iterator().next();
        String id = "RUNE_" + rune.getKey() + "_" + str(rune.getValue());
        if (has(prices, id)) price += price(prices, id) * RUNES;
    }

    private void potatoBooks(Map<String, Double> prices) {
        double books = num(ea.get("hot_potato_count"));
        if (!(books > 0)) return;
        if (books > 10) price += price(prices, "FUMING_POTATO_BOOK") * (books - 10) * FUMING_POTATO_BOOK;
        price += price(prices, "HOT_POTATO_BOOK") * Math.min(books, 10) * HOT_POTATO_BOOK;
    }

    private void enrichment(Map<String, Double> prices) {
        if (!truthy(ea.get("talisman_enrichment"))) return;
        double cheapest = Double.POSITIVE_INFINITY;
        for (String id : ENRICHMENTS) {
            Double p = prices.get(id);
            if (p != null) cheapest = Math.min(cheapest, p);
        }
        if (cheapest != Double.POSITIVE_INFINITY) price += cheapest * ENRICHMENT;
    }

    private void recombobulator(Map<String, Double> prices) {
        boolean recombobulated = num(ea.get("rarity_upgrades")) > 0 && !truthy(ea.get("item_tier"));
        if (!recombobulated) return;
        Map<String, Object> enchants = map(ea.get("enchantments"));
        boolean hasEnchants = enchants != null && !enchants.isEmpty();
        boolean allowed = in(RECOMB_CATEGORIES, category()) || in(RECOMB_IDS, itemId);
        String last = lore.isEmpty() ? "" : lore.getLast();
        boolean accessory = last.contains("ACCESSORY") || last.contains("HATCESSORY");
        if (!hasEnchants && !allowed && !accessory) return;
        price += price(prices, "RECOMBOBULATOR_3000") * ("BONE_BOOMERANG".equals(itemId) ? RECOMBOBULATOR * 0.5 : RECOMBOBULATOR);
    }

    private void gemstones(Map<String, Double> prices) {
        Map<String, Object> gemsData = map(ea.get("gems"));
        JsonArray slots = skyblockItem.has("gemstone_slots") && skyblockItem.get("gemstone_slots").isJsonArray() ? skyblockItem.getAsJsonArray("gemstone_slots") : null;
        if (gemsData == null || gemsData.isEmpty() || slots == null || slots.isEmpty()) return;
        record Gem(String type, String tier) {}
        List<String> unlocked = new ArrayList<>();
        List<Gem> gems = new ArrayList<>();
        Map<String, Object> extra = new LinkedHashMap<>(gemsData);
        List<Object> unlockedSlots = list(extra.get("unlocked_slots")) == null ? null : new ArrayList<>(list(extra.get("unlocked_slots")));
        if (unlockedSlots != null) extra.put("unlocked_slots", unlockedSlots);
        for (JsonElement el : slots) {
            JsonObject slot = el.getAsJsonObject();
            String slotType = slot.get("slot_type").getAsString();
            boolean costs = slot.has("costs") && !slot.get("costs").isJsonNull();
            List<Object> stillUnlocked = list(extra.get("unlocked_slots"));
            if (costs && stillUnlocked != null) {
                for (int i = 0; i < stillUnlocked.size(); i++) {
                    if (String.valueOf(stillUnlocked.get(i)).startsWith(slotType)) {
                        unlocked.add(slotType);
                        stillUnlocked.remove(i);
                        break;
                    }
                }
            }
            if (!costs) unlocked.add(slotType);
            String key = null;
            for (String k : extra.keySet()) {
                if (k.startsWith(slotType) && !k.endsWith("_gem")) {
                    key = k;
                    break;
                }
            }
            if (key != null) {
                String type = in(GEMSTONE_SLOTS, slotType) ? str(extra.get(key + "_gem")) : slotType;
                Object value = extra.get(key);
                Map<String, Object> asMap = map(value);
                String tier = asMap != null ? str(asMap.get("quality")) : str(value);
                gems.add(new Gem(type, tier));
                extra.remove(key);
                if (costs && extra.get("unlocked_slots") == null) unlocked.add(slotType);
            }
        }
        boolean divans = baseItemId != null && DIVANS.matcher(baseItemId).matches();
        boolean kuudra = baseItemId != null && KUUDRA_ARMOUR.matcher(baseItemId).matches();
        if (divans || kuudra) {
            double application = divans ? GEMSTONE_CHAMBERS : GEMSTONE_SLOTS_WORTH;
            List<JsonObject> remaining = new ArrayList<>();
            for (JsonElement el : slots) remaining.add(el.getAsJsonObject());
            for (String slotType : unlocked) {
                for (int i = 0; i < remaining.size(); i++) {
                    JsonObject slot = remaining.get(i);
                    if (!slot.get("slot_type").getAsString().equals(slotType)) continue;
                    double total = 0;
                    if (slot.has("costs") && slot.get("costs").isJsonArray()) {
                        for (JsonElement c : slot.getAsJsonArray("costs")) {
                            JsonObject cost = c.getAsJsonObject();
                            String type = cost.has("type") ? cost.get("type").getAsString() : "";
                            if (type.equals("COINS")) total += cost.get("coins").getAsDouble();
                            else if (type.equals("ITEM")) total += price(prices, cost.get("item_id").getAsString().toUpperCase(Locale.ROOT)) * cost.get("amount").getAsDouble();
                        }
                    }
                    price += total * application;
                    remaining.remove(i);
                    break;
                }
            }
        }
        for (Gem gem : gems) price += price(prices, (gem.tier() + "_" + gem.type() + "_GEM").toUpperCase(Locale.ROOT)) * GEMSTONE;
    }

    private int upgradeLevel() {
        return Math.max(digits(ea.get("dungeon_item_level")), digits(ea.get("upgrade_level")));
    }

    /** {@code parseInt(String(x).replace(/\D/g, ''))}, 0 for nothing. */
    private static int digits(Object o) {
        if (o == null) return 0;
        String d = str(o).replaceAll("\\D", "");
        if (d.isEmpty()) return 0;
        try {
            return Integer.parseInt(d.length() > 9 ? d.substring(0, 9) : d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void masterStars(Map<String, Double> prices) {
        if (!skyblockItem.has("upgrade_costs")) return;
        int level = upgradeLevel();
        if (level <= 5) return;
        int used = Math.min(level - 5, 5);
        if (skyblockItem.getAsJsonArray("upgrade_costs").size() <= 5) {
            for (int star = 0; star < used; star++) price += price(prices, MASTER_STARS[star]) * MASTER_STAR;
        }
    }

    private void essenceStars(Map<String, Double> prices) {
        JsonArray costs = skyblockItem.has("upgrade_costs") && skyblockItem.get("upgrade_costs").isJsonArray() ? skyblockItem.getAsJsonArray("upgrade_costs") : null;
        int level = upgradeLevel();
        if (costs == null || costs.isEmpty() || level <= 0) return;
        JsonArray used = new JsonArray();
        for (int i = 0; i < Math.min(level, costs.size()); i++) used.add(costs.get(i));
        price += starCosts(prices, used, null);
    }

    /** helper/essenceStars.js: one upgrade cost, or null when its item has no price. */
    private static Double starCost(Map<String, Double> prices, JsonObject upgrade) {
        boolean essence = upgrade.has("essence_type");
        String id = essence ? "ESSENCE_" + upgrade.get("essence_type").getAsString() : upgrade.has("item_id") ? upgrade.get("item_id").getAsString() : null;
        double unit = price(prices, id);
        if (unit == 0) return null;
        double amount = upgrade.has("amount") ? upgrade.get("amount").getAsDouble() : 0;
        return amount * unit * (essence ? ESSENCE : 1);
    }

    /** helper/essenceStars.js starCosts; with a prestige item, everything only counts when its first cost had a price. */
    static double starCosts(Map<String, Double> prices, JsonArray upgrades, String prestigeItem) {
        double total = 0;
        List<Double> costs = new ArrayList<>();
        for (JsonElement upgrade : upgrades) {
            if (upgrade.isJsonArray()) {
                for (JsonElement cost : upgrade.getAsJsonArray()) {
                    Double c = cost.isJsonObject() ? starCost(prices, cost.getAsJsonObject()) : null;
                    costs.add(c);
                    if (prestigeItem == null && c != null) total += c;
                }
            } else if (upgrade.isJsonObject()) {
                Double c = starCost(prices, upgrade.getAsJsonObject());
                costs.add(c);
                if (prestigeItem == null && c != null) total += c;
            }
        }
        if (prestigeItem != null && !costs.isEmpty() && costs.getFirst() != null) {
            for (Double c : costs) if (c != null) total += c;
        }
        return total;
    }

    private void abilityScrolls(Map<String, Double> prices) {
        List<Object> scrolls = list(ea.get("ability_scroll"));
        if (scrolls == null) return;
        for (Object id : scrolls) price += price(prices, str(id).toUpperCase(Locale.ROOT)) * ABILITY_SCROLL;
    }

    private void drillParts(Map<String, Double> prices) {
        for (String type : DRILL_PARTS) {
            if (truthy(ea.get(type))) price += price(prices, str(ea.get(type)).toUpperCase(Locale.ROOT)) * DRILL_PART;
        }
    }

    private void newYearCakeBag(Map<String, Double> prices) {
        Object data = ea.get("new_year_cake_bag_data");
        if (!(data instanceof byte[] bytes)) return;
        Object list = Nbt.decode(bytes).get("i");
        List<Object> cakes = list(list);
        if (cakes == null) return;
        double total = 0;
        boolean any = false;
        for (Object cake : cakes) {
            Map<String, Object> c = map(cake);
            Map<String, Object> tag = c == null ? null : map(c.get("tag"));
            Map<String, Object> extra = tag == null ? null : map(tag.get("ExtraAttributes"));
            if (c == null || c.get("id") == null || extra == null || !truthy(extra.get("new_years_cake"))) continue;
            any = true;
            total += price(prices, "NEW_YEAR_CAKE_" + str(extra.get("new_years_cake")));
        }
        if (any) price += total;
    }

    private void soulboundSkin(Map<String, Double> prices) {
        String skin = str(ea.get("skin"));
        if (skin == null || skin.isEmpty() || itemId == null || itemId.contains(skin) || !isSoulbound() || !has(prices, skin)) return;
        price += price(prices, skin) * SOULBOUND_SKINS;
    }

    private void rodParts(Map<String, Double> prices) {
        for (String type : ROD_PARTS) {
            Map<String, Object> part = map(ea.get(type));
            if (part != null && truthy(part.get("part"))) price += price(prices, str(part.get("part")).toUpperCase(Locale.ROOT)) * ROD_PART;
        }
    }

    private void boosters(Map<String, Double> prices) {
        Map<String, Object> tiers = map(ea.get("booster_tiers"));
        if (tiers != null && !tiers.isEmpty()) {
            for (Map.Entry<String, Object> e : tiers.entrySet()) {
                String booster = e.getKey().toUpperCase(Locale.ROOT);
                int tier = (int) num(e.getValue());
                List<String> ids = new ArrayList<>();
                ids.add(booster + "_BOOSTER");
                // TIERS.at(t + 1) past the end is "undefined" in JavaScript, which has no price.
                for (int t = 0; t < tier - 1 && t + 1 < TIERS.size(); t++) ids.add(booster + "_BOOSTER_" + TIERS.get(t + 1));
                for (String id : ids) {
                    double p = price(prices, id);
                    if (p == 0 && id.endsWith("_BOOSTER")) p = price(prices, id + "_COMMON");
                    if (p != 0) price += p * BOOSTER;
                }
            }
            return;
        }
        List<Object> boosters = list(ea.get("boosters"));
        if (boosters == null) return;
        for (Object b : boosters) {
            double p = price(prices, str(b).toUpperCase(Locale.ROOT) + "_BOOSTER");
            if (p != 0) price += p * BOOSTER;
        }
    }

    /** The item's name without colour codes, for the networth breakdown. */
    static String name(Map<String, Object> itemData) {
        Map<String, Object> tag = map(itemData.get("tag"));
        Map<String, Object> display = tag == null ? null : map(tag.get("display"));
        return display == null ? "" : str(display.get("Name")).replaceAll("§[0-9a-fk-or]", "");
    }

    static String titleCaseId(String id) {
        return titleCase(id);
    }
}
