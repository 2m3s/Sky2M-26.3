package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.dungeons.mapstyle.MapView;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sim.livemap.MapViewSource;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dungeons > Secrets > Room Personal Bests: your fastest time for each room, and the time per secret.
 *
 * <p>The idea is SecretRoutes' personal bests (https://github.com/yourboykyle/SecretRoutes, GPL-3.0), but the clock is
 * {@link RoomClears}' one, first secret found in the room to the green checkmark, not SecretRoutes' room entry to the
 * route's last secret. So a best works with or without a route, doesn't care which route you took, and running through on a blood
 * rush doesn't count. The walk to the first secret isn't in it. {@link RoomClears} says the time, and a new best goes on
 * that same line.
 *
 * <p>A time only counts when you were there for all of the room: nobody had got one of its secrets before the clock started
 * and (unless Solo Only is off) no teammate's head was in the room on the map when a secret came up or it went green.
 * Dungeon Sim runs don't count.
 */
public final class RoomBests {
    /** One room's best. {@code splits} are ms from the first secret to each secret, so the first is 0. */
    public record Best(long ms, int secrets, long at, List<Long> splits) {}

    /** What a timed room came to. {@code previous} is the best before it, null when there wasn't one. */
    public record Outcome(long ms, Long previous, boolean counted, boolean newBest, List<Long> splits) {}

    /** The saved bests, by {@link DungeonRoutes#normalize room name}. Public for the game test. */
    public static final class Store {
        private final Map<String, Best> rooms = new LinkedHashMap<>();

        public Best get(String room) {
            return rooms.get(room);
        }

        public Map<String, Best> all() {
            return rooms;
        }

        public boolean remove(String room) {
            return rooms.remove(room) != null;
        }

        /** Keeps the time when it's the room's first or beats its best. Returns whether it did. */
        public boolean record(String room, long ms, int secrets, long at, List<Long> splits) {
            Best best = rooms.get(room);
            if (best != null && best.ms() <= ms) return false;
            rooms.put(room, new Best(ms, secrets, at, List.copyOf(splits)));
            return true;
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject list = new JsonObject();
            rooms.forEach((name, best) -> {
                JsonObject room = new JsonObject();
                room.addProperty("ms", best.ms());
                room.addProperty("secrets", best.secrets());
                room.addProperty("at", best.at());
                JsonArray splits = new JsonArray();
                best.splits().forEach(splits::add);
                room.add("splits", splits);
                list.add(name, room);
            });
            root.add("rooms", list);
            return new GsonBuilder().setPrettyPrinting().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad room is skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject rooms = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("rooms");
            if (rooms == null) return store;
            for (var entry : rooms.entrySet()) {
                try {
                    JsonObject room = entry.getValue().getAsJsonObject();
                    List<Long> splits = new ArrayList<>();
                    if (room.has("splits")) for (JsonElement split : room.getAsJsonArray("splits")) splits.add(split.getAsLong());
                    long ms = room.get("ms").getAsLong();
                    if (ms < 0) continue;
                    store.rooms.put(entry.getKey(), new Best(ms, room.has("secrets") ? room.get("secrets").getAsInt() : 0,
                        room.has("at") ? room.get("at").getAsLong() : 0, List.copyOf(splits)));
                } catch (RuntimeException ignored) {
                    // Only this room is lost.
                }
            }
            return store;
        }
    }

    /** One go at a room, from the first secret. */
    private static final class Attempt {
        final long startedAt;
        /** Secrets the room already had when the clock started: above 0, the time isn't for the whole room. */
        final int foundBefore;
        final List<Long> splits = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        boolean stacked;

        Attempt(long startedAt, int foundBefore) {
            this.startedAt = startedAt;
            this.foundBefore = foundBefore;
        }
    }

    private static final Map<Room, Attempt> ATTEMPTS = new WeakHashMap<>();
    private static Store store = new Store();
    private static Path file;

    private RoomBests() {}

