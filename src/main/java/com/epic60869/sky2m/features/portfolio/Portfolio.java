package com.epic60869.sky2m.features.portfolio;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portfolio / net worth tracker. You add items; their quantity is counted from your inventory and the Ender Chest and
 * backpack pages Sky2M has seen (Storage Search), plus an optional extra amount you type for items it can't see.
 * Prices come live from Hypixel's bazaar API and the lowest BIN / 3-day average API Skyblocker uses (hysky.de); rune
 * names and textures come from the NEU repo. Items are keyed by their price key (runes: AXE_SHATTER_RUNE_3).
 * Value snapshots are saved to config/sky2m/portfolio/history.json for the graph and CSV export, and auction/bazaar
 * sales of tracked items are logged (asking first, or automatically).
 */
public final class Portfolio {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final String LOWEST_BINS_URL = "https://hysky.de/api/auctions/lowestbins";
    private static final String AVERAGE_URL = "https://hysky.de/api/auctions/lowestbins/average?days=3";
    private static final long PRICE_REFRESH_MS = 60_000;
    private static final long SNAPSHOT_MS = 30 * 60_000;
    private static final long COUNT_REFRESH_MS = 3_000;

    private static final Pattern AUCTION_BOUGHT = Pattern.compile("^\\[Auction] (?<buyer>\\w+) bought (?<item>.+) for (?<price>[\\d,]+) coins.*$");
    private static final Pattern AUCTION_COLLECTED = Pattern.compile("^You collected (?<price>[\\d,]+) coins from selling (?<item>.+) to (?<buyer>.+) in an auction!$");
    private static final Pattern BAZAAR_SOLD = Pattern.compile("^\\[Bazaar] Sold (?<amount>[\\d,]+)x (?<item>.+) for (?<price>[\\d,.]+) coins!$");
    private static final Pattern BAZAAR_OFFER = Pattern.compile("^\\[Bazaar] Your Sell Offer for (?<amount>[\\d,]+)x (?<item>.+) was filled!$");

    /** Old single price setting, only read to carry it over to the two new ones. */
    public enum PriceMode { LOWEST_BIN, AVERAGE, INSTABUY }

    /** How auction house items are priced. */
    public enum AhMode {
        LOWEST_BIN("Lowest BIN"), AVERAGE("3-day avg");

        final String label;

        AhMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How bazaar items are priced. */
    public enum BzMode {
        SELL("Sell price"), BUY("Buy price");

        final String label;

        BzMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final class Entry {
        public String id;
        public String name;
        /** Price paid per item, or 0 when not set. */
        public double buyPrice;
        /** Extra copies Sky2M can't see (applied skins, pets, museum...). */
        public int extra;
        /** When the item was added, for profit per hour. */
        public long addedAt;
        /** The buy price is filled in with the lowest BIN once prices have loaded. */
        public boolean buyPricePending;
    }

    public static final class Sale {
        public String id;
        public String name;
        public int amount;
        public double price;
        public double buyPrice;
        public long time;
    }

    public static final class Snapshot {
        public long time;
        public double total;
        public Map<String, Double> prices = new HashMap<>();
    }

    private static final class Data {
        List<Entry> entries = new ArrayList<>();
        List<Sale> sold = new ArrayList<>();
        PriceMode priceMode;
        AhMode ahMode = AhMode.LOWEST_BIN;
        BzMode bzMode = BzMode.SELL;
        boolean autoRemove = false;
    }

    public record Count(int inventory, int storage, int extra) {
        public int total() {
            return inventory + storage + extra;
        }
    }

    private static Data data = new Data();
    private static List<Snapshot> history = new ArrayList<>();
    private static Path dir;

