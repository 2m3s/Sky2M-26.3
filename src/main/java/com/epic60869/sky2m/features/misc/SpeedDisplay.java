package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * SkyblockAddons' Speed Percentage (https://github.com/BiscuitDevelopment/SkyblockAddons, MIT:
 * listeners/RenderListener.java): Hypixel sets your walking speed to speed / 1000, so 0.1 is 100% and 0.4 the 400% cap.
 */
public final class SpeedDisplay {
    private static final String ID = "speed_display";

    private SpeedDisplay() {}

    private static boolean turnedOn() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.speedDisplay.enabled;
    }

    public static void init() {
        Sky2MHuds.setting(ID, SpeedDisplay::turnedOn);
        Sky2MHuds.register(ID, "Speed Display", () -> turnedOn() && Compat.isOnSkyblock(), SpeedDisplay::lines,
            List.of(Component.literal("123%")), 20, 100);
    }

    private static List<Component> lines() {
        var player = Minecraft.getInstance().player;
        if (player == null) return List.of();
        // SkyblockAddons cuts the number to its first three characters ("123.4" -> "123").
        int percent = (int) Math.floor(player.getAbilities().getWalkingSpeed() * 1000 + 1e-3);
        return List.of(Component.literal(percent + "%"));
    }
}
