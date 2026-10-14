package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Slayers > Boss Fight > Phase Timer: how long each phase of your boss took, read from its health nametag as
 * Slayer Features finds it. While the nametag says "15 Hits" (a Voidgloom's shield, and any other boss that shows
 * hits), the boss is in a hit phase; otherwise it's taking damage. The fight is timed from the nametag appearing to it
 * going, and the last fight stays in the HUD for a few seconds after.
 */
public final class SlayerPhaseTimer {
    private static final Pattern HITS = Pattern.compile("\\b\\d+ Hits?\\b");
    private static final long KEEP_MS = 10_000;

    private record Phase(boolean hits, long millis) {}

    private static final List<Phase> phases = new ArrayList<>();
    private static boolean active, currentHits;
    private static long fightStart, phaseStart, endedAt;

    private SlayerPhaseTimer() {}

    private static FeatureConfigs.SlayerPhaseTimer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.phaseTimer;
    }

    static boolean needsBoss() {
        FeatureConfigs.SlayerPhaseTimer c = config();
        return c != null && c.enabled;
    }

    public static void init() {
        Sky2MHuds.register("slayer_phase_timer", "Slayer Phase Timer", () -> config() != null && config().enabled,
            SlayerPhaseTimer::lines,
            List.of(line(true, 1, 3_200), line(false, 1, 5_400), line(true, 2, 2_100)), 8, 240);
        Sky2MHuds.setting("slayer_phase_timer", () -> config() != null && config().enabled);
    }

    /** The boss's health line every other tick (null when you have no boss). */
    static void onBoss(String text) {
        long now = System.currentTimeMillis();
        if (text == null) {
            if (active) finish(now);
            return;
        }
        boolean hits = HITS.matcher(text).find();
        if (!active) {
            active = true;
            phases.clear();
            fightStart = phaseStart = now;
            currentHits = hits;
            endedAt = 0;
            return;
        }
        if (hits != currentHits) {
            phases.add(new Phase(currentHits, now - phaseStart));
            FeatureConfigs.SlayerPhaseTimer c = config();
            if (c != null && c.enabled && c.phaseAlerts) {
                Sky2MAlerts.title(Component.literal(hits ? "Hit Phase" : "Damage Phase").withStyle(hits ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED),
                    Component.literal((currentHits ? "Hits" : "Damage") + " took " + seconds(now - phaseStart)).withStyle(ChatFormatting.GRAY));
            }
            phaseStart = now;
            currentHits = hits;
        }
    }

    private static void finish(long now) {
        active = false;
        phases.add(new Phase(currentHits, now - phaseStart));
        endedAt = now;
        FeatureConfigs.SlayerPhaseTimer c = config();
        if (c == null || !c.enabled || !c.chat || now - fightStart < 1_000) return;
        MutableComponent msg = Component.literal("Boss phases: ").withStyle(ChatFormatting.GOLD);
        int hitN = 0, dmgN = 0;
        for (int i = 0; i < phases.size(); i++) {
            Phase p = phases.get(i);
            if (i > 0) msg.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            msg.append(Component.literal((p.hits() ? "Hits " + ++hitN : "Damage " + ++dmgN) + " ").withStyle(p.hits() ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED))
                .append(Component.literal(seconds(p.millis())).withStyle(ChatFormatting.WHITE));
        }
        msg.append(Component.literal(" (total " + seconds(now - fightStart) + ")").withStyle(ChatFormatting.GRAY));
        Sky2MAlerts.chat(msg);
    }

    private static String seconds(long millis) {
        return String.format(Locale.US, "%.1fs", millis / 1000.0);
    }

    private static Component line(boolean hits, int n, long millis) {
        return Component.literal((hits ? "Hit Phase " : "Damage Phase ") + n + ": ").withStyle(hits ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED)
            .append(Component.literal(seconds(millis)).withStyle(ChatFormatting.WHITE));
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (!active && (endedAt == 0 || now - endedAt > KEEP_MS)) return out;
        int hitN = 0, dmgN = 0;
        for (Phase p : phases) out.add(line(p.hits(), p.hits() ? ++hitN : ++dmgN, p.millis()));
        if (active) out.add(line(currentHits, currentHits ? ++hitN : ++dmgN, now - phaseStart));
        out.add(Component.literal("Total: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(seconds((active ? now : endedAt) - fightStart)).withStyle(ChatFormatting.WHITE)));
        return out;
    }
}
