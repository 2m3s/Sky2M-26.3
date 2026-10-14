package com.epic60869.sky2m.features.profileviewer.networth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A profile's networth the way SkyHelper (and the bots and sites that use it) works it out: a Java port of
 * SkyHelper-Networth (MIT, https://github.com/Altpapier/SkyHelper-Networth, v2.11.1, commit
 * 1a56f2ab2309e39d037708ca906e140e725ff013): calculators/ProfileNetworthCalculator.js, BasicItemNetworthCalculator.js,
 * helper/parseItems.js and helper/toolkits.js, with {@link SkyHelperItem} and {@link SkyHelperPet}. It uses the same
 * price list (SkyHelperBot/Prices pricesV2.json, cached five minutes) and Hypixel's item list (from PriceService).
 */
public final class SkyHelperNetworth {
    private static final String PRICES_URL = "https://raw.githubusercontent.com/SkyHelperBot/Prices/main/pricesV2.json";
    private static final long PRICES_MS = 5 * 60 * 1000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /** A category's total and its most valuable items (stacked by name). */
    public record Category(String name, double total, List<Map.Entry<String, Double>> top) {}

    public record Result(double total, double purse, double bank, double personalBank, List<Category> categories) {}

    private SkyHelperNetworth() {}

    // ------------------------------------------------------------------------------------------------ data

    private static volatile CompletableFuture<Map<String, Double>> prices;
    private static volatile long pricesAt;

