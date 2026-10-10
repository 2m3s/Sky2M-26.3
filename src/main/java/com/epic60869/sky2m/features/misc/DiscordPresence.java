package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence: "Playing Sky2M" with where you are on SkyBlock ("In the Dwarven Mines", "Royal Mines"),
 * the dungeon floor, and how long you've been playing. Talks to the Discord app over its local IPC pipe (a named pipe
 * on Windows, a Unix socket elsewhere), so it needs no library; when Discord isn't running it quietly tries again
 * every so often.
 */
public final class DiscordPresence {
    /**
     * The Discord application the presence is shown for: its name is the "Playing ..." line. Made at
     * https://discord.com/developers/applications, with a Rich Presence art asset called "sky2m". Until one is built
     * in here, the Discord Application ID setting (Misc > Discord & Screenshots) gives it; with neither, nothing is
     * sent (Discord refuses a presence without an application).
     */
    private static final String CLIENT_ID = "";
    private static final String INVITE = "https://discord.gg/7AAjvjxsby";
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final long UPDATE_EVERY_MS = 5_000L;
    private static final long RETRY_EVERY_MS = 30_000L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sky2M Discord Presence");
        t.setDaemon(true);
        return t;
    });
    private static final long STARTED = System.currentTimeMillis() / 1000L;

    private static Pipe pipe;
    /** The activity last sent ("" for cleared), so it is only sent when it changes. */
    private static String sent;
    private static long lastCheck;
    private static long lastFailure;
    private static volatile boolean busy;

    private DiscordPresence() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (busy || now - lastCheck < UPDATE_EVERY_MS) return;
            lastCheck = now;
            JsonObject activity = enabled() ? activity(mc) : null;
            busy = true;
            IO.execute(() -> {
                try {
                    update(activity);
                } finally {
                    busy = false;
                }
            });
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> IO.execute(DiscordPresence::disconnect));
    }

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.discordPresence && !clientId().isEmpty();
    }

    /** The Discord application: the setting if it's filled in, else the built-in one. */
    private static String clientId() {
        Sky2MConfig config = Sky2MConfig.current();
        String id = config == null ? "" : config.misc.discordAppId.trim();
        return id.matches("\\d{15,25}") ? id : CLIENT_ID;
    }

    // ------------------------------------------------------------------------------------------------ activity

    private static JsonObject activity(Minecraft mc) {
        String details;
        String state = "";
        Sky2MConfig config = Sky2MConfig.current();
        boolean showLocation = config != null && config.misc.discordPresenceLocation;
        if (mc.player == null) {
            details = "In the menus";
        } else if (Sky2MLocation.onSkyblock()) {
            String area = Sky2MLocation.area();
            if (!showLocation || area.isEmpty()) {
                details = "Playing SkyBlock";
            } else if (Sky2MLocation.inDungeon()) {
                String floor = Sky2MLocation.dungeonFloor();
                details = "In the Catacombs" + (floor.isEmpty() ? "" : " " + floor);
            } else {
                details = in(area);
                String location = clean(Sky2MLocation.location());
                if (!location.isEmpty() && !location.equalsIgnoreCase(area)) state = location;
            }
        } else if (onHypixel(mc)) {
            details = "On Hypixel";
        } else {
            details = mc.hasSingleplayerServer() ? "In singleplayer" : "In multiplayer";
        }

        JsonObject activity = new JsonObject();
        activity.addProperty("details", details);
        if (!state.isEmpty()) activity.addProperty("state", state);
        JsonObject timestamps = new JsonObject();
        timestamps.addProperty("start", STARTED);
        activity.add("timestamps", timestamps);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "sky2m");
        assets.addProperty("large_text", "Sky2M " + FabricLoader.getInstance().getModContainer("sky2m")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""));
        activity.add("assets", assets);
        JsonArray buttons = new JsonArray();
        JsonObject button = new JsonObject();
        button.addProperty("label", "Sky2M Discord");
        button.addProperty("url", INVITE);
        buttons.add(button);
        activity.add("buttons", buttons);
        return activity;
    }

    /** Areas said with "the": "In the Dwarven Mines", but "In Galatea". */
    private static final List<String> TAKES_THE = List.of("Garden", "Mineshaft", "Backwater Bayou", "Crimson Isle",
        "Crystal Hollows", "Dwarven Mines", "Gold Mine", "Deep Caverns", "Hub", "Dungeon Hub", "Spider's Den");

    /** "Dwarven Mines" -> "In the Dwarven Mines", "The End" -> "In The End", "Galatea" -> "In Galatea". */
    private static String in(String area) {
        if (area.equals("Private Island")) return "On their Private Island";
        return "In " + (TAKES_THE.contains(area) ? "the " + area : area);
    }

    /** The sidebar's location without symbols and padding. */
    private static String clean(String location) {
        return location.replaceAll("[^\\p{L}\\p{N} '()&-]", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean onHypixel(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        return server != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    // ------------------------------------------------------------------------------------------------ IPC

    /** Runs on the IO thread. */
    private static void update(JsonObject activity) {
        String wanted = activity == null ? "" : activity.toString();
        if (pipe == null) {
            if (activity == null) return;
            if (System.currentTimeMillis() - lastFailure < RETRY_EVERY_MS) return;
            if (!connect()) {
                lastFailure = System.currentTimeMillis();
                return;
            }
            sent = null;
        }
        if (Objects.equals(wanted, sent)) return;
        try {
            JsonObject args = new JsonObject();
            args.addProperty("pid", ProcessHandle.current().pid());
            if (activity != null) args.add("activity", activity);
            JsonObject command = new JsonObject();
            command.addProperty("cmd", "SET_ACTIVITY");
            command.add("args", args);
            command.addProperty("nonce", UUID.randomUUID().toString());
            send(OP_FRAME, command.toString());
            Frame reply = receive();
            // Discord answers a bad presence (an unknown application, a missing art asset) with an ERROR event.
            if (reply.json().contains("\"evt\":\"ERROR\"")) System.err.println("[Sky2M] Discord presence: " + reply.json());
            sent = wanted;
            // Turned off: let go of Discord until it is turned back on.
            if (activity == null) disconnect();
        } catch (IOException e) {
            disconnect();
            lastFailure = System.currentTimeMillis();
        }
    }

    private static boolean connect() {
        for (int i = 0; i < 10; i++) {
            try {
                Pipe opened = Pipe.open(i);
                if (opened == null) continue;
                pipe = opened;
                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("client_id", clientId());
                send(OP_HANDSHAKE, hello.toString());
                Frame ready = receive();
                if (ready.op() == OP_CLOSE) {
                    System.err.println("[Sky2M] Discord refused the presence handshake: " + ready.json());
                    throw new IOException("Discord refused the handshake: " + ready.json());
                }
                return true;
            } catch (IOException e) {
                closeQuietly();
            }
        }
        return false;
    }

    private static void disconnect() {
        if (pipe == null) return;
        try {
            send(OP_CLOSE, "{}");
        } catch (IOException ignored) {}
        closeQuietly();
        sent = null;
    }

    private static void closeQuietly() {
        try {
            if (pipe != null) pipe.close();
        } catch (IOException ignored) {}
        pipe = null;
    }

    private record Frame(int op, String json) {}

    private static void send(int op, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(op).putInt(body.length).put(body);
        pipe.write(frame.array());
    }

    private static Frame receive() throws IOException {
        ByteBuffer header = ByteBuffer.wrap(pipe.read(8)).order(ByteOrder.LITTLE_ENDIAN);
        int op = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > 1 << 20) throw new IOException("Bad frame from Discord");
        return new Frame(op, new String(pipe.read(length), StandardCharsets.UTF_8));
    }

    /** Discord's IPC endpoint: \\.\pipe\discord-ipc-N on Windows, a discord-ipc-N Unix socket elsewhere. */
    private interface Pipe extends Closeable {
        void write(byte[] bytes) throws IOException;

        byte[] read(int length) throws IOException;

        static Pipe open(int index) throws IOException {
            String name = "discord-ipc-" + index;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                RandomAccessFile file;
                try {
                    file = new RandomAccessFile("\\\\.\\pipe\\" + name, "rw");
                } catch (IOException e) {
                    return null;
                }
                return new Pipe() {
                    public void write(byte[] bytes) throws IOException { file.write(bytes); }

                    public byte[] read(int length) throws IOException {
                        byte[] out = new byte[length];
                        file.readFully(out);
                        return out;
                    }

                    public void close() throws IOException { file.close(); }
                };
            }
            for (Path dir : unixDirs()) {
                Path socket = dir.resolve(name);
                if (!Files.exists(socket)) continue;
                SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                try {
                    channel.connect(UnixDomainSocketAddress.of(socket));
                } catch (IOException e) {
                    channel.close();
                    continue;
                }
                return new Pipe() {
                    public void write(byte[] bytes) throws IOException {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) channel.write(buffer);
                    }

                    public byte[] read(int length) throws IOException {
                        ByteBuffer buffer = ByteBuffer.allocate(length);
                        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new EOFException();
                        return buffer.array();
                    }

                    public void close() throws IOException { channel.close(); }
                };
            }
            return null;
        }

        /** Where Discord puts its socket, including the Flatpak and Snap versions. */
        private static List<Path> unixDirs() {
            List<Path> dirs = new ArrayList<>();
            for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String value = System.getenv(env);
                if (value != null && !value.isBlank()) dirs.add(Path.of(value));
            }
            dirs.add(Path.of("/tmp"));
            List<Path> all = new ArrayList<>();
            for (Path dir : dirs) {
                all.add(dir);
                all.add(dir.resolve("app/com.discordapp.Discord"));
                all.add(dir.resolve("snap.discord"));
            }
            return all;
        }
    }
}
