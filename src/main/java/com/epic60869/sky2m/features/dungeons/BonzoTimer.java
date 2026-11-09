package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
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
 * Bonzo tick timer (F1/M1), like the Livid one ({@link LividTimer}): the server ticks until Bonzo can be damaged, once
 * from his opening line to his first phase and once from "Oh I'm dead!" to his second life.
 *
 * <p>The tick counts are APPROXIMATE, measured from an M1 run (2026-11-04 clip, 60 fps): his bar stayed full under the
 * team's beams for 13.8 s after "Gratz for making it this far" and only then dropped, and it refilled and started
 * dropping 5.2 s after "Oh I'm dead!" (1.4 s after "Sike").
 */
public final class BonzoTimer {
    static final String START = DungeonBoss.BONZO.message();
    static final String SECOND_LIFE = "[BOSS] Bonzo: Oh I'm dead!";
    /** Opening line to his first phase. */
    public static final int FIRST_PHASE_TICKS = 276;
    /** "Oh I'm dead!" to his second life. */
    public static final int SECOND_PHASE_TICKS = 104;

    private static int ticks;
    /** 1 or 2: which phase the countdown is to. */
    private static int phase;
    private static Level lastLevel;

    private BonzoTimer() {}

    private static FeatureConfigs.Dungeons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            if (!onFloor1()) return;
            onLine(message.text());
        });
        ServerTickCallback.EVENT.register(() -> {
            if (ticks > 0 && onFloor1()) ticks--;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == lastLevel) return;
            lastLevel = mc.level;
            ticks = 0;
        });
        Sky2MHuds.setting("bonzo_timer", () -> config() != null && config().bonzoTimer);
        Sky2MHuds.register("bonzo_timer", "Bonzo Tick Timer",
            () -> config() != null && config().bonzoTimer && inBoss() && ticks > 0,
            () -> List.of(line(phase, ticks)),
            List.of(line(1, FIRST_PHASE_TICKS)),
            200, 772);
    }

    /** Starts the countdown on Bonzo's opening line or his "Oh I'm dead!". Public for the game test. */
    public static void onLine(String text) {
        if (START.equals(text)) {
            phase = 1;
            ticks = FIRST_PHASE_TICKS;
        } else if (SECOND_LIFE.equals(text)) {
            phase = 2;
            ticks = SECOND_PHASE_TICKS;
        }
    }

    /** For the game test: {phase, ticks left}. */
    public static int[] state() {
        return new int[]{phase, ticks};
    }

    private static boolean onFloor1() {
        return Sky2MLocation.inDungeon() && Sky2MLocation.dungeonFloor().endsWith("1");
    }

    private static boolean inBoss() {
        return Sky2MLocation.inDungeon() && DungeonManager.getBoss() == DungeonBoss.BONZO;
    }

    /** "Bonzo P1: 276t", green, then yellow, then red as it runs out. */
    public static Component line(int phase, int ticks) {
        int full = phase == 2 ? SECOND_PHASE_TICKS : FIRST_PHASE_TICKS;
        String colour = ticks > full * 2 / 3 ? "§a" : ticks > full / 3 ? "§e" : "§c";
        return Component.literal("§cBonzo P" + phase + ": " + colour + ticks + "t");
    }
}
