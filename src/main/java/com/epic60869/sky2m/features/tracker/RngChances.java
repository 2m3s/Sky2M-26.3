package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.itemlist.ItemListData;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drop chance shown next to each drop in /s2 rnglist, per category (asked for, 2026-11-04).
 *
 * <ul>
 * <li>Catacombs and Master Mode floors: the floor boss's chest table in the NEU repo ("Necron" / "Necron (Master Mode)"),
 *     Necron's Handle 0.1% on Floor 7 and 0.13% on Master 7.</li>
 * <li>Slayers: the highest tier's table ("Revenant Horror V"), with the tier: "0.0136% · T5".</li>
 * <li>Kuudra: the wiki's Infernal paid chest chances ({@link #KUUDRA}); the repo has no Kuudra table.</li>
 * <li>Everything else (Diana, fishing, pests ...): the best chance any mob's table gives it, and which mob.</li>
 * </ul>
 * These are base chances, before Magic Find, the RNG Meter or boss luck. The repo tables come from the Item List's NEU
 * download ({@link ItemListData}), so with the Item List off (nothing downloaded) only Kuudra's show. Null when unknown.
 */
public final class RngChances {
    /** Floor N's boss, as its chest table is named. */
    private static final String[] FLOOR_BOSS = {"", "Bonzo", "Scarf", "Professor", "Thorn", "Livid", "Sadan", "Necron"};
    /**
     * Kuudra's Infernal paid chest, from each item's wiki page (read 2026-11-04). "~" marks the ones only read off the
     * Kuudra page's loot tables, which it calls outdated.
     */
    static final Map<String, String> KUUDRA = Map.of(
        "Tentacle Dye", "0.005%", "Tormentor", "0.1%", "Hellstorm Wand", "0.1%", "Ananke Feather", "0.27%",
        "Burning Kuudra Core", "~0.51%", "Wheel of Fate", "~2.5%", "Hollow Wand", "~1%", "Fatal Tempo I", "~0.2%",
        "Inferno I", "~0.2%");

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private RngChances() {}

    /** "0.1%", "0.0136% · T5", "0.5% · Lord Jawbus", or null when no table has it. */
    public static String of(RngDropList.Category c, String item) {
        if (c.name().equals("Kuudra")) return KUUDRA.get(item);
        if (!ItemListData.loaded()) return null;
        return CACHE.computeIfAbsent(c.name() + "|" + item, k -> {
            String found = lookup(c, item);
            return found == null ? "" : found;
        }).transform(s -> s.isEmpty() ? null : s);
    }

    private static String lookup(RngDropList.Category c, String item) {
        String id = RepoItems.idByName(item);
        if (id == null) return null;
        List<ItemListData.Recipe> recipes = ItemListData.recipes(id);
        String best = null, bestFrom = null;
        double bestValue = -1;
        int bestTier = -1;
        for (ItemListData.Recipe r : recipes) {
            if (!(r instanceof ItemListData.Drops d)) continue;
            String table = ItemListData.strip(d.name() == null ? "" : d.name()).trim();
            String chance = chanceOf(d, id);
            if (chance == null) continue;
            switch (c.group()) {
                case "Catacombs", "Master Mode" -> {
                    int floor = floor(c.name());
                    boolean master = c.group().equals("Master Mode");
                    if (floor < 1 || !table.contains(FLOOR_BOSS[floor]) || table.contains("Master Mode") != master) continue;
                    return chance;
                }
                case "Slayer" -> {
                    if (!table.startsWith(c.name())) continue;
                    int tier = tier(table);
                    if (tier > bestTier) {
                        bestTier = tier;
                        best = chance + (tier > 0 ? " · T" + tier : "");
                    }
                }
                default -> {
                    double value = value(chance);
                    if (value > bestValue) {
                        bestValue = value;
                        best = chance;
                        bestFrom = table;
                    }
                }
            }
        }
        if (bestFrom != null && !bestFrom.isEmpty()) return best + " · " + bestFrom;
        return best;
    }

    /** The chance this table gives {@code id} ("0.1%"), or null. */
    private static String chanceOf(ItemListData.Drops d, String id) {
        for (ItemListData.Drop drop : d.drops()) {
            if (drop.item() != null && id.equals(drop.item().id()) && drop.chance() != null && !drop.chance().isBlank()) {
                return drop.chance().trim();
            }
        }
        return null;
    }

    /** "Floor 7" / "Master 7" -> 7. */
    private static int floor(String name) {
        char last = name.charAt(name.length() - 1);
        return Character.isDigit(last) ? last - '0' : -1;
    }

    /** The roman tier at the end of a table name ("Revenant Horror V" -> 5), 0 without one. */
    private static int tier(String table) {
        String[] parts = table.split("\\s+");
        return switch (parts[parts.length - 1]) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            default -> 0;
        };
    }

    /** "0.0136%" -> 0.0136, for comparing; -1 when it isn't a percentage. */
    private static double value(String chance) {
        try {
            return Double.parseDouble(chance.replace("%", "").replace("~", "").replace(",", ".").trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