    private static volatile Map<String, Double> lowestBins = Map.of();
    private static volatile Map<String, Double> averages = Map.of();
    private static volatile Map<String, Double> bazaarSell = Map.of();
    private static volatile Map<String, Double> bazaarBuy = Map.of();
    private static volatile Map<String, Double> bestSellOffers = Map.of();
    private static volatile Map<String, Double> bestBuyOrders = Map.of();
    /** How many orders share the top price, for {@link #bazaarOrderBookCount}. */
    private static volatile Map<String, Integer> bestSellOfferCounts = Map.of();
    private static volatile Map<String, Integer> bestBuyOrderCounts = Map.of();
    private static volatile Map<String, String> names = Map.of();
    /** Rune price keys (e.g. AXE_SHATTER_RUNE_3) to names ("Barkshatter Rune III") and head textures, from the NEU repo. */
    private static volatile Map<String, String> runeNames = Map.of();
    private static volatile Map<String, String> runeTextures = Map.of();
    private static final AtomicBoolean RUNES_LOADING = new AtomicBoolean();
    /** One copy of each tracked item seen in your inventory or storage, for the icons. */
    private static final Map<String, ItemStack> SAMPLES = new HashMap<>();
    private static volatile long lastPriceRefresh;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();

    private static Map<String, Integer> inventoryCounts = Map.of();
    private static Map<String, Integer> storageCounts = Map.of();
    private static long lastCount;
    private static long lastSnapshot;

    private Portfolio() {}

    public static void init(Path configDir) {
        dir = configDir.resolve("sky2m").resolve("portfolio");
        load();
        ClientTickEvents.END_CLIENT_TICK.register(Portfolio::tick);
        Sky2MChat.onChat(message -> onChat(message.text()));
        registerCommands();
    }

    // ----- Accessors for the screen -----

    public static List<Entry> entries() {
        return data.entries;
    }

    public static List<Sale> sold() {
        return data.sold;
    }

    public static List<Snapshot> history() {
        return history;
    }

    public static AhMode ahMode() {
        return data.ahMode;
    }

    public static BzMode bzMode() {
        return data.bzMode;
    }

    public static void toggleAhMode() {
        data.ahMode = data.ahMode == AhMode.LOWEST_BIN ? AhMode.AVERAGE : AhMode.LOWEST_BIN;
        save();
    }

    public static void toggleBzMode() {
        data.bzMode = data.bzMode == BzMode.SELL ? BzMode.BUY : BzMode.SELL;
        save();
    }

    public static void removeSale(Sale sale) {
        data.sold.remove(sale);
        save();
    }

    public static boolean autoRemove() {
        return data.autoRemove;
    }

    public static void toggleAutoRemove() {
        data.autoRemove = !data.autoRemove;
        save();
    }

    public static boolean pricesLoaded() {
        return !lowestBins.isEmpty() || !bazaarSell.isEmpty();
    }

    public static long lastPriceRefresh() {
        return lastPriceRefresh;
    }

    // ----- Prices -----

    /** Current price of one item with the selected price mode, or 0 when unknown. */
    public static double price(String id) {
        Double sell = bazaarSell.get(id);
        if (sell != null) {
            if (data.bzMode == BzMode.BUY) {
                Double buy = bazaarBuy.get(id);
                if (buy != null) return buy;
            }
            return sell;
        }
        if (data.ahMode == AhMode.AVERAGE) {
            Double avg = averages.get(id);
            if (avg != null) return avg;
        }
        Double bin = lowestBins.get(id);
        if (bin != null) return bin;
        // Nobody is selling one right now (common for runes): the 3 day average, else the last price seen.
        Double avg = averages.get(id);
        if (avg != null) return avg;
        com.epic60869.sky2m.PriceHistory.Seen seen = com.epic60869.sky2m.PriceHistory.get(id);
        return seen == null ? 0 : seen.price();
    }

    /** Resolves a displayed Bazaar product name to the API's product id. */
    public static String bazaarProductId(String name) {
        return resolve(name);
    }

    /** Best current order-book price for an item: top buy order when {@code buyOrder}, top sell offer otherwise. */
    public static double bazaarOrderBookPrice(String id, boolean buyOrder) {
        Double price = (buyOrder ? bestBuyOrders : bestSellOffers).get(id);
        return price == null ? 0 : price;
    }

    /** How many orders are at the best price ({@link #bazaarOrderBookPrice}); 0 when unknown. */
    public static int bazaarOrderBookCount(String id, boolean buyOrder) {
        Integer count = (buyOrder ? bestBuyOrderCounts : bestSellOfferCounts).get(id);
        return count == null ? 0 : count;
    }