    /** SkyHelper's price list, at most five minutes old. */
    public static synchronized CompletableFuture<Map<String, Double>> prices() {
        CompletableFuture<Map<String, Double>> f = prices;
        boolean stale = System.currentTimeMillis() - pricesAt > PRICES_MS;
        if (f != null && !(f.isDone() && (f.isCompletedExceptionally() || stale))) return f;
        pricesAt = System.currentTimeMillis();
        prices = get(PRICES_URL).thenApply(body -> {
            Map<String, Double> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : JsonParser.parseString(body).getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) out.put(e.getKey(), e.getValue().getAsDouble());
            }
            return out;
        });
        return prices;
    }

    /**
     * Hypixel's SkyBlock items (category, stars, gemstone slots...), shared with the rest of Sky2M through
     * PriceService (downloaded once per start). Fails if the download fails; asking again retries.
     */
    public static CompletableFuture<Map<String, JsonObject>> items() {
        return com.epic60869.sky2m.PriceService.loadItems();
    }

    private static CompletableFuture<String> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "Sky2M").build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
            return r.body();
        });
    }

    /** A profile's networth once the prices and items are loaded; {@code museum} is the member's museum or null. */
    public static CompletableFuture<Result> calculate(JsonObject member, JsonObject museum, double bankBalance) {
        return prices().thenCombine(items(), (p, i) -> calculate(member, museum, bankBalance, p, i));
    }

    // ------------------------------------------------------------------------------------------------ ProfileNetworthCalculator.js

    public static Result calculate(JsonObject member, JsonObject museum, double bankBalance, Map<String, Double> prices, Map<String, JsonObject> items) {
        Map<String, List<Object>> parsed = parseItems(member, museum, items);
        List<Category> categories = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, List<Object>> e : parsed.entrySet()) {
            String category = e.getKey();
            double sum = 0;
            Map<String, Double> byName = new LinkedHashMap<>();
            for (Object raw : e.getValue()) {
                Map<String, Object> item = SkyHelperItem.map(raw);
                if (item == null || item.isEmpty()) continue;
                double value = 0;
                String name;
                Map<String, Object> tag = SkyHelperItem.map(item.get("tag"));
                Map<String, Object> extra = tag == null ? null : SkyHelperItem.map(tag.get("ExtraAttributes"));
                if (category.equals("sacks") || category.equals("essence")) {
                    String id = SkyHelperItem.str(item.get("id"));
                    double amount = SkyHelperItem.num(item.get("amount"));
                    if (id == null || !(amount > 0)) continue;
                    value = SkyHelperItem.price(prices, id) * amount;
                    name = id;
                } else if (category.equals("pets") || (extra != null && extra.get("petInfo") != null)) {
                    Map<String, Object> petData = item;
                    if (extra != null && extra.get("petInfo") != null) {
                        try {
                            petData = SkyHelperItem.map(toJava(JsonParser.parseString(SkyHelperItem.str(extra.get("petInfo")))));
                        } catch (RuntimeException ex) {
                            continue;
                        }
                    }
                    SkyHelperPet pet = petData == null ? null : SkyHelperPet.of(petData);
                    if (pet == null) continue;
                    value = pet.networth(prices);
                    name = SkyHelperItem.titleCaseId(SkyHelperItem.str(petData.get("type")));
                } else {
                    if (extra == null && item.get("exp") == null && !(item.get("id") instanceof String)) continue;
                    SkyHelperItem calc = SkyHelperItem.of(item, items);
                    if (calc == null) continue;
                    value = calc.networth(prices, items);
                    name = SkyHelperItem.name(item);
                }
                if (Double.isNaN(value) || value == 0) continue;
                sum += value;
                byName.merge(name, value, Double::sum);
            }
            List<Map.Entry<String, Double>> top = new ArrayList<>(byName.entrySet());
            top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            categories.add(new Category(category, sum, top.size() > 8 ? new ArrayList<>(top.subList(0, 8)) : top));
            total += sum;
        }
        double purse = num(path(member, "currencies"), "coin_purse");
        double personal = num(path(member, "profile"), "bank_account");
        return new Result(total + bankBalance + purse + personal, purse, bankBalance, personal, categories);
    }

    // ------------------------------------------------------------------------------------------------ parseItems.js

    private static final String[][] INVENTORIES = {
        {"armor", "inventory.inv_armor"}, {"equipment", "inventory.equipment_contents"}, {"inventory", "inventory.inv_contents"},
        {"enderchest", "inventory.ender_chest_contents"}, {"accessories", "inventory.bag_contents.talisman_bag"},
        {"personal_vault", "inventory.personal_vault_contents"}, {"fishing_bag", "inventory.bag_contents.fishing_bag"},
        {"potion_bag", "inventory.bag_contents.potion_bag"}, {"sacks_bag", "inventory.bag_contents.sacks_bag"},
        {"candy_inventory", "shared_inventory.candy_inventory_contents"}, {"carnival_mask_inventory", "shared_inventory.carnival_mask_inventory_contents"},
        {"quiver", "inventory.bag_contents.quiver"}};

    private static Map<String, List<Object>> parseItems(JsonObject member, JsonObject museum, Map<String, JsonObject> items) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        for (String[] inv : INVENTORIES) out.put(inv[0], decoded(path(member, inv[1])));
        List<Object> storage = new ArrayList<>();
        JsonObject inventory = path(member, "inventory");
        for (String key : new String[]{"backpack_contents", "backpack_icons"}) {
            JsonObject packs = path(inventory, key);
            if (packs != null) for (Map.Entry<String, JsonElement> e : packs.entrySet()) {
                if (e.getValue().isJsonObject()) storage.addAll(decoded(e.getValue().getAsJsonObject()));
            }
        }
        out.put("storage", storage);
        List<Object> wardrobe = new ArrayList<>();
        JsonObject armour = path(member, "loadout.armor");
        if (armour != null) for (Map.Entry<String, JsonElement> e : armour.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            for (String piece : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) wardrobe.addAll(decoded(path(e.getValue().getAsJsonObject(), piece)));
        }
        out.put("wardrobe", wardrobe);
        JsonObject equipment = path(member, "loadout.equipment");
        if (equipment != null) for (Map.Entry<String, JsonElement> e : equipment.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            for (int slot = 1; slot <= 4; slot++) out.get("equipment").addAll(decoded(path(e.getValue().getAsJsonObject(), "EQUIPMENT_SLOT_" + slot)));
        }
        List<Object> museumItems = new ArrayList<>();
        if (museum != null) {
            JsonObject donated = path(museum, "items");
            if (donated != null) for (Map.Entry<String, JsonElement> e : donated.entrySet()) {
                if (!e.getValue().isJsonObject()) continue;
                JsonObject entry = e.getValue().getAsJsonObject();
                if (entry.has("borrowing") && entry.get("borrowing").getAsBoolean()) continue;
                museumItems.addAll(decoded(path(entry, "items")));
            }
            if (museum.has("special") && museum.get("special").isJsonArray()) {
                for (JsonElement s : museum.getAsJsonArray("special")) if (s.isJsonObject()) museumItems.addAll(decoded(path(s.getAsJsonObject(), "items")));
            }
        }
        out.put("museum", museumItems);
        out.put("farming_toolkit", toolkit(path(member, "garden_player_data.farming_toolkit"), items));
        out.put("hunting_toolkit", toolkit(path(member, "foraging.hunting_toolkit"), items));

        List<Object> sacks = new ArrayList<>();
        JsonObject sackCounts = member.has("sacks_counts") ? path(member, "sacks_counts") : path(member, "inventory.sacks_counts");
        if (sackCounts != null) for (Map.Entry<String, JsonElement> e : sackCounts.entrySet()) {
            double amount = e.getValue().isJsonPrimitive() ? e.getValue().getAsDouble() : 0;
            if (amount != 0) sacks.add(Map.of("id", e.getKey(), "amount", amount));
        }
        out.put("sacks", sacks);
        List<Object> essence = new ArrayList<>();
        JsonObject ess = path(member, "currencies.essence");
        if (ess != null) for (Map.Entry<String, JsonElement> e : ess.entrySet()) {
            double amount = e.getValue().isJsonObject() ? num(e.getValue().getAsJsonObject(), "current") : 0;
            essence.add(Map.of("id", "ESSENCE_" + e.getKey().toUpperCase(java.util.Locale.ROOT), "amount", amount));
        }
        out.put("essence", essence);
        List<Object> pets = new ArrayList<>();
        JsonObject petsData = path(member, "pets_data");
        if (petsData != null && petsData.has("pets") && petsData.get("pets").isJsonArray()) {
            for (JsonElement p : petsData.getAsJsonArray("pets")) pets.add(toJava(p));
        }
        out.put("pets", pets);
        return out;
    }

    private static final Set<String> TOOLKIT_METADATA = Set.of("IN_USE", "IS_UNLOCKED");

    /** helper/toolkits.js: the tools stored in a toolkit (not the ones in use), as items. */
    private static List<Object> toolkit(JsonObject toolkit, Map<String, JsonObject> items) {
        List<Object> out = new ArrayList<>();
        if (toolkit == null || !toolkit.has("IS_UNLOCKED") || !truthy(toolkit.get("IS_UNLOCKED"))) return out;
        JsonObject inUse = path(toolkit, "IN_USE");
        Set<String> categories = new LinkedHashSet<>(toolkit.keySet());
        if (inUse != null) categories.addAll(inUse.keySet());
        categories.removeAll(TOOLKIT_METADATA);
        for (String category : categories) {
            JsonElement tools = toolkit.get(category);
            if (tools == null || !(tools.isJsonObject() || tools.isJsonArray())) continue;
            JsonObject used = inUse == null ? null : path(inUse, category);
            Map<String, JsonElement> entries = new LinkedHashMap<>();
            if (tools.isJsonObject()) for (Map.Entry<String, JsonElement> e : tools.getAsJsonObject().entrySet()) entries.put(e.getKey(), e.getValue());
            else for (int i = 0; i < tools.getAsJsonArray().size(); i++) entries.put(String.valueOf(i), tools.getAsJsonArray().get(i));
            for (Map.Entry<String, JsonElement> e : entries.entrySet()) {
                if (used != null && used.has(e.getKey()) && used.get(e.getKey()).isJsonPrimitive() && used.get(e.getKey()).getAsJsonPrimitive().isBoolean()
                    && used.get(e.getKey()).getAsBoolean()) continue;
                if (!e.getValue().isJsonObject() || !e.getValue().getAsJsonObject().has("data")) continue;
                String data = e.getValue().getAsJsonObject().get("data").getAsString();
                if (data.isEmpty()) continue;
                Map<String, Object> extra = Nbt.decode(data);
                Object id = extra.get("id");
                if (!(id instanceof String s) || s.isEmpty()) continue;
                JsonObject info = items.get(s);
                String name = info != null && info.has("name") ? info.get("name").getAsString() : SkyHelperItem.titleCaseId(s);
                Map<String, Object> display = new LinkedHashMap<>();
                display.put("Name", name);
                display.put("Lore", new ArrayList<>());
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("ExtraAttributes", extra);
                tag.put("display", display);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("Count", 1);
                item.put("tag", tag);
                out.add(item);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ JSON

    private static List<Object> decoded(JsonObject blob) {
        if (blob == null || !blob.has("data") || !blob.get("data").isJsonPrimitive()) return new ArrayList<>();
        List<Object> out = new ArrayList<>();
        for (Object item : Nbt.items(blob.get("data").getAsString())) {
            Map<String, Object> m = SkyHelperItem.map(item);
            if (m != null && !m.isEmpty()) out.add(m);
        }
        return out;
    }

    private static JsonObject path(JsonObject o, String path) {
        JsonObject cur = o;
        for (String key : path.split("\\.")) {
            if (cur == null || !cur.has(key) || !cur.get(key).isJsonObject()) return null;
            cur = cur.getAsJsonObject(key);
        }
        return cur;
    }

    private static double num(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsDouble() : 0;
    }

    private static boolean truthy(JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (!e.isJsonPrimitive()) return true;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble() != 0;
        return !p.getAsString().isEmpty();
    }

    /** Gson to plain Java values, like JSON.parse. */
    static Object toJava(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) m.put(entry.getKey(), toJava(entry.getValue()));
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement el : (JsonArray) e) l.add(toJava(el));
            return l;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }
}
