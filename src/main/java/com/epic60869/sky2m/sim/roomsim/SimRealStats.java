package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel's real item and pet stats for the Dungeon Sim's wardrobe, pets and weapons ({@link SimGear}, {@link SimDamage}),
 * in place of the hand-written tables those carried.
 *
 * <p>Where the numbers come from, in order:
 * <ol>
 * <li><b>Items</b>: Hypixel's own items resource ({@code api.hypixel.net/v2/resources/skyblock/items}, which the mod
 * already loads for prices - {@link com.epic60869.sky2m.price.PriceService#items()}): every item's numeric
 * {@code stats} ({@code "STRENGTH":40}), its {@code dungeon_item} flag and its {@code dungeon_item_conversion_cost}.
 * If that has not loaded, the NotEnoughUpdates item repo ({@link com.epic60869.sky2m.features.itemlist.ItemListData},
 * when something else has loaded it) is read instead: its lore's stat lines ({@code "§7Strength: §c+40"}) and its
 * rarity line ("LEGENDARY DUNGEON CHESTPLATE").</li>
 * <li><b>Pets</b>: NEU's {@code constants/petnums.json} ({@link com.epic60869.sky2m.custom.RepoItems#neuRepoFile},
 * cached on disk, fetched once in the background): per pet and rarity, the stats at level 1 and 100, interpolated
 * linearly between them, and its {@code stats_levelling_curve} for pets that go past 100 ({@code "101:200:1"} on the
 * Golden Dragon: levels 101 to 200 use the 1-100 table, so a level 200 Golden Dragon reads the "100" row). The
 * {@code otherNums} are the numbers in the perk texts ({@code {0}}, {@code {1}} ...), for the numeric perks.</li>
 * </ol>
 * Anything not found answers null and the caller keeps its own hand-written value; that is said once in the log
 * ({@link #fallback}), so the sim works offline exactly as before.
 *
 * <p>Neither source carries Hypixel's in-dungeon (starred, Catacombs-boosted) values: the API's {@code gear_score} is
 * not a stat, and NEU's "Gear Score" lore line is a single score, not per-stat numbers. The boost is worked out from
 * Hypixel's documented formula in {@link SimItemData#itemMultiplier}; this class says which items get it
 * ({@link #dungeonItem}) and supplies each item's name, tier, category, gemstone slots and {@code tiered_stats}.
 */
public final class SimRealStats {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");

    /** An item's stats: the {@link SimStats.Stat}s plus the weapon's own Damage, which is not a profile stat. */
    public record ItemStats(Map<SimStats.Stat, Double> stats, double damage, boolean dungeon, String source,
                            String name, String tier, String category, List<String> gemSlots,
                            Map<SimStats.Stat, double[]> tiered, double[] tieredDamage) {
        public ItemStats(Map<SimStats.Stat, Double> stats, double damage, boolean dungeon, String source) {
            this(stats, damage, dungeon, source, null, null, null, List.of(), Map.of(), null);
        }

        public double get(SimStats.Stat s) {
            Double v = stats.get(s);
            return v == null ? 0 : v;
        }
    }

    /** One pet's numbers at a level. */
    public record PetStats(Map<SimStats.Stat, Double> stats, double[] other, String rarity, int level) {
        public double get(SimStats.Stat s) {
            Double v = stats.get(s);
            return v == null ? 0 : v;
        }

        /** Perk number {@code {i}}, or {@code fallback} when the table has none. */
        public double other(int i, double fallback) {
            return other != null && i >= 0 && i < other.length ? other[i] : fallback;
        }
    }

    private static final Map<String, ItemStats> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, PetStats> PETS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile JsonObject petNums;
    private static volatile boolean petNumsRequested;

    private SimRealStats() {
    }

    // ------------------------------------------------------------------ items

    /** The Hypixel API stat keys, as the sim's stats. */
    private static final Map<String, SimStats.Stat> API_KEYS = Map.ofEntries(
            Map.entry("HEALTH", SimStats.Stat.HEALTH),
            Map.entry("DEFENSE", SimStats.Stat.DEFENSE),
            Map.entry("STRENGTH", SimStats.Stat.STRENGTH),
            Map.entry("CRITICAL_CHANCE", SimStats.Stat.CRIT_CHANCE),
            Map.entry("CRITICAL_DAMAGE", SimStats.Stat.CRIT_DAMAGE),
            Map.entry("INTELLIGENCE", SimStats.Stat.INTELLIGENCE),
            Map.entry("FEROCITY", SimStats.Stat.FEROCITY),
            Map.entry("ABILITY_DAMAGE_PERCENT", SimStats.Stat.ABILITY_DAMAGE),
            Map.entry("ATTACK_SPEED", SimStats.Stat.ATTACK_SPEED));

    /** NEU lore labels, as the sim's stats ("Damage" is the weapon's own and handled apart). */
    private static final Map<String, SimStats.Stat> LORE_LABELS = Map.ofEntries(
            Map.entry("health", SimStats.Stat.HEALTH),
            Map.entry("defense", SimStats.Stat.DEFENSE),
            Map.entry("strength", SimStats.Stat.STRENGTH),
            Map.entry("crit chance", SimStats.Stat.CRIT_CHANCE),
            Map.entry("crit damage", SimStats.Stat.CRIT_DAMAGE),
            Map.entry("intelligence", SimStats.Stat.INTELLIGENCE),
            Map.entry("ferocity", SimStats.Stat.FEROCITY),
            Map.entry("ability damage", SimStats.Stat.ABILITY_DAMAGE),
            Map.entry("attack speed", SimStats.Stat.ATTACK_SPEED),
            Map.entry("bonus attack speed", SimStats.Stat.ATTACK_SPEED));

    /** "Strength: +40", "Crit Damage: +30%", "Damage: +260 (+30)" - the first number is the base. */
    private static final Pattern LORE_STAT = Pattern.compile("^([A-Za-z ]+): \\+([\\d,]+(?:\\.\\d+)?)%?");
    private static final Pattern CODES = Pattern.compile("§.");

    /** An item's real stats by SkyBlock id, or null when neither source has it (yet). Any thread. */
    public static ItemStats item(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String key = id.toUpperCase(Locale.ROOT);
        ItemStats cached = ITEMS.get(key);
        if (cached != null) {
            return cached;
        }
        ItemStats found = fromApi(key);
        if (found == null) {
            found = fromNeu(key);
        }
        if (found != null) {
            ITEMS.put(key, found);
        }
        return found;
    }

    /**
     * Whether Hypixel boosts this item's stats inside a dungeon: a dungeon item, or one that can be converted into one
     * (the Terminator, the Aspect of the Void) - an M7 player's are converted and starred. Null when unknown.
     */
    public static Boolean dungeonItem(String id) {
        ItemStats s = item(id);
        return s == null ? null : s.dungeon();
    }

    private static ItemStats fromApi(String id) {
        Map<String, JsonObject> items;
        try {
            items = com.epic60869.sky2m.price.PriceService.items();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        JsonObject o = items == null ? null : items.get(id);
        if (o == null) {
            return null;
        }
        Map<SimStats.Stat, Double> stats = new EnumMap<>(SimStats.Stat.class);
        double damage = 0;
        if (o.get("stats") instanceof JsonObject so) {
            for (Map.Entry<String, JsonElement> e : so.entrySet()) {
                if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                double v = e.getValue().getAsDouble();
                // Some entries use lower-case keys (SOULWEAVER_GLOVES' "strength", FLAMING_FLAY's "damage").
                String key = e.getKey().toUpperCase(Locale.ROOT);
                if ("DAMAGE".equals(key)) {
                    damage = v;
                    continue;
                }
                SimStats.Stat s = API_KEYS.get(key);
                if (s != null) {
                    stats.merge(s, v, Double::sum);
                }
            }
        }
        boolean dungeon = (o.has("dungeon_item") && o.get("dungeon_item").getAsBoolean())
                || o.has("dungeon_item_conversion_cost");
        // Randomized dungeon drops (Skeleton Master, Ice Spray Wand) list their stats per floor tier instead.
        Map<SimStats.Stat, double[]> tiered = new EnumMap<>(SimStats.Stat.class);
        double[] tieredDamage = null;
        if (o.get("tiered_stats") instanceof JsonObject to) {
            for (Map.Entry<String, JsonElement> e : to.entrySet()) {
                if (!(e.getValue() instanceof com.google.gson.JsonArray a) || a.isEmpty()) {
                    continue;
                }
                double[] arr = new double[a.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = a.get(i).getAsDouble();
                }
                String key = e.getKey().toUpperCase(Locale.ROOT);
                if ("DAMAGE".equals(key)) {
                    tieredDamage = arr;
                } else if (API_KEYS.get(key) != null) {
                    tiered.put(API_KEYS.get(key), arr);
                }
            }
        }
        List<String> slots = new java.util.ArrayList<>();
        if (o.get("gemstone_slots") instanceof com.google.gson.JsonArray ga) {
            for (JsonElement g : ga) {
                if (g instanceof JsonObject go && go.has("slot_type")) {
                    slots.add(go.get("slot_type").getAsString());
                }
            }
        }
        return new ItemStats(Map.copyOf(stats), damage, dungeon, "Hypixel items API",
                o.has("name") ? CODES.matcher(o.get("name").getAsString()).replaceAll("") : null,
                o.has("tier") ? o.get("tier").getAsString() : null,
                o.has("category") ? o.get("category").getAsString() : null, List.copyOf(slots), Map.copyOf(tiered),
                tieredDamage);
    }

    private static ItemStats fromNeu(String id) {
        try {
            if (!com.epic60869.sky2m.features.itemlist.ItemListData.loaded()) {
                return null;
            }
            var entry = com.epic60869.sky2m.features.itemlist.ItemListData.entry(id);
            return entry == null ? null : parseLore(entry.lore(), "NEU item repo");
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * Reads the stat lines of an item's lore (NEU's, with § codes) - "Strength: +40" and the like, stopping at the first
     * blank line after them so a set bonus's "+20 Strength" is never read as a base stat - and its rarity line for
     * "DUNGEON". Public for the game test.
     */
    public static ItemStats parseLore(List<String> lore, String source) {
        if (lore == null) {
            return null;
        }
        Map<SimStats.Stat, Double> stats = new EnumMap<>(SimStats.Stat.class);
        double damage = 0;
        boolean dungeon = false;
        boolean inStats = false;
        boolean statsDone = false;
        boolean any = false;
        for (String raw : lore) {
            String line = CODES.matcher(raw == null ? "" : raw).replaceAll("").trim();
            if (line.contains("DUNGEON") && line.equals(line.toUpperCase(Locale.ROOT))) {
                dungeon = true;
            }
            if (line.isEmpty()) {
                if (inStats) {
                    inStats = false;
                    statsDone = true;
                }
                continue;
            }
            if (statsDone) {
                continue;
            }
            Matcher m = LORE_STAT.matcher(line);
            if (!m.find()) {
                continue;
            }
            String label = m.group(1).trim().toLowerCase(Locale.ROOT);
            double v;
            try {
                v = Double.parseDouble(m.group(2).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (label.equals("damage")) {
                damage = v;
                any = true;
                inStats = true;
                continue;
            }
            SimStats.Stat s = LORE_LABELS.get(label);
            if (s != null) {
                stats.merge(s, v, Double::sum);
                any = true;
                inStats = true;
            }
        }
        return any ? new ItemStats(Map.copyOf(stats), damage, dungeon, source) : null;
    }

    // ------------------------------------------------------------------ pets

    /** NEU petnums keys, as the sim's stats. */
    private static final Map<String, SimStats.Stat> PET_KEYS = Map.ofEntries(
            Map.entry("HEALTH", SimStats.Stat.HEALTH),
            Map.entry("DEFENSE", SimStats.Stat.DEFENSE),
            Map.entry("STRENGTH", SimStats.Stat.STRENGTH),
            Map.entry("CRIT_CHANCE", SimStats.Stat.CRIT_CHANCE),
            Map.entry("CRIT_DAMAGE", SimStats.Stat.CRIT_DAMAGE),
            Map.entry("INTELLIGENCE", SimStats.Stat.INTELLIGENCE),
            Map.entry("FEROCITY", SimStats.Stat.FEROCITY),
            Map.entry("ABILITY_DAMAGE", SimStats.Stat.ABILITY_DAMAGE),
            Map.entry("BONUS_ATTACK_SPEED", SimStats.Stat.ATTACK_SPEED));

    private static final List<String> RARITY_ORDER = List.of("LEGENDARY", "MYTHIC", "EPIC", "RARE", "UNCOMMON",
            "COMMON", "SPECIAL", "VERY_SPECIAL");

    /**
     * A pet's real stats at {@code level} (its rarity is the best one NEU lists, LEGENDARY first), or null until
     * petnums.json has loaded. The first call starts that load in the background. Any thread.
     */
    public static PetStats pet(String petId, int level) {
        return pet(petId, null, level);
    }

    /**
     * {@link #pet(String, int)} at a chosen rarity ("MYTHIC" Spider, "SPECIAL" Phoenix); a rarity NEU does not list
     * falls back to the best one it has.
     */
    public static PetStats pet(String petId, String wantRarity, int level) {
        if (petId == null || petId.isEmpty()) {
            return null;
        }
        String key = petId.toUpperCase(Locale.ROOT) + "@" + wantRarity + "@" + level;
        PetStats cached = PETS.get(key);
        if (cached != null) {
            return cached;
        }
        JsonObject nums = petNums();
        if (nums == null || !(nums.get(petId.toUpperCase(Locale.ROOT)) instanceof JsonObject byRarity)) {
            return null;
        }
        String rarity = wantRarity != null && byRarity.get(wantRarity.toUpperCase(Locale.ROOT)) instanceof JsonObject
                ? wantRarity.toUpperCase(Locale.ROOT) : null;
        for (String r : rarity != null ? List.<String>of() : RARITY_ORDER) {
            if (byRarity.get(r) instanceof JsonObject) {
                rarity = r;
                break;
            }
        }
        if (rarity == null) {
            return null;
        }
        PetStats found = petAt(byRarity.getAsJsonObject(rarity), rarity, level);
        if (found != null) {
            PETS.put(key, found);
        }
        return found;
    }

    /** Interpolates a rarity's table to a level. Public for the game test. */
    public static PetStats petAt(JsonObject table, String rarity, int level) {
        if (table == null) {
            return null;
        }
        // The "1".."100" rows; a curve maps levels past 100 onto them ("101:200:1": 101 -> 1 .. 200 -> 100).
        int row = level;
        if (table.get("stats_levelling_curve") instanceof JsonElement curve && curve.isJsonPrimitive()
                && level > 100) {
            String[] parts = curve.getAsString().split(":");
            try {
                int from = Integer.parseInt(parts[0].trim());
                int to = Integer.parseInt(parts[1].trim());
                if (level >= from) {
                    row = 1 + (int) Math.round((Math.min(level, to) - from) * 99.0 / Math.max(1, to - from));
                }
            } catch (RuntimeException ignored) {
                row = 100;
            }
        }
        row = Math.max(1, Math.min(100, row));
        JsonObject low = table.get("1") instanceof JsonObject o ? o : null;
        JsonObject high = table.get("100") instanceof JsonObject o ? o : null;
        if (high == null) {
            return null;
        }
        if (low == null) {
            low = high;
        }
        double t = (row - 1) / 99.0;
        Map<SimStats.Stat, Double> stats = new EnumMap<>(SimStats.Stat.class);
        JsonObject hs = high.get("statNums") instanceof JsonObject o ? o : new JsonObject();
        JsonObject ls = low.get("statNums") instanceof JsonObject o ? o : new JsonObject();
        for (Map.Entry<String, JsonElement> e : hs.entrySet()) {
            SimStats.Stat s = PET_KEYS.get(e.getKey());
            if (s == null || !e.getValue().isJsonPrimitive()) {
                continue;
            }
            double hi = e.getValue().getAsDouble();
            double lo = ls.has(e.getKey()) ? ls.get(e.getKey()).getAsDouble() : hi;
            stats.put(s, lo + (hi - lo) * t);
        }
        double[] other = null;
        if (high.get("otherNums") instanceof com.google.gson.JsonArray ha) {
            com.google.gson.JsonArray la = low.get("otherNums") instanceof com.google.gson.JsonArray a ? a : ha;
            other = new double[ha.size()];
            for (int i = 0; i < ha.size(); i++) {
                double hi = ha.get(i).getAsDouble();
                double lo = i < la.size() ? la.get(i).getAsDouble() : hi;
                other[i] = lo + (hi - lo) * t;
            }
        }
        return new PetStats(Map.copyOf(stats), other, rarity, level);
    }

    private static JsonObject petNums() {
        JsonObject n = petNums;
        if (n == null && !petNumsRequested) {
            petNumsRequested = true;
            try {
                com.epic60869.sky2m.custom.RepoItems.runAsync(() -> {
                    try {
                        petNums = JsonParser.parseString(
                                com.epic60869.sky2m.custom.RepoItems.neuRepoFile("constants/petnums.json"))
                                .getAsJsonObject();
                        PETS.clear();
                        LOGGER.info("Sim gear: pet stats loaded from the NEU repo (constants/petnums.json)");
                    } catch (Exception e) {
                        LOGGER.info("Sim gear: NEU petnums.json unavailable ({}), pets keep their built-in stats",
                                e.toString());
                    }
                });
            } catch (RuntimeException | LinkageError e) {
                // no repo pool (a stripped test environment) - the hand values stand
            }
        }
        return n;
    }

    /** Starts the background loads, so the first stats screen already shows real numbers. */
    public static void preload() {
        petNums();
    }

    // ------------------------------------------------------------------ fallback

    /** Says once per thing that the hand-written approximation is in use. */
    static void fallback(String what) {
        if (WARNED.add(what)) {
            LOGGER.info("Sim gear: no repo data for {} yet - using the sim's built-in approximate stats", what);
        }
    }

    /** Where an item's numbers came from, for the lore: "Hypixel items API", "NEU item repo" or null. */
    public static String sourceOf(String id) {
        ItemStats s = item(id);
        return s == null ? null : s.source();
    }
}
