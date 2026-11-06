package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.dungeons.mapstyle.MapView;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.epic60869.sky2m.sim.livemap.MapViewSource;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.phys.Vec3;

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
 * {@link RoomClears}' one, first secret found in the room to the last, not SecretRoutes' room entry to the
 * route's last secret. So a best works with or without a route, doesn't care which route you took, and running through on a blood
 * rush doesn't count. The walk to the first secret isn't in it, except in a room with one secret, which is timed from walking
 * in. {@link RoomClears} says the time, and a new best goes on that same line.
 *
 * <p>A time only counts when you were there for all of the room: nobody had got one of its secrets before the clock started
 * and (unless Solo Only is off) no teammate's head was in the room on the map when a secret came up or it went green.
 * Dungeon Sim runs don't count.
 */
public final class RoomBests {
    /**
     * One room's best. {@code splits} are ms from the first secret to each secret, so the first is 0. {@code path} is where
     * you walked, relative to the room so it fits any rotation; empty for a best saved before paths were.
     */
    public record Best(long ms, int secrets, long at, List<Long> splits, List<Vec3> path) {}

    /** A counted room time this run, with the room's best before it (null without one). */
    public record RunRoom(String name, long ms, Long previous) {}

    /** What a timed room came to. {@code previous} is the best before it, null when there wasn't one. */
    public record Outcome(long ms, Long previous, boolean counted, boolean newBest, List<Long> splits) {}

    /** The saved bests, by {@link DungeonRoutes#normalize room name}. Public for the game test. */
    public static final class Store {
        private static final int RECENT_KEPT = 5;
        private final Map<String, Best> rooms = new LinkedHashMap<>();
        /** The last few counted times per room, so {@link #practiceOrder} can see where you usually lose time. */
        private final Map<String, List<Long>> recent = new LinkedHashMap<>();

        public void addRecent(String room, long ms) {
            List<Long> times = recent.computeIfAbsent(room, r -> new ArrayList<>());
            times.add(ms);
            while (times.size() > RECENT_KEPT) times.removeFirst();
        }

        /** The average of the room's recent times, or -1 without any. */
        public long recentAverage(String room) {
            List<Long> times = recent.get(room);
            if (times == null || times.isEmpty()) return -1;
            long total = 0;
            for (long time : times) total += time;
            return total / times.size();
        }

        /** How far the room's recent times are above its best, or 0 without any. */
        private long gap(String room) {
            long average = recentAverage(room);
            return average < 0 ? 0 : Math.max(0, average - rooms.get(room).ms());
        }

        /** Rooms to practise first: the biggest gap between recent times and the best, then the slowest per secret. */
        public List<String> practiceOrder() {
            return rooms.keySet().stream().sorted(Comparator.comparingLong((String room) -> gap(room)).reversed()
                .thenComparing(Comparator.comparingDouble((String room) -> {
                    Best best = rooms.get(room);
                    return (double) best.ms() / Math.max(1, best.secrets());
                }).reversed())).toList();
        }

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
            return record(room, ms, secrets, at, splits, List.of());
        }

