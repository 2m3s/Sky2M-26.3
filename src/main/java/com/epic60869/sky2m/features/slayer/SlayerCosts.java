package com.epic60869.sky2m.features.slayer;

import java.util.Map;

/**
 * What a slayer quest costs, for the boss profit: always the wiki's price for the slayer and tier, whatever was
 * actually paid (discounts such as Aatrox's half price aren't taken into account). Riftstalker Bloodfiend quests cost
 * motes, not coins, so they cost 0 here. A boss this doesn't know falls back to the cost seen being paid.
 */
public final class SlayerCosts {
    /** Coins per tier (I, II, ...), from the Hypixel SkyBlock wiki. */
    private static final Map<String, long[]> WIKI = Map.of(
        "Revenant Horror", new long[]{2_000, 7_500, 20_000, 50_000, 100_000},
        "Tarantula Broodfather", new long[]{2_000, 7_500, 20_000, 50_000, 100_000},
        "Sven Packmaster", new long[]{2_000, 7_500, 20_000, 50_000},
        "Voidgloom Seraph", new long[]{2_000, 7_500, 20_000, 50_000},
        "Inferno Demonlord", new long[]{10_000, 25_000, 60_000, 150_000});

    private SlayerCosts() {}

    /**
     * The coins a quest for {@code boss} ("Tarantula Broodfather V", "Conjoined Brood"...) costs: the wiki's price, or
     * {@code seen} (the cost seen being paid, -1 if none) for a boss this doesn't know.
     */
    public static long price(String boss, long seen) {
        String slayer = slayer(boss);
        if ("Riftstalker Bloodfiend".equals(slayer)) return 0;
        long[] tiers = slayer == null ? null : WIKI.get(slayer);
        int tier = tier(boss);
        if (tiers == null || tier < 1 || tier > tiers.length) return seen;
        return tiers[tier - 1];
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
}
