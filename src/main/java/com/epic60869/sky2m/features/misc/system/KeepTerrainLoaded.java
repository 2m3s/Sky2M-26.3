package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.world.Sky2MClientPacketListenerAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Misc > Keep Terrain Loaded, ported from Skysoft's KeepTerrainLoaded (https://github.com/Akinsoft/Skysoft,
 * features/misc/KeepTerrainLoaded.kt and the KeepTerrainLoaded mixins, LGPL-3.0): on SkyBlock islands, chunks the
 * server unloads stay loaded, and every chunk the server sends is saved to disk per island, so terrain you've visited
 * shows up to your render distance even past Hypixel's view distance, also next time you visit. Off when Bobby is
 * installed (it does the same).
 */
public final class KeepTerrainLoaded {
    /** Skysoft's TerrainCacheIsland list, as the tab list names them. */
    private static final Set<String> ISLANDS = Set.of("The End", "Dwarven Mines", "Glacite Tunnels", "Dungeon Hub", "Hub",
        "The Farming Islands", "Crystal Hollows", "The Park", "Deep Caverns", "Gold Mine", "Garden", "Spider's Den",
        "Jerry's Workshop", "The Rift", "Crimson Isle", "Backwater Bayou", "Galatea", "Torrhus Canyon", "Safari",
        "Lotus Atoll");
    private static final Pattern SERVER_LINE = Pattern.compile("\\d{2}/\\d{2}/\\d{2}\\s+(\\S+)");
    private static final boolean BOBBY = FabricLoader.getInstance().isModLoaded("bobby");
    private static final int MAX_CONCURRENT_LOADS = 16;
    private static final int MAX_SCAN_CHECKS_PER_TICK = 64;
    private static final int MAX_SAVES_PER_TICK = 16;
    /**
     * Turning a chunk packet back into bytes for the cache takes a while (a whole chunk's blocks and light); done on the
     * render thread for every chunk the server sends, it stuttered the game when joining an island. The packet isn't
     * changed after it arrives, so it's encoded here instead.
     */
    private static final java.util.concurrent.ExecutorService ENCODER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Sky2M terrain cache");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final int MAX_PENDING_SAVES = 512;
    private static final int MAX_DISTANCE = 32;
    private static final int MAX_PACKET_BYTES = 4 * 1024 * 1024;

    private record Session(String island, String server) {}

    private record Active(ClientLevel level, Session session, Storage storage) {}

    private record Loading(Storage storage, long position) {}

    private record PendingSave(Storage storage, RegistryAccess registries, int sections, ClientboundLevelChunkWithLightPacket packet) {}

    private static final Set<ChunkPos> retained = new LinkedHashSet<>();
    private static final Set<Loading> loading = new HashSet<>();
    private static final Map<Loading, PendingSave> pendingSaves = new LinkedHashMap<>();
    private static final Map<Path, Storage> storages = new HashMap<>();
    private static Path cacheDir;
    private static Active active;
    private static boolean restoringServerDistance;
    private static boolean applyingCached;
    private static ChunkPos scanCenter;
    private static int scanDistance = -1;
    private static List<int[]> scanOffsets = List.of();
    private static int scanIndex;
    /**
     * The island/server read since joining this world, and for how many ticks it has read the same. The tab list and
     * scoreboard can still show the last island for a moment after a world change, so a session only starts once it
     * has read the same for {@link #STABLE_TICKS}. Once started it stays until the world changes: Hypixel always sends
     * a new world for a new island or server, and re-reading it every tick (the tab list and scoreboard flicker) is what
     * made the terrain drop and reload at random.
     */
    private static Session candidate;
    private static int candidateTicks;
    /**
     * The last session's island and server. A new world there (Hypixel rebuilds it when you die, for example) starts
     * keeping terrain at once, so the cached terrain comes back right away instead of after the stable reading wait.
     */
    private static Session lastSession;
    private static final int STABLE_TICKS = 40;
    /** Each chunk cache's storage centre and radius: {centreX, centreZ, radius or -1}, as vanilla keeps them. */
    private static final Map<ClientChunkCache, int[]> storageShape = new WeakHashMap<>();

    private KeepTerrainLoaded() {}

    private static int[] shape(ClientChunkCache cache) {
        return storageShape.computeIfAbsent(cache, k -> new int[]{0, 0, -1});
    }

    /** The server moved the chunk storage's centre ({@code Sky2MKeepTerrainChunkCacheMixin}). */
    public static void onViewCenter(ClientChunkCache cache, int x, int z) {
        int[] shape = shape(cache);
        shape[0] = x;
        shape[1] = z;
    }

