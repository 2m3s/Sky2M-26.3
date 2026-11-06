package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A boss arena: one fixed block box shipped in the jar ({@code assets/sky2m/sim/bosses/<id>.json}) or recorded on
 * Hypixel with {@code /s2 bossrecord} ({@code config/sky2m-boss-arenas/<id>.json}, which wins), and the job that
 * pastes it into the sim world.
 *
 * <p><b>Not a room.</b> Rooms are 31-block tiles on the dungeon grid; a boss arena is one irregular box (Bonzo's
 * is 60x92x89) with no tiles, doors or rotations, so it gets its own small format rather than a special case in
 * {@link RoomLibrary}. The block data uses the same encoding the room library does - a palette of full block
 * state strings and gzip+base64 big-endian shorts, -1 for air - in y, x, z order.
 *
 * <p><b>Placed far from the grid.</b> On Hypixel the boss rooms sit next to the dungeon grid, and on the bigger
 * floors that is ground the sim's 6x6 grid can occupy. The arena goes to {@link #WORLD_X}/{@link #WORLD_Z}
 * instead, well outside any view distance from the floor, so coming from the floor the client holds no copy of
 * those chunks and vanilla streams them in when the player is teleported there. Every arena but F7 shares that
 * spot, though, so a paste also clears what the last arena there left outside its own box, and re-sends its
 * chunks to anyone still tracking them (a player who was in the last arena), like the F7 paste below.
 *
 * <p><b>Except F7/M7, which is placed natively.</b> Most of the mod's F7 features only react at the boss room's
 * fixed Hypixel coordinates (the P3 terminals, gates and devices, the crystal pads, the dragons' spawn boxes,
 * the phase-by-height), so the {@code necron} arena is pasted at its own source coordinates (x 0..134,
 * z -16..147) with no {@link SimAltitude} shift ({@link #isNative}). That ground is close enough to the grid
 * (which ends near x/z +6) that the client may already hold those chunks empty, so after the paste the job also
 * sends every chunk it wrote to the players that track it ({@link PasteJob}). The few columns of the box that
 * lie on the grid's own corner ({@link #GRID_MAX}) are left alone, so the paste never cuts into a room.
 *
 * <p><b>Not through {@link SimBuildQueue}.</b> That queue remembers every chunk it touches and the next floor
 * build clears the bounding box of all of them; an arena a thousand blocks away would turn that box into a
 * million-block sweep. So this job writes every cell of its own box (air included, which also wipes the last
 * fight) and relights its own chunks.
 */
public final class SimBossArena {

    private static final Logger LOGGER = ModLog.get("sky2m-simboss");
    private static final String ROOT = "assets/sky2m/sim/bosses/";

    /** Where the arena's minimum corner goes. Far enough that no floor's view distance reaches it. */
    static final int WORLD_X = 1000;
    static final int WORLD_Z = 1000;
    /**
     * The dungeon grid's far corner: rooms are pasted from -185 up to about +6 on both axes (see SimTermBuild).
     * A natively placed arena skips the cells with x and z both at or below this, which on F7 is only a strip of
     * the solid shell round the boss room.
     */
    static final int GRID_MAX = 6;

    private static final Map<String, SimBossArena> CACHE = new HashMap<>();

    /**
     * The world box (min x, y, z, max x, y, z, inclusive) that the arenas pasted at {@link #WORLD_X}/{@link #WORLD_Z}
     * may have written, or null. They all share that spot, so a smaller arena pasted after a bigger one clears what
     * of the old box lies outside its own first ({@link PasteJob}); otherwise the old walls and floor stay standing.
     */
    private static int[] lastBox;

    final String id;
    final String name;
    final int sizeX;
    final int sizeY;
    final int sizeZ;
    final int sourceX;
    final int sourceY;
    final int sourceZ;
    final List<String> palette;
    final short[] blocks;
    /** Where the player arrives, in the source world's coordinates. */
    final int[] entry;
    final float entryYaw;
    final int[] bossSpawn;
    final int[] centre;
    final int radius;

    private SimBossArena(String id, JsonObject o) {
        this.id = id;
        this.name = o.get("name").getAsString();
        this.sizeX = o.get("sizeX").getAsInt();
        this.sizeY = o.get("sizeY").getAsInt();
        this.sizeZ = o.get("sizeZ").getAsInt();
        this.sourceX = o.get("sourceX").getAsInt();
        this.sourceY = o.get("sourceY").getAsInt();
        this.sourceZ = o.get("sourceZ").getAsInt();
        this.palette = new ArrayList<>();
        for (var e : o.getAsJsonArray("palette")) {
            palette.add(e.getAsString());
        }
        this.blocks = new short[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(blocks, (short) -1);
        decodeShorts(o.get("blocksZ").getAsString(), blocks);
        this.entry = ints(o.getAsJsonArray("entry"));
        this.entryYaw = o.has("entryYaw") ? o.get("entryYaw").getAsFloat() : 0f;
        this.bossSpawn = ints(o.getAsJsonArray("bossSpawn"));
        this.centre = ints(o.getAsJsonArray("arenaCentre"));
        this.radius = o.get("arenaRadius").getAsInt();
    }

    /** Ids that all mean the F7/M7 arena (Maxor, Storm, Goldor, Necron and the Wither King share one box). */
    private static final Set<String> F7_ALIASES = Set.of("necron", "m7", "f7", "maxor", "storm", "goldor",
            "witherking", "wither_king");

    /** The id an arena is stored under: every F7/M7 name becomes {@code necron}, the rest are lower-cased. */
    public static String canonical(String id) {
        String lower = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        return F7_ALIASES.contains(lower) ? "necron" : lower;
    }

    /** Whether this id is the F7/M7 arena (shipped in the jar, or recorded with /s2 bossrecord). */
    public static boolean isF7(String id) {
        return "necron".equals(canonical(id));
    }

    /**
     * Whether this arena is pasted at its own source coordinates with no altitude shift rather than at
     * {@link #WORLD_X}/{@link #WORLD_Z}: the F7/M7 room, whose features need Hypixel's real coordinates.
     */
    public boolean isNative() {
        return isF7(id);
    }

    /** Where the box's minimum x corner is pasted. */
    private int baseX() {
        return isNative() ? sourceX : WORLD_X;
    }

    /** Where the box's minimum z corner is pasted. */
    private int baseZ() {
        return isNative() ? sourceZ : WORLD_Z;
    }

    /** The y shift the arena is pasted with: the floor's altitude offset, none for a native arena. */
    private int yShift() {
        return isNative() ? 0 : SimAltitude.offset();
    }

    /** {@code config/sky2m-boss-arenas/}: arenas recorded with /s2 bossrecord, which win over the jar's copy. */
    public static Path recordedDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m-boss-arenas");
    }

    /** Forgets the cached arena so the next {@link #get} reads it again (after a new recording is saved). */
    public static synchronized void invalidate(String id) {
        CACHE.remove(canonical(id));
    }

    /**
     * The arena with this id, read once, or null if there is none. A recording in {@link #recordedDir()} is
     * preferred over the jar's copy, so recording an arena replaces (upgrades) the shipped one.
     */
    public static synchronized SimBossArena get(String rawId) {
        String id = canonical(rawId);
        if (CACHE.containsKey(id)) {
            return CACHE.get(id);
        }
        SimBossArena arena = null;
        Path recorded = recordedDir().resolve(id + ".json");
        if (Files.isRegularFile(recorded)) {
            try {
                arena = new SimBossArena(id, JsonParser.parseString(
                        Files.readString(recorded, StandardCharsets.UTF_8)).getAsJsonObject());
                LOGGER.info("Boss arena {} read from the recording {}", id, recorded);
            } catch (Exception e) {
                LOGGER.warn("Could not read the recorded boss arena {} - falling back to the jar's copy", recorded, e);
            }
        }
        if (arena == null) {
            try {
                Optional<Path> p = FabricLoader.getInstance().getModContainer("sky2m")
                        .flatMap(c -> c.findPath(ROOT + id + ".json"));
                if (p.isPresent()) {
                    arena = new SimBossArena(id, JsonParser.parseString(
                            Files.readString(p.get(), StandardCharsets.UTF_8)).getAsJsonObject());
                } else if (isF7(id)) {
                    LOGGER.warn("Boss arena {} (F7/M7) is not shipped - record it on Hypixel with "
                            + "/s2 bossrecord start necron", id);
                } else {
                    LOGGER.warn("Boss arena {} is not in the jar or {}", id, recordedDir());
                }
            } catch (Exception e) {
                LOGGER.warn("Could not read boss arena {}", id, e);
            }
        }
        CACHE.put(id, arena);
        return arena;
    }

    /**
     * A source-world block position as a sim-world position (the floor's altitude shift included; the same
     * position for a {@link #isNative native} arena).
     */
    public BlockPos toWorld(int x, int y, int z) {
        return new BlockPos(x - sourceX + baseX(), y + yShift(), z - sourceZ + baseZ());
    }

    public BlockPos toWorld(int[] p) {
        return toWorld(p[0], p[1], p[2]);
    }

    /** {@link #toWorld} backwards: a sim-world position as the source (Hypixel) position. */
    public BlockPos toSource(BlockPos world) {
        return new BlockPos(world.getX() - baseX() + sourceX, world.getY() - yShift(), world.getZ() - baseZ() + sourceZ);
    }

    /**
     * Whether a sim-world position is inside this arena's box. A native arena gets a few blocks of slack on the
     * west side: the F7 box was cut at x 0, and Hypixel's P3 section 3 reaches x -3 (SimNecron rebuilds it).
     */
    public boolean contains(double x, double y, double z) {
        double lx = x - baseX();
        double ly = y - yShift() - sourceY;
        double lz = z - baseZ();
        double minX = isNative() ? -8 : 0;
        return lx >= minX && lx < sizeX && ly >= 0 && ly < sizeY && lz >= 0 && lz < sizeZ;
    }

    private short at(int x, int y, int z) {
        return blocks[(y * sizeX + x) * sizeZ + z];
    }

    /**
     * Pastes the arena a slice per call, then relights the chunks it wrote, and for a {@link #isNative native}
     * arena sends them to the players again. Server thread only.
     *
     * <p>Every cell of the box is written, air included, so a second paste over the same spot also clears what
     * the last fight left (fallen adds' drops aside, which the fight discards itself).
     */
    public final class PasteJob {

        /** Cells per server tick, matching the room build's slice. */
        private static final int CELLS_PER_TICK = 150_000;
        private static final int CHUNKS_PER_TICK = 6;
        /** Chunks re-sent per server tick: each is a full chunk packet (the F7 box is 9x11 chunks, 255 tall). */
        private static final int SENDS_PER_TICK = 4;
        /** Ticks between the relight and the re-send, so the light engine has caught up with the new blocks. */
        private static final int SEND_DELAY_TICKS = 10;

        private final ServerLevel level;
        private final RoomPlacer.SectionWriter writer;
        private final BlockState[] resolved;
        private final Set<Long> chunks = new LinkedHashSet<>();
        private List<Long> lightList;
        private int cell;
        private int lit;
        private int sendDelay = SEND_DELAY_TICKS;
        private int sent;
        /** This paste's own world box, as {@link #lastBox} holds one. */
        private final int[] box;
        /** The last arena's box at this spot, cleared to air where it lies outside {@link #box}; null for none. */
        private final int[] clearBox;
        private long clearCell;

        public PasteJob(ServerLevel level) {
            this.level = level;
            this.writer = new RoomPlacer.SectionWriter(level);
            this.resolved = new BlockState[palette.size()];
            for (int i = 0; i < resolved.length; i++) {
                resolved[i] = RoomPlacer.resolve(palette.get(i));
            }
            int y0 = sourceY + yShift();
            this.box = new int[]{baseX(), y0, baseZ(), baseX() + sizeX - 1, y0 + sizeY - 1, baseZ() + sizeZ - 1};
            if (isNative()) {
                this.clearBox = null;
            } else {
                this.clearBox = lastBox;
                // Both until this paste finishes: one left half done still has the old arena's blocks in it.
                lastBox = clearBox == null ? box : new int[]{
                        Math.min(box[0], clearBox[0]), Math.min(box[1], clearBox[1]), Math.min(box[2], clearBox[2]),
                        Math.max(box[3], clearBox[3]), Math.max(box[4], clearBox[4]), Math.max(box[5], clearBox[5])};
            }
        }

        /** Clears a slice of {@link #clearBox} outside this paste's own box. @return whether that is done */
        private boolean clearOld() {
            if (clearBox == null) {
                return true;
            }
            long sx = clearBox[3] - clearBox[0] + 1L;
            long sy = clearBox[4] - clearBox[1] + 1L;
            long sz = clearBox[5] - clearBox[2] + 1L;
            long total = sx * sy * sz;
            if (clearCell >= total) {
                return true;
            }
            long end = Math.min(total, clearCell + CELLS_PER_TICK);
            BlockState air = Blocks.AIR.defaultBlockState();
            // World y in the box; the section writer adds the altitude shift itself, so it is taken off here.
            int shift = SimAltitude.offset();
            for (; clearCell < end; clearCell++) {
                int x = clearBox[0] + (int) ((clearCell / sz) % sx);
                int y = clearBox[1] + (int) (clearCell / (sz * sx));
                int z = clearBox[2] + (int) (clearCell % sz);
                if (x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5]) {
                    continue;   // the new arena writes this cell itself
                }
                writer.set(x, y - shift, z, air);
                chunks.add(ChunkPos.pack(x >> 4, z >> 4));
            }
            return clearCell >= total;
        }

        public boolean isDone() {
            return lightList != null && lit >= lightList.size() && sent >= lightList.size();
        }

        /** 0..1, for a progress line. */
        public float progress() {
            int total = blocks.length;
            if (lightList == null) {
                return 0.9f * cell / total;
            }
            int n = Math.max(1, lightList.size());
            return 0.9f + 0.05f * lit / n + 0.05f * sent / n;
        }

        public void step() {
            if (lightList == null) {
                if (!clearOld()) {
                    return;
                }
                int end = Math.min(blocks.length, cell + CELLS_PER_TICK);
                BlockState air = Blocks.AIR.defaultBlockState();
                boolean natively = isNative();
                // The section writer adds the floor's altitude shift to every y it is given; a native arena
                // has none, so it is taken back off here rather than giving the writer a second mode.
                int yIn = sourceY - (natively ? SimAltitude.offset() : 0);
                int bx = baseX();
                int bz = baseZ();
                for (; cell < end; cell++) {
                    int z = cell % sizeZ;
                    int x = (cell / sizeZ) % sizeX;
                    int y = cell / (sizeZ * sizeX);
                    int wx = bx + x;
                    int wz = bz + z;
                    if (natively && wx <= GRID_MAX && wz <= GRID_MAX) {
                        continue;   // the dungeon grid's corner room: not ours to overwrite
                    }
                    short idx = at(x, y, z);
                    BlockState state = idx < 0 ? air : resolved[idx];
                    if (state == null) {
                        state = air;
                    }
                    writer.set(wx, yIn + y, wz, state);
                    chunks.add(ChunkPos.pack(wx >> 4, wz >> 4));
                }
                if (cell >= blocks.length) {
                    lightList = new ArrayList<>(chunks);
                    if (!natively) {
                        lastBox = box;
                    }
                    if (writer.outsideWorld() > 0) {
                        LOGGER.warn("Boss arena {} lost {} block(s) outside the world", id, writer.outsideWorld());
                    }
                }
                return;
            }
            if (lit >= lightList.size()) {
                resend();
                return;
            }
            // The same finishing pass SimBuildQueue gives a floor: blocks written behind setBlock's back need
            // their section counts recalculated and the light engine told the sections stopped being empty,
            // or the chunk is sent half-empty and the lighting worker walks into a null layer.
            var lights = level.getChunkSource().getLightEngine();
            for (int n = 0; n < CHUNKS_PER_TICK && lit < lightList.size(); n++) {
                long k = lightList.get(lit++);
                int cx = ChunkPos.getX(k);
                int cz = ChunkPos.getZ(k);
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(cx, cz);
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    sections[i].recalcBlockCounts();
                    lights.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)),
                            sections[i].hasOnlyAir());
                }
                lights.setLightEnabled(pos, true);
                lights.propagateLightSources(pos);
                chunk.markUnsaved();
            }
        }

        /**
         * Sends each written chunk again, a few per tick, to the players already tracking it. The paste went into
         * the sections behind setBlock's back, so nothing told the client: the F7 room's chunks can be in its view
         * of the grid, and a boss arena pasted over another one (every other arena shares WORLD_X/WORLD_Z) can be
         * in view of a player still there - the client would keep showing what it had before. A chunk nobody
         * tracks yet needs nothing: vanilla sends it fresh when the player comes near.
         */
        private void resend() {
            if (sendDelay > 0) {
                sendDelay--;
                return;
            }
            var source = level.getChunkSource();
            for (int n = 0; n < SENDS_PER_TICK && sent < lightList.size(); n++) {
                long k = lightList.get(sent++);
                ChunkPos pos = new ChunkPos(ChunkPos.getX(k), ChunkPos.getZ(k));
                LevelChunk chunk = source.getChunkNow(ChunkPos.getX(k), ChunkPos.getZ(k));
                if (chunk == null) {
                    continue;
                }
                List<ServerPlayer> watching = source.chunkMap.getPlayers(pos, false);
                if (watching.isEmpty()) {
                    continue;
                }
                var packet = new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null);
                for (ServerPlayer p : watching) {
                    p.connection.send(packet);
                }
            }
        }
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    /** The inverse of {@link #decodeShorts}: big-endian shorts, gzipped, base64 - the {@code blocksZ} field. */
    public static String encodeShorts(short[] cells) {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                new java.util.zip.GZIPOutputStream(bytes), 1 << 16))) {
            for (short c : cells) {
                out.writeShort(c);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void decodeShorts(String encoded, short[] into) {
        // Buffered: an F7-sized arena is millions of shorts, and readShort straight off the gzip stream is two
        // one-byte inflater reads each.
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(
                new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(encoded))), 1 << 16))) {
            for (int i = 0; i < into.length; i++) {
                into[i] = in.readShort();
            }
        } catch (java.io.EOFException expected) {
            // Shorter than the box: the rest stays air.
        } catch (Exception e) {
            LOGGER.warn("Could not read a boss arena's blocks", e);
        }
    }
}
