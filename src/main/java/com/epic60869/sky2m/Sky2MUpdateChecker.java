package com.epic60869.sky2m;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tells you in chat when a newer Sky2M is out: "New Sky2M Mod Version 1.2.3 --> 1.2.5" (the newest release, even if
 * you're several versions behind), with a link to the download. Checked from GitHub's latest release (2m3s/Sky2M-26.3)
 * when you join a server and every minute while you're in game, so a release made while you play shows up within a
 * minute. Repeat checks send the last answer's ETag, and GitHub's "not changed" (304) doesn't count toward its limit.
 * Each new version is only announced once per game session.
 */
public final class Sky2MUpdateChecker {
    private static final String LATEST_URL = "https://api.github.com/repos/2m3s/Sky2M-26.3/releases/latest";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Sky2M");
    private static final long CHECK_EVERY_MS = 60_000L;
    /** GitHub's ETag for the last answer: asking "changed since?" gets a 304 that doesn't count toward the rate limit. */
    private static volatile String etag;

    private static long lastCheck;
    private static volatile boolean checking;
    private static String announced = "";

    private Sky2MUpdateChecker() {}

    /** The Sky2M server already announced this version; don't announce it again from GitHub. */
    public static void markAnnounced(String version) {
        announced = version;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> check());
        // Keep checking while you play (every CHECK_EVERY_MS), not only when you join.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) check();
        });
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.misc.updateNotifications;
    }

    private static String installed() {
        return FabricLoader.getInstance().getModContainer("sky2m")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
    }

    private static void check() {
        long now = System.currentTimeMillis();
        if (!enabled() || checking || now - lastCheck < CHECK_EVERY_MS) return;
        lastCheck = now;
        checking = true;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(LATEST_URL))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Sky2M/" + installed());
        if (etag != null) builder.header("If-None-Match", etag);
        HttpRequest request = builder.GET().build();
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() == 304) return; // no new release since the last check
            if (response.statusCode() != 200) {
                LOGGER.info("[Sky2M] Update check: GitHub answered HTTP {}", response.statusCode());
                return;
            }
            response.headers().firstValue("ETag").ifPresent(tag -> etag = tag);
            JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
            String latest = release.get("tag_name").getAsString().replaceFirst("^[vV]", "").trim();
            String url = release.has("html_url") ? release.get("html_url").getAsString() : "https://github.com/2m3s/Sky2M-26.3/releases/latest";
            String current = installed();
            LOGGER.info("[Sky2M] Update check: installed {}, latest {}", current, latest);
            if (current.isEmpty() || compare(latest, current) <= 0 || latest.equals(announced)) return;
            announced = latest;
            // Give the server's join messages a moment so this doesn't get buried.
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> announce(current, latest, url));
        }).exceptionally(e -> {
            LOGGER.info("[Sky2M] Update check failed: {}", e.getMessage());
            return null;
        }).whenComplete((v, e) -> checking = false);
    }

    private static void announce(String current, String latest, String url) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            Component message = Component.literal("New Sky2M Mod Version ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal(current).withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(false)))
                .append(Component.literal(" --> ").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)))
                .append(Component.literal(latest).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(false)))
                .append(Component.literal("  [Download]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(false).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the Sky2M " + latest + " release on GitHub")))));
            mc.gui.hud.getChat().addClientSystemMessage(message);
        });
    }

    /**
     * Compares versions like "1.2.10" and "1.2.9" number by number; a missing part counts as 0. A test build
     * ("test-1.3.9") is that version's number, just before its release.
     */
    static int compare(String a, String b) {
        String numberA = a.replaceFirst("^\\D+", ""), numberB = b.replaceFirst("^\\D+", "");
        int result = compareNumbers(numberA, numberB);
        if (result != 0) return result;
        return Boolean.compare(numberA.equals(a), numberB.equals(b));
    }

    private static int compareNumbers(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0;
            int q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String part) {
        String digits = part.replaceAll("\\D.*$", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }
}
