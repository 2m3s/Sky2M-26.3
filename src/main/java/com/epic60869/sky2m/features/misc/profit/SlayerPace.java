package com.epic60869.sky2m.features.misc.profit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The slayer trackers' Pace line: bosses per hour and profit per boss this session, next to your best session for
 * that slayer. A session only counts towards the best once it has 5 bosses and 10 minutes of active time, so a lucky
 * first boss doesn't set a record nobody can beat. Bests are saved in config/sky2m/slayer-pace-bests.json.
 */
final class SlayerPace {
    private static final int MIN_BOSSES = 5;
    private static final long MIN_ACTIVE_MS = 10 * 60_000L;
    /** Bests can rise every tick during a good session: written at most this often. */
    private static final long SAVE_EVERY_MS = 30_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final class Best {
        double bossesPerHour;
        double profitPerBoss;
    }

    private static final Map<ProfitTracker.Preset, Best> BESTS = new EnumMap<>(ProfitTracker.Preset.class);
    private static Path file;
    private static boolean dirty;
    private static long savedAt;

    private SlayerPace() {}

    /** "Pace: 14.2 bosses/h · 182k/boss (best: 16.0/h · 240k/boss)", or null when this session has no boss yet. */
    static String line(ProfitTracker.Preset preset, ProfitTracker.Stats session, double profit) {
        load();
        if (session == null || session.actions <= 0 || session.activeMs <= 0) return null;
        double perHour = session.actions / (session.activeMs / 3_600_000d);
        double perBoss = profit / session.actions;
        Best best = BESTS.computeIfAbsent(preset, p -> new Best());
        if (session.actions >= MIN_BOSSES && session.activeMs >= MIN_ACTIVE_MS) {
            if (perHour > best.bossesPerHour) {
                best.bossesPerHour = perHour;
                dirty = true;
            }
            if (perBoss > best.profitPerBoss) {
                best.profitPerBoss = perBoss;
                dirty = true;
            }
        }
        long now = System.currentTimeMillis();
        if (dirty && now - savedAt >= SAVE_EVERY_MS) save(now);
        String out = "§7Pace: §e" + String.format(Locale.US, "%.1f", perHour) + " bosses/h §8· "
            + (perBoss >= 0 ? "§6" : "§c") + ProfitTrackerHud.coins(perBoss) + "/boss";
        if (best.bossesPerHour > 0) {
            out += " §8(best: §e" + String.format(Locale.US, "%.1f", best.bossesPerHour) + "/h §8· §6"
                + ProfitTrackerHud.coins(best.profitPerBoss) + "/boss§8)";
        }
        return out;
    }

    private static void load() {
        if (file != null) return;
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("slayer-pace-bests.json");
        try {
            if (!Files.exists(file)) return;
            Map<String, Best> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Best>>() {}.getType());
            if (loaded == null) return;
            for (Map.Entry<String, Best> e : loaded.entrySet()) {
                try {
                    if (e.getValue() != null) BESTS.put(ProfitTracker.Preset.valueOf(e.getKey()), e.getValue());
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read slayer-pace-bests.json: " + e.getMessage());
        }
    }

    private static void save(long now) {
        dirty = false;
        savedAt = now;
        Map<String, Best> out = new LinkedHashMap<>();
        BESTS.forEach((preset, best) -> out.put(preset.name(), best));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(out), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save slayer-pace-bests.json: " + e.getMessage());
        }
    }
}
