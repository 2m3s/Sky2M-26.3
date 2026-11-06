package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;

/**
 * The time left on Sadan's Interest Meter in the F6 and M6 boss: when it runs out the Giants wake. The wiki gives it two
 * minutes, less a second for every Terracotta killed, and 28 logged runs fit that (the longest phase was 120s). A boss bar
 * naming the meter is used when there is one; otherwise it's worked out from Sadan's first line and {@link TerracottaTimer}'s
 * flower pots.
 */
public final class SadanInterest {
    private static final String START = "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!";
    private static final String END = "[BOSS] Sadan: ENOUGH!";
    private static final float METER_SECONDS = 120f;
    private static final long BAR_FRESH_MS = 1000;

    /** 0 outside the Terracotta phase. */
    private static long startedAt;
    private static int kills;
    private static float barProgress;
    private static long barAt;
    private static Level lastLevel;

    private SadanInterest() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.dungeons.sadanInterest;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> startedAt = 0);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != lastLevel) {
                lastLevel = mc.level;
                startedAt = 0;
            }
        });
        Sky2MChat.onChat(message -> {
            String text = message.text();
            if (text.equals(START)) {
                startedAt = System.currentTimeMillis();
                kills = 0;
                barAt = 0;
            } else if (text.equals(END)) {
                startedAt = 0;
            }
        });
        Sky2MHuds.setting("sadan_interest", SadanInterest::enabled);
        Sky2MHuds.register("sadan_interest", "Sadan Interest Timer",
            () -> enabled() && startedAt != 0 && Sky2MLocation.inDungeon(),
            () -> List.of(line(left())),
            List.of(line(83.4f)),
            20, 170);
    }

    /** A Terracotta died: its flower pot appeared ({@link TerracottaTimer}). */
    static void onTerracottaDied() {
        if (startedAt != 0) kills++;
    }

    /** Every boss bar drawn in a dungeon ({@link BossBarHealth#barName}). */
    static void onBossBar(String plainName, float progress) {
        if (startedAt == 0 || !plainName.contains("Interest")) return;
        barProgress = progress;
        barAt = System.currentTimeMillis();
    }

    private static float left() {
        long now = System.currentTimeMillis();
        if (now - barAt < BAR_FRESH_MS) return Math.clamp(barProgress, 0f, 1f) * METER_SECONDS;
        return secondsLeft(now - startedAt, kills);
    }

    /** Seconds left on the meter from the time so far and the Terracottas killed. Public for the game test. */
    public static float secondsLeft(long elapsedMs, int killed) {
        return Math.max(0f, METER_SECONDS - elapsedMs / 1000f - killed);
    }

    /** "83.4s", green, then gold from 30s and red from 10s. Public for the game test. */
    public static String text(float seconds) {
        String colour = seconds > 30f ? "§a" : seconds > 10f ? "§6" : "§c";
        return colour + String.format(Locale.ROOT, "%.1fs", seconds);
    }

    private static Component line(float seconds) {
        return Component.literal("§eSadan's Interest: " + text(seconds));
    }
}
