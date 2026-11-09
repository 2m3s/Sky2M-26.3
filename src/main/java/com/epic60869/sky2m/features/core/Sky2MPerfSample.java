package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /s2 perf start [seconds]: measures for a while, ticks included, then says in chat what cost the most and the average FPS.
 * /s2 perf (the screen) keeps its always-on HUD, in-world and chat numbers; this adds what {@link
 * com.epic60869.sky2m.sim.util.FeatureGuard} wraps, which is only timed during a window. A boolean check otherwise.
 */
public final class Sky2MPerfSample {
    public static final String TICK = "TICK";
    private static final int DEFAULT_SECONDS = 10;
    private static final int MAX_SECONDS = 120;
    private static final int TOP = 10;

    /** One feature over the window. */
    public record Row(String kind, String name, long nanos, long calls) {}

    /** True only during a window. Read by the code being timed. */
    public static volatile boolean running;
    private static final Map<String, long[]> ROWS = new ConcurrentHashMap<>(); // "KIND|name" -> {nanos, calls}
    private static long startedAt;
    private static long endsAt;
    private static long frames;
    private static long ticks;

    private Sky2MPerfSample() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("perf")
                    .then(ClientCommands.literal("start")
                        .executes(c -> start(DEFAULT_SECONDS))
                        .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, MAX_SECONDS))
                            .executes(c -> start(IntegerArgumentType.getInteger(c, "seconds")))))
                    .then(ClientCommands.literal("stop").executes(c -> stop()))));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!running) return;
            ticks++;
            if (System.nanoTime() >= endsAt) finish();
        });
    }

    private static int start(int seconds) {
        ROWS.clear();
        frames = 0;
        ticks = 0;
        startedAt = System.nanoTime();
        endsAt = startedAt + seconds * 1_000_000_000L;
        running = true;
        Sky2MAlerts.chat(Component.literal("Measuring for " + seconds + " seconds. Play as usual; the results come here.").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static int stop() {
        if (running) {
            finish();
        } else {
            Sky2MAlerts.chat(Component.literal("Nothing is being measured. /s2 perf start begins.").withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    /** Adds one timed call. */
    public static void add(String kind, String name, long nanos) {
        long[] v = ROWS.computeIfAbsent(kind + "|" + name, k -> new long[2]);
        synchronized (v) {
            v[0] += nanos;
            v[1]++;
        }
    }

    /** Ends the timing of a tick handler that {@code FeatureGuard} wraps. */
    public static void tick(String name, long started) {
        add(TICK, name, System.nanoTime() - started);
    }

    static void frame() {
        frames++;
    }

    private static void finish() {
        running = false;
        double seconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1e9);
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : ROWS.entrySet()) {
            int bar = e.getKey().indexOf('|');
            long[] v = e.getValue();
            synchronized (v) {
                rows.add(new Row(e.getKey().substring(0, bar), e.getKey().substring(bar + 1), v[0], v[1]));
            }
        }
        ROWS.clear();
        Sky2MAlerts.chat(report(rows, frames, ticks, seconds, Minecraft.getInstance().getFps()));
    }

    /** The slowest first, at most {@code limit}. Public for the game test. */
    public static List<Row> top(Collection<Row> rows, int limit) {
        return rows.stream().sorted(Comparator.comparingLong(Row::nanos).reversed()).limit(limit).toList();
    }

    /** Average FPS over the window; the HUD pass counts frames, so with HUDs hidden it falls back to the game's number. Public for the game test. */
    public static double averageFps(long frames, double seconds, int gameFps) {
        return frames > 0 && seconds > 0 ? frames / seconds : gameFps;
    }

    /** "0.42 ms/frame", "1.30 ms/tick" or, for chat, the total. Public for the game test. */
    public static String cost(Row row, long frames, long ticks) {
        if (row.kind().equals(Sky2MPerf.Kind.CHAT.name())) return String.format(Locale.US, "%.2f ms total", row.nanos() / 1e6);
        boolean tick = row.kind().equals(TICK);
        long per = Math.max(1, tick ? ticks : frames);
        return String.format(Locale.US, "%.2f ms/%s", row.nanos() / 1e6 / per, tick ? "tick" : "frame");
    }

    private static String kindName(String kind) {
        if (kind.equals(TICK)) return "tick";
        if (kind.equals(Sky2MPerf.Kind.HUD.name())) return "HUD";
        if (kind.equals(Sky2MPerf.Kind.WORLD.name())) return "in world";
        return "chat";
    }

    private static Component report(List<Row> rows, long frames, long ticks, double seconds, int gameFps) {
        MutableComponent out = Component.literal(String.format(Locale.US, "Performance over %.0f s: %.0f FPS on average", seconds, averageFps(frames, seconds, gameFps)))
            .withStyle(ChatFormatting.AQUA);
        List<Row> top = top(rows, TOP);
        if (top.isEmpty()) out.append(Component.literal("\nNothing measured ran in that time.").withStyle(ChatFormatting.GRAY));
        for (int i = 0; i < top.size(); i++) {
            Row row = top.get(i);
            out.append(Component.literal("\n" + (i + 1) + ". ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(row.name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + kindName(row.kind()) + ") ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(cost(row, frames, ticks)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(String.format(Locale.US, ", %.2f%% of the time", 100 * row.nanos() / 1e9 / seconds)).withStyle(ChatFormatting.GRAY));
        }
        out.append(Component.literal("\nNot measured: mixins, most tick features (only those with a safety guard are timed) and the game's own work.")
            .withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }
}
