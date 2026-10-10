package com.epic60869.sky2m.features.itemlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Every SkyBlock item, attribute, enchantment, mob, NPC, pet, potion and rune for the Item List, with every recipe:
 * crafting, Forge, Kat pet upgrades, NPC shops, mob drops and trades. From the NotEnoughUpdates-REPO (MIT), which
 * Sky2M already reads files from: the whole repo is downloaded once as a zip (about 22 MB) into
 * config/sky2m/neu-repo.zip, and checked for changes once a day. Nothing is downloaded while the Item List is off.
 */
public final class ItemListData {
    private static final String ZIP_URL = "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.zip";
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("sky2m");
    private static final Path ZIP = DIR.resolve("neu-repo.zip");
    private static final Path ETAG = DIR.resolve("neu-repo.etag");
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern TEXTURE = Pattern.compile("Value:\\\\?\"([A-Za-z0-9+/=]+)\\\\?\"");
    private static final Pattern ITEM_MODEL = Pattern.compile("ItemModel:\\\\?\"([a-z0-9_.:/-]+)\\\\?\"");

    public enum Category {
        ALL("All"), ITEM("Items"), ATTRIBUTE("Attributes"), ENCHANTMENT("Enchants"), MOB("Mobs"), NPC("NPCs"),
        PET("Pets"), POTION("Potions"), RUNE("Runes");

        public final String label;

        Category(String label) {
            this.label = label;
        }
    }

    /** One entry of the list. Names and lore keep their § colour codes; {@code search} is the lower-case plain text. */
    public record Entry(String id, String name, List<String> lore, Category category, String itemId, int damage,
                        String texture, String itemModel, boolean glint, String parent, String wiki, String searchName,
                        String searchLore, boolean vanilla) {}

    public record Ingredient(String id, double amount) {}

    /** A recipe. {@code kind} is its tab; {@code owner} is the item, NPC or mob it came from. */
    public sealed interface Recipe permits Crafting, Forge, Kat, Shop, Drops, Trade {
        String owner();

        List<Ingredient> inputs();

        List<Ingredient> outputs();

        default String kind() {
            return switch (this) {
                case Crafting c -> "Crafting";
                case Forge f -> "Forge";
                case Kat k -> "Kat Upgrade";
                case Shop s -> "NPC Shop";
                case Drops d -> "Mob Drops";
                case Trade t -> "Trade";
            };
        }
    }

    /** A 3x3 grid, A1..C3 left to right, top to bottom (null for empty), making {@code result}. */
    public record Crafting(String owner, Ingredient[] grid, Ingredient result) implements Recipe {
        public List<Ingredient> inputs() {
            List<Ingredient> out = new ArrayList<>();
            for (Ingredient i : grid) if (i != null) out.add(i);
            return out;
        }

        public List<Ingredient> outputs() {
            return List.of(result);
        }
    }

    public record Forge(String owner, List<Ingredient> inputs, Ingredient result, int seconds) implements Recipe {
        public List<Ingredient> outputs() {
            return List.of(result);
        }
    }

    public record Kat(String owner, Ingredient input, Ingredient output, List<Ingredient> items, long coins, int seconds) implements Recipe {
        public List<Ingredient> inputs() {
            List<Ingredient> out = new ArrayList<>(items);
            out.addFirst(input);
            return out;
        }

        public List<Ingredient> outputs() {
            return List.of(output);
        }
    }

    public record Shop(String owner, List<Ingredient> cost, Ingredient result) implements Recipe {
        public List<Ingredient> inputs() {
            return cost;
        }

        public List<Ingredient> outputs() {
            return List.of(result);
        }
    }

    public record Drop(Ingredient item, String chance, List<String> extra) {}

    public record Drops(String owner, String name, int level, long coins, long xp, List<String> extra, List<Drop> drops) implements Recipe {
        public List<Ingredient> inputs() {
            return List.of(new Ingredient(owner, 1));
        }

        public List<Ingredient> outputs() {
            return drops.stream().map(Drop::item).toList();
        }
    }

    public record Trade(String owner, Ingredient cost, Ingredient result) implements Recipe {
        public List<Ingredient> inputs() {
            return List.of(cost);
        }

