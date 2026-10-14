package com.epic60869.sky2m;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized live pricing for the Sky2M RNG overlay. NPC prices, item names and bazaar prices come from
 * {@link PriceService}; lowest BINs come from lb.tricked.dev, a different source from the tooltip's hysky.de, so that
 * one download stays here.
 */
public final class ItemPriceResolver {
    private static final String LOWEST_BINS_URL = "https://lb.tricked.dev/lowestbins.json";
    private static final long CACHE_TIME_MILLIS = 5 * 60 * 1000L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();
    /** Built from PriceService's item list (only prices above 0). */
    private static volatile Map<String, Double> npcPrices = Map.of();
    private static final Map<String, Double> LOWEST_BIN_PRICES = new HashMap<>();
    private static volatile Map<String, String> nameToId = Map.of();
    /** Completes once {@link #npcPrices} and {@link #nameToId} have been built. */
    private static final CompletableFuture<Void> ITEMS_READY = new CompletableFuture<>();
    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static volatile long lastRefresh = 0L;
    private static volatile CompletableFuture<Void> binsFuture = CompletableFuture.completedFuture(null);

    static {
        PriceService.onItems(items -> {
            npcPrices = parseNpcPrices(items);
            nameToId = parseNames(items);
            ITEMS_READY.complete(null);
        });
    }

    static {
        alias("dedication iv", "ENCHANTMENT_DEDICATION_4");
        alias("dedication 4", "ENCHANTMENT_DEDICATION_4");
        alias("cultivating x", "ENCHANTMENT_CULTIVATING_10");
        alias("cultivating 10", "ENCHANTMENT_CULTIVATING_10");
        alias("turbo wheat v", "ENCHANTMENT_TURBO_WHEAT_5");
        alias("turbo carrot v", "ENCHANTMENT_TURBO_CARROT_5");
        alias("turbo potato v", "ENCHANTMENT_TURBO_POTATO_5");
        alias("turbo pumpkin v", "ENCHANTMENT_TURBO_PUMPKIN_5");
        alias("turbo melon v", "ENCHANTMENT_TURBO_MELON_5");
        alias("turbo cocoa v", "ENCHANTMENT_TURBO_COCOA_5");
        alias("turbo cactus v", "ENCHANTMENT_TURBO_CACTUS_5");
        alias("turbo mushrooms v", "ENCHANTMENT_TURBO_MUSHROOMS_5");
        alias("turbo cane v", "ENCHANTMENT_TURBO_CANE_5");
        alias("turbo warts v", "ENCHANTMENT_TURBO_WARTS_5");
        alias("pesterminator i", "ENCHANTMENT_PESTERMINATOR_1");
        alias("sunset i", "ENCHANTMENT_SUNSET_1");
        alias("synthesis", "SYNTHESIS_GARDEN_CHIP");
        alias("synthesis chip", "SYNTHESIS_GARDEN_CHIP");
        alias("evergreen", "EVERGREEN_GARDEN_CHIP");
        alias("evergreen chip", "EVERGREEN_GARDEN_CHIP");
        alias("quickdraw", "QUICKDRAW_GARDEN_CHIP");
        alias("quickdraw chip", "QUICKDRAW_GARDEN_CHIP");
        alias("hypercharge", "HYPERCHARGE_GARDEN_CHIP");
        alias("hypercharge chip", "HYPERCHARGE_GARDEN_CHIP");
        alias("vermin vaporizer", "VERMIN_VAPORIZER_GARDEN_CHIP");
        alias("vermin vaporizer chip", "VERMIN_VAPORIZER_GARDEN_CHIP");
        alias("rare finder", "RAREFINDER_CHIP");
        alias("rare finder chip", "RAREFINDER_CHIP");
        alias("rareﬁnder chip", "RAREFINDER_CHIP");
        alias("cropshot", "CROPSHOT_GARDEN_CHIP");
        alias("cropshot chip", "CROPSHOT_GARDEN_CHIP");
        alias("sowledge", "SOWLEDGE_GARDEN_CHIP");
        alias("sowledge chip", "SOWLEDGE_GARDEN_CHIP");
        alias("mechamind", "MECHAMIND_GARDEN_CHIP");
        alias("mechamind chip", "MECHAMIND_GARDEN_CHIP");
        alias("overdrive", "OVERDRIVE_GARDEN_CHIP");
        alias("overdrive chip", "OVERDRIVE_GARDEN_CHIP");
    }

    private ItemPriceResolver() {}

    private static void alias(String name, String id) { ALIASES.put(normalize(name), id); }

    public static void warmup() { ensureRefresh(); }

    /** NPC sell price for an item id, or -1 if it cannot be sold to an NPC (or prices have not loaded yet). */
    public static double npcPrice(String itemId) {
        ensureRefresh();
        Double price = itemId == null ? null : npcPrices.get(itemId);
        return price == null ? -1 : price;
    }

