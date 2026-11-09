package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This game session's slayer kill times per boss and tier, for /s2 slayertimes ({@link SlayerConsistencyScreen}). They come
 * from {@link SlayerTimes}, so a kill it throws out as a timing miss isn't here either, and they're gone when the game closes.
 */
public final class SlayerSessionTimes {
    /** How long a kill took and when it happened. */
    public record Kill(long ms, long at) {}

    /** {@code spread} is the standard deviation; all 0 with no kills. */
    public record Stats(int count, long average, long best, long worst, long spread) {}

    /** Kills kept per boss; older ones drop off the chart. */
    public static final int MAX_KILLS = 500;

    private static final Map<String, List<Kill>> KILLS = new LinkedHashMap<>();

    private SlayerSessionTimes() {}

    static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("slayertimes")
                    .executes(c -> Compat.queueOpenScreen(new SlayerConsistencyScreen(null)))));
            }
        });
    }

    /** Public for the game test. */
    public static void add(String boss, long ms, long at) {
        List<Kill> kills = KILLS.computeIfAbsent(boss, k -> new ArrayList<>());
        kills.add(new Kill(ms, at));
        if (kills.size() > MAX_KILLS) kills.removeFirst();
    }

    /** The bosses with kills this session, oldest first. */
    public static List<String> bosses() {
        return List.copyOf(KILLS.keySet());
    }

    /** {@code boss}'s kills this session, oldest first. */
    public static List<Kill> kills(String boss) {
        return List.copyOf(KILLS.getOrDefault(boss, List.of()));
    }

    /** Public for the game test. */
    public static Stats stats(List<Kill> kills) {
        if (kills.isEmpty()) return new Stats(0, 0, 0, 0, 0);
        long best = Long.MAX_VALUE, worst = 0;
        double total = 0;
        for (Kill kill : kills) {
            best = Math.min(best, kill.ms());
            worst = Math.max(worst, kill.ms());
            total += kill.ms();
        }
        double average = total / kills.size(), squares = 0;
        for (Kill kill : kills) squares += (kill.ms() - average) * (kill.ms() - average);
        return new Stats(kills.size(), Math.round(average), best, worst, Math.round(Math.sqrt(squares / kills.size())));
    }
}
