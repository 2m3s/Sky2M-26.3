package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dungeons > Puzzle HUD > Puzzle Bests: your fastest time for each puzzle, from walking into its room to its green
 * checkmark or a "PUZZLE SOLVED!" line naming you. A green checkmark says nothing of who solved it, so it only counts while
 * you're alone in the room on the map. Saved in config/sky2m/puzzle-bests.json; /s2 puzzles lists them. Not in the Dungeon Sim.
 */
public final class PuzzleBests {
    /** The saved bests in ms, by puzzle. Public for the game test. */
    public static final class Store {
        private final Map<String, Long> puzzles = new LinkedHashMap<>();

        public Long get(String puzzle) {
            return puzzles.get(puzzle);
        }

        public Map<String, Long> all() {
            return puzzles;
        }

        /** Keeps the time when it's the puzzle's first or beats its best. Returns whether it did. */
        public boolean record(String puzzle, long ms) {
            Long best = puzzles.get(puzzle);
            if (best != null && best <= ms) return false;
            puzzles.put(puzzle, ms);
            return true;
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject list = new JsonObject();
            puzzles.forEach(list::addProperty);
            root.add("puzzles", list);
            return new GsonBuilder().setPrettyPrinting().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad puzzle is skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject puzzles = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("puzzles");
            if (puzzles == null) return store;
            for (var entry : puzzles.entrySet()) {
                try {
                    long ms = entry.getValue().getAsLong();
                    if (ms >= 0) store.puzzles.put(entry.getKey(), ms);
                } catch (RuntimeException ignored) {
                    // Only this puzzle is lost.
                }
            }
            return store;
        }
    }

    private static final int CHECK_EVERY_TICKS = 2;
    /** When you first walked into each puzzle room this run. */
    private static final Map<Room, Long> ENTERED = new WeakHashMap<>();
    private static final Set<Room> FINISHED = Collections.newSetFromMap(new WeakHashMap<>());
    private static Room lastEntered;
    private static Store store = new Store();
    private static Path file;
    private static int ticks;

    private PuzzleBests() {}

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.dungeons.puzzleHud.puzzleBests && !com.epic60869.sky2m.sim.roomsim.SimState.isActive();
    }

    static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("puzzle-bests.json");
        store = SavedJson.load(file, Store::fromJson, store);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % CHECK_EVERY_TICKS != 0 || !enabled() || !Sky2MLocation.inDungeon()) return;
            Room room = DungeonManager.getCurrentRoom();
            if (room == null || room.getType() != Room.Type.PUZZLE) return;
            if (ENTERED.putIfAbsent(room, System.currentTimeMillis()) == null) lastEntered = room;
        });
        Sky2MChat.onChat(message -> {
            if (enabled() && Sky2MLocation.inDungeon()) onChat(message.text());
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("puzzles").executes(c -> show())));
            }
        });
    }

    private static void onChat(String text) {
        PuzzleHud.ChatLine line = PuzzleHud.parseChat(text);
        if (line == null || !line.player().equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) return;
        Room current = DungeonManager.getCurrentRoom();
        Room room = current != null && ENTERED.containsKey(current) ? current : lastEntered;
        if (room == null) return;
        if (line.solved()) finish(room);
        else FINISHED.add(room);
    }

    /** The map shows {@code room} green after showing it unfinished. */
    static void onGreen(Room room, Room current) {
        if (enabled() && room == current && room.getType() == Room.Type.PUZZLE && RoomBests.aloneInRoom()) finish(room);
    }

    private static void finish(Room room) {
        Long entered = ENTERED.get(room);
        if (entered == null || room.getName() == null || !FINISHED.add(room)) return;
        String puzzle = puzzleName(room.getName());
        long ms = System.currentTimeMillis() - entered;
        Long previous = store.get(puzzle);
        if (!store.record(puzzle, ms)) return;
        SavedJson.save(file, store.toJson());
        Sky2MAlerts.chat(Component.literal(puzzle + ": ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(RoomClears.timeText(ms)).withStyle(ChatFormatting.GREEN))
            .append(previous == null ? Component.literal(" (first best)").withStyle(ChatFormatting.GRAY)
                : Component.literal(" New best!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                    .append(Component.literal(" (was " + RoomClears.timeText(previous) + ")").withStyle(style -> style.withColor(ChatFormatting.GRAY).withBold(false)))));
    }

    /** The puzzle a room is, from its name in the room data; both Blaze rooms are one puzzle. Public for the game test. */
    public static String puzzleName(String room) {
        return switch (room) {
            case "boxes-room" -> "Boulder";
            case "creeper-room" -> "Creeper Beams";
            case "blaze-room-1-high", "blaze-room-1-low" -> "Higher Or Lower";
            case "ice-path" -> "Ice Fill";
            case "ice-silverfish-room" -> "Ice Path";
            case "teleport-pad-room" -> "Teleport Maze";
            case "three-chests" -> "Three Weirdos";
            case "tic-tac-toe-1" -> "Tic Tac Toe";
            case "trivia-room" -> "Quiz";
            case "water-puzzle" -> "Water Board";
            default -> room;
        };
    }

    private static int show() {
        if (store.all().isEmpty()) return DungeonRoutes.say("No puzzle bests yet.", ChatFormatting.YELLOW);
        StringBuilder text = new StringBuilder("Puzzle bests:");
        store.all().entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(e -> text.append('\n').append(e.getKey()).append(": ").append(RoomClears.timeText(e.getValue())));
        return DungeonRoutes.say(text.toString(), ChatFormatting.YELLOW);
    }
}
