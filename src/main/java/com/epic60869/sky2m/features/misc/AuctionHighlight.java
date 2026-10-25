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

import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your auctions in Manage Auctions: sold ones green and expired ones red, and optionally a BIN priced above the item's
 * estimated value. From SkyHanni's AuctionsHighlighter (LGPL-2.1), which reads the same "Status:" lore lines.
 */
public final class AuctionHighlight {
    private static final int SOLD = 0x8055FF55;
    private static final int EXPIRED = 0x80FF5555;
    private static final int UNDERBID = 0x80FFAA00;
    private static final Pattern BUY_IT_NOW = Pattern.compile("^Buy it now: ([\\d,]+) coins");

    /** Each item's colour (0 for none), so its lore isn't read every frame. */
    private static final Map<ItemStack, Integer> CACHE = new WeakHashMap<>();
    // The screen title is matched when it changes, not for every slot every frame.
    private static Component lastTitle;
    private static boolean lastManage;

    private AuctionHighlight() {}

    /** Drawn behind the slot's item, from Sky2MSlotBackgroundMixin. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.inventory.highlightAuctions || !slot.hasItem()) return;
        if (slot.container instanceof Inventory || !Compat.isOnSkyblock()) return;
        if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        Component title = screen.getTitle();
        if (title != lastTitle) {
            lastTitle = title;
            lastManage = Sky2MLocation.strip(title.getString()).trim().equals("Manage Auctions");
            CACHE.clear();
        }
        if (!lastManage) return;
        ItemStack stack = slot.getItem();
        Integer colour = CACHE.get(stack);
        if (colour == null) {
            colour = colour(stack, config.inventory.highlightAuctionsUnderbid);
            CACHE.put(stack, colour);
        }
        if (colour != 0) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour);
    }

    private static int colour(ItemStack stack, boolean underbid) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return 0;
        long buyItNow = -1;
        for (Component line : lore.lines()) {
            String text = Sky2MLocation.strip(line.getString()).trim();
            if (text.equals("Status: Sold!")) return SOLD;
            if (text.equals("Status: Expired!")) return EXPIRED;
            Matcher m = BUY_IT_NOW.matcher(text);
            if (m.find()) buyItNow = Long.parseLong(m.group(1).replace(",", ""));
        }
        // A BIN listed above what the item is worth (SkyHanni's "underbid").
        if (underbid && buyItNow > 0) {
            double value = com.epic60869.sky2m.Sky2MPriceTooltip.itemValue(stack);
            if (value > 0 && buyItNow > value) return UNDERBID;
        }
        return 0;
    }
}
