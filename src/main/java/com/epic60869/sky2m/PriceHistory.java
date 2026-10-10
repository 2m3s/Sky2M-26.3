package com.epic60869.sky2m;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last lowest BIN seen for each auction item, saved in config/sky2m/price-history.json. The lowest BIN list
 * only has items that are on the auction house right now, so rarely listed items (most rune levels, for example)
 * would otherwise have no price whenever nobody is selling one.
 */
public final class PriceHistory {
    public record Seen(double price, long at) {}

    private static final Gson GSON = new Gson();
    private static final Map<String, Seen> SEEN = new ConcurrentHashMap<>();
    private static Path file;
    private static boolean loaded;

    private PriceHistory() {}

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("price-history.json");
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                SEEN.put(e.getKey(), new Seen(o.get("price").getAsDouble(), o.get("at").getAsLong()));
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read price-history.json: " + e.getMessage());
        }
    }

    /** Remembers every price in a freshly fetched lowest BIN list. Call off the render thread. */
    public static synchronized void record(Map<String, Double> lowestBins) {
        load();
        if (lowestBins.isEmpty()) return;
        long now = System.currentTimeMillis();
        lowestBins.forEach((id, price) -> {
            if (price != null && price > 0) SEEN.put(id, new Seen(price, now));
        });
        try {
            JsonObject root = new JsonObject();
            SEEN.forEach((id, seen) -> {
                JsonObject o = new JsonObject();
                o.addProperty("price", seen.price());
                o.addProperty("at", seen.at());
                root.add(id, o);
            });
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save price-history.json: " + e.getMessage());
        }
    }

    /** The last lowest BIN seen for {@code id}, or null if it has never been seen. */
    public static Seen get(String id) {
        load();
        return SEEN.get(id);
    }

    /** "3h ago", "2d ago", ... */
    public static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 60) return minutes + "m ago";
        if (minutes < 48 * 60) return (minutes / 60) + "h ago";
        return (minutes / (60 * 24)) + "d ago";
    }
}
