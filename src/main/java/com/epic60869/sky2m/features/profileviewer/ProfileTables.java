package com.epic60869.sky2m.features.profileviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.epic60869.sky2m.features.profileviewer.ProfileExtras.asArr;
import static com.epic60869.sky2m.features.profileviewer.ProfileExtras.asObj;
import static com.epic60869.sky2m.features.profileviewer.ProfileExtras.num;
import static com.epic60869.sky2m.features.profileviewer.ProfileExtras.str;
import static com.epic60869.sky2m.features.profileviewer.ProfileExtras.titleCase;

/**
 * Ported from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, profileviewer/data/ExtraTables).
 *
 * Tables for the Profile Viewer's extra pages:
 * <ul>
 *   <li>{@code assets/sky2m/profileviewer/extras.json} (bundled): Crimson Isle, Rift, corpse, fossil and garden tables
 *   from meowdding-repo's {@code repo/pv} (MIT) and skyblock-pv's HOTM/HOTF levels, as Killer560's Mod ships them.</li>
 *   <li>Collections: Hypixel's keyless {@code /v2/resources/skyblock/collections}.</li>
 *   <li>Bestiary: meowdding-repo's {@code repo/neu/bestiary.json}, NEU-REPO's {@code constants/bestiary.json} if that fails.</li>
 *   <li>Museum categories: NEU-REPO's {@code constants/museum.json}.</li>
 *   <li>Pet prices for networth: Coflnet's keyless NEU-format price list ({@code TYPE;rarity} keys).</li>
 * </ul>
 * Remote tables are fetched once per session on first use, and again a minute after a failure.
 */
public final class ProfileTables {
    private static final String COLLECTIONS_URL = "https://api.hypixel.net/v2/resources/skyblock/collections";
    private static final String BESTIARY_URL = "https://raw.githubusercontent.com/meowdding/meowdding-repo/HEAD/repo/neu/bestiary.json";
    private static final String BESTIARY_FALLBACK = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/bestiary.json";
    private static final String MUSEUM_URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/museum.json";
    private static final String NEU_PRICES_URL = "https://sky.coflnet.com/api/prices/neu";
    private static final long RETRY_MS = 60_000L, PRICE_TTL_MS = 10 * 60_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    private ProfileTables() {}

    // ------------------------------------------------------------------------------------------------ bundled

    private static JsonObject bundled;

    public static synchronized JsonObject bundled() {
        if (bundled == null) {
            try (InputStream in = ProfileTables.class.getResourceAsStream("/assets/sky2m/profileviewer/extras.json")) {
                bundled = in == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                System.err.println("[Sky2M] Profile Viewer couldn't read its bundled tables: " + e.getMessage());
                bundled = new JsonObject();
            }
        }
        return bundled;
    }

    public static JsonObject section(String name) {
        JsonObject o = asObj(bundled().get(name));
        return o == null ? new JsonObject() : o;
    }

    public static long[] longs(JsonElement el) {
        JsonArray arr = asArr(el);
        if (arr == null) return new long[0];
        long[] out = new long[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = (long) num(arr.get(i));
        return out;
    }

    public static List<String> strings(JsonElement el) {
        List<String> out = new ArrayList<>();
        JsonArray arr = asArr(el);
        if (arr != null) for (JsonElement e : arr) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    /** Running totals of a per-level list. */
    public static long[] cumulative(long[] increments) {
        long[] out = new long[increments.length];
        long sum = 0;
        for (int i = 0; i < increments.length; i++) out[i] = sum += increments[i];
        return out;
    }

    /** The {@code field} of the highest entry whose threshold is at most {@code amount}. */
    public static String threshold(JsonElement list, String field, long amount) {
        JsonArray arr = asArr(list);
        if (arr == null) return "";
        String best = null;
        long bestT = Long.MIN_VALUE;
        for (JsonElement e : arr) {
            JsonObject o = asObj(e);
            if (o == null) continue;
            long t = (long) num(o.get("threshold"));
            if (t <= amount && t >= bestT) {
                bestT = t;
                best = str(o, field, "");
            }
        }
        return best == null ? "" : best;
    }

    // ------------------------------------------------------------------------------------------------ remote

    /** One remote table: loaded once, again after a minute if it failed, and after {@code ttl} if that's set. */
    private static final class Remote<T> {
        private final String url, fallback;
        private final Function<JsonObject, T> parser;
        private final long ttl;
        private CompletableFuture<T> future;
        private long startedAt;

        Remote(String url, String fallback, Function<JsonObject, T> parser, long ttl) {
            this.url = url;
            this.fallback = fallback;
            this.parser = parser;
            this.ttl = ttl;
        }

        synchronized CompletableFuture<T> get() {
            long now = System.currentTimeMillis();
            boolean failed = future != null && future.isDone() && future.getNow(null) == null;
            boolean expired = future != null && future.isDone() && ttl > 0 && now - startedAt > ttl;
            if (future == null || (failed && now - startedAt > RETRY_MS) || expired) {
                startedAt = now;
                CompletableFuture<JsonObject> json = getJson(url);
                if (fallback != null) json = json.thenCompose(j -> j != null ? CompletableFuture.completedFuture(j) : getJson(fallback));
                future = json.thenApplyAsync(j -> j == null ? null : parser.apply(j)).exceptionally(t -> null);
            }
            return future;
        }
    }

    private static CompletableFuture<JsonObject> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "Sky2M").GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(r -> {
            if (r.statusCode() != 200) return null;
            JsonElement root = JsonParser.parseString(r.body());
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        }).exceptionally(t -> null);
    }

