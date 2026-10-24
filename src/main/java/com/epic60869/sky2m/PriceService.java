package com.epic60869.sky2m;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The one place Sky2M downloads shared price data from, so each source is fetched once however many features use it:
 * Hypixel's SkyBlock item list (once per start, retried with backoff on failure), Hypixel's bazaar and hysky.de's
 * lowest BINs and 3 day averages (refreshed on demand, at most once a minute). Everything runs on one background
 * thread; readers get immutable snapshots from volatile fields and never block or touch the network.
 */
public final class PriceService {
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String LOWEST_BINS_URL = "https://hysky.de/api/auctions/lowestbins";
    private static final String AVERAGE_URL = "https://hysky.de/api/auctions/lowestbins/average?days=3";
    private static final long MARKET_REFRESH_MS = 60_000;
    private static final long ITEMS_RETRY_MIN_MS = 30_000, ITEMS_RETRY_MAX_MS = 10 * 60_000;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Sky2M Prices");
        thread.setDaemon(true);
        return thread;
    });

    /** Bazaar prices: quick_status insta-buy/insta-sell, plus the top of each order book (for the Portfolio). */
    public record Bazaar(Map<String, Double> buy, Map<String, Double> sell,
                         Map<String, Double> bestSellOffers, Map<String, Double> bestBuyOrders,
                         Map<String, Integer> bestSellOfferCounts, Map<String, Integer> bestBuyOrderCounts) {
        public static final Bazaar EMPTY = new Bazaar(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static volatile Map<String, JsonObject> items = Map.of();
    private static volatile Bazaar bazaar = Bazaar.EMPTY;
    private static volatile Map<String, Double> lowestBins = Map.of();
    private static volatile Map<String, Double> threeDayAverages = Map.of();
    private static volatile long lastMarketRefresh;
    /** Goes up every time any snapshot changes, so callers with derived data can tell when to rebuild it. */
    private static volatile long version;

    private static final Object LOCK = new Object();
    private static CompletableFuture<Void> marketFuture = CompletableFuture.completedFuture(null);
    private static CompletableFuture<Map<String, JsonObject>> itemsAttempt;
    private static boolean itemsRetryScheduled;
    private static long itemsRetryMs = ITEMS_RETRY_MIN_MS;

    private static final List<Consumer<Map<String, JsonObject>>> ITEM_LISTENERS = new ArrayList<>();
    private static final List<Runnable> MARKET_LISTENERS = new CopyOnWriteArrayList<>();

    private PriceService() {}

    // ----- Snapshots -----

    /** Hypixel's SkyBlock items by id (the raw item objects, in the API's order; do not modify). Empty until loaded. */
    public static Map<String, JsonObject> items() {
        return items;
    }

    public static boolean itemsLoaded() {
        return !items.isEmpty();
    }

    public static Bazaar bazaar() {
        return bazaar;
    }

    /** hysky.de lowest BINs by price key; empty until loaded. */
    public static Map<String, Double> lowestBins() {
        return lowestBins;
    }

    /** hysky.de 3 day average lowest BINs by price key; empty until loaded. */
    public static Map<String, Double> threeDayAverages() {
        return threeDayAverages;
    }

    /** When the market data (bazaar, BINs, averages) last finished refreshing (successfully or not); 0 if never. */
    public static long lastMarketRefresh() {
        return lastMarketRefresh;
    }

    public static long version() {
        return version;
    }

    // ----- Loading -----

    /**
     * The item list: a completed future once loaded, else the current download (started now if none is running,
     * skipping any backoff wait). The returned future fails if that download fails; a retry is scheduled anyway.
     */
    public static CompletableFuture<Map<String, JsonObject>> loadItems() {
        synchronized (LOCK) {
            Map<String, JsonObject> loaded = items;
            if (!loaded.isEmpty()) return CompletableFuture.completedFuture(loaded);
            if (itemsAttempt != null && !itemsAttempt.isCompletedExceptionally()) return itemsAttempt;
            itemsAttempt = CompletableFuture.supplyAsync(PriceService::downloadItems, EXECUTOR);
            itemsAttempt.whenCompleteAsync((result, error) -> {
                if (error == null) itemsLoaded(result);
                else itemsFailed(error);
            }, EXECUTOR);
            return itemsAttempt;
        }
    }

    /**
     * Like {@link #loadItems} but respecting the retry backoff: starts the first download if none has been tried,
     * otherwise returns the latest attempt (which may have failed; a retry is then already scheduled).
     */
    public static CompletableFuture<Map<String, JsonObject>> requestItems() {
        synchronized (LOCK) {
            Map<String, JsonObject> loaded = items;
            if (!loaded.isEmpty()) return CompletableFuture.completedFuture(loaded);
            if (itemsAttempt != null) return itemsAttempt;
        }
        return loadItems();
    }

    /**
     * Calls {@code listener} with the item list once it has loaded (straight away, on the price thread, if it already
     * has), and starts loading it if nothing has yet.
     */
    public static void onItems(Consumer<Map<String, JsonObject>> listener) {
        Map<String, JsonObject> loaded;
        synchronized (ITEM_LISTENERS) {
            loaded = items;
            if (loaded.isEmpty()) ITEM_LISTENERS.add(listener);
        }
        if (loaded.isEmpty()) requestItems();
        else EXECUTOR.execute(() -> runItemListener(listener, loaded));
    }

    /** Runs {@code listener} on the price thread after every market refresh. */
    public static void onMarketRefreshed(Runnable listener) {
        MARKET_LISTENERS.add(listener);
    }

    /**
     * Refreshes the bazaar, lowest BINs and 3 day averages in the background when they are over a minute old (or
     * always, with {@code force}), unless a refresh is already running. Never blocks. The future completes when the
     * running (or just started) refresh is done; it is already complete when nothing needed refreshing.
     */
    public static CompletableFuture<Void> refreshMarket(boolean force) {
        if (items.isEmpty()) requestItems();
        synchronized (LOCK) {
            if (!marketFuture.isDone()) return marketFuture;
            if (!force && System.currentTimeMillis() - lastMarketRefresh < MARKET_REFRESH_MS) return marketFuture;
            marketFuture = CompletableFuture.runAsync(PriceService::downloadMarket, EXECUTOR);
            return marketFuture;
        }
    }

    private static void downloadMarket() {
        try {
            Map<String, Double> bins = numbers(fetch(LOWEST_BINS_URL));
            if (!bins.isEmpty()) {
                lowestBins = Collections.unmodifiableMap(bins);
                PriceHistory.record(bins);
                version++;
            }
            Map<String, Double> average = numbers(fetch(AVERAGE_URL));
            if (!average.isEmpty()) {
                threeDayAverages = Collections.unmodifiableMap(average);
                version++;
            }
            Bazaar parsed = parseBazaar(fetch(BAZAAR_URL));
            if (parsed != null) {
                bazaar = parsed;
                version++;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Price refresh failed: " + e.getMessage());
        } finally {
            lastMarketRefresh = System.currentTimeMillis();
        }
        for (Runnable listener : MARKET_LISTENERS) {
            try {
                listener.run();
            } catch (Exception e) {
                System.err.println("[Sky2M] Price listener failed: " + e.getMessage());
            }
        }
    }

    private static Bazaar parseBazaar(JsonObject root) {
        if (root == null || !root.has("products") || !root.get("products").isJsonObject()) return null;
        Map<String, Double> buy = new HashMap<>(), sell = new HashMap<>();
        Map<String, Double> bestSells = new HashMap<>(), bestBuys = new HashMap<>();
        Map<String, Integer> sellCounts = new HashMap<>(), buyCounts = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("products").entrySet()) {
            try {
                JsonObject product = e.getValue().getAsJsonObject();
                JsonObject status = product.getAsJsonObject("quick_status");
                if (status != null) {
                    buy.put(e.getKey(), status.get("buyPrice").getAsDouble());
                    sell.put(e.getKey(), status.get("sellPrice").getAsDouble());
                }
                // The API names them by what you'd do: sell_summary is the buy orders you'd sell into (top one
                // first), buy_summary the sell offers you'd buy from (lowest first).
                if (product.has("buy_summary") && product.getAsJsonArray("buy_summary").size() > 0) {
                    JsonObject top = product.getAsJsonArray("buy_summary").get(0).getAsJsonObject();
                    bestSells.put(e.getKey(), top.get("pricePerUnit").getAsDouble());
                    if (top.has("orders")) sellCounts.put(e.getKey(), top.get("orders").getAsInt());
                }
                if (product.has("sell_summary") && product.getAsJsonArray("sell_summary").size() > 0) {
                    JsonObject top = product.getAsJsonArray("sell_summary").get(0).getAsJsonObject();
                    bestBuys.put(e.getKey(), top.get("pricePerUnit").getAsDouble());
                    if (top.has("orders")) buyCounts.put(e.getKey(), top.get("orders").getAsInt());
                }
            } catch (Exception ignored) {}
        }
        if (buy.isEmpty()) return null;
        return new Bazaar(Collections.unmodifiableMap(buy), Collections.unmodifiableMap(sell),
            Collections.unmodifiableMap(bestSells), Collections.unmodifiableMap(bestBuys),
            Collections.unmodifiableMap(sellCounts), Collections.unmodifiableMap(buyCounts));
    }

    /** The item list changes rarely, so the last download is kept on disk and reused for this long. */
    private static final long ITEMS_CACHE_MS = 6L * 60 * 60 * 1000;

    /** The item list from the disk copy when it is fresh, else downloaded (and saved); the old copy if offline. */
    private static JsonObject fetchItemsCached() {
        java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("hypixel-items.json");
        JsonObject old = null;
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                long age = System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(file).toMillis();
                if (age >= 0 && age < ITEMS_CACHE_MS) {
                    JsonObject cached = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
                    if (cached.has("items")) return cached;
                }
            }
        } catch (Exception ignored) {}
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ITEMS_URL)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
                if (parsed.has("items")) {
                    try {
                        java.nio.file.Files.createDirectories(file.getParent());
                        java.nio.file.Files.writeString(file, response.body());
                    } catch (Exception ignored) {}
                    return parsed;
                }
            } else {
                System.err.println("[Sky2M] Price download failed for " + ITEMS_URL + ": HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Price download failed for " + ITEMS_URL + ": " + e.getMessage());
        }
        try {
            if (java.nio.file.Files.isRegularFile(file)) old = JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
        } catch (Exception ignored) {}
        return old;
    }

    private static Map<String, JsonObject> downloadItems() {
        JsonObject root = fetchItemsCached();
        if (root == null || !root.has("items") || !root.get("items").isJsonArray()) {
            throw new IllegalStateException("no item list");
        }
        JsonArray array = root.getAsJsonArray("items");
        Map<String, JsonObject> out = new LinkedHashMap<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!item.has("id")) continue;
            try {
                out.put(item.get("id").getAsString(), item);
            } catch (Exception ignored) {}
        }
        if (out.isEmpty()) throw new IllegalStateException("empty item list");
        return Collections.unmodifiableMap(out);
    }

    private static void itemsLoaded(Map<String, JsonObject> loaded) {
        List<Consumer<Map<String, JsonObject>>> listeners;
        synchronized (ITEM_LISTENERS) {
            items = loaded;
            version++;
            listeners = List.copyOf(ITEM_LISTENERS);
            ITEM_LISTENERS.clear();
        }
        for (Consumer<Map<String, JsonObject>> listener : listeners) runItemListener(listener, loaded);
    }

    private static void itemsFailed(Throwable error) {
        System.err.println("[Sky2M] SkyBlock item list download failed: " + rootMessage(error));
        synchronized (LOCK) {
            if (itemsRetryScheduled) return;
            itemsRetryScheduled = true;
            long delay = itemsRetryMs;
            itemsRetryMs = Math.min(ITEMS_RETRY_MAX_MS, itemsRetryMs * 2);
            EXECUTOR.schedule(() -> {
                synchronized (LOCK) {
                    itemsRetryScheduled = false;
                }
                loadItems();
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void runItemListener(Consumer<Map<String, JsonObject>> listener, Map<String, JsonObject> loaded) {
        try {
            listener.accept(loaded);
        } catch (Exception e) {
            System.err.println("[Sky2M] Item list listener failed: " + e);
        }
    }

    // ----- HTTP -----

    /** GETs a JSON object; null on any failure (logged). */
    private static JsonObject fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Sky2M] Price download failed for " + url + ": HTTP " + response.statusCode());
                return null;
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[Sky2M] Price download failed for " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Double> numbers(JsonObject object) {
        Map<String, Double> result = new HashMap<>();
        if (object == null) return result;
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            try {
                result.put(e.getKey(), e.getValue().getAsDouble());
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
