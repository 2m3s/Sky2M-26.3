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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.epic60869.sky2m.hud.Sky2MRivalHud;

/**
 * Rivals: players you race on one leaderboard, or a few (Wheat and Cactus, say). /s2 rival add &lt;name&gt; &lt;board&gt;
 * (or click them in /s2 leaderboard's standings for the board that's open); on those boards they're highlighted with
 * how far ahead or behind you they are, and when a board's standings show a rival has gone from behind you to ahead
 * of you, you're told in chat. The Rival HUD ({@link Sky2MRivalHud}) shows the board you're working on.
 *
 * <p>A board is "type|id" as the leaderboard screen keys it ("collection|WHEAT", "slayer|zombie", "pb|...",
 * "event|..."); the weekly views count as the same board. {@link #ALL} is every board: rivals saved before rivals
 * were per board (rivals.json was a plain list of names) are kept that way, so nothing changes for them until
 * they're set on a board.
 *
 * <p>Kept in config/sky2m/rivals.json: {"version": 2, "rivals": {"name": {"collection|WHEAT": "Wheat", ...}}}, the
 * value being the board's name for /s2 rival list (so the list doesn't need the server).
 */
public final class Sky2MRivals {
    /** Every board: what rivals saved before per-board rivals became. */
    public static final String ALL = "*";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Rival (lower case) -> board key -> board name, in the order they were added. */
    private static Map<String, Map<String, String>> rivals;
    /** "board|rival" -> whether the rival was ahead of you the last time that board's standings came. */
    private static final Map<String, Boolean> AHEAD = new HashMap<>();

