package com.epic60869.sky2m.features.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /s2 perf's measurements: how long each HUD, in-world renderer and chat listener takes, added up over 5-second
 * windows. Timing is two System.nanoTime() calls around each one, so it's always on and costs next to nothing.
 */
public final class Sky2MPerf {
    public enum Kind { HUD, WORLD, CHAT }

    /** One feature over the last full window: total time, calls, slowest call. */
    public record Stat(Kind kind, String name, long nanos, long calls, long worstNanos) {}

    private static final long WINDOW_NANOS = 5_000_000_000L;
    @SuppressWarnings("unchecked")
    private static final Map<String, long[]>[] BY_KIND = new Map[Kind.values().length]; // name -> {nanos, calls, worst}
    static {
        for (int i = 0; i < BY_KIND.length; i++) BY_KIND[i] = new ConcurrentHashMap<>();
    }
    private static final ClassValue<String> NAMES = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            String name = type.getName();
            int lambda = name.indexOf("$$");
            if (lambda > 0) name = name.substring(0, lambda);
            name = name.substring(name.lastIndexOf('.') + 1);
            int inner = name.indexOf('$');
            return inner > 0 ? name.substring(0, inner) : name;
        }
    };

    private static volatile List<Stat> last = List.of();
    private static volatile long lastFrames;
    private static volatile double lastSeconds = 5;
    private static long windowStart = System.nanoTime();
    private static long frames;

    private Sky2MPerf() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void end(Kind kind, String name, long start) {
        long took = System.nanoTime() - start;
        // Called for every HUD every frame and for every chat line: no key building, no lambda.
        Map<String, long[]> byName = BY_KIND[kind.ordinal()];
        long[] v = byName.get(name);
        if (v == null) {
            v = new long[]{0, 0, 0};
            long[] raced = byName.putIfAbsent(name, v);
            if (raced != null) v = raced;
        }
        synchronized (v) {
            v[0] += took;
            v[1]++;
            if (took > v[2]) v[2] = took;
        }
    }

    /** A readable name for a listener or renderer: its class, without the lambda and package parts. */
    public static String nameOf(Object callback) {
        return callback == null ? "?" : NAMES.get(callback.getClass());
    }

    /** Once per frame (from the HUD pass): counts frames and closes the window every 5 seconds. */
    public static void frame() {
        frames++;
        long now = System.nanoTime();
        if (now - windowStart < WINDOW_NANOS) return;
        List<Stat> stats = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (Map.Entry<String, long[]> e : BY_KIND[kind.ordinal()].entrySet()) {
                long[] v = e.getValue();
                synchronized (v) {
                    if (v[1] > 0) stats.add(new Stat(kind, e.getKey(), v[0], v[1], v[2]));
                }
            }
        }
        stats.sort(Comparator.comparingLong(Stat::nanos).reversed());
        last = List.copyOf(stats);
        lastFrames = frames;
        lastSeconds = (now - windowStart) / 1e9;
        for (Map<String, long[]> byName : BY_KIND) byName.clear();
        frames = 0;
        windowStart = now;
        Sky2MPerfWarnings.onWindow(last, lastFrames);
    }

    public static List<Stat> stats() {
        return last;
    }

    public static long frames() {
        return lastFrames;
    }

    public static double seconds() {
        return lastSeconds;
    }
}
