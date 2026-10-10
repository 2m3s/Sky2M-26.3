package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Pattern;

/**
 * SkyHanni's loadout highlighting (https://github.com/hannibal002/SkyHanni, LGPL-2.1: LoadoutApi,
 * LoadoutSlotHighlight): the loadout you have equipped is highlighted in the Loadouts menu. Hypixel shows
 * "Left-click to equip!" on every loadout but the equipped one.
 */
public final class LoadoutHighlight {
    private static final Pattern INVENTORY = Pattern.compile("\\((?<currentPage>\\d+)/\\d+\\) Loadouts");
    private static final Pattern LOCKED = Pattern.compile("Loadout \\d+ Locked");
    private static final int FIRST_ICON_SLOT = 14;
    private static final int ROWS = 4;
    private static final int SLOTS_PER_ROW = 3;

    private LoadoutHighlight() {}

    /** Drawn behind the slot's item, from Sky2MSlotBackgroundMixin. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.loadoutHighlight.enabled || !config.misc.loadoutHighlight.currentlyEquipped) return;
        if (!Compat.isOnSkyblock() || slot.container instanceof Inventory || !isIconSlot(slot.index)) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        if (!INVENTORY.matcher(Sky2MLocation.strip(screen.getTitle().getString())).find()) return;
        if (!isCurrentSelectedLoadout(slot.getItem())) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour(config.misc.loadoutHighlight.equippedColor));
    }

    private static boolean isIconSlot(int index) {
        for (int row = 0; row < ROWS; row++) {
            int start = FIRST_ICON_SLOT + row * 9;
            if (index >= start && index < start + SLOTS_PER_ROW) return true;
        }
        return false;
    }

    // A loadout that doesn't change anything has no "Left-click to equip!" either, so it counts as equipped too.
    private static boolean isCurrentSelectedLoadout(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem().toString().contains("glass_pane")) return false;
        if (LOCKED.matcher(Sky2MLocation.strip(stack.getHoverName().getString())).find()) return false;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = line.getString();
            if (text.contains("Left-click to equip!") || text.contains("You must customize this loadout")) return false;
        }
        return true;
    }

    private static int colour(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xAA55FF55;
        }
    }
}