    private Sky2MRivals() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("rival")
                    .executes(c -> {
                        list();
                        return 1;
                    })
                    .then(ClientCommands.literal("list").executes(c -> {
                        list();
                        return 1;
                    }))
                    .then(ClientCommands.literal("add")
                        .then(ClientCommands.argument("player", StringArgumentType.word())
                            .executes(c -> {
                                say(Component.literal("Which board? /s2 rival add " + StringArgumentType.getString(c, "player")
                                    + " <board>, e.g. Wheat, Cactus, slayer zombie or all. Or click them in /s2 leaderboard.").withStyle(ChatFormatting.YELLOW));
                                return 1;
                            })
                            .then(ClientCommands.argument("board", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(boardSuggestions(), b))
                                .executes(c -> {
                                    addByName(StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "board"));
                                    return 1;
                                }))))
                    .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("player", StringArgumentType.word())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(names(), b))
                            .executes(c -> {
                                remove(StringArgumentType.getString(c, "player"), null);
                                return 1;
                            })
                            .then(ClientCommands.argument("board", StringArgumentType.greedyString())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                    boardNamesOf(StringArgumentType.getString(c, "player")), b))
                                .executes(c -> {
                                    removeByName(StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "board"));
                                    return 1;
                                }))))));
            }
        });
        Sky2MRivalHud.init();
        Sky2MRivalRecap.init();
    }

    // ---------------------------------------------------------------- boards

    /** A board key as rivals keep it: the weekly view ("collectionweek|WHEAT") is the same board as all-time. */
    public static String board(String key) {
        if (key == null) return "";
        int bar = key.indexOf('|');
        if (bar <= 0) return key;
        String type = key.substring(0, bar);
        if (type.endsWith("week")) type = type.substring(0, type.length() - "week".length());
        return type + key.substring(bar);
    }

    /** "collection|WHEAT" -> "Wheat" from the server's board list when it's loaded, else something readable. */
    public static String boardName(String key) {
        if (ALL.equals(key)) return "all boards";
        String board = board(key);
        int bar = board.indexOf('|');
        String type = bar < 0 ? "" : board.substring(0, bar), id = bar < 0 ? board : board.substring(bar + 1);
        Sky2MLeaderboards.StatListData list = Sky2MLeaderboards.statList();
        switch (type) {
            case "collection" -> {
                if (list != null) for (Sky2MLeaderboards.StatBoard b : list.collections())
                    if (b.id().equalsIgnoreCase(id)) return b.name().replaceFirst(" Collection$", "");
                return titleCase(id);
            }
            case "slayer" -> {
                if (list != null) for (Sky2MLeaderboards.StatBoard b : list.slayers())
                    if (b.id().equalsIgnoreCase(id)) return "Slayer XP " + b.name();
                return "Slayer XP " + titleCase(id);
            }
            case "pb" -> {
                return "Kill Times " + id;
            }
            case "event" -> {
                for (Sky2MLeaderboards.EventBoard b : Sky2MLeaderboards.eventBoards()) if (b.id().equals(id)) return b.name();
                return "Event " + id;
            }
            default -> {
                return board;
            }
        }
    }

    private static String titleCase(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("[_:\\s]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * What a typed board means: "all", a collection by name or ID ("Wheat", "sugar cane", "INK_SACK:3"), "slayer
     * &lt;boss&gt;" / "slayer xp &lt;boss&gt;", or a running event's name. Null when it's none of those.
     */
    static String resolveBoard(String input) {
        String text = input == null ? "" : input.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()) return null;
        if (lower.equals("all") || lower.equals("*") || lower.equals("all boards")) return ALL;
        Sky2MLeaderboards.StatListData list = Sky2MLeaderboards.statList();
        if (lower.startsWith("slayer ")) {
            String boss = lower.replaceFirst("^slayer (xp )?", "").trim();
            if (list != null) {
                for (Sky2MLeaderboards.StatBoard b : list.slayers()) {
                    if (b.id().equalsIgnoreCase(boss) || b.name().equalsIgnoreCase(boss)) return "slayer|" + b.id();
                }
                return null;
            }
            return boss.isEmpty() ? null : "slayer|" + boss;
        }
        for (Sky2MLeaderboards.EventBoard b : Sky2MLeaderboards.eventBoards()) {
            if (b.name().equalsIgnoreCase(text)) return "event|" + b.id();
        }
        String collection = lower.replaceFirst("^collections? ", "").replaceFirst(" collection$", "").trim();
        String id = collection.toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
        if (list != null) {
            for (Sky2MLeaderboards.StatBoard b : list.collections()) {
                if (b.id().equalsIgnoreCase(id) || b.name().replaceFirst(" Collection$", "").equalsIgnoreCase(collection)) {
                    return "collection|" + b.id();
                }
            }
            return null;
        }
        // The board list hasn't come yet: take it as a collection ID (and ask for the list for next time).
        Sky2MLeaderboards.requestStatList();
        return id.matches("[A-Z0-9_:]{2,40}") ? "collection|" + id : null;
    }

    /** Tab completion for a board: every collection, slayer XP board and running event, and "all". */
    private static List<String> boardSuggestions() {
        List<String> out = new ArrayList<>();
        Sky2MLeaderboards.StatListData list = Sky2MLeaderboards.statList();
        if (list == null) Sky2MLeaderboards.requestStatList();
        else {
            for (Sky2MLeaderboards.StatBoard b : list.collections()) out.add(b.name().replaceFirst(" Collection$", ""));
            for (Sky2MLeaderboards.StatBoard b : list.slayers()) out.add("slayer " + b.id());
        }
        for (Sky2MLeaderboards.EventBoard b : Sky2MLeaderboards.eventBoards()) out.add(b.name());
        out.add("all");
        return out;
    }

    // ---------------------------------------------------------------- storage

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("rivals.json");
    }

    private static synchronized Map<String, Map<String, String>> rivals() {
        if (rivals == null) {
            rivals = new LinkedHashMap<>();
            try {
                if (Files.exists(file())) {
                    JsonElement root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
                    if (root.isJsonArray()) {
                        // Rivals from before they were per board: every board, as they were. The old file is kept.
                        for (JsonElement e : root.getAsJsonArray()) {
                            String name = e.isJsonPrimitive() ? e.getAsString() : null;
                            if (name != null && name.matches("\\w{1,16}")) {
                                rivals.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>()).put(ALL, "all boards");
                            }
                        }
                        Files.copy(file(), file().resolveSibling("rivals-v1.json"), StandardCopyOption.REPLACE_EXISTING);
                        save();
                    } else if (root.isJsonObject() && root.getAsJsonObject().has("rivals") && root.getAsJsonObject().get("rivals").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().getAsJsonObject("rivals").entrySet()) {
                            if (!e.getKey().matches("\\w{1,16}") || !e.getValue().isJsonObject()) continue;
                            Map<String, String> boards = new LinkedHashMap<>();
                            for (Map.Entry<String, JsonElement> b : e.getValue().getAsJsonObject().entrySet()) {
                                if (b.getKey().isBlank()) continue;
                                boards.put(b.getKey(), b.getValue().isJsonPrimitive() ? b.getValue().getAsString() : boardName(b.getKey()));
                            }
                            if (!boards.isEmpty()) rivals.put(e.getKey().toLowerCase(Locale.ROOT), boards);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read rivals.json: " + e.getMessage());
            }
        }
        return rivals;
    }

    private static synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 2);
            JsonObject all = new JsonObject();
            for (Map.Entry<String, Map<String, String>> e : rivals.entrySet()) {
                JsonObject boards = new JsonObject();
                e.getValue().forEach(boards::addProperty);
                all.add(e.getKey(), boards);
            }
            root.add("rivals", all);
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save rivals.json: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- queries

    public static synchronized List<String> names() {
        return new ArrayList<>(rivals().keySet());
    }

    /** Whether {@code name} is your rival on this board (or on every board). */
    public static synchronized boolean isRival(String name, String board) {
        if (name == null) return false;
        Map<String, String> boards = rivals().get(name.toLowerCase(Locale.ROOT));
        return boards != null && (boards.containsKey(ALL) || boards.containsKey(board(board)));
    }

    /** Whether {@code name} is your rival on every board (a rival from before per-board rivals). */
    public static synchronized boolean isRivalEverywhere(String name) {
        Map<String, String> boards = name == null ? null : rivals().get(name.toLowerCase(Locale.ROOT));
        return boards != null && boards.containsKey(ALL);
    }

    /** Your rivals on this board (lower case). */
    public static synchronized List<String> rivalsOn(String board) {
        String key = board(board);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : rivals().entrySet()) {
            if (e.getValue().containsKey(ALL) || e.getValue().containsKey(key)) out.add(e.getKey());
        }
        return out;
    }

    /** Whether anyone is your rival on this board. */
    public static synchronized boolean hasRivalsOn(String board) {
        String key = board(board);
        for (Map<String, String> boards : rivals().values()) if (boards.containsKey(ALL) || boards.containsKey(key)) return true;
        return false;
    }

    /** Whether anyone is your rival on this board itself (not only through "all boards"), for the board list. */
    public static synchronized boolean hasOwnRivalsOn(String board) {
        if (board == null) return false;
        String key = board(board);
        for (Map<String, String> boards : rivals().values()) if (boards.containsKey(key)) return true;
        return false;
    }

    /**
     * Every board someone is your rival on by name (not "all boards"), key -> board name, for the weekly recap's
     * fetching: "all boards" rivals would mean every board.
     */
    public static synchronized Map<String, String> ownBoards() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> boards : rivals().values()) {
            for (Map.Entry<String, String> b : boards.entrySet()) if (!ALL.equals(b.getKey())) out.putIfAbsent(b.getKey(), b.getValue());
        }
        return out;
    }

    /** The names of the boards {@code name} is your rival on, for tooltips and tab completion. */
    public static synchronized List<String> boardNamesOf(String name) {
        Map<String, String> boards = name == null ? null : rivals().get(name.toLowerCase(Locale.ROOT));
        return boards == null ? List.of() : new ArrayList<>(boards.values());
    }

    // ---------------------------------------------------------------- changes

    private static boolean validName(String clean) {
        if (!clean.matches("\\w{1,16}")) {
            say(Component.literal("That isn't a Minecraft name.").withStyle(ChatFormatting.RED));
            return false;
        }
        if (clean.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) {
            say(Component.literal("You can't be your own rival.").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        return true;
    }

    private static void addByName(String name, String boardText) {
        String board = resolveBoard(boardText);
        if (board == null) {
            say(Component.literal("No leaderboard called \"" + boardText.trim() + "\". Press Tab for the boards, or click them in /s2 leaderboard.")
                .withStyle(ChatFormatting.RED));
            return;
        }
        add(name, board, boardName(board));
    }

    /** Makes {@code name} your rival on one board ({@link #ALL} for every board) and says so. */
    public static synchronized void add(String name, String board, String boardName) {
        String clean = name == null ? "" : name.trim();
        if (!validName(clean)) return;
        String key = ALL.equals(board) ? ALL : board(board);
        String label = boardName == null || boardName.isBlank() ? boardName(key) : boardName;
        Map<String, String> boards = rivals().computeIfAbsent(clean.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>());
        boolean changed = !boards.containsKey(key);
        boards.put(key, label);
        if (changed) save();
        say(Component.literal(changed ? clean + " is now your rival on " + label + ". They're highlighted there in /s2 leaderboard and you'll hear when they pass you."
            : clean + " is already your rival on " + label + ".").withStyle(ChatFormatting.GOLD));
    }

    private static void removeByName(String name, String boardText) {
        // A board as /s2 rival list shows it ("Wheat") first, then anything /s2 rival add takes.
        String board = null;
        synchronized (Sky2MRivals.class) {
            Map<String, String> boards = rivals().get(name.trim().toLowerCase(Locale.ROOT));
            if (boards != null) {
                for (Map.Entry<String, String> e : boards.entrySet()) {
                    if (e.getValue().equalsIgnoreCase(boardText.trim())) board = e.getKey();
                }
            }
        }
        if (board == null) board = resolveBoard(boardText);
        if (board == null) {
            say(Component.literal("No leaderboard called \"" + boardText.trim() + "\".").withStyle(ChatFormatting.RED));
            return;
        }
        remove(name, board);
    }

    /** Stops {@code name} being your rival on one board, or on every board when {@code board} is null. */
    public static synchronized void remove(String name, String board) {
        String clean = name == null ? "" : name.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        Map<String, String> boards = rivals().get(lower);
        if (boards == null) {
            say(Component.literal(clean + " wasn't your rival.").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (board == null) {
            rivals().remove(lower);
            save();
            say(Component.literal(clean + " isn't your rival any more.").withStyle(ChatFormatting.GRAY));
            return;
        }
        String key = ALL.equals(board) ? ALL : board(board);
        String label = boards.getOrDefault(key, boardName(key));
        if (boards.remove(key) == null) {
            say(Component.literal(boards.containsKey(ALL)
                ? clean + " is your rival on all boards. /s2 rival remove " + clean + " to stop, then add them on the boards you want."
                : clean + " wasn't your rival on " + label + ".").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (boards.isEmpty()) rivals().remove(lower);
        save();
        say(Component.literal(clean + " isn't your rival on " + label + " any more.").withStyle(ChatFormatting.GRAY));
    }

    /**
     * The leaderboard screen's click on a player: their rival status on the board that's open. A rival on all boards
     * (from before per-board rivals) becomes a rival on just this one.
     */
    public static synchronized void toggle(String name, String board, String boardName) {
        String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        Map<String, String> boards = rivals().get(lower);
        String key = board(board);
        if (boards != null && boards.containsKey(ALL)) {
            boards.remove(ALL);
            boards.put(key, boardName == null || boardName.isBlank() ? boardName(key) : boardName);
            save();
            say(Component.literal(name + " is now your rival only on " + boards.get(key) + " (was all boards).").withStyle(ChatFormatting.GOLD));
        } else if (isRival(name, key)) {
            remove(name, key);
        } else {
            add(name, key, boardName);
        }
    }

    private static void list() {
        Map<String, Map<String, String>> copy;
        synchronized (Sky2MRivals.class) {
            copy = new LinkedHashMap<>(rivals());
        }
        if (copy.isEmpty()) {
            say(Component.literal("No rivals yet: /s2 rival add <name> <board>, or click a player in /s2 leaderboard.").withStyle(ChatFormatting.GOLD));
            return;
        }
        say(Component.literal("Your rivals:").withStyle(ChatFormatting.GOLD));
        for (Map.Entry<String, Map<String, String>> e : copy.entrySet()) {
            say(Component.literal(" ⚔ " + e.getKey() + ": ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.join(", ", e.getValue().values())).withStyle(ChatFormatting.GRAY)));
        }
    }

    /**
     * A board's standings arrived: for each rival on it, whether they've gone from behind you to ahead of you since
     * the last time this board came (only when both of you are on it). {@code board} is "type|id", or the board's
     * name when it can't be told (then only rivals on every board count).
     */
    static void onStandings(String board, String boardName, List<Sky2MLeaderboards.StatEntry> entries) {
        if (board == null || board.isEmpty() || board.equals("|") || !hasRivalsOn(board)) return;
        String self = Minecraft.getInstance().getUser().getName();
        int yourRank = -1;
        for (Sky2MLeaderboards.StatEntry e : entries) if (e.username().equalsIgnoreCase(self)) yourRank = e.rank();
        if (yourRank < 0) return;
        for (Sky2MLeaderboards.StatEntry e : entries) {
            if (!isRival(e.username(), board)) continue;
            boolean ahead = e.rank() < yourRank;
            Boolean before = AHEAD.put(board + "|" + e.username().toLowerCase(Locale.ROOT), ahead);
            if (before != null && !before && ahead) {
                say(Component.literal(e.username() + " passed you on " + (boardName.isBlank() ? board : boardName)
                    + " (#" + e.rank() + ", you're #" + yourRank + ")!").withStyle(ChatFormatting.GOLD));
            }
        }
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
        });
    }
}
