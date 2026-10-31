package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.mining.extras.PowderTrackerHud;
import com.epic60869.sky2m.features.slayer.SlayerBossProfit;
import com.epic60869.sky2m.features.slayer.SlayerQuestCost;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coins per hour HUD: the scoreboard's purse sampled once a minute, drawn as a small bar graph of the change each
 * minute, with the session's net coins per hour (spending lowers it). Optionally the last slayer boss's drops value
 * against its quest cost (SlayerBossProfit) and the time to a powder goal at this session's powder per hour
 * (PowderTrackerHud, which has to be turned on for that).
 */
public final class CoinsChart {
    private static final String ID = "coins_chart";
    private static final long SAMPLE_MS = 60_000L;
    private static final int MAX_SAMPLES = 30;
    private static final String BARS = "▁▂▃▄▅▆▇█";

    public enum PowderType {
        MITHRIL("Mithril"), GEMSTONE("Gemstone"), GLACITE("Glacite");

        private final String displayName;

        PowderType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /** The purse at each minute mark of this session. */
    private static final List<Double> samples = new ArrayList<>();
    private static long startedAt, lastSample;
    private static double startPurse = -1;

    private CoinsChart() {}

    private static Sky2MConfig.CoinsChartSettings config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.coinsChart;
    }

    private static boolean turnedOn() {
        Sky2MConfig.CoinsChartSettings c = config();
        return c != null && c.enabled;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!turnedOn() || mc.player == null || !Compat.isOnSkyblock()) return;
            long now = System.currentTimeMillis();
            if (now - lastSample < SAMPLE_MS) return;
            double purse = SlayerQuestCost.purse();
            if (purse < 0) return;
            if (startPurse < 0) {
                startPurse = purse;
                startedAt = now;
                samples.clear();
            }
            lastSample = now;
            samples.add(purse);
            while (samples.size() > MAX_SAMPLES + 1) samples.remove(0);
        });
        Sky2MHuds.setting(ID, CoinsChart::turnedOn);
        Sky2MHuds.register(ID, "Coins Per Hour", () -> turnedOn() && Compat.isOnSkyblock(), CoinsChart::lines,
            List.of(Component.literal("Coins/h").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("▁▂▄▃▆▇▅█").withStyle(ChatFormatting.GREEN),
                Component.literal("1.2m/h (+300k)").withStyle(ChatFormatting.GRAY)), 20, 180);
    }

    private static String coins(double v) {
        String sign = v < 0 ? "-" : "";
        v = Math.abs(v);
        if (v >= 1_000_000_000) return sign + String.format(Locale.US, "%.2fb", v / 1_000_000_000);
        if (v >= 1_000_000) return sign + String.format(Locale.US, "%.2fm", v / 1_000_000);
        if (v >= 1_000) return sign + String.format(Locale.US, "%.1fk", v / 1_000);
        return sign + String.format(Locale.US, "%.0f", v);
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Coins/h").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        double purse = SlayerQuestCost.purse();
        if (startPurse < 0 || purse < 0 || samples.size() < 2) {
            out.add(Component.literal("Collecting purse data (1 sample per minute)").withStyle(ChatFormatting.GRAY));
        } else {
            double max = 0;
            List<Double> deltas = new ArrayList<>();
            for (int i = 1; i < samples.size(); i++) {
                double d = samples.get(i) - samples.get(i - 1);
                deltas.add(d);
                max = Math.max(max, d);
            }
            StringBuilder graph = new StringBuilder();
            for (double d : deltas) {
                if (d <= 0 || max <= 0) graph.append(' ');
                else graph.append(BARS.charAt((int) Math.min(BARS.length() - 1, Math.floor(d / max * (BARS.length() - 1)))));
            }
            out.add(Component.literal(graph.toString()).withStyle(ChatFormatting.GREEN));
            double hours = Math.max(1.0 / 60, (System.currentTimeMillis() - startedAt) / 3_600_000.0);
            double net = purse - startPurse;
            out.add(Component.literal(coins(net / hours) + "/h").withStyle(net >= 0 ? ChatFormatting.YELLOW : ChatFormatting.RED)
                .append(Component.literal(" (" + (net >= 0 ? "+" : "") + coins(net) + " this session)").withStyle(ChatFormatting.GRAY)));
        }
        Sky2MConfig.CoinsChartSettings c = config();
        if (c != null && c.slayerRoi) {
            double[] kill = SlayerBossProfit.lastKill();
            if (kill != null) {
                Component line = Component.literal("Slayer: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(coins(kill[0]) + " drops").withStyle(ChatFormatting.GREEN));
                if (kill[1] >= 0) line = line.copy().append(Component.literal(" - " + coins(kill[1]) + " cost = ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal((kill[2] >= 0 ? "+" : "") + coins(kill[2])).withStyle(kill[2] >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
                else line = line.copy().append(Component.literal(" (cost unknown)").withStyle(ChatFormatting.DARK_GRAY));
                out.add(line);
            }
        }
        if (c != null && c.powderGoal) {
            int type = c.powderType.ordinal();
            long have = PowderTrackerHud.current(type);
            double rate = PowderTrackerHud.perHour(type);
            if (have >= 0 && rate > 0) {
                long left = c.powderTarget - have;
                if (left <= 0) {
                    out.add(Component.literal(c.powderType + " powder goal reached").withStyle(ChatFormatting.GREEN));
                } else {
                    long minutes = Math.round(left / rate * 60);
                    out.add(Component.literal(c.powderType + " powder goal: ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m").withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(String.format(Locale.US, " (%,.0f/h)", rate)).withStyle(ChatFormatting.DARK_GRAY)));
                }
            }
        }
        return out;
    }
}
