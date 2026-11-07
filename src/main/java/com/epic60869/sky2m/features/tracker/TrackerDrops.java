package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.misc.profit.ProfitTracker;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The other tabs of /s2 tracker ({@link DungeonDropScreen}): what Sky2M's own trackers have counted, read from their
 * files when a tab opens. Slayer, Fishing, Mining, Farming and Foraging come from the profit trackers
 * (config/sky2m/profit-trackers.json, all time), Diana from the Diana tracker (diana-tracker.json, all time). These
 * keep counts, not prices, so items are priced now.
 */
public final class TrackerDrops {
    /** The tabs after Dungeons, each with its parts (the left box) as {label, where its counts are}. */
    public static final String[] CATEGORIES = {"Slayer", "Fishing", "Diana", "Mining", "Farming", "Foraging"};
    private static final String[][] SLAYERS = {{"Revenant Horror", "ZOMBIE"}, {"Tarantula Broodfather", "SPIDER"},
        {"Sven Packmaster", "WOLF"}, {"Voidgloom Seraph", "ENDERMAN"}, {"Inferno Demonlord", "BLAZE"}, {"Riftstalker Bloodfiend", "VAMPIRE"}};

    /** One part of a category: its name and what was counted, id (or name) -> how many. */
    public record Part(String name, Map<String, Long> counts) {}

    private TrackerDrops() {}

