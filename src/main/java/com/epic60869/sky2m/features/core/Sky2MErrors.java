package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MSelfTest;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.sbc.SbcCrashReports;
import com.epic60869.sky2m.features.sbc.SbcInfo;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Error Reporter: remembers errors caught around Sky2M feature callbacks (HUDs, chat listeners, in-world renderers)
 * and offers, in chat, to send one to staff as a bug report. A broken HUD throws every frame, so each distinct error
 * (feature + exception + top frame) is logged in full once and then only counted, and prompted at most once a session,
 * with at most one prompt every 2 minutes. Nothing is sent without the player clicking [Report to staff] and then
 * [Send].
 */
public final class Sky2MErrors {
    private static final int STACK_LINES = 15;
    private static final long PROMPT_GAP_MS = 120_000L;
    /** How often a repeating error's count goes to the log. */
    private static final long LOG_REPEAT_MS = 60_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One distinct error this session. */
    public static final class Record {
        public final int id;
        public final String feature;
        public final String exception;
        public final String message;
        public final String stack;
        public final String firstSeen;
        volatile int count;
        volatile boolean prompted;
        volatile boolean ignored;
        volatile boolean sent;

        Record(int id, String feature, Throwable error) {
            this.id = id;
            this.feature = feature;
            this.exception = error.getClass().getSimpleName();
            String m = error.getMessage();
            this.message = m == null ? "" : m;
            this.stack = stackHead(error);
            this.firstSeen = LocalTime.now().format(TIME);
        }

        public int count() {
            return count;
        }

        /** The error as text, for [Copy] and the bug report. */
        public String text() {
            return feature + " hit " + exception + (message.isEmpty() ? "" : ": " + message)
                + "\nFirst at " + firstSeen + ", " + count + " time" + (count == 1 ? "" : "s") + " this session\n" + stack;
        }
    }

    private static final Map<String, Record> RECORDS = new ConcurrentHashMap<>();
    private static final Map<Integer, Record> BY_ID = new ConcurrentHashMap<>();
    /** Log throttling, by where + exception + top frame: {count since last log line, last log time}. */
    private static final Map<String, long[]> LOGGED = new ConcurrentHashMap<>();
    private static int nextId = 1;
    private static volatile long lastPromptAt;

