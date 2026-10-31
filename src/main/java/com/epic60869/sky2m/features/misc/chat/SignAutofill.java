package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.portfolio.Portfolio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Misc > Inventory & Storage > Sign Autofill. When Hypixel opens a number sign from a Bazaar or Auction House menu,
 * line 1 is filled in (and selected) with a suggestion, so Done uses it and typing replaces it. Never sent by itself.
 * <ul>
 *   <li>"How much do you want to pay?" (Create Buy Order &gt; Custom Price): the top buy order + the Bazaar Step.</li>
 *   <li>"At what price are you selling?" (Create Sell Offer &gt; Custom Price): the lowest sell offer - the Bazaar Step.</li>
 *   <li>"How many do you want?" (Custom Amount): the last amount you entered on a bazaar amount sign.</li>
 *   <li>"Create BIN Auction" (Set Item Price): the item's lowest BIN (times the stack size) minus the AH Undercut.</li>
 * </ul>
 * Bazaar prices are read from the menu's own buttons ("Price per unit: ... coins" on Same as Top Order / Top Order +0.1
 * / Same as Best Offer / Best Offer -0.1), falling back to the bazaar API's order book for the item. The menu is the
 * container still open when the sign screen is built; Sky2MSignEditMixin does the hooking.
 */
public final class SignAutofill {
    private static final Pattern UNIT_PRICE = Pattern.compile("(?:Price per unit|Unit price|Per unit): ?([\\d,]+(?:\\.\\d+)?) coins");
    private static final Pattern ORDER_ITEM = Pattern.compile("(?:Ordering|Selling|Order|Offer): ?([\\d,]+)x (.+)");

    private enum Kind { BUY_PRICE, SELL_PRICE, AMOUNT, AH_BIN }

    /** What the sign being built should be filled with (set by the constructor hook, taken by init). */
    private static String pending;
    /** Which sign the open sign screen is (null for none of these). */
    private static Kind signKind;
    /** The last amount entered on a bazaar amount sign this session. */
    private static String lastAmount;

    private SignAutofill() {}

