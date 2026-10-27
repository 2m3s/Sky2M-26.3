package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Yaw/pitch and blocks per second. The pest timer and alerts are in {@link PestTimer}; the dye / Ray of Helios drop
 * animation is the shared totem animation ({@link com.epic60869.sky2m.features.combat.RareDropTotem}).
 */
public final class GardenFeatures {
    private static final String[] FACINGS = {"South", "South West", "West", "North West", "North", "North East", "East", "South East"};

    private static final Deque<Long> BREAKS = new ArrayDeque<>();

    private GardenFeatures() {}

    private static FeatureConfigs.Garden config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.garden;
    }

    public static void init() {
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            synchronized (BREAKS) {
                BREAKS.addLast(System.currentTimeMillis());
            }
        });
        PestTimer.init();
        GardenKeybinds.init();
        GardenReminders.init();
        PestFinder.init();
        GardenPlotPanel.init();
        GreenhousePreview.init();
        OverflowDropSound.init();
        com.epic60869.sky2m.features.garden.skyhanni.SkyHanniGarden.init();

        com.epic60869.sky2m.features.core.Sky2MHuds.setting("yaw_pitch", () -> config() != null && config().yawPitch);
        Sky2MHuds.register("yaw_pitch", "Yaw and Pitch",
            () -> config() != null && config().yawPitch && Sky2MLocation.inGarden(),
            GardenFeatures::yawPitchLines,
            List.of(kv("Yaw: ", "123.96"), kv("Pitch: ", "0.00"), kv("Facing: ", "West")),
            8, 300);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("bps", () -> config() != null && config().blocksPerSecond);
        Sky2MHuds.register("bps", "Blocks Per Second",
            () -> config() != null && config().blocksPerSecond && Sky2MLocation.inGarden(),
            GardenFeatures::bpsLines,
            List.of(kv("BPS: ", "19.8")),
            8, 354);
    }

    private static Component kv(String key, String value) {
        return Component.literal(key).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static List<Component> yawPitchLines() {
        var player = Minecraft.getInstance().player;
        if (player == null) return List.of();
        float yaw = Mth.wrapDegrees(player.getYRot());
        float pitch = player.getXRot();
        int index = Math.floorMod(Math.round(yaw / 45f), 8);
        return List.of(
            kv("Yaw: ", String.format(Locale.US, "%.2f", yaw)),
            kv("Pitch: ", String.format(Locale.US, "%.2f", pitch)),
            kv("Facing: ", FACINGS[index]));
    }

    private static List<Component> bpsLines() {
        long now = System.currentTimeMillis();
        int count;
        synchronized (BREAKS) {
            while (!BREAKS.isEmpty() && now - BREAKS.peekFirst() > 2000) BREAKS.removeFirst();
            count = BREAKS.size();
        }
        return List.of(kv("BPS: ", String.format(Locale.US, "%.1f", count / 2.0)));
    }
}