    /** Returns the best currently cached sell value without blocking the render thread. */
    public static double value(String itemId) {
        if (itemId == null || itemId.isBlank()) return 0.0;
        Double bazaar = PriceService.bazaar().sell().get(itemId);
        if (bazaar != null && bazaar > 0) return bazaar;
        Double bin;
        synchronized (LOWEST_BIN_PRICES) { bin = LOWEST_BIN_PRICES.get(itemId); }
        if (bin != null && bin > 0) return bin;
        Double npc = npcPrices.get(itemId);
        return npc == null ? 0.0 : npc;
    }

    public static double valueByName(String name) {
        if (name == null || name.isBlank()) return 0.0;
        ensureRefresh();
        String normalized = normalize(name);
        String id = ALIASES.get(normalized);
        if (id == null) id = nameToId.get(normalized);
        if (id == null) id = generatedId(normalized);
        return id == null ? 0.0 : value(id);
    }

    /** Resolves pricing after the live caches are ready, so first drops do not display 0 coins. */
    public static CompletableFuture<Double> valueByNameAsync(String name) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(0.0);
        ensureRefresh();
        return currentRefresh().handle((ignored, error) -> valueByName(name));
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT)
            .replace('ﬁ', 'f')
            .replaceAll("[^a-z0-9 ]", "")
            .replaceAll("\\s+", " ").trim();
    }

    private static String generatedId(String normalized) {
        return switch (normalized) {
            case "wheat" -> "WHEAT";
            case "carrot" -> "CARROT_ITEM";
            case "potato" -> "POTATO_ITEM";
            case "pumpkin" -> "PUMPKIN";
            case "melon" -> "MELON";
            case "cactus" -> "CACTUS";
            case "sugar cane" -> "SUGAR_CANE";
            case "nether wart" -> "NETHER_STALK";
            case "cocoa beans" -> "INK_SACK:3";
            case "mushroom" -> "MUSHROOM_COLLECTION";
            default -> null;
        };
    }

    /** Starts refreshing whatever is stale: PriceService's market and item list, and the lowest BINs here. */
    private static void ensureRefresh() {
        PriceService.refreshMarket(false);
        long now = System.currentTimeMillis();
        if (now - lastRefresh < CACHE_TIME_MILLIS || !REFRESHING.compareAndSet(false, true)) return;

        binsFuture = get(LOWEST_BINS_URL).thenAccept(bins -> {
            try {
                Map<String, Double> binsMap = parseLowestBins(bins);
                synchronized (LOWEST_BIN_PRICES) { LOWEST_BIN_PRICES.clear(); LOWEST_BIN_PRICES.putAll(binsMap); }
                lastRefresh = System.currentTimeMillis();
            } catch (Throwable error) {
                System.err.println("[Sky2M] Failed to parse item prices: " + rootMessage(error));
            }
        }).exceptionally(error -> {
            System.err.println("[Sky2M] Failed to refresh item prices: " + rootMessage(error));
            return null;
        }).whenComplete((ignored, error) -> REFRESHING.set(false));
    }

    /** Completes when the current refreshes (market, item list, lowest BINs) are done, successfully or not. */
    private static CompletableFuture<Void> currentRefresh() {
        CompletableFuture<Void> market = PriceService.refreshMarket(false).exceptionally(error -> null);
        // Once the item list has downloaded, wait for the names and NPC prices built from it; if it failed, don't.
        CompletableFuture<Void> items = PriceService.requestItems()
            .handle((loaded, error) -> error == null ? ITEMS_READY : CompletableFuture.<Void>completedFuture(null))
            .thenCompose(future -> future);
        return CompletableFuture.allOf(market, items, binsFuture);
    }

    private static CompletableFuture<HttpResponse<String>> get(String url) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("HTTP " + response.statusCode());
            return response;
        });
    }

    private static Map<String, Double> parseNpcPrices(Map<String, JsonObject> items) {
        Map<String, Double> result = new HashMap<>();
        for (JsonObject item : items.values()) {
            if (!item.has("id") || !item.has("npc_sell_price")) continue;
            try {
                double price = item.get("npc_sell_price").getAsDouble();
                if (price > 0) result.put(item.get("id").getAsString(), price);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static Map<String, String> parseNames(Map<String, JsonObject> items) {
        Map<String, String> result = new HashMap<>();
        for (JsonObject item : items.values()) {
            try {
                if (item.has("id") && item.has("name"))
                    result.put(normalize(item.get("name").getAsString()), item.get("id").getAsString());
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static Map<String, Double> parseLowestBins(HttpResponse<String> response) {
        Map<String, Double> result = new HashMap<>();
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                if (entry.getValue().isJsonPrimitive()) {
                    double price = entry.getValue().getAsDouble();
                    if (price > 0) result.put(entry.getKey(), price);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }
}