    /** What you'd pay right now: the lowest BIN, or the bazaar buy price. 0 when unknown. */
    private static double currentBuyPrice(String id) {
        Double bin = lowestBins.get(id);
        if (bin != null && bin > 0) return bin;
        Double buy = bazaarBuy.get(id);
        if (buy != null) return buy;
        com.epic60869.sky2m.PriceHistory.Seen seen = com.epic60869.sky2m.PriceHistory.get(id);
        return seen == null ? 0 : seen.price();
    }

    public static void refreshPrices(boolean force) {
        if (!force && System.currentTimeMillis() - lastPriceRefresh < PRICE_REFRESH_MS) return;
        if (!REFRESHING.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject bazaar = fetch(BAZAAR_URL);
                if (bazaar != null && bazaar.has("products")) {
                    Map<String, Double> sell = new HashMap<>(), buy = new HashMap<>();
                    Map<String, Double> bestSells = new HashMap<>(), bestBuys = new HashMap<>();
                    Map<String, Integer> sellCounts = new HashMap<>(), buyCounts = new HashMap<>();
                    for (var e : bazaar.getAsJsonObject("products").entrySet()) {
                        JsonObject product = e.getValue().getAsJsonObject();
                        JsonObject status = product.getAsJsonObject("quick_status");
                        if (status != null) {
                            sell.put(e.getKey(), status.get("sellPrice").getAsDouble());
                            buy.put(e.getKey(), status.get("buyPrice").getAsDouble());
                        }
                        // The API names them by what you'd do: sell_summary is the buy orders you'd sell into (top
                        // one first), buy_summary the sell offers you'd buy from (lowest first).
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
                    }
                    bazaarSell = sell;
                    bazaarBuy = buy;
                    bestSellOffers = bestSells;
                    bestBuyOrders = bestBuys;
                    bestSellOfferCounts = sellCounts;
                    bestBuyOrderCounts = buyCounts;
                }
                Map<String, Double> bins = numbers(fetch(LOWEST_BINS_URL));
                if (!bins.isEmpty()) {
                    lowestBins = bins;
                    com.epic60869.sky2m.PriceHistory.record(bins);
                }
                Map<String, Double> avg = numbers(fetch(AVERAGE_URL));
                if (!avg.isEmpty()) averages = avg;
                loadRuneNames();
                if (names.isEmpty()) {
                    JsonObject items = fetch(ITEMS_URL);
                    if (items != null && items.has("items")) {
                        Map<String, String> n = new HashMap<>();
                        for (JsonElement el : items.getAsJsonArray("items")) {
                            JsonObject item = el.getAsJsonObject();
                            if (item.has("id") && item.has("name")) n.put(item.get("id").getAsString(), strip(item.get("name").getAsString()));
                        }
                        names = n;
                    }
                }
            } finally {
                lastPriceRefresh = System.currentTimeMillis();
                REFRESHING.set(false);
            }
        });
    }

    private static final Pattern RUNE_KEY = Pattern.compile("^(.+)_RUNE_(\\d)$");
    private static final Pattern SKULL_TEXTURE = Pattern.compile("Value:\\\\?\"([A-Za-z0-9+/=]+)\\\\?\"");

