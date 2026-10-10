package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MConfig;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends Sky2M errors to the mod server ({@code POST /mod-api/crash-reports}) so they can be fixed: uncaught
 * exceptions that went through Sky2M code, and errors caught around feature code. Each distinct stack trace is
 * sent once per game session, at most 10 an hour, and only while "Crash Reports" is on. Only the trace, versions,
 * OS, Java, your name and UUID are sent.
 */
public final class SbcCrashReports {
    private static final String URL = "https://shadowisabot.com/mod-api/crash-reports";
    private static final int MAX_TRACE = 32_000;
    private static final int PER_HOUR = 10;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Set<String> SENT = ConcurrentHashMap.newKeySet();
    private static final Deque<Long> RECENT = new ArrayDeque<>();

    private SbcCrashReports() {}

    public static void init() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                if (ours(error)) report(error, "uncaught in thread " + thread.getName());
            } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(thread, error);
            else error.printStackTrace();
        });
    }

    private static boolean ours(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            for (StackTraceElement element : t.getStackTrace()) {
                if (element.getClassName().startsWith("com.epic60869.sky2m")) return true;
            }
        }
        return false;
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return (c == null || c.online.crashReports) && Flags.isEnabled("crashReports");
    }

    /** Reports an error caught around feature code ({@code context} says where). Safe from any thread. */
    public static void report(Throwable error, String context) {
        System.err.println("[Sky2M] " + context + " failed: " + error);
        if (error == null || !enabled()) return;
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String trace = writer.toString();
        if (trace.length() > MAX_TRACE) trace = trace.substring(0, MAX_TRACE);
        // The same error from the same place is the same report, whatever the message says.
        String key = error.getClass().getName() + "|" + trace.lines().skip(1).limit(6).reduce("", String::concat);
        if (!SENT.add(key)) return;
        synchronized (RECENT) {
            long now = System.currentTimeMillis();
            while (!RECENT.isEmpty() && now - RECENT.peekFirst() > 3_600_000L) RECENT.pollFirst();
            if (RECENT.size() >= PER_HOUR) return;
            RECENT.addLast(now);
        }

        Minecraft mc = Minecraft.getInstance();
        JsonObject body = new JsonObject();
        body.addProperty("stackTrace", trace);
        body.addProperty("modVersion", SbcInfo.modVersion());
        body.addProperty("mcVersion", SbcInfo.mcVersion());
        body.addProperty("loader", "fabric " + FabricLoader.getInstance().getModContainer("fabricloader")
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""));
        body.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        body.addProperty("java", System.getProperty("java.version") + " " + System.getProperty("java.vendor"));
        body.addProperty("username", mc.getUser().getName());
        body.addProperty("uuid", mc.getUser().getProfileId().toString());
        body.addProperty("context", context == null ? "" : context);
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("User-Agent", "Sky2M/" + SbcInfo.modVersion())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
    }
}
