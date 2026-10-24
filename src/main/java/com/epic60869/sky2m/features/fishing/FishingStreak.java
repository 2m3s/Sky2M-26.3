package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Catch timer: every sea creature you catch within the set time window of the previous one adds to a streak, and the
 * HUD shows the time since the last catch plus the streak. The streak resets when the window runs out.
 */
public final class FishingStreak {
    private static long lastTime;
    private static int streak;
    private static String lastCreature = "";

    private FishingStreak() {}

    private static FeatureConfigs.FishingCatchStreak config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.fishing.catchStreak;
    }

    private static boolean enabled() {
        FeatureConfigs.FishingCatchStreak c = config();
        return c != null && c.enabled;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MHuds.setting("fishing_streak", FishingStreak::enabled);
        Sky2MHuds.register("fishing_streak", "Catch Streak", FishingStreak::enabled, FishingStreak::lines,
            List.of(Component.literal("§eLast catch: §bThunder §7(4.2s ago)"), Component.literal("§eStreak: §a5")),
            8, 380);
    }

    private static void reset() {
        lastTime = 0;
        streak = 0;
        lastCreature = "";
    }

    /** A sea creature you caught (a double hook counts as two). */
    static void onCatch(String creature, boolean doubleHook) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (lastTime == 0 || now - lastTime > windowMs()) streak = 0;
        streak += doubleHook ? 2 : 1;
        lastTime = now;
        lastCreature = creature;
    }

    private static long windowMs() {
        FeatureConfigs.FishingCatchStreak c = config();
        return c == null ? 60_000L : Math.round(c.windowSeconds) * 1000L;
    }

    private static List<Component> lines() {
        if (lastTime == 0 || streak <= 0) return List.of();
        long since = System.currentTimeMillis() - lastTime;
        if (since > windowMs()) return List.of();
        return List.of(
            Component.literal("§eLast catch: §b" + lastCreature + " §7(" + format(since) + " ago)"),
            Component.literal("§eStreak: §a" + streak));
    }

    private static String format(long millis) {
        if (millis < 60_000) return String.format(Locale.US, "%.1fs", millis / 1000.0);
        return (millis / 60_000) + "m " + (millis % 60_000) / 1000 + "s";
    }
}
