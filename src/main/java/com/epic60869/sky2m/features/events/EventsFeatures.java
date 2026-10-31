package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point of the Events category (Hoppity's Hunt, Chocolate Factory, Carnival, Great Spook, winter), plus the small
 * saved state they share (sky2m-events.json). Ported from SkyHanni's event features (https://github.com/hannibal002/SkyHanni,
 * LGPL-2.1).
 */
public final class EventsFeatures {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static JsonObject store = new JsonObject();
    private static Path file;

    private EventsFeatures() {}

    public static EventsConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.events;
    }

    static boolean onSkyblock() {
        return Sky2MLocation.onSkyblock();
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m-events.json");
        try {
            if (Files.exists(file)) store = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read sky2m-events.json: " + e.getMessage());
            store = new JsonObject();
        }
        HoppityFeatures.init();
        ChocolateFactoryFeatures.init();
        CarnivalFeatures.init();
        SpookFeatures.init();
        WinterFeatures.init();
        JerryFeatures.init();
        WinterGifting.init();
        HarvestFeast.init();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("resetgifttracker").executes(c -> {
                        WinterGifting.resetTracker();
                        return 1;
                    }))
                    .then(ClientCommands.literal("resetfrozentreasure").executes(c -> {
                        WinterFeatures.resetTracker();
                        return 1;
                    }))
                    .then(ClientCommands.literal("resethoppityeggs").executes(c -> {
                        HoppityFeatures.resetCollected();
                        return 1;
                    })));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ storage

    static JsonObject object(String key) {
        JsonElement e = store.get(key);
        if (e == null || !e.isJsonObject()) {
            JsonObject created = new JsonObject();
            store.add(key, created);
            return created;
        }
        return e.getAsJsonObject();
    }

    static JsonArray array(String key) {
        JsonElement e = store.get(key);
        if (e == null || !e.isJsonArray()) {
            JsonArray created = new JsonArray();
            store.add(key, created);
            return created;
        }
        return e.getAsJsonArray();
    }

    static String string(String key, String fallback) {
        JsonElement e = store.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    static long number(String key, long fallback) {
        JsonElement e = store.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static void put(String key, String value) {
        store.addProperty(key, value);
    }

    static void put(String key, long value) {
        store.addProperty(key, value);
    }

    static void remove(String key) {
        store.remove(key);
    }

    static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(store), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save sky2m-events.json: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ chat

    /** A chat line in Sky2M's prefix. */
    static void say(Component message) {
        Sky2MAlerts.chat(message);
    }

    /** A chat line the player can click to run {@code command} (with its leading slash). */
    static void sayClickable(String text, String command, String hover) {
        Component message = Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.YELLOW)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
        Minecraft.getInstance().execute(() -> say(message));
    }

    /** "1h 2m 3s" style time. */
    static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long h = seconds / 3600;
        long m = seconds % 3600 / 60;
        long s = seconds % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    static String grouped(long value) {
        return String.format(java.util.Locale.US, "%,d", value);
    }

    /** Shortens big numbers: 1.2M, 3.4B. */
    static String compact(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e12) return String.format(java.util.Locale.US, "%.2fT", value / 1e12);
        if (abs >= 1e9) return String.format(java.util.Locale.US, "%.2fB", value / 1e9);
        if (abs >= 1e6) return String.format(java.util.Locale.US, "%.2fM", value / 1e6);
        if (abs >= 1e3) return String.format(java.util.Locale.US, "%.1fk", value / 1e3);
        return String.format(java.util.Locale.US, "%.0f", value);
    }
}
