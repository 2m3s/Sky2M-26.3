package com.epic60869.sky2m.social;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.config.Sky2MConfigCode;

/**
 * Settings profiles: /s2 profile save|load|list|delete <name> keeps named copies of the settings in
 * config/sky2m/profiles/<name>.json. Saving writes the same settings the settings code shares (so personal state and
 * secrets stay out); loading goes through {@link Sky2MConfig#importJson}, which keeps sky2m-mod.json.bak and puts the old
 * settings back if the profile can't be loaded, and keeps this player's own state.
 */
public final class Sky2MProfiles {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final long MAX_BYTES = 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Sky2MProfiles() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                var name = ClientCommands.argument("name", StringArgumentType.word()).suggests((c, b) -> {
                    for (String n : names()) b.suggest(n);
                    return b.buildFuture();
                });
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("profile")
                    // No executes here: a bare /s2 profile stays the profile viewer's (it registers the same node).
                    .then(ClientCommands.literal("list").executes(c -> list()))
                    .then(ClientCommands.literal("save").then(ClientCommands.argument("name", StringArgumentType.word())
                        .executes(c -> save(StringArgumentType.getString(c, "name")))))
                    .then(ClientCommands.literal("load").then(name.executes(c -> load(StringArgumentType.getString(c, "name")))))
                    .then(ClientCommands.literal("delete").then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (String n : names()) b.suggest(n);
                            return b.buildFuture();
                        }).executes(c -> delete(StringArgumentType.getString(c, "name"))))))
                );
            }
        });
    }

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("sky2m").resolve("profiles");
    }

    private static Path file(String name) {
        return dir().resolve(name + ".json");
    }

    private static List<String> names() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                .map(n -> n.substring(0, n.length() - 5)).filter(n -> NAME.matcher(n).matches()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean valid(String name) {
        if (NAME.matcher(name).matches()) return true;
          error("A profile name is 1 to 32 letters, numbers, - or _.");
        return false;
    }

    private static int list() {
        List<String> names = names();
        if (names.isEmpty()) {
              say(Component.literal("No profiles yet. /s2 profile save <name> makes one from your current settings.").withStyle(ChatFormatting.GRAY));
        } else {
            say(Component.literal("Profiles: " + String.join(", ", names) + ". /s2 profile load <name> switches to one.").withStyle(ChatFormatting.AQUA));
        }
        return 1;
    }

    private static int save(String name) {
        if (!valid(name)) return 0;
        JsonObject current = Sky2MConfig.exportJson();
        if (current == null) {
            error("Couldn't read your settings; nothing was saved.");
            return 0;
        }
        try {
            Files.createDirectories(dir());
            Files.writeString(file(name), GSON.toJson(Sky2MConfigCode.shareable(current)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            error("Couldn't save the profile: " + e.getMessage());
            return 0;
        }
        say(Component.literal("Saved your settings as profile \"" + name + "\".").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int load(String name) {
        if (!valid(name)) return 0;
        Path file = file(name);
        if (!Files.isRegularFile(file)) {
            error("No profile called \"" + name + "\" (/s2 profile list).");
            return 0;
        }
        JsonObject profile;
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("the file is over 1 MB");
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("general")) throw new IOException("it has no Sky2M settings");
            profile = parsed.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            error("Couldn't read profile \"" + name + "\": " + e.getMessage());
            return 0;
        }
        JsonObject current = Sky2MConfig.exportJson();
        if (current == null) {
            error("Couldn't read your current settings; nothing was changed.");
            return 0;
        }
        if (Sky2MConfig.importJson(Sky2MConfigCode.keepPersonal(profile, current))) {
            say(Component.literal("Loaded profile \"" + name + "\". Your previous settings are in config/sky2m-mod.json.bak.").withStyle(ChatFormatting.GREEN));
            return 1;
        }
        error("Profile \"" + name + "\" couldn't be loaded; your settings were left as they were.");
        return 0;
    }

    private static int delete(String name) {
        if (!valid(name)) return 0;
        try {
            if (Files.deleteIfExists(file(name))) {
                say(Component.literal("Deleted profile \"" + name + "\".").withStyle(ChatFormatting.GREEN));
                return 1;
            }
        } catch (IOException e) {
            error("Couldn't delete the profile: " + e.getMessage());
            return 0;
        }
        error("No profile called \"" + name + "\" (/s2 profile list).");
        return 0;
    }

    public static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message));
        });
    }

    public static void error(String text) {
        say(Component.literal(text).withStyle(ChatFormatting.RED));
    }
}