        public List<Ingredient> outputs() {
            return List.of(result);
        }
    }

    private record Data(List<Entry> entries, Map<String, Entry> byId, Map<String, List<Recipe>> recipes,
                        Map<String, List<Recipe>> usages) {}

    private static volatile Data data;
    private static volatile boolean loading;
    private static volatile String status = "";
    private static long checkedAt;

    private ItemListData() {}

    public static boolean loaded() {
        return data != null;
    }

    /** What's happening while nothing is loaded yet ("Downloading items..."), or "". */
    public static String status() {
        return status;
    }

    public static List<Entry> entries() {
        Data d = data;
        return d == null ? List.of() : d.entries();
    }

    public static Entry entry(String id) {
        Data d = data;
        return d == null || id == null ? null : d.byId().get(id);
    }

    /** Recipes that make {@code id}. */
    public static List<Recipe> recipes(String id) {
        Data d = data;
        return d == null ? List.of() : d.recipes().getOrDefault(id, List.of());
    }

    /** Recipes that use {@code id} (and, for an NPC or mob, its shop or drops). */
    public static List<Recipe> usages(String id) {
        Data d = data;
        return d == null ? List.of() : d.usages().getOrDefault(id, List.of());
    }

    /** Loads the list if it isn't yet (from the saved zip, downloading it if there's none), and checks for updates daily. */
    public static void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (loading || (data != null && now - checkedAt < REFRESH_MS)) return;
        loading = true;
        checkedAt = now;
        CompletableFuture.runAsync(() -> {
            try {
                boolean changed = download();
                if (data == null || changed) {
                    status = "Reading items...";
                    data = parse();
                }
                status = "";
            } catch (Exception e) {
                status = data == null ? "Couldn't load the item list: " + e.getMessage() : "";
                System.err.println("[Sky2M] Item List: " + e.getMessage());
            } finally {
                loading = false;
            }
        });
    }

    /** Downloads the repo when it changed (by its ETag); true if a new zip was saved. */
    private static boolean download() throws Exception {
        Files.createDirectories(DIR);
        String etag = Files.exists(ETAG) ? Files.readString(ETAG).trim() : "";
        if (!Files.exists(ZIP)) {
            etag = "";
            status = "Downloading items (about 22 MB, once)...";
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ZIP_URL)).timeout(Duration.ofMinutes(3))
            .header("User-Agent", "Sky2M/1.0").GET();
        if (!etag.isEmpty()) request.header("If-None-Match", etag);
        HttpResponse<Path> response;
        try {
            Path temp = DIR.resolve("neu-repo.zip.part");
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofFile(temp));
            if (response.statusCode() == 304) {
                Files.deleteIfExists(temp);
                return false;
            }
            if (response.statusCode() != 200) {
                Files.deleteIfExists(temp);
                if (Files.exists(ZIP)) return false; // keep using the one we have
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            Files.move(temp, ZIP, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            if (Files.exists(ZIP)) return false; // offline: the saved copy still works
            throw e;
        }
        response.headers().firstValue("ETag").ifPresent(tag -> {
            try {
                Files.writeString(ETAG, tag);
            } catch (Exception ignored) {}
        });
        return true;
    }

    private static Data parse() throws Exception {
        Map<String, Entry> byId = new LinkedHashMap<>();
        Map<String, List<Recipe>> recipes = new HashMap<>();
        Map<String, List<Recipe>> usages = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(ZIP))) {
            ZipEntry e;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                String path = slash >= 0 ? name.substring(slash + 1) : name;
                if (!path.startsWith("items/") || !path.endsWith(".json")) continue;
                buffer.reset();
                zip.transferTo(buffer);
                JsonObject item;
                try {
                    item = JsonParser.parseString(buffer.toString(StandardCharsets.UTF_8)).getAsJsonObject();
                } catch (Exception bad) {
                    continue;
                }
                Entry entry = entry(item);
                if (entry == null) continue;
                byId.put(entry.id(), entry);
                for (Recipe recipe : recipesOf(entry.id(), item)) {
                    for (Ingredient out : recipe.outputs()) add(recipes, out.id(), recipe);
                    for (Ingredient in : recipe.inputs()) add(usages, in.id(), recipe);
                    // An NPC's shop and a mob's drops are listed under the NPC or mob too.
                    if (recipe instanceof Shop || recipe instanceof Drops) add(usages, entry.id(), recipe);
                }
            }
        }
        List<Entry> entries = new ArrayList<>(byId.values());
        entries.sort((a, b) -> {
            int c = a.category().compareTo(b.category());
            return c != 0 ? c : a.searchName().compareTo(b.searchName());
        });
        recipes.values().forEach(list -> deduplicate(list));
        usages.values().forEach(list -> deduplicate(list));
        return new Data(Collections.unmodifiableList(entries), byId, recipes, usages);
    }

    private static void add(Map<String, List<Recipe>> map, String id, Recipe recipe) {
        if (id == null || id.isEmpty() || id.equals("SKYBLOCK_COIN")) return;
        map.computeIfAbsent(id, k -> new ArrayList<>()).add(recipe);
    }

    private static void deduplicate(List<Recipe> list) {
        List<Recipe> unique = new ArrayList<>(new java.util.LinkedHashSet<>(list));
        list.clear();
        list.addAll(unique);
    }

    private static Entry entry(JsonObject item) {
        String id = str(item, "internalname");
        if (id == null || id.isEmpty()) return null;
        String name = str(item, "displayname");
        if (name == null) name = id;
        List<String> lore = new ArrayList<>();
        if (item.has("lore") && item.get("lore").isJsonArray()) for (JsonElement l : item.getAsJsonArray("lore")) lore.add(l.getAsString());
        String itemId = str(item, "itemid");
        int damage = 0;
        try {
            damage = item.has("damage") ? item.get("damage").getAsInt() : 0;
        } catch (Exception ignored) {}
        String nbt = str(item, "nbttag");
        String texture = null, model = null;
        boolean glint = false;
        if (nbt != null) {
            Matcher t = TEXTURE.matcher(nbt);
            if (t.find()) texture = t.group(1);
            Matcher m = ITEM_MODEL.matcher(nbt);
            if (m.find()) model = m.group(1);
            glint = nbt.contains("ench:[");
        }
        String wiki = null;
        if (item.has("info") && item.get("info").isJsonArray()) {
            for (JsonElement i : item.getAsJsonArray("info")) {
                String url = i.getAsString();
                if (url.startsWith("https://")) {
                    wiki = url;
                    break;
                }
            }
        }
        String plainName = strip(name).toLowerCase(Locale.ROOT);
        StringBuilder plainLore = new StringBuilder();
        for (String l : lore) plainLore.append(strip(l).toLowerCase(Locale.ROOT)).append(' ');
        Category category = category(id, itemId, name, item);
        // Vanilla items: plain Minecraft things with no SkyBlock look (no skin, no Hypixel model, no SkyBlock lore).
        boolean vanilla = texture == null && model == null && lore.isEmpty();
        return new Entry(id, name, List.copyOf(lore), category, itemId == null ? "minecraft:barrier" : itemId, damage,
            texture, model, glint, str(item, "parent"), wiki, plainName, plainLore.toString(), vanilla);
    }

    private static Category category(String id, String itemId, String name, JsonObject item) {
        if (item.has("recipes")) {
            for (JsonElement r : item.getAsJsonArray("recipes")) {
                if (r.isJsonObject() && "drops".equals(str(r.getAsJsonObject(), "type"))) return Category.MOB;
            }
        }
        if (id.matches(".*_(MONSTER|SC|MINIBOSS|BOSS|ANIMAL)$")) return Category.MOB;
        if (id.endsWith("_NPC")) return Category.NPC;
        if (id.startsWith("ATTRIBUTE_SHARD")) return Category.ATTRIBUTE;
        if (id.contains(";")) {
            if ("minecraft:enchanted_book".equals(itemId)) return Category.ENCHANTMENT;
            if (id.contains("RUNE")) return Category.RUNE;
            if (id.contains("POTION")) return Category.POTION;
            if (name.contains("[Lvl")) return Category.PET;
        }
        return Category.ITEM;
    }

    // ------------------------------------------------------------------------------------------------ recipes

    private static List<Recipe> recipesOf(String id, JsonObject item) {
        List<Recipe> out = new ArrayList<>();
        if (item.has("recipe") && item.get("recipe").isJsonObject()) {
            Recipe crafting = crafting(id, item.getAsJsonObject("recipe"), id);
            if (crafting != null) out.add(crafting);
        }
        if (item.has("recipes") && item.get("recipes").isJsonArray()) {
            for (JsonElement element : item.getAsJsonArray("recipes")) {
                if (!element.isJsonObject()) continue;
                JsonObject r = element.getAsJsonObject();
                String type = str(r, "type");
                if (type == null) type = "crafting";
                try {
                    Recipe recipe = switch (type) {
                        case "crafting" -> crafting(id, r, str(r, "overrideOutputId") != null ? str(r, "overrideOutputId") : id);
                        case "forge" -> new Forge(id, ingredients(r.getAsJsonArray("inputs")),
                            new Ingredient(str(r, "overrideOutputId") != null ? str(r, "overrideOutputId") : id, num(r, "count", 1)),
                            (int) num(r, "duration", 0));
                        case "katgrade" -> new Kat(id, ingredient(str(r, "input")), ingredient(str(r, "output")),
                            r.has("items") ? ingredients(r.getAsJsonArray("items")) : List.of(), (long) num(r, "coins", 0),
                            (int) num(r, "time", 0));
                        case "npc_shop" -> new Shop(id, r.get("cost").isJsonArray() ? ingredients(r.getAsJsonArray("cost"))
                            : List.of(ingredient(r.get("cost").getAsString())), ingredient(str(r, "result")));
                        case "trade" -> new Trade(id, ingredient(str(r, "cost")), ingredient(str(r, "result")));
                        case "drops" -> drops(id, r);
                        default -> null;
                    };
                    if (recipe != null) out.add(recipe);
                } catch (Exception ignored) {
                    // One malformed recipe shouldn't hide the item's others.
                }
            }
        }
        return out;
    }

    private static Crafting crafting(String owner, JsonObject r, String output) {
        Ingredient[] grid = new Ingredient[9];
        boolean any = false;
        String[] keys = {"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"};
        for (int i = 0; i < 9; i++) {
            String value = str(r, keys[i]);
            if (value != null && !value.isEmpty()) {
                grid[i] = ingredient(value);
                any = true;
            }
        }
        return any ? new Crafting(owner, grid, new Ingredient(output, num(r, "count", 1))) : null;
    }

    private static Drops drops(String owner, JsonObject r) {
        List<Drop> drops = new ArrayList<>();
        if (r.has("drops")) {
            for (JsonElement d : r.getAsJsonArray("drops")) {
                JsonObject o = d.getAsJsonObject();
                drops.add(new Drop(ingredient(str(o, "id")), str(o, "chance"), strings(o.get("extra"))));
            }
        }
        return new Drops(owner, str(r, "name"), (int) num(r, "level", 0), (long) num(r, "coins", 0), (long) num(r, "xp", 0),
            strings(r.get("extra")), drops);
    }

    /** "ENCHANTED_DIAMOND:32" or "ENCHANTED_DIAMOND". */
    private static Ingredient ingredient(String value) {
        if (value == null) return new Ingredient("", 0);
        int colon = value.lastIndexOf(':');
        if (colon > 0) {
            try {
                return new Ingredient(value.substring(0, colon), Double.parseDouble(value.substring(colon + 1)));
            } catch (NumberFormatException ignored) {}
        }
        return new Ingredient(value, 1);
    }

    private static List<Ingredient> ingredients(JsonArray array) {
        List<Ingredient> out = new ArrayList<>();
        if (array != null) for (JsonElement e : array) out.add(ingredient(e.getAsString()));
        return out;
    }

    private static List<String> strings(JsonElement element) {
        List<String> out = new ArrayList<>();
        if (element != null && element.isJsonArray()) for (JsonElement e : element.getAsJsonArray()) out.add(e.getAsString());
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static double num(JsonObject o, String key, double fallback) {
        try {
            return o.has(key) ? o.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }
}