    private Sky2MErrors() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("errors")
                    .executes(c -> list())
                    .then(ClientCommands.literal("report").then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                        .executes(c -> confirm(IntegerArgumentType.getInteger(c, "id")))))
                    .then(ClientCommands.literal("send").then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                        .executes(c -> send(IntegerArgumentType.getInteger(c, "id")))))
                    .then(ClientCommands.literal("copy").then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                        .executes(c -> copy(IntegerArgumentType.getInteger(c, "id")))))
                    .then(ClientCommands.literal("ignore").then(ClientCommands.argument("id", IntegerArgumentType.integer(1))
                        .executes(c -> ignore(IntegerArgumentType.getInteger(c, "id")))))));
            }
        });
    }

    /**
     * An error caught around a feature callback that also goes to {@link SbcCrashReports} (as it did before), which
     * logs it through {@link #log}.
     */
    public static void caught(String feature, Throwable error, String context) {
        record(feature, error);
        SbcCrashReports.report(error, context);
    }

    /** An error caught around a feature callback that was only swallowed before: recorded and logged, not sent. */
    public static void caught(String feature, Throwable error) {
        record(feature, error);
        log(feature, error);
    }

    /**
     * Logs an error caught at {@code where}: the first time in full, after that only a count once a minute, so a
     * HUD that throws every frame doesn't fill the log.
     */
    public static void log(String where, Throwable error) {
        if (error == null) return;
        String key = where + "|" + error.getClass().getName() + "|" + topFrame(error);
        long now = System.currentTimeMillis();
        long[] seen = LOGGED.get(key);
        if (seen == null) {
            LOGGED.put(key, new long[]{0, now});
            System.err.println("[Sky2M] " + where + " failed: " + error);
            error.printStackTrace();
            return;
        }
        synchronized (seen) {
            seen[0]++;
            if (now - seen[1] < LOG_REPEAT_MS) return;
            System.err.println("[Sky2M] " + where + " failed " + seen[0] + " more time" + (seen[0] == 1 ? "" : "s") + ": " + error);
            seen[0] = 0;
            seen[1] = now;
        }
    }

    private static void record(String feature, Throwable error) {
        if (error == null) return;
        String name = feature == null || feature.isBlank() ? "A feature" : feature;
        String key = name + "|" + error.getClass().getName() + "|" + topFrame(error);
        Record record = RECORDS.get(key);
        if (record == null) {
            synchronized (RECORDS) {
                record = RECORDS.get(key);
                if (record == null) {
                    record = new Record(nextId++, name, error);
                    RECORDS.put(key, record);
                    BY_ID.put(record.id, record);
                }
            }
        }
        record.count++;
        maybePrompt(record);
    }

    private static void maybePrompt(Record record) {
        if (record.prompted || record.ignored) return;
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null && !config.general.errorReporter) return;
        long now = System.currentTimeMillis();
        // A skipped error is offered the next time it happens after the gap; a broken feature keeps throwing.
        if (now - lastPromptAt < PROMPT_GAP_MS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        lastPromptAt = now;
        record.prompted = true;
        String detail = record.exception + (record.message.isEmpty() ? "" : ": " + shorten(record.message, 80));
        MutableComponent line = Component.literal("⚠ Sky2M: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(record.feature).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" hit an error (" + detail + ") ").withStyle(ChatFormatting.YELLOW))
            .append(button("[Report to staff]", ChatFormatting.GREEN, "s2 errors report " + record.id,
                "Send this error to the Sky2M team as a bug report (asks first)"))
            .append(Component.literal(" "))
            .append(button("[Copy]", ChatFormatting.AQUA, "s2 errors copy " + record.id, "Copy the error and its stack trace"))
            .append(Component.literal(" "))
            .append(button("[Ignore]", ChatFormatting.GRAY, "s2 errors ignore " + record.id, "Don't mention this error again this session"));
        say(line);
    }

    /** Every error this session, newest first. */
    public static List<Record> records() {
        List<Record> out = new ArrayList<>(BY_ID.values());
        out.sort((a, b) -> Integer.compare(b.id, a.id));
        return out;
    }

    private static int list() {
        List<Record> records = records();
        if (records.isEmpty()) {
            say(prefix().append(Component.literal("No Sky2M feature errors this session.").withStyle(ChatFormatting.GREEN)));
            return 1;
        }
        MutableComponent message = prefix().append(Component.literal("Feature errors this session:").withStyle(ChatFormatting.GOLD));
        for (Record r : records) {
            message.append(Component.literal("\n " + r.feature + ": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(r.exception + " ×" + r.count + " ").withStyle(ChatFormatting.GRAY))
                .append(button("[Report]", ChatFormatting.GREEN, "s2 errors report " + r.id, "Send this error to the Sky2M team (asks first)"))
                .append(Component.literal(" "))
                .append(button("[Copy]", ChatFormatting.AQUA, "s2 errors copy " + r.id, "Copy the error and its stack trace"));
        }
        say(message);
        return 1;
    }

    /** [Report to staff] only asks; [Send] here is what sends, so a stray click never files a report. */
    private static int confirm(int id) {
        Record r = BY_ID.get(id);
        if (r == null) return notFound();
        if (r.sent) {
            say(prefix().append(Component.literal("That error was already sent to staff.").withStyle(ChatFormatting.GRAY)));
            return 1;
        }
        say(prefix().append(Component.literal("Send " + r.feature + "'s error to staff? It includes the stack trace, the /s2 selftest "
                + "report, your Sky2M and Minecraft versions and recent game log errors (never chat). ").withStyle(ChatFormatting.YELLOW))
            .append(button("[Send]", ChatFormatting.GREEN, "s2 errors send " + id, "Send the bug report now"))
            .append(Component.literal(" "))
            .append(button("[Cancel]", ChatFormatting.GRAY, "s2 errors", "Don't send it")));
        return 1;
    }

    private static int send(int id) {
        Record r = BY_ID.get(id);
        if (r == null) return notFound();
        if (r.sent) return confirm(id);
        r.sent = true;
        say(prefix().append(Component.literal("Sending the error report...").withStyle(ChatFormatting.GRAY)));
        String title = shorten(r.feature + ": " + r.exception + (r.message.isEmpty() ? "" : " (" + r.message + ")"), 100);
        String description = "Sent from the Sky2M error reporter.\n\n" + r.text()
            + "\n\n" + Sky2MSelfTest.reportText();
        com.epic60869.sky2m.reports.ErrorReports.submitBug(title, description, (ok, problem) -> {
            if (ok) {
                say(prefix().append(Component.literal("Thanks! The error was sent to staff. /s2 reports shows replies.").withStyle(ChatFormatting.GREEN)));
            } else {
                r.sent = false;
                say(prefix().append(Component.literal("Couldn't send it: " + (problem == null || problem.isBlank() ? "try again in a moment." : problem) + " ")
                        .withStyle(ChatFormatting.RED))
                    .append(button("[Try again]", ChatFormatting.GREEN, "s2 errors report " + id, "Send it again")));
            }
        });
        return 1;
    }

    private static int copy(int id) {
        Record r = BY_ID.get(id);
        if (r == null) return notFound();
        String text = "Sky2M " + SbcInfo.modVersion() + ", Minecraft " + SbcInfo.mcVersion() + "\n" + r.text();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.keyboardHandler.setClipboard(text));
        say(prefix().append(Component.literal("Copied the error.").withStyle(ChatFormatting.GREEN)));
        return 1;
    }

    private static int ignore(int id) {
        Record r = BY_ID.get(id);
        if (r == null) return notFound();
        r.ignored = true;
        say(prefix().append(Component.literal("Ignoring " + r.feature + "'s error this session. /s2 errors lists them.").withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int notFound() {
        say(prefix().append(Component.literal("That error isn't from this session.").withStyle(ChatFormatting.RED)));
        return 0;
    }

    /** The exception line and the first frames, with causes past them cut. */
    private static String stackHead(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        StringBuilder out = new StringBuilder();
        writer.toString().lines().limit(STACK_LINES).forEach(l -> out.append(l).append('\n'));
        return out.toString().stripTrailing();
    }

    private static String topFrame(Throwable error) {
        StackTraceElement[] trace = error.getStackTrace();
        return trace.length == 0 ? "" : trace[0].toString();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    private static MutableComponent prefix() {
        return Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    private static MutableComponent button(String text, ChatFormatting colour, String command, String hover) {
        return Component.literal(text).withStyle(style -> style.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
        });
    }
}