    /**
     * Hypixel's item list only has one "Rune" item, so rune names and textures come from the NEU repo, once, for
     * every rune on the auction house (cached in config/sky2m/portfolio/runes.json).
     */
    /** Every rune with a known price: listed now, in the 3 day average, or seen before. */
    private static java.util.Set<String> runeKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String key : lowestBins.keySet()) if (RUNE_KEY.matcher(key).matches()) keys.add(key);
        for (String key : averages.keySet()) if (RUNE_KEY.matcher(key).matches()) keys.add(key);
        return keys;
    }

    private static void loadRuneNames() {
        if (!runeNames.isEmpty() || !RUNES_LOADING.compareAndSet(false, true)) return;
        Path cache = dir.resolve("runes.json");
        try {
            if (Files.exists(cache)) {
                JsonObject root = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, String> n = new HashMap<>(), t = new HashMap<>();
                for (var e : root.getAsJsonObject("names").entrySet()) n.put(e.getKey(), e.getValue().getAsString());
                for (var e : root.getAsJsonObject("textures").entrySet()) t.put(e.getKey(), e.getValue().getAsString());
                boolean complete = true;
                for (String key : runeKeys()) if (!n.containsKey(key)) complete = false;
                runeNames = n;
                runeTextures = t;
                if (complete) return;
            }
            Map<String, CompletableFuture<HttpResponse<String>>> requests = new HashMap<>();
            for (String key : runeKeys()) {
                if (runeNames.containsKey(key)) continue;
                Matcher m = RUNE_KEY.matcher(key);
                if (!m.matches()) continue;
                String file = m.group(1) + "_RUNE%3B" + m.group(2) + ".json";
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/" + file))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "Sky2M/1.0").GET().build();
                requests.put(key, HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
            }
            Map<String, String> n = new HashMap<>(runeNames), t = new HashMap<>(runeTextures);
            for (var e : requests.entrySet()) {
                try {
                    HttpResponse<String> response = e.getValue().join();
                    if (response.statusCode() != 200) continue;
                    JsonObject item = JsonParser.parseString(response.body()).getAsJsonObject();
                    String name = strip(item.get("displayname").getAsString()).replace("◆", "").trim();
                    if (!name.isEmpty()) n.put(e.getKey(), name);
                    Matcher tex = SKULL_TEXTURE.matcher(item.has("nbttag") ? item.get("nbttag").getAsString() : "");
                    if (tex.find()) t.put(e.getKey(), tex.group(1));
                } catch (Exception ignored) {}
            }
            runeNames = n;
            runeTextures = t;
            JsonObject root = new JsonObject();
            root.add("names", GSON.toJsonTree(n));
            root.add("textures", GSON.toJsonTree(t));
            Files.createDirectories(dir);
            Files.writeString(cache, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not load rune names: " + e.getMessage());
        } finally {
            RUNES_LOADING.set(false);
        }
    }

    private static JsonObject fetch(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[Sky2M] Portfolio price download failed for " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static Map<String, Double> numbers(JsonObject object) {
        Map<String, Double> result = new HashMap<>();
        if (object == null) return result;
        for (var e : object.entrySet()) {
            try {
                result.put(e.getKey(), e.getValue().getAsDouble());
            } catch (Exception ignored) {}
        }
        return result;
    }

    // ----- Quantities -----

    public static Count count(Entry entry) {
        return new Count(inventoryCounts.getOrDefault(entry.id, 0), storageCounts.getOrDefault(entry.id, 0), entry.extra);
    }

    public static void recount() {
        Minecraft mc = Minecraft.getInstance();
        // Items are counted by their price key, so runes, pets and enchanted books match their row.
        Map<String, Integer> inv = new HashMap<>();
        if (mc.player != null) {
            var inventory = mc.player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (stack.isEmpty()) continue;
                String id = Sky2MPriceTooltip.marketId(stack);
                if (id.isEmpty()) continue;
                inv.merge(id, stack.getCount(), Integer::sum);
                SAMPLES.putIfAbsent(id, stack.copyWithCount(1));
            }
            inventoryCounts = inv;
        }
        try {
            Map<String, Integer> stored = new HashMap<>();
            for (ItemStack stack : Sky2MStorageSearch.storedStacks()) {
                String id = Sky2MPriceTooltip.marketId(stack);
                if (id.isEmpty()) continue;
                stored.merge(id, stack.getCount(), Integer::sum);
                SAMPLES.putIfAbsent(id, stack.copyWithCount(1));
            }
            storageCounts = stored;
        } catch (Exception ignored) {}
        lastCount = System.currentTimeMillis();
    }

    public static double value(Entry entry) {
        return count(entry).total() * price(entry.id);
    }

    public static double totalValue() {
        double total = 0;
        for (Entry e : data.entries) total += value(e);
        return total;
    }

    /** Icon for a row: a copy you own, else the rune head, else the item from Hypixel's item list. Null if unknown. */
    public static ItemStack icon(Entry entry) {
        ItemStack sample = SAMPLES.get(entry.id);
        if (sample != null) return sample;
        String texture = runeTextures.get(entry.id);
        if (texture != null) {
            ItemStack head = Compat.createSkull(texture);
            SAMPLES.put(entry.id, head);
            return head;
        }
        if (com.epic60869.sky2m.custom.RepoItems.displayName(entry.id) == null) return null;
        ItemStack stack = com.epic60869.sky2m.custom.RepoItems.itemStack(entry.id);
        SAMPLES.put(entry.id, stack);
        return stack;
    }

    /** Profit per hour: each row's profit divided by the hours since it was added (at least one hour), summed. */
    public static double profitPerHour() {
        long now = System.currentTimeMillis();
        double total = 0;
        for (Entry e : data.entries) {
            if (e.buyPrice <= 0 || e.addedAt <= 0) continue;
            double price = price(e.id);
            if (price <= 0) continue;
            double hours = Math.max(1.0, (now - e.addedAt) / 3_600_000.0);
            total += count(e).total() * (price - e.buyPrice) / hours;
        }
        return total;
    }

    /** Profit/loss of rows that have a buy price set. */
    public static double totalProfit() {
        double total = 0;
        for (Entry e : data.entries) if (e.buyPrice > 0) total += count(e).total() * (price(e.id) - e.buyPrice);
        return total;
    }

    public static double realisedProfit() {
        double total = 0;
        for (Sale s : data.sold) if (s.buyPrice > 0) total += s.price - s.buyPrice * s.amount;
        return total;
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || data.entries.isEmpty()) return;
        refreshPrices(false);
        // Rows added before prices loaded get their default buy price now.
        boolean filled = false;
        for (Entry e : data.entries) {
            if (!e.buyPricePending) continue;
            double p = currentBuyPrice(e.id);
            if (p <= 0) continue;
            e.buyPrice = p;
            e.buyPricePending = false;
            filled = true;
        }
        if (filled) save();
        if (System.currentTimeMillis() - lastCount > COUNT_REFRESH_MS) recount();
        if (pricesLoaded() && System.currentTimeMillis() - lastSnapshot > SNAPSHOT_MS && lastPriceRefresh > 0) snapshot();
    }

    // ----- Editing -----

    /** Adds an item by SkyBlock id or display name. Returns the added entry, or null when not found. */
    public static Entry add(String query) {
        String id = resolve(query);
        if (id == null) return null;
        for (Entry e : data.entries) if (e.id.equals(id)) return e;
        Entry entry = newEntry(id);
        entry.name = runeNames.getOrDefault(id, names.getOrDefault(id, prettyId(id)));
        data.entries.add(entry);
        save();
        recount();
        return entry;
    }

    public static Entry addHeld() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        ItemStack stack = player.getMainHandItem();
        String id = Sky2MPriceTooltip.marketId(stack);
        if (id.isEmpty()) return null;
        for (Entry e : data.entries) if (e.id.equals(id)) return e;
        Entry entry = newEntry(id);
        entry.name = strip(Compat.realName(stack).getString()).replace("◆", "").trim();
        SAMPLES.put(id, stack.copyWithCount(1));
        data.entries.add(entry);
        save();
        recount();
        return entry;
    }

    /** A new row, with the buy price defaulting to what the item costs right now (you can still change it). */
    private static Entry newEntry(String id) {
        Entry entry = new Entry();
        entry.id = id;
        entry.addedAt = System.currentTimeMillis();
        entry.buyPrice = currentBuyPrice(id);
        entry.buyPricePending = entry.buyPrice <= 0;
        return entry;
    }

    public static void remove(Entry entry) {
        data.entries.remove(entry);
        save();
    }

    public static void changed() {
        save();
    }

    /** Resolves "PET_SKIN_X", "pet skin x" or a display name to a SkyBlock id. */
    private static String resolve(String query) {
        String q = query.trim();
        if (q.isEmpty()) return null;
        String asId = q.toUpperCase(Locale.ROOT).replace(' ', '_');
        if (names.containsKey(asId) || lowestBins.containsKey(asId) || bazaarSell.containsKey(asId)) return asId;
        String wanted = normalize(q);
        String partial = null;
        for (var e : runeNames.entrySet()) {
            String n = normalize(e.getValue());
            if (n.equals(wanted)) return e.getKey();
            if (partial == null && n.contains(wanted)) partial = e.getKey();
        }
        for (var e : names.entrySet()) {
            String n = normalize(e.getValue());
            if (n.equals(wanted)) return e.getKey();
            if (partial == null && n.contains(wanted)) partial = e.getKey();
        }
        return partial;
    }

    public static List<String> suggestions(String query, int limit) {
        List<String> out = new ArrayList<>();
        String wanted = normalize(query);
        if (wanted.length() < 2) return out;
        for (String name : names.values()) {
            if (normalize(name).contains(wanted)) out.add(name);
            if (out.size() >= limit) return out;
        }
        for (String name : runeNames.values()) {
            if (normalize(name).contains(wanted)) out.add(name);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ----- Sales -----

    private static void onChat(String text) {
        if (data.entries.isEmpty()) return;
        Matcher m;
        String item = null;
        int amount = 1;
        double price = 0;
        if ((m = AUCTION_BOUGHT.matcher(text)).matches() || (m = AUCTION_COLLECTED.matcher(text)).matches()) {
            item = m.group("item");
            price = parse(m.group("price"));
        } else if ((m = BAZAAR_SOLD.matcher(text)).matches()) {
            item = m.group("item");
            amount = (int) parse(m.group("amount"));
            price = parse(m.group("price"));
        } else if ((m = BAZAAR_OFFER.matcher(text)).matches()) {
            item = m.group("item");
            amount = (int) parse(m.group("amount"));
        }
        if (item == null) return;
        Entry entry = match(item);
        if (entry == null) return;
        if (data.autoRemove) {
            recordSale(entry, amount, price, true);
            return;
        }
        String cmd = "/s2 portfolio sold " + entry.id + " " + amount + " " + (long) price;
        MutableComponent line = Component.literal("Sold " + (amount > 1 ? amount + "x " : "") + entry.name + (price > 0 ? " for " + coins(price) : "") + ". ").withStyle(ChatFormatting.GOLD)
            .append(button("[Log sale & remove]", cmd + " remove", ChatFormatting.GREEN))
            .append(Component.literal(" "))
            .append(button("[Log sale, keep row]", cmd + " keep", ChatFormatting.YELLOW));
        Sky2MAlerts.chat(line);
    }

    private static Component button(String text, String command, ChatFormatting colour) {
        return Component.literal(text).withStyle(style -> style.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    private static Entry match(String item) {
        String wanted = normalize(item);
        for (Entry e : data.entries) if (normalize(e.name).equals(wanted)) return e;
        for (Entry e : data.entries) {
            String n = normalize(e.name);
            if (!n.isEmpty() && (wanted.contains(n) || n.contains(wanted))) return e;
        }
        return null;
    }

    public static void recordSale(Entry entry, int amount, double price, boolean removeRow) {
        Sale sale = new Sale();
        sale.id = entry.id;
        sale.name = entry.name;
        sale.amount = amount;
        sale.price = price;
        sale.buyPrice = entry.buyPrice;
        sale.time = System.currentTimeMillis();
        data.sold.add(sale);
        if (entry.extra > 0) entry.extra = Math.max(0, entry.extra - amount);
        if (removeRow) data.entries.remove(entry);
        save();
        String profit = entry.buyPrice > 0 && price > 0 ? " (profit " + coins(price - entry.buyPrice * amount) + ")" : "";
        Sky2MAlerts.chat(Component.literal("Logged sale of " + entry.name + (price > 0 ? " for " + coins(price) : "") + profit
            + (removeRow ? " and removed it from your portfolio." : ".")).withStyle(ChatFormatting.GREEN));
    }

    // ----- History and export -----

    public static void snapshot() {
        lastSnapshot = System.currentTimeMillis();
        if (data.entries.isEmpty()) return;
        Snapshot s = new Snapshot();
        s.time = lastSnapshot;
        s.total = totalValue();
        for (Entry e : data.entries) s.prices.put(e.id, price(e.id));
        history.add(s);
        if (history.size() > 5000) history.remove(0);
        saveHistory();
    }

    /** Writes portfolio.csv and history.csv (open them in Excel or Google Sheets). Returns the folder. */
    public static Path exportCsv() throws Exception {
        Files.createDirectories(dir);
        StringBuilder sheet = new StringBuilder("Item,ID,Inventory,Storage,Extra,Total,Buy price each,Price each,Value,Profit/loss\n");
        for (Entry e : data.entries) {
            Count c = count(e);
            double p = price(e.id);
            sheet.append(csv(e.name)).append(',').append(e.id).append(',').append(c.inventory()).append(',').append(c.storage()).append(',')
                .append(c.extra()).append(',').append(c.total()).append(',').append(num(e.buyPrice)).append(',').append(num(p)).append(',')
                .append(num(c.total() * p)).append(',').append(e.buyPrice > 0 ? num(c.total() * (p - e.buyPrice)) : "").append('\n');
        }
        sheet.append("TOTAL,,,,,,,,").append(num(totalValue())).append(',').append(num(totalProfit())).append('\n');
        Files.writeString(dir.resolve("portfolio.csv"), sheet.toString(), StandardCharsets.UTF_8);

        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        StringBuilder hist = new StringBuilder("Time,Total value\n");
        for (Snapshot s : history) hist.append(format.format(Instant.ofEpochMilli(s.time))).append(',').append(num(s.total)).append('\n');
        Files.writeString(dir.resolve("history.csv"), hist.toString(), StandardCharsets.UTF_8);

        StringBuilder sales = new StringBuilder("Time,Item,Amount,Sold for,Bought for each,Profit\n");
        for (Sale s : data.sold) {
            sales.append(format.format(Instant.ofEpochMilli(s.time))).append(',').append(csv(s.name)).append(',').append(s.amount).append(',')
                .append(num(s.price)).append(',').append(num(s.buyPrice)).append(',')
                .append(s.buyPrice > 0 && s.price > 0 ? num(s.price - s.buyPrice * s.amount) : "").append('\n');
        }
        Files.writeString(dir.resolve("sold.csv"), sales.toString(), StandardCharsets.UTF_8);
        return dir;
    }

    private static String csv(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String num(double value) {
        return String.format(Locale.US, "%.0f", value);
    }

    // ----- Storage -----

    private static void load() {
        try {
            Path file = dir.resolve("portfolio.json");
            if (Files.exists(file)) {
                Data loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
                if (loaded != null) {
                    data = loaded;
                    if (data.entries == null) data.entries = new ArrayList<>();
                    if (data.sold == null) data.sold = new ArrayList<>();
                    if (data.ahMode == null) data.ahMode = AhMode.LOWEST_BIN;
                    if (data.bzMode == null) data.bzMode = BzMode.SELL;
                    if (data.priceMode != null) {
                        data.ahMode = data.priceMode == PriceMode.AVERAGE ? AhMode.AVERAGE : AhMode.LOWEST_BIN;
                        data.bzMode = data.priceMode == PriceMode.INSTABUY ? BzMode.BUY : BzMode.SELL;
                        data.priceMode = null;
                    }
                }
            }
            Path hist = dir.resolve("history.json");
            if (Files.exists(hist)) {
                List<Snapshot> loaded = GSON.fromJson(Files.readString(hist, StandardCharsets.UTF_8), new TypeToken<List<Snapshot>>() {}.getType());
                if (loaded != null) history = new ArrayList<>(loaded);
                if (!history.isEmpty()) lastSnapshot = history.getLast().time;
            }
            // Rows from before "added at" existed: use the first snapshot that has them, else now.
            for (Entry e : data.entries) {
                if (e.addedAt > 0) continue;
                e.addedAt = System.currentTimeMillis();
                for (Snapshot snap : history) {
                    if (snap.prices.containsKey(e.id)) {
                        e.addedAt = snap.time;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read portfolio: " + e);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("portfolio.json"), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save portfolio: " + e);
        }
    }

    private static void saveHistory() {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("history.json"), new Gson().toJson(history), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save portfolio history: " + e);
        }
    }

    // ----- Commands -----

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("portfolio")
                    .executes(c -> Compat.queueOpenScreen(new PortfolioScreen()))
                    .then(ClientCommands.literal("add")
                        .executes(c -> {
                            Entry e = addHeld();
                            return say(e == null ? "Hold a SkyBlock item, or use /s2 portfolio add <name or ID>." : "Added " + e.name + " to your portfolio.", e == null ? ChatFormatting.RED : ChatFormatting.GREEN);
                        })
                        .then(ClientCommands.argument("item", StringArgumentType.greedyString()).executes(c -> {
                            String q = StringArgumentType.getString(c, "item");
                            Entry e = add(q);
                            return say(e == null ? "Couldn't find \"" + q + "\". Try its SkyBlock ID (e.g. PET_SKIN_ENDERMAN)." : "Added " + e.name + " to your portfolio.", e == null ? ChatFormatting.RED : ChatFormatting.GREEN);
                        })))
                    .then(ClientCommands.literal("export").executes(c -> {
                        try {
                            Path out = exportCsv();
                            Sky2MAlerts.chat(Component.literal("Exported portfolio.csv, history.csv and sold.csv to ").withStyle(ChatFormatting.GREEN)
                                .append(Component.literal(out.toString()).withStyle(style -> style.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(out.toAbsolutePath())))));
                            return 1;
                        } catch (Exception e) {
                            return say("Export failed: " + e.getMessage(), ChatFormatting.RED);
                        }
                    }))
                    .then(ClientCommands.literal("sold")
                        .then(ClientCommands.argument("id", StringArgumentType.word())
                            .then(ClientCommands.argument("amount", IntegerArgumentType.integer(1))
                                .then(ClientCommands.argument("price", DoubleArgumentType.doubleArg(0))
                                    .then(ClientCommands.literal("remove").executes(c -> sold(c.getArgument("id", String.class), IntegerArgumentType.getInteger(c, "amount"), DoubleArgumentType.getDouble(c, "price"), true)))
                                    .then(ClientCommands.literal("keep").executes(c -> sold(c.getArgument("id", String.class), IntegerArgumentType.getInteger(c, "amount"), DoubleArgumentType.getDouble(c, "price"), false)))))))));
            }
        });
    }

    private static int sold(String id, int amount, double price, boolean remove) {
        for (Entry e : data.entries) {
            if (e.id.equals(id)) {
                recordSale(e, amount, price, remove);
                return 1;
            }
        }
        return say("That item is no longer in your portfolio.", ChatFormatting.RED);
    }

    // ----- Helpers -----

    public static String coins(double value) {
        String sign = value < 0 ? "-" : "";
        double v = Math.abs(value);
        if (v >= 1_000_000_000) return sign + String.format(Locale.US, "%.2fB", v / 1_000_000_000);
        if (v >= 1_000_000) return sign + String.format(Locale.US, "%.2fM", v / 1_000_000);
        if (v >= 1_000) return sign + String.format(Locale.US, "%.1fk", v / 1_000);
        return sign + String.format(Locale.US, "%.0f", v);
    }

    /** Parses "1,234", "1.5m", "200k", "2b". Returns -1 when invalid. */
    public static double parse(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT).replace(",", "");
        if (t.isEmpty()) return -1;
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k') mult = 1_000;
        else if (last == 'm') mult = 1_000_000;
        else if (last == 'b') mult = 1_000_000_000;
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String prettyId(String id) {
        StringBuilder out = new StringBuilder();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String strip(String text) {
        String s = ChatFormatting.stripFormatting(text);
        return s == null ? "" : s.trim();
    }

    private static String normalize(String text) {
        return strip(text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim();
    }

    private static int say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
        return 1;
    }
}
