package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.util.Util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FPS, TPS, ping and time HUD, ported from Skysoft's ServerInfoDisplay, ServerPingTracker, ServerTpsEstimator
 * and RealTimeDisplay (https://github.com/Akinsoft/Skysoft, LGPL-3.0), with the time as a fourth value. Ping is the round trip of a ping packet sent
 * once a second; TPS is how fast the server's clock moves between its time packets (or, where the clock stands still
 * as on Hypixel, how often the once-every-20-ticks time packets arrive), averaged over the last 5. Both
 * are also used by the !ping and !tps party commands.
 */
public final class ServerInfo {
    public enum Metric {
        FPS("FPS", "⚡"), TPS("TPS", "⇄"), PING("Ping", "▂▄▆"), TIME("Time", "⌚");

        private final String label;
        private final String symbol;

        Metric(String label, String symbol) {
            this.label = label;
            this.symbol = symbol;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Style {
        SIMPLE("Simple"), SPLIT("Split");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum Layout {
        VERTICAL("Vertical"), HORIZONTAL("Horizontal");

        private final String label;

        Layout(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum LabelStyle {
        TEXT("Text"), SYMBOLS("Symbols"), VALUES_ONLY("Values");

        private final String label;

        LabelStyle(String label) {
            this.label = label;
        }

        String prefix(String text, String symbol) {
            return switch (this) {
                case TEXT -> text + ": ";
                case SYMBOLS -> symbol + " ";
                case VALUES_ONLY -> "";
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum TimeFormat {
        TWENTY_FOUR_HOUR("24h", "HH:mm"),
        TWENTY_FOUR_HOUR_SECONDS("24h + sec", "HH:mm:ss"),
        TWELVE_HOUR("12h", "h:mm a"),
        TWELVE_HOUR_SECONDS("12h + sec", "h:mm:ss a");

        private final String label;
        private final DateTimeFormatter formatter;

        TimeFormat(String label, String pattern) {
            this.label = label;
            this.formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
        }

        String format(LocalTime time) {
            return formatter.format(time);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ---------------------------------------------------------------- ping (Skysoft's ServerPingTracker)

    private static final long PING_INTERVAL_NANOS = 1_000_000_000L;
    private static final long PING_TIMEOUT_NANOS = 5_000_000_000L;
    private static long pendingId;
    private static long pendingSentAt;
    private static boolean pending;
    private static long lastRequestAt;
    private static boolean requesting;
    private static Integer pingMs;

    // ---------------------------------------------------------------- TPS (Skysoft's ServerTpsEstimator)

    private static final int TPS_SAMPLES = 5;
    private static final ArrayDeque<Double> tpsSamples = new ArrayDeque<>();
    private static long lastGameTime = -1;
    private static long lastTimeAt;
    private static double lastTarget;

    private ServerInfo() {}

    private static Sky2MConfig config() {
        return Sky2MConfig.current();
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ServerInfo::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());

        Sky2MHuds.register("server_info", "Server Info Display",
            () -> showing() && serverInfo().style != Style.SPLIT,
            ServerInfo::simpleLines,
            List.of(Component.literal("FPS: 144"), Component.literal("TPS: 20.0"), Component.literal("Ping: 42 ms"), Component.literal("Time: 14:32")),
            8, 30);
        int y = 30;
        for (Metric metric : Metric.values()) {
            String id = "server_info_" + metric.name().toLowerCase(Locale.ROOT);
            Sky2MHuds.register(id, "Server Info: " + metric,
                () -> showing() && serverInfo().style == Style.SPLIT && metrics(serverInfo()).contains(metric),
                () -> List.of(metricLine(metric)),
                List.of(Component.literal(metric + ": " + switch (metric) {
                    case FPS -> "144";
                    case TPS -> "20.0";
                    case PING -> "42 ms";
                    case TIME -> "14:32";
                })),
                8, y);
            Sky2MHuds.setting(id, () -> serverInfo() != null && serverInfo().enabled && serverInfo().style == Style.SPLIT);
            y += 13;
        }
        Sky2MHuds.setting("server_info", () -> serverInfo() != null && serverInfo().enabled && serverInfo().style != Style.SPLIT);
    }

    private static FeatureConfigs.ServerInfoDisplay serverInfo() {
        Sky2MConfig c = config();
        return c == null ? null : c.misc.serverInfoDisplay;
    }


    /** The values the display shows, in order. */
    private static List<Metric> metrics(FeatureConfigs.ServerInfoDisplay c) {
        List<Metric> metrics = new ArrayList<>();
        if (c.showFps) metrics.add(Metric.FPS);
        if (c.showTps) metrics.add(Metric.TPS);
        if (c.showPing) metrics.add(Metric.PING);
        if (c.showTime) metrics.add(Metric.TIME);
        return metrics;
    }

    private static boolean remoteServer(Minecraft mc) {
        return mc.getConnection() != null && mc.level != null && mc.player != null && !mc.isLocalServer();
    }

    private static boolean showing() {
        FeatureConfigs.ServerInfoDisplay c = serverInfo();
        Minecraft mc = Minecraft.getInstance();
        return c != null && c.enabled && !metrics(c).isEmpty() && remoteServer(mc);
    }

    private static void tick(Minecraft mc) {
        FeatureConfigs.ServerInfoDisplay c = serverInfo();
        Sky2MConfig all = config();
        boolean hudPing = c != null && c.enabled && c.showPing;
        boolean partyPing = all != null && all.misc.partyCommands.enabled && all.misc.partyCommands.ping;
        if (!remoteServer(mc) || !(hudPing || partyPing)) {
            if (requesting) resetPing();
            return;
        }
        requesting = true;
        long now = System.nanoTime();
        if (pending) {
            if (now - pendingSentAt < PING_TIMEOUT_NANOS) return;
            pending = false;
            pingMs = null;
        }
        if (lastRequestAt != 0 && now - lastRequestAt < PING_INTERVAL_NANOS) return;
        pendingId = Util.getMillis();
        pendingSentAt = now;
        pending = true;
        lastRequestAt = now;
        mc.getConnection().send(new ServerboundPingRequestPacket(pendingId));
    }

    /** A pong came back ({@code Sky2MServerInfoPacketMixin}). */
    public static void onPong(long id, long receivedAt) {
        if (!pending || id != pendingId) return;
        if (receivedAt < pendingSentAt) {
            resetPing();
            return;
        }
        pingMs = (int) Math.round((receivedAt - pendingSentAt) / 1_000_000.0);
        pending = false;
    }

    /** The server's time packet ({@code Sky2MServerInfoPacketMixin}). */
    public static void onServerTime(long gameTime, long at) {
        Minecraft mc = Minecraft.getInstance();
        double target = mc.level == null ? Double.NaN : mc.level.tickRateManager().tickrate();
        if (!Double.isFinite(target) || target <= 0) return;
        if (lastGameTime < 0 || lastTarget != target) {
            tpsSamples.clear();
            lastGameTime = gameTime;
            lastTimeAt = at;
            lastTarget = target;
            return;
        }
        long ticks = gameTime - lastGameTime;
        long elapsed = at - lastTimeAt;
        lastGameTime = gameTime;
        lastTimeAt = at;
        // Hypixel's game time doesn't move between time packets. Servers send one every 20 ticks, so count those
        // instead (Odin's ServerUtils).
        if (ticks <= 0) ticks = 20;
        if (elapsed <= 0) {
            tpsSamples.clear();
            return;
        }
        double tps = ticks * 1_000_000_000.0 / elapsed;
        if (!Double.isFinite(tps) || tps <= 0) {
            tpsSamples.clear();
            return;
        }
        tpsSamples.addLast(Math.min(tps, target));
        while (tpsSamples.size() > TPS_SAMPLES) tpsSamples.removeFirst();
    }

    private static void resetPing() {
        pending = false;
        lastRequestAt = 0;
        pingMs = null;
        requesting = false;
    }

    private static void reset() {
        resetPing();
        tpsSamples.clear();
        lastGameTime = -1;
    }

    /** Average TPS over the last few time packets, or null before there are any. */
    public static Double tps() {
        if (tpsSamples.isEmpty()) return null;
        double sum = 0;
        for (double sample : tpsSamples) sum += sample;
        return sum / tpsSamples.size();
    }

    /** Measured ping in ms, else the tab list's latency, or null. */
    public static Integer ping() {
        if (pingMs != null) return pingMs;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return null;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null || info.getLatency() <= 0 ? null : info.getLatency();
    }

    public static int fps() {
        return Minecraft.getInstance().getFps();
    }

    // ---------------------------------------------------------------- HUD

    private static List<Component> simpleLines() {
        FeatureConfigs.ServerInfoDisplay c = serverInfo();
        List<Component> lines = new ArrayList<>();
        if (c == null) return lines;
        if (c.layout == Layout.HORIZONTAL) {
            MutableComponent line = Component.empty();
            boolean first = true;
            for (Metric metric : metrics(c)) {
                if (!first) line.append(Component.literal("  "));
                line.append(metricLine(metric));
                first = false;
            }
            lines.add(line);
        } else {
            for (Metric metric : metrics(c)) lines.add(metricLine(metric));
        }
        return lines;
    }

    private static Component metricLine(Metric metric) {
        FeatureConfigs.ServerInfoDisplay c = serverInfo();
        LabelStyle style = c == null || c.labelStyle == null ? LabelStyle.TEXT : c.labelStyle;
        String value = switch (metric) {
            case FPS -> String.valueOf(fps());
            case TPS -> {
                Double tps = tps();
                yield tps == null ? "--" : String.format(Locale.ROOT, "%.1f", tps);
            }
            case PING -> {
                Integer ping = pingMs;
                yield ping == null ? "--" : ping + " ms";
            }
            case TIME -> (c == null || c.timeFormat == null ? TimeFormat.TWENTY_FOUR_HOUR : c.timeFormat).format(LocalTime.now());
        };
        String suffix = style == LabelStyle.TEXT ? "" : switch (metric) {
            case FPS -> " FPS";
            case TPS -> " TPS";
            case PING, TIME -> "";
        };
        String colour = c == null ? null : switch (metric) {
            case FPS -> c.fpsColour;
            case TPS -> c.tpsColour;
            case PING -> c.pingColour;
            case TIME -> c.timeColour;
        };
        return Component.literal(style.prefix(metric.toString(), metric.symbol) + value + suffix).withColor(colour(colour));
    }


    private static int colour(String value) {
        if (value == null || value.isEmpty()) return 0xFFFFFF;
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB() & 0xFFFFFF;
        } catch (Exception e) {
            return 0xFFFFFF;
        }
    }
}
