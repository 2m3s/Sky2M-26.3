package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.sim.livemap.DungeonLayout;
import com.epic60869.sky2m.sim.roomsim.SimState;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Route Replay: while a dungeon run (or a Dungeon Sim run) is going, the player's position is sampled a few times a
 * second together with the run clock, secrets found and deaths, plus a compact copy of the floor's room layout. At the
 * end it's saved next to the Run History (config/sky2m/replays/, gzip, delta-encoded ints, capped) and
 * {@link RouteReplayScreen} plays it back on the map. /s2 replay [last] opens the newest one.
 */
public final class RouteReplay {
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("replays");
    private static final Gson GSON = new Gson();
    private static final int SAMPLE_TICKS = 4, LAYOUT_TICKS = 100, MAX_POINTS = 12000, MAX_FILES = 100, GRID = 11;
    private static final double MIN_MOVE_SQ = 0.09;

    /** A saved replay. Points are stored delta-encoded (t in ms, x/z in half blocks); {@link #decode()} makes them absolute. */
    public static final class Replay {
        public int v = 1;
        public String floor;
        public long startedAt;
        public long durationMs;
        public boolean sim;
        public int[] dt = new int[0], dx = new int[0], dz = new int[0];
        public List<Event> events = new ArrayList<>();
        public Layout layout;
        /** Absolute time (ms) and position (blocks) per point, filled by {@link #decode()}. */
        public transient int[] t;
        public transient float[] x, z;

        public void decode() {
            int n = dt.length;
            t = new int[n];
            x = new float[n];
            z = new float[n];
            int at = 0, ax = 0, az = 0;
            for (int i = 0; i < n; i++) {
                at += dt[i];
                ax += dx[i];
                az += dz[i];
                t[i] = at;
                x[i] = ax / 2f;
                z[i] = az / 2f;
            }
        }
    }

    /** A secret or a death: when, where and in which room. */
    public static final class Event {
        public int t;
        public String kind, room;
        public float x, z;
    }

    /** The floor: room id per grid cell (-1 none), door type per cell, and per room its name, type and label spot. */
    public static final class Layout {
        public int[] roomOf = new int[GRID * GRID];
        public int[] doors = new int[GRID * GRID];
        public String[] names = new String[0], types = new String[0];
        public float[] labelGX = new float[0], labelGZ = new float[0];
    }

    // recording state
    private static boolean recording;
    private static Replay rec;
    private static long startMs;
    private static int tick, lastSecrets, lastDeaths, count;
    private static double lastX = Double.NaN, lastZ;
    private static int ax, az, lastT;
    private static final List<int[]> points = new ArrayList<>();
    private static Layout lastLayout;
    private static DungeonLayout lastDungeon;

    private RouteReplay() {}

