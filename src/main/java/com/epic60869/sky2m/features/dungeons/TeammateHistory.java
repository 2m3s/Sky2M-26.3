package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dungeons > Team Secrets > Teammate History: each player's average secrets, rooms and deaths in your runs, added up when
 * {@link TeamSecrets} has its numbers for the run. Saved in config/sky2m/teammate-history.json, and shown by Played Before
 * ({@link PfHistory}) and /s2 pfhistory &lt;name&gt;. Rooms are the same rough count as Rooms Done.
 */
public final class TeammateHistory {
    /** One run's numbers: secrets by name (below 0 when their total couldn't be loaded), and rooms and deaths by lower-case name. */
    public record Run(Map<String, Long> secrets, Map<String, RoomClears.Counts> rooms, Map<String, Integer> deaths) {
        /** With secrets from a later look; someone it couldn't load keeps what they had. */
        Run withLaterSecrets(Map<String, Long> later) {
            Map<String, Long> merged = new LinkedHashMap<>(secrets);
            later.forEach((name, found) -> {
                if (found >= 0) merged.put(name, found);
            });
            return new Run(merged, rooms, deaths);
        }
    }

    /** One player's totals. {@code secretRuns} and {@code roomRuns} are the runs those were known for. */
    public static final class Totals {
        public String name;
        public int runs, secretRuns, roomRuns;
        public long secrets, roomsSolo, roomsStacked, deaths;
    }

    /** Everyone's totals, by lower-case name. Public for the game test. */
    public static final class Store {
        private final Map<String, Totals> players = new LinkedHashMap<>();

        public Totals get(String name) {
            return players.get(name.toLowerCase(Locale.ROOT));
        }

        /** Adds one player's run. */
        public void add(String name, long secrets, RoomClears.Counts rooms, int deaths) {
            Totals totals = players.computeIfAbsent(name.toLowerCase(Locale.ROOT), n -> new Totals());
            totals.name = name;
            totals.runs++;
            totals.deaths += deaths;
            if (secrets >= 0) {
                totals.secretRuns++;
                totals.secrets += secrets;
            }
            if (rooms != null) {
                totals.roomRuns++;
                totals.roomsSolo += rooms.solo().size();
                totals.roomsStacked += rooms.stacked().size();
            }
        }

        public void add(Run run) {
            run.secrets().forEach((name, secrets) -> {
                String key = name.toLowerCase(Locale.ROOT);
                RoomClears.Counts rooms = run.rooms() == null ? null : run.rooms().getOrDefault(key, new RoomClears.Counts(List.of(), List.of()));
                add(name, secrets, rooms, run.deaths().getOrDefault(key, 0));
            });
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject list = new JsonObject();
            var gson = new GsonBuilder().create();
            players.forEach((key, totals) -> list.add(key, gson.toJsonTree(totals)));
            root.add("players", list);
            return new GsonBuilder().setPrettyPrinting().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad player is skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject players = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("players");
            if (players == null) return store;
            var gson = new GsonBuilder().create();
            for (var entry : players.entrySet()) {
                try {
                    Totals totals = gson.fromJson(entry.getValue(), Totals.class);
                    if (totals != null && totals.runs > 0) store.players.put(entry.getKey().toLowerCase(Locale.ROOT), totals);
                } catch (RuntimeException ignored) {
                    // Only this player is lost.
                }
            }
            return store;
        }
    }

    private static Store store = new Store();
    private static Path file;

    private TeammateHistory() {}

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.dungeons.teamSecrets.teammateHistory;
    }

    static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("teammate-history.json");
        store = SavedJson.load(file, Store::fromJson, store);
    }

    /** Adds a finished run's numbers. */
    static void record(Run run) {
        if (!enabled() || file == null || run.secrets().isEmpty()) return;
        store.add(run);
        SavedJson.save(file, store.toJson());
    }

    /** For Played Before: their averages, or null if there's nothing saved or this is off. */
    static String summary(String name) {
        if (!enabled()) return null;
        return summary(store.get(name));
    }

    /** "avg 12.3 secrets, 3.2-4.5 rooms, 0.5 deaths over 8 runs". Null without any runs. Public for the game test. */
    public static String summary(Totals t) {
        if (t == null || t.runs <= 0) return null;
        StringBuilder out = new StringBuilder("avg ");
        if (t.secretRuns > 0) out.append(one((double) t.secrets / t.secretRuns)).append(" secrets, ");
        if (t.roomRuns > 0) {
            String solo = one((double) t.roomsSolo / t.roomRuns);
            out.append(t.roomsStacked == 0 ? solo : solo + "-" + one((double) (t.roomsSolo + t.roomsStacked) / t.roomRuns)).append(" rooms, ");
        }
        out.append(one((double) t.deaths / t.runs)).append(" deaths over ").append(t.runs).append(t.runs == 1 ? " run" : " runs");
        return out.toString();
    }

    private static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
