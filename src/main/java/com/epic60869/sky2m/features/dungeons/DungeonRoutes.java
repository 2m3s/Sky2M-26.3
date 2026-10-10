package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
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

    public record Waypoint(BlockPos pos, Type type, String name) {}

    public record Step(List<Waypoint> waypoints, List<BlockPos> line) {
        Step() {
            this(new ArrayList<>(), new ArrayList<>());
        }

        Waypoint secret() {
            for (Waypoint w : waypoints) if (w.type().secret()) return w;
            return null;
        }
    }

    /** Room corner (x, z) and rotation in degrees, Stella's scheme. */
    private record Frame(int x, int z, int rotation) {
        BlockPos toRoom(BlockPos pos) {
            return rotate(new BlockPos(pos.getX() - x, pos.getY(), pos.getZ() - z), rotation);
        }

        BlockPos toWorld(BlockPos local) {
            return rotate(local, 360 - rotation).offset(x, 0, z);
        }
    }

    private static final Map<String, List<Step>> STELLA = new LinkedHashMap<>();
    private static final Map<String, List<Step>> CUSTOM = new LinkedHashMap<>();
    /** Routes from the files in the "dungeon route" folder. */
    private static final Map<String, List<Step>> FOLDER = new LinkedHashMap<>();
    private static Path routeFolder;
    private static long folderStamp = Long.MIN_VALUE;
    private static int folderCheckTicks;
    private static final Map<Room, Frame> FRAMES = new WeakHashMap<>();
    private static Path customFile;
    private static Path stellaFile;

    // Playback
    private static Room playRoom;
    private static int stepIndex;

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
        load(customFile, CUSTOM);
        load(stellaFile, STELLA);
        reloadFolder(false);
        CompletableFuture.runAsync(DungeonRoutes::downloadStella);

        ClientTickEvents.END_CLIENT_TICK.register(DungeonRoutes::tick);
        Sky2MWorldRender.register(DungeonRoutes::render);
        Sky2MChat.onChat(message -> {
            if (message.text().trim().equalsIgnoreCase("That chest is locked!") && stepIndex > 0) stepIndex--;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            // Where the pearl was thrown from.
            if (level.isClientSide() && recording != null && heldName().contains("Ender Pearl")) addWaypoint(Type.PEARL, player.getOnPos());
            return InteractionResult.PASS;
        });
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            // Dungeonbreaker and stonk mining: every broken block is a mining spot.
            if (recording != null) addWaypoint(Type.MINE, pos);
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) onUseBlock(hit.getBlockPos(), level.getBlockState(hit.getBlockPos()));
            return InteractionResult.PASS;
        });
        registerCommands();
    }

    // ----- Stella's route file -----

    private static void downloadStella() {
        try {
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
                    alphabetical order win for the same room.

                    Rooms without a route here use Stella's default routes. Routes you record
                    yourself with /s2 route start always come first.

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
                    for (var entry : routes.entrySet()) {
                        String key = normalize(entry.getKey());
                        loaded.keySet().removeIf(k -> normalize(k).equals(key));
                        loaded.put(entry.getKey(), entry.getValue());
                    }
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

    private static Map<String, List<Step>> parse(String json) {
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
                    step.waypoints().add(new Waypoint(pos(wj.getAsJsonArray("pos")), Type.of(wj.get("type").getAsString()), name));
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
        JsonObject root = new JsonObject();
        root.addProperty("#name", "Sky2M secret routes");
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

    private static void saveCustom() {
        try {
            Files.createDirectories(customFile.getParent());
            Files.writeString(customFile, serialize(CUSTOM), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save routes: " + e.getMessage());
        }
    }

    // ----- Rooms -----

    private static String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("-\\d+$", "").replaceAll("[^a-z]", "");
        return ALIASES.getOrDefault(n, n);
    }

    /** Your recorded route for the room, else one from the route folder, else Stella's. */
    private static List<Step> routeFor(String roomName) {
        String key = normalize(roomName);
        for (var entry : CUSTOM.entrySet()) if (normalize(entry.getKey()).equals(key)) return entry.getValue();
        synchronized (FOLDER) {
            for (var entry : FOLDER.entrySet()) if (normalize(entry.getKey()).equals(key)) return entry.getValue();
        }
        synchronized (STELLA) {
            for (var entry : STELLA.entrySet()) if (normalize(entry.getKey()).equals(key)) return entry.getValue();
        }
        return null;
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
        else if (type != Type.LEVER) secretFound(pos, 0);
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
        else secretFound(item.blockPosition(), 25);
    }

    /** Called when a bat secret dies. */
    public static void onBatKilled(Entity bat) {
        if (recording != null) addWaypoint(Type.BAT, bat.blockPosition());
        else secretFound(bat.blockPosition(), 100);
    }

    /** Moves to the next step when the current step's secret is collected. */
    private static void secretFound(BlockPos pos, int maxDistanceSq) {
        Room room = currentRoom();
        if (room == null) return;
        List<Step> route = routeFor(room.getName());
        Frame frame = frame(room);
        if (route == null || frame == null || stepIndex >= route.size()) return;
        Waypoint secret = route.get(stepIndex).secret();
        if (secret == null) return;
        if (frame.toWorld(secret.pos()).distSqr(pos) <= maxDistanceSq && stepIndex < route.size() - 1) stepIndex++;
    }

    private static void addWaypoint(Type type, BlockPos worldPos) {
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
            for (Waypoint w : step.waypoints()) {
                if (w.type() == type && w.pos().equals(pos)) return; // already recorded
            }
            step.waypoints().add(new Waypoint(pos, type, null));
            if (type != Type.MINE && type != Type.START) say("Recorded " + type.name().toLowerCase(Locale.ROOT) + ".", ChatFormatting.GRAY);
        }
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.Dungeons config = config();
        if (config == null || !config.secrets.routes || !Sky2MLocation.inDungeon()) return;
        Room room = currentRoom();
        if (room == null) return;
        Frame frame = frame(room);
        if (frame == null) return;
        if (recording != null && room == recordRoom) {
            renderStep(collector, frame, recording.getLast(), recording.size() == 1);
            return;
        }
        List<Step> route = routeFor(room.getName());
        if (route == null || route.isEmpty() || stepIndex >= route.size()) return;
        renderStep(collector, frame, route.get(stepIndex), stepIndex == 0);
    }

    private static void renderStep(PrimitiveCollector collector, Frame frame, Step step, boolean first) {
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
                        .then(ClientCommands.literal("reload").executes(c -> {
                            reloadFolder(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("folder").executes(c -> {
                            com.mojang.blaze3d.Blaze3D.openPath(routeFolder);
                            return 1;
                        }))
                        .then(ClientCommands.literal("list").executes(c -> say("Your routes: "
                            + (CUSTOM.isEmpty() ? "none" : String.join(", ", CUSTOM.keySet())) + ". Route folder rooms: " + FOLDER.size() + ". Stella routes loaded: " + STELLA.size() + ".", ChatFormatting.YELLOW))))
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
        String name = recordRoom.getName();
        recording = null;
        recordRoom = null;
        // The last step only holds the walk after the final secret.
        if (steps.size() > 1 && steps.getLast().secret() == null) steps.removeLast();
        if (steps.getFirst().secret() == null && steps.size() == 1) {
            return say("No secrets were recorded, so nothing was saved.", ChatFormatting.RED);
        }
        CUSTOM.put(name, steps);
        saveCustom();
        return say("Saved your route for " + name + " (" + steps.size() + " steps).", ChatFormatting.GREEN);
    }

    private static int step(int delta) {
        Room room = currentRoom();
        List<Step> route = room == null ? null : routeFor(room.getName());
        if (route == null) return say("No route for this room.", ChatFormatting.RED);
        stepIndex = Math.max(0, Math.min(route.size() - 1, stepIndex + delta));
        return say("Step " + (stepIndex + 1) + "/" + route.size() + ".", ChatFormatting.YELLOW);
    }

    private static int clear() {
        Room room = currentRoom();
        if (room == null) return say("Stand in the room whose route you want to delete.", ChatFormatting.RED);
        String key = normalize(room.getName());
        boolean removed = CUSTOM.keySet().removeIf(k -> normalize(k).equals(key));
        if (!removed) return say("You have no route for this room. It uses Stella's route.", ChatFormatting.RED);
        saveCustom();
        return say("Deleted your route for " + room.getName() + ". Stella's route is used again.", ChatFormatting.YELLOW);
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
            for (var entry : imported.entrySet()) {
                String key = normalize(entry.getKey());
                CUSTOM.keySet().removeIf(k -> normalize(k).equals(key));
                CUSTOM.put(entry.getKey(), entry.getValue());
            }
            saveCustom();
            stepIndex = 0;
            return say("Imported " + imported.size() + " room routes from " + source + ". They replace your routes for those rooms (/s2 route clear in a room goes back to Stella's).", ChatFormatting.GREEN);
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

    private static Map<String, List<Step>> parseAny(String json) {
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
                addAll(sj, "enderpearls", Type.PEARL, step);
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

    private static void addAll(JsonObject step, String key, Type type, Step into) {
        if (!step.has(key) || !step.get(key).isJsonArray()) return;
        for (JsonElement e : step.getAsJsonArray(key)) {
            if (!e.isJsonArray() || e.getAsJsonArray().size() < 3) continue;
            BlockPos p = pos(e.getAsJsonArray());
            if (type == null) into.line().add(p);
            else into.waypoints().add(new Waypoint(p, type, null));
        }
    }

    private static int say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
        return 1;
    }
}
