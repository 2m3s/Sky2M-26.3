package com.epic60869.sky2m.features.profileviewer;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hypixel stores inventories as old (1.8) item NBT, base64 and gzipped. Each item is shown with its SkyBlock icon (from
 * the item list by its SkyBlock id, its own skull texture, or its pet), and its name and lore exactly as Hypixel wrote
 * them for the tooltip.
 */
public final class ProfileItems {
    /** An item to draw, and its tooltip. */
    public record PvItem(ItemStack stack, List<Component> tooltip) {}

    private static final Map<String, List<PvItem>> DECODED = new ConcurrentHashMap<>();
    private static final java.util.regex.Pattern RUNE_TIER = java.util.regex.Pattern.compile("(.+_RUNE)_(I{1,3})");
    private static final java.util.regex.Pattern SACK_RUNE = java.util.regex.Pattern.compile("RUNE_(.+)_([1-3])");

    private ProfileItems() {}

    /** The items in an inventory blob ({"type": 0, "data": "..."}), with null for empty slots. */
    public static List<PvItem> decode(JsonObject blob) {
        if (blob == null || !blob.has("data")) return List.of();
        String data = blob.get("data").getAsString();
        return DECODED.computeIfAbsent(data, ProfileItems::decode);
    }

    private static List<PvItem> decode(String data) {
        List<PvItem> out = new ArrayList<>();
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(Base64.getDecoder().decode(data)), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            for (int i = 0; i < list.size(); i++) out.add(item(list.getCompoundOrEmpty(i)));
        } catch (Exception e) {
            System.err.println("[Sky2M] Profile Viewer couldn't read an inventory: " + e.getMessage());
        }
        return out;
    }

    private static PvItem item(CompoundTag nbt) {
        if (nbt.isEmpty() || !nbt.contains("id")) return null;
        CompoundTag tag = nbt.getCompoundOrEmpty("tag");
        CompoundTag display = tag.getCompoundOrEmpty("display");
        CompoundTag extra = tag.getCompoundOrEmpty("ExtraAttributes");
        String id = extra.getStringOr("id", "");

        ItemStack stack = null;
        if (id.equals("PET")) {
            try {
                JsonObject info = JsonParser.parseString(extra.getStringOr("petInfo", "{}")).getAsJsonObject();
                stack = known(Json.str(info, "type") + ";" + ProfileStats.rarityIndex(Json.str(info, "tier")));
            } catch (Exception ignored) {}
        }
        // Heads keep their own texture (each one can differ, like custom-textured armour).
        String texture = skullTexture(tag);
        if (stack == null && texture != null && (id.isEmpty() || known(id) == null || known(id).is(Items.PLAYER_HEAD))) stack = Compat.createSkull(texture);
        if (stack == null && !id.isEmpty()) stack = known(id);
        if (stack == null) stack = texture != null ? Compat.createSkull(texture) : new ItemStack(Items.PAPER);
        else stack = stack.copy();

        stack.setCount(Math.max(1, nbt.getByteOr("Count", (byte) 1)));
        if (display.contains("color")) stack.set(DataComponents.DYED_COLOR, new DyedItemColor(display.getIntOr("color", 0)));
        if (tag.contains("ench") || !extra.getCompoundOrEmpty("enchantments").isEmpty()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        List<Component> tooltip = new ArrayList<>();
        String name = display.getStringOr("Name", "");
        tooltip.add(Component.literal(name.isEmpty() ? stack.getHoverName().getString() : name));
        ListTag lore = display.getListOrEmpty("Lore");
        for (int i = 0; i < lore.size(); i++) tooltip.add(Component.literal(lore.getStringOr(i, "")));
        stack.set(DataComponents.CUSTOM_NAME, tooltip.getFirst());
        // Hypixel's SkyBlock data, as on a real item, so prices and Estimated Item Value work on it (networth).
        if (!extra.isEmpty()) stack.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(extra.copy()));
        return new PvItem(stack, tooltip);
    }

    /**
     * An icon for a SkyBlock id: NEU's item (pet heads, legacy ids like {@code LOG:2} as {@code LOG-2}), else Hypixel's
     * item list, else a barrier.
     */
    public static ItemStack icon(String id) {
        if (id == null || id.isEmpty()) return new ItemStack(Items.BARRIER);
        // Runes come as "RUNE_HEARTS_1" (sacks) or "BITE_RUNE_I"; the NEU repo calls them "HEARTS_RUNE;1".
        String runeBase = null;
        java.util.regex.Matcher m = SACK_RUNE.matcher(id);
        if (m.matches()) {
            runeBase = m.group(1) + "_RUNE";
            id = runeBase + ";" + m.group(2);
        } else if ((m = RUNE_TIER.matcher(id)).matches()) {
            runeBase = m.group(1);
            id = runeBase + ";" + m.group(2).length();
        }
        var entry = com.epic60869.sky2m.features.itemlist.ItemListData.entry(id.replace(':', '-'));
        if (entry == null) entry = com.epic60869.sky2m.features.itemlist.ItemListData.entry(id);
        // Some runes are only in the repo at one tier: any tier's head is the same.
        for (int tier = 3; entry == null && runeBase != null && tier >= 1; tier--)
            entry = com.epic60869.sky2m.features.itemlist.ItemListData.entry(runeBase + ";" + tier);
        if (entry != null) return com.epic60869.sky2m.features.itemlist.ItemListStacks.stack(entry);
        ItemStack stack = RepoItems.itemStack(id);
        if (stack.is(Items.BARRIER) && runeBase != null) stack = RepoItems.itemStack(runeBase);
        // A rune neither list knows (Pestilence): another rune's head, named after its own id below.
        if (stack.is(Items.BARRIER) && runeBase != null) {
            for (var any : com.epic60869.sky2m.features.itemlist.ItemListData.entries()) {
                if (!any.id().endsWith("_RUNE;1") || any.texture() == null) continue;
                stack = com.epic60869.sky2m.features.itemlist.ItemListStacks.stack(any).copy();
                stack.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
                stack.remove(net.minecraft.core.component.DataComponents.LORE);
                break;
            }
        }
        // Unknown to both lists: at least name it after its id ("MANTIS_SHRIMP_SHARD" -> "Mantis Shrimp Shard").
        if ((stack.is(Items.BARRIER) || runeBase != null) && !stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) {
            StringBuilder name = new StringBuilder();
            for (String word : id.split("[_:;-]")) {
                if (word.isEmpty() || word.chars().allMatch(Character::isDigit)) continue;
                if (!name.isEmpty()) name.append(' ');
                name.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name.toString()));
        }
        return stack;
    }

    /** The item list's stack for a SkyBlock id, or null if it doesn't know the id. */
    private static ItemStack known(String id) {
        if (id == null || id.isEmpty()) return null;
        ItemStack stack = icon(id);
        return stack.is(Items.BARRIER) ? null : stack;
    }

    private static String skullTexture(CompoundTag tag) {
        ListTag textures = tag.getCompoundOrEmpty("SkullOwner").getCompoundOrEmpty("Properties").getListOrEmpty("textures");
        if (textures.isEmpty()) return null;
        String value = textures.getCompoundOrEmpty(0).getStringOr("Value", "");
        return value.isEmpty() ? null : value;
    }

    /** The blobs of an inventory map like backpack_contents, in key order (0, 1, 2...). */
    public static List<List<PvItem>> numbered(JsonObject map) {
        List<List<PvItem>> out = new ArrayList<>();
        if (map == null) return out;
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(map.entrySet());
        entries.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey()));
            } catch (NumberFormatException e) {
                return a.getKey().compareTo(b.getKey());
            }
        });
        for (Map.Entry<String, JsonElement> e : entries) if (e.getValue().isJsonObject()) out.add(decode(e.getValue().getAsJsonObject()));
        return out;
    }
}