    /** The value once loaded; null while loading or after a failure. */
    public static <T> T now(CompletableFuture<T> f) {
        return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
    }

    // ------------------------------------------------------------------------------------------------ collections

    public record CollectionItem(String id, String name, int maxTiers, long[] tiers) {}

    public record CollectionCategory(String id, String name, List<CollectionItem> items) {}

    private static final Remote<List<CollectionCategory>> COLLECTIONS = new Remote<>(COLLECTIONS_URL, null, json -> {
        JsonObject cols = asObj(json.get("collections"));
        if (cols == null) return null;
        List<CollectionCategory> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> c : cols.entrySet()) {
            JsonObject cat = asObj(c.getValue());
            JsonObject items = cat == null ? null : asObj(cat.get("items"));
            if (items == null) continue;
            List<CollectionItem> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> i : items.entrySet()) {
                JsonObject it = asObj(i.getValue());
                if (it == null) continue;
                List<Long> amounts = new ArrayList<>();
                JsonArray tiers = asArr(it.get("tiers"));
                if (tiers != null) for (JsonElement t : tiers) {
                    JsonObject to = asObj(t);
                    if (to != null) amounts.add((long) num(to.get("amountRequired")));
                }
                Collections.sort(amounts);
                list.add(new CollectionItem(i.getKey(), str(it, "name", titleCase(i.getKey())), (int) num(it.get("maxTiers")),
                    amounts.stream().mapToLong(Long::longValue).toArray()));
            }
            list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            out.add(new CollectionCategory(c.getKey(), str(cat, "name", titleCase(c.getKey())), list));
        }
        return out.isEmpty() ? null : Collections.unmodifiableList(out);
    }, 0);

    public static CompletableFuture<List<CollectionCategory>> collections() {
        return COLLECTIONS.get();
    }

    // ------------------------------------------------------------------------------------------------ bestiary

    /**
     * {@code icon} is a vanilla item id ("dragon_egg") or null; {@code texture} a skull texture or null; {@code group}
     * the part of its category it's listed under (Fishing has Water, Lava, Spooky Festival...), else the category's name.
     */
    public record BestiaryFamily(String name, String icon, String texture, int cap, List<String> mobs, int[] tiers, String group) {}

    public record BestiaryCategory(String id, String name, String icon, String texture, List<BestiaryFamily> families) {}

    private static final Remote<List<BestiaryCategory>> BESTIARY = new Remote<>(BESTIARY_URL, BESTIARY_FALLBACK, ProfileTables::parseBestiary, 0);

    public static CompletableFuture<List<BestiaryCategory>> bestiary() {
        return BESTIARY.get();
    }

    private static List<BestiaryCategory> parseBestiary(JsonObject json) {
        Map<String, int[]> brackets = brackets(asObj(json.get("brackets")));
        Map<String, Map<String, int[]>> sets = new LinkedHashMap<>();
        JsonObject bs = asObj(json.get("bracketSets"));
        if (bs != null) for (Map.Entry<String, JsonElement> e : bs.entrySet()) sets.put(e.getKey().toUpperCase(Locale.ROOT), brackets(asObj(e.getValue())));
        List<BestiaryCategory> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (e.getKey().equals("brackets") || e.getKey().equals("bracketSets")) continue;
            JsonObject cat = asObj(e.getValue());
            if (cat == null || !cat.has("name")) continue;
            List<BestiaryFamily> families = new ArrayList<>();
            String catName = cleanName(str(cat, "name", e.getKey()));
            if (asArr(cat.get("mobs")) != null) {
                addFamilies(families, asArr(cat.get("mobs")), brackets, sets, catName);
            } else {
                for (Map.Entry<String, JsonElement> sub : cat.entrySet()) {
                    JsonObject so = asObj(sub.getValue());
                    if (so != null && asArr(so.get("mobs")) != null)
                        addFamilies(families, asArr(so.get("mobs")), brackets, sets, cleanName(str(so, "name", sub.getKey())));
                }
            }
            JsonObject icon = asObj(cat.get("icon"));
            out.add(new BestiaryCategory(e.getKey(), cleanName(str(cat, "name", e.getKey())), str(icon, "item", null),
                fixTexture(str(icon, "texture", null)), families));
        }
        return out.isEmpty() ? null : Collections.unmodifiableList(out);
    }

    private static void addFamilies(List<BestiaryFamily> out, JsonArray mobs, Map<String, int[]> brackets, Map<String, Map<String, int[]>> sets,
                                    String group) {
        for (JsonElement el : mobs) {
            JsonObject m = asObj(el);
            if (m == null) continue;
            int cap = (int) num(m.get("cap"));
            String bracket = String.valueOf((int) num(m.get("bracket")));
            String type = str(m, "bracketType", null);
            int[] full;
            if (type != null) {
                String upper = type.toUpperCase(Locale.ROOT);
                Map<String, int[]> set = sets.getOrDefault(upper, sets.get(upper + "S"));
                full = set == null ? null : set.get(bracket);
            } else {
                full = brackets.get(bracket);
            }
            int[] tiers = new int[0];
            if (full != null && full.length > 0) {
                List<Integer> t = new ArrayList<>();
                for (int v : full) if (v < cap) t.add(v);
                t.add(cap);
                tiers = t.stream().mapToInt(Integer::intValue).toArray();
            }
            out.add(new BestiaryFamily(cleanName(str(m, "name", "?")), str(m, "item", null), fixTexture(str(m, "texture", null)), cap,
                strings(m.get("mobs")), tiers, group));
        }
    }

    private static Map<String, int[]> brackets(JsonObject obj) {
        Map<String, int[]> out = new LinkedHashMap<>();
        if (obj == null) return out;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            long[] l = longs(e.getValue());
            int[] arr = new int[l.length];
            for (int i = 0; i < l.length; i++) arr[i] = (int) l[i];
            out.put(e.getKey(), arr);
        }
        return out;
    }

    /** NEU's file has mis-decoded "Â§" pairs; drop those and the colour codes. */
    private static String cleanName(String raw) {
        return raw == null ? "" : raw.replace("Â", "").replaceAll("§.", "").trim();
    }

    private static String fixTexture(String tex) {
        if (tex == null || tex.isBlank()) return null;
        StringBuilder t = new StringBuilder(tex.split("\", \"")[0].trim());
        while (t.length() % 4 != 0) t.append('=');
        return t.toString();
    }

    // ------------------------------------------------------------------------------------------------ museum

    public record MuseumTables(Map<String, List<String>> categories, Map<String, Integer> maxValues) {}

    private static final Remote<MuseumTables> MUSEUM = new Remote<>(MUSEUM_URL, null, json -> {
        JsonObject items = asObj(json.get("items"));
        if (items == null) return null;
        Map<String, List<String>> cats = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : items.entrySet()) cats.put(e.getKey(), strings(e.getValue()));
        Map<String, Integer> max = new LinkedHashMap<>();
        JsonObject mv = asObj(json.get("max_values"));
        if (mv != null) for (Map.Entry<String, JsonElement> e : mv.entrySet()) max.put(e.getKey(), (int) num(e.getValue()));
        return new MuseumTables(cats, max);
    }, 0);

    public static CompletableFuture<MuseumTables> museum() {
        return MUSEUM.get();
    }

    // ------------------------------------------------------------------------------------------------ prices

    private static final Remote<Map<String, Double>> NEU_PRICES = new Remote<>(NEU_PRICES_URL, null, json -> {
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            double v = num(e.getValue());
            if (v > 0) out.put(e.getKey(), v);
        }
        return out.isEmpty() ? null : out;
    }, PRICE_TTL_MS);

    /** Coflnet's lowest BINs keyed like NEU ({@code ENDER_DRAGON;4} for pets). */
    public static CompletableFuture<Map<String, Double>> neuPrices() {
        return NEU_PRICES.get();
    }
}