    /**
     * The chunk storage is about to be resized to {@code viewDistance}. Vanilla forgets the chunks that don't fit
     * without unloading them, which leaves Sodium drawing chunks that are gone (a crash in Sodium's chunk renderer), so
     * they're dropped properly first.
     */
    public static void beforeResize(ClientChunkCache cache, int viewDistance) {
        int[] shape = shape(cache);
        int newRadius = Math.max(2, viewDistance) + 3;
        int oldRadius = shape[2];
        shape[2] = newRadius;
        if (oldRadius >= 0 && newRadius >= oldRadius) return;
        int scan = oldRadius >= 0 ? oldRadius : MAX_DISTANCE + 3;
        int cx = shape[0], cz = shape[1];
        for (int x = cx - scan; x <= cx + scan; x++) {
            for (int z = cz - scan; z <= cz + scan; z++) {
                if (Math.abs(x - cx) <= newRadius && Math.abs(z - cz) <= newRadius) continue;
                if (cache.getChunk(x, z, ChunkStatus.FULL, false) == null) continue;
                ChunkPos position = new ChunkPos(x, z);
                cache.drop(position);
                retained.remove(position);
            }
        }
    }

    public static void init(Path configDir) {
        cacheDir = configDir.resolve("sky2m").resolve("terrain-cache");
        ClientTickEvents.END_CLIENT_TICK.register(KeepTerrainLoaded::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> close());
    }

    /** The client keeps chunks this far out ({@code Sky2MKeepTerrainLoadedMixin}). */
    public static int storageViewDistance(int viewDistance) {
        if (restoringServerDistance) return viewDistance;
        Minecraft mc = Minecraft.getInstance();
        if (!keeping(mc.level)) return viewDistance;
        return Math.max(viewDistance, cacheDistance(mc));
    }

    /** This world's terrain is being kept (a session started in it and the feature is still on for its island). */
    private static boolean keeping(ClientLevel level) {
        return level != null && active != null && active.level() == level && enabledFor(active.session().island());
    }