        /** The same with the path walked. */
        public boolean record(String room, long ms, int secrets, long at, List<Long> splits, List<Vec3> path) {
            Best best = rooms.get(room);
            if (best != null && best.ms() <= ms) return false;
            rooms.put(room, new Best(ms, secrets, at, List.copyOf(splits), List.copyOf(path)));
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
                if (!best.path().isEmpty()) room.addProperty("path", encodePath(best.path()));
                list.add(name, room);
            });
            root.add("rooms", list);
            JsonObject times = new JsonObject();
            recent.forEach((name, values) -> {
                JsonArray array = new JsonArray();
                values.forEach(array::add);
                times.add(name, array);
            });
            root.add("recent", times);
            return new GsonBuilder().setPrettyPrinting().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad room is skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject rooms = root.getAsJsonObject("rooms");
            if (rooms == null) return store;
            if (root.has("recent")) {
                for (var entry : root.getAsJsonObject("recent").entrySet()) {
                    try {
                        for (JsonElement time : entry.getValue().getAsJsonArray()) store.addRecent(entry.getKey(), time.getAsLong());
                    } catch (RuntimeException ignored) {
                        // Only this room's recent times are lost.
                    }
                }
            }
            for (var entry : rooms.entrySet()) {
                try {
                    JsonObject room = entry.getValue().getAsJsonObject();
                    List<Long> splits = new ArrayList<>();
                    if (room.has("splits")) for (JsonElement split : room.getAsJsonArray("splits")) splits.add(split.getAsLong());
                    long ms = room.get("ms").getAsLong();
                    if (ms < 0) continue;
                    store.rooms.put(entry.getKey(), new Best(ms, room.has("secrets") ? room.get("secrets").getAsInt() : 0,
                        room.has("at") ? room.get("at").getAsLong() : 0, List.copyOf(splits),
                        room.has("path") ? decodePath(room.get("path").getAsString()) : List.of()));
                } catch (RuntimeException ignored) {
                    // Only this room is lost.
                }
            }
            return store;
        }
    }

    /** One go at a room, from the first secret. Public for the game test. */
    public static final class Attempt {
        final long startedAt;
        /** Secrets the room already had when the clock started: above 0, the time isn't for the whole room. */
        final int foundBefore;
        final List<Long> splits = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        /** Secrets the action bar counted that the waypoints haven't seen yet: when they do, it's the same secret. */
        private int countOnly;
        final List<Vec3> path = new ArrayList<>();
        /** Every secret is in: the time has stopped, so the path should too. */
        boolean pathDone;
        boolean stacked;

        public Attempt(long startedAt, int foundBefore) {
            this.startedAt = startedAt;
            this.foundBefore = foundBefore;
        }

        /** The waypoints saw this secret. The two can come in either order: a bat's death often comes after the action bar has it. */
        public void sawSecret(int index, long now, int max) {
            if (!seen.add(index)) return;
            if (countOnly > 0) countOnly--;
            else if (max <= 0 || foundBefore + splits.size() < max) splits.add(now);
        }

        /** The action bar says the room has this many found. */
        public void countSays(int found, long now, int max) {
            int target = (max > 0 ? Math.min(found, max) : found) - foundBefore;
            while (splits.size() < target) {
                splits.add(now);
                countOnly++;
            }
        }

        public int secretsFound() {
            return foundBefore + splits.size();
        }
    }

    private static final int PRACTICE_LISTED = 10;
    /** A few samples a second is plenty for a line, and a long room's path is thinned to stay under the cap. */
    private static final int PATH_SAMPLE_TICKS = 5;
    private static final int PATH_MAX_POINTS = 200;
    private static final double PATH_MIN_STEP = 0.25;
    private static final double GHOST_LIFT = 0.1;
    private static final float[] GHOST_COLOUR = {0.4f, 0.8f, 1f};
    private static final List<RunRoom> RUN_ROOMS = new ArrayList<>();
    private static final Map<Room, Attempt> ATTEMPTS = new WeakHashMap<>();
    private static Store store = new Store();
    private static Path file;
    /** Live Room Splits: the room being timed, and what the HUD says about it. */
    private static Room liveRoom;
    private static String liveName = "";
    private static int liveFound, liveTotal;
    private static Long liveDelta;
    private static Long liveBest;
    /** The ghost's world points, worked out once for the room and best it was drawn for. */
    private static Room ghostRoom;
    private static Best ghostBest;
    private static Vec3[] ghostPoints;
    private static int ticks;

    private RoomBests() {}

    private static boolean liveEnabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.dungeons.secrets.roomLiveSplits;
    }

    /** How far behind (or, below 0, ahead of) the best this secret is, or null if there's nothing to compare it with. Public for the game test. */
    public static Long splitDelta(List<Long> bestSplits, int index, long now) {
        return index <= 0 || bestSplits == null || index >= bestSplits.size() ? null : now - bestSplits.get(index);
    }

    /** "§a-0.8s" ahead, "§c+1.2s" behind. Public for the game test. */
    public static String deltaText(long ms) {
        return (ms <= 0 ? "§a-" : "§c+") + String.format(java.util.Locale.ROOT, "%.1fs", Math.abs(ms) / 1000.0);
    }

    private static List<Component> liveLines(String name, int found, int total, Long delta, Long best) {
        String count = "§6" + name + " §7" + found + (total > 0 ? "/" + total : "");
        String time = best == null ? "§7No best yet" : "§7Best §f" + RoomClears.timeText(best);
        return List.of(Component.literal(delta == null ? count : count + " " + deltaText(delta)), Component.literal(time));
    }

    static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("room-bests.json");
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            ATTEMPTS.clear();
            liveRoom = null;
        });
        Sky2MHuds.setting("room_splits", RoomBests::liveEnabled);
        Sky2MHuds.register("room_splits", "Live Room Splits",
            () -> liveEnabled() && liveRoom != null && liveRoom == DungeonManager.getCurrentRoom(),
            () -> liveLines(liveName, liveFound, liveTotal, liveDelta, liveBest),
            liveLines("Raccoon", 2, 4, -800L, 6_500L),
            20, 190);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % PATH_SAMPLE_TICKS != 0) return;
            Room room = DungeonManager.getCurrentRoom();
            Attempt attempt = room == null ? null : ATTEMPTS.get(room);
            if (attempt != null && !attempt.pathDone) samplePath(room, attempt);
        });
        Sky2MWorldRender.register(RoomBests::renderGhost);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("route")
                        .then(ClientCommands.literal("best")
                            .executes(c -> showRoom())
                            .then(ClientCommands.literal("reset").executes(c -> resetRoom())))
                        .then(ClientCommands.literal("bests").executes(c -> showAll())))
                    .then(ClientCommands.literal("practice")
                        .executes(c -> showPractice())
                        .then(ClientCommands.argument("room", StringArgumentType.greedyString())
                            .executes(c -> practise(StringArgumentType.getString(c, "room"))))));
            }
        });
    }

    /** Adds a point unless you've barely moved, and thins the path once it's over the cap, keeping both ends. Public for the game test. */
    public static void addPathPoint(List<Vec3> path, Vec3 point, int cap) {
        if (!path.isEmpty() && path.getLast().distanceToSqr(point) < PATH_MIN_STEP * PATH_MIN_STEP) return;
        path.add(point);
        if (path.size() <= cap) return;
        List<Vec3> thinned = new ArrayList<>();
        for (int i = 0; i < path.size(); i += 2) thinned.add(path.get(i));
        if (path.size() % 2 == 0) thinned.add(path.getLast());
        path.clear();
        path.addAll(thinned);
    }

    /** "x,y,z x,y,z", to a tenth of a block: a line per number in the pretty-printed file would make it far bigger. Public for the game test. */
    public static String encodePath(List<Vec3> path) {
        StringBuilder text = new StringBuilder();
        for (Vec3 point : path) {
            if (!text.isEmpty()) text.append(' ');
            text.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", point.x, point.y, point.z));
        }
        return text.toString();
    }

    /** Back from {@link #encodePath}. A bad point is skipped, as the best itself is still good. Public for the game test. */
    public static List<Vec3> decodePath(String text) {
        List<Vec3> path = new ArrayList<>();
        for (String point : text.trim().split(" ")) {
            String[] parts = point.split(",");
            if (parts.length != 3) continue;
            try {
                path.add(new Vec3(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            } catch (NumberFormatException ignored) {
                // Only this point is lost.
            }
        }
        return List.copyOf(path);
    }

    private static void samplePath(Room room, Attempt attempt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !room.isMatched()) return;
        addPathPoint(attempt.path, room.actualToRelative(mc.player.position()), PATH_MAX_POINTS);
    }

    /** Room Ghost: the path of the room's best, as a thin line that walls hide. */
    private static void renderGhost(PrimitiveCollector collector) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.dungeons.secrets.roomGhost || !Sky2MLocation.inDungeon() || DungeonManager.isInBoss()) return;
        Room room = DungeonManager.getCurrentRoom();
        if (room == null || !room.isMatched() || room.getName() == null) return;
        Best best = store.get(DungeonRoutes.normalize(room.getName()));
        if (best == null || best.path().size() < 2) return;
        if (room != ghostRoom || best != ghostBest) {
            ghostRoom = room;
            ghostBest = best;
            ghostPoints = new Vec3[best.path().size()];
            for (int i = 0; i < ghostPoints.length; i++) ghostPoints[i] = room.relativeToActual(best.path().get(i)).add(0, GHOST_LIFT, 0);
        }
        collector.submitLinesFromPoints(ghostPoints, GHOST_COLOUR, 0.7f, 1.5f, false);
    }

    /** A run started: forget the last one's room times. */
    static void startRun() {
        RUN_ROOMS.clear();
    }

    /** This run's counted room times, for the Run Review. */
    static List<RunRoom> runRooms() {
        return List.copyOf(RUN_ROOMS);
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
        int before = attempt.splits.size();
        int max = room.getMaxSecretCount();
        if (secretIndex >= 0) attempt.sawSecret(secretIndex, now, max);
        else attempt.countSays(found, now, max);
        // The action bar comes about once a second: only a new secret should move anything on.
        if (attempt.splits.size() == before) return;
        attempt.stacked |= !aloneInRoom();
        samplePath(room, attempt);
        if (max > 0 && attempt.secretsFound() >= max) attempt.pathDone = true;
        if (room.getName() == null) return;
        Best best = store.get(DungeonRoutes.normalize(room.getName()));
        liveRoom = room;
        liveName = RoomClears.displayName(room);
        liveFound = attempt.secretsFound();
        liveTotal = max;
        liveDelta = best == null ? null : splitDelta(best.splits(), attempt.splits.size() - 1, now);
        liveBest = best == null ? null : best.ms();
    }

    /** When the last secret came up, in ms from the first, if all of {@code room}'s came up while you were in it, else null. */
    static Long lastSecretAt(Room room, int secrets) {
        Attempt attempt = ATTEMPTS.get(room);
        return attempt == null ? null : lastSecretAt(attempt.foundBefore, attempt.splits, secrets);
    }

    /** The same from what was found before the clock started and the times since. Public for the game test. */
    public static Long lastSecretAt(int foundBefore, List<Long> splits, int secrets) {
        return splits.isEmpty() || foundBefore + splits.size() < secrets ? null : splits.getLast();
    }

    /** {@code room} went green {@code ms} after its first secret; {@code inRoom}: you're still in it. Returns null when bests are off. */
    static Outcome onCleared(Room room, int secrets, long ms, boolean inRoom) {
        Attempt attempt = ATTEMPTS.remove(room);
        if (liveRoom == room) liveRoom = null;
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.dungeons.secrets.roomBests || room.getName() == null) return null;
        String key = DungeonRoutes.normalize(room.getName());
        Best best = store.get(key);
        Long previous = best == null ? null : best.ms();
        List<Long> splits = attempt == null ? List.of() : List.copyOf(attempt.splits);
        boolean solo = !config.dungeons.secrets.roomBestsSoloOnly || (attempt != null && !attempt.stacked && (!inRoom || aloneInRoom()));
        boolean counts = attempt != null && attempt.foundBefore == 0 && solo && !com.epic60869.sky2m.sim.roomsim.SimState.isActive();
        if (!counts) return new Outcome(ms, previous, false, false, splits);
        boolean newBest = store.record(key, ms, secrets, System.currentTimeMillis(), splits, attempt.path);
        store.addRecent(key, ms);
        RUN_ROOMS.add(new RunRoom(inRoom ? RoomClears.displayName(room) : room.getName(), ms, previous));
        save();
        return new Outcome(ms, previous, true, newBest, splits);
    }

    /** Whether no other head is on your room on the map. True when the map can't tell. */
    static boolean aloneInRoom() {
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

    /** The Dungeon Sim's room for a saved best, or null if the sim doesn't have it. */
    private static String simRoom(String key) {
        for (String name : com.epic60869.sky2m.sim.roomsim.RoomLibrary.names()) {
            if (DungeonRoutes.normalize(name).equals(key)) return name;
        }
        return null;
    }

    /** /s2 practice: the rooms worth practising, each with a link to load it in the Dungeon Sim. */
    private static int showPractice() {
        if (store.all().isEmpty()) return DungeonRoutes.say("No room bests yet: clear a room on your own first.", ChatFormatting.YELLOW);
        var chat = Minecraft.getInstance().gui.hud.getChat();
        chat.addClientSystemMessage(Compat.PREFIX.get().append(Component.literal("Rooms to practise:").withStyle(ChatFormatting.GOLD)));
        store.practiceOrder().stream().limit(PRACTICE_LISTED).forEach(key -> {
            Best best = store.get(key);
            long average = store.recentAverage(key);
            var line = Component.literal(" " + key + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("best " + RoomClears.timeText(best.ms())).withStyle(ChatFormatting.GREEN));
            if (average > best.ms()) line.append(Component.literal(", recently " + RoomClears.timeText(average)).withStyle(ChatFormatting.RED));
            if (simRoom(key) != null) {
                line.append(Component.literal(" [Practice]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)
                    .withClickEvent(new ClickEvent.RunCommand("/s2 practice " + key))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Load this room in the Dungeon Sim")))));
            }
            chat.addClientSystemMessage(line);
        });
        return 1;
    }

    /** /s2 practice <room>: loads it in the Dungeon Sim. That can't happen while on a server, as the sim is its own world. */
    private static int practise(String typed) {
        String room = simRoom(DungeonRoutes.normalize(typed));
        if (room == null) return DungeonRoutes.say("The Dungeon Sim has no room called " + typed + ".", ChatFormatting.RED);
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasSingleplayerServer()) return DungeonRoutes.say("The Dungeon Sim is its own world: open it from the title screen, then run /s2 practice " + typed + " there.", ChatFormatting.YELLOW);
        if (!com.epic60869.sky2m.sim.Sky2MSim.enabled()) return DungeonRoutes.say("Turn on Dungeons > Dungeon Sim first.", ChatFormatting.YELLOW);
        mc.execute(() -> com.epic60869.sky2m.sim.roomsim.SimBuilder.buildSingleRoom(mc, room));
        return 1;
    }

    private static int showAll() {
        if (store.all().isEmpty()) return DungeonRoutes.say("No room bests yet.", ChatFormatting.YELLOW);
        StringBuilder text = new StringBuilder("Room bests:");
        store.all().entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey))
            .forEach(e -> text.append('\n').append(e.getKey()).append(": ").append(RoomClears.timeText(e.getValue().ms())));
        return DungeonRoutes.say(text.toString(), ChatFormatting.YELLOW);
    }
}
