package com.epic60869.sky2m.features.profileviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sky2M's own Mining Weight and Farming Weight, in the spirit of Coleweight and Elite's farming weight but with Sky2M's
 * own numbers: worked out locally from the profile data /pv already has. Each part is "1 weight per N", where N is
 * roughly what a well-geared player gets in a few minutes, so a weight point means about the same effort whatever you
 * mine or farm. The divisors are Sky2M's and may be tuned; the breakdown shows exactly what each part counts.
 */
public final class Sky2MWeight {
    /** One counted thing: {@code amount} of it, worth {@code weight}. */
    public record Part(String name, double amount, double weight) {}

    public record Result(double total, List<Part> parts) {}

    /** {collection id, name, amount per 1 weight}. */
    private static final Object[][] MINING = {
        {"MITHRIL_ORE", "Mithril", 20_000}, {"TITANIUM_ORE", "Titanium", 2_000}, {"GEMSTONE_COLLECTION", "Gemstone", 200_000},
        {"HARD_STONE", "Hard Stone", 500_000}, {"GLACITE", "Glacite", 100_000}, {"TUNGSTEN", "Tungsten", 50_000},
        {"UMBER", "Umber", 50_000}, {"COBBLESTONE", "Cobblestone", 500_000}, {"COAL", "Coal", 200_000},
        {"IRON_INGOT", "Iron", 200_000}, {"GOLD_INGOT", "Gold", 200_000}, {"DIAMOND", "Diamond", 100_000},
        {"INK_SACK:4", "Lapis Lazuli", 500_000}, {"EMERALD", "Emerald", 100_000}, {"REDSTONE", "Redstone", 500_000},
        {"QUARTZ", "Nether Quartz", 200_000}, {"OBSIDIAN", "Obsidian", 100_000}, {"NETHERRACK", "Netherrack", 500_000},
        {"ENDER_STONE", "End Stone", 500_000}, {"MYCEL", "Mycelium", 200_000}, {"SAND:1", "Red Sand", 200_000},
        {"SULPHUR", "Sulphur", 100_000}};
    /** {powder, amount of all-time powder per 1 weight}. */
    private static final Object[][] POWDER = {{"mithril", 200_000}, {"gemstone", 400_000}, {"glacite", 200_000}};

    private static final Object[][] FARMING = {
        {"WHEAT", "Wheat", 100_000}, {"CARROT_ITEM", "Carrot", 300_000}, {"POTATO_ITEM", "Potato", 300_000},
        {"PUMPKIN", "Pumpkin", 100_000}, {"MELON", "Melon", 500_000}, {"MUSHROOM_COLLECTION", "Mushroom", 90_000},
        {"INK_SACK:3", "Cocoa Beans", 270_000}, {"CACTUS", "Cactus", 180_000}, {"SUGAR_CANE", "Sugar Cane", 200_000},
        {"NETHER_STALK", "Nether Wart", 250_000}, {"DOUBLE_PLANT", "Sunflower", 200_000}, {"MOONFLOWER", "Moonflower", 200_000},
        {"WILD_ROSE", "Wild Rose", 200_000}};
    /** Farming XP per 1 weight (about 55 at Farming 60). */
    private static final double FARMING_XP_PER_WEIGHT = 2_000_000;
    /** Jacob's Contests: the best unique bracket on each crop. */
    private static final Map<String, Integer> BRACKET_WEIGHT = Map.of("gold", 10, "platinum", 20, "diamond", 30);

    private Sky2MWeight() {}

    /** Mining Weight for one profile member: mining collections plus all-time powder. */
    public static Result mining(JsonObject member) {
        List<Part> parts = new ArrayList<>();
        JsonObject collection = Json.obj(member, "collection");
        for (Object[] c : MINING) add(parts, (String) c[1], Json.num(collection, (String) c[0]), (Integer) c[2]);
        JsonObject core = Json.obj(member, "mining_core");
        for (Object[] p : POWDER) {
            String powder = (String) p[0];
            add(parts, Character.toUpperCase(powder.charAt(0)) + powder.substring(1) + " Powder", Json.num(core, "powder_" + powder), (Integer) p[1]);
        }
        return result(parts);
    }

    /** Farming Weight for one profile member: crop collections, Farming XP and Jacob's Contest brackets. */
    public static Result farming(JsonObject member) {
        List<Part> parts = new ArrayList<>();
        JsonObject collection = Json.obj(member, "collection");
        for (Object[] c : FARMING) add(parts, (String) c[1], Json.num(collection, (String) c[0]), (Integer) c[2]);
        double xp = Json.num(Json.path(member, "player_data.experience"), "SKILL_FARMING");
        if (xp > 0) parts.add(new Part("Farming XP", xp, xp / FARMING_XP_PER_WEIGHT));
        JsonObject brackets = Json.path(member, "jacobs_contest.unique_brackets");
        if (brackets != null) {
            Map<String, Integer> best = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> medal : BRACKET_WEIGHT.entrySet()) {
                JsonArray crops = Json.arr(brackets, medal.getKey());
                for (JsonElement crop : crops) best.merge(crop.getAsString(), medal.getValue(), Math::max);
            }
            int sum = best.values().stream().mapToInt(Integer::intValue).sum();
            if (sum > 0) parts.add(new Part("Jacob's Contest brackets", best.size(), sum));
        }
        return result(parts);
    }

    private static void add(List<Part> parts, String name, double amount, double perWeight) {
        if (amount > 0) parts.add(new Part(name, amount, amount / perWeight));
    }

    private static Result result(List<Part> parts) {
        parts.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        double total = 0;
        for (Part p : parts) total += p.weight();
        return new Result(total, parts);
    }
}
