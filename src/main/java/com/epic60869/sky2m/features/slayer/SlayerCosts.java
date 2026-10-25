package com.epic60869.sky2m.features.slayer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * What a slayer quest costs, for the boss profit. The wiki's price for each slayer and tier is the anchor: a cost seen
 * being paid (Hypixel's bank charge, or the purse dropping) counts when it's that price or a known discount of it
 * (4% off, Aatrox's half price, or both), snapped to the exact amount and remembered for that tier
 * (config/sky2m/slayer-costs.json). When nothing was seen, the last price paid for the tier is used, else the wiki's.
 * Riftstalker Bloodfiend quests cost motes, not coins, so they cost 0 here.
 */
public final class SlayerCosts {
    /** Coins per tier (I, II, ...), from the Hypixel SkyBlock wiki. */
    private static final Map<String, long[]> WIKI = Map.of(
        "Revenant Horror", new long[]{2_000, 7_500, 20_000, 50_000, 100_000},
        "Tarantula Broodfather", new long[]{2_000, 7_500, 20_000, 50_000, 100_000},
        "Sven Packmaster", new long[]{2_000, 7_500, 20_000, 50_000},
        "Voidgloom Seraph", new long[]{2_000, 7_500, 20_000, 50_000},
        "Inferno Demonlord", new long[]{10_000, 25_000, 60_000, 150_000});
    /** Full price, 4% off, Aatrox's Slashed Pricing (half), and both. */
    private static final double[] DISCOUNTS = {1.0, 0.96, 0.5, 0.48};
    /** How far a purse change may be from a price (mob kill coins coming in) and still be it. */
    private static final double TOLERANCE = 0.03;

    private static final Map<String, Long> paid = new HashMap<>();
    private static boolean loaded;

    private SlayerCosts() {}

    /**
     * The coins a quest for {@code boss} ("Tarantula Broodfather V", "Conjoined Brood"...) cost, given the cost seen
     * being paid ({@code seen}, -1 if none). -1 only for a boss this doesn't know.
     */
    public static long price(String boss, long seen) {
        String slayer = slayer(boss);
        int tier = tier(boss);
        if ("Riftstalker Bloodfiend".equals(slayer)) return 0;
        long[] tiers = slayer == null ? null : WIKI.get(slayer);
        if (tiers == null || tier < 1 || tier > tiers.length) return seen;
        long wiki = tiers[tier - 1];
        String key = slayer + " " + tier;
        load();
        if (seen > 0) {
            for (double discount : DISCOUNTS) {
                long price = Math.round(wiki * discount);
                if (Math.abs(seen - price) <= price * TOLERANCE) {
                    if (!Long.valueOf(price).equals(paid.put(key, price))) save();
                    return price;
                }
            }
        }
        return paid.getOrDefault(key, wiki);
    }

    /** The slayer a boss belongs to; Tier 5's second phases have their own names. */
    private static String slayer(String boss) {
        if (boss == null) return null;
        if (boss.startsWith("Atoned Horror")) return "Revenant Horror";
        if (boss.startsWith("Conjoined Brood")) return "Tarantula Broodfather";
        for (String slayer : WIKI.keySet()) if (boss.startsWith(slayer)) return slayer;
        return boss.startsWith("Riftstalker Bloodfiend") ? "Riftstalker Bloodfiend" : null;
    }

    private static int tier(String boss) {
        if (boss.startsWith("Atoned Horror") || boss.startsWith("Conjoined Brood")) return 5;
        String roman = boss.matches(".* [IVX]+$") ? boss.substring(boss.lastIndexOf(' ') + 1) : "";
        return switch (roman) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> 0;
        };
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("slayer-costs.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            JsonObject root = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            for (var entry : root.entrySet()) paid.put(entry.getKey(), entry.getValue().getAsLong());
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read slayer-costs.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject root = new JsonObject();
            paid.forEach(root::addProperty);
            Files.writeString(file(), root.toString());
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save slayer-costs.json: " + e.getMessage());
        }
    }
}
