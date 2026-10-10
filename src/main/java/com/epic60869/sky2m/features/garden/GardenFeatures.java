package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Yaw/pitch, blocks per second and the dye / Ray of Helios animation. The pest timer and alerts are in {@link PestTimer}. */
public final class GardenFeatures {
    private static final Pattern SPECIAL_DROP = Pattern.compile("(?<item>[A-Z][\\w' ]* Dye|Ray of Helios)");
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
        Sky2MChat.onChat(GardenFeatures::onChat);
        PestTimer.init();
        PestFinder.init();
        GardenPlotPanel.init();
        OverflowDropSound.init();

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

    private static void onChat(Sky2MChat.Message message) {
        String text = message.text();
        FeatureConfigs.Garden config = config();
        if (config == null || !config.specialDropAnimation || !Sky2MLocation.inGarden()) return;
        boolean dropMessage = text.contains("DROP!") || text.contains("CROP!") || text.startsWith("WOW!") || text.contains(" found ");
        if (!dropMessage) return;
        Matcher m = SPECIAL_DROP.matcher(text);
        if (m.find()) {
            Sky2MAlerts.dropAnimation(Component.literal(m.group("item") + "!").withStyle(ChatFormatting.BOLD), 0xFF55FF);
        }
    }
}