    /** A category's parts. Slayer has one per boss; the others have one. */
    public static List<Part> load(String category) {
        Path dir = DungeonProfitHour.DIR.resolve("sky2m");
        List<Part> out = new ArrayList<>();
        if (category.equals("Diana")) {
            JsonObject diana = object(dir.resolve("diana-tracker.json"));
            // The Diana tracker keeps its own drop names, lootshared ones as NAME_LS: both count, under the item's id.
            Map<String, Long> counts = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : counts(child(child(diana, "allTime"), "drops")).entrySet()) {
                counts.merge(dianaId(e.getKey()), e.getValue(), Long::sum);
            }
            out.add(new Part("Diana", counts));
            return out;
        }
        JsonObject profit = object(dir.resolve("profit-trackers.json"));
        if (category.equals("Slayer")) {
            for (String[] s : SLAYERS) out.add(new Part(s[0], counts(child(child(child(profit, s[1]), "total"), "items"))));
            return out;
        }
        out.add(new Part(category, counts(child(child(child(profit, category.toUpperCase(Locale.ROOT)), "total"), "items"))));
        return out;
    }

    /**
     * One part's rows (all parts when {@code part} is null), the most expensive first. Priced now; an item whose price
     * isn't known is worth 0 and sorts by count among the rest.
     */
    public static List<DungeonDropTracker.Row> rows(List<Part> parts, String part, String search) {
        Map<String, Long> all = new LinkedHashMap<>();
        for (Part p : parts) {
            if (part != null && !part.equals(p.name())) continue;
            for (Map.Entry<String, Long> e : p.counts().entrySet()) all.merge(e.getKey(), e.getValue(), Long::sum);
        }
        return DungeonDropTracker.rows(drops(all), null, search);
    }

    /** One activity's day: what it dropped (each with its worth), the coins it made and what it cost. */
    public record Day(String name, List<DungeonDropTracker.Drop> drops, double coins, double costs) {
        public double worth() {
            double worth = 0;
            for (DungeonDropTracker.Drop d : drops) worth += d.value;
            return worth;
        }

        public double profit() {
            return worth() + coins - costs;
        }

        boolean empty() {
            return drops.isEmpty() && coins == 0 && costs == 0;
        }
    }

    /**
     * Today so far (since local midnight, when the profit trackers start their day), one entry per activity that did
     * anything. Dungeon loot is worth what it was when claimed; the trackers only keep counts, so theirs is priced now.
     */
    public static List<Day> today() {
        long midnight = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Day> out = new ArrayList<>();
        out.add(dungeonDay(DungeonDropTracker.drops(), DungeonDropTracker.costs(), midnight));
        out.add(trackerDay("Slayer", ProfitTracker.Preset.ZOMBIE, ProfitTracker.Preset.SPIDER, ProfitTracker.Preset.WOLF,
            ProfitTracker.Preset.ENDERMAN, ProfitTracker.Preset.BLAZE, ProfitTracker.Preset.VAMPIRE));
        out.add(trackerDay("Fishing", ProfitTracker.Preset.FISHING));
        out.add(trackerDay("Diana", ProfitTracker.Preset.MYTHOLOGICAL_RITUAL));
        out.add(trackerDay("Mining", ProfitTracker.Preset.MINING));
        out.add(trackerDay("Farming", ProfitTracker.Preset.FARMING));
        out.add(trackerDay("Foraging", ProfitTracker.Preset.FORAGING));
        out.removeIf(Day::empty);
        return out;
    }

    /** The dungeon chests opened from {@code since} on. Public for the game test. */
    public static Day dungeonDay(List<DungeonDropTracker.Drop> drops, List<DungeonDropTracker.Cost> costs, long since) {
        return dungeonDay(drops, costs, since, Long.MAX_VALUE);
    }

    /** The dungeon chests opened from {@code from} up to (not including) {@code to}. Public for the game test. */
    public static Day dungeonDay(List<DungeonDropTracker.Drop> drops, List<DungeonDropTracker.Cost> costs, long from, long to) {
        List<DungeonDropTracker.Drop> kept = new ArrayList<>();
        for (DungeonDropTracker.Drop d : drops) if (d.time >= from && d.time < to) kept.add(d);
        double spent = 0;
        for (DungeonDropTracker.Cost c : costs) if (c.time >= from && c.time < to) spent += c.total();
        return new Day("Dungeons", kept, 0, spent);
    }

    /** One local calendar day's dungeon chests, for /s2 session: the same numbers as /s2 tracker, worth what they were when claimed. */
    public static Day dungeonDay(long epochDay) {
        if (!DungeonDropTracker.started()) return new Day("Dungeons", List.of(), 0, 0);
        return dungeonDay(DungeonDropTracker.drops(), DungeonDropTracker.costs(), dayStart(epochDay), dayStart(epochDay + 1));
    }

    /** The chests opened on one local calendar day. */
    public static List<DungeonDropTracker.Cost> chestsOn(long epochDay) {
        if (!DungeonDropTracker.started()) return List.of();
        long from = dayStart(epochDay), to = dayStart(epochDay + 1);
        List<DungeonDropTracker.Cost> out = new ArrayList<>();
        for (DungeonDropTracker.Cost c : DungeonDropTracker.costs()) if (c.time >= from && c.time < to) out.add(c);
        return out;
    }

    /** How long your dungeon runs that ended on one local calendar day took (Run History). */
    public static long runTimeOn(long epochDay) {
        return com.epic60869.sky2m.features.dungeons.RunHistory.runTime(dayStart(epochDay), dayStart(epochDay + 1));
    }

    private static long dayStart(long epochDay) {
        return LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static Day trackerDay(String name, ProfitTracker.Preset... presets) {
        Map<String, Long> all = new LinkedHashMap<>();
        double coins = 0, costs = 0;
        for (ProfitTracker.Preset preset : presets) {
            ProfitTracker.DayNumbers day = ProfitTracker.todayNumbers(preset);
            day.items().forEach((id, count) -> all.merge(id, count, Long::sum));
            coins += day.coins();
            costs += day.costs();
        }
        return new Day(name, drops(all), coins, costs);
    }

    /** Counted items as drops, priced now. */
    private static List<DungeonDropTracker.Drop> drops(Map<String, Long> counts) {
        List<DungeonDropTracker.Drop> drops = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (e.getValue() <= 0) continue;
            DungeonDropTracker.Drop d = new DungeonDropTracker.Drop();
            String key = e.getKey();
            boolean isId = key.equals(key.toUpperCase(Locale.ROOT)) && !key.contains(" ");
            d.id = isId ? key : idFor(key);
            d.name = isId ? nameFor(key) : key;
            d.count = (int) Math.min(Integer.MAX_VALUE, e.getValue());
            d.value = d.id.isEmpty() ? 0 : price(d.id) * e.getValue();
            drops.add(d);
        }
        return drops;
    }

    /** "ENCHANTMENT_CRITICAL_6" -> "Critical VI", else the item's name, else the id tidied up. */
    static String nameFor(String id) {
        if (id.startsWith("ENCHANTMENT_")) return DungeonDropTracker.displayName("Enchanted Book", id);
        String name = RepoItems.itemsLoaded() ? RepoItems.displayName(id.replace(':', '-')) : null;
        if (name != null && !name.isBlank()) return TrackLines.strip(name);
        StringBuilder out = new StringBuilder();
        for (String word : id.split("[_:;-]")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** "CHIMERA" / "CHIMERA_LS" -> "ENCHANTMENT_ULTIMATE_CHIMERA_1" (Sky2M's Diana drop list); anything else as it is. */
    private static String dianaId(String key) {
        String name = key.endsWith("_LS") ? key.substring(0, key.length() - 3) : key;
        for (com.epic60869.sky2m.features.combat.DianaTracker.Drop d : com.epic60869.sky2m.features.combat.DianaTracker.Drop.values()) {
            if (d.name().equals(name)) return d.itemId;
        }
        return name;
    }

    /** A price id for a counted name: "Sphinx Shard" -> "SHARD_SPHINX", else the item by that name, or "". */
    private static String idFor(String name) {
        if (name.endsWith(" Shard")) return "SHARD_" + name.substring(0, name.length() - 6).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        String id = RepoItems.itemsLoaded() ? RepoItems.idByLooseName(name) : null;
        return id == null ? "" : id;
    }

    /** What one is worth now; fishing ids like "RAW_FISH:1" are tried as "RAW_FISH-1" too. 0 if unknown. */
    private static double price(String id) {
        double p = Sky2MPriceTooltip.unitPrice(id);
        if (p <= 0 && id.contains(":")) p = Sky2MPriceTooltip.unitPrice(id.replace(':', '-'));
        return Math.max(0, p);
    }

    private static Map<String, Long> counts(JsonObject json) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (json == null) return out;
        for (Map.Entry<String, JsonElement> e : json.entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) out.put(e.getKey(), e.getValue().getAsLong());
        }
        return out;
    }

    private static JsonObject child(JsonObject json, String key) {
        return json != null && json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : null;
    }

    private static JsonObject object(Path file) {
        try {
            if (Files.exists(file)) return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        } catch (Exception e) {
            TrackLines.LOG.warn("Couldn't read {}: {}", file, e.toString());
        }
        return null;
    }
}
