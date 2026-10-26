package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonBoss;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Odin's Livid invulnerability HUD (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/boss/LividSolver.kt):
 * the server ticks left of Livid's 340 tick invulnerability, from her opening line.
 */
public final class LividTimer {
    private static final String START = "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";
    private static final int INVULNERABLE_TICKS = 340;

    private static int invulnerableTicks;
    private static Level lastLevel;

    private LividTimer() {}

    private static FeatureConfigs.Dungeons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            if (onFloor5() && message.text().equals(START)) invulnerableTicks = INVULNERABLE_TICKS;
        });
        ServerTickCallback.EVENT.register(() -> {
            if (invulnerableTicks > 0 && onFloor5()) invulnerableTicks--;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == lastLevel) return;
            lastLevel = mc.level;
            invulnerableTicks = 0;
        });
        Sky2MHuds.setting("livid_timer", () -> config() != null && config().lividTimer);
        Sky2MHuds.register("livid_timer", "Livid Invulnerability",
            () -> config() != null && config().lividTimer && inBoss() && invulnerableTicks > 0,
            () -> List.of(line(invulnerableTicks)),
            List.of(line(INVULNERABLE_TICKS)),
            200, 760);
    }

    private static boolean onFloor5() {
        return Sky2MLocation.inDungeon() && Sky2MLocation.dungeonFloor().endsWith("5");
    }

    private static boolean inBoss() {
        return Sky2MLocation.inDungeon() && DungeonManager.getBoss() == DungeonBoss.LIVID;
    }

    private static Component line(int ticks) {
        String colour = ticks > 260 ? "§a" : ticks > 130 ? "§e" : "§c";
        return Component.literal("§bLivid: " + colour + ticks + "t");
    }
}