    /** The server unloads a chunk: keep it instead, if we're keeping this island's terrain. */
    public static boolean didRetain(ClientLevel level, ChunkPos position) {
        if (!keeping(level)) return false;
        LevelChunk chunk = level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false);
        if (chunk == null) return false;
        chunk.clearAllBlockEntities();
        retained.add(position);
        return true;
    }

    /** The server sent a chunk: remember it on disk. */
    public static void onServerChunk(ClientLevel level, ClientboundLevelChunkWithLightPacket packet) {
        if (applyingCached || !keeping(level)) return;
        ChunkPos position = new ChunkPos(packet.x(), packet.z());
        retained.remove(position);
        Loading key = new Loading(active.storage(), position.pack());
        pendingSaves.put(key, new PendingSave(active.storage(), level.registryAccess(), level.getSectionsCount(), packet));
        while (pendingSaves.size() > MAX_PENDING_SAVES) {
            Iterator<Loading> it = pendingSaves.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private static void tick(Minecraft mc) {
        processPendingSaves();
        ClientLevel level = mc.level;
        if (active != null && !keeping(level)) deactivate(mc);
        if (level == null) {
            candidate = null;
            return;
        }
        if (active == null) {
            Session session = session();
            if (session == null || !session.equals(candidate)) {
                candidate = session;
                candidateTicks = 0;
                return;
            }
            if (++candidateTicks < STABLE_TICKS && !session.equals(lastSession)) return;
            activate(mc, level, session);
            if (active == null) return;
        }
        Session session = active.session();
        Integer serverDistance = serverViewDistance(mc);
        if (serverDistance == null) return;
        int distance = cacheDistance(mc);
        level.getChunkSource().updateViewRadius(Math.max(serverDistance, distance));
        if (mc.player == null) return;
        ChunkPos center = mc.player.chunkPosition();
        if (!center.equals(scanCenter) || scanDistance != distance) resetScan(center, distance);
        processCachedLoads(mc, level, session);
    }

    private static void activate(Minecraft mc, ClientLevel level, Session session) {
        retained.clear();
        Integer serverDistance = serverViewDistance(mc);
        if (serverDistance == null) return;
        active = new Active(level, session, storageFor(session));
        lastSession = session;
        int distance = cacheDistance(mc);
        level.getChunkSource().updateViewRadius(Math.max(serverDistance, distance));
        if (mc.player != null) resetScan(mc.player.chunkPosition(), distance);
    }

    private static void processPendingSaves() {
        for (int i = 0; i < MAX_SAVES_PER_TICK; i++) {
            Iterator<PendingSave> it = pendingSaves.values().iterator();
            if (!it.hasNext()) return;
            PendingSave save = it.next();
            it.remove();
            ENCODER.execute(() -> {
                try {
                    save.storage().save(new ChunkPos(save.packet().x(), save.packet().z()), encode(save.packet(), save.registries()), save.sections());
                } catch (Exception e) {
                    System.err.println("[Sky2M] Couldn't cache a terrain chunk: " + e);
                }
            });
        }
    }

    private static void processCachedLoads(Minecraft mc, ClientLevel level, Session session) {
        Storage storage = active.storage();
        int checks = 0;
        while (scanIndex < scanOffsets.size() && loadingCount(storage) < MAX_CONCURRENT_LOADS && checks++ < MAX_SCAN_CHECKS_PER_TICK) {
            int[] offset = scanOffsets.get(scanIndex++);
            if (scanCenter == null) return;
            ChunkPos position = new ChunkPos(scanCenter.x() + offset[0], scanCenter.z() + offset[1]);
            if (level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false) != null) continue;
            Loading key = new Loading(storage, position.pack());
            if (!loading.add(key)) continue;
            storage.load(position, level.getSectionsCount()).whenComplete((bytes, error) -> mc.execute(() -> {
                loading.remove(key);
                if (error != null) System.err.println("[Sky2M] Couldn't load a cached terrain chunk: " + error);
                else if (bytes != null) applyCached(mc, level, session, position, bytes);
            }));
        }
    }

    private static int loadingCount(Storage storage) {
        int count = 0;
        for (Loading l : loading) if (l.storage() == storage) count++;
        return count;
    }

    private static void applyCached(Minecraft mc, ClientLevel level, Session session, ChunkPos position, byte[] bytes) {
        if (active == null || active.level() != level || !active.session().equals(session) || !withinScan(position)) return;
        if (level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false) != null) return;
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        try {
            ClientboundLevelChunkWithLightPacket packet = decode(bytes, level.registryAccess());
            if (packet.x() != position.x() || packet.z() != position.z()) return;
            applyingCached = true;
            try {
                packet.handle(connection);
            } finally {
                applyingCached = false;
            }
            LevelChunk chunk = level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false);
            if (chunk != null) chunk.clearAllBlockEntities();
            retained.add(position);
        } catch (Exception e) {
            applyingCached = false;
            System.err.println("[Sky2M] Couldn't apply a cached terrain chunk: " + e);
        }
    }

    private static void resetScan(ChunkPos center, int distance) {
        scanCenter = center;
        scanIndex = 0;
        if (scanDistance != distance) {
            scanDistance = distance;
            scanOffsets = offsets(distance);
        }
    }

    private static boolean withinScan(ChunkPos position) {
        return scanCenter != null && Math.abs(position.x() - scanCenter.x()) <= scanDistance
            && Math.abs(position.z() - scanCenter.z()) <= scanDistance;
    }

    private static void deactivate(Minecraft mc) {
        if (active != null && mc.level == active.level()) {
            releaseRetained(active.level());
            resizeToServerDistance(mc, active.level());
        }
        active = null;
        candidate = null;
        candidateTicks = 0;
        scanCenter = null;
        scanDistance = -1;
        scanOffsets = List.of();
        scanIndex = 0;
    }

    private static void releaseRetained(ClientLevel level) {
        for (ChunkPos position : retained) {
            level.getChunkSource().drop(position);
            removeLight(level, position);
        }
        retained.clear();
    }

    private static void removeLight(ClientLevel level, ChunkPos position) {
        level.queueLightUpdate(() -> {
            if (level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false) != null) return;
            var light = level.getLightEngine();
            light.setLightEnabled(position, false);
            for (int y = light.getMinLightSection(); y < light.getMaxLightSection(); y++) {
                SectionPos section = SectionPos.of(position, y);
                light.queueSectionData(LightLayer.BLOCK, section, null);
                light.queueSectionData(LightLayer.SKY, section, null);
            }
            for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
                light.updateSectionStatus(SectionPos.of(position, y), true);
            }
        });
    }

    private static void resizeToServerDistance(Minecraft mc, ClientLevel level) {
        Integer serverDistance = serverViewDistance(mc);
        if (serverDistance == null) return;
        restoringServerDistance = true;
        try {
            level.getChunkSource().updateViewRadius(serverDistance);
        } finally {
            restoringServerDistance = false;
        }
    }

    private static Storage storageFor(Session session) {
        Path dir = cacheDir.resolve(session.island().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_"));
        return storages.computeIfAbsent(dir, Storage::new);
    }

    private static Integer serverViewDistance(Minecraft mc) {
        return mc.getConnection() instanceof Sky2MClientPacketListenerAccessor accessor ? accessor.sky2m$serverChunkRadius() : null;
    }

    /** The island and server you're on, if this island's terrain is kept. */
    private static Session session() {
        if (!Sky2MLocation.onSkyblock()) return null;
        String island = Sky2MLocation.area();
        if (!enabledFor(island)) return null;
        String server = null;
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = SERVER_LINE.matcher(line);
            if (m.find()) {
                server = m.group(1);
                break;
            }
        }
        // A full scoreboard (Jacob's Contest and pests in the Garden) drops the date and server line at its top, which
        // kept terrain from ever being kept there; the tab list names the server too.
        if (server == null) server = tabServer();
        return server == null ? null : new Session(island, server);
    }

    /** The tab list's "Server: m24W", or null. */
    private static String tabServer() {
        for (net.minecraft.client.multiplayer.PlayerInfo info : com.epic60869.sky2m.misc.Sky2MTabWidgetManager.players()) {
            net.minecraft.network.chat.Component name = com.epic60869.sky2m.custom.util.Compat.rawTabName(info);
            if (name == null) continue;
            String text = Sky2MLocation.strip(name.getString()).trim();
            if (text.startsWith("Server: ") && text.length() > 8) return text.substring(8).trim();
        }
        return null;
    }

    /** The feature is on and this island's terrain is kept (it's one of the islands and isn't left out). */
    private static boolean enabledFor(String island) {
        if (BOBBY) return false;
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || !c.misc.keepTerrainLoaded.enabled) return false;
        return ISLANDS.contains(island) && !excluded(c.misc.keepTerrainLoaded.excludedIslands, island);
    }

    private static boolean excluded(String list, String island) {
        if (list == null || list.isBlank()) return false;
        for (String name : list.split(",")) if (name.trim().equalsIgnoreCase(island)) return true;
        return false;
    }

    private static void close() {
        pendingSaves.clear();
        loading.clear();
        // Closed after the saves still being encoded, on their thread.
        List<Storage> closing = new ArrayList<>(storages.values());
        ENCODER.execute(() -> {
            for (Storage storage : closing) {
                try {
                    storage.close();
                } catch (Exception e) {
                    System.err.println("[Sky2M] Couldn't close the terrain cache: " + e);
                }
            }
        });
        storages.clear();
        retained.clear();
        active = null;
        lastSession = null;
        candidate = null;
        candidateTicks = 0;
        restoringServerDistance = false;
        applyingCached = false;
        scanCenter = null;
        scanDistance = -1;
        scanOffsets = List.of();
        scanIndex = 0;
    }

    private static int cacheDistance(Minecraft mc) {
        return Math.min(mc.options.renderDistance().get(), MAX_DISTANCE);
    }

    /** The chunks around you, nearest first (rings outwards). */
    private static List<int[]> offsets(int distance) {
        List<int[]> offsets = new ArrayList<>();
        offsets.add(new int[]{0, 0});
        for (int r = 1; r <= distance; r++) {
            for (int x = -r; x <= r; x++) {
                offsets.add(new int[]{x, -r});
                offsets.add(new int[]{x, r});
            }
            for (int z = -r + 1; z < r; z++) {
                offsets.add(new int[]{-r, z});
                offsets.add(new int[]{r, z});
            }
        }
        return offsets;
    }

    private static byte[] encode(ClientboundLevelChunkWithLightPacket packet, RegistryAccess registries) {
        ByteBuf source = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(source, registries);
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            source.release();
        }
    }

    private static ClientboundLevelChunkWithLightPacket decode(byte[] bytes, RegistryAccess registries) {
        if (bytes.length > MAX_PACKET_BYTES) throw new IllegalArgumentException("cached chunk too large");
        ByteBuf source = Unpooled.wrappedBuffer(bytes);
        try {
            return ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(source, registries));
        } finally {
            source.release();
        }
    }

    /** Region files of saved chunk packets for one island. */
    private static final class Storage extends IOWorker {
        Storage(Path dir) {
            super(new RegionStorageInfo("sky2m", Level.OVERWORLD, "terrain"), dir, false);
        }

        void save(ChunkPos position, byte[] packet, int sections) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("protocol", SharedConstants.getProtocolVersion());
            tag.putInt("sections", sections);
            tag.putByteArray("packet", packet);
            store(position, tag).exceptionally(error -> {
                System.err.println("[Sky2M] Couldn't write a cached terrain chunk: " + error);
                return null;
            });
        }

        CompletableFuture<byte[]> load(ChunkPos position, int sections) {
            return loadAsync(position).thenApply(stored -> {
                CompoundTag tag = stored.orElse(null);
                if (tag == null) return null;
                if (tag.getIntOr("protocol", -1) != SharedConstants.getProtocolVersion()) return null;
                if (tag.getIntOr("sections", -1) != sections) return null;
                byte[] bytes = tag.getByteArray("packet").orElse(null);
                return bytes != null && bytes.length <= MAX_PACKET_BYTES ? bytes : null;
            });
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(System.identityHashCode(this));
        }
    }
}
