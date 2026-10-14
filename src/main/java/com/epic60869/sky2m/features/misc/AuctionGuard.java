package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Misc > Inventory & Storage > Auction Price Guard. In "Create BIN Auction" / "Confirm BIN Auction", clicking Create or
 * Confirm for a price far below the item's lowest BIN is stopped; in "BIN Auction View" / "Confirm Purchase", clicking
 * Buy Item Right Now or Confirm for a price far above it is stopped. A second click on the same button within 3
 * seconds goes through. The price is read from the button's or menu's "Price: ... coins" lore, and the item is the
 * menu's auctioned item (remembered from the first menu for the confirm one).
 */
public final class AuctionGuard {
    private static final Pattern PRICE = Pattern.compile("(?:Item [Pp]rice|BIN [Pp]rice|Price|Cost|Starting bid|Buy it now): ?([\\d,]+(?:\\.\\d+)?) coins");
    private static final long CONFIRM_MS = 3_000;

    private static ItemStack lastItem = ItemStack.EMPTY;
    private static String confirmKey;
    private static long confirmAt;

    private AuctionGuard() {}

    private static FeatureConfigs.AuctionGuard config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.auctionGuard;
    }

    /** From the container click mixin: true stops the click. */
    public static boolean onSlotClicked(AbstractContainerScreen<?> screen, Slot slot) {
        FeatureConfigs.AuctionGuard c = config();
        if (c == null || (!c.underprice && !c.overpay) || slot == null || !slot.hasItem() || !Sky2MLocation.onSkyblock()) return false;
        String title = screen.getTitle().getString();
        String button = Sky2MLocation.strip(slot.getItem().getHoverName().getString()).trim();
        boolean selling = title.contains("BIN Auction") && !title.contains("View") && (button.contains("Create BIN Auction") || button.equals("Confirm"));
        boolean buying = (title.contains("BIN Auction View") && button.contains("Buy Item Right Now")) || (title.contains("Confirm Purchase") && button.equals("Confirm"));
        ItemStack item = auctioned(screen, slot);
        if (!item.isEmpty()) lastItem = item.copy();
        if ((!selling || !c.underprice) && (!buying || !c.overpay)) return false;
        if (item.isEmpty()) item = lastItem;
        double price = price(slot.getItem());
        if (price <= 0) price = menuPrice(screen);
        String id = item.isEmpty() ? "" : Sky2MPriceTooltip.marketId(item);
        Double lowest = id == null || id.isEmpty() ? null : Sky2MPriceTooltip.lowestBinPrice(id);
        if (price <= 0 || lowest == null || lowest <= 0) return false;
        double value = lowest * Math.max(1, item.getCount());
        double ratio = price / value;
        boolean bad = selling ? ratio * 100 < c.underpricePercent : (ratio - 1) * 100 > c.overpayPercent;
        if (!bad) return false;
        String key = title + "|" + slot.index;
        long now = System.currentTimeMillis();
        if (key.equals(confirmKey) && now - confirmAt < CONFIRM_MS) {
            confirmKey = null;
            return false;
        }
        confirmKey = key;
        confirmAt = now;
        String name = item.getHoverName().getString();
        String what = selling
            ? String.format("Listing %s for %s is only %d%% of its lowest BIN (%s).", name, CombatFeatures.formatCoins(price), Math.round(ratio * 100), CombatFeatures.formatCoins(value))
            : String.format("Buying %s for %s is %d%% more than its lowest BIN (%s).", name, CombatFeatures.formatCoins(price), Math.round((ratio - 1) * 100), CombatFeatures.formatCoins(value));
        Sky2MAlerts.chat(Component.literal(what + " Click again within 3 seconds to " + (selling ? "list" : "buy") + " it anyway.").withStyle(ChatFormatting.RED));
        Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f);
        return true;
    }

    /** The menu's auctioned item: the first SkyBlock item in the menu that isn't a button. */
    private static ItemStack auctioned(AbstractContainerScreen<?> screen, Slot clicked) {
        int menuSize = screen.getMenu().slots.size() - 36;
        for (int i = 0; i < Math.max(0, menuSize); i++) {
            Slot s = screen.getMenu().slots.get(i);
            if (s == clicked || !s.hasItem()) continue;
            String id = Compat.neuName(s.getItem());
            if (id != null && !id.isEmpty()) return s.getItem();
        }
        return ItemStack.EMPTY;
    }

    private static double menuPrice(AbstractContainerScreen<?> screen) {
        int menuSize = screen.getMenu().slots.size() - 36;
        for (int i = 0; i < Math.max(0, menuSize); i++) {
            double p = price(screen.getMenu().slots.get(i).getItem());
            if (p > 0) return p;
        }
        return 0;
    }

    private static double price(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        double fromName = parse(Sky2MLocation.strip(stack.getHoverName().getString()));
        if (fromName > 0) return fromName;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return 0;
        for (Component line : lore.lines()) {
            double p = parse(Sky2MLocation.strip(line.getString()));
            if (p > 0) return p;
        }
        return 0;
    }

    private static double parse(String text) {
        Matcher m = PRICE.matcher(text);
        if (!m.find()) return 0;
        try {
            return Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
