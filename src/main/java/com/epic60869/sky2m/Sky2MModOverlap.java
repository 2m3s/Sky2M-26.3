package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Other mods that do some of what Sky2M does: a one-time chat notice listing them, and /s2 import skyhanni|skyblocker
 * to carry over a few obvious on/off settings. Import only reads the other mod's config and writes Sky2M's own, through
 * {@link Sky2MConfig#importJson}.
 */
public final class Sky2MModOverlap {
    /** Installed mod id -> the Sky2M features it overlaps. */
    private static final Map<String, String[]> OVERLAPS = new LinkedHashMap<>();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        add("skyhanni", "SkyHanni", "Estimated Item Value", "Bestiary overlay", "Compact chat", "Slayer, Diana and mining HUDs", "Chat translator");
        add("skyblocker", "Skyblocker", "Item price tooltips", "Dungeon map and solvers", "Fishing and mining HUDs", "Search overlays", "Quick navigation");
        add("noammaddons", "NoammAddons", "Dungeon features", "Kuudra features");
        add("odin", "Odin", "Dungeon features", "Chat commands");
        add("devonian", "Devonian", "Dungeon map", "Mining HUDs");
        add("skysoft", "SkySoft", "Item price tooltips", "Mining HUDs");
        add("customscoreboard", "Custom Scoreboard", "Scoreboard cleanup");
    }

    private static void add(String id, String name, String... features) {
        OVERLAPS.put(id, features);
        NAMES.put(id, name);
    }

    /** {source path in the other mod's config, Sky2M path, label}. Applied only when both are on/off values. */
    private static final String[][] SKYHANNI = {
        {"inventory.estimatedItemValues.enabled", "inventory.priceTooltip.estimatedValue", "Estimated Item Value"},
        {"chat.compactChat", "chat.compactChat", "Compact Chat"},
    };
    private static final String[][] SKYBLOCKER = {
        {"general.itemTooltip.enableLowestBIN", "inventory.priceTooltip.lowestBin", "Lowest BIN in tooltips"},
    };

    private Sky2MModOverlap() {}

    /** Ids of the installed mods that overlap Sky2M. */
    public static List<String> installed() {
        List<String> found = new ArrayList<>();
        for (String id : OVERLAPS.keySet()) if (FabricLoader.getInstance().isModLoaded(id)) found.add(id);
        return found;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(Sky2MModOverlap::notice));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("import")
                    .then(ClientCommands.literal("skyhanni").executes(c -> importFrom("SkyHanni", "skyhanni", new String[]{"config.json"}, SKYHANNI)))
                    .then(ClientCommands.literal("skyblocker").executes(c -> importFrom("Skyblocker", "skyblocker", new String[]{"skyblocker.json", "config.json"}, SKYBLOCKER)))));
            }
        });
    }

    private static void notice() {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.general.overlapNotice || config.general.overlapNoticeShown) return;
        List<String> ids = installed();
        if (ids.isEmpty()) return;
        config.general.overlapNoticeShown = true;
        Sky2MConfig.saveCurrent(config);
        StringBuilder text = new StringBuilder("Other installed mods overlap some Sky2M features: ");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) text.append("; ");
            text.append(NAMES.get(ids.get(i))).append(" (").append(String.join(", ", OVERLAPS.get(ids.get(i)))).append(")");
        }
        text.append(". Turn one side off to avoid doubled HUDs and tooltips. ");
        Component message = Component.literal(text.toString()).withStyle(ChatFormatting.YELLOW).append(
            Component.literal("[Open /s2]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/s2"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the Sky2M settings. \"Warn About Overlapping Mods\" there silences this.")))));
        Sky2MProfiles.say(message);
    }

    private static int importFrom(String name, String dir, String[] files, String[][] mapping) {
        Path base = FabricLoader.getInstance().getConfigDir().resolve(dir);
        JsonObject source = null;
        Path used = null;
        for (String file : files) {
            Path path = base.resolve(file);
            if (!Files.isRegularFile(path)) continue;
            try {
                if (Files.size(path) > 20L * 1024 * 1024) continue;
                JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    source = parsed.getAsJsonObject();
                    used = path;
                    break;
                }
            } catch (Exception ignored) {
                // Try the next candidate.
            }
        }
        if (source == null) {
            Sky2MProfiles.error("Couldn't find a readable " + name + " config in config/" + dir + ".");
            return 0;
        }
        JsonObject current = Sky2MConfig.exportJson();
        if (current == null) {
            Sky2MProfiles.error("Couldn't read your Sky2M settings; nothing was imported.");
            return 0;
        }
        JsonObject updated = current.deepCopy();
        List<String> done = new ArrayList<>();
        for (String[] map : mapping) {
            Boolean value = bool(get(source, map[0]));
            JsonObject parent = parent(updated, map[1]);
            String key = map[1].substring(map[1].lastIndexOf('.') + 1);
            // Only an existing on/off setting in Sky2M is touched; nothing is created.
            if (value == null || parent == null || bool(parent.get(key)) == null) continue;
            if (bool(parent.get(key)).booleanValue() != value.booleanValue()) parent.addProperty(key, value);
            done.add(map[2] + ": " + (value ? "on" : "off"));
        }
        if (done.isEmpty()) {
            Sky2MProfiles.say(Component.literal("Read " + used.getFileName() + " but found none of the settings Sky2M can import.").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        if (!Sky2MConfig.importJson(updated)) {
            Sky2MProfiles.error("The imported settings couldn't be loaded; your settings were left as they were.");
            return 0;
        }
        Sky2MProfiles.say(Component.literal("Imported from " + name + ": " + String.join(", ", done) + ".").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static JsonElement get(JsonObject root, String path) {
        JsonElement at = root;
        for (String key : path.split("\\.")) {
            if (at == null || !at.isJsonObject()) return null;
            at = at.getAsJsonObject().get(key);
        }
        return at;
    }

    private static JsonObject parent(JsonObject root, String path) {
        int dot = path.lastIndexOf('.');
        JsonElement parent = dot < 0 ? root : get(root, path.substring(0, dot));
        return parent != null && parent.isJsonObject() ? parent.getAsJsonObject() : null;
    }

    private static Boolean bool(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) return null;
        return element.getAsBoolean();
    }
}
