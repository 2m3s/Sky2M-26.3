package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2ic;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon puzzle solvers ported from Odin (https://github.com/odtheking/Odin, BSD-3-Clause):
 * IceFillSolver, BoulderSolver, BeamsSolver, WeirdosSolver, QuizSolver, TPMazeSolver, WaterSolver and BlazeSolver,
 * with Odin's puzzle data files. Room coordinates are relative to the blue terracotta in one of the room's roof
 * corners, rotated by that corner (Odin's clayPos and RoomRotation). Tic Tac Toe and Silverfish have no Odin solver
 * and keep Sky2M's.
 */
public final class OdinPuzzleSolvers {
    private static final String ICE_FILL = "ice-path", BOULDER = "boxes-room", BEAMS = "creeper-room", WEIRDOS = "three-chests",
        QUIZ = "trivia-room", TP_MAZE = "teleport-pad-room", WATER = "water-puzzle", BLAZE_HIGH = "blaze-room-1-high", BLAZE_LOW = "blaze-room-1-low";

    // ----- Rooms -----

    /** Odin's RoomRotation: offsets from the room centre to the blue terracotta corner. */
    private enum Rotation {
        NORTH(15, 15), SOUTH(-15, -15), WEST(15, -15), EAST(-15, 15);

        final int dx, dz;

        Rotation(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        /** Odin's rotateAroundNorth (room to world). */
        BlockPos toWorld(BlockPos p) {
            return switch (this) {
                case NORTH -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
                case WEST -> new BlockPos(-p.getZ(), p.getY(), p.getX());
                case SOUTH -> p;
                case EAST -> new BlockPos(p.getZ(), p.getY(), -p.getX());
            };
        }

        /** Odin's rotateToNorth (world to room). */
        BlockPos toRoom(BlockPos p) {
            return switch (this) {
                case NORTH -> new BlockPos(-p.getX(), p.getY(), -p.getZ());
                case WEST -> new BlockPos(p.getZ(), p.getY(), -p.getX());
                case SOUTH -> p;
                case EAST -> new BlockPos(-p.getZ(), p.getY(), p.getX());
            };
        }
    }

    private record Frame(BlockPos clay, Rotation rotation) {
        BlockPos real(int x, int y, int z) {
            return rotation.toWorld(new BlockPos(x, y, z)).offset(clay.getX(), 0, clay.getZ());
        }

        BlockPos relative(BlockPos world) {
            return rotation.toRoom(world.offset(-clay.getX(), 0, -clay.getZ()));
        }
    }

    private static Room room;
    private static Frame frame;

    // ----- Data -----

    private static final List<List<List<BlockPos>>> ICE_IDENTIFIERS = new ArrayList<>(), ICE_EASY = new ArrayList<>(), ICE_HARD = new ArrayList<>();
    private static final Map<String, List<int[]>> BOULDER_SOLUTIONS = new HashMap<>();
    private static final List<int[]> BEAM_PAIRS = new ArrayList<>();
    private static final Map<String, List<String>> QUIZ_ANSWERS = new LinkedHashMap<>();
    private static JsonObject waterSolutions;

    // ----- State -----

    private static final List<Vec3> icePath = new ArrayList<>();
    private static final List<AABB[]> boulderClicks = new ArrayList<>(); // [box, click block]
    private static final List<BlockPos[]> beamPairs = new ArrayList<>();
    private static BlockPos weirdosCorrect;
    private static final Set<BlockPos> weirdosWrong = new HashSet<>();
    private static final BlockPos[] quizOptions = new BlockPos[3];
    private static final boolean[] quizCorrect = new boolean[3];
    private static List<String> quizAnswers;
    private static final List<BlockPos> tpPads = new ArrayList<>();
    private static final Set<BlockPos> tpVisited = new HashSet<>();
    private static List<BlockPos> tpCorrect = new ArrayList<>();
    private static BlockPos tpBest;
    private static final List<ArmorStand> blazes = new ArrayList<>();
    /** The quiz room this run, once you've been in it. */
    private static Room quizRoom;
    private static int waterPattern = -1;
    private static final Map<Lever, double[]> waterSolution = new EnumMap<>(Lever.class);
    private static final Map<Lever, Integer> leverClicks = new EnumMap<>(Lever.class);
    private static long serverTicks;
    private static long waterOpenedTick = -1;
    private static int ticks;

    private static final Pattern WEIRDOS_NPC = Pattern.compile("^\\[NPC] (.+): (.+).?$");
    private static final List<Pattern> WEIRDOS_RIGHT = patterns(
        "The reward is not in my chest!", "At least one of them is lying, and the reward is not in .+'s chest.?",
        "My chest doesn't have the reward. We are all telling the truth.?", "My chest has the reward and I'm telling the truth!",
        "The reward isn't in any of our chests.?", "Both of them are telling the truth. Also, .+ has the reward in their chest.?");
    private static final List<Pattern> WEIRDOS_WRONG = patterns(
        "One of us is telling the truth!", "They are both telling the truth. The reward isn't in .+'s chest.", "We are all telling the truth!",
        ".+ is telling the truth and the reward is in his chest.", "My chest doesn't have the reward. At least one of the others is telling the truth!",
        "One of the others is lying.", "They are both telling the truth, the reward is in .+'s chest.", "They are both lying, the reward is in my chest!",
        "The reward is in my chest.", "The reward is not in my chest. They are both lying.", ".+ is telling the truth.", "My chest has the reward.");
    /** "[Lv15] ♨ Blaze 20,000/20,000❤": the mob-type symbol varies, so only the "Blaze hp/max❤" part is matched. */
    private static final Pattern BLAZE_HP = Pattern.compile("\\bBlaze [\\d,]+/([\\d,]+)❤");
    private static final int[][] TP_PADS = {
        {4, 69, 12}, {4, 69, 6}, {10, 69, 12}, {10, 69, 6}, {4, 69, 20}, {4, 69, 14}, {10, 69, 20}, {10, 69, 14},
        {4, 69, 28}, {4, 69, 22}, {10, 69, 28}, {10, 69, 22}, {12, 69, 28}, {12, 69, 22}, {18, 69, 28}, {18, 69, 22},
        {20, 69, 28}, {20, 69, 22}, {26, 69, 28}, {26, 69, 22}, {26, 69, 20}, {26, 69, 14}, {20, 69, 20}, {20, 69, 14},
        {26, 69, 12}, {26, 69, 6}, {20, 69, 12}, {20, 69, 6}, {15, 69, 14}, {15, 69, 12}};
    private static final float[][] BEAM_COLOURS = {
        {1f, 0.67f, 0f}, {0.33f, 1f, 0.33f}, {1f, 0.33f, 1f}, {0f, 0.67f, 0.67f}, {1f, 1f, 0.33f}, {0.67f, 0f, 0f}, {1f, 1f, 1f}, {0.67f, 0f, 0.67f}};

    private enum Lever {
        COAL(20, 61, 10, "coal_block"), GOLD(20, 61, 15, "gold_block"), QUARTZ(20, 61, 20, "quartz_block"),
        DIAMOND(10, 61, 20, "diamond_block"), EMERALD(10, 61, 15, "emerald_block"), CLAY(10, 61, 10, "hardened_clay"),
        WATER(15, 60, 5, "water");

        final int x, y, z;
        final String key;

        Lever(int x, int y, int z, String key) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = key;
        }

        BlockPos pos() {
            return frame == null ? BlockPos.ZERO : frame.real(x, y, z);
        }
    }

    private OdinPuzzleSolvers() {}

    private static FeatureConfigs.Puzzles config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.puzzles;
    }

    private static boolean in(String name) {
        return room != null && frame != null && name.equals(room.getName());
    }

    private static boolean inQuiz() {
        return room != null && QUIZ.equals(room.getName());
    }

    /** The blaze puzzle needs no room frame (its room has no blue terracotta roof corner to find one by). */
    private static boolean inBlaze() {
        return room != null && (BLAZE_HIGH.equals(room.getName()) || BLAZE_LOW.equals(room.getName()));
    }

    public static void init() {
        loadData();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        ServerTickCallback.EVENT.register(() -> serverTicks++);
        ClientTickEvents.END_CLIENT_TICK.register(OdinPuzzleSolvers::tick);
        Sky2MChat.onChat(message -> onChat(message.text()));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            boulderClicks.removeIf(b -> BlockPos.containing(b[1].getCenter()).equals(pos));
            if (in(WATER) && !waterSolution.isEmpty()) {
                for (Lever lever : Lever.values()) {
                    if (!lever.pos().equals(pos)) continue;
                    if (lever == Lever.WATER && waterOpenedTick < 0) waterOpenedTick = serverTicks;
                    leverClicks.merge(lever, 1, Integer::sum);
                }
            }
            return InteractionResult.PASS;
        });
        Sky2MWorldRender.register(OdinPuzzleSolvers::render);
    }

    private static void reset() {
        room = null;
        frame = null;
        quizRoom = null;
        resetRoomState();
        resetQuiz();
    }

    /**
     * The quiz is kept apart from the other puzzles: Oruo asks the next question while you may be outside his room,
     * and walking out and back in mustn't forget the answer.
     */
    private static void resetQuiz() {
        java.util.Arrays.fill(quizOptions, null);
        java.util.Arrays.fill(quizCorrect, false);
        quizAnswers = null;
    }

    private static void resetRoomState() {
        icePath.clear();
        boulderClicks.clear();
        beamPairs.clear();
        weirdosCorrect = null;
        weirdosWrong.clear();
        tpPads.clear();
        tpVisited.clear();
        tpCorrect = new ArrayList<>();
        tpBest = null;
        blazes.clear();
        waterPattern = -1;
        waterSolution.clear();
        leverClicks.clear();
        waterOpenedTick = -1;
    }

    // ----- Room tracking -----

    private static void tick(Minecraft mc) {
        FeatureConfigs.Puzzles config = config();
        if (config == null || mc.level == null || !Sky2MLocation.inDungeon() || DungeonManager.isInBoss()) {
            if (room != null) reset();
            return;
        }
        Room current = DungeonManager.getCurrentRoom();
        if (current == null || !current.isMatched() || current.getName() == null) return;
        if (current != room) {
            room = current;
            frame = findFrame(mc.level, current);
            resetRoomState();
            if (inQuiz() && current != quizRoom) {
                // Another quiz room is another run; the answers read so far (maybe before you got here) are this one's.
                if (quizRoom != null) resetQuiz();
                quizRoom = current;
                // The quiz uses Skyblocker's room transform, so it works even when the roof corner block isn't found.
                quizOptions[0] = current.relativeToActual(new BlockPos(20, 70, 6));
                quizOptions[1] = current.relativeToActual(new BlockPos(15, 70, 9));
                quizOptions[2] = current.relativeToActual(new BlockPos(10, 70, 6));
            }
            if (frame != null) onRoomEnter(mc.level, config);
        }
        if (++ticks % 5 == 0 && config.blaze && inBlaze()) scanBlazes(mc);
        if (frame == null) return;
        if (ticks % 5 == 0) {
            if (config.waterBoard && in(WATER) && waterPattern < 0) scanWater(mc.level, config.waterOptimized);
            if (config.creeperBeams && in(BEAMS)) scanBeams(mc.level);
        }
    }

    private static void onRoomEnter(ClientLevel level, FeatureConfigs.Puzzles config) {
        switch (room.getName()) {
            case ICE_FILL -> solveIceFill(level, config.iceFillOptimized);
            case BOULDER -> solveBoulder(level);
            case BEAMS -> scanBeams(level);
            case TP_MAZE -> {
                for (int[] p : TP_PADS) tpPads.add(frame.real(p[0], p[1], p[2]));
            }
            default -> {}
        }
    }

    /** Finds the blue terracotta roof corner of a 1x1 room. */
    private static Frame findFrame(ClientLevel level, Room room) {
        for (Vector2ic segment : room.getSegments()) {
            int cx = segment.x() + 15, cz = segment.y() + 15;
            for (Rotation rotation : Rotation.values()) {
                int x = cx + rotation.dx, z = cz + rotation.dz;
                for (int y = 160; y >= 12; y--) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (state.isAir() || state.is(Blocks.GOLD_BLOCK)) continue;
                    if (state.is(Blocks.DYED_TERRACOTTA.blue())) return new Frame(new BlockPos(x, y, z), rotation);
                    break;
                }
            }
        }
        return null;
    }

    // ----- Ice Fill -----

    private static void solveIceFill(ClientLevel level, boolean optimized) {
        List<List<List<BlockPos>>> patterns = optimized ? ICE_HARD : ICE_EASY;
        for (int floor = 0; floor < ICE_IDENTIFIERS.size(); floor++) {
            List<List<BlockPos>> identifiers = ICE_IDENTIFIERS.get(floor);
            for (int i = 0; i < identifiers.size(); i++) {
                BlockPos a = identifiers.get(i).get(0), b = identifiers.get(i).get(1);
                if (level.getBlockState(frame.real(a.getX(), a.getY(), a.getZ())).isAir() && !level.getBlockState(frame.real(b.getX(), b.getY(), b.getZ())).isAir()) {
                    for (BlockPos p : patterns.get(floor).get(i)) icePath.add(Vec3.atLowerCornerOf(frame.real(p.getX(), p.getY(), p.getZ())).add(0.5, 0.1, 0.5));
                    break;
                }
            }
        }
    }

    // ----- Boulder -----

    private static void solveBoulder(ClientLevel level) {
        StringBuilder key = new StringBuilder();
        for (int z = 24; z >= 9; z -= 3) {
            for (int x = 24; x >= 6; x -= 3) key.append(level.getBlockState(frame.real(x, 66, z)).isAir() ? '0' : '1');
        }
        List<int[]> solution = BOULDER_SOLUTIONS.get(key.toString());
        if (solution == null) return;
        for (int[] s : solution) boulderClicks.add(new AABB[]{new AABB(frame.real(s[0], 65, s[1])), new AABB(frame.real(s[2], 65, s[3]))});
    }

    // ----- Creeper Beams -----

    private static void scanBeams(ClientLevel level) {
        beamPairs.clear();
        for (int[] p : BEAM_PAIRS) {
            BlockPos a = frame.real(p[0], p[1], p[2]), b = frame.real(p[3], p[4], p[5]);
            if (level.getBlockState(a).is(Blocks.SEA_LANTERN) && level.getBlockState(b).is(Blocks.SEA_LANTERN)) beamPairs.add(new BlockPos[]{a, b});
        }
    }

    // ----- Blaze -----

    private static void scanBlazes(Minecraft mc) {
        Map<ArmorStand, Integer> hp = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            Matcher m = BLAZE_HP.matcher(ChatFormatting.stripFormatting(stand.getCustomName().getString()));
            if (!m.find()) continue;
            try {
                hp.put(stand, Integer.parseInt(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {}
        }
        blazes.clear();
        blazes.addAll(hp.keySet());
        Comparator<ArmorStand> byHp = Comparator.comparingInt(hp::get);
        // Blazes down in the room (below y 69) are shot highest first, ones up high lowest first (like Skyblocker),
        // so the order doesn't hang on which of the two room names is which.
        double y = blazes.stream().mapToDouble(Entity::getY).average().orElse(70);
        blazes.sort(y < 69 ? byHp.reversed() : byHp);
    }

    // ----- Water Board -----

    private static void scanWater(ClientLevel level, boolean optimized) {
        int[][] wool = {{15, 56, 19}, {15, 56, 18}, {15, 56, 17}, {15, 56, 16}, {15, 56, 15}}; // purple, orange, blue, green, red
        StringBuilder extended = new StringBuilder();
        for (int i = 0; i < wool.length; i++) {
            if (!level.getBlockState(frame.real(wool[i][0], wool[i][1], wool[i][2])).isAir()) extended.append(i);
        }
        if (extended.length() != 3) return;
        int pattern;
        if (is(level, frame.real(14, 77, 27), Blocks.TERRACOTTA)) pattern = 0;
        else if (is(level, frame.real(16, 78, 27), Blocks.EMERALD_BLOCK)) pattern = 1;
        else if (is(level, frame.real(14, 78, 27), Blocks.DIAMOND_BLOCK)) pattern = 2;
        else if (is(level, frame.real(14, 78, 27), Blocks.QUARTZ_BLOCK)) pattern = 3;
        else return;
        if (waterSolutions == null) return;
        JsonObject byPattern = waterSolutions.getAsJsonObject(String.valueOf(optimized));
        JsonObject byWool = byPattern == null ? null : byPattern.getAsJsonObject(String.valueOf(pattern));
        JsonObject solution = byWool == null ? null : byWool.getAsJsonObject(extended.toString());
        if (solution == null) return;
        waterPattern = pattern;
        for (Lever lever : Lever.values()) {
            if (!solution.has(lever.key)) continue;
            JsonArray times = solution.getAsJsonArray(lever.key);
            double[] t = new double[times.size()];
            for (int i = 0; i < t.length; i++) t[i] = times.get(i).getAsDouble();
            waterSolution.put(lever, t);
        }
    }

    private static boolean is(ClientLevel level, BlockPos pos, Block block) {
        return level.getBlockState(pos).is(block);
    }

    // ----- Teleport Maze -----

    /**
     * Called for each teleport packet (Odin's TPMazeSolver.tpPacket). {@code before} is where you stood before the
     * teleport: Odin handles the packet before it moves you, so the pad you left counts as visited. Without that the
     * solver could send you back to the pad you just came from, round in a loop.
     */
    public static void onTeleport(ClientboundPlayerPositionPacket packet, BlockPos before) {
        FeatureConfigs.Puzzles config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || !config.teleportMaze || !in(TP_MAZE) || tpPads.isEmpty() || mc.player == null) return;
        Vec3 pos = packet.change().position();
        if (pos.x % 0.5 != 0 || pos.y != 69.5 || pos.z % 0.5 != 0) return;
        float yaw = packet.change().yRot(), pitch = packet.change().xRot();

        AABB posBox = AABB.unitCubeFromLowerCorner(pos).inflate(1, 0, 1);
        AABB beforeBox = before != null ? new AABB(before.getX(), before.getY(), before.getZ(), before.getX() + 1, before.getY() + 2, before.getZ() + 1) : mc.player.getBoundingBox();
        AABB playerBox = beforeBox.inflate(1, 0, 1);
        for (BlockPos pad : tpPads) if (posBox.intersects(new AABB(pad)) || playerBox.intersects(new AABB(pad))) tpVisited.add(pad);

        if (tpCorrect.isEmpty()) tpCorrect = new ArrayList<>(tpPads);
        tpCorrect.removeIf(p -> tpVisited.contains(p)
            || !xzInterceptable(new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 4, p.getZ() + 1).inflate(0.75, 0, 0.75), 32, pos, yaw, pitch, mc.player.getEyeHeight())
            || new AABB(p).inflate(0.5, 0, 0.5).intersects(beforeBox));

        BlockPos current = null;
        for (BlockPos pad : tpPads) if (posBox.intersects(new AABB(pad))) { current = pad; break; }
        if (current == null) return;
        int index = tpPads.indexOf(current);
        if (index >= 28) {
            tpBest = null;
            return;
        }
        int groupStart = index / 4 * 4;
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos p : tpPads.subList(groupStart, groupStart + 4)) if (!p.equals(current) && !tpVisited.contains(p)) candidates.add(p);
        tpBest = candidates.stream().filter(tpCorrect::contains).findFirst().orElseGet(() -> candidates.stream().min(Comparator.comparingDouble(p -> {
            float angle = (float) (Math.atan2(Vec3.atCenterOf(p).z - pos.z, Vec3.atCenterOf(p).x - pos.x) * 180 / Math.PI) - 90f;
            return Math.abs(Mth.wrapDegrees(angle) - Mth.wrapDegrees(yaw));
        })).orElse(null));
    }

    /** Odin's isXZInterceptable. */
    private static boolean xzInterceptable(AABB box, double range, Vec3 pos, float yaw, float pitch, double eyeHeight) {
        Vec3 start = pos.add(0, eyeHeight, 0);
        double f2 = -Math.cos(-pitch * 0.017453292f);
        Vec3 look = new Vec3(Math.sin(-yaw * 0.017453292f - Math.PI) * f2, Math.sin(-pitch * 0.017453292f), Math.cos(-yaw * 0.017453292f - Math.PI) * f2);
        Vec3 goal = start.add(look.scale(range));
        return inZ(atX(start, goal, box.minX), box) || inZ(atX(start, goal, box.maxX), box)
            || inX(atZ(start, goal, box.minZ), box) || inX(atZ(start, goal, box.maxZ), box);
    }

    private static Vec3 atX(Vec3 s, Vec3 g, double x) {
        double dx = g.x - s.x;
        if (dx * dx < 1e-8) return null;
        double t = (x - s.x) / dx;
        return t < 0 || t > 1 ? null : new Vec3(s.x + dx * t, s.y + (g.y - s.y) * t, s.z + (g.z - s.z) * t);
    }

    private static Vec3 atZ(Vec3 s, Vec3 g, double z) {
        double dz = g.z - s.z;
        if (dz * dz < 1e-8) return null;
        double t = (z - s.z) / dz;
        return t < 0 || t > 1 ? null : new Vec3(s.x + (g.x - s.x) * t, s.y + (g.y - s.y) * t, s.z + dz * t);
    }

    private static boolean inX(Vec3 v, AABB box) {
        return v != null && v.x >= box.minX && v.x <= box.maxX;
    }

    private static boolean inZ(Vec3 v, AABB box) {
        return v != null && v.z >= box.minZ && v.z <= box.maxZ;
    }

    // ----- Chat: Three Weirdos and Quiz -----

    private static void onChat(String text) {
        FeatureConfigs.Puzzles config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || mc.level == null) return;

        if (config.threeWeirdos && in(WEIRDOS)) {
            Matcher m = WEIRDOS_NPC.matcher(text);
            if (m.matches()) {
                String npc = m.group(1), msg = m.group(2);
                boolean right = WEIRDOS_RIGHT.stream().anyMatch(p -> p.matcher(msg).matches() || p.matcher(text.substring(text.indexOf(": ") + 2)).matches());
                boolean wrong = !right && WEIRDOS_WRONG.stream().anyMatch(p -> p.matcher(text.substring(text.indexOf(": ") + 2)).matches());
                if (right || wrong) {
                    for (Entity entity : mc.level.entitiesForRendering()) {
                        if (!(entity instanceof ArmorStand) || !npc.equals(ChatFormatting.stripFormatting(entity.getName().getString()))) continue;
                        BlockPos rel = frame.relative(new BlockPos((int) entity.getX() - 1, 69, (int) entity.getZ() - 1)).offset(1, 0, 0);
                        BlockPos chest = frame.real(rel.getX(), rel.getY(), rel.getZ());
                        if (right) {
                            weirdosCorrect = chest;
                            mc.player.playSound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 2f, 1f);
                        } else {
                            weirdosWrong.add(chest);
                        }
                        break;
                    }
                }
            }
        }

        // Read wherever you are in the dungeon: a question can come while you're outside the quiz room.
        if (config.trivia && Sky2MLocation.inDungeon()) {
            if (text.startsWith("[STATUE] Oruo the Omniscient: ") && text.endsWith("correctly!")) {
                if (text.contains("answered the final question")) {
                    quizAnswers = null;
                    java.util.Arrays.fill(quizCorrect, false);
                    return;
                }
                if (text.contains("answered Question #")) java.util.Arrays.fill(quizCorrect, false);
            }
            String trimmed = text.trim();
            if (!trimmed.isEmpty() && quizAnswers != null && "ⓐⓑⓒ".indexOf(trimmed.charAt(0)) >= 0) {
                for (String answer : quizAnswers) if (trimmed.endsWith(answer)) quizCorrect["ⓐⓑⓒ".indexOf(trimmed.charAt(0))] = true;
            }
            if (trimmed.equals("What SkyBlock year is it?")) {
                quizAnswers = List.of("Year " + ((System.currentTimeMillis() / 1000 - 1560276000) / 446400 + 1));
            } else {
                for (var entry : QUIZ_ANSWERS.entrySet()) if (text.contains(entry.getKey())) quizAnswers = entry.getValue();
            }
        }
    }

    // ----- Rendering -----

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.Puzzles config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || mc.level == null) return;

        if (config.iceFill && in(ICE_FILL) && icePath.size() > 1) {
            collector.submitLinesFromPoints(icePath.toArray(new Vec3[0]), new float[]{1f, 0.33f, 1f}, 1f, 3f, true);
        }
        if (config.boulder && in(BOULDER) && !boulderClicks.isEmpty()) {
            for (AABB[] b : config.boulderShowAll ? boulderClicks : boulderClicks.subList(0, 1)) {
                collector.submitFilledBox(b[0], new float[]{0.33f, 1f, 0.33f}, 0.35f, false);
                collector.submitOutlinedBox(b[0], new float[]{0.33f, 1f, 0.33f}, 3f, false);
            }
        }
        if (config.creeperBeams && in(BEAMS)) {
            for (int i = 0; i < beamPairs.size(); i++) {
                float[] colour = BEAM_COLOURS[i % BEAM_COLOURS.length];
                BlockPos[] pair = beamPairs.get(i);
                collector.submitFilledBox(pair[0], colour, 0.6f, false);
                collector.submitFilledBox(pair[1], colour, 0.6f, false);
                collector.submitLinesFromPoints(new Vec3[]{Vec3.atCenterOf(pair[0]), Vec3.atCenterOf(pair[1])}, colour, 1f, 2f, true);
            }
        }
        if (config.threeWeirdos && in(WEIRDOS)) {
            if (weirdosCorrect != null) collector.submitFilledBox(weirdosCorrect, new float[]{0.33f, 1f, 0.33f}, 0.5f, false);
            for (BlockPos wrong : weirdosWrong) collector.submitFilledBox(wrong, new float[]{1f, 0.33f, 0.33f}, 0.5f, false);
        }
        if (config.trivia && inQuiz() && quizAnswers != null) {
            for (int i = 0; i < 3; i++) {
                if (quizCorrect[i] && quizOptions[i] != null) collector.submitFilledBoxWithBeaconBeam(quizOptions[i].below(), new float[]{0.33f, 1f, 0.33f}, 0.6f, false);
            }
        }
        if (config.teleportMaze && in(TP_MAZE)) {
            for (BlockPos pad : tpPads) {
                AABB box = new AABB(pad.getX(), pad.getY(), pad.getZ(), pad.getX() + 1, pad.getY() + 0.8125, pad.getZ() + 1);
                if (tpCorrect.contains(pad)) collector.submitFilledBox(box, tpCorrect.size() == 1 ? new float[]{0.33f, 1f, 0.33f} : new float[]{1f, 0.67f, 0f}, 0.6f, true);
                else if (tpVisited.contains(pad)) collector.submitFilledBox(box, new float[]{1f, 0.33f, 0.33f}, 0.5f, false);
                else collector.submitFilledBox(box, new float[]{1f, 1f, 1f}, 0.3f, false);
            }
            if (tpBest != null) collector.submitLineFromCursor(new Vec3(tpBest.getX() + 0.5, tpBest.getY() + 0.8, tpBest.getZ() + 0.5), new float[]{0.33f, 1f, 0.33f}, 1f, 2f);
        }
        if (config.blaze && inBlaze() && !blazes.isEmpty()) {
            blazes.removeIf(b -> !b.isAlive());
            float[][] colours = {{0.33f, 1f, 0.33f}, {1f, 0.67f, 0f}, {1f, 1f, 1f}};
            for (int i = 0; i < blazes.size(); i++) {
                AABB box = blazes.get(i).getBoundingBox().inflate(0.5, 1, 0.5).move(0, -1, 0);
                float[] colour = i < 3 ? colours[i] : new float[]{1f, 1f, 1f};
                if (i < 3 || config.blazeShowAll) collector.submitOutlinedBox(box, colour, 3f, true);
                if (i > 0 && i < 3) collector.submitLinesFromPoints(new Vec3[]{blazes.get(i - 1).position(), box.getCenter()}, colour, 1f, 2f, true);
            }
        }
        if (config.waterBoard && in(WATER) && !waterSolution.isEmpty()) renderWater(collector);
    }

    private static void renderWater(PrimitiveCollector collector) {
        // Next lever: first "click now" (0s) lever by lever order, then the soonest timed click.
        Lever first = null;
        double firstTime = Double.MAX_VALUE;
        for (var entry : waterSolution.entrySet()) {
            int done = leverClicks.getOrDefault(entry.getKey(), 0);
            double[] times = entry.getValue();
            for (int i = done; i < times.length; i++) {
                double t = times[i];
                BlockPos pos = entry.getKey().pos();
                long ticksLeft = waterOpenedTick < 0 ? (long) (t * 20) : waterOpenedTick + (long) (t * 20) - serverTicks;
                String label = ticksLeft <= 0 || (waterOpenedTick < 0 && t == 0) ? "CLICK ME!" : waterOpenedTick < 0 ? t + "s" : String.format(Locale.US, "%.2fs", ticksLeft / 20f);
                ChatFormatting colour = label.equals("CLICK ME!") ? ChatFormatting.GREEN : ChatFormatting.YELLOW;
                collector.submitText(Component.literal(label).withStyle(colour, ChatFormatting.BOLD), Vec3.atLowerCornerOf(pos).add(0.5, i * 0.5 + 1.5, 0.5), 1f, true);
                double order = t == 0 ? -100 + entry.getKey().ordinal() : t;
                if (order < firstTime) {
                    firstTime = order;
                    first = entry.getKey();
                }
            }
        }
        if (first != null) collector.submitLineFromCursor(Vec3.atCenterOf(first.pos()), new float[]{0.33f, 1f, 0.33f}, 1f, 2f);
    }

    // ----- Data loading -----

    private static List<Pattern> patterns(String... regexes) {
        List<Pattern> list = new ArrayList<>();
        for (String r : regexes) list.add(Pattern.compile(r));
        return list;
    }

    private static void loadData() {
        JsonElement ice = read("ice-fill-floors.json");
        if (ice != null) {
            readPatterns(ice.getAsJsonObject().getAsJsonArray("identifier"), ICE_IDENTIFIERS);
            readPatterns(ice.getAsJsonObject().getAsJsonArray("easy"), ICE_EASY);
            readPatterns(ice.getAsJsonObject().getAsJsonArray("hard"), ICE_HARD);
        }
        JsonElement boulder = read("boulder-solutions.json");
        if (boulder != null) {
            for (var entry : boulder.getAsJsonObject().entrySet()) {
                List<int[]> clicks = new ArrayList<>();
                for (JsonElement e : entry.getValue().getAsJsonArray()) clicks.add(ints(e.getAsJsonArray()));
                BOULDER_SOLUTIONS.put(entry.getKey(), clicks);
            }
        }
        JsonElement beams = read("creeper-beams-solutions.json");
        if (beams != null) for (JsonElement e : beams.getAsJsonArray()) BEAM_PAIRS.add(ints(e.getAsJsonArray()));
        JsonElement quiz = read("quiz-answers.json");
        if (quiz != null) {
            for (var entry : quiz.getAsJsonObject().entrySet()) {
                List<String> answers = new ArrayList<>();
                for (JsonElement e : entry.getValue().getAsJsonArray()) answers.add(e.getAsString());
                QUIZ_ANSWERS.put(entry.getKey(), answers);
            }
        }
        JsonElement water = read("water-solutions.json");
        if (water != null) waterSolutions = water.getAsJsonObject();
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int i = 0; i < out.length; i++) out[i] = array.get(i).getAsInt();
        return out;
    }

    private static void readPatterns(JsonArray floors, List<List<List<BlockPos>>> into) {
        for (JsonElement floor : floors) {
            List<List<BlockPos>> patterns = new ArrayList<>();
            for (JsonElement pattern : floor.getAsJsonArray()) {
                List<BlockPos> positions = new ArrayList<>();
                for (JsonElement p : pattern.getAsJsonArray()) {
                    JsonObject o = p.getAsJsonObject();
                    positions.add(new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
                }
                patterns.add(positions);
            }
            into.add(patterns);
        }
    }

    private static JsonElement read(String name) {
        try (InputStream in = OdinPuzzleSolvers.class.getResourceAsStream("/assets/sky2m/puzzles/" + name)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read puzzle data " + name + ": " + e);
            return null;
        }
    }
}
