package com.epic60869.sky2m;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves to the SBC chat server which Minecraft account this connection is, like a Minecraft server login: the mod
 * gets a random serverId from /mod-api/auth/challenge, "joins" it through Mojang's session server, and sends it as
 * casinoAuth; the server asks Mojang whether this player joined it. Needed for things that change your data on the
 * server (/s2 nick, friends, leaderboards). A login lasts for one connection; a reconnect logs in again.
 */
public final class Sky2MLogin {
    private static final String CHALLENGE_URL = "https://shadowisabot.com/mod-api/auth/challenge";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** How long to wait for the server's authResult before counting the login as failed. */
    private static final long CONFIRM_TIMEOUT_MS = 10_000L;

    // All state below is only touched on the game thread (callbacks hop over with Minecraft.execute).
    private static Object loggedInConnection;
    /** casinoAuth sent on this connection; waiting for the server to confirm it. */
    private static Object pendingConnection;
    private static boolean loggingIn;
    /** Bumped by every login attempt and every disconnect, so callbacks of an older attempt do nothing. */
    private static int attempt;
    private static String problem = "";
    private static final List<Runnable> WAITING = new ArrayList<>();

    private Sky2MLogin() {}

    public static boolean loggedIn() {
        Object connection = Sky2MGlobalChat.currentConnection();
        return connection != null && connection == loggedInConnection;
    }

    /** Why the last login failed, or "". */
    public static String problem() {
        return problem;
    }

    /** The server forgot the login (it restarted, or answered "notLoggedIn"): log in again next time. */
    public static void forget() {
        loggedInConnection = null;
    }

    /**
     * The connection dropped: forget its login and any login still in progress on it (quietly; the reconnect logs in
     * again). Packets waiting for the login stay queued and go out after the next connection's login.
     */
    public static void onDisconnected() {
        attempt++;
        loggedInConnection = null;
        pendingConnection = null;
        loggingIn = false;
    }

    /**
     * The server's answer to casinoAuth (authResult / casinoAuthResult, or the casino's casinoState / casinoError
     * authFailed). Sending anything that needs the login before this arrives races the server's Mojang check.
     */
    public static void confirmed(boolean ok, String message) {
        Object connection = pendingConnection;
        if (connection == null) return;
        pendingConnection = null;
        loggingIn = false;
        if (ok && connection == Sky2MGlobalChat.currentConnection()) {
            loggedInConnection = connection;
            List<Runnable> ready = new ArrayList<>(WAITING);
            WAITING.clear();
            ready.forEach(Runnable::run);
            // Your slayer personal bests, so ones set offline or before sharing existed still count.
            com.epic60869.sky2m.features.slayer.SlayerLeaderboard.onLoggedIn();
            // Replies to your reports (or, for admins, reports waiting) since you were last on.
            com.epic60869.sky2m.reports.Reports.onLoggedIn();
            // A saved /s2 stafflogin logs in again on this connection.
            com.epic60869.sky2m.features.sbc.SbcStaff.onLoggedIn();
            // Finished tracker sessions, when Share Sessions to Website is on.
            com.epic60869.sky2m.features.misc.profit.SessionShare.uploadRecent();
        } else {
            problem = message == null || message.isEmpty() ? "The server couldn't verify your Minecraft login." : message;
            WAITING.clear();
        }
    }

    /** Runs {@code then} on the game thread once logged in (now, if already). Logs in if needed. */
    public static void whenLoggedIn(Runnable then) {
        if (loggedIn()) {
            if (then != null) then.run();
            return;
        }
        if (then != null) WAITING.add(then);
        Object connection = Sky2MGlobalChat.currentConnection();
        if (connection == null) {
            Sky2MGlobalChat.ensureConnected();
            return;
        }
        if (loggingIn) return;
        loggingIn = true;
        problem = "";
        int thisAttempt = ++attempt;
        Minecraft mc = Minecraft.getInstance();
        HttpRequest request = HttpRequest.newBuilder(URI.create(CHALLENGE_URL)).timeout(Duration.ofSeconds(15)).GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("challenge HTTP " + response.statusCode());
            String serverId = JsonParser.parseString(response.body()).getAsJsonObject().get("serverId").getAsString();
            try {
                mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
            } catch (Exception e) {
                throw new IllegalStateException("Mojang login failed (this needs a real Minecraft account)");
            }
            return serverId;
        }).whenComplete((serverId, e) -> mc.execute(() -> {
            if (thisAttempt != attempt) return; // disconnected meanwhile; the reconnect's login takes over
            loggingIn = false;
            if (connection != Sky2MGlobalChat.currentConnection()) {
                // Reconnected while getting the challenge: log in on the new connection instead (keeps WAITING).
                whenLoggedIn(null);
                return;
            }
            if (e != null) {
                Throwable cause = e;
                while (cause.getCause() != null) cause = cause.getCause();
                problem = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                WAITING.clear();
                return;
            }
            JsonObject auth = new JsonObject();
            auth.addProperty("type", "auth");
            auth.addProperty("serverId", serverId);
            auth.addProperty("username", mc.getUser().getName());
            if (!Sky2MGlobalChat.send(auth)) {
                WAITING.clear();
                return;
            }
            // Logged in only once the server says so; no answer in time counts as a failed login.
            loggingIn = true;
            pendingConnection = connection;
            java.util.concurrent.CompletableFuture.delayedExecutor(CONFIRM_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> mc.execute(() -> {
                    if (thisAttempt == attempt && pendingConnection == connection) {
                        confirmed(false, "The Sky2M server didn't answer the login.");
                    }
                }));
        }));
    }
}
