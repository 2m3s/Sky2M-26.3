package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Settings as one shareable code: /s2 config export copies "S2CFG1:" + base64 of the gzipped sky2m-mod.json, and
 * /s2 config import [code] (clipboard when no code: chat can't hold a code that long) loads one after an [Apply]
 * click. Personal state (versions seen, warning mutes, the cloud-sync slot) is left out of the code and kept as it is
 * on import; anything that looks like a credential is stripped both ways in case a later setting adds one.
 */
public final class Sky2MConfigCode {
    private static final String PREFIX = "S2CFG1:";
    /** A real config is ~40 KB; this stops a crafted code from inflating into something huge. */
    private static final int MAX_JSON_BYTES = 1024 * 1024;
    private static final int KEEP_BACKUPS = 10;
    private static final Gson GSON = new Gson();
    /** Paths (category.key...) that are this player's state, not settings to share. */
    private static final List<String> PERSONAL = List.of(
        "general.lastSeenVersion", "general.whatsNewSince", "general.firstBootAcknowledged",
        "general.perfWarningMuted", "general.overlapNoticeShown", "online.settingsSync.slot");
    /** Key names that would hold a secret; none exist in the config today, this guards future ones. */
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(token|api_?key|password|webhook|discord_?id|uuid).*");

    /** The code waiting for [Apply], and its id so an old chat button can't apply a newer code. */
    private static JsonObject pending;
    private static int pendingId;

    private Sky2MConfigCode() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("config")
                    .then(ClientCommands.literal("export").executes(c -> {
                        exportToClipboard();
                        return 1;
                    }))
                    .then(ClientCommands.literal("import")
                        .executes(c -> {
                            prepareImport(Minecraft.getInstance().keyboardHandler.getClipboard(), true);
                            return 1;
                        })
                        .then(ClientCommands.argument("code", StringArgumentType.greedyString()).executes(c -> {
                            prepareImport(StringArgumentType.getString(c, "code"), false);
                            return 1;
                        })))
                    .then(ClientCommands.literal("apply")
                        .then(ClientCommands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(c -> {
                            apply(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "id"));
                            return 1;
                        })))
                    .then(ClientCommands.literal("cancel").executes(c -> {
                        if (pending != null) say(Component.literal("Settings import cancelled; nothing was changed.").withStyle(ChatFormatting.GRAY));
                        pending = null;
                        return 1;
                    }))));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ export

    public static void exportToClipboard() {
        JsonObject json = Sky2MConfig.exportJson();
        if (json == null) {
            error("Couldn't read your settings.");
            return;
        }
        json = json.deepCopy();
        for (String path : PERSONAL) remove(json, path);
        stripSecrets(json);
        try {
            String code = encode(GSON.toJson(json));
            Minecraft.getInstance().keyboardHandler.setClipboard(code);
            say(Component.literal("Copied your settings code to the clipboard (" + code.length() / 1024 + " KB). Others load it with /s2 config import.")
                .withStyle(ChatFormatting.GREEN));
        } catch (IOException e) {
            error("Couldn't make a settings code: " + e.getMessage());
        }
    }

    static String encode(String json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    /** The settings in a code, or throws with a message fit for chat. */
    static JsonObject decode(String code) {
        String trimmed = code == null ? "" : code.strip();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("No code given (and the clipboard is empty).");
        if (!trimmed.startsWith(PREFIX)) {
            if (trimmed.startsWith("S2CFG")) throw new IllegalArgumentException("That code is from a newer Sky2M version; update to load it.");
            throw new IllegalArgumentException("That isn't a Sky2M settings code (it should start with " + PREFIX + ").");
        }
        byte[] gz;
        try {
            // Pasting can add spaces or line breaks; base64 has none.
            gz = Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length()).replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The code is damaged (not valid base64); copy it again.");
        }
        String json;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            byte[] data = in.readNBytes(MAX_JSON_BYTES + 1);
            if (data.length > MAX_JSON_BYTES) throw new IllegalArgumentException("The code is too big (over 1 MB of settings).");
            json = new String(data, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("The code is damaged or cut off; copy all of it again.");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The code's settings aren't valid JSON.");
        }
        if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("general")) {
            throw new IllegalArgumentException("The code doesn't contain Sky2M settings.");
        }
        return parsed.getAsJsonObject();
    }

    // ------------------------------------------------------------------------------------------------ import

    /** Opened from the settings GUI's button: close it so the [Apply] message in chat can be seen and clicked. */
    public static void importFromGui() {
        Minecraft mc = Minecraft.getInstance();
        String clipboard = mc.keyboardHandler.getClipboard();
        mc.execute(() -> {
            if (mc.player == null) {
                error("Join a world to import settings (the confirmation is shown in chat).");
                return;
            }
            mc.gui.setScreen(null);
            prepareImport(clipboard, true);
        });
    }

    private static void prepareImport(String code, boolean fromClipboard) {
        JsonObject settings;
        try {
            settings = decode(code);
        } catch (IllegalArgumentException e) {
            error((fromClipboard ? "Clipboard: " : "") + e.getMessage());
            return;
        }
        pending = settings;
        pendingId++;
        MutableComponent message = Component.literal("Replace all your Sky2M settings with this code? ").withStyle(ChatFormatting.YELLOW)
            .append(button("[Apply]", ChatFormatting.GREEN, "/s2 config apply " + pendingId,
                "Load these settings. Your current ones are backed up to config/sky2m/config-backups first."))
            .append(Component.literal(" "))
            .append(button("[Cancel]", ChatFormatting.RED, "/s2 config cancel", "Keep your current settings"));
        say(message);
    }

    private static void apply(int id) {
        if (pending == null || id != pendingId) {
            error("That settings code is no longer waiting; import it again.");
            return;
        }
        JsonObject settings = pending.deepCopy();
        pending = null;
        JsonObject current = Sky2MConfig.exportJson();
        if (current == null) {
            error("Couldn't read your current settings; nothing was changed.");
            return;
        }
        Path backup;
        try {
            backup = backup(current);
        } catch (IOException e) {
            error("Couldn't back up your current settings (" + e.getMessage() + "); nothing was changed.");
            return;
        }
        stripSecrets(settings);
        // Keep this player's own state rather than the sharer's (or a missing value).
        for (String path : PERSONAL) {
            remove(settings, path);
            JsonElement mine = get(current, path);
            if (mine != null) put(settings, path, mine.deepCopy());
        }
        restoreSecrets(current, settings);
        if (Sky2MConfig.importJson(settings)) {
            say(Component.literal("Settings imported. Your old settings are in config/sky2m/config-backups/" + backup.getFileName() + ".")
                .withStyle(ChatFormatting.GREEN));
        } else {
            error("Those settings couldn't be loaded; nothing was changed.");
        }
    }

    /** The settings with this player's own state removed, for saving as a profile. */
    static JsonObject shareable(JsonObject current) {
        JsonObject copy = current.deepCopy();
        for (String path : PERSONAL) remove(copy, path);
        stripSecrets(copy);
        return copy;
    }

    /** {@code incoming} with this player's own state and secrets from {@code current} put in, ready for importJson. */
    static JsonObject keepPersonal(JsonObject incoming, JsonObject current) {
        JsonObject settings = incoming.deepCopy();
        stripSecrets(settings);
        for (String path : PERSONAL) {
            remove(settings, path);
            JsonElement mine = get(current, path);
            if (mine != null) put(settings, path, mine.deepCopy());
        }
        restoreSecrets(current, settings);
        return settings;
    }

    /** Writes the current settings to config/sky2m/config-backups/<time>.json and keeps the newest 10. */
    private static Path backup(JsonObject current) throws IOException {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("sky2m").resolve("config-backups");
        Files.createDirectories(dir);
        Path file = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".json");
        Files.writeString(file, GSON.toJson(current), StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.list(dir)) {
            // The timestamped names sort oldest first.
            List<Path> all = new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList());
            while (all.size() > KEEP_BACKUPS) Files.deleteIfExists(all.removeFirst());
        }
        return file;
    }

    // ------------------------------------------------------------------------------------------------ json paths

    private static JsonElement get(JsonObject root, String path) {
        JsonElement at = root;
        for (String key : path.split("\\.")) {
            if (at == null || !at.isJsonObject()) return null;
            at = at.getAsJsonObject().get(key);
        }
        return at;
    }

    private static void remove(JsonObject root, String path) {
        int dot = path.lastIndexOf('.');
        JsonElement parent = dot < 0 ? root : get(root, path.substring(0, dot));
        if (parent != null && parent.isJsonObject()) parent.getAsJsonObject().remove(path.substring(dot + 1));
    }

    private static void put(JsonObject root, String path, JsonElement value) {
        String[] keys = path.split("\\.");
        JsonObject at = root;
        for (int i = 0; i < keys.length - 1; i++) {
            if (!at.has(keys[i]) || !at.get(keys[i]).isJsonObject()) at.add(keys[i], new JsonObject());
            at = at.getAsJsonObject(keys[i]);
        }
        at.add(keys[keys.length - 1], value);
    }

    private static void stripSecrets(JsonObject object) {
        for (String key : new ArrayList<>(object.keySet())) {
            JsonElement value = object.get(key);
            if (SECRET_KEY.matcher(key).matches()) object.remove(key);
            else if (value.isJsonObject()) stripSecrets(value.getAsJsonObject());
        }
    }

    /** Puts this player's own secret-looking values back after an import stripped them. */
    private static void restoreSecrets(JsonObject from, JsonObject into) {
        for (Map.Entry<String, JsonElement> e : from.entrySet()) {
            if (SECRET_KEY.matcher(e.getKey()).matches()) into.add(e.getKey(), e.getValue().deepCopy());
            else if (e.getValue().isJsonObject()) {
                if (!into.has(e.getKey()) || !into.get(e.getKey()).isJsonObject()) continue;
                restoreSecrets(e.getValue().getAsJsonObject(), into.getAsJsonObject(e.getKey()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static MutableComponent button(String text, ChatFormatting colour, String command, String hover) {
        return Component.literal(text).withStyle(s -> s.withColor(colour).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message));
        });
    }

    private static void error(String text) {
        say(Component.literal(text).withStyle(ChatFormatting.RED));
    }
}
