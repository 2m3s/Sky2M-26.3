package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2ic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Secret routes, in Stella's format and coordinate system so Stella's routes and Sky2M's
 * recorded routes are interchangeable. Route data and the room rotation scheme are from
 * Stella (https://github.com/Eclipse-5214/stella, LGPL-3.0): each room's origin is the blue
 * terracotta block in one of its roof corners, and the corner that holds it gives the rotation.
 *
 * <p>Your own routes are all kept in one file, {@code sky2m/routes.json}. Route files you put in
 * {@code config/sky2m/dungeon route/} (Stella / Sky2M, SecretRoutes or Dungeon Rooms Mod style) come
 * next, and any room still without a route uses Stella's default route. {@code /s2 export} writes your
 * recorded routes for sharing.
 *
 * <p>A room can have several routes in a file: "Melon-3", "Melon-3:2", "Melon-3:3" (SecretRoutes names them
 * the same way, and an older file simply has the one). The route that starts closest to you shows, until you
 * get its first secret. Pearl steps keep the angle they were thrown at, drawn as a line; nothing here moves,
 * aims or clicks for the player. Behaviour of both from SecretRoutes (https://github.com/yourboykyle/SecretRoutes,
 * GPL-3.0), written for this class.
 */
public final class DungeonRoutes {
    private static final String STELLA_URL = "https://ether.stellarskys.co/routes/default.json";
    private static final int HALF_ROOM = 15;
    private static final float[] LINE_COLOUR = {0.3f, 1f, 0.3f};

    // Stella room names that differ from Sky2M's (Skyblocker's) room names.
    private static final Map<String, String> ALIASES = Map.of(
        "withermancer", "withermancers",
        "dinosite", "dinodigsite",
        "silversword", "silverssword",
        "mines", "mithrilcave",
        "rails", "railtrack",
        "pipes", "sewer",
        "staircase", "doublestair");

    public enum Type {
        START(0x55FF55, ""), BAT(0x55FFFF, "Bat"), CHEST(0xFFAA00, "Click"), ESSENCE(0xAA00AA, "Click"),
        ITEM(0x5555FF, "Item"), MINE(0x8B4513, "Mine"), LEVER(0xFFFF55, "Flick"), SUPERBOOM(0xFF5555, "Boom!"),
        ETHERWARP(0xFF55FF, "Warp"), PEARL(0x00AAAA, "Pearl"), CUSTOM(0xFFFFFF, "");

        final float[] colour;
        final String label;

        Type(int rgb, String label) {
            this.colour = new float[]{(rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f};
            this.label = label;
        }

        boolean secret() {
            return this == CHEST || this == ITEM || this == ESSENCE || this == BAT;
        }

        static Type of(String name) {
            try {
                return valueOf(name.toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                return CUSTOM;
            }
        }
    }

    public record Waypoint(BlockPos pos, Type type, String name, Aim aim) {
        public Waypoint(BlockPos pos, Type type, String name) {
            this(pos, type, name, null);
        }
    }

    /** The angle a pearl was thrown at, in room terms: {@code from} is where your feet were, {@code yaw} is turned with the room. */
    public record Aim(float pitch, float yaw, Vec3 from) {}

    public record Step(List<Waypoint> waypoints, List<BlockPos> line) {
        public Step() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        public Waypoint secret() {
            for (Waypoint w : waypoints) if (w.type().secret()) return w;
            return null;
        }
    }

    /** One route for a room. {@code key} is its name in the file ("Melon-3:2"); {@code own} is true for routes in routes.json. */
    public record Route(String key, List<Step> steps, boolean own) {}

    /** Room corner (x, z) and rotation in degrees, Stella's scheme. */
    public record Frame(int x, int z, int rotation) {
        public BlockPos toRoom(BlockPos pos) {
            return rotate(new BlockPos(pos.getX() - x, pos.getY() - com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonMapUtils.yShift(), pos.getZ() - z), rotation);
        }

        public BlockPos toWorld(BlockPos local) {
            return rotate(local, 360 - rotation).offset(x, com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonMapUtils.yShift(), z);
        }

        /** A point, not a block: blocks turn about their own centre, so a point turns about the origin block's centre. */
        public Vec3 toRoom(Vec3 pos) {
            double[] r = rotate(pos.x - x - 0.5, pos.z - z - 0.5, rotation);
            return new Vec3(r[0] + 0.5, pos.y - com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonMapUtils.yShift(), r[1] + 0.5);
        }

        public Vec3 toWorld(Vec3 local) {
            double[] r = rotate(local.x - 0.5, local.z - 0.5, 360 - rotation);
            return new Vec3(r[0] + 0.5 + x, local.y + com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonMapUtils.yShift(), r[1] + 0.5 + z);
        }

        public float yawToRoom(float yaw) {
            return net.minecraft.util.Mth.wrapDegrees(yaw - rotation);
        }

        public float yawToWorld(float yaw) {
            return net.minecraft.util.Mth.wrapDegrees(yaw + rotation);
        }
    }

    private static double[] rotate(double x, double z, int rotation) {
        return switch ((rotation % 360 + 360) % 360) {
            case 90 -> new double[]{z, -x};
            case 180 -> new double[]{-x, -z};
            case 270 -> new double[]{-z, x};
            default -> new double[]{x, z};
        };
    }

    private static final Map<String, List<Step>> STELLA = new LinkedHashMap<>();
    private static final Map<String, List<Step>> CUSTOM = new LinkedHashMap<>();
    /** Routes from the files in the "dungeon route" folder. */
    private static final Map<String, List<Step>> FOLDER = new LinkedHashMap<>();
    private static Path routeFolder;
    private static volatile long folderStamp = Long.MIN_VALUE;
    private static int folderCheckTicks;
    private static final Map<Room, Frame> FRAMES = new WeakHashMap<>();
    private static Path customFile;
    private static Path stellaFile;

    /** Bumped whenever a route source changes, so the room's route list is only looked up again then. */
    private static final java.util.concurrent.atomic.AtomicInteger ROUTES_VERSION = new java.util.concurrent.atomic.AtomicInteger();
    private static String cachedRoom;
    private static int cachedVersion = -1;
    private static boolean cachedAdaptive;
    private static List<Route> cachedRoutes = List.of();

    // Playback
    private static Room playRoom;
    private static int stepIndex;
    /** Which of the room's routes is showing, and whether you chose it with /s2 route switch. */
    private static int routeIndex;
    private static boolean routeChosen;
    private static int pickTicks;
    private static final int PICK_EVERY_TICKS = 10;
    /** Another route must start this many blocks closer to take over, so standing half way doesn't flicker. */
    private static final double ROUTE_SWITCH_MARGIN = 2;
    private static final double PEARL_LINE_LENGTH = 10;
    private static final double EYE_HEIGHT = 1.62;

    // Recording
    private static Room recordRoom;
    private static List<Step> recording;
    private static BlockPos lastLinePos;
    /** Etherwarp landing heard from the server, checked next tick to make sure it was your own warp. */
    private static BlockPos pendingWarp;
    private static int pendingWarpTicks;

    private DungeonRoutes() {}

    private static FeatureConfigs.Dungeons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons;
    }

    public static void init(Path configDir) {
        customFile = configDir.resolve("sky2m").resolve("routes.json");
        stellaFile = configDir.resolve("sky2m").resolve("stella-routes.json");
        routeFolder = configDir.resolve("sky2m").resolve("dungeon route");
        createRouteFolder();
        loadCustom();
        // Stella's cached routes and the route folder can be big: read them off the render thread (lookups already lock).
        CompletableFuture.runAsync(() -> {
            Map<String, List<Step>> cached = new LinkedHashMap<>();
            load(stellaFile, cached);
            synchronized (STELLA) {
                if (STELLA.isEmpty()) STELLA.putAll(cached);
            }
            ROUTES_VERSION.incrementAndGet();
            reloadFolder(false);
            downloadStella();
        });

        ClientTickEvents.END_CLIENT_TICK.register(DungeonRoutes::tick);
        Sky2MWorldRender.register(DungeonRoutes::render);
        Sky2MChat.onChat(message -> {
            if (!message.text().trim().equalsIgnoreCase("That chest is locked!")) return;
            if (stepIndex > 0) stepIndex--;
            Room room = currentRoom();
            if (room != null && AdaptiveRoutes.enabled()) AdaptiveRoutes.onLockedChest(room);
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            // Where the pearl was thrown from.
            if (level.isClientSide() && recording != null && heldName().contains("Ender Pearl")) {
                Frame frame = recordRoom == null ? null : frame(recordRoom);
                Aim aim = frame == null ? null : new Aim(player.getXRot(), frame.yawToRoom(player.getYRot()), frame.toRoom(player.position()));
                addWaypoint(Type.PEARL, player.getOnPos(), aim);
            }
            return InteractionResult.PASS;
        });
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            // Dungeonbreaker and stonk mining: every broken block is a mining spot.
            if (recording != null) addWaypoint(Type.MINE, pos);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            // The route editor uses your right clicks on blocks; FAIL keeps that click from reaching the server.
            if (RouteEditor.onUseBlock(player, hit.getBlockPos(), level.getBlockState(hit.getBlockPos()))) return InteractionResult.FAIL;
            onUseBlock(hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
            return InteractionResult.PASS;
        });
        registerCommands();
        RouteEditor.init();
        RoutePacks.init();
        AdaptiveRoutes.init();
    }

    // ----- Stella's route file -----

    private static void downloadStella() {
        try {
            // The saved copy was just loaded: only download again when it is over 12 hours old.
            synchronized (STELLA) {
                if (!STELLA.isEmpty() && Files.isRegularFile(stellaFile)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(stellaFile).toMillis() < 12L * 60 * 60 * 1000) return;
            }
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(STELLA_URL))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            Map<String, List<Step>> parsed = parse(response.body());
            if (parsed.isEmpty()) return;
            Files.createDirectories(stellaFile.getParent());
            Files.writeString(stellaFile, response.body(), StandardCharsets.UTF_8);
            synchronized (STELLA) {
                STELLA.clear();
                STELLA.putAll(parsed);
            }
            ROUTES_VERSION.incrementAndGet();
            System.out.println("[Sky2M] Loaded Stella routes for " + parsed.size() + " rooms.");
        } catch (Exception e) {
            System.err.println("[Sky2M] Stella route download failed: " + e.getMessage());
        }
    }

    // ----- The "dungeon route" folder -----

    private static void createRouteFolder() {
        try {
            Files.createDirectories(routeFolder);
            Path readme = routeFolder.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                    Put the dungeon route file(s) you want to use in this folder.

                    Any .json route file works: Stella / Sky2M exports, SecretRoutes files and
                    Dungeon Rooms Mod style secret lists. With several files, files later in
                    alphabetical order win for the same room. Inside one file, a room can have
                    several routes ("Melon-3", "Melon-3:2"); the one that starts closest to you
                    shows.

                    Rooms without a route here use Stella's default routes. Routes you record
                    yourself with /s2 route start always come first.

                    /s2 route pack import <name> adds a shared pack from your clipboard here.
                    A file ending in .json.disabled is ignored (/s2 route pack off <name>).

                    Changes are picked up automatically; /s2 route reload reloads right away.
                    """, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not create the dungeon route folder: " + e.getMessage());
        }
    }

    /** Names and last-modified times of every route file, so edits are noticed. */
    private static long folderStamp() {
        long stamp = 17;
        try (var files = Files.list(routeFolder)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                stamp = stamp * 31 + p.getFileName().toString().hashCode();
                stamp = stamp * 31 + Files.getLastModifiedTime(p).toMillis();
            }
        } catch (Exception ignored) {}
        return stamp;
    }

    /** Loads every .json in the route folder. Returns how many rooms have a route. */
    private static int reloadFolder(boolean announce) {
        Map<String, List<Step>> loaded = new LinkedHashMap<>();
        List<String> used = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (var files = Files.list(routeFolder)) {
            List<Path> jsons = files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")).sorted().toList();
            for (Path p : jsons) {
                try {
                    Map<String, List<Step>> routes = parseAny(Files.readString(p, StandardCharsets.UTF_8));
                    if (routes.isEmpty()) {
                        failed.add(p.getFileName().toString());
                        continue;
                    }
                    // A later file takes a room over from earlier files, but a file's own routes for a room all stay.
                    java.util.Set<String> rooms = new java.util.HashSet<>();
                    for (String key : routes.keySet()) rooms.add(normalize(key));
                    loaded.keySet().removeIf(k -> rooms.contains(normalize(k)));
                    loaded.putAll(routes);
                    used.add(p.getFileName().toString());
                } catch (Exception e) {
                    failed.add(p.getFileName().toString());
                }
            }
        } catch (Exception ignored) {}
        synchronized (FOLDER) {
            FOLDER.clear();
            FOLDER.putAll(loaded);
        }
        folderStamp = folderStamp();
        stepIndex = 0;
        routeIndex = 0;
        ROUTES_VERSION.incrementAndGet();
        if (announce) {
            if (used.isEmpty()) say("No routes in config/sky2m/dungeon route, using Stella's routes.", ChatFormatting.YELLOW);
            else say("Loaded routes for " + loaded.size() + " rooms from " + String.join(", ", used) + ".", ChatFormatting.GREEN);
            if (!failed.isEmpty()) say("Could not read: " + String.join(", ", failed) + ".", ChatFormatting.RED);
        }
        return loaded.size();
    }

    private static void load(Path file, Map<String, List<Step>> into) {
        try {
            if (!Files.exists(file)) return;
            into.putAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load routes from " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /** Your routes file. One that can't be read is copied aside first, so the next save can't overwrite what's in it. */
    private static void loadCustom() {
        try {
            if (Files.exists(customFile)) CUSTOM.putAll(parse(Files.readString(customFile, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load routes from " + customFile.getFileName() + ": " + e.getMessage());
            try {
                Path aside = customFile.resolveSibling("routes-unreadable-" + System.currentTimeMillis() + ".json");
                Files.copy(customFile, aside);
                System.err.println("[Sky2M] Kept a copy of it as " + aside.getFileName());
            } catch (Exception copyFailed) {
                System.err.println("[Sky2M] Could not copy it aside: " + copyFailed.getMessage());
            }
        }
    }

    /** Writes next to the file then moves it over, so a crash mid-save can't leave half a file. */
    static void writeSafely(Path file, String text) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Aim aim(JsonObject waypoint) {
        if (!waypoint.has("aim") || !waypoint.get("aim").isJsonObject()) return null;
        JsonObject aim = waypoint.getAsJsonObject("aim");
        if (!aim.has("pitch") || !aim.has("yaw") || !aim.has("from")) return null;
        JsonArray from = aim.getAsJsonArray("from");
        return new Aim(aim.get("pitch").getAsFloat(), aim.get("yaw").getAsFloat(),
            new Vec3(from.get(0).getAsDouble(), from.get(1).getAsDouble(), from.get(2).getAsDouble()));
    }

    static Map<String, List<Step>> parse(String json) {
        Map<String, List<Step>> routes = new LinkedHashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (var entry : root.entrySet()) {
            if (entry.getKey().startsWith("#") || entry.getKey().equals("Version") || !entry.getValue().isJsonArray()) continue;
            List<Step> steps = new ArrayList<>();
            for (JsonElement stepElement : entry.getValue().getAsJsonArray()) {
                JsonObject stepJson = stepElement.getAsJsonObject();
                Step step = new Step();
                for (JsonElement w : stepJson.getAsJsonArray("waypoints")) {
                    JsonObject wj = w.getAsJsonObject();
                    String name = wj.has("name") && !wj.get("name").isJsonNull() ? wj.get("name").getAsString() : null;
                    step.waypoints().add(new Waypoint(pos(wj.getAsJsonArray("pos")), Type.of(wj.get("type").getAsString()), name, aim(wj)));
                }
                for (JsonElement p : stepJson.getAsJsonArray("line")) step.line().add(pos(p.getAsJsonArray()));
                steps.add(step);
            }
            routes.put(entry.getKey(), steps);
        }
        return routes;
    }

    private static BlockPos pos(JsonArray array) {
        return BlockPos.containing(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static String serialize(Map<String, List<Step>> routes) {
        return serialize(routes, "Sky2M secret routes");
    }

    /** Stella's format; "aim" on a pearl is Sky2M's addition, which Stella's own files simply don't have. Public for the game test. */
    public static String serialize(Map<String, List<Step>> routes, String name) {
        JsonObject root = new JsonObject();
        root.addProperty("#name", name);
        root.addProperty("#origin", "recorded with Sky2M");
        root.addProperty("Version", "1.0");
        for (var entry : routes.entrySet()) {
            JsonArray steps = new JsonArray();
            for (Step step : entry.getValue()) {
                JsonObject stepJson = new JsonObject();
                JsonArray waypoints = new JsonArray();
                for (Waypoint w : step.waypoints()) {
                    JsonObject wj = new JsonObject();
                    wj.add("pos", array(w.pos()));
                    wj.addProperty("type", w.type().name());
                    if (w.name() != null) wj.addProperty("name", w.name());
                    if (w.aim() != null) {
                        JsonObject aim = new JsonObject();
                        aim.addProperty("pitch", w.aim().pitch());
                        aim.addProperty("yaw", w.aim().yaw());
                        JsonArray from = new JsonArray();
                        from.add(w.aim().from().x);
                        from.add(w.aim().from().y);
                        from.add(w.aim().from().z);
                        aim.add("from", from);
                        wj.add("aim", aim);
                    }
                    waypoints.add(wj);
                }
                JsonArray line = new JsonArray();
                for (BlockPos p : step.line()) line.add(array(p));
                stepJson.add("waypoints", waypoints);
                stepJson.add("line", line);
                steps.add(stepJson);
            }
            root.add(entry.getKey(), steps);
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
    }

    private static JsonArray array(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    static void saveCustom() {
        ROUTES_VERSION.incrementAndGet();
        try {
            writeSafely(customFile, serialize(CUSTOM));
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save routes: " + e.getMessage());
        }
    }

    // ----- Rooms -----

    /** "Melon-3", "melon-3:2" and "Melon" are all "melon". Public for the game test. */
    public static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("-\\d+$", "").replaceAll("[^a-z]", "");
        return ALIASES.getOrDefault(n, n);
    }

    private static List<Route> routesIn(Map<String, List<Step>> source, String room, boolean own) {
        List<Route> routes = new ArrayList<>();
        for (var entry : source.entrySet()) {
            if (!entry.getValue().isEmpty() && normalize(entry.getKey()).equals(room)) routes.add(new Route(entry.getKey(), entry.getValue(), own));
        }
        return routes;
    }

    /** The room's routes: yours if you have any, else those from the route folder, else Stella's; then, with Adaptive Routes on, those it learned. */
    static List<Route> routesFor(String roomName) {
        int version = ROUTES_VERSION.get();
        boolean adaptive = AdaptiveRoutes.enabled();
        if (roomName.equals(cachedRoom) && version == cachedVersion && adaptive == cachedAdaptive) return cachedRoutes;
        String key = normalize(roomName);
        List<Route> routes = routesIn(CUSTOM, key, true);
        if (routes.isEmpty()) {
            synchronized (FOLDER) {
                routes = routesIn(FOLDER, key, false);
            }
        }
        if (routes.isEmpty()) {
            synchronized (STELLA) {
                routes = routesIn(STELLA, key, false);
            }
        }
        if (adaptive) routes.addAll(AdaptiveRoutes.learnedRoutes(key));
        cachedRoom = roomName;
        cachedVersion = version;
        cachedAdaptive = adaptive;
        cachedRoutes = routes;
        return routes;
    }

    /** Adaptive Routes learned or forgot a route. */
    static void routesChanged() {
        ROUTES_VERSION.incrementAndGet();
    }

    static int routeIndex() {
        return routeIndex;
    }

    /** The route showing in the room, or null when it has none. */
    static Route showingRoute(Room room) {
        List<Route> routes = routesFor(room.getName());
        if (routes.isEmpty()) return null;
        if (routeIndex >= routes.size()) routeIndex = 0;
        return routes.get(routeIndex);
    }

    /** Where a route begins: its START marker, else the start of its line, else its first waypoint. Public for the game test. */
    public static BlockPos routeStart(List<Step> route) {
        for (Step step : route) {
            for (Waypoint w : step.waypoints()) if (w.type() == Type.START) return w.pos();
            if (!step.line().isEmpty()) return step.line().getFirst();
            if (!step.waypoints().isEmpty()) return step.waypoints().getFirst().pos();
        }
        return null;
    }

    /**
     * Which route to show: the one starting closest to {@code at}, staying on {@code current} unless another starts over
     * {@link #ROUTE_SWITCH_MARGIN} blocks closer. A route with no start (null) is never picked. Public for the game test.
     */
    public static int closestRoute(List<BlockPos> starts, BlockPos at, int current) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) == null) continue;
            double distance = Math.sqrt(starts.get(i).distSqr(at));
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        if (best < 0) return current;
        if (current >= 0 && current < starts.size() && starts.get(current) != null
            && Math.sqrt(starts.get(current).distSqr(at)) - bestDistance <= ROUTE_SWITCH_MARGIN) return current;
        return best;
    }

    private static void pickClosestRoute(Room room, Minecraft mc) {
        List<Route> routes = routesFor(room.getName());
        Frame frame = routes.size() > 1 ? frame(room) : null;
        if (frame == null || mc.player == null) return;
        List<BlockPos> starts = new ArrayList<>();
        for (Route route : routes) starts.add(routeStart(route.steps()));
        BlockPos at = frame.toRoom(mc.player.blockPosition());
        int current = Math.min(routeIndex, routes.size() - 1);
        int fastest = AdaptiveRoutes.enabled() ? AdaptiveRoutes.pick(room.getName(), routes, starts, at, current) : -1;
        routeIndex = fastest >= 0 ? fastest : closestRoute(starts, at, current);
    }

    /** A key for one more route in a room: the room's name when it has none yet, else "name:2" and on. */
    private static String freeKey(String roomName) {
        String room = normalize(roomName);
        boolean used = false;
        for (String key : CUSTOM.keySet()) used |= normalize(key).equals(room);
        if (!used) return roomName;
        int n = 2;
        while (CUSTOM.containsKey(roomName + ":" + n)) n++;
        return roomName + ":" + n;
    }

    // ----- For the route editor and packs -----

    static Room room() {
        return currentRoom();
    }

    static Frame frameOf(Room room) {
        return frame(room);
    }

    static int stepIndex() {
        return stepIndex;
    }

    static void setStepIndex(int step) {
        stepIndex = Math.max(0, step);
    }

    static Map<String, List<Step>> ownRoutes() {
        return CUSTOM;
    }

    static Path routeFolder() {
        return routeFolder;
    }

    static int reloadRouteFolder() {
        return reloadFolder(false);
    }

    /**
     * Your route to change in this room: the one showing when it's yours, else a copy of the one showing (or an empty
     * route), which becomes yours and shows from now on. /s2 route clear goes back to the original.
     */
    static List<Step> editableSteps(Room room) {
        Route showing = showingRoute(room);
        if (showing != null && showing.own()) return showing.steps();
        List<Step> copy = new ArrayList<>();
        if (showing != null) {
            for (Step step : showing.steps()) copy.add(new Step(new ArrayList<>(step.waypoints()), new ArrayList<>(step.line())));
        } else {
            copy.add(new Step());
        }
        CUSTOM.put(freeKey(room.getName()), copy);
        saveCustom();
        routeIndex = 0;
        routeChosen = true;
        say("Your route for " + room.getName() + " now starts from a copy of what was showing. /s2 route clear goes back.", ChatFormatting.YELLOW);
        return copy;
    }

    /** Starts one more empty route of yours in the room. */
    static void newRoute(Room room) {
        // Your first own route hides the bundled one, so copy nothing: the empty route is what you asked for.
        String key = freeKey(room.getName());
        List<Step> steps = new ArrayList<>();
        steps.add(new Step());
        CUSTOM.put(key, steps);
        saveCustom();
        routeIndex = Math.max(0, routesFor(room.getName()).size() - 1);
        routeChosen = true;
        stepIndex = 0;
    }

    /** Next route in the room; it stays until you leave the room. Returns the route now showing. */
    static Route switchRoute(Room room, int delta) {
        List<Route> routes = routesFor(room.getName());
        if (routes.isEmpty()) return null;
        routeIndex = Math.floorMod(routeIndex + delta, routes.size());
        routeChosen = true;
        stepIndex = 0;
        return routes.get(routeIndex);
    }

    /** Deletes the route showing, when it's yours. */
    static boolean deleteShowing(Room room) {
        Route showing = showingRoute(room);
        if (showing == null || !showing.own()) return false;
        CUSTOM.remove(showing.key());
        saveCustom();
        routeIndex = 0;
        stepIndex = 0;
        return true;
    }

    private static Room currentRoom() {
        Room room = DungeonManager.getCurrentRoom();
        return room != null && room.isMatched() && room.getName() != null ? room : null;
    }

    /** Finds the blue terracotta corner that Stella's coordinates are relative to. */
    private static Frame frame(Room room) {
        Frame cached = FRAMES.get(room);
        if (cached != null) return cached;
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        Frame sim = simFrame(room);
        if (sim != null) {
            FRAMES.put(room, sim);
            return sim;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vector2ic s : room.getSegments()) {
            minX = Math.min(minX, s.x());
            maxX = Math.max(maxX, s.x());
            minZ = Math.min(minZ, s.y());
            maxZ = Math.max(maxZ, s.y());
        }
        // Corner offsets from a segment centre: NW, NE, SE, SW, for rotations 0, 90, 180, 270.
        int[][] offsets = {{-HALF_ROOM, -HALF_ROOM}, {HALF_ROOM, -HALF_ROOM}, {HALF_ROOM, HALF_ROOM}, {-HALF_ROOM, HALF_ROOM}};
        for (Vector2ic s : room.getSegments()) {
            int cx = s.x() + HALF_ROOM, cz = s.y() + HALF_ROOM;
            int roof = roofY(level, cx, cz);
            if (roof < 0) continue;
            for (int i = 0; i < 4; i++) {
                boolean xOk = (i == 0 || i == 3) ? s.x() == minX : s.x() == maxX;
                boolean zOk = (i == 0 || i == 1) ? s.y() == minZ : s.y() == maxZ;
                if (!xOk || !zOk) continue;
                int x = cx + offsets[i][0], z = cz + offsets[i][1];
                BlockState state = level.getBlockState(new BlockPos(x, roof, z));
                if (state.is(Blocks.DYED_TERRACOTTA.blue())) {
                    Frame found = new Frame(x, z, i * 90);
                    FRAMES.put(room, found);
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Dungeon Sim: the sim placed the room, so it knows its clay corner and rotation (the same corner and the same 0/90/180/270
     * turn as Stella's), and no roof has to be searched for the blue terracotta.
     */
    private static Frame simFrame(Room room) {
        if (!com.epic60869.sky2m.sim.roomsim.SimState.isActive()) return null;
        for (Vector2ic s : room.getSegments()) {
            int cell = (s.x() + HALF_ROOM - com.epic60869.sky2m.sim.livemap.LiveMapFeature.START_X) / 16
                + (s.y() + HALF_ROOM - com.epic60869.sky2m.sim.livemap.LiveMapFeature.START_Z) / 16 * 11;
            for (var placed : com.epic60869.sky2m.sim.roomsim.SimRoomIndex.placed()) {
                for (int c : placed.cells()) if (c == cell) return new Frame(placed.clayX(), placed.clayZ(), placed.rotation());
            }
        }
        return null;
    }

    /** Highest block that is not air or gold (Stella's roof height). */
    private static int roofY(net.minecraft.client.multiplayer.ClientLevel level, int x, int z) {
        for (int y = 255; y >= 0; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.is(Blocks.GOLD_BLOCK)) return y;
        }
        return -1;
    }

    private static BlockPos rotate(BlockPos p, int rotation) {
        return switch ((rotation % 360 + 360) % 360) {
            case 90 -> new BlockPos(p.getZ(), p.getY(), -p.getX());
            case 180 -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
            case 270 -> new BlockPos(-p.getZ(), p.getY(), p.getX());
            default -> p;
        };
    }

    // ----- Playback and recording -----

    private static void tick(Minecraft mc) {
        if (++folderCheckTicks >= 100) {
            folderCheckTicks = 0;
            if (routeFolder != null && folderStamp() != folderStamp) {
                int rooms = reloadFolder(false);
                if (mc.player != null) say("Dungeon route folder changed: " + rooms + " room routes loaded.", ChatFormatting.GREEN);
            }
        }
        Room room = currentRoom();
        if (room != playRoom) {
            playRoom = room;
            stepIndex = 0;
            routeIndex = 0;
            routeChosen = false;
            pickTicks = PICK_EVERY_TICKS;
        }
        // Until the route's first secret: the closest route. After that you're on it.
        if (room != null && recording == null && !routeChosen && stepIndex == 0 && !RouteEditor.active() && ++pickTicks >= PICK_EVERY_TICKS) {
            pickTicks = 0;
            pickClosestRoute(room, mc);
        }
        if (room != null && recording == null && mc.player != null && AdaptiveRoutes.enabled() && !RouteEditor.active()) {
            Frame walked = frame(room);
            if (walked != null) AdaptiveRoutes.onMoved(room, walked.toRoom(mc.player.getOnPos().above()));
        }
        if (recording == null || mc.player == null) return;
        if (room != recordRoom) {
            say("You left the room, so the recording was stopped. Use /s2 route stop to save it or /s2 route cancel to discard it.", ChatFormatting.RED);
            finishRecording(true);
            return;
        }
        if (pendingWarp != null) {
            if (mc.player.getOnPos().distSqr(pendingWarp) <= 9) {
                addWaypoint(Type.ETHERWARP, pendingWarp);
                pendingWarp = null;
            } else if (++pendingWarpTicks > 5) {
                pendingWarp = null; // someone else's warp
            }
        }
        Frame frame = frame(room);
        if (frame == null) return;
        BlockPos pos = frame.toRoom(mc.player.getOnPos().above());
        if (lastLinePos == null || pos.distSqr(lastLinePos) > 4) {
            recording.getLast().line().add(pos);
            lastLinePos = pos;
        }
    }

    private static void onUseBlock(BlockPos pos, BlockState state) {
        Type type = state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) ? Type.CHEST
            : state.is(Blocks.PLAYER_HEAD) || state.is(Blocks.PLAYER_WALL_HEAD) ? Type.ESSENCE
            : state.is(Blocks.LEVER) ? Type.LEVER : null;
        if (type == null) return;
        if (recording != null) addWaypoint(type, pos);
        else if (type != Type.LEVER) secretFound(pos, 0, type);
        else if (AdaptiveRoutes.enabled() && !RouteEditor.active()) {
            Room room = currentRoom();
            Frame frame = room == null ? null : frame(room);
            if (frame != null) AdaptiveRoutes.onLever(room, frame.toRoom(pos));
        }
    }

    /**
     * Sounds Stella records routes from: the dragon hurt sound plays where an etherwarp lands,
     * an explosion while holding a Superboom or Explosive Bow is a superboom, and the enderman
     * teleport sound while holding pearls is where a pearl landed.
     */
    public static void onSound(SoundEvent sound, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (recording == null || mc.player == null) return;
        mc.execute(() -> {
            if (recording == null || mc.player == null) return;
            BlockPos at = BlockPos.containing(x, y, z);
            String held = heldName();
            if (sound == SoundEvents.ENDER_DRAGON_HURT) {
                pendingWarp = at.below();
                pendingWarpTicks = 0;
            } else if (sound == SoundEvents.GENERIC_EXPLODE.value()) {
                if ((held.contains("boom TNT") || held.contains("Explosive Bow")) && mc.player.distanceToSqr(x, y, z) < 20 * 20) {
                    addWaypoint(Type.SUPERBOOM, at);
                }
            } else if (sound == SoundEvents.ENDERMAN_TELEPORT && held.contains("Ender Pearl")) {
                if (mc.player.distanceToSqr(x, y, z) < 4 * 4) addWaypoint(Type.PEARL, mc.player.getOnPos());
            }
        });
    }

    private static String heldName() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : Sky2MLocation.strip(com.epic60869.sky2m.custom.util.Compat.realName(player.getMainHandItem()).getString());
    }

    /** Called when a dungeon item secret is picked up. */
    public static void onItemPickup(Entity item) {
        if (recording != null) addWaypoint(Type.ITEM, item.blockPosition());
        else secretFound(item.blockPosition(), 25, Type.ITEM);
    }

    /** Called when a bat secret dies. */
    public static void onBatKilled(Entity bat) {
        if (recording != null) addWaypoint(Type.BAT, bat.blockPosition());
        else secretFound(bat.blockPosition(), 100, Type.BAT);
    }

    /** Moves to the next step when the current step's secret is collected. */
    private static void secretFound(BlockPos pos, int maxDistanceSq, Type type) {
        Room room = currentRoom();
        if (room == null || RouteEditor.active()) return;
        Route showing = showingRoute(room);
        List<Step> route = showing == null ? null : showing.steps();
        Frame frame = frame(room);
        if (frame != null && AdaptiveRoutes.enabled()) AdaptiveRoutes.onSecret(room, frame.toRoom(pos), type);
        if (route == null || frame == null || stepIndex >= route.size()) return;
        Waypoint secret = route.get(stepIndex).secret();
        if (secret == null) return;
        if (frame.toWorld(secret.pos()).distSqr(pos) <= maxDistanceSq && stepIndex < route.size() - 1) stepIndex++;
    }

    private static void addWaypoint(Type type, BlockPos worldPos) {
        addWaypoint(type, worldPos, null);
    }

    private static void addWaypoint(Type type, BlockPos worldPos, Aim aim) {
        Frame frame = recordRoom == null ? null : frame(recordRoom);
        if (frame == null) return;
        BlockPos pos = frame.toRoom(worldPos);
        Step step = recording.getLast();
        if (type.secret()) {
            Step previous = recording.size() > 1 ? recording.get(recording.size() - 2) : null;
            Waypoint last = previous == null ? null : previous.secret();
            if (last != null && last.pos().distSqr(pos) < 3) return; // same secret clicked twice
            step.waypoints().add(new Waypoint(pos, type, null));
            Step next = new Step();
            next.line().add(pos);
            recording.add(next);
            say("Recorded " + type.name().toLowerCase(Locale.ROOT) + " secret (step " + (recording.size() - 1) + ").", ChatFormatting.GREEN);
        } else {
            for (int i = 0; i < step.waypoints().size(); i++) {
                Waypoint w = step.waypoints().get(i);
                if (w.type() != type || !w.pos().equals(pos)) continue;
                if (aim == null || aim.equals(w.aim())) return; // already recorded
                if (w.aim() != null) continue; // a second pearl from the same spot, at another angle
                // You landed here on a pearl, and now threw the next one from here.
                step.waypoints().set(i, new Waypoint(w.pos(), w.type(), w.name(), aim));
                return;
            }
            step.waypoints().add(new Waypoint(pos, type, null, aim));
            if (type != Type.MINE && type != Type.START) say("Recorded " + type.name().toLowerCase(Locale.ROOT) + ".", ChatFormatting.GRAY);
        }
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.Dungeons config = config();
        if (config == null || (!config.secrets.routes && !RouteEditor.active()) || !Sky2MLocation.inDungeon()) return;
        Room room = currentRoom();
        if (room == null) return;
        Frame frame = frame(room);
        if (frame == null) return;
        if (recording != null && room == recordRoom) {
            renderStep(collector, frame, recording.getLast(), recording.size() == 1);
            return;
        }
        Route showing = showingRoute(room);
        List<Step> route = showing == null ? List.of() : showing.steps();
        if (RouteEditor.active()) {
            RouteEditor.render(collector, frame, route, stepIndex);
            return;
        }
        if (route.isEmpty() || stepIndex >= route.size()) return;
        renderStep(collector, frame, route.get(stepIndex), stepIndex == 0);
    }

    /** The line a pearl was thrown along, from eye height: world coordinates, start then end. Public for the game test. */
    public static Vec3[] pearlLine(Frame frame, Aim aim) {
        Vec3 start = frame.toWorld(aim.from()).add(0, EYE_HEIGHT, 0);
        return new Vec3[]{start, start.add(Vec3.directionFromRotation(aim.pitch(), frame.yawToWorld(aim.yaw())).scale(PEARL_LINE_LENGTH))};
    }

    static void renderStep(PrimitiveCollector collector, Frame frame, Step step, boolean first) {
        FeatureConfigs.Dungeons config = config();
        boolean pearlLines = config != null && config.secrets.pearlLines;
        for (Waypoint w : step.waypoints()) {
            if (pearlLines && w.aim() != null) collector.submitLinesFromPoints(pearlLine(frame, w.aim()), Type.PEARL.colour, 1f, 3f, true);
        }
        if (step.line().size() > 1) {
            Vec3[] points = new Vec3[step.line().size()];
            for (int i = 0; i < points.length; i++) points[i] = Vec3.atCenterOf(frame.toWorld(step.line().get(i)));
            collector.submitLinesFromPoints(points, LINE_COLOUR, 1f, 3f, true);
            if (first) collector.submitText(Component.literal("Start!").withStyle(ChatFormatting.GREEN), points[0].add(0, 1, 0), true);
        }
        boolean firstMine = true;
        for (Waypoint w : step.waypoints()) {
            BlockPos pos = frame.toWorld(w.pos());
            // Secrets are drawn through walls; everything else only when visible, as in Stella.
            boolean throughWalls = w.type().secret() || w.type() == Type.START;
            collector.submitOutlinedBox(pos, w.type().colour, 2f, throughWalls);
            boolean label = w.type() != Type.MINE || firstMine;
            if (w.type() == Type.MINE) firstMine = false;
            String text = w.name() != null ? w.name() : w.type().label;
            if (label && !text.isEmpty()) collector.submitText(Component.literal(text), Vec3.atCenterOf(pos), true);
        }
    }

    // ----- Commands -----

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("route")
                        .then(ClientCommands.literal("start").executes(c -> start()))
                        .then(ClientCommands.literal("stop").executes(c -> finishRecording(false)))
                        .then(ClientCommands.literal("cancel").executes(c -> {
                            recording = null;
                            recordRoom = null;
                            return say("Recording cancelled.", ChatFormatting.YELLOW);
                        }))
                        .then(ClientCommands.literal("next").executes(c -> step(1)))
                        .then(ClientCommands.literal("back").executes(c -> step(-1)))
                        .then(ClientCommands.literal("clear").executes(c -> clear()))
                        .then(ClientCommands.literal("delete").executes(c -> delete()))
                        .then(ClientCommands.literal("switch").executes(c -> switchCommand()))
                        .then(ClientCommands.literal("reload").executes(c -> {
                            reloadFolder(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("folder").executes(c -> {
                            com.mojang.blaze3d.Blaze3D.openPath(routeFolder);
                            return 1;
                        }))
                        .then(ClientCommands.literal("list").executes(c -> say("Your routes: "
                            + (CUSTOM.isEmpty() ? "none" : String.join(", ", CUSTOM.keySet())) + ". Route folder routes: " + FOLDER.size() + ". Stella routes loaded: " + STELLA.size() + "." + roomRoutesText(), ChatFormatting.YELLOW))))
                    .then(ClientCommands.literal("export").executes(c -> export())));
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("route")
                        .then(ClientCommands.literal("import")
                            .executes(c -> importRoutes(null))
                            .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                .executes(c -> importRoutes(StringArgumentType.getString(c, "file")))))));
            }
        });
    }

    private static int start() {
        Room room = currentRoom();
        if (room == null) return say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        if (frame(room) == null) return say("Couldn't find this room's corner marker yet. Walk around a little and try again.", ChatFormatting.RED);
        recordRoom = room;
        recording = new ArrayList<>();
        recording.add(new Step());
        lastLinePos = null;
        addWaypoint(Type.START, Minecraft.getInstance().player.getOnPos());
        return say("Recording a route for " + room.getName() + ". Get the secrets in order (secrets, levers, etherwarps, superbooms, pearls and mined blocks are recorded automatically), then /s2 route stop to save.", ChatFormatting.GREEN);
    }

    private static int finishRecording(boolean discardIfEmpty) {
        if (recording == null || recordRoom == null) return say("Nothing is being recorded. Start with /s2 route start.", ChatFormatting.RED);
        List<Step> steps = recording;
        Room room = recordRoom;
        String name = recordRoom.getName();
        recording = null;
        recordRoom = null;
        // The last step only holds the walk after the final secret.
        if (steps.size() > 1 && steps.getLast().secret() == null) steps.removeLast();
        if (steps.getFirst().secret() == null && steps.size() == 1) {
            return say("No secrets were recorded, so nothing was saved.", ChatFormatting.RED);
        }
        CUSTOM.put(freeKey(name), steps);
        saveCustom();
        int routes = routesFor(name).size();
        if (room == playRoom) {
            routeIndex = routes - 1;
            stepIndex = 0;
        }
        if (routes == 1) return say("Saved your route for " + name + " (" + steps.size() + " steps).", ChatFormatting.GREEN);
        return say("Saved your route for " + name + " (" + steps.size() + " steps) as route " + routes + " of " + routes
            + ". The one that starts closest to you shows; /s2 route switch changes it, /s2 route delete deletes the one showing.", ChatFormatting.GREEN);
    }

    private static int step(int delta) {
        Room room = currentRoom();
        Route showing = room == null ? null : showingRoute(room);
        if (showing == null) return say("No route for this room.", ChatFormatting.RED);
        stepIndex = Math.max(0, Math.min(showing.steps().size() - 1, stepIndex + delta));
        return say("Step " + (stepIndex + 1) + "/" + showing.steps().size() + ".", ChatFormatting.YELLOW);
    }

    private static int switchCommand() {
        Room room = currentRoom();
        if (room == null) return say("Stand in a dungeon room Sky2M has recognised first.", ChatFormatting.RED);
        List<Route> routes = routesFor(room.getName());
        if (routes.size() < 2) return say(routes.isEmpty() ? "No route for this room." : "This room only has one route.", ChatFormatting.RED);
        switchRoute(room, 1);
        return say("Showing route " + (routeIndex + 1) + " of " + routes.size() + " until you leave the room.", ChatFormatting.YELLOW);
    }

    private static int delete() {
        Room room = currentRoom();
        if (room == null) return say("Stand in the room whose route you want to delete.", ChatFormatting.RED);
        Route showing = showingRoute(room);
        if (!deleteShowing(room)) return say("The route showing isn't one of yours.", ChatFormatting.RED);
        int left = routesFor(room.getName()).size();
        return say("Deleted your route " + showing.key() + ". " + (routesFor(room.getName()).stream().anyMatch(Route::own) ? left + " of yours left in this room." : "No routes of yours left in this room."), ChatFormatting.YELLOW);
    }

    private static String roomRoutesText() {
        Room room = currentRoom();
        if (room == null) return "";
        int routes = routesFor(room.getName()).size();
        return routes == 0 ? " No route for this room." : " This room: route " + (routeIndex + 1) + " of " + routes + ".";
    }

    private static int clear() {
        Room room = currentRoom();
        if (room == null) return say("Stand in the room whose route you want to delete.", ChatFormatting.RED);
        String key = normalize(room.getName());
        int before = CUSTOM.size();
        CUSTOM.keySet().removeIf(k -> normalize(k).equals(key));
        int removed = before - CUSTOM.size();
        if (removed == 0) return say("You have no route for this room. It uses Stella's route.", ChatFormatting.RED);
        saveCustom();
        routeIndex = 0;
        stepIndex = 0;
        routeChosen = false;
        return say("Deleted your " + (removed == 1 ? "route" : removed + " routes") + " for " + room.getName() + ". Stella's route is used again.", ChatFormatting.YELLOW);
    }

    private static int export() {
        try {
            Path out = customFile.resolveSibling("routes-export.json");
            String json = serialize(CUSTOM);
            Files.createDirectories(out.getParent());
            Files.writeString(out, json, StandardCharsets.UTF_8);
            Minecraft.getInstance().keyboardHandler.setClipboard(json);
            Sky2MAlerts.chat(Component.literal("Exported " + CUSTOM.size() + " routes to ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(out.getFileName().toString()).withStyle(style -> style.withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenFile(out.toAbsolutePath()))))
                .append(Component.literal(" and copied them to your clipboard. The file uses Stella's route format.").withStyle(ChatFormatting.GREEN)));
            return 1;
        } catch (Exception e) {
            return say("Export failed: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    // ----- Import -----

    /**
     * Imports routes from the clipboard, or from a file: a path, or a file name in Sky2M's or Stella's route folder.
     * Accepts Stella's format (what Stella and /s2 export write) and the SecretRoutes mod's format.
     */
    private static int importRoutes(String file) {
        String json;
        String source;
        try {
            if (file == null) {
                json = Minecraft.getInstance().keyboardHandler.getClipboard();
                source = "your clipboard";
                if (json == null || json.isBlank()) return say("Your clipboard is empty. Copy a route export (Stella's export or /s2 export) or use /s2 route import <file>.", ChatFormatting.RED);
            } else {
                Path path = findRouteFile(file.trim().replace("\"", ""));
                if (path == null) return say("Could not find " + file + ". Give a full path, or a file in config/sky2m or config/stella/routes.", ChatFormatting.RED);
                json = Files.readString(path, StandardCharsets.UTF_8);
                source = path.getFileName().toString();
            }
            Map<String, List<Step>> imported = parseAny(json);
            if (imported.isEmpty()) return say("No routes found in " + source + ". Supported: Stella / Sky2M route files and SecretRoutes files.", ChatFormatting.RED);
            // Added next to what you have, never over it; a route you already have isn't added twice.
            int added = 0;
            for (var entry : imported.entrySet()) {
                String room = normalize(entry.getKey());
                boolean have = false;
                for (var own : CUSTOM.entrySet()) have |= normalize(own.getKey()).equals(room) && own.getValue().equals(entry.getValue());
                if (have) continue;
                CUSTOM.put(freeKey(entry.getKey().split(":", 2)[0]), entry.getValue());
                added++;
            }
            saveCustom();
            stepIndex = 0;
            routeIndex = 0;
            return say("Imported " + added + " new routes from " + source + " (" + (imported.size() - added) + " you already had). They're added next to your routes for those rooms; the one that starts closest to you shows (/s2 route clear in a room goes back to Stella's).", ChatFormatting.GREEN);
        } catch (Exception e) {
            return say("Import failed: " + e.getMessage(), ChatFormatting.RED);
        }
    }

    private static Path findRouteFile(String name) {
        Path configDir = customFile.getParent().getParent();
        List<Path> candidates = new ArrayList<>();
        try {
            candidates.add(Path.of(name));
        } catch (Exception ignored) {}
        for (String n : List.of(name, name + ".json")) {
            candidates.add(customFile.resolveSibling(n));
            candidates.add(configDir.resolve("stella").resolve("routes").resolve(n));
            candidates.add(configDir.resolve(n));
        }
        for (Path p : candidates) if (p != null && Files.isRegularFile(p)) return p;
        return null;
    }

    /** Reads any supported route file: Stella / Sky2M, SecretRoutes or a Dungeon Rooms Mod style list. Public for the game test. */
    public static Map<String, List<Step>> parseAny(String json) {
        JsonObject root = JsonParser.parseString(json.trim()).getAsJsonObject();
        if (root.has("routes") && root.get("routes").isJsonObject()) root = root.getAsJsonObject("routes");
        for (var entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray() || entry.getValue().getAsJsonArray().isEmpty()) continue;
            JsonElement first = entry.getValue().getAsJsonArray().get(0);
            if (!first.isJsonObject()) continue;
            if (first.getAsJsonObject().has("waypoints")) return parse(root.toString());
            if (first.getAsJsonObject().has("secret") || first.getAsJsonObject().has("locations")) return parseSecretRoutes(root);
            if (first.getAsJsonObject().has("x") && first.getAsJsonObject().has("z")) return parseSecretList(root);
        }
        return new LinkedHashMap<>();
    }

    /**
     * Dungeon Rooms Mod style: per room a list of secrets as {"secretName", "category", "x", "y", "z"}.
     * Each secret becomes one step, in file order.
     */
    private static Map<String, List<Step>> parseSecretList(JsonObject root) {
        Map<String, List<Step>> routes = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            List<Step> steps = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject w = element.getAsJsonObject();
                if (!w.has("x") || !w.has("y") || !w.has("z")) continue;
                String category = w.has("category") ? w.get("category").getAsString().toLowerCase(Locale.ROOT) : "";
                Type type = switch (category) {
                    case "chest" -> Type.CHEST;
                    case "wither", "essence" -> Type.ESSENCE;
                    case "item" -> Type.ITEM;
                    case "bat" -> Type.BAT;
                    case "lever" -> Type.LEVER;
                    case "superboom" -> Type.SUPERBOOM;
                    case "stonk" -> Type.MINE;
                    case "entrance" -> Type.START;
                    default -> Type.CUSTOM;
                };
                String name = w.has("secretName") ? w.get("secretName").getAsString() : null;
                Step step = new Step();
                step.waypoints().add(new Waypoint(BlockPos.containing(w.get("x").getAsDouble(), w.get("y").getAsDouble(), w.get("z").getAsDouble()), type, type == Type.CUSTOM ? name : null));
                steps.add(step);
            }
            if (!steps.isEmpty()) routes.put(entry.getKey(), steps);
        }
        return routes;
    }

    /**
     * The SecretRoutes mod's format: per room a list of steps with "locations" (the walk), "etherwarps", "mines",
     * "tnts", "enderpearls" and a "secret" with a type and location. Its room-relative coordinates are read as-is,
     * so rooms whose corner differs from Stella's may be offset.
     */
    private static Map<String, List<Step>> parseSecretRoutes(JsonObject root) {
        Map<String, List<Step>> routes = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            List<Step> steps = new ArrayList<>();
            for (JsonElement stepElement : entry.getValue().getAsJsonArray()) {
                if (!stepElement.isJsonObject()) continue;
                JsonObject sj = stepElement.getAsJsonObject();
                Step step = new Step();
                if (steps.isEmpty() && sj.has("locations") && !sj.getAsJsonArray("locations").isEmpty()) {
                    step.waypoints().add(new Waypoint(pos(sj.getAsJsonArray("locations").get(0).getAsJsonArray()), Type.START, null));
                }
                addAll(sj, "locations", null, step);
                addAll(sj, "etherwarps", Type.ETHERWARP, step);
                addAll(sj, "mines", Type.MINE, step);
                addAll(sj, "tnts", Type.SUPERBOOM, step);
                addPearls(sj, step);
                if (sj.has("secret") && sj.get("secret").isJsonObject()) {
                    JsonObject secret = sj.getAsJsonObject("secret");
                    String type = secret.has("type") ? secret.get("type").getAsString().toLowerCase(Locale.ROOT) : "interact";
                    Type t = switch (type) {
                        case "item" -> Type.ITEM;
                        case "bat" -> Type.BAT;
                        case "exitroute" -> Type.CUSTOM;
                        default -> Type.CHEST;
                    };
                    if (secret.has("location")) step.waypoints().add(new Waypoint(pos(secret.getAsJsonArray("location")), t, t == Type.CUSTOM ? "Exit" : null));
                }
                steps.add(step);
            }
            if (!steps.isEmpty()) routes.put(entry.getKey(), steps);
        }
        return routes;
    }

    /**
     * SecretRoutes' "enderpearls" are where your feet were and its "enderpearlangles" are [pitch, yaw]. Its rooms turn the
     * same way as Stella's (S, W, N, E are 0, 90, 180, 270), and it draws the line at the saved yaw + 90, which is what
     * fits the landing spots in its own pearlroutes.json, so that's the yaw kept here.
     */
    private static void addPearls(JsonObject step, Step into) {
        if (!step.has("enderpearls") || !step.get("enderpearls").isJsonArray()) return;
        JsonArray pearls = step.getAsJsonArray("enderpearls");
        JsonArray angles = step.has("enderpearlangles") && step.get("enderpearlangles").isJsonArray() ? step.getAsJsonArray("enderpearlangles") : new JsonArray();
        for (int i = 0; i < pearls.size(); i++) {
            if (!pearls.get(i).isJsonArray() || pearls.get(i).getAsJsonArray().size() < 3) continue;
            JsonArray at = pearls.get(i).getAsJsonArray();
            Aim aim = null;
            if (i < angles.size() && angles.get(i).isJsonArray() && angles.get(i).getAsJsonArray().size() >= 2) {
                JsonArray angle = angles.get(i).getAsJsonArray();
                aim = new Aim(angle.get(0).getAsFloat(), net.minecraft.util.Mth.wrapDegrees(angle.get(1).getAsFloat() + 90),
                    new Vec3(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()));
            }
            into.waypoints().add(new Waypoint(pos(at), Type.PEARL, null, aim));
        }
    }

    private static void addAll(JsonObject step, String key, Type type, Step into) {
        if (!step.has(key) || !step.get(key).isJsonArray()) return;
        for (JsonElement e : step.getAsJsonArray(key)) {
            if (!e.isJsonArray() || e.getAsJsonArray().size() < 3) continue;
            BlockPos p = pos(e.getAsJsonArray());
            if (type == null) into.line().add(p);
            else into.waypoints().add(new Waypoint(p, type, null));
        }
    }

    static int say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
        return 1;
    }
}
