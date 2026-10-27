package com.epic60869.sky2m.price;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.epic60869.sky2m.config.Sky2MConfig;

/**
 * Adds prices to SkyBlock item tooltips, following Skyblocker's AvgBinTooltip, LBinTooltip and
 * NpcPriceTooltip and BazaarPriceTooltip: bazaar buy/sell price, 3 day average price, lowest BIN, estimated item value and NPC sell price, with the stack total and
 * the price each when there is more than one item. Auction prices come from the same API
 * Skyblocker uses (hysky.de); NPC prices come from the Hypixel items API.
 */
public final class Sky2MPriceTooltip {
    /** "Stored: 1,234/2,240" on items in the Sacks menu. */
    private static final Pattern SACK_STORED = Pattern.compile("^Stored: ([\\d,]+)/");
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d+)]");

    /** NPC sell prices, built from {@link PriceService}'s item list (the same data Skyblocker's NPC price tooltip uses). */
    private static volatile Map<String, Double> npcPrices = Map.of();

    static {
        PriceService.onItems(items -> {
            Map<String, Double> result = new HashMap<>();
            for (JsonObject item : items.values()) {
                try {
                    if (item.has("id") && item.has("npc_sell_price")) {
                        result.put(item.get("id").getAsString(), item.get("npc_sell_price").getAsDouble());
                    }
                } catch (Exception ignored) {}
            }
            npcPrices = result;
        });
    }

    private Sky2MPriceTooltip() {}

    // Live market snapshots, shared with every other price feature through PriceService.
    private static Map<String, Double> lowestBins() {
        return PriceService.lowestBins();
    }

    private static Map<String, Double> threeDayAverage() {
        return PriceService.threeDayAverages();
    }

    private static Map<String, Double> bazaarBuy() {
        return PriceService.bazaar().buy();
    }

    private static Map<String, Double> bazaarSell() {
        return PriceService.bazaar().sell();
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.inventory.priceTooltip.enabled) return;
            // The Item List works anywhere: it isn't a Hypixel inventory.
            if (!forceShow && !Compat.isOnSkyblock()) return;
            if (forceShow && !config.inventory.priceTooltip.itemList) return;
            addPrices(config.inventory.priceTooltip, stack, lines);
        });
    }

    /** Set while the Item List builds a tooltip, so prices show there outside SkyBlock too. */
    private static boolean forceShow;

    /** An Item List (or recipe card) item's tooltip, with the prices. */
    public static List<Component> itemListTooltip(ItemStack stack) {
        forceShow = true;
        try {
            return net.minecraft.client.gui.screens.Screen.getTooltipFromItem(net.minecraft.client.Minecraft.getInstance(), stack);
        } finally {
            forceShow = false;
        }
    }

    private static final Pattern LISTING_PRICE = Pattern.compile("^(?:Buy it now|Starting bid|Top bid|Item price): ([\\d,]+) coins");
    private static final Pattern PET_TIER = Pattern.compile("^(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)\\b");
    private static final java.util.List<String> TIERS = java.util.List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
    private static final Pattern BAZAAR_ORDER_PREFIX = Pattern.compile("^(?:BUY|SELL) ");
    private static volatile Map<String, String> namesToIds;
    private static volatile Object namesFor;

    /** Item names (colour codes stripped) to ids, from the Item List data; null until that has loaded. */
    private static Map<String, String> namesToIds() {
        java.util.List<com.epic60869.sky2m.features.itemlist.ItemListData.Entry> entries = com.epic60869.sky2m.features.itemlist.ItemListData.entries();
        if (entries == null || entries.isEmpty()) return null;
        if (namesFor != entries || namesToIds == null) {
            Map<String, String> map = new HashMap<>();
            for (var e : entries) {
                String name = ChatFormatting.stripFormatting(e.name());
                if (name != null) map.putIfAbsent(name.trim(), e.id());
            }
            namesToIds = map;
            namesFor = entries;
        }
        return namesToIds;
    }

    /** Whether the open container is the Pets menu, the Bazaar or the Auction House. 1 = pets, 2 = bazaar, 3 = auctions, 0 = none. */
    private static int menuKind() {
        try {
            var screen = net.minecraft.client.Minecraft.getInstance().gui.screen();
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return 0;
            String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
            if (title == null) return 0;
            if (title.matches("^(?:\\(\\d+/\\d+\\) )?Pets\\b.*")) return 1;
            if (title.startsWith("Bazaar") || title.contains("➜")) return 2;
            if (title.startsWith("Auction") || title.contains("Auctions") || title.startsWith("Category") || title.startsWith("Item Auction")) return 3;
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * The price key for a menu item that has no SkyBlock id of its own (a pet without its data, a Bazaar order or product
     * button): worked out from its name and, for pets, its rarity line. "" when it can't be told.
     */
    private static String menuFallbackId(ItemStack stack, int kind, List<Component> lines) {
        Map<String, String> names = namesToIds();
        if (names == null) return "";
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        if (name == null) return "";
        name = name.replace("⭐", "").trim();
        if (kind == 1) {
            Matcher m = PET_LEVEL.matcher(name);
            if (!m.find()) return "";
            String type = name.substring(m.end()).trim();
            String tier = "";
            for (Component line : lines) {
                Matcher t = PET_TIER.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
                if (t.find()) tier = t.group(1);
            }
            int rarity = TIERS.indexOf(tier);
            if (rarity < 0) return "";
            String id = names.get("[Lvl 100] " + type);
            if (id == null) id = names.get("[Lvl {LVL}] " + type);
            if (id == null) return "";
            int semi = id.indexOf(';');
            return semi < 0 ? "" : "LVL_" + m.group(1) + "_" + tier + "_" + id.substring(0, semi);
        }
        name = BAZAAR_ORDER_PREFIX.matcher(name).replaceFirst("");
        String id = names.get(name);
        if (id == null || id.indexOf(';') >= 0) return "";
        if (kind == 2 && !bazaarSell().containsKey(id)) return "";
        if (kind == 3 && !lowestBins().containsKey(id)) return "";
        return id;
    }

    /** The coins a menu item asks for in its lore (an auction's price), or -1. */
    private static long listingPrice(List<Component> lines) {
        for (Component line : lines) {
            Matcher m = LISTING_PRICE.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
            if (m.find()) {
                try { return Long.parseLong(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    /** What one {@code id} is worth: bazaar insta-sell, else lowest BIN (or last seen), else NPC sell. 0 if unknown. */
    public static double unitPrice(String id) {
        refreshIfStale();
        Double sell = bazaarSell().get(id);
        if (sell != null && sell > 0) return sell;
        Double bin = lowestBins().get(id);
        if (bin != null) return bin;
        PriceHistory.Seen seen = PriceHistory.get(id);
        if (seen != null) return seen.price();
        Double npc = npcPrices.get(id);
        return npc == null ? 0 : npc;
    }

    /**
     * What one {@code id} is worth by a profit tracker's price source (Skysoft's profitTrackerSourcePrice): bazaar
     * instant sell, sell order or buy order, or NPC sell; lowest BIN when that has no price. 0 if unknown.
     */
    public static double price(String id, com.epic60869.sky2m.features.FeatureConfigs.ProfitPriceSource source) {
        refreshIfStale();
        Double price = switch (source) {
            case INSTANT_SELL, BUY_ORDER -> bazaarSell().get(id);
            case SELL_ORDER -> bazaarBuy().get(id);
            case NPC_SELL -> npcPrices.get(id);
        };
        if (price != null && price > 0) return price;
        Double bin = lowestBins().get(id);
        if (bin != null && bin > 0) return bin;
        PriceHistory.Seen seen = PriceHistory.get(id);
        return seen == null ? 0 : seen.price();
    }

    /** What it costs to get one {@code id}: bazaar insta-buy, else lowest BIN (or last seen). 0 if unknown. */
    public static double costPrice(String id) {
        Double buy = bazaarBuy().get(id);
        if (buy != null && buy > 0) return buy;
        Double bin = lowestBins().get(id);
        if (bin != null && bin > 0) return bin;
        PriceHistory.Seen seen = PriceHistory.get(id);
        return seen == null ? 0 : seen.price();
    }

    /** The bazaar insta-buy price, or null (the Item List calculator's bz()). */
    public static Double bazaarBuyPrice(String id) {
        refreshIfStale();
        return bazaarBuy().get(id);
    }

    /** The bazaar insta-sell price, or null. */
    public static Double bazaarSellPrice(String id) {
        refreshIfStale();
        return bazaarSell().get(id);
    }

    /** The lowest BIN, or null (the Item List calculator's lb()). */
    public static Double lowestBinPrice(String id) {
        refreshIfStale();
        return lowestBins().get(id);
    }

    /** The NPC sell price, or null (the Item List calculator's npc()). */
    public static Double npcSellPrice(String id) {
        refreshIfStale();
        return npcPrices.get(id);
    }

    /**
     * What an item is worth, with what's applied to it (Estimated Item Value), times its count; 0 when it has no
     * price. For the Profile Viewer's networth.
     */
    public static double itemValue(ItemStack stack) {
        String id = Compat.neuName(stack);
        if (id.isEmpty()) return 0;
        String apiId = apiId(stack, id);
        double value = EstimatedItemValue.estimate(id, apiId, Compat.customDataView(stack));
        if (value <= 0) value = costPrice(apiId);
        return value * Math.max(1, stack.getCount());
    }

    /** Every bazaar product id with a sell price ("ENCHANTMENT_SHARPNESS_5", "ENCHANTED_DIAMOND", ...). */
    public static java.util.Set<String> bazaarIds() {
        refreshIfStale();
        return bazaarSell().keySet();
    }

    /** Every item id with a lowest BIN or 3 day average, i.e. sold on the Auction House. */
    public static java.util.Set<String> auctionIds() {
        refreshIfStale();
        java.util.Set<String> ids = new java.util.HashSet<>(lowestBins().keySet());
        ids.addAll(threeDayAverage().keySet());
        return ids;
    }

    /** Starts loading the prices (if they're old or not loaded yet) without asking for one. */
    public static void warmup() {
        refreshIfStale();
    }

    /** Whether any market prices (bazaar or auction) have loaded yet. */
    public static boolean pricesLoaded() {
        return !bazaarSell().isEmpty() || !lowestBins().isEmpty();
    }

    /** Has {@link PriceService} refresh the market prices in the background when they're over a minute old. */
    private static void refreshIfStale() {
        PriceService.refreshMarket(false);
    }

    private static void addPrices(Sky2MConfig.PriceTooltip config, ItemStack stack, List<Component> lines) {
        String id = Compat.neuName(stack);
        String apiId;
        int kind = forceShow || !config.menus ? 0 : menuKind();
        if (id.isEmpty()) {
            // Pets, Bazaar and Auction House menus: buttons and pets that carry no id are priced by their name.
            if (kind == 0) return;
            apiId = menuFallbackId(stack, kind, lines);
            if (apiId.isEmpty()) return;
            id = apiId;
            refreshIfStale();
        } else {
            refreshIfStale();
            apiId = apiId(stack, id);
            // A Pets menu pet whose data has no tier/level (or that isn't priced): try its name and rarity line.
            if (kind == 1 && id.equals("PET") && apiId.equals(id)) {
                String fallback = menuFallbackId(stack, kind, lines);
                if (!fallback.isEmpty()) apiId = fallback;
            }
        }
        int count = Math.max(1, stack.getCount());

        // Order: NPC sell price, then auction prices, or for bazaar items (not on the auction house) the bazaar
        // insta-buy and insta-sell price in their place.
        if (config.npcPrice) {
            Double price = npcPrices.get(id);
            if (price != null) lines.add(line("NPC Sell Price: ", ChatFormatting.YELLOW, price, count));
        }
        boolean onAuctionHouse = lowestBins().containsKey(apiId) || threeDayAverage().containsKey(apiId) || PriceHistory.get(apiId) != null;
        if (!onAuctionHouse && bazaarBuy().containsKey(apiId)) {
            if (!config.bazaar && !config.lowestBin && !config.threeDayAverage) {
                Sky2MRecipeCost.addLines(config, id, apiId, count, lines);
                return;
            }
            // For a sack in the Sacks menu, price everything stored in it.
            int amount = count;
            for (Component line : lines) {
                Matcher m = SACK_STORED.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
                if (m.find()) {
                    amount = Math.max(1, Integer.parseInt(m.group(1).replace(",", "")));
                    break;
                }
            }
            Double buy = bazaarBuy().get(apiId), sell = bazaarSell().get(apiId);
            lines.add(buy == null || buy <= 0 ? noData("Bazaar Insta-Buy: ") : line("Bazaar Insta-Buy: ", ChatFormatting.GOLD, buy, amount));
            lines.add(sell == null || sell <= 0 ? noData("Bazaar Insta-Sell: ") : line("Bazaar Insta-Sell: ", ChatFormatting.GOLD, sell, amount));
            Sky2MRecipeCost.addLines(config, id, apiId, count, lines);
            return;
        }
        if (config.lowestBin) {
            Double price = lowestBins().get(apiId);
            PriceHistory.Seen seen = price == null ? PriceHistory.get(apiId) : null;
            if (price != null) {
                lines.add(line("Lowest BIN Price: ", ChatFormatting.GOLD, price, count));
                // An auction listing: how its price compares with the lowest BIN.
                long asking = kind == 3 ? listingPrice(lines) : -1;
                if (asking > 0 && price > 0) {
                    double diff = (asking - price * count) / (price * count) * 100;
                    boolean cheaper = diff < 0;
                    lines.add(Component.literal("Listing vs Lowest BIN: ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(String.format(Locale.ENGLISH, "%+.1f%%", diff)).withStyle(cheaper ? ChatFormatting.GREEN : ChatFormatting.RED))
                        .append(Component.literal(cheaper ? " (below lowest BIN)" : " (above lowest BIN)").withStyle(ChatFormatting.GRAY)));
                }
            } else if (seen != null) {
                // Nobody is selling one right now (common for runes): show the last price seen.
                lines.add(line("Lowest BIN Price: ", ChatFormatting.GOLD, seen.price(), count)
                    .append(Component.literal(" (last seen " + PriceHistory.ago(seen.at()) + ")").withStyle(ChatFormatting.DARK_GRAY)));
            } else if (id.equals("RUNE") || id.equals("UNIQUE_RUNE")) {
                lines.add(noData("Lowest BIN Price: "));
            }
        }
        if (config.threeDayAverage) {
            Double price = threeDayAverage().get(apiId);
            if (price != null) lines.add(line("3 Day Avg. Price: ", ChatFormatting.GOLD, price, count));
        }
        if (config.estimatedValue && !com.epic60869.sky2m.features.economy.ValueBreakdown.panelEnabled()) {
            double value = EstimatedItemValue.estimate(id, apiId, Compat.customDataView(stack));
            if (value > 0) lines.add(line("Estimated Item Value: ", ChatFormatting.GOLD, value, count));
        }
        Sky2MRecipeCost.addLines(config, id, apiId, count, lines);
    }

    private static Component noData(String label) {
        return Component.literal(label).withStyle(ChatFormatting.GOLD).append(Component.literal("No data").withStyle(ChatFormatting.RED));
    }

    /** Skyblocker's coin format: the total, plus the price each when there is more than one. */
    private static net.minecraft.network.chat.MutableComponent line(String label, ChatFormatting labelColour, double price, int count) {
        String each = String.format(Locale.ENGLISH, "%,.1f", price);
        MutableComponent line = Component.literal(label).withStyle(labelColour);
        if (count == 1) return line.append(Component.literal(each + " Coins").withStyle(ChatFormatting.DARK_AQUA));
        return line.append(Component.literal(String.format(Locale.ENGLISH, "%,.1f", price * count) + " Coins ").withStyle(ChatFormatting.DARK_AQUA))
            .append(Component.literal("(" + each + " each)").withStyle(ChatFormatting.GRAY));
    }

    /** The price key for a stack (see {@link #apiId}), or "" for non-SkyBlock items. */
    public static String marketId(ItemStack stack) {
        String id = Compat.neuName(stack);
        return id.isEmpty() ? "" : apiId(stack, id);
    }

    /** The key the auction price API uses: the item id, except for pets, runes and enchanted books. */
    private static String apiId(ItemStack stack, String id) {
        // Item List entries use the NEU repo's ids: "WOLF;4" (a pet), "SHARPNESS;5" (a book), "AMBER_RUNE;1".
        int semi = id.indexOf(';');
        if (semi > 0 && semi < id.length() - 1) {
            String base = id.substring(0, semi), n = id.substring(semi + 1);
            try {
                int num = Integer.parseInt(n);
                if (stack.getHoverName().getString().contains("[Lvl")) {
                    Matcher m = PET_LEVEL.matcher(ChatFormatting.stripFormatting(stack.getHoverName().getString()));
                    if (num >= 0 && num < TIERS.size() && m.find()) return "LVL_" + m.group(1) + "_" + TIERS.get(num) + "_" + base;
                } else if (base.endsWith("_RUNE")) {
                    return base + "_" + num;
                } else {
                    return "ENCHANTMENT_" + base + "_" + num;
                }
            } catch (NumberFormatException ignored) {}
            return id;
        }
        // Most items are just their id: only read (never copy) the data for the few that aren't.
        if (!id.equals("PET") && !id.equals("RUNE") && !id.equals("UNIQUE_RUNE") && !id.equals("ENCHANTED_BOOK")) return id;
        CompoundTag data = Compat.customDataView(stack);
        try {
            switch (id) {
                case "PET" -> {
                    JsonObject pet = JsonParser.parseString(data.getStringOr("petInfo", "{}")).getAsJsonObject();
                    Matcher m = PET_LEVEL.matcher(stack.getHoverName().getString());
                    if (pet.has("type") && pet.has("tier") && m.find()) {
                        return "LVL_" + m.group(1) + "_" + pet.get("tier").getAsString() + "_" + pet.get("type").getAsString();
                    }
                }
                case "RUNE", "UNIQUE_RUNE" -> {
                    CompoundTag runes = data.getCompoundOrEmpty("runes");
                    for (String rune : runes.keySet()) return rune + "_RUNE_" + runes.getIntOr(rune, 1);
                }
                case "ENCHANTED_BOOK" -> {
                    CompoundTag enchants = data.getCompoundOrEmpty("enchantments");
                    if (enchants.size() == 1) {
                        for (String enchant : enchants.keySet()) {
                            return "ENCHANTMENT_" + enchant.toUpperCase(Locale.ROOT) + "_" + enchants.getIntOr(enchant, 1);
                        }
                    }
                }
                default -> {}
            }
        } catch (Exception ignored) {}
        return id;
    }
}
