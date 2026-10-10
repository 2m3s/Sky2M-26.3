package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonBoss;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Odin's Spirit Bear (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/boss/SpiritBear.kt): in the F4 and
 * M4 boss, each spirit killed lights a coal block in the ring around the arena into a sea lantern. The HUD counts the
 * kills (25 on F4, 30 on M4), then, when the last block lights, counts down the 68 server ticks until the Spirit Bear
 * spawns, then shows it's alive.
 */
public final class SpiritBear {
    private static final int SPAWN_TICKS = 68;
    private static final BlockPos LAST_BLOCK = new BlockPos(7, 77, 34);
    private static final Set<BlockPos> F4_BLOCKS = Set.of(
        new BlockPos(-3, 77, 33), new BlockPos(-9, 77, 31), new BlockPos(-16, 77, 26), new BlockPos(-20, 77, 20), new BlockPos(-23, 77, 13),
        new BlockPos(-24, 77, 6), new BlockPos(-24, 77, 0), new BlockPos(-22, 77, -7), new BlockPos(-18, 77, -13), new BlockPos(-12, 77, -19),
        new BlockPos(-5, 77, -22), new BlockPos(1, 77, -24), new BlockPos(8, 77, -24), new BlockPos(14, 77, -23), new BlockPos(21, 77, -19),
        new BlockPos(27, 77, -14), new BlockPos(31, 77, -8), new BlockPos(33, 77, -1), new BlockPos(34, 77, 5), new BlockPos(33, 77, 12),
        new BlockPos(31, 77, 19), new BlockPos(27, 77, 25), new BlockPos(20, 77, 30), new BlockPos(14, 77, 33), new BlockPos(7, 77, 34));
    private static final Set<BlockPos> M4_BLOCKS = Set.of(
        new BlockPos(-2, 77, 33), new BlockPos(-7, 77, 32), new BlockPos(-13, 77, 28), new BlockPos(-17, 77, 24), new BlockPos(-21, 77, 18),
        new BlockPos(-23, 77, 13), new BlockPos(-24, 77, 7), new BlockPos(-24, 77, 2), new BlockPos(-23, 77, -4), new BlockPos(-21, 77, -9),
        new BlockPos(-17, 77, -14), new BlockPos(-12, 77, -19), new BlockPos(-6, 77, -22), new BlockPos(-1, 77, -23), new BlockPos(5, 77, -24),
        new BlockPos(10, 77, -24), new BlockPos(16, 77, -22), new BlockPos(21, 77, -19), new BlockPos(27, 77, -15), new BlockPos(30, 77, -10),
        new BlockPos(32, 77, -5), new BlockPos(34, 77, 1), new BlockPos(34, 77, 7), new BlockPos(33, 77, 12), new BlockPos(31, 77, 18),
        new BlockPos(28, 77, 23), new BlockPos(23, 77, 28), new BlockPos(18, 77, 31), new BlockPos(12, 77, 33), new BlockPos(7, 77, 34));

    /** -1: not spawning yet, 0: alive, above 0: server ticks until it spawns. */
    private static int timer = -1;
    private static int kills;
    private static Level lastLevel;

    private SpiritBear() {}

    private static FeatureConfigs.Dungeons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons;
    }

    public static void init() {
        WorldEvents.BLOCK_STATE_UPDATE.register(SpiritBear::onBlockUpdate);
        ServerTickCallback.EVENT.register(() -> {
            if (timer > 0) timer--;
        });
        // Odin resets on world load.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != lastLevel) {
                lastLevel = mc.level;
                kills = 0;
                timer = -1;
            }
        });
        Sky2MHuds.setting("spirit_bear", () -> config() != null && config().spiritBear);
        Sky2MHuds.register("spirit_bear", "Spirit Bear",
            () -> config() != null && config().spiritBear && inBoss(),
            () -> List.of(line(state())),
            List.of(line("§e1.45s")),
            200, 800);
    }

    private static boolean inBoss() {
        return Sky2MLocation.inDungeon() && DungeonManager.getBoss() == DungeonBoss.THORN;
    }

    private static boolean masterMode() {
        return Sky2MLocation.dungeonFloor().startsWith("M");
    }

    private static String state() {
        if (timer < 0) return "§d" + kills + "/" + (masterMode() ? 30 : 25);
        if (timer > 0) return "§e" + String.format(Locale.ROOT, "%.2f", timer / 20f) + "s";
        return "§aAlive!";
    }

    private static Component line(String state) {
        return Component.literal("§6Bear: " + state);
    }

    private static void onBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        if (old == null || !inBoss() || !(masterMode() ? M4_BLOCKS : F4_BLOCKS).contains(pos)) return;
        int max = masterMode() ? 30 : 25;
        if (updated.is(Blocks.SEA_LANTERN) && old.is(Blocks.COAL_BLOCK)) {
            if (kills < max) kills++;
            if (pos.equals(LAST_BLOCK)) timer = SPAWN_TICKS;
        } else if (updated.is(Blocks.COAL_BLOCK) && old.is(Blocks.SEA_LANTERN)) {
            if (kills > 0) kills--;
            if (pos.equals(LAST_BLOCK)) timer = -1;
        }
    }
}
