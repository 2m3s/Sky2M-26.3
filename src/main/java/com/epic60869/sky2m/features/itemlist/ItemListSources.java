package com.epic60869.sky2m.features.itemlist;

import com.epic60869.sky2m.features.tracker.RngDropList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Where to get it" for an item in the item list (asked for by a player, 2026-11-04: "NPCs, Dungeon Floors, Kuudra
 * Tiers, so that it shows me how and where I get it"). One short line per source, from data Sky2M already has:
 * <ul>
 * <li>the RNG drop list ({@link RngDropList}, the RNG Meter tables): "Catacombs Floor 7", "Master Mode Floor 7", a slayer
 *     boss, Kuudra, Diana, fishing and the other big rare drops;</li>
 * <li>who sells it and who drops it, from the item list's own NPC shop and mob drop recipes;</li>
 * <li>what crafting it needs unlocked, from the NEU repo ("Requires Wheat VIII").</li>
 * </ul>
 * Kuudra's tiers and ordinary (non-RNG) dungeon chest loot aren't in any table Sky2M has, so they aren't listed.
 */
public final class ItemListSources {
    private ItemListSources() {}

    /** The source lines for {@code id}, in Minecraft colour codes; empty when nothing is known. */
    public static List<String> of(String id) {
        Set<String> out = new LinkedHashSet<>();
        ItemListData.Entry entry = ItemListData.entry(id);
        String name = entry == null ? null : ItemListData.strip(entry.name()).trim();
        for (RngDropList.Category c : RngDropList.containing(name)) out.add(line(c));
        Set<String> sellers = new LinkedHashSet<>(), droppers = new LinkedHashSet<>();
        for (ItemListData.Recipe r : ItemListData.recipes(id)) {
            if (r instanceof ItemListData.Shop) sellers.add(ownerName(r.owner()));
            else if (r instanceof ItemListData.Drops d) droppers.add(d.name() != null && !d.name().isBlank() ? ItemListData.strip(d.name()) : ownerName(r.owner()));
        }
        if (!sellers.isEmpty()) out.add("§7Sold by §f" + join(sellers));
        if (!droppers.isEmpty()) out.add("§7Dropped by §f" + join(droppers));
        String requires = ItemListData.craftText(id);
        if (requires != null) out.add("§7" + ItemListData.strip(requires));
        return new ArrayList<>(out);
    }

    /** "§6Catacombs Floor 7 §8· RNG drop", "§6Master Mode Floor 1", "§cRevenant Horror §8· slayer", "§6Kuudra". */
    public static String line(RngDropList.Category c) {
        return switch (c.group()) {
            case "Catacombs" -> "§6Catacombs " + c.name() + " §8· RNG drop";
            case "Master Mode" -> "§cMaster Mode " + c.name().replace("Master ", "Floor ") + " §8· RNG drop";
            case "Slayer" -> "§c" + c.name() + " §8· slayer RNG drop";
            default -> "§6" + c.name();
        };
    }

    private static String ownerName(String owner) {
        ItemListData.Entry e = owner == null ? null : ItemListData.entry(owner);
        return e == null ? String.valueOf(owner) : ItemListData.strip(e.name()).trim();
    }

    /** "A, B and 3 more" past three, so one line stays one line. */
    private static String join(Set<String> names) {
        List<String> list = new ArrayList<>(names);
        if (list.size() <= 3) return String.join(", ", list);
        return String.join(", ", list.subList(0, 3)) + " and " + (list.size() - 3) + " more";
    }
}
