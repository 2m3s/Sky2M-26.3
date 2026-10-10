package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonBoss;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Odin's Terracotta Timer (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/boss/TerracottaTimer.kt): in
 * the F6 and M6 boss, a flower pot appears where a terracotta died; it respawns 15 seconds later (12 on M6). The time
 * left is shown over each pot.
 */
public final class TerracottaTimer {
    private static final List<Terracotta> spawning = new CopyOnWriteArrayList<>();
    private static Level lastLevel;

    private TerracottaTimer() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.dungeons.terracottaTimer;
    }

    public static void init() {
        WorldEvents.BLOCK_STATE_UPDATE.register(TerracottaTimer::onBlockUpdate);
        ServerTickCallback.EVENT.register(() -> spawning.removeIf(t -> (t.seconds -= 0.05f) <= 0));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != lastLevel) {
                lastLevel = mc.level;
                spawning.clear();
            }
        });
        Sky2MWorldRender.register(TerracottaTimer::render);
    }

    private static boolean inBoss() {
        return Sky2MLocation.inDungeon() && DungeonManager.getBoss() == DungeonBoss.SADAN;
    }

    private static void onBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        if (!(updated.getBlock() instanceof FlowerPotBlock) || !inBoss()) return;
        if (spawning.stream().anyMatch(t -> t.pos.equals(pos))) return;
        spawning.add(new Terracotta(pos.immutable(), Sky2MLocation.dungeonFloor().startsWith("M") ? 12f : 15f));
    }

    private static void render(PrimitiveCollector collector) {
        if (!enabled() || spawning.isEmpty() || !inBoss()) return;
        Level level = net.minecraft.client.Minecraft.getInstance().level;
        for (Terracotta t : spawning) {
            String colour = t.seconds > 5f ? "§a" : t.seconds > 2f ? "§6" : "§c";
            // Once the pot turns into a terracotta block the middle of the block is inside it: show the time on top.
            boolean pot = level == null || level.getBlockState(t.pos).getBlock() instanceof FlowerPotBlock || level.getBlockState(t.pos).isAir();
            net.minecraft.world.phys.Vec3 at = pot ? net.minecraft.world.phys.Vec3.atCenterOf(t.pos)
                : net.minecraft.world.phys.Vec3.atBottomCenterOf(t.pos.above()).add(0, 0.4, 0);
            collector.submitText(Component.literal(colour + String.format(Locale.ROOT, "%.2fs", t.seconds)), at, 2f, false);
        }
    }

    private static final class Terracotta {
        final BlockPos pos;
        float seconds;

        Terracotta(BlockPos pos, float seconds) {
            this.pos = pos;
            this.seconds = seconds;
        }
    }
}
