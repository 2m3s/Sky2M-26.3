package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Route;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Step;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Shareable route packs. A pack is a route file in the "dungeon route" folder, so it works like any other file there, and
 * sharing one is a text through the clipboard: gzip then Base64 of the same JSON, the way Odin shares its waypoint packs
 * (https://github.com/odtheking/Odin, config/DungeonWaypointConfig.kt, BSD-3-Clause). Importing never replaces anything: it
 * writes a new file. Turning a pack off renames the file to .json.disabled.
 */
public final class RoutePacks {
    private static final String PREFIX = "S2MPACK1:";
    private static final String ON = ".json", OFF = ".json.disabled";
    /** Far above any real pack; stops a tiny pasted text unpacking into gigabytes. */
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;

    private RoutePacks() {}

    static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("route")
                        .then(ClientCommands.literal("pack")
                            .then(ClientCommands.literal("export")
                                .executes(c -> exportAll())
                                .then(ClientCommands.literal("room").executes(c -> exportRoom())))
                            .then(ClientCommands.literal("import")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .executes(c -> importClipboard(StringArgumentType.getString(c, "name")))))
                            .then(ClientCommands.literal("list").executes(c -> list()))
                            .then(ClientCommands.literal("on")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(names(OFF), b))
                                    .executes(c -> turn(StringArgumentType.getString(c, "name"), true))))
                            .then(ClientCommands.literal("off")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(names(ON), b))
                                    .executes(c -> turn(StringArgumentType.getString(c, "name"), false)))))));
            }
        });
    }

    // ----- The text form. Public for the game test. -----

    public static String encode(String json) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** The JSON in a shared text, which can be a pack text or plain JSON (a route file). Throws when it's neither. */
    public static String decode(String text) throws IOException {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) return trimmed;
        if (trimmed.startsWith(PREFIX)) trimmed = trimmed.substring(PREFIX.length());
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(trimmed.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new IOException("that isn't a route pack");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            byte[] json = gzip.readNBytes(MAX_JSON_BYTES + 1);
            if (json.length > MAX_JSON_BYTES) throw new IOException("that pack is too big");
            return new String(json, StandardCharsets.UTF_8);
        }
    }

    /** A file name from what was typed: letters, digits, spaces, - and _ only, so it can't point outside the folder. Empty if nothing is left. */
    public static String safeName(String name) {
        return name.trim().replaceAll("[^A-Za-z0-9 _-]", "").replaceAll("\\s+", " ").trim();
    }

    // ----- Export and import -----

    static int exportAll() {
        Map<String, List<Step>> own = DungeonRoutes.ownRoutes();
        if (own.isEmpty()) return DungeonRoutes.say("You have no routes of your own to share yet. /s2 route pack export room shares what this room shows.", ChatFormatting.RED);
        return copy(own, "Sky2M route pack");
    }

    static int exportRoom() {
        Room room = DungeonRoutes.room();
        if (room == null) return DungeonRoutes.say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        Map<String, List<Step>> routes = new LinkedHashMap<>();
        for (Route route : DungeonRoutes.routesFor(room.getName())) routes.put(route.key(), route.steps());
        if (routes.isEmpty()) return DungeonRoutes.say("No route for this room.", ChatFormatting.RED);
        return copy(routes, room.getName() + " routes");
    }

    private static int copy(Map<String, List<Step>> routes, String name) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(encode(DungeonRoutes.serialize(routes, name)));
            return DungeonRoutes.say("Copied " + routes.size() + (routes.size() == 1 ? " route" : " routes") + " to your clipboard. Someone else adds them with /s2 route pack import <name>.", ChatFormatting.GREEN);
        } catch (Exception e) {
            return DungeonRoutes.say("Export failed: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    static int importClipboard(String typedName) {
        String name = safeName(typedName);
        if (name.isEmpty()) return DungeonRoutes.say("Give the pack a name of letters and numbers: /s2 route pack import <name>.", ChatFormatting.RED);
        String text = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (text == null || text.isBlank()) return DungeonRoutes.say("Your clipboard is empty. Copy a route pack first.", ChatFormatting.RED);
        try {
            Map<String, List<Step>> routes = DungeonRoutes.parseAny(decode(text));
            if (routes.isEmpty()) return DungeonRoutes.say("No routes found in your clipboard.", ChatFormatting.RED);
            Path out = freeFile(name);
            DungeonRoutes.writeSafely(out, DungeonRoutes.serialize(routes, name));
            DungeonRoutes.reloadRouteFolder();
            String saved = out.getFileName().toString();
            return DungeonRoutes.say("Added " + routes.size() + (routes.size() == 1 ? " route" : " routes") + " as the pack " + saved.substring(0, saved.length() - ON.length())
                + ". Your own routes still come first in the same rooms; /s2 route pack off <name> turns it off.", ChatFormatting.GREEN);
        } catch (Exception e) {
            return DungeonRoutes.say("That isn't a route pack: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    /** {@code name.json}, or {@code name 2.json} on, never a file that's there already, on or off. */
    private static Path freeFile(String name) {
        Path folder = DungeonRoutes.routeFolder();
        String candidate = name;
        for (int n = 2; Files.exists(folder.resolve(candidate + ON)) || Files.exists(folder.resolve(candidate + OFF)); n++) candidate = name + " " + n;
        return folder.resolve(candidate + ON);
    }

    // ----- List, on and off -----

    /** The packs with this ending, without it. */
    private static List<String> names(String ending) {
        List<String> names = new ArrayList<>();
        try (var files = Files.list(DungeonRoutes.routeFolder())) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String file = p.getFileName().toString();
                if (file.toLowerCase(Locale.ROOT).endsWith(ending)) names.add(file.substring(0, file.length() - ending.length()));
            }
        } catch (Exception ignored) {}
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static int list() {
        List<String> on = names(ON), off = names(OFF);
        if (on.isEmpty() && off.isEmpty()) return DungeonRoutes.say("No route packs yet. /s2 route pack import <name> adds one from your clipboard.", ChatFormatting.YELLOW);
        return DungeonRoutes.say("Route packs on: " + (on.isEmpty() ? "none" : String.join(", ", on)) + ". Off: " + (off.isEmpty() ? "none" : String.join(", ", off)) + ".", ChatFormatting.YELLOW);
    }

    private static int turn(String typedName, boolean on) {
        String name = typedName.trim();
        if (!names(on ? OFF : ON).contains(name)) return DungeonRoutes.say("No " + (on ? "turned off " : "") + "pack called " + name + ". See /s2 route pack list.", ChatFormatting.RED);
        Path folder = DungeonRoutes.routeFolder();
        try {
            Files.move(folder.resolve(name + (on ? OFF : ON)), folder.resolve(name + (on ? ON : OFF)));
            DungeonRoutes.reloadRouteFolder();
            return DungeonRoutes.say("Route pack " + name + " is " + (on ? "on" : "off") + ".", ChatFormatting.YELLOW);
        } catch (Exception e) {
            return DungeonRoutes.say("Could not change " + name + ": " + e.getMessage(), ChatFormatting.RED);
        }
    }
}
