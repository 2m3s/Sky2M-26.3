package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 timer: a countdown or a stopwatch, set up in {@link TimerScreen}. Its HUD only shows while one is on (place it in
 * /s2 gui). A countdown ends with a title, a ding and a chat line, and the HUD says so for a few seconds. It keeps running
 * when you change server, but isn't saved when the game closes. Runs on the client thread.
 */
public final class Sky2MTimer {
    public enum Mode { TIMER, STOPWATCH }

    private static final String HUD_ID = "s2_timer";
    /** A longer time is more likely a typo. */
    static final long MAX_MS = 100L * 60 * 60 * 1000;
    private static final long DONE_SHOW_MS = 10_000L;
    private static final RepoPattern UNITS = RepoPattern.of("misc.system.Sky2MTimer.UNITS", "^(?:(\\d+(?:\\.\\d+)?)\\s*h)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*m)?\\s*(?:(\\d+(?:\\.\\d+)?)\\s*s)?$");

    private static Mode mode = Mode.TIMER;
    private static boolean on, paused;
    /** The countdown's length. */
    private static long durationMs;
    /** When it last started or resumed, and the time run before that. */
    private static long resumedAt, bankedMs;
    private static long doneUntil;

    private Sky2MTimer() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("timer")
                    .executes(c -> Compat.queueOpenScreen(new TimerScreen(null)))));
            }
        });
        Sky2MHuds.register(HUD_ID, "Timer", () -> visible(System.currentTimeMillis()), () -> lines(System.currentTimeMillis()),
            List.of(line("Timer", "4:32")), 10, 60);
        // Always listed in /s2 gui, so it can be placed before it's started.
        Sky2MHuds.setting(HUD_ID, () -> true);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long total = durationMs;
            if (finishIfDone(System.currentTimeMillis())) announce(total);
        });
    }

    private static void announce(long total) {
        Sky2MAlerts.title(Component.literal("Time's up!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
            Component.literal(format(total) + " timer").withStyle(ChatFormatting.GRAY));
        Sky2MAlerts.chat(Component.literal("Your " + format(total) + " timer is up.").withStyle(ChatFormatting.YELLOW));
    }

    // ------------------------------------------------------------------------------------------------ state

    public static void startTimer(long ms, long now) {
        start(Mode.TIMER, ms, now);
    }

    public static void startStopwatch(long now) {
        start(Mode.STOPWATCH, 0, now);
    }

    private static void start(Mode newMode, long ms, long now) {
        mode = newMode;
        durationMs = ms;
        bankedMs = 0;
        resumedAt = now;
        on = true;
        paused = false;
        doneUntil = 0;
    }

    public static void togglePause(long now) {
        if (!on) return;
        if (paused) resumedAt = now;
        else bankedMs += now - resumedAt;
        paused = !paused;
    }

    public static void stop() {
        on = false;
        paused = false;
        doneUntil = 0;
    }

    /** Ends a countdown that has run out; true the once it does. */
    public static boolean finishIfDone(long now) {
        if (!on || mode != Mode.TIMER || paused || durationMs - elapsed(now) > 0) return false;
        on = false;
        doneUntil = now + DONE_SHOW_MS;
        return true;
    }

    public static boolean isOn() {
        return on;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static Mode mode() {
        return mode;
    }

    private static long elapsed(long now) {
        return bankedMs + (paused ? 0 : now - resumedAt);
    }

    // ------------------------------------------------------------------------------------------------ text

    /** What the clock says: a countdown rounds up, so it starts on 5:00 and its last second reads 0:01. */
    public static String clock(long now) {
        if (mode == Mode.STOPWATCH) return format(elapsed(now));
        long left = Math.max(0, durationMs - elapsed(now));
        return format((left + 999) / 1000 * 1000);
    }

    /** "1:05" or, from an hour, "1:01:05". */
    public static String format(long ms) {
        long s = ms / 1000, h = s / 3600, m = s % 3600 / 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }

    private static boolean visible(long now) {
        return on || now < doneUntil;
    }

    /** The HUD's line; empty when nothing is on. */
    public static List<Component> lines(long now) {
        if (on) {
            Component line = line(mode == Mode.STOPWATCH ? "Stopwatch" : "Timer", clock(now));
            return List.of(paused ? line.copy().append(Component.literal(" (paused)").withStyle(ChatFormatting.GRAY)) : line);
        }
        if (now < doneUntil) return List.of(Component.literal("Time's up!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        return List.of();
    }

    private static Component line(String label, String time) {
        return Component.literal(label + " ").withStyle(ChatFormatting.GOLD).append(Component.literal(time).withStyle(ChatFormatting.WHITE));
    }

    /**
     * A typed time in ms: "5" is 5 minutes, also "90s", "1h 30m", "1:30" (1 minute 30) and "1:00:00". -1 if it isn't a
     * time, or is 0 or over {@link #MAX_MS}.
     */
    public static long parseMs(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        double seconds = 0;
        try {
            if (t.contains(":")) {
                String[] parts = t.split(":", -1);
                if (parts.length > 3) return -1;
                for (String part : parts) {
                    if (!part.trim().matches("\\d+")) return -1;
                    seconds = seconds * 60 + Double.parseDouble(part.trim());
                }
            } else if (t.matches("\\d+(?:\\.\\d+)?")) {
                seconds = Double.parseDouble(t) * 60;
            } else {
                Matcher m = UNITS.matcher(t);
                if (!m.matches()) return -1;
                seconds = unit(m.group(1), 3600) + unit(m.group(2), 60) + unit(m.group(3), 1);
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        double ms = seconds * 1000;
        return ms < 1000 || ms > MAX_MS ? -1 : Math.round(ms);
    }

    private static double unit(String number, int seconds) {
        return number == null ? 0 : Double.parseDouble(number) * seconds;
    }
}
