package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds prices to SkyBlock item tooltips, following Skyblocker's AvgBinTooltip, LBinTooltip and
 * NpcPriceTooltip and BazaarPriceTooltip: bazaar buy/sell price, 3 day average price, lowest BIN, estimated item value and NPC sell price, with the stack total and
 * the price each when there is more than one item. Auction prices come from the same API
 * Skyblocker uses (hysky.de); NPC prices come from the Hypixel items API.
 */
public final class Sky2MPriceTooltip {
    private static final String LOWEST_BINS_URL = "https://hysky.de/api/auctions/lowestbins";
    private static final String AVERAGE_URL = "https://hysky.de/api/auctions/lowestbins/average?days=3";
    private static final String NPC_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    /** "Stored: 1,234/2,240" on items in the Sacks menu. */
    private static final Pattern SACK_STORED = Pattern.compile("^Stored: ([\\d,]+)/");
    private static final long REFRESH_MS = 60_000;
    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl (\\d+)]");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static volatile Map<String, Double> lowestBins = Map.of();
    private static volatile Map<String, Double> threeDayAverage = Map.of();
    private static volatile Map<String, Double> npcPrices = Map.of();
    private static volatile Map<String, Double> bazaarBuy = Map.of();
    private static volatile Map<String, Double> bazaarSell = Map.of();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static volatile long lastRefresh;

    private Sky2MPriceTooltip() {}

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.misc.priceTooltip.enabled || !Compat.isOnSkyblock()) return;
            addPrices(config.misc.priceTooltip, stack, lines);
        });
    }

    /** What one {@code id} is worth: bazaar insta-sell, else lowest BIN (or last seen), else NPC sell. 0 if unknown. */
    public static double unitPrice(String id) {
        refreshIfStale();
        Double sell = bazaarSell.get(id);
        if (sell != null && sell > 0) return sell;
        Double bin = lowestBins.get(id);
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
            case INSTANT_SELL, BUY_ORDER -> bazaarSell.get(id);
            case SELL_ORDER -> bazaarBuy.get(id);
            case NPC_SELL -> npcPrices.get(id);
        };
        if (price != null && price > 0) return price;
        Double bin = lowestBins.get(id);
        if (bin != null && bin > 0) return bin;
        PriceHistory.Seen seen = PriceHistory.get(id);
        return seen == null ? 0 : seen.price();
    }

    /** What it costs to get one {@code id}: bazaar insta-buy, else lowest BIN (or last seen). 0 if unknown. */
    public static double costPrice(String id) {
        Double buy = bazaarBuy.get(id);
        if (buy != null && buy > 0) return buy;
        Double bin = lowestBins.get(id);
        if (bin != null && bin > 0) return bin;
        PriceHistory.Seen seen = PriceHistory.get(id);
        return seen == null ? 0 : seen.price();
    }

    /** The bazaar insta-buy price, or null (the Item List calculator's bz()). */
    public static Double bazaarBuyPrice(String id) {
        refreshIfStale();
        return bazaarBuy.get(id);
    }

    /** The bazaar insta-sell price, or null. */
    public static Double bazaarSellPrice(String id) {
        refreshIfStale();
        return bazaarSell.get(id);
    }

    /** The lowest BIN, or null (the Item List calculator's lb()). */
    public static Double lowestBinPrice(String id) {
        refreshIfStale();
        return lowestBins.get(id);
    }

    /** The NPC sell price, or null (the Item List calculator's npc()). */
    public static Double npcSellPrice(String id) {
        refreshIfStale();
        return npcPrices.get(id);
    }

    /** Starts loading the prices (if they're old or not loaded yet) without asking for one. */
    public static void warmup() {
        refreshIfStale();
    }

    /** Whether any market prices (bazaar or auction) have loaded yet. */
    public static boolean pricesLoaded() {
        return !bazaarSell.isEmpty() || !lowestBins.isEmpty();
    }

    private static void refreshIfStale() {
        if (System.currentTimeMillis() - lastRefresh < REFRESH_MS || !REFRESHING.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Double> bins = fetch(LOWEST_BINS_URL);
                if (!bins.isEmpty()) {
                    lowestBins = bins;
                    PriceHistory.record(bins);
                }
                Map<String, Double> average = fetch(AVERAGE_URL);
                if (!average.isEmpty()) threeDayAverage = average;
                fetchBazaar();
                // NPC prices change rarely, so they are only fetched until they have loaded once.
                if (npcPrices.isEmpty()) {
                    Map<String, Double> npc = fetchNpcPrices();
                    if (!npc.isEmpty()) npcPrices = npc;
                }
            } finally {
                lastRefresh = System.currentTimeMillis();
                REFRESHING.set(false);
            }
        });
    }

    private static Map<String, Double> fetch(String url) {
        Map<String, Double> result = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return result;
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            for (var entry : root.entrySet()) {
                try {
                    result.put(entry.getKey(), entry.getValue().getAsDouble());
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Price download failed for " + url + ": " + e.getMessage());
        }
        return result;
    }

    /** Instant buy / instant sell prices from Hypixel's bazaar API (what Skyblocker's bazaar tooltip shows). */
    private static void fetchBazaar() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BAZAAR_URL)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            JsonObject products = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("products");
            Map<String, Double> buy = new HashMap<>(), sell = new HashMap<>();
            for (var entry : products.entrySet()) {
                JsonObject status = entry.getValue().getAsJsonObject().getAsJsonObject("quick_status");
                if (status == null) continue;
                buy.put(entry.getKey(), status.get("buyPrice").getAsDouble());
                sell.put(entry.getKey(), status.get("sellPrice").getAsDouble());
            }
            if (!buy.isEmpty()) {
                bazaarBuy = buy;
                bazaarSell = sell;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Bazaar price download failed: " + e.getMessage());
        }
    }

    /** NPC sell prices from the Hypixel items API (the same data Skyblocker's NPC price tooltip uses). */
    private static Map<String, Double> fetchNpcPrices() {
        Map<String, Double> result = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(NPC_URL)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return result;
            for (var element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("items")) {
                JsonObject item = element.getAsJsonObject();
                if (item.has("id") && item.has("npc_sell_price")) {
                    result.put(item.get("id").getAsString(), item.get("npc_sell_price").getAsDouble());
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] NPC price download failed: " + e.getMessage());
        }
        return result;
    }

    private static void addPrices(Sky2MConfig.PriceTooltip config, ItemStack stack, List<Component> lines) {
        String id = Compat.neuName(stack);
        if (id.isEmpty()) return;
        refreshIfStale();
        String apiId = apiId(stack, id);
        int count = Math.max(1, stack.getCount());

        // Order: NPC sell price, then auction prices, or for bazaar items (not on the auction house) the bazaar
        // insta-buy and insta-sell price in their place.
        if (config.npcPrice) {
            Double price = npcPrices.get(id);
            if (price != null) lines.add(line("NPC Sell Price: ", ChatFormatting.YELLOW, price, count));
        }
        boolean onAuctionHouse = lowestBins.containsKey(apiId) || threeDayAverage.containsKey(apiId) || PriceHistory.get(apiId) != null;
        if (!onAuctionHouse && bazaarBuy.containsKey(apiId)) {
            if (!config.bazaar && !config.lowestBin && !config.threeDayAverage) return;
            // For a sack in the Sacks menu, price everything stored in it.
            int amount = count;
            for (Component line : lines) {
                Matcher m = SACK_STORED.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
                if (m.find()) {
                    amount = Math.max(1, Integer.parseInt(m.group(1).replace(",", "")));
                    break;
                }
            }
            Double buy = bazaarBuy.get(apiId), sell = bazaarSell.get(apiId);
            lines.add(buy == null || buy <= 0 ? noData("Bazaar Insta-Buy: ") : line("Bazaar Insta-Buy: ", ChatFormatting.GOLD, buy, amount));
            lines.add(sell == null || sell <= 0 ? noData("Bazaar Insta-Sell: ") : line("Bazaar Insta-Sell: ", ChatFormatting.GOLD, sell, amount));
            return;
        }
        if (config.lowestBin) {
            Double price = lowestBins.get(apiId);
            PriceHistory.Seen seen = price == null ? PriceHistory.get(apiId) : null;
            if (price != null) {
                lines.add(line("Lowest BIN Price: ", ChatFormatting.GOLD, price, count));
            } else if (seen != null) {
                // Nobody is selling one right now (common for runes): show the last price seen.
                lines.add(line("Lowest BIN Price: ", ChatFormatting.GOLD, seen.price(), count)
                    .append(Component.literal(" (last seen " + PriceHistory.ago(seen.at()) + ")").withStyle(ChatFormatting.DARK_GRAY)));
            } else if (id.equals("RUNE") || id.equals("UNIQUE_RUNE")) {
                lines.add(noData("Lowest BIN Price: "));
            }
        }
        if (config.threeDayAverage) {
            Double price = threeDayAverage.get(apiId);
            if (price != null) lines.add(line("3 Day Avg. Price: ", ChatFormatting.GOLD, price, count));
        }
        if (config.estimatedValue) {
            double value = EstimatedItemValue.estimate(id, apiId, Compat.customDataView(stack));
            if (value > 0) lines.add(line("Estimated Item Value: ", ChatFormatting.GOLD, value, count));
        }
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
