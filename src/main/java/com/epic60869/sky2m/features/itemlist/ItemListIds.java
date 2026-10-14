package com.epic60869.sky2m.features.itemlist;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * The Item List id (the NotEnoughUpdates-REPO's) of any item: the list's own stacks carry it, and real SkyBlock items are
 * turned into it, since pets ({@code ENDER_DRAGON;4}), enchanted books ({@code SHARPNESS;5}), runes and items with a
 * {@code :} in their Hypixel id are named differently there.
 */
public final class ItemListIds {
    private static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");

    private ItemListIds() {}

    /** The id of the list entry for this item, or null when it has none. */
    public static String of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String id = Compat.neuName(stack);
        if (id.isEmpty()) return null;
        if (ItemListData.entry(id) != null) return id;
        CompoundTag data = Compat.customDataView(stack);
        String converted = switch (id) {
            case "PET" -> pet(data);
            case "ENCHANTED_BOOK" -> book(data);
            case "RUNE", "UNIQUE_RUNE" -> rune(data);
            default -> id.replace(':', '-');
        };
        return converted != null && ItemListData.entry(converted) != null ? converted : null;
    }

    private static String pet(CompoundTag data) {
        try {
            JsonObject info = JsonParser.parseString(data.getStringOr("petInfo", "{}")).getAsJsonObject();
            if (!info.has("type") || !info.has("tier")) return null;
            int rarity = RARITIES.indexOf(info.get("tier").getAsString().toUpperCase(Locale.ROOT));
            return rarity < 0 ? null : info.get("type").getAsString() + ";" + rarity;
        } catch (Exception e) {
            return null;
        }
    }

    private static String book(CompoundTag data) {
        CompoundTag enchants = data.getCompoundOrEmpty("enchantments");
        if (enchants.size() != 1) return null;
        for (String enchant : enchants.keySet()) return enchant.toUpperCase(Locale.ROOT) + ";" + enchants.getIntOr(enchant, 1);
        return null;
    }

    private static String rune(CompoundTag data) {
        CompoundTag runes = data.getCompoundOrEmpty("runes");
        for (String rune : runes.keySet()) return rune.toUpperCase(Locale.ROOT) + "_RUNE;" + runes.getIntOr(rune, 1);
        return null;
    }
}
