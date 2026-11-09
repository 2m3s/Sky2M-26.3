package com.epic60869.sky2m.sim.relay;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Dungeon Sim parties across the internet, Essential/e4mc style: no port forwarding. The host's game keeps one
 * WebSocket to the Sky2M mod-server's relay ({@code wss://shadowisabot.com/mod-api/sim-relay/host}); each guest's game
 * opens a local port, points Minecraft at it, and the relay pipes the bytes between the two games. The Minecraft
 * connection inside is the normal online-mode LAN one (Mojang login and encryption between the two games), so the
 * relay only ever sees an encrypted byte stream.
 *
 * <p>Both ends log in to the relay like Sky2M chat does ({@link com.epic60869.sky2m.social.Sky2MLogin}): a serverId
 * from {@code /mod-api/auth/challenge}, "joined" through Mojang's session server, sent as the first message. The relay
 * is invite-only: a guest needs the party code AND to be on the host's invite list ({@link #allow}).
 *
 * <p>For {@code /simparty}:
 * <ul>
 *   <li>{@link #host(int, Consumer)} once the sim world is open to LAN on {@code localPort}; {@code onCode} gets the
 *       party code (on the render thread).</li>
 *   <li>{@link #allow(String)} for every invited player (name or UUID); allowed before or while hosting.</li>
 *   <li>{@link #stopHosting()} when the party ends or the world closes.</li>
 *   <li>{@link #joinAndConnect(String)} on the guest's side: leaves the current world (saving it) and connects.</li>
 * </ul>
 * All network and socket work runs on background (virtual) threads; callbacks hop to the render thread. Nothing here
 * blocks the game. {@code -Dsky2m.simRelay=ws://127.0.0.1:3003/sim-relay} and {@code -Dsky2m.simRelayApi=http://127.0.0.1:3003}
 * point it at a local mod-server for testing.
 *
 * <p>Protocol (see {@code sim-relay.js} on the mod-server): JSON text frames for control
 * ({@code auth, code, allow, disallow, open, opened, close, ready}); binary frames are {@code [stream id u32][bytes]}
 * on the host's socket and raw bytes on a guest's.
 */
public final class SimRelay {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String WS_BASE = System.getProperty("sky2m.simRelay", "wss://shadowisabot.com/mod-api/sim-relay");
    private static final String API_BASE = System.getProperty("sky2m.simRelayApi", "https://shadowisabot.com/mod-api");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** Bytes read from a local socket per frame; the relay accepts frames up to 256 KB. */
    private static final int CHUNK = 16 * 1024;
    /** How long a guest's local port waits for Minecraft to connect. */
    private static final int ACCEPT_TIMEOUT_MS = 60_000;

    private static volatile HostSession hosting;
    private static volatile GuestSession joining;
    /** Invited players (as given, lowercased names or UUIDs); sent when hosting starts and on every change. */
    private static final Set<String> INVITES = new LinkedHashSet<>();
    private static volatile String lastError = "";

    private SimRelay() {}

    // ---------------------------------------------------------------- public API (host)

    /** {@link #host(int, Consumer, Consumer)} without a stopped callback. */
    public static void host(int localPort, Consumer<String> onCode) {
        host(localPort, onCode, null);
    }

    /**
     * Starts hosting the LAN world on {@code 127.0.0.1:localPort} through the relay (stops any earlier hosting first).
     * {@code onCode} gets the party code once the relay gives one; {@code onStopped} (may be null) gets the reason
     * when hosting ends for any reason other than {@link #stopHosting()}. Both run on the render thread.
     */
    public static void host(int localPort, Consumer<String> onCode, Consumer<String> onStopped) {
        // Keeps the invites given so far (allow() before host() is fine).
        HostSession old = hosting;
        hosting = null;
        if (old != null) old.close("Stopped hosting", false);
        HostSession session = new HostSession(localPort, onCode, onStopped);
        hosting = session;
        session.start();
    }

    /** Invites a player (Minecraft name or UUID) to join; only invited players can join. Works before or while hosting. */
    public static void allow(String player) {
        String key = inviteKey(player);
        if (key.isEmpty()) return;
        synchronized (INVITES) {
            INVITES.add(key);
        }
        HostSession session = hosting;
        if (session != null) session.sendInvite("allow", key);
    }

    /** Takes an invite back; that player is disconnected if they are in the party. */
    public static void disallow(String player) {
        String key = inviteKey(player);
        if (key.isEmpty()) return;
        synchronized (INVITES) {
            INVITES.remove(key);
        }
        HostSession session = hosting;
        if (session != null) session.sendInvite("disallow", key);
    }

    /** Stops hosting: closes the relay connection and every guest's stream, and forgets the invites. */
    public static void stopHosting() {
        HostSession session = hosting;
        hosting = null;
        synchronized (INVITES) {
            INVITES.clear();
        }
        if (session != null) session.close("Stopped hosting", false);
    }

    /** True while hosting (connecting to the relay or holding a code). */
    public static boolean isHosting() {
        HostSession session = hosting;
        return session != null && !session.closed;
    }

    /** The party code while hosting, or null (not hosting, or the relay hasn't answered yet). */
    public static String currentCode() {
        HostSession session = hosting;
        return session == null || session.closed ? null : session.code;
    }

    /** How many guests are connected through the relay right now. */
    public static int guestCount() {
        HostSession session = hosting;
        return session == null ? 0 : session.streams.size();
    }

    /** Why the last relay connection (host or guest) failed or ended, or "". */
    public static String lastError() {
        return lastError;
    }

    // ---------------------------------------------------------------- public API (guest)

    /** {@link #join(String, IntConsumer, Consumer)} without a failure callback (the reason goes to {@link #lastError()}). */
    public static void join(String code, IntConsumer onLocalPort) {
        join(code, onLocalPort, null);
    }

    /**
     * Joins a party: opens a local port on 127.0.0.1 and connects it to the host through the relay. Once the host is
     * connected, {@code onLocalPort} gets the port (render thread) and the caller connects Minecraft to
     * {@code 127.0.0.1:<port>}; the port accepts exactly one connection. {@code onFailed} (may be null) gets the reason
     * if joining fails before that (not invited, wrong code, party full, ...).
     */
    public static void join(String code, IntConsumer onLocalPort, Consumer<String> onFailed) {
        leave();
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        GuestSession session = new GuestSession(clean, onLocalPort, onFailed);
        joining = session;
        session.start();
    }

    /**
     * Joins a party and connects Minecraft to it (leaving and saving the current world first). Failures are shown in
     * chat. Call from any thread.
     */
    public static void joinAndConnect(String code) {
        join(code, port -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) mc.disconnectWithSavingScreen();
            ServerData data = new ServerData("Dungeon Sim party", "127.0.0.1:" + port, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc, new ServerAddress("127.0.0.1", port), data, false, null);
        }, reason -> chat("Couldn't join the party: " + reason));
    }

    /** Cancels joining, or ends the current guest connection through the relay. */
    public static void leave() {
        GuestSession session = joining;
        joining = null;
        if (session != null) session.close("Left", false);
    }

    /** True while joining or connected to a party as a guest through the relay. */
    public static boolean isJoined() {
        GuestSession session = joining;
        return session != null && !session.closed;
    }

    // ---------------------------------------------------------------- shared plumbing

    private static String inviteKey(String player) {
        if (player == null) return "";
        String value = player.trim().toLowerCase(Locale.ROOT);
        return value.matches("[0-9a-f-]{32,36}|[a-z0-9_]{1,16}") ? value : "";
    }

    private static void onRender(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    private static void chat(String text) {
        onRender(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[S2M] " + text).withColor(0xFF5555));
        });
    }

    private static Thread thread(String name, Runnable task) {
        return Thread.ofVirtual().name(name).start(task);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * Logs in like Sky2M chat (challenge, Mojang join) and opens an authenticated relay WebSocket; {@code extra} is
     * merged into the auth packet. Everything off the render thread.
     */
    private static CompletableFuture<WebSocket> openAuthenticated(String url, WebSocket.Listener listener, JsonObject extra) {
        Minecraft mc = Minecraft.getInstance();
        HttpRequest challenge = HttpRequest.newBuilder(URI.create(API_BASE + "/auth/challenge"))
            .timeout(Duration.ofSeconds(15)).header("User-Agent", userAgent()).GET().build();
        return HTTP.sendAsync(challenge, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
            if (response.statusCode() != 200) throw new IllegalStateException("The Sky2M server didn't answer (HTTP " + response.statusCode() + ")");
            String serverId = JsonParser.parseString(response.body()).getAsJsonObject().get("serverId").getAsString();
            try {
                mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
            } catch (Exception e) {
                throw new IllegalStateException("Mojang login failed (this needs a real Minecraft account)");
            }
            return serverId;
        }).thenCompose(serverId -> HTTP.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .header("User-Agent", userAgent())
            .buildAsync(URI.create(url), listener)
            .thenCompose(ws -> {
                JsonObject auth = extra == null ? new JsonObject() : extra.deepCopy();
                auth.addProperty("type", "auth");
                auth.addProperty("serverId", serverId);
                auth.addProperty("username", mc.getUser().getName());
                return ws.sendText(auth.toString(), true);
            }));
    }

    private static String userAgent() {
        String version;
        try {
            version = com.epic60869.sky2m.features.sbc.SbcInfo.modVersion();
        } catch (Throwable t) {
            version = "?";
        }
        return "Sky2M/" + version;
    }

    /** Sends one frame at a time (java.net.http allows only one outstanding send); each future completes when sent. */
    private static final class Sender {
        private final WebSocket ws;
        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

        Sender(WebSocket ws) {
            this.ws = ws;
        }

        synchronized CompletableFuture<?> binary(ByteBuffer data) {
            tail = tail.thenCompose(ignored -> ws.sendBinary(data, true));
            return tail;
        }

        synchronized CompletableFuture<?> text(String data) {
            tail = tail.thenCompose(ignored -> ws.sendText(data, true));
            return tail;
        }

        void close(String reason) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception ignored) {
            }
            // If the close handshake doesn't finish, drop the connection anyway.
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(ws::abort);
        }
    }

    /** Puts message parts back together; subclasses get whole text/binary messages and one final close call. */
    private abstract static class Frames implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private boolean ended;

        abstract void onJson(JsonObject packet);

        abstract void onBytes(ByteBuffer data);

        abstract void onEnded(int code, String reason);

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                try {
                    onJson(JsonParser.parseString(message).getAsJsonObject());
                } catch (Exception e) {
                    LOGGER.debug("[Sky2M] Sim relay: bad message {}", message, e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            try {
                if (last && binary.size() == 0) {
                    onBytes(data);
                } else {
                    byte[] part = new byte[data.remaining()];
                    data.get(part);
                    binary.writeBytes(part);
                    if (last) {
                        onBytes(ByteBuffer.wrap(binary.toByteArray()));
                        binary.reset();
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[Sky2M] Sim relay: couldn't pass on bytes", e);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            end(code, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            end(-1, rootMessage(error));
        }

        private synchronized void end(int code, String reason) {
            if (ended) return;
            ended = true;
            onEnded(code, reason);
        }
    }

    /** Writes into a local socket once it's connected, keeping anything that arrives before. */
    private static final class LocalPipe {
        private OutputStream out;
        private final List<byte[]> pending = new ArrayList<>();
        private boolean closed;

        synchronized void write(ByteBuffer data) throws IOException {
            if (closed) return;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            if (out == null) pending.add(bytes);
            else out.write(bytes);
        }

        synchronized void connected(OutputStream stream) throws IOException {
            out = stream;
            for (byte[] bytes : pending) out.write(bytes);
            pending.clear();
            out.flush();
        }

        synchronized void close() {
            closed = true;
            pending.clear();
        }
    }

    private static void pump(InputStream in, Sender sender, int streamId) throws IOException {
        byte[] buffer = new byte[CHUNK];
        int n;
        while ((n = in.read(buffer)) > 0) {
            ByteBuffer frame;
            if (streamId >= 0) {
                frame = ByteBuffer.allocate(4 + n);
                frame.putInt(streamId).put(buffer, 0, n).flip();
            } else {
                frame = ByteBuffer.wrap(buffer.clone(), 0, n);
            }
            // Wait until it's sent: the relay's pace (and its bandwidth caps) slow down reading the local socket.
            sender.binary(frame).join();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- host

    private static final class HostSession {
        final int port;
        final Consumer<String> onCode;
        final Consumer<String> onStopped;
        final Map<Integer, HostStream> streams = new ConcurrentHashMap<>();
        volatile Sender sender;
        volatile String code;
        volatile boolean closed;

        HostSession(int port, Consumer<String> onCode, Consumer<String> onStopped) {
            this.port = port;
            this.onCode = onCode;
            this.onStopped = onStopped;
        }

        void start() {
            JsonObject extra = new JsonObject();
            JsonArray allow = new JsonArray();
            synchronized (INVITES) {
                INVITES.forEach(allow::add);
            }
            extra.add("allow", allow);
            openAuthenticated(WS_BASE + "/host", new Frames() {
                @Override
                void onJson(JsonObject packet) {
                    handle(packet);
                }

                @Override
                void onBytes(ByteBuffer data) {
                    if (data.remaining() < 4) return;
                    HostStream stream = streams.get(data.getInt());
                    if (stream == null) return;
                    try {
                        stream.pipe.write(data);
                    } catch (IOException e) {
                        stream.close("Local connection failed", true);
                    }
                }

                @Override
                void onEnded(int code, String reason) {
                    close(reason == null || reason.isBlank() ? "Lost the connection to the relay" : reason, true);
                }
            }, extra).whenComplete((ws, error) -> {
                if (error != null) {
                    close(rootMessage(error), true);
                    return;
                }
                if (closed) {
                    ws.abort();
                    return;
                }
                sender = new Sender(ws);
            });
        }

        // The listener can get "code" before buildAsync's future completes, so the sender may still be null here.
        private Sender senderWhenReady() {
            for (int i = 0; i < 100 && sender == null && !closed; i++) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return sender;
        }

        void handle(JsonObject packet) {
            String type = packet.has("type") ? packet.get("type").getAsString() : "";
            switch (type) {
                case "code" -> {
                    code = packet.get("code").getAsString();
                    loggedIn();
                    LOGGER.info("[Sky2M] Sim relay: hosting on port {}", port);
                    if (onCode != null) onRender(() -> {
                        if (!closed && hosting == this) onCode.accept(code);
                    });
                }
                case "open" -> {
                    int id = packet.get("stream").getAsInt();
                    String name = packet.has("name") ? packet.get("name").getAsString() : "?";
                    HostStream stream = new HostStream(this, id, name);
                    streams.put(id, stream);
                    thread("Sky2M sim relay " + name, stream::run);
                }
                case "close" -> {
                    HostStream stream = streams.get(packet.get("stream").getAsInt());
                    if (stream != null) stream.close("Left", false);
                }
                case "error" -> LOGGER.warn("[Sky2M] Sim relay: {}", packet.has("message") ? packet.get("message").getAsString() : packet);
                default -> {
                }
            }
        }

        /**
         * Messages held until the relay has checked the login: it answers with "code" then, and anything sent before
         * (an invite while Mojang's check is still running) makes it close the party with "Log in first".
         */
        private final List<JsonObject> beforeLogin = new ArrayList<>();
        private boolean loggedIn;

        void send(JsonObject packet) {
            synchronized (beforeLogin) {
                if (!loggedIn) {
                    beforeLogin.add(packet);
                    return;
                }
            }
            thread("Sky2M sim relay send", () -> {
                Sender s = senderWhenReady();
                if (s != null && !closed) s.text(packet.toString());
            });
        }

        private void loggedIn() {
            List<JsonObject> held;
            synchronized (beforeLogin) {
                if (loggedIn) return;
                loggedIn = true;
                held = new ArrayList<>(beforeLogin);
                beforeLogin.clear();
            }
            held.forEach(this::send);
        }

        void sendInvite(String type, String key) {
            JsonObject packet = new JsonObject();
            packet.addProperty("type", type);
            packet.addProperty("name", key);
            send(packet);
        }

        void close(String reason, boolean unexpected) {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            for (HostStream stream : streams.values()) stream.close(reason, false);
            streams.clear();
            Sender s = sender;
            if (s != null) s.close(reason);
            if (unexpected) {
                lastError = reason;
                LOGGER.info("[Sky2M] Sim relay: hosting ended ({})", reason);
                onRender(() -> {
                    if (hosting == this) hosting = null;
                    if (onStopped != null) onStopped.accept(reason);
                });
            }
        }
    }

    /** One guest, on the host's side: a local connection to the LAN world piped through the host's relay socket. */
    private static final class HostStream {
        final HostSession session;
        final int id;
        final String name;
        final LocalPipe pipe = new LocalPipe();
        volatile Socket socket;
        volatile boolean closed;

        HostStream(HostSession session, int id, String name) {
            this.session = session;
            this.id = id;
            this.name = name;
        }

        void run() {
            Socket local = new Socket();
            try {
                local.setTcpNoDelay(true);
                local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), session.port), 5000);
                socket = local;
                if (closed) {
                    closeQuietly(local);
                    return;
                }
                pipe.connected(local.getOutputStream());
                Sender sender = session.senderWhenReady();
                if (sender == null) throw new IOException("Not connected to the relay");
                JsonObject opened = new JsonObject();
                opened.addProperty("type", "opened");
                opened.addProperty("stream", id);
                sender.text(opened.toString());
                LOGGER.info("[Sky2M] Sim relay: {} joined", name);
                pump(local.getInputStream(), sender, id);
                close("The world closed the connection", true);
            } catch (Exception e) {
                close(closed ? "Left" : "Couldn't reach the world: " + rootMessage(e), true);
            } finally {
                closeQuietly(local);
            }
        }

        void close(String reason, boolean tellRelay) {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            session.streams.remove(id, this);
            pipe.close();
            closeQuietly(socket);
            Sender sender = session.sender;
            if (tellRelay && sender != null && !session.closed) {
                JsonObject packet = new JsonObject();
                packet.addProperty("type", "close");
                packet.addProperty("stream", id);
                packet.addProperty("reason", reason);
                sender.text(packet.toString());
            }
            LOGGER.info("[Sky2M] Sim relay: {} left ({})", name, reason);
        }
    }

    // ---------------------------------------------------------------- guest

    private static final class GuestSession {
        final String code;
        final IntConsumer onLocalPort;
        final Consumer<String> onFailed;
        final LocalPipe pipe = new LocalPipe();
        volatile ServerSocket server;
        volatile Socket socket;
        volatile Sender sender;
        volatile WebSocket ws;
        volatile boolean ready;
        volatile boolean closed;

        GuestSession(String code, IntConsumer onLocalPort, Consumer<String> onFailed) {
            this.code = code;
            this.onLocalPort = onLocalPort;
            this.onFailed = onFailed;
        }

        void start() {
            try {
                server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                server.setSoTimeout(ACCEPT_TIMEOUT_MS);
            } catch (IOException e) {
                close("Couldn't open a local port: " + rootMessage(e), true);
                return;
            }
            String url = WS_BASE + "/join?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
            openAuthenticated(url, new Frames() {
                @Override
                void onJson(JsonObject packet) {
                    if (packet.has("type") && "ready".equals(packet.get("type").getAsString())) onReady();
                }

                @Override
                void onBytes(ByteBuffer data) {
                    try {
                        pipe.write(data);
                    } catch (IOException e) {
                        close("Minecraft's connection failed", true);
                    }
                }

                @Override
                void onEnded(int code, String reason) {
                    close(reason == null || reason.isBlank() ? "Lost the connection to the relay" : reason, true);
                }
            }, null).whenComplete((relay, error) -> {
                if (error != null) {
                    close(rootMessage(error), true);
                    return;
                }
                ws = relay;
                sender = new Sender(relay);
                if (closed) relay.abort();
            });
        }

        void onReady() {
            if (ready || closed) return;
            ready = true;
            ServerSocket listening = server;
            if (listening == null) return;
            int port = listening.getLocalPort();
            thread("Sky2M sim relay guest", () -> {
                try (ServerSocket only = listening) {
                    Socket mc = only.accept();
                    // Exactly one connection: Minecraft's.
                    closeQuietly(only);
                    server = null;
                    mc.setTcpNoDelay(true);
                    socket = mc;
                    if (closed) return;
                    pipe.connected(mc.getOutputStream());
                    for (int i = 0; i < 100 && sender == null && !closed; i++) Thread.sleep(20);
                    Sender s = sender;
                    if (s == null) throw new IOException("Not connected to the relay");
                    pump(mc.getInputStream(), s, -1);
                    close("Disconnected", false);
                } catch (Exception e) {
                    close(closed ? "Left" : "Minecraft didn't connect: " + rootMessage(e), false);
                }
            });
            LOGGER.info("[Sky2M] Sim relay: joined a party, local port {}", port);
            onRender(() -> {
                if (!closed && joining == this && onLocalPort != null) onLocalPort.accept(port);
            });
        }

        void close(String reason, boolean fromRelay) {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            pipe.close();
            closeQuietly(server);
            closeQuietly(socket);
            Sender s = sender;
            if (s != null) s.close(reason);
            else if (ws != null) ws.abort();
            LOGGER.info("[Sky2M] Sim relay: left the party ({})", reason);
            if (fromRelay) lastError = reason;
            // Before "ready" it's a failed join; after, Minecraft's own disconnect screen says what happened.
            if (fromRelay && !ready) onRender(() -> {
                if (joining == this) joining = null;
                if (onFailed != null) onFailed.accept(reason);
            });
            else onRender(() -> {
                if (joining == this) joining = null;
            });
        }
    }
}
