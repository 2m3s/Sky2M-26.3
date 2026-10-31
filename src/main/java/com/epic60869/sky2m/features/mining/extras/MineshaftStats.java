package com.epic60869.sky2m.features.mining.extras;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Mineshaft stats: the time you have spent in the current Glacite Mineshaft and the blocks you broke in it, counting
 * vanilla-named ore blocks ({@code *_ore}) separately. Tungsten, Umber, Glacite and gemstones are other blocks and
 * only count towards the total, so "Ores" is not the number of resources you gained. Broken blocks are counted
 * client side, so a block that is broken but refused by the server is still counted.
 */
public final class MineshaftStats {
    private static long enteredAt;
    private static int blocks, ores;

    private MineshaftStats() {}

    private static MiningExtrasConfig.MineshaftStats config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.mineshaftStats;
    }

    public static void init() {
        Sky2MLocation.onAreaChange(area -> {
            MiningExtrasConfig.MineshaftStats c = config();
            if (enteredAt > 0 && c != null && c.chat && blocks > 0) {
                Sky2MAlerts.chat(Component.literal("Mineshaft: ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(time(System.currentTimeMillis() - enteredAt) + ", " + String.format(Locale.US, "%,d", blocks)
                        + " blocks broken (" + String.format(Locale.US, "%,d", ores) + " ores)").withStyle(ChatFormatting.WHITE)));
            }
            enteredAt = area.equals("Mineshaft") ? System.currentTimeMillis() : 0;
            blocks = 0;
            ores = 0;
        });
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (enteredAt == 0 || !level.isClientSide() || !Sky2MLocation.inMineshaft()) return;
            blocks++;
            if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore")) ores++;
        });
        Sky2MHuds.register("mineshaft_stats", "Mineshaft Stats", () -> config() != null && config().hud && enteredAt > 0 && Sky2MLocation.inMineshaft(),
            MineshaftStats::lines,
            List.of(Component.literal("Time in shaft: ").withStyle(ChatFormatting.WHITE).append(Component.literal("4:12").withStyle(ChatFormatting.AQUA)),
                Component.literal("Blocks: ").withStyle(ChatFormatting.WHITE).append(Component.literal("1,204 (86 ores)").withStyle(ChatFormatting.GRAY))), 150, 470);
        Sky2MHuds.setting("mineshaft_stats", () -> config() != null && config().hud);
    }

    private static List<Component> lines() {
        return List.of(Component.literal("Time in shaft: ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(time(System.currentTimeMillis() - enteredAt)).withStyle(ChatFormatting.AQUA)),
            Component.literal("Blocks: ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(String.format(Locale.US, "%,d (%,d ores)", blocks, ores)).withStyle(ChatFormatting.GRAY)));
    }

    private static String time(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
