package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Route;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Step;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Type;
import com.epic60869.sky2m.features.dungeons.DungeonRoutes.Waypoint;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dungeons > Secrets and Routes > Adaptive Routes, off by default: learns from the clears {@link RoomBests} counts (the
 * whole room, on your own, outside the sim). The secrets you got are matched, in order, against the room's routes: a match
 * adds the time to that route, and a new order is saved as a learned route, up to {@link #MAX_LEARNED} a room. The room
 * then shows the route with your lowest usual time, plus an estimate of the walk to where it starts, as {@link RoomBests}'
 * clock only begins at the first secret. Saved in {@code config/sky2m/adaptive-routes.json}.
 *
 * <p>A learned route has what's seen without recording on purpose: secrets, levers and the line you walked. A clear with a
 * secret only the action bar counted (no position) can't be matched, so it's left out.
 */
public final class AdaptiveRoutes {
    private static final int RECENT_KEPT = 8;
    private static final int MAX_LEARNED = 3;
    /** Learned routes are "Melon-3:101", which {@link DungeonRoutes#normalize} still reads as that room's. */
    private static final int LEARNED_KEY_START = 101;
    private static final int MAX_LINE_POINTS = 600;
    private static final double LINE_STEP_SQ = 4;
    /** For the walk to a route's start: about Speed 350, with no time for doors or mobs. */
    private static final double WALK_BLOCKS_PER_SECOND = 15;
    /** Another route must be this much faster to take over, so a close call doesn't flicker. */
    private static final long SWITCH_MARGIN_MS = 1000;
    private static final String HUD_ID = "adaptive_route";

    private static final Map<Room, Clear> CLEARS = new WeakHashMap<>();
    private static Store store = new Store();
    private static Path file;

    private AdaptiveRoutes() {}

    /** How close a secret you got must be to a route's: a click is on the block, an item or bat ends up near it. */
    private static int toleranceSq(Type type) {
        return switch (type) {
            case BAT -> 100;
            case ITEM -> 25;
            default -> 2;
        };
    }

    private static List<Waypoint> secrets(List<Step> steps) {
        List<Waypoint> out = new ArrayList<>();
        for (Step step : steps) for (Waypoint w : step.waypoints()) if (w.type().secret()) out.add(w);
        return out;
    }

    /** What you've done in a room so far, in room coordinates. Public for the game test. */
    public static final class Clear {
        private final List<Step> steps = new ArrayList<>(List.of(new Step()));
        private BlockPos lastPoint;
        private int points;

        public void addPoint(BlockPos pos) {
            if (points >= MAX_LINE_POINTS || (lastPoint != null && pos.distSqr(lastPoint) <= LINE_STEP_SQ)) return;
            steps.getLast().line().add(pos);
            lastPoint = pos;
            points++;
        }

        /** Ends the step on this secret, unless you've already got it (a chest opened again). */
        public void addSecret(BlockPos pos, Type type) {
            for (Waypoint w : secrets(steps)) if (w.pos().distSqr(pos) <= toleranceSq(type)) return;
            steps.getLast().waypoints().add(new Waypoint(pos, type, null));
            steps.add(new Step());
        }

        public void addLever(BlockPos pos) {
            for (Waypoint w : steps.getLast().waypoints()) if (w.pos().equals(pos)) return;
            steps.getLast().waypoints().add(new Waypoint(pos, Type.LEVER, null));
        }

        /** "That chest is locked!": the last chest wasn't a secret after all, so what was done since joins its step. */
        public void undoLockedChest() {
            if (steps.size() < 2) return;
            Step previous = steps.get(steps.size() - 2);
            if (previous.waypoints().isEmpty() || previous.waypoints().getLast().type() != Type.CHEST) return;
            previous.waypoints().removeLast();
            Step current = steps.removeLast();
            previous.waypoints().addAll(current.waypoints());
            previous.line().addAll(current.line());
        }

        /** The steps ending on a secret. A lever after the last secret did nothing for the route. */
        public List<Step> finished() {
            List<Step> out = new ArrayList<>(steps);
            if (!out.isEmpty() && secrets(List.of(out.getLast())).isEmpty()) out.removeLast();
            return out;
        }
    }

    /** Your numbers and learned routes. Public for the game test. */
    public static final class Store {
        private final Map<String, Map<String, List<Long>>> times = new LinkedHashMap<>();
        private final Map<String, List<Step>> learned = new LinkedHashMap<>();

        public void addTime(String room, String routeKey, long ms) {
            List<Long> list = times.computeIfAbsent(room, r -> new LinkedHashMap<>()).computeIfAbsent(routeKey, k -> new ArrayList<>());
            list.add(ms);
            while (list.size() > RECENT_KEPT) list.removeFirst();
        }

        public int clears(String room, String routeKey) {
            Map<String, List<Long>> routes = times.get(room);
            List<Long> list = routes == null ? null : routes.get(routeKey);
            return list == null ? 0 : list.size();
        }

        /** Your usual time on the route, or -1 with no clears: the median, so one death or lucky run doesn't decide it. */
        public long usual(String room, String routeKey) {
            Map<String, List<Long>> routes = times.get(room);
            List<Long> list = routes == null ? null : routes.get(routeKey);
            if (list == null || list.isEmpty()) return -1;
            List<Long> sorted = list.stream().sorted().toList();
            int mid = sorted.size() / 2;
            return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }

        public List<Route> learnedRoutes(String room) {
            List<Route> out = new ArrayList<>();
            learned.forEach((key, steps) -> {
                if (DungeonRoutes.normalize(key).equals(room)) out.add(new Route(key, steps, false));
            });
            return out;
        }

        public boolean isLearned(String routeKey) {
            return learned.containsKey(routeKey);
        }

        /**
         * A counted clear. Returns the route it went to, or null when it can't be used: too few secrets to have an order, or
         * one had no position. A learned route that doesn't fit in the room's limit, as the slowest, is dropped.
         */
        public String recordClear(String roomName, List<Route> routes, List<Step> clear, long ms, int roomSecrets) {
            List<Waypoint> found = secrets(clear);
            if (roomSecrets < 2 || found.size() < roomSecrets) return null;
            String room = DungeonRoutes.normalize(roomName);
            for (Route route : routes) {
                if (followed(route.steps(), found)) {
                    addTime(room, route.key(), ms);
                    return route.key();
                }
            }
            int n = LEARNED_KEY_START;
            while (learned.containsKey(roomName + ":" + n)) n++;
            String key = roomName + ":" + n;
            learned.put(key, clear);
            addTime(room, key, ms);
            List<Route> own = learnedRoutes(room);
            if (own.size() > MAX_LEARNED) {
                Route slowest = own.getFirst();
                for (Route route : own) if (usual(room, route.key()) > usual(room, slowest.key())) slowest = route;
                learned.remove(slowest.key());
                times.get(room).remove(slowest.key());
                return slowest.key().equals(key) ? null : key;
            }
            return key;
        }

        /**
         * The route to show: the lowest usual time plus the walk to its start, staying on {@code current} unless another
         * beats it by {@link #SWITCH_MARGIN_MS}. -1 with no clears on any of them. {@code walks} is the blocks to each start.
         */
        public int pick(String room, List<Route> routes, List<Double> walks, int current) {
            int best = -1;
            double bestScore = Double.MAX_VALUE, currentScore = -1;
            for (int i = 0; i < routes.size(); i++) {
                long usual = usual(room, routes.get(i).key());
                if (usual < 0) continue;
                double score = usual + walks.get(i) / WALK_BLOCKS_PER_SECOND * 1000;
                if (i == current) currentScore = score;
                if (score < bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            return currentScore >= 0 && currentScore - bestScore <= SWITCH_MARGIN_MS ? current : best;
        }

        public boolean reset(String room) {
            boolean any = times.remove(room) != null;
            any |= learned.keySet().removeIf(key -> DungeonRoutes.normalize(key).equals(room));
            return any;
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject allTimes = new JsonObject();
            times.forEach((room, routes) -> {
                JsonObject roomJson = new JsonObject();
                routes.forEach((key, list) -> {
                    JsonArray array = new JsonArray();
                    list.forEach(array::add);
                    roomJson.add(key, array);
                });
                allTimes.add(room, roomJson);
            });
            root.add("times", allTimes);
            root.add("learned", JsonParser.parseString(DungeonRoutes.serialize(learned, "Sky2M learned routes")));
            return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad room's times are skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("times")) {
                for (var room : root.getAsJsonObject("times").entrySet()) {
                    try {
                        for (var route : room.getValue().getAsJsonObject().entrySet()) {
                            for (JsonElement time : route.getValue().getAsJsonArray()) store.addTime(room.getKey(), route.getKey(), time.getAsLong());
                        }
                    } catch (RuntimeException ignored) {
                        // Only this room's times are lost.
                    }
                }
            }
            if (root.has("learned")) store.learned.putAll(DungeonRoutes.parse(root.get("learned").toString()));
            return store;
        }
    }

    /**
     * Whether you got a route's secrets in its order: all of them, in sequence, with at most one extra along the way (a route
     * can leave one out, or a chest that wasn't a secret got clicked). Public for the game test.
     */
    public static boolean followed(List<Step> route, List<Waypoint> found) {
        List<Waypoint> wanted = secrets(route);
        if (wanted.isEmpty() || found.size() < wanted.size() || found.size() - wanted.size() > 1) return false;
        int next = 0;
        for (Waypoint got : found) {
            if (next < wanted.size() && got.pos().distSqr(wanted.get(next).pos()) <= toleranceSq(wanted.get(next).type())) next++;
        }
        return next == wanted.size();
    }

    static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.dungeons.secrets.adaptiveRoutes;
    }

    static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("adaptive-routes.json");
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> CLEARS.clear());
        Sky2MHuds.setting(HUD_ID, AdaptiveRoutes::enabled);
        Sky2MHuds.register(HUD_ID, "Adaptive Route", () -> enabled() && Sky2MLocation.inDungeon() && DungeonRoutes.room() != null, AdaptiveRoutes::lines,
            List.of(Component.literal("§6Route 2/3 §b(learned)"), Component.literal("§7Usually §f12.3s §8(4 clears)")), 20, 215);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("route").then(ClientCommands.literal("adaptive")
                    .executes(c -> showRoom())
                    .then(ClientCommands.literal("reset").executes(c -> resetRoom())))));
            }
        });
    }

    private static void load() {
        try {
            if (Files.exists(file)) store = Store.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load adaptive routes: " + e.getMessage());
            try {
                Files.copy(file, file.resolveSibling("adaptive-routes-unreadable-" + System.currentTimeMillis() + ".json"));
            } catch (Exception copyFailed) {
                System.err.println("[Sky2M] Could not copy adaptive routes aside: " + copyFailed.getMessage());
            }
        }
    }

    private static void save() {
        try {
            DungeonRoutes.writeSafely(file, store.toJson());
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save adaptive routes: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ from DungeonRoutes

    static List<Route> learnedRoutes(String room) {
        return store.learnedRoutes(room);
    }

    /** Which route to show in the room, -1 to leave it to the closest start. */
    static int pick(String roomName, List<Route> routes, List<BlockPos> starts, BlockPos at, int current) {
        List<Double> walks = new ArrayList<>();
        for (BlockPos start : starts) walks.add(start == null ? 0 : Math.sqrt(start.distSqr(at)));
        return store.pick(DungeonRoutes.normalize(roomName), routes, walks, current);
    }

    private static Clear clear(Room room) {
        return CLEARS.computeIfAbsent(room, r -> new Clear());
    }

    static void onMoved(Room room, BlockPos pos) {
        clear(room).addPoint(pos);
    }

    static void onSecret(Room room, BlockPos pos, Type type) {
        clear(room).addSecret(pos, type);
    }

    static void onLever(Room room, BlockPos pos) {
        clear(room).addLever(pos);
    }

    static void onLockedChest(Room room) {
        Clear clear = CLEARS.get(room);
        if (clear != null) clear.undoLockedChest();
    }

    // ------------------------------------------------------------------------------------------------ from RoomBests

    /** {@link RoomBests} counted a clear of the room. {@code stacked}: a teammate was in it, even with Solo Only off. */
    static void onCleared(Room room, long ms, int secrets, boolean stacked) {
        Clear clear = CLEARS.remove(room);
        if (!enabled() || clear == null || stacked || room.getName() == null) return;
        if (store.recordClear(room.getName(), DungeonRoutes.routesFor(room.getName()), clear.finished(), ms, secrets) == null) return;
        save();
        DungeonRoutes.routesChanged();
    }

    // ------------------------------------------------------------------------------------------------ what you see

    private static List<Component> lines() {
        Room room = DungeonRoutes.room();
        Route showing = room == null ? null : DungeonRoutes.showingRoute(room);
        if (showing == null) return List.of();
        String key = DungeonRoutes.normalize(room.getName());
        int total = DungeonRoutes.routesFor(room.getName()).size();
        long usual = store.usual(key, showing.key());
        int clears = store.clears(key, showing.key());
        return List.of(
            Component.literal("§6Route " + (DungeonRoutes.routeIndex() + 1) + "/" + total + (store.isLearned(showing.key()) ? " §b(learned)" : "")),
            Component.literal(usual < 0 ? "§7No clears on this one yet" : "§7Usually §f" + RoomClears.timeText(usual) + " §8(" + clears + (clears == 1 ? " clear)" : " clears)")));
    }

    private static int showRoom() {
        Room room = DungeonRoutes.room();
        if (room == null) return DungeonRoutes.say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        String key = DungeonRoutes.normalize(room.getName());
        List<Route> routes = DungeonRoutes.routesFor(room.getName());
        if (routes.isEmpty()) return DungeonRoutes.say("No routes for " + room.getName() + " yet.", ChatFormatting.YELLOW);
        StringBuilder text = new StringBuilder(room.getName() + (enabled() ? "" : " (Adaptive Routes is off):"));
        for (int i = 0; i < routes.size(); i++) {
            Route route = routes.get(i);
            long usual = store.usual(key, route.key());
            text.append("\n").append(i + 1).append(store.isLearned(route.key()) ? " (learned): " : ": ")
                .append(usual < 0 ? "no clears" : RoomClears.timeText(usual) + ", " + store.clears(key, route.key()) + " clears");
        }
        return DungeonRoutes.say(text.toString(), ChatFormatting.YELLOW);
    }

    private static int resetRoom() {
        Room room = DungeonRoutes.room();
        if (room == null) return DungeonRoutes.say("Stand in the room you want to forget first.", ChatFormatting.RED);
        if (!store.reset(DungeonRoutes.normalize(room.getName()))) return DungeonRoutes.say("Nothing learned for " + room.getName() + " yet.", ChatFormatting.YELLOW);
        CLEARS.remove(room);
        save();
        DungeonRoutes.routesChanged();
        return DungeonRoutes.say("Forgot your times and learned routes for " + room.getName() + ".", ChatFormatting.YELLOW);
    }
}
