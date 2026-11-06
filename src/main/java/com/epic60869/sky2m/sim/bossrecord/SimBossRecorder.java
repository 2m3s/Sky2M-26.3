package com.epic60869.sky2m.sim.bossrecord;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.sim.roomsim.SimBossArena;
import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Boss Arena Recorder ({@code /s2 bossrecord}): captures a Hypixel boss arena into {@link SimBossArena}'s JSON
 * format, so the sim can paste arenas nothing ships for (F7/M7) or upgrade the fan-made ones.
 *
 * <p><b>Purely passive.</b> It only reads the client's own world ({@code client.level}) - the chunks the server
 * already sent - every tick, a couple of chunks at a time. No packets, clicks or movement, so it is safe to leave
 * running through a real fight.
 *
 * <p><b>Seen columns, like the old Room Recorder.</b> A chunk is read only once the client has it fully loaded
 * ({@link ChunkStatus#FULL}, the same check as {@code SimChunks.isLoaded}); every x/z column of the box in that chunk
 * is then marked seen. Columns never seen stay air in the file and are reported as missing, rather than an unloaded
 * chunk being recorded as air. The first look wins: a column is not re-read, so the arena is kept as it stood when
 * it first loaded, before the fight broke anything.
 *
 * <p>Memory: one short per cell of the box (F7's 151x256x151 box is ~5.8M cells, ~11.7 MB) plus one bit per column.
 */
public final class SimBossRecorder {

    private static final Logger LOGGER = ModLog.get("sky2m-bossrecord");
    private static final String FEATURE = "Boss Recorder";

    /** Chunks read per client tick (a chunk is at most 16x16 columns of the box's height). */
    private static final int CHUNKS_PER_TICK = 2;
    /** Default box around the player for arenas other than F7/M7. */
    private static final int AROUND = 100;
    /** Largest box accepted, in cells (~32 MB of shorts). */
    private static final long MAX_CELLS = 16_000_000L;

    private static Recording current;
    private static int ticks;

    private SimBossRecorder() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SimBossRecorder::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(command()));
            }
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        var id = ClientCommands.argument("id", StringArgumentType.word())
                .executes(c -> start(StringArgumentType.getString(c, "id"), null))
                .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("y1", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("y2", IntegerArgumentType.integer())
                                                        .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(c -> start(
                                                                        StringArgumentType.getString(c, "id"),
                                                                        box(c)))))))));
        return ClientCommands.literal("bossrecord")
                .executes(c -> status())
                .then(ClientCommands.literal("start").then(id))
                .then(ClientCommands.literal("status").executes(c -> status()))
                .then(ClientCommands.literal("stop").executes(c -> stop()))
                .then(ClientCommands.literal("cancel").executes(c -> cancel()))
                .then(ClientCommands.literal("mark")
                        .then(ClientCommands.literal("entry").executes(c -> mark("entry", -1)))
                        .then(ClientCommands.literal("spawn").executes(c -> mark("spawn", -1)))
                        .then(ClientCommands.literal("centre").executes(c -> mark("centre", -1))
                                .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, 512))
                                        .executes(c -> mark("centre", IntegerArgumentType.getInteger(c, "radius")))))
                        .then(ClientCommands.literal("center").executes(c -> mark("centre", -1))
                                .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, 512))
                                        .executes(c -> mark("centre",
                                                IntegerArgumentType.getInteger(c, "radius"))))));
    }

    private static int[] box(CommandContext<FabricClientCommandSource> c) {
        return new int[]{
                IntegerArgumentType.getInteger(c, "x1"), IntegerArgumentType.getInteger(c, "y1"),
                IntegerArgumentType.getInteger(c, "z1"), IntegerArgumentType.getInteger(c, "x2"),
                IntegerArgumentType.getInteger(c, "y2"), IntegerArgumentType.getInteger(c, "z2")};
    }

    // ---------------------------------------------------------------- commands

    private static int start(String rawId, int[] box) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            send(ModChat.bad("Join a world first."));
            return 0;
        }
        if (current != null) {
            send(ModChat.bad("Already recording " + current.id + ". "), ModChat.value("/s2 bossrecord stop"),
                    ModChat.text(" or "), ModChat.value("/s2 bossrecord cancel"), ModChat.text(" first."));
            return 0;
        }
        String id = SimBossArena.canonical(rawId);
        if (!id.matches("[a-z0-9_\\-]{1,40}")) {
            send(ModChat.bad("Use a simple id like necron, bonzo or sadan."));
            return 0;
        }
        BlockPos p = mc.player.blockPosition();
        int worldMinY = level.getMinY();
        int worldMaxY = level.getMaxY();
        int x1;
        int y1;
        int z1;
        int x2;
        int y2;
        int z2;
        if (box != null) {
            x1 = Math.min(box[0], box[3]);
            x2 = Math.max(box[0], box[3]);
            y1 = Math.min(box[1], box[4]);
            y2 = Math.max(box[1], box[4]);
            z1 = Math.min(box[2], box[5]);
            z2 = Math.max(box[2], box[5]);
        } else if (SimBossArena.isF7(id)) {
            // Hypixel's F7/M7 boss: Maxor's floor to Necron's and the Wither King's pit below, all in one box.
            x1 = 0;
            x2 = 150;
            y1 = 0;
            y2 = 255;
            z1 = 0;
            z2 = 150;
        } else {
            x1 = p.getX() - AROUND;
            x2 = p.getX() + AROUND;
            y1 = p.getY() - AROUND;
            y2 = p.getY() + AROUND;
            z1 = p.getZ() - AROUND;
            z2 = p.getZ() + AROUND;
        }
        y1 = Math.max(y1, worldMinY);
        y2 = Math.min(y2, worldMaxY);
        if (y2 < y1) {
            send(ModChat.bad("That box is outside the world's height."));
            return 0;
        }
        long cells = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (cells > MAX_CELLS) {
            send(ModChat.bad(String.format(Locale.ROOT, "That box is %,d blocks; the limit is %,d.", cells,
                    MAX_CELLS)));
            return 0;
        }
        current = new Recording(id, level, x1, y1, z1, x2, y2, z2, p, mc.player.getYRot());
        ticks = 0;
        LOGGER.info("Boss recording {} started: box {},{},{} .. {},{},{}", id, x1, y1, z1, x2, y2, z2);
        send(ModChat.text("Recording "), ModChat.value(id), ModChat.text(String.format(Locale.ROOT,
                        " - box %d,%d,%d to %d,%d,%d (%,d columns). Walk around the whole arena so every part loads; ",
                        x1, y1, z1, x2, y2, z2, current.columnsTotal())),
                ModChat.value("/s2 bossrecord mark entry|spawn|centre [radius]"), ModChat.text(" sets the spots, "),
                ModChat.value("/s2 bossrecord stop"), ModChat.text(" saves. Only reads loaded chunks: nothing is sent."));
        if (!rawId.equalsIgnoreCase(id)) {
            send(ModChat.dim("(" + rawId + " is saved as " + id + ", the F7/M7 arena.)"));
        }
        return 1;
    }

    private static int status() {
        Recording r = current;
        if (r == null) {
            send(ModChat.text("Not recording. "), ModChat.value("/s2 bossrecord start <id>"),
                    ModChat.dim(" (necron/m7 records the F7/M7 boss at 0,0,0 to 150,255,150)"));
            return 1;
        }
        send(ModChat.text("Recording "), ModChat.value(r.id), ModChat.text(": " + r.progressText()
                + (r.paused ? " (paused: different world)" : "")
                + ". Entry " + fmt(r.entry) + ", spawn " + fmt(r.spawn) + ", centre " + fmt(r.centre)
                + (r.radius > 0 ? " r" + r.radius : "") + "."));
        return 1;
    }

    private static int mark(String what, int radius) {
        Minecraft mc = Minecraft.getInstance();
        Recording r = current;
        if (r == null || mc.player == null) {
            send(ModChat.bad("Not recording. "), ModChat.value("/s2 bossrecord start <id>"));
            return 0;
        }
        BlockPos p = mc.player.blockPosition();
        int[] pos = {p.getX(), p.getY(), p.getZ()};
        switch (what) {
            case "entry" -> {
                r.entry = pos;
                r.entryYaw = mc.player.getYRot();
            }
            case "spawn" -> r.spawn = pos;
            default -> {
                r.centre = pos;
                if (radius > 0) {
                    r.radius = radius;
                }
            }
        }
        send(ModChat.text("Marked " + what + " at "), ModChat.value(fmt(pos)),
                ModChat.text(radius > 0 ? ", radius " + radius : ""), ModChat.text("."));
        return 1;
    }

    private static int cancel() {
        if (current == null) {
            send(ModChat.text("Not recording."));
            return 0;
        }
        String id = current.id;
        current = null;
        send(ModChat.text("Dropped the " + id + " recording without saving."));
        return 1;
    }

    private static int stop() {
        Recording r = current;
        if (r == null) {
            send(ModChat.text("Not recording."));
            return 0;
        }
        current = null;
        try {
            Result res = r.save();
            if (res == null) {
                send(ModChat.bad("Nothing was captured (no loaded chunk inside the box) - not saved."));
                return 0;
            }
            SimBossArena.invalidate(r.id);
            send(ModChat.good("Saved "), ModChat.value(r.id), ModChat.text(String.format(Locale.ROOT,
                    ": %dx%dx%d blocks, %d block types, %,d/%,d columns seen (%.1f%%). ",
                    res.sizeX, res.sizeY, res.sizeZ, res.paletteSize, res.seen, res.total, 100.0 * res.seen
                            / Math.max(1, res.total))), ModChat.dim(res.path.toAbsolutePath().toString()));
            if (res.seen < res.total) {
                send(ModChat.dim("Unseen columns were saved as air. Record again and walk the missing parts to "
                        + "fill them in."));
            }
            return 1;
        } catch (Exception e) {
            LOGGER.warn("Could not save boss recording {}", r.id, e);
            send(ModChat.bad("Could not save: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------- ticking

    private static void tick(Minecraft mc) {
        Recording r = current;
        if (r == null) {
            return;
        }
        if (mc.level != r.level) {
            // Another world (lobby, next run, disconnect): reading it would mix two places into one arena.
            if (!r.paused) {
                r.paused = true;
                send(ModChat.text("Left the recorded world - recording " + r.id + " paused. "),
                        ModChat.value("/s2 bossrecord stop"), ModChat.text(" still saves what was captured."));
            }
            return;
        }
        r.paused = false;
        try {
            r.scan(CHUNKS_PER_TICK);
        } catch (RuntimeException e) {
            LOGGER.warn("Boss recorder scan failed", e);
        }
        if (++ticks % 20 == 0 && mc.gui != null && mc.gui.hud != null) {
            mc.gui.hud.setOverlayMessage(Component.literal("Boss Recorder " + r.id + ": " + r.progressText()),
                    false);
        }
    }

    private static String fmt(int[] p) {
        return p == null ? "unset" : p[0] + "," + p[1] + "," + p[2];
    }

    private static void send(Component... parts) {
        ModChat.send(FEATURE, parts);
    }

    private record Result(Path path, int sizeX, int sizeY, int sizeZ, int paletteSize, int seen, int total) {
    }

    /** One recording in progress: the box, its cells and which columns have been read. */
    private static final class Recording {
        final String id;
        final ClientLevel level;
        final int minX;
        final int minY;
        final int minZ;
        final int sx;
        final int sy;
        final int sz;
        /** (y * sx + x) * sz + z, -1 = air or unseen. */
        final short[] cells;
        final java.util.BitSet seen;
        final List<String> palette = new ArrayList<>();
        final Map<BlockState, Short> paletteIndex = new HashMap<>();
        final java.util.Set<Long> doneChunks = new java.util.HashSet<>();
        final int chunkX0;
        final int chunkX1;
        final int chunkZ0;
        final int chunkZ1;
        int seenCount;
        int chunkCursor;
        boolean paused;
        boolean paletteFull;
        int[] entry;
        float entryYaw;
        int[] spawn;
        int[] centre;
        int radius;

        Recording(String id, ClientLevel level, int x1, int y1, int z1, int x2, int y2, int z2, BlockPos start,
                  float yaw) {
            this.id = id;
            this.level = level;
            this.minX = x1;
            this.minY = y1;
            this.minZ = z1;
            this.sx = x2 - x1 + 1;
            this.sy = y2 - y1 + 1;
            this.sz = z2 - z1 + 1;
            this.cells = new short[sx * sy * sz];
            java.util.Arrays.fill(cells, (short) -1);
            this.seen = new java.util.BitSet(sx * sz);
            this.chunkX0 = x1 >> 4;
            this.chunkX1 = x2 >> 4;
            this.chunkZ0 = z1 >> 4;
            this.chunkZ1 = z2 >> 4;
            // Where the recording started is the best guess for where a player arrives.
            this.entry = new int[]{start.getX(), start.getY(), start.getZ()};
            this.entryYaw = yaw;
        }

        int columnsTotal() {
            return sx * sz;
        }

        String progressText() {
            return String.format(Locale.ROOT, "%,d/%,d columns seen (%.1f%%)", seenCount, columnsTotal(),
                    100.0 * seenCount / Math.max(1, columnsTotal()));
        }

        /** Reads up to {@code budget} newly loaded chunks of the box, walking the chunk grid round-robin. */
        void scan(int budget) {
            int cw = chunkX1 - chunkX0 + 1;
            int total = cw * (chunkZ1 - chunkZ0 + 1);
            if (doneChunks.size() >= total) {
                return;
            }
            int read = 0;
            for (int n = 0; n < total && read < budget; n++) {
                int i = chunkCursor;
                chunkCursor = (chunkCursor + 1) % total;
                int cx = chunkX0 + i % cw;
                int cz = chunkZ0 + i / cw;
                long key = net.minecraft.world.level.ChunkPos.pack(cx, cz);
                if (doneChunks.contains(key)) {
                    continue;
                }
                ChunkAccess access = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(access instanceof LevelChunk chunk) || chunk.isEmpty()) {
                    continue;
                }
                readChunk(chunk, cx, cz);
                doneChunks.add(key);
                read++;
            }
        }

        private void readChunk(LevelChunk chunk, int cx, int cz) {
            int bx0 = Math.max(minX, cx << 4);
            int bx1 = Math.min(minX + sx - 1, (cx << 4) + 15);
            int bz0 = Math.max(minZ, cz << 4);
            int bz1 = Math.min(minZ + sz - 1, (cz << 4) + 15);
            LevelChunkSection[] sections = chunk.getSections();
            for (int y = minY; y < minY + sy; y++) {
                int si = chunk.getSectionIndex(y);
                if (si < 0 || si >= sections.length) {
                    continue;
                }
                LevelChunkSection section = sections[si];
                if (section == null || section.hasOnlyAir()) {
                    // Skip to the next section: the whole slice is air, which the cells already say.
                    int next = ((y >> 4) + 1) << 4;
                    y = next - 1;
                    continue;
                }
                int ly = y - minY;
                for (int x = bx0; x <= bx1; x++) {
                    int base = (ly * sx + (x - minX)) * sz;
                    for (int z = bz0; z <= bz1; z++) {
                        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                        if (state.isAir()) {
                            continue;
                        }
                        cells[base + (z - minZ)] = indexOf(state);
                    }
                }
            }
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    int col = (x - minX) * sz + (z - minZ);
                    if (!seen.get(col)) {
                        seen.set(col);
                        seenCount++;
                    }
                }
            }
        }

        private short indexOf(BlockState state) {
            Short idx = paletteIndex.get(state);
            if (idx != null) {
                return idx;
            }
            if (palette.size() >= Short.MAX_VALUE) {
                if (!paletteFull) {
                    paletteFull = true;
                    LOGGER.warn("Boss recording {} ran out of palette slots; further new states are air", id);
                }
                return -1;
            }
            short next = (short) palette.size();
            // "minecraft:id[prop=value,...]", the form RoomPlacer.resolve parses back.
            palette.add(BlockStateParser.serialize(state));
            paletteIndex.put(state, next);
            return next;
        }

        /** Trims to the blocks found and writes {@code config/sky2m-boss-arenas/<id>.json}; null if empty. */
        Result save() throws java.io.IOException {
            int x0 = Integer.MAX_VALUE;
            int y0 = Integer.MAX_VALUE;
            int z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int y1 = Integer.MIN_VALUE;
            int z1 = Integer.MIN_VALUE;
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int base = (y * sx + x) * sz;
                    for (int z = 0; z < sz; z++) {
                        if (cells[base + z] >= 0) {
                            if (x < x0) x0 = x;
                            if (x > x1) x1 = x;
                            if (y < y0) y0 = y;
                            if (y > y1) y1 = y;
                            if (z < z0) z0 = z;
                            if (z > z1) z1 = z;
                        }
                    }
                }
            }
            if (x1 < x0) {
                return null;
            }
            int tx = x1 - x0 + 1;
            int ty = y1 - y0 + 1;
            int tz = z1 - z0 + 1;
            short[] out = new short[tx * ty * tz];
            for (int y = 0; y < ty; y++) {
                for (int x = 0; x < tx; x++) {
                    System.arraycopy(cells, ((y + y0) * sx + (x + x0)) * sz + z0, out, (y * tx + x) * tz, tz);
                }
            }
            int seenIn = 0;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (seen.get(x * sz + z)) {
                        seenIn++;
                    }
                }
            }
            int srcX = minX + x0;
            int srcY = minY + y0;
            int srcZ = minZ + z0;
            int[] c = centre != null ? centre
                    : new int[]{srcX + tx / 2, entry[1], srcZ + tz / 2};
            int rad = radius > 0 ? radius : Math.max(tx, tz) / 2;

            JsonObject o = new JsonObject();
            o.addProperty("name", displayName(id));
            if (SimBossArena.isF7(id)) {
                o.addProperty("floor", "F7");
            }
            o.addProperty("source", "Recorded on Hypixel with /s2 bossrecord, " + Instant.now());
            o.add("entry", arr(entry));
            o.addProperty("entryYaw", Math.round(entryYaw));
            o.add("bossSpawn", arr(spawn != null ? spawn : c));
            o.add("arenaCentre", arr(c));
            o.addProperty("arenaRadius", rad);
            o.addProperty("format", 1);
            o.addProperty("sizeX", tx);
            o.addProperty("sizeY", ty);
            o.addProperty("sizeZ", tz);
            o.addProperty("sourceX", srcX);
            o.addProperty("sourceY", srcY);
            o.addProperty("sourceZ", srcZ);
            o.addProperty("order", "y,x,z");
            JsonArray pal = new JsonArray();
            palette.forEach(pal::add);
            o.add("palette", pal);
            o.addProperty("columnsSeen", seenIn);
            o.addProperty("columnsTotal", tx * tz);
            o.addProperty("blocksZ", SimBossArena.encodeShorts(out));

            Path dir = SimBossArena.recordedDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(id + ".json");
            Path tmp = dir.resolve(id + ".json.tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Boss recording {} saved to {} ({}x{}x{}, {}/{} columns)", id, file, tx, ty, tz, seenIn,
                    tx * tz);
            return new Result(file, tx, ty, tz, palette.size(), seenIn, tx * tz);
        }

        private static JsonArray arr(int[] p) {
            JsonArray a = new JsonArray();
            for (int v : p) {
                a.add(v);
            }
            return a;
        }

        private static String displayName(String id) {
            if (SimBossArena.isF7(id)) {
                return "Necron";
            }
            String s = id.replace('_', ' ');
            return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }
}