    private static FeatureConfigs.RunHistory config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.timers.runHistory;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (RuntimeException e) {
                recording = false;
                rec = null;
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("replay")
                    .executes(c -> openLast())
                    .then(ClientCommands.literal("last").executes(c -> openLast()))));
            }
        });
    }

    private static int openLast() {
        Replay r = latest();
        if (r == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[S2M] No route replay saved yet. Turn on Run History > Record Route and finish a run."));
            return 1;
        }
        return Compat.queueOpenScreen(new RouteReplayScreen(r, null));
    }

    // ------------------------------------------------------------------------------------------------ recording

    /** Called by Run History when a run starts. */
    public static void begin(String floor, long startedAt) {
        FeatureConfigs.RunHistory c = config();
        if (c == null || !c.recordRoute) return;
        start(floor, startedAt, false);
    }

    /** Called by Run History when the run is saved. */
    public static void end() {
        if (recording && rec != null && !rec.sim) finishRecording();
    }

    private static void start(String floor, long startedAt, boolean sim) {
        rec = new Replay();
        rec.floor = floor;
        rec.startedAt = startedAt;
        rec.sim = sim;
        startMs = System.currentTimeMillis();
        tick = 0;
        count = 0;
        lastX = Double.NaN;
        ax = az = lastT = 0;
        lastSecrets = ScoreCalculator.foundSecrets();
        lastDeaths = ScoreCalculator.deaths();
        points.clear();
        lastLayout = null;
        lastDungeon = null;
        recording = true;
    }

    private static void tick(Minecraft mc) {
        boolean sim = SimState.isActive();
        if (!recording && sim && mc.player != null) {
            FeatureConfigs.RunHistory c = config();
            if (c != null && c.recordRoute) start(SimState.floorLabel(), System.currentTimeMillis(), true);
        }
        if (!recording || rec == null) return;
        if (rec.sim && !sim) {
            finishRecording();
            return;
        }
        if (mc.player == null) return;
        tick++;
        long now = System.currentTimeMillis() - startMs;
        if (tick % LAYOUT_TICKS == 20 || tick == 2) captureLayout();
        if (tick % SAMPLE_TICKS != 0) return;
        double x = mc.player.getX(), z = mc.player.getZ();
        if (!Double.isNaN(lastX) && (x - lastX) * (x - lastX) + (z - lastZ) * (z - lastZ) < MIN_MOVE_SQ) return;
        if (count < MAX_POINTS) {
            int hx = (int) Math.round(x * 2), hz = (int) Math.round(z * 2);
            points.add(new int[]{(int) now - lastT, hx - ax, hz - az});
            lastT = (int) now;
            ax = hx;
            az = hz;
            count++;
        }
        lastX = x;
        lastZ = z;
        int secrets = ScoreCalculator.foundSecrets(), deaths = ScoreCalculator.deaths();
        if (secrets > lastSecrets) event("secret", (int) now, x, z);
        if (deaths > lastDeaths) event("death", (int) now, x, z);
        lastSecrets = secrets;
        lastDeaths = deaths;
    }

    private static void event(String kind, int t, double x, double z) {
        Event e = new Event();
        e.kind = kind;
        e.t = t;
        e.x = (float) x;
        e.z = (float) z;
        if (lastDungeon != null) e.room = lastDungeon.name(lastDungeon.roomAtWorld(x, z));
        if (rec.events.size() < 2000) rec.events.add(e);
    }

    private static void captureLayout() {
        try {
            DungeonLayout l = DungeonLayout.capture();
            int n = l.roomCount();
            if (n <= 0 || lastLayout != null && n < lastLayout.names.length) return;
            Layout out = new Layout();
            for (int i = 0; i < GRID * GRID; i++) {
                out.roomOf[i] = l.roomOfCell(i);
                out.doors[i] = l.doorType(i);
            }
            out.names = new String[n];
            out.types = new String[n];
            out.labelGX = new float[n];
            out.labelGZ = new float[n];
            for (int r = 0; r < n; r++) {
                out.names[r] = l.name(r);
                var entry = l.entry(r);
                out.types[r] = entry == null || entry.type == null ? "UNKNOWN" : entry.type.toUpperCase(Locale.ROOT);
                out.labelGX[r] = l.labelGX(r);
                out.labelGZ[r] = l.labelGZ(r);
            }
            lastLayout = out;
            lastDungeon = l;
        } catch (RuntimeException ignored) {
            // the map isn't ready; try again later
        }
    }

    private static void finishRecording() {
        Replay r = rec;
        recording = false;
        rec = null;
        if (r == null || points.size() < 2) return;
        captureLayout();
        r.durationMs = System.currentTimeMillis() - startMs;
        int n = points.size();
        r.dt = new int[n];
        r.dx = new int[n];
        r.dz = new int[n];
        for (int i = 0; i < n; i++) {
            int[] p = points.get(i);
            r.dt[i] = p[0];
            r.dx[i] = p[1];
            r.dz[i] = p[2];
        }
        r.layout = lastLayout;
        points.clear();
        // Compressing and writing the file (and pruning the folder) happens off the client thread.
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(DIR);
                try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file(r))), StandardCharsets.UTF_8)) {
                    GSON.toJson(r, w);
                }
                prune();
            } catch (Exception e) {
                System.err.println("[Sky2M] Route Replay couldn't save: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static Path file(Replay r) {
        return DIR.resolve((r.sim ? "sim-" : "run-") + r.startedAt + ".json.gz");
    }

    /** Whether a replay was recorded for the Run History run that started at this time. */
    public static boolean has(long startedAt) {
        return Files.exists(DIR.resolve("run-" + startedAt + ".json.gz"));
    }

    public static Replay load(long startedAt) {
        return read(DIR.resolve("run-" + startedAt + ".json.gz"));
    }

    public static void delete(long startedAt) {
        try {
            Files.deleteIfExists(DIR.resolve("run-" + startedAt + ".json.gz"));
        } catch (Exception ignored) {
        }
    }

    /** The newest saved replay, a real run or a Dungeon Sim one. */
    public static Replay latest() {
        if (!Files.isDirectory(DIR)) return null;
        try (Stream<Path> s = Files.list(DIR)) {
            Path newest = s.filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                .max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
            return newest == null ? null : read(newest);
        } catch (Exception e) {
            return null;
        }
    }

    private static Replay read(Path p) {
        if (!Files.exists(p)) return null;
        try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)), StandardCharsets.UTF_8)) {
            Replay replay = GSON.fromJson(r, Replay.class);
            if (replay == null || replay.dt == null || replay.dt.length < 2) return null;
            replay.decode();
            return replay;
        } catch (Exception e) {
            System.err.println("[Sky2M] Route Replay couldn't read " + p.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /** Keeps only the newest replay files, so the folder can't grow without bound. */
    private static void prune() {
        try (Stream<Path> s = Files.list(DIR)) {
            List<Path> files = s.filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            for (int i = MAX_FILES; i < files.size(); i++) Files.deleteIfExists(files.get(i));
        } catch (Exception ignored) {
        }
    }
}
