package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.JsonElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mines of Divan Metal Detector solver, ported from SkyHanni's MetalDetectorSolver (hannibal002/SkyHanni, LGPL-2.1).
 * The treasure chests sit at fixed offsets from the barrier block next to the Jade crystal (the offsets are SkyHanni's
 * MetalDetectorChests repo constant); the detector's "TREASURE: 79.2m" action bar tells how far you are from the chest,
 * which narrows the possible offsets down until one is left.
 */
public final class MetalDetectorSolver {
    private static final Pattern TREASURE = Pattern.compile("TREASURE: ([\\d,.]+)m");
    private static final Pattern FOUND = Pattern.compile("^You found .* with your Metal Detector!$");
    private static final Set<String> TOOLS = Set.of("DWARVEN_LAPIS_SWORD", "DWARVEN_EMERALD_HAMMER", "DWARVEN_GOLD_HAMMER", "DWARVEN_DIAMOND_AXE");

    private static volatile List<int[]> offsets = List.of();
    private static final List<BlockPos> predicted = new CopyOnWriteArrayList<>();
    private static BlockPos base;
    private static BlockPos ignore;
    private static long lastSearchedForBase;
    private static long lastTreasureFound;
    private static Vec3 lastPlayerPos;
    private static boolean playedPling;

    private MetalDetectorSolver() {}

    public static void init() {
        SkyHanniRepo.load("MetalDetectorChests", json -> {
            List<int[]> list = new ArrayList<>();
            for (JsonElement e : json.getAsJsonArray("locations")) {
                String[] p = e.getAsString().split(":");
                if (p.length == 3) list.add(new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())});
            }
            offsets = list;
        });
        Sky2MChat.onActionBar(message -> onActionBar(message.text()));
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MLocation.onAreaChange(area -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Sky2MWorldRender.register(MetalDetectorSolver::render);
    }

    private static void reset() {
        base = null;
        lastSearchedForBase = 0;
        predicted.clear();
        ignore = null;
        lastPlayerPos = null;
        playedPling = false;
        lastTreasureFound = 0;
    }

    private static boolean enabled() {
        DwarvenConfig.Solvers c = DwarvenMining.solvers();
        return c != null && c.metalDetector && Sky2MLocation.inCrystalHollows() && "Mines of Divan".equals(Sky2MLocation.location());
    }

    private static boolean hasAllTools(Minecraft mc) {
        Set<String> found = new java.util.HashSet<>();
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String id = Compat.neuName(stack);
            if (TOOLS.contains(id)) found.add(id);
        }
        return found.size() == TOOLS.size();
    }

    private static void onActionBar(String text) {
        if (!enabled() || predicted.size() == 1 || offsets.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        DwarvenConfig.Solvers c = DwarvenMining.solvers();
        if (c.metalDetectorStopWhenAllTools && hasAllTools(mc)) return;
        Vec3 player = mc.player.position();
        if (lastPlayerPos == null || !lastPlayerPos.equals(player)) {
            lastPlayerPos = player;
            playedPling = false;
        }
        Matcher m = TREASURE.matcher(text);
        if (!m.find()) return;
        double distance;
        try {
            distance = Double.parseDouble(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return;
        }
        if (base == null) findBase(mc);
        if (base == null) return;

        predicted.clear();
        for (int[] o : offsets) {
            BlockPos loc = base.offset(-o[0], -o[1], -o[2]);
            if (loc.equals(ignore)) {
                ignore = null;
                return;
            }
            double d = player.distanceTo(new Vec3(loc.getX(), loc.getY() + 1, loc.getZ()));
            if (Math.round(d * 10) / 10.0 == distance) {
                if (predicted.isEmpty() && !playedPling && c.metalDetectorSound) {
                    Sky2MAlerts.ding();
                    playedPling = true;
                }
                predicted.add(loc);
            }
        }
        if (predicted.size() == 1) {
            say("Found a treasure chest location.", ChatFormatting.GREEN);
        } else if (System.currentTimeMillis() - lastTreasureFound >= 500) {
            if (predicted.isEmpty()) {
                say("No chests found. Try standing still with the metal detector in a different spot.", ChatFormatting.YELLOW);
            } else {
                say(predicted.size() + " possible locations found. Please try standing still with the metal detector in a different spot.", ChatFormatting.YELLOW);
            }
        }
    }

    private static void onChat(String text) {
        if (!enabled() || !FOUND.matcher(text).matches()) return;
        playedPling = false;
        predicted.clear();
        long now = System.currentTimeMillis();
        DwarvenConfig.Solvers c = DwarvenMining.solvers();
        if (c.metalDetectorTime && lastTreasureFound > 0) {
            say("You found the treasure in " + (now - lastTreasureFound) / 1000 + " seconds.", ChatFormatting.GREEN);
        }
        lastTreasureFound = now;
    }

    private static void tick() {
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (predicted.size() == 1) {
            BlockPos p = predicted.get(0);
            if (mc.player.position().distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= 25) {
                ignore = p;
                predicted.clear();
            }
        } else if (predicted.isEmpty() && ignore != null
            && mc.player.position().distanceToSqr(ignore.getX() + 0.5, ignore.getY() + 0.5, ignore.getZ() + 0.5) > 100) {
            ignore = null;
        }
    }

    /** Finds the quartz stairs with a barrier 13 blocks above, then the barrier with the highest x, y and z next to it. */
    private static void findBase(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastSearchedForBase < 15_000) return;
        lastSearchedForBase = now;
        BlockPos origin = mc.player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int i = -50; i < 50; i++) {
            for (int j = 30; j >= -30; j--) {
                for (int k = -50; k < 50; k++) {
                    pos.set(origin.getX() + i, origin.getY() + j, origin.getZ() + k);
                    if (!mc.level.getBlockState(pos).is(Blocks.QUARTZ_STAIRS)) continue;
                    above.set(pos.getX(), pos.getY() + 13, pos.getZ());
                    if (mc.level.getBlockState(above).is(Blocks.BARRIER)) {
                        base = highestBarrier(mc, above.immutable());
                        return;
                    }
                }
            }
        }
    }

    private static BlockPos highestBarrier(Minecraft mc, BlockPos start) {
        BlockPos current = start;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (BlockPos next : new BlockPos[]{current.east(), current.above(), current.south()}) {
                if (mc.level.getBlockState(next).is(Blocks.BARRIER)) {
                    current = next;
                    changed = true;
                }
            }
        }
        return current;
    }

    private static void render(PrimitiveCollector collector) {
        if (!enabled() || predicted.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float[] gold = {1f, 0.67f, 0f};
        float[] red = {1f, 0.2f, 0.2f};
        float[] white = {1f, 1f, 1f};
        for (BlockPos p : predicted) {
            collector.submitOutlinedBox(p, gold, 3f, true);
            collector.submitFilledBoxWithBeaconBeam(p, red, 0.4f, true);
            collector.submitLineFromCursor(Vec3.atCenterOf(p), white, 1f, 3f);
            double d = mc.player.position().distanceTo(Vec3.atCenterOf(p));
            collector.submitText(Component.literal(String.format(Locale.US, "Treasure: %.1fm", d)).withStyle(ChatFormatting.YELLOW),
                Vec3.atCenterOf(p).add(0, 1.2, 0), 1.5f, true);
        }
    }

    private static void say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
    }
}
