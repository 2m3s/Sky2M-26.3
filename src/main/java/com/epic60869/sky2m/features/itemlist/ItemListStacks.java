package com.epic60869.sky2m.features.itemlist;

import com.epic60869.sky2m.custom.LegacyMaterials;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Item List's entries as item stacks: the item, its head skin or Hypixel model, its name and lore, and its SkyBlock
 * id, so prices and Sky2M' other tooltips show on them like on real items. Built once per entry, on the game thread.
 */
public final class ItemListStacks {
    private static final Map<String, ItemStack> CACHE = new HashMap<>();
    private static Object cachedFor;

    private ItemListStacks() {}

    public static ItemStack stack(ItemListData.Entry entry) {
        if (entry == null) return ItemStack.EMPTY;
        // A reloaded list makes new entries: build the stacks again.
        List<ItemListData.Entry> current = ItemListData.entries();
        if (cachedFor != current) {
            CACHE.clear();
            cachedFor = current;
        }
        return CACHE.computeIfAbsent(entry.id(), id -> build(entry));
    }

    /** The stack for an id that may not be in the list (a coin, an unknown item): its name at least. */
    public static ItemStack stack(String id) {
        ItemListData.Entry entry = ItemListData.entry(id);
        if (entry != null) return stack(entry);
        if ("SKYBLOCK_COIN".equals(id)) {
            ItemStack coin = new ItemStack(Items.GOLD_NUGGET);
            coin.set(DataComponents.CUSTOM_NAME, Component.literal("§6Coins"));
            return coin;
        }
        return RepoItems.itemStack(id);
    }

    private static ItemStack build(ItemListData.Entry entry) {
        ItemStack stack;
        if (entry.texture() != null) {
            stack = Compat.createSkull(entry.texture());
        } else {
            stack = new ItemStack(item(entry.itemId(), entry.damage()));
        }
        if (entry.itemModel() != null) {
            Identifier model = Identifier.tryParse(entry.itemModel());
            // Hypixel's models only exist while its resource pack is loaded; the plain item is shown otherwise.
            if (model != null && RepoItems.hasItemModel(model)) stack.set(DataComponents.ITEM_MODEL, model);
        }
        // Pets are named "[Lvl {LVL}] Wolf" in the repo.
        stack.set(DataComponents.CUSTOM_NAME, SbcItems.parseLegacy(entry.name().replace("{LVL}", "100")).withStyle(s -> s.withItalic(false)));
        List<Component> lore = new ArrayList<>();
        for (String line : entry.lore()) lore.add(SbcItems.parseLegacy(line).withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        if (entry.glint()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entry.id());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** "minecraft:skull" with damage 3, "minecraft:dye" with 4: the NEU repo still uses 1.8 names. */
    private static Item item(String itemId, int damage) {
        String name = itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
        if (name.equals("skull")) return Items.PLAYER_HEAD;
        Identifier id = Identifier.tryParse(LegacyMaterials.modern(name, damage));
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == Items.AIR) {
            Identifier direct = Identifier.tryParse(itemId);
            item = direct == null ? null : BuiltInRegistries.ITEM.getOptional(direct).orElse(null);
        }
        return item == null || item == Items.AIR ? Items.BARRIER : item;
    }
}
