package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floor 7 device solvers, ported from Odin's SimonSays, ArrowAlign and ArrowsDevice
 * (https://github.com/odtheking/Odin, BSD-3-Clause).
 * <ul>
 *     <li>Simon Says: boxes the buttons in order (green, gold, then red) with each button's number, and optionally
 *     blocks clicks on any button but the next one.</li>
 *     <li>Arrow Align: clicks left on each frame, and blocks clicks on frames that are already right.</li>
 *     <li>Sharp Shooter (i4): the lit target, blocks already hit, and optionally the best places to aim.</li>
 * </ul>
 * Shift always lets a blocked click through.
 */
public final class OdinDevices {
    private OdinDevices() {}

    private static FeatureConfigs.Terminals config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.terminals;
    }

    /**
     * The F7 device solvers only react to blocks at their exact boss-room coordinates, so being in a dungeon is enough;
     * waiting for the floor and Skyblocker's boss-room detection too made Simon Says miss the lanterns.
     */
    private static boolean inF7Boss() {
        return com.epic60869.sky2m.features.core.Sky2MLocation.inDungeon();
    }

    private static boolean sneaking() {
        var player = Minecraft.getInstance().player;
        return player != null && player.isShiftKeyDown();
    }

    private static float[] colour(String value, float[] fallback) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f};
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        WorldEvents.BLOCK_STATE_UPDATE.register(OdinDevices::onBlockUpdate);
        ClientTickEvents.END_CLIENT_TICK.register(OdinDevices::arrowAlignTick);
        Sky2MChat.onChat(message -> onChat(message.text()));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) return InteractionResult.PASS;
            return onUseBlock(hit.getBlockPos()) ? InteractionResult.FAIL : InteractionResult.PASS;
        });
        Sky2MWorldRender.register(OdinDevices::render);
    }

    private static void reset() {
        resetSimon();
        marked.clear();
        target = null;
        aims = List.of();
        i4Complete = false;
        clicksRemaining.clear();
        frameRotations = null;
        solution = null;
    }

    private static void onChat(String text) {
        if (text.equals("[BOSS] Goldor: Who dares trespass into my domain?")) resetSimon();
        Matcher m = DEVICE_COMPLETE.matcher(text);
        if (m.matches() && !i4Complete && inI4Room()) {
            var player = Minecraft.getInstance().player;
            if (player != null && m.group(1).equals(player.getGameProfile().name())) i4Done();
        }
    }

    private static void onBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        FeatureConfigs.Terminals config = config();
        if (config == null || !config.odinDevices || !inF7Boss()) return;
        simonBlockUpdate(pos, old, updated);
        i4BlockUpdate(pos, old, updated);
    }

    /** @return true to cancel the click. */
    private static boolean onUseBlock(BlockPos pos) {
        FeatureConfigs.Terminals config = config();
        if (config == null || !config.odinDevices || !inF7Boss()) return false;
        if (pos.getX() == 110 && pos.getY() >= 120 && pos.getY() <= 123 && pos.getZ() >= 92 && pos.getZ() <= 95) {
            BlockPos lantern = pos.east();
            BlockPos needed = clickNeeded < clickOrder.size() ? clickOrder.get(clickNeeded) : null;
            if (config.ssBlockWrong && !sneaking() && needed != null && !lantern.equals(needed)) {
                Sky2MAlerts.play(SoundEvents.BLAZE_HURT, 1f);
                return true;
            }
            if (config.ssAnnounce && !clickOrder.isEmpty() && lantern.equals(clickOrder.getLast())) {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.sendCommand("pc SS " + clickOrder.size() + "/5");
            }
            // Move on to the next button straight away: waiting for the server to show the press blocked a quick
            // correct click on the next button.
            if (lantern.equals(needed)) clickNeeded++;
        }
        return false;
    }

    /**
     * Right-clicking an entity, from Sky2MEntityUseMixin (Minecraft.startUseItem, just before the interaction, like
     * Odin's EntityInteractEvent). Called once per click.
     * @return true to cancel the click.
     */
    public static boolean onUseEntity(Entity entity) {
        FeatureConfigs.Terminals config = config();
        if (config == null || !config.odinDevices || !Sky2MLocation.inDungeon()) return false;
        return arrowAlignClick(entity, config);
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.Terminals config = config();
        if (config == null || !config.odinDevices) return;
        renderArrowAlign(collector);
        if (!inF7Boss()) return;
        renderSimon(collector, config);
        renderI4(collector, config);
    }

    // ----- Simon Says -----

    private static final BlockPos START_BUTTON = new BlockPos(110, 121, 91);
    private static final List<BlockPos> GRID = new ArrayList<>();
    static {
        for (int y = 120; y <= 123; y++) for (int z = 92; z <= 95; z++) GRID.add(new BlockPos(110, y, z));
    }
    /** The lanterns in the order they flashed; each round flashes the whole sequence again, one longer. */
    private static final List<BlockPos> clickOrder = new ArrayList<>();
    /** How many buttons of this round have been pressed. */
    private static int clickNeeded;

    private static void resetSimon() {
        clickOrder.clear();
        clickNeeded = 0;
    }

    private static void simonBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        if (pos.equals(START_BUTTON) && updated.is(Blocks.STONE_BUTTON) && updated.getValue(BlockStateProperties.POWERED)) {
            resetSimon();
            return;
        }
        if (pos.getY() < 120 || pos.getY() > 123 || pos.getZ() < 92 || pos.getZ() > 95) return;
        if (pos.getX() == 111) {
            // A lantern flashing: the next button of the sequence (the round replays the ones already known).
            if (updated.is(Blocks.OBSIDIAN) && old.is(Blocks.SEA_LANTERN) && !clickOrder.contains(pos)) {
                clickOrder.add(pos.immutable());
            }
        } else if (pos.getX() == 110) {
            var level = Minecraft.getInstance().level;
            if (updated.isAir()) {
                int air = 0;
                if (level != null) for (BlockPos p : GRID) if (level.getBlockState(p).isAir()) air++;
                if (air > 8) resetSimon();
            } else if (old.is(Blocks.STONE_BUTTON) && updated.is(Blocks.STONE_BUTTON) && updated.getValue(BlockStateProperties.POWERED)) {
                // The server showing a press: never go back (a click may already have moved on), only forward.
                int index = clickOrder.indexOf(pos.east());
                if (index >= 0) clickNeeded = Math.max(clickNeeded, index + 1);
                // The round is done: the next one flashes the whole sequence again.
                if (!clickOrder.isEmpty() && clickNeeded >= clickOrder.size()) resetSimon();
            }
        }
    }

    private static void renderSimon(PrimitiveCollector collector, FeatureConfigs.Terminals config) {
        if (!config.simonSays) return;
        if (clickNeeded >= clickOrder.size()) return;
        float[] first = colour(config.ssFirstColor, new float[]{0.33f, 1f, 0.33f, 0.5f});
        float[] second = colour(config.ssSecondColor, new float[]{1f, 0.67f, 0f, 0.5f});
        float[] third = colour(config.ssThirdColor, new float[]{1f, 0.33f, 0.33f, 0.5f});
        for (int i = clickNeeded; i < clickOrder.size(); i++) {
            BlockPos p = clickOrder.get(i);
            float[] c = i == clickNeeded ? first : i == clickNeeded + 1 ? second : third;
            AABB box = new AABB(p.getX() + 0.05, p.getY() + 0.37, p.getZ() + 0.3, p.getX() - 0.15, p.getY() + 0.63, p.getZ() + 0.7);
            collector.submitFilledBox(box, c, c[3], true);
            collector.submitOutlinedBox(box, c, 1f, 2f, true);
            // Its place in the sequence, so every button past the first two can be told apart too.
            collector.submitText(Component.literal(String.valueOf(i + 1)).withStyle(i == clickNeeded ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                new Vec3(p.getX() - 0.07, p.getY() + 0.5, p.getZ() + 0.5), 1f, true);
        }
    }

    // ----- Arrow Align -----

    private static final BlockPos FRAME_CORNER = new BlockPos(-2, 120, 75);
    private static final BlockPos ALIGN_CENTER = new BlockPos(0, 120, 77);
    private static final int[][] SOLUTIONS = {
        {7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1},
        {-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1},
        {7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3},
        {5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1},
        {5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
        {7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
        {-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1},
        {-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1},
        {-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1}
    };
    private static final Map<Integer, Long> recentClicks = new HashMap<>();
    private static final Map<Integer, Integer> clicksRemaining = new HashMap<>();
    private static int[] frameRotations;
    private static int[] solution;

    private static BlockPos framePos(int index) {
        return FRAME_CORNER.offset(0, index % 5, index / 5);
    }

    private static int clicksNeeded(int current, int target) {
        return (8 - current + target) % 8;
    }

    private static void arrowAlignTick(Minecraft mc) {
        FeatureConfigs.Terminals config = config();
        clicksRemaining.clear();
        // Only near the board, whose spot is only in the F7/M7 boss, so it works even when the floor can't be read.
        if (config == null || !config.odinDevices || !config.arrowAlign || mc.player == null || mc.level == null || !Sky2MLocation.inDungeon()) return;
        if (mc.player.blockPosition().distSqr(ALIGN_CENTER) > 200) {
            frameRotations = null;
            solution = null;
            return;
        }
        List<ItemFrame> frames = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof ItemFrame f && f.getItem().is(Items.ARROW)) frames.add(f);
        int[] rotations = new int[25];
        for (int i = 0; i < 25; i++) {
            Long clicked = recentClicks.get(i);
            if (clicked != null && System.currentTimeMillis() - clicked < 1000 && frameRotations != null) {
                rotations[i] = frameRotations[i];
                continue;
            }
            rotations[i] = -1;
            BlockPos pos = framePos(i);
            for (ItemFrame f : frames) if (f.blockPosition().equals(pos)) rotations[i] = f.getRotation();
        }
        frameRotations = rotations;
        for (int[] candidate : SOLUTIONS) {
            boolean fits = true;
            for (int i = 0; i < 25; i++) {
                if ((candidate[i] == -1 || rotations[i] == -1) && candidate[i] != rotations[i]) {
                    fits = false;
                    break;
                }
            }
            if (!fits) continue;
            solution = candidate;
            for (int i = 0; i < 25; i++) {
                if (rotations[i] == -1) continue;
                int needed = clicksNeeded(rotations[i], candidate[i]);
                if (needed != 0) clicksRemaining.put(i, needed);
            }
            // Only the first layout that fits: mixing in another one's counts let clicks through on frames that were already right.
            break;
        }
    }

    private static boolean arrowAlignClick(Entity entity, FeatureConfigs.Terminals config) {
        if (!config.arrowAlign || !(entity instanceof ItemFrame frame) || !frame.getItem().is(Items.ARROW) || frameRotations == null) return false;
        BlockPos pos = frame.blockPosition();
        int index = (pos.getY() - FRAME_CORNER.getY()) + (pos.getZ() - FRAME_CORNER.getZ()) * 5;
        if (pos.getX() != FRAME_CORNER.getX() || index < 0 || index > 24 || frameRotations[index] == -1) return false;
        // One right click can reach this twice (interact at + interact): the second gets the same answer as the first
        // and isn't counted again, so the prediction doesn't run ahead of the frame.
        Long last = recentClicks.get(index);
        if (last != null && System.currentTimeMillis() - last < 30) return false;
        if (config.arrowAlignBlockWrong && !sneaking() && (solution == null || !clicksRemaining.containsKey(index))) return true;
        recentClicks.put(index, System.currentTimeMillis());
        frameRotations[index] = (frameRotations[index] + 1) % 8;
        if (solution != null && clicksNeeded(frameRotations[index], solution[index]) == 0) clicksRemaining.remove(index);
        else if (solution != null) clicksRemaining.put(index, clicksNeeded(frameRotations[index], solution[index]));
        return false;
    }

    private static void renderArrowAlign(PrimitiveCollector collector) {
        for (var entry : clicksRemaining.entrySet()) {
            int needed = entry.getValue();
            if (needed == 0) continue;
            ChatFormatting colour = needed < 3 ? ChatFormatting.GREEN : needed < 5 ? ChatFormatting.GOLD : ChatFormatting.RED;
            collector.submitText(Component.literal(String.valueOf(needed)).withStyle(colour),
                Vec3.atCenterOf(framePos(entry.getKey())).add(-0.3, 0.1, 0), 1f, false);
        }
    }

    // ----- Sharp Shooter (i4) -----

    private static final Pattern DEVICE_COMPLETE = Pattern.compile("^(.{1,16}) completed a device! \\((\\d)/(\\d)\\)$");
    private static final AABB I4_ROOM = new AABB(20, 100, 30, 89, 151, 51);
    private static final List<BlockPos> I4_BLOCKS = List.of(
        new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
        new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
        new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50));
    private static final Set<BlockPos> marked = new LinkedHashSet<>();
    private static BlockPos target;
    private static boolean i4Complete;
    private static List<Vec3> aims = List.of();

    private static boolean inI4Room() {
        var player = Minecraft.getInstance().player;
        return player != null && I4_ROOM.contains(player.position());
    }

    private static void i4BlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        if (!I4_BLOCKS.contains(pos)) return;
        var blueClay = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE);
        if (old.is(Blocks.EMERALD_BLOCK) && updated.is(blueClay)) {
            marked.add(pos.immutable());
            if (pos.equals(target)) target = null;
            aims = aimPositions(pos);
        } else if (old.is(blueClay) && updated.is(Blocks.EMERALD_BLOCK)) {
            marked.remove(pos);
            target = pos.immutable();
            aims = aimPositions(pos);
        }
        // "Active" on the device's armor stand also means it is done; the chat message covers it too.
    }

    private static void i4Done() {
        i4Complete = true;
        FeatureConfigs.Terminals config = config();
        if (config != null && config.i4CompleteAlert) {
            Sky2MAlerts.title(Component.literal("Device Complete").withStyle(ChatFormatting.GREEN), Component.empty());
        }
        marked.clear();
        target = null;
        aims = List.of();
    }

    /** Odin's aim helper: aim between two blocks two apart to hit both, starting with the lit target. */
    private static List<Vec3> aimPositions(BlockPos lit) {
        List<BlockPos[]> pairs = new ArrayList<>();
        for (int i = 0; i < I4_BLOCKS.size(); i++) {
            for (int j = i + 1; j < I4_BLOCKS.size(); j++) {
                BlockPos a = I4_BLOCKS.get(i);
                BlockPos b = I4_BLOCKS.get(j);
                if (Math.abs(a.getX() - b.getX()) == 2 && a.getY() == b.getY() && a.getZ() == b.getZ()) pairs.add(new BlockPos[]{a, b});
            }
        }
        record Aim(Vec3 pos, Set<BlockPos> covers) {}
        List<Aim> withTarget = new ArrayList<>();
        List<Aim> others = new ArrayList<>();
        for (BlockPos[] pair : pairs) {
            Set<BlockPos> covers = new HashSet<>();
            for (BlockPos b : pair) if (!marked.contains(b)) covers.add(b);
            if (covers.isEmpty()) continue;
            Vec3 mid = new Vec3((pair[0].getX() + pair[1].getX()) / 2.0 + 0.5, pair[0].getY() + 0.5, pair[0].getZ() + 0.5);
            (pair[0].equals(lit) || pair[1].equals(lit) ? withTarget : others).add(new Aim(mid, covers));
        }
        if (withTarget.isEmpty()) return List.of();
        Aim first = withTarget.stream().max((a, b) -> Integer.compare(a.covers().size(), b.covers().size())).get();
        List<Aim> result = new ArrayList<>(List.of(first));
        Set<BlockPos> covered = new HashSet<>(first.covers());
        for (int n = 0; n < 2; n++) {
            Aim best = null;
            int bestNew = -1, bestSize = -1;
            double bestDist = Double.MAX_VALUE;
            for (Aim a : others) {
                if (result.contains(a)) continue;
                int fresh = (int) a.covers().stream().filter(b -> !covered.contains(b)).count();
                double dist = result.getLast().pos().distanceTo(a.pos());
                if (fresh > bestNew || (fresh == bestNew && (a.covers().size() > bestSize || (a.covers().size() == bestSize && dist < bestDist)))) {
                    best = a;
                    bestNew = fresh;
                    bestSize = a.covers().size();
                    bestDist = dist;
                }
            }
            if (best == null) break;
            result.add(best);
            covered.addAll(best.covers());
        }
        return result.stream().map(Aim::pos).toList();
    }

    private static void renderI4(PrimitiveCollector collector, FeatureConfigs.Terminals config) {
        if (!config.targetPractice || i4Complete) return;
        float[] markedColour = {0.33f, 1f, 1f, 0.5f};
        float[] targetColour = {1f, 0.33f, 1f, 0.5f};
        for (BlockPos p : marked) collector.submitFilledBox(p, markedColour, markedColour[3], true);
        if (target != null) {
            collector.submitFilledBox(target, targetColour, targetColour[3], true);
            collector.submitOutlinedBox(target, targetColour, 3f, true);
        }
        if (config.i4AimPositions) {
            float[][] colours = {{0.33f, 1f, 0.33f}, {1f, 0.67f, 0f}, {1f, 0.33f, 0.33f}};
            for (int i = 0; i < Math.min(3, aims.size()); i++) {
                Vec3 a = aims.get(i);
                collector.submitFilledBox(new AABB(a.x - 0.5, a.y - 0.5, a.z - 0.1, a.x + 0.5, a.y + 0.5, a.z + 0.9), colours[i], 0.5f, true);
            }
        }
    }
}