    static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("room-bests.json");
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ATTEMPTS.clear());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("route")
                        .then(ClientCommands.literal("best")
                            .executes(c -> showRoom())
                            .then(ClientCommands.literal("reset").executes(c -> resetRoom())))
                        .then(ClientCommands.literal("bests").executes(c -> showAll()))));
            }
        });
    }

    /** A file that can't be read is copied aside first, so the next save can't overwrite what's in it. */
    private static void load() {
        try {
            if (Files.exists(file)) store = Store.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load room bests: " + e.getMessage());
            try {
                Files.copy(file, file.resolveSibling("room-bests-unreadable-" + System.currentTimeMillis() + ".json"));
            } catch (Exception copyFailed) {
                System.err.println("[Sky2M] Could not copy room bests aside: " + copyFailed.getMessage());
            }
        }
    }

    private static void save() {
        try {
            DungeonRoutes.writeSafely(file, store.toJson());
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save room bests: " + e.getMessage());
        }
    }

    /**
     * A secret came up in the room you're in. {@code startedAt} is {@link RoomClears}' clock. {@code secretIndex} is which
     * secret the waypoints saw, or -1 when this is Hypixel's action bar count, which also fills in secrets the waypoints missed.
     */
    static void onSecret(Room room, long startedAt, int secretIndex) {
        int found = Math.max(0, room.getFoundSecretCount());
        Attempt attempt = ATTEMPTS.get(room);
        if (attempt == null || attempt.startedAt != startedAt) {
            // The waypoints see a secret before the count has it; the count already includes the one that started the clock.
            attempt = new Attempt(startedAt, secretIndex >= 0 ? found : Math.max(0, found - 1));
            ATTEMPTS.put(room, attempt);
        }
        long now = Math.max(0, System.currentTimeMillis() - startedAt);
        if (secretIndex >= 0) {
            if (attempt.seen.add(secretIndex)) attempt.splits.add(now);
        } else {
            while (attempt.splits.size() < found - attempt.foundBefore) attempt.splits.add(now);
        }
        attempt.stacked |= !aloneInRoom();
    }

    /** {@code room} went green {@code ms} after its first secret. Returns null when bests are off. */
    static Outcome onCleared(Room room, int secrets, long ms) {
        Attempt attempt = ATTEMPTS.remove(room);
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.dungeons.secrets.roomBests || room.getName() == null) return null;
        String key = DungeonRoutes.normalize(room.getName());
        Best best = store.get(key);
        Long previous = best == null ? null : best.ms();
        List<Long> splits = attempt == null ? List.of() : List.copyOf(attempt.splits);
        boolean solo = !config.dungeons.secrets.roomBestsSoloOnly || (attempt != null && !attempt.stacked && aloneInRoom());
        boolean counts = attempt != null && attempt.foundBefore == 0 && solo && !com.epic60869.sky2m.sim.roomsim.SimState.isActive();
        if (!counts) return new Outcome(ms, previous, false, false, splits);
        boolean newBest = store.record(key, ms, secrets, System.currentTimeMillis(), splits);
        if (newBest) save();
        return new Outcome(ms, previous, true, newBest, splits);
    }

    /** Whether no other head is on your room on the map. True when the map can't tell. */
    private static boolean aloneInRoom() {
        MapView view = MapViewSource.current();
        if (!view.valid()) return true;
        for (MapView.Head head : view.heads()) {
            if (!head.self()) continue;
            for (MapView.Room mapRoom : view.rooms()) {
                if (!RoomClears.inRoom(view, mapRoom, head.x(), head.y())) continue;
                for (MapView.Head other : view.heads()) {
                    if (!other.self() && RoomClears.inRoom(view, mapRoom, other.x(), other.y())) return false;
                }
                return true;
            }
        }
        return true;
    }

    /** What goes after the time on the room's line: nothing, " (best 40.2s)", " (first best)" or " New best! (was 45.1s)". */
    public static Component suffix(Outcome outcome) {
        if (outcome == null) return Component.empty();
        if (outcome.newBest()) {
            if (outcome.previous() == null) return Component.literal(" (first best)").withStyle(ChatFormatting.GRAY);
            return Component.literal(" New best!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(Component.literal(" (was " + RoomClears.timeText(outcome.previous()) + ")").withStyle(style -> style.withColor(ChatFormatting.GRAY).withBold(false)));
        }
        return outcome.previous() == null ? Component.empty()
            : Component.literal(" (best " + RoomClears.timeText(outcome.previous()) + ")").withStyle(ChatFormatting.GRAY);
    }

    /** Hovering the time shows each secret after the first: how long it took from the one before. Empty with too few secrets seen. */
    public static String splitsText(List<Long> splits) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i < splits.size(); i++) {
            if (i > 1) text.append('\n');
            text.append("Secret ").append(i + 1).append(": ").append(RoomClears.timeText(Math.max(0, splits.get(i) - splits.get(i - 1))));
        }
        return text.toString();
    }

    static HoverEvent splitsHover(List<Long> splits) {
        String text = splitsText(splits);
        return text.isEmpty() ? null : new HoverEvent.ShowText(Component.literal(text));
    }

    private static int showRoom() {
        Room room = DungeonRoutes.room();
        if (room == null) return DungeonRoutes.say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        Best best = store.get(DungeonRoutes.normalize(room.getName()));
        if (best == null) return DungeonRoutes.say("No best for " + room.getName() + " yet.", ChatFormatting.YELLOW);
        String splits = splitsText(best.splits());
        return DungeonRoutes.say(room.getName() + ": best " + RoomClears.timeText(best.ms()) + " for " + best.secrets() + " secrets."
            + (splits.isEmpty() ? "" : "\n" + splits), ChatFormatting.YELLOW);
    }

    private static int resetRoom() {
        Room room = DungeonRoutes.room();
        if (room == null) return DungeonRoutes.say("Stand in the room whose best you want to reset.", ChatFormatting.RED);
        if (!store.remove(DungeonRoutes.normalize(room.getName()))) return DungeonRoutes.say("No best for " + room.getName() + " yet.", ChatFormatting.RED);
        save();
        return DungeonRoutes.say("Reset your best for " + room.getName() + ".", ChatFormatting.YELLOW);
    }

    private static int showAll() {
        if (store.all().isEmpty()) return DungeonRoutes.say("No room bests yet.", ChatFormatting.YELLOW);
        StringBuilder text = new StringBuilder("Room bests:");
        store.all().entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(e -> text.append('\n').append(e.getKey()).append(": ").append(RoomClears.timeText(e.getValue().ms())));
        return DungeonRoutes.say(text.toString(), ChatFormatting.YELLOW);
    }
}
