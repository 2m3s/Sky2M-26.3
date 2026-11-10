package com.epic60869.sky2m.features.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A table of Hypixel data (names, item ids, messages, areas) that Sky2M-REPO can replace without a new jar. The
 * default stays in the code; {@code constants/Tables.json} in the repo lists only the tables it replaces, by key
 * ({@code <package>.<Class>.<FIELD>}), as JSON: an object for a map, an array for a list or set. A replacement that
 * doesn't fit the table's type is logged and the default kept; removing it from the repo restores the default.
 * {@code reference/AllTables.json} in the repo lists every table with its default.
 *
 * <p>The returned map, list or set is read-only and always shows the current table, so fields keep their types.
 */
public final class RepoTable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Live> ALL = new ConcurrentHashMap<>();
    private static volatile Map<String, JsonElement> overrides = Map.of();

    private interface Live {
        boolean use(JsonElement json);

        void reset();
    }

    private RepoTable() {}

    // ------------------------------------------------------------------------------------------------ factories

    public static Map<String, String> map(String key, Map<String, String> defaults) {
        return register(key, new LiveMap<>(key, defaults, JsonElement::getAsString));
    }

    public static Map<String, Integer> intMap(String key, Map<String, Integer> defaults) {
        return register(key, new LiveMap<>(key, defaults, JsonElement::getAsInt));
    }

    public static Map<String, List<String>> listMap(String key, Map<String, List<String>> defaults) {
        return register(key, new LiveMap<>(key, defaults, json -> List.copyOf(strings(json))));
    }

    public static Map<String, String[]> arrayMap(String key, Map<String, String[]> defaults) {
        return register(key, new LiveMap<>(key, defaults, json -> strings(json).toArray(new String[0])));
    }

    public static List<String> list(String key, List<String> defaults) {
        return register(key, new LiveList(key, defaults));
    }

    public static Set<String> set(String key, Set<String> defaults) {
        return register(key, new LiveSet(key, defaults));
    }

    private static <T extends Live> T register(String key, T table) {
        if (ALL.putIfAbsent(key, table) != null) LOGGER.warn("[Sky2M] Two tables use the key {}", key);
        JsonElement override = overrides.get(key);
        if (override != null) table.use(override);
        return table;
    }

    // ------------------------------------------------------------------------------------------------ repo

    /** Starts loading Tables.json from Sky2M-REPO. */
    public static void init() {
        Sky2MRepo.load("Tables", RepoTable::apply);
    }

    /** Applies a Tables.json ({@code {"tables": {"key": <json>}}}): listed tables are replaced, the rest use their default. */
    public static void apply(JsonObject json) {
        Map<String, JsonElement> next = new HashMap<>();
        JsonObject tables = json.has("tables") ? json.getAsJsonObject("tables") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : tables.entrySet()) next.put(e.getKey(), e.getValue());
        overrides = Collections.unmodifiableMap(next);
        int replaced = 0;
        for (Map.Entry<String, Live> e : ALL.entrySet()) {
            JsonElement override = next.get(e.getKey());
            if (override == null) e.getValue().reset();
            else if (e.getValue().use(override)) replaced++;
        }
        if (!next.isEmpty()) LOGGER.info("[Sky2M] Sky2M-REPO replaces {} tables", replaced);
    }

    /** Every table loaded so far, by key, with its default as JSON (for the repo's reference list). */
    public static Map<String, Object> defaults() {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, Live> e : ALL.entrySet()) {
            Live t = e.getValue();
            if (t instanceof LiveMap<?> m) out.put(e.getKey(), m.defaults);
            else if (t instanceof LiveList l) out.put(e.getKey(), l.defaults);
            else if (t instanceof LiveSet s) out.put(e.getKey(), s.defaults);
        }
        return out;
    }

    /** The live table with this key (a Map, List or Set), or null if its class isn't loaded yet. */
    public static Object byKey(String key) {
        return ALL.get(key);
    }

    private static List<String> strings(JsonElement json) {
        JsonArray array = json.getAsJsonArray();
        List<String> out = new ArrayList<>(array.size());
        for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }

    // ------------------------------------------------------------------------------------------------ live views

    private static final class LiveMap<V> extends AbstractMap<String, V> implements Live {
        final String key;
        final Map<String, V> defaults;
        final Function<JsonElement, V> read;
        volatile Map<String, V> current;

        LiveMap(String key, Map<String, V> defaults, Function<JsonElement, V> read) {
            this.key = key;
            this.defaults = defaults;
            this.read = read;
            this.current = defaults;
        }

        @Override
        public boolean use(JsonElement json) {
            try {
                Map<String, V> next = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) next.put(e.getKey(), read.apply(e.getValue()));
                current = Collections.unmodifiableMap(next);
                return true;
            } catch (RuntimeException e) {
                LOGGER.error("[Sky2M] Sky2M-REPO's table {} isn't the right shape, keeping the current one: {}", key, e.toString());
                return false;
            }
        }

        @Override
        public void reset() {
            current = defaults;
        }

        @Override
        public Set<Entry<String, V>> entrySet() {
            return current.entrySet();
        }

        @Override
        public V get(Object k) {
            return current.get(k);
        }

        @Override
        public boolean containsKey(Object k) {
            return current.containsKey(k);
        }

        @Override
        public V getOrDefault(Object k, V fallback) {
            return current.getOrDefault(k, fallback);
        }

        @Override
        public int size() {
            return current.size();
        }

        @Override
        public Set<String> keySet() {
            return current.keySet();
        }

        @Override
        public Collection<V> values() {
            return current.values();
        }
    }

    private static final class LiveList extends AbstractList<String> implements Live {
        final String key;
        final List<String> defaults;
        volatile List<String> current;

        LiveList(String key, List<String> defaults) {
            this.key = key;
            this.defaults = defaults;
            this.current = defaults;
        }

        @Override
        public boolean use(JsonElement json) {
            try {
                current = List.copyOf(strings(json));
                return true;
            } catch (RuntimeException e) {
                LOGGER.error("[Sky2M] Sky2M-REPO's table {} isn't a list of strings, keeping the current one: {}", key, e.toString());
                return false;
            }
        }

        @Override
        public void reset() {
            current = defaults;
        }

        @Override
        public String get(int index) {
            return current.get(index);
        }

        @Override
        public int size() {
            return current.size();
        }

        @Override
        public boolean contains(Object o) {
            return current.contains(o);
        }

        @Override
        public Iterator<String> iterator() {
            return current.iterator();
        }
    }

    private static final class LiveSet extends AbstractSet<String> implements Live {
        final String key;
        final Set<String> defaults;
        volatile Set<String> current;

        LiveSet(String key, Set<String> defaults) {
            this.key = key;
            this.defaults = defaults;
            this.current = defaults;
        }

        @Override
        public boolean use(JsonElement json) {
            try {
                current = Collections.unmodifiableSet(new LinkedHashSet<>(strings(json)));
                return true;
            } catch (RuntimeException e) {
                LOGGER.error("[Sky2M] Sky2M-REPO's table {} isn't a list of strings, keeping the current one: {}", key, e.toString());
                return false;
            }
        }

        @Override
        public void reset() {
            current = defaults;
        }

        @Override
        public boolean contains(Object o) {
            return current.contains(o);
        }

        @Override
        public Iterator<String> iterator() {
            return current.iterator();
        }

        @Override
        public int size() {
            return current.size();
        }
    }
}
