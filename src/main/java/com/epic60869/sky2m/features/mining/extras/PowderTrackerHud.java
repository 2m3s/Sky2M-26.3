package com.epic60869.sky2m.features.mining.extras;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mining > Powder Tracker: the Mithril, Gemstone and Glacite powder you gained this session and per hour, like
 * SkyHanni's Powder Tracker (hannibal002/SkyHanni, LGPL-2.1) but read from the sidebar's powder lines instead of
 * chat, so every source counts. Only increases count (spending powder in the Heart of the Mountain does not lower
 * it). The clock only runs while powder keeps coming in: it pauses after {@link #PAUSE_MS} without a gain.
 */
public final class PowderTrackerHud {
    private static final Pattern POWDER = Pattern.compile("(Mithril|Gemstone|Glacite)(?: Powder)?: ([\\d,]+)");
    private static final long PAUSE_MS = 60_000;

    private enum Type {
        MITHRIL("Mithril", ChatFormatting.GREEN),
        GEMSTONE("Gemstone", ChatFormatting.LIGHT_PURPLE),
        GLACITE("Glacite", ChatFormatting.AQUA);

        final String label;
        final ChatFormatting colour;

        Type(String label, ChatFormatting colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    private static final long[] last = {-1, -1, -1};
    private static final long[] gained = new long[3];
    private static long activeMs, lastGain, lastTick;
    private static int ticks;

    private PowderTrackerHud() {}

    private static MiningExtrasConfig.PowderTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.powderTracker;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            long delta = lastTick == 0 ? 0 : Math.min(1_000L, now - lastTick);
            lastTick = now;
            MiningExtrasConfig.PowderTracker c = config();
            if (c == null || !c.enabled || mc.player == null || !Sky2MLocation.inMiningIsland()) {
                if (mc.player == null || !Sky2MLocation.onSkyblock()) java.util.Arrays.fill(last, -1);
                return;
            }
            if (lastGain > 0 && now - lastGain < PAUSE_MS) activeMs += delta;
            if (++ticks % 10 == 0) read(now);
        });
        Sky2MLocation.onAreaChange(area -> java.util.Arrays.fill(last, -1));
        Sky2MHuds.setting("powder_tracker", () -> config() != null && config().enabled);
        Sky2MHuds.register("powder_tracker", "Powder Tracker",
            () -> config() != null && config().enabled && Sky2MLocation.inMiningIsland(),
            PowderTrackerHud::lines,
            List.of(line(Type.MITHRIL, 12_480, 31_200), line(Type.GEMSTONE, 8_315, 20_800), line(Type.GLACITE, 2_150, 5_400)),
            8, 200);
    }

    private static void read(long now) {
        List<String> all = new ArrayList<>(Sky2MLocation.scoreboard());
        all.addAll(Sky2MLocation.teamLines());
        long[] seen = {-1, -1, -1};
        for (String line : all) {
            Matcher m = POWDER.matcher(line);
            while (m.find()) {
                Type type = switch (m.group(1)) {
                    case "Mithril" -> Type.MITHRIL;
                    case "Gemstone" -> Type.GEMSTONE;
                    default -> Type.GLACITE;
                };
                try {
                    if (seen[type.ordinal()] < 0) seen[type.ordinal()] = Long.parseLong(m.group(2).replace(",", ""));
                } catch (NumberFormatException ignored) {}
            }
        }
        for (Type type : Type.values()) {
            int i = type.ordinal();
            if (seen[i] < 0) continue;
            if (last[i] >= 0 && seen[i] > last[i]) {
                gained[i] += seen[i] - last[i];
                lastGain = now;
            }
            last[i] = seen[i];
        }
    }

    private static Component line(Type type, long amount, long perHour) {
        MiningExtrasConfig.PowderTracker c = config();
        var out = Component.literal(type.label + ": ").withStyle(type.colour)
            .append(Component.literal(String.format(Locale.US, "%,d", amount)).withStyle(ChatFormatting.WHITE));
        if (c == null || c.perHour) {
            out.append(Component.literal(String.format(Locale.US, " (%,d/h)", perHour)).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private static List<Component> lines() {
        MiningExtrasConfig.PowderTracker c = config();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Powder Tracker").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        double hours = activeMs / 3_600_000.0;
        for (Type type : Type.values()) {
            long amount = gained[type.ordinal()];
            if (amount == 0 && c != null && c.onlyGained) continue;
            out.add(line(type, amount, hours < 1.0 / 3600 ? 0 : Math.round(amount / hours)));
        }
        if (out.size() == 1) out.add(Component.literal("Nothing gained yet").withStyle(ChatFormatting.GRAY));
        else if (activeMs > 0) {
            long s = activeMs / 1000;
            out.add(Component.literal(String.format(Locale.US, "Time: %d:%02d", s / 60, s % 60)).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** The powder (0 Mithril, 1 Gemstone, 2 Glacite) last read from the sidebar, or -1. */
    public static long current(int type) {
        return type < 0 || type > 2 ? -1 : last[type];
    }

    /** Powder per hour gained this session of a type (0 Mithril, 1 Gemstone, 2 Glacite), or 0 until there is enough data. */
    public static double perHour(int type) {
        if (type < 0 || type > 2) return 0;
        double hours = activeMs / 3_600_000.0;
        return hours < 1.0 / 60 ? 0 : gained[type] / hours;
    }

    /** The Reset button. */
    public static void reset() {
        java.util.Arrays.fill(gained, 0);
        activeMs = 0;
        lastGain = 0;
        Sky2MAlerts.chat(Component.literal("Powder Tracker reset.").withStyle(ChatFormatting.YELLOW));
    }
}