    private static FeatureConfigs.SignAutofill config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.signAutofill;
    }

    /** From the sign screen's constructor, while the menu that opened it is still the current screen. */
    public static void onSignCreated(String[] lines) {
        pending = null;
        signKind = null;
        FeatureConfigs.SignAutofill c = config();
        if (c == null || !Sky2MLocation.onSkyblock() || lines.length < 4 || !lines[1].startsWith("^^^")) return;
        if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> menu)) return;
        Kind kind = kind(menu.getTitle().getString(), lines);
        signKind = kind;
        if (kind == null || !lines[0].isBlank()) return;
        try {
            pending = switch (kind) {
                case BUY_PRICE -> c.bazaarPrices ? bazaarPrice(menu, true, c.bazaarStep) : null;
                case SELL_PRICE -> c.bazaarPrices ? bazaarPrice(menu, false, c.bazaarStep) : null;
                case AMOUNT -> c.bazaarAmount ? lastAmount : null;
                case AH_BIN -> c.ahBinPrice ? binPrice(menu, c.ahUndercut) : null;
            };
        } catch (RuntimeException e) {
            pending = null;
        }
    }

    /** From the sign screen's init: the text to put on line 1, once (null for none). */
    public static String takeSuggestion() {
        String text = pending;
        pending = null;
        return text;
    }

    /** From the sign screen's Done: remembers amounts typed on a bazaar amount sign, for the next one. */
    public static void onSignDone(String[] lines) {
        Kind kind = signKind;
        signKind = null;
        if (kind != Kind.AMOUNT || lines[0].isBlank()) return;
        // Sums like "2s" are worked out the same way the Sign Calculator would.
        String amount = SignCalculator.result(lines[0].trim(), false).replace(",", "");
        if (amount.matches("\\d+") && !amount.matches("0+")) lastAmount = amount;
    }

    private static Kind kind(String rawTitle, String[] lines) {
        String title = Sky2MLocation.strip(rawTitle);
        String prompt = String.join(" ", lines[1], lines[2], lines[3]).toLowerCase(Locale.ROOT);
        if (title.contains("How much do you want to pay")) return Kind.BUY_PRICE;
        if (title.contains("At what price are you selling")) return Kind.SELL_PRICE;
        if (title.contains("How many do you want")) return Kind.AMOUNT;
        // The BIN menu also has the duration sign: only the price one.
        if (title.contains("Create BIN Auction") && !prompt.contains("hour") && !prompt.contains("day") && !prompt.contains("duration")) {
            return Kind.AH_BIN;
        }
        return null;
    }

    // ---------------------------------------------------------------- bazaar

    private static String bazaarPrice(AbstractContainerScreen<?> menu, boolean buyOrder, float step) {
        double best = 0;
        String productName = null;
        for (Slot slot : menuSlots(menu)) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
            double unit = 0;
            for (String line : lore(stack)) {
                Matcher m = UNIT_PRICE.matcher(line);
                if (m.find()) unit = parse(m.group(1));
                Matcher item = ORDER_ITEM.matcher(line);
                if (item.find() && productName == null) productName = item.group(2).trim();
            }
            if (unit <= 0) continue;
            // "Same as ..." is the order book's top; "Top Order +0.1" / "Best Offer -0.1" are 0.1 past it.
            if (name.startsWith("Same as")) best = unit;
            else if (best <= 0 && name.contains("+0.1")) best = unit - 0.1;
            else if (best <= 0 && name.contains("-0.1")) best = unit + 0.1;
        }
        if (best <= 0) {
            String id = productId(menu, productName);
            if (id != null) best = Portfolio.bazaarOrderBookPrice(id, buyOrder);
        }
        if (best <= 0) return null;
        double price = buyOrder ? best + step : best - step;
        price = Math.round(price * 10d) / 10d;
        if (price < 0.1) price = 0.1;
        return price == Math.rint(price) ? Long.toString((long) price) : String.format(Locale.US, "%.1f", price);
    }

    /** The product's API id: a SkyBlock item in the menu, else the name from an "Ordering: 64x Wheat" line. */
    private static String productId(AbstractContainerScreen<?> menu, String productName) {
        for (Slot slot : menuSlots(menu)) {
            if (!slot.hasItem()) continue;
            String id = Compat.neuName(slot.getItem());
            if (id != null && !id.isEmpty() && Portfolio.bazaarOrderBookPrice(id, true) + Portfolio.bazaarOrderBookPrice(id, false) > 0) return id;
        }
        return productName == null ? null : Portfolio.bazaarProductId(productName);
    }

    // ---------------------------------------------------------------- auction house

    private static String binPrice(AbstractContainerScreen<?> menu, FeatureConfigs.SignAutofill.Undercut undercut) {
        ItemStack item = ItemStack.EMPTY;
        for (Slot slot : menuSlots(menu)) {
            if (!slot.hasItem()) continue;
            String id = Compat.neuName(slot.getItem());
            if (id != null && !id.isEmpty()) {
                item = slot.getItem();
                break;
            }
        }
        if (item.isEmpty()) return null;
        String id = Sky2MPriceTooltip.marketId(item);
        Double lowest = id == null || id.isEmpty() ? null : Sky2MPriceTooltip.lowestBinPrice(id);
        if (lowest == null || lowest <= 0) return null;
        double total = lowest * Math.max(1, item.getCount());
        long price = switch (undercut) {
            case ONE_COIN -> (long) Math.floor(total) - 1;
            case ONE_PERCENT -> (long) Math.floor(total * 0.99);
            case FIVE_PERCENT -> (long) Math.floor(total * 0.95);
        };
        return price > 0 ? Long.toString(price) : null;
    }

    // ---------------------------------------------------------------- helpers

    private static java.util.List<Slot> menuSlots(AbstractContainerScreen<?> menu) {
        java.util.List<Slot> slots = menu.getMenu().slots;
        return slots.subList(0, Math.max(0, slots.size() - 36));
    }

    private static java.util.List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return java.util.List.of();
        return lore.lines().stream().map(Component::getString).map(Sky2MLocation::strip).toList();
    }

    private static double parse(String number) {
        try {
            return Double.parseDouble(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
