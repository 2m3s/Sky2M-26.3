package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.dungeons.BloodCampSkulls;
import com.epic60869.sky2m.sim.livemap.DungeonLayout;
import com.epic60869.sky2m.sim.util.FeatureGuard;
import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Watcher's part of a run: Blood, the way Hypixel runs it, and the way into the boss.
 *
 * <p>Walking into Blood on a generated floor wakes <b>The Watcher</b> - a floating head over the middle of the room,
 * a zombie wearing the Watcher's skin as the mod's Blood Camp helper knows it ({@link BloodCampSkulls}). He greets
 * you with one of Hypixel's openers and starts throwing <b>skulls</b>: armour stands wearing a blood mob's head that
 * fly in a straight line from him to a spot on the floor and turn into the mob when they land. The first wave goes
 * with the greeting; then "Let's see how you can handle this.", he drops lower, and the rest come in waves of up to
 * four until the floor's total ({@link #mobCount}) is out. Blood mobs fight with Hypixel's Watcher-undead health and
 * damage for the floor ({@link SimMobs#spawnBloodMob}, {@link SimHealth#BLOOD}). Once every one is dead he says
 * "You have proven yourself. You may pass." and a pad appears in the middle
 * of the room; stepping on it builds the floor's boss room ({@link SimBoss}). On the
 * Entrance, which has no boss, clearing Blood ends the run.
 *
 * <p><b>For the Blood Camp helper.</b> {@code BloodCamp} finds the Watcher by his head texture, tracks each thrown
 * head from the moment it moves, predicts where it lands (16.1 blocks along its line for the first wave, 11.9 after
 * "Let's see...") and counts down to the spawn (40 + 38 server ticks for the first wave, 38 after - its default
 * Spawn Tick). The skulls here fly exactly those distances in exactly those times, rest a moment before they move
 * (as the helper expects of a head it has not seen move yet), and the server sends the per-tick ping the helper
 * counts ticks by, which a local world otherwise never sends. The Watcher's boss bar counts the mobs left.
 *
 * <p>The Watcher's lines are Hypixel's exact words, so Blood Camp, the score's "watcher cleared" and the party's
 * blood-done flag all fire in the sim as they do on Hypixel.
 *
 * <p>Detection runs on the client tick (the room under the player comes from the client's map layout); everything
 * that touches the world runs on the server thread.
 */
public final class SimWatcher {

    private static final Logger LOGGER = ModLog.get("sky2m-simboss");

    /**
     * Hypixel's openers, the same list {@code BloodCamp} matches, in floor order: Entrance, then floors 1 to 7 (the
     * wiki's Welcome Speech for each floor).
     */
    private static final String[] OPENERS = {
            "Congratulations, you made it through the Entrance.",
            "Ah, you've finally arrived.",
            "Ah, we meet again...",
            "So you made it this far... interesting.",
            "You've managed to scratch and claw your way here, eh?",
            "I'm starting to get tired of seeing you around here...",
            "Oh.. hello?",
            "Things feel a little more roomy now, eh?",
    };

    /** The blood mobs' names; {@link SimHealth#statsFor} gives each the Watcher's undead stats for the floor. */
    static final String[] MOB_NAMES = {
            "Revoker", "Psycho", "Reaper", "Cannibal", "Mute", "Ooze", "Putrid", "Freak", "Leech", "Tear",
            "Parasite", "Flamer", "Skull", "Mr. Dead", "Vader", "Frost", "Walker", "Wandering Soul",
    };

    /** How far a skull flies, as {@code BloodCamp} predicts it: the first wave, then every later one. */
    private static final double FIRST_THROW = 16.1;
    private static final double THROW = 11.9;
    /** Ticks from a skull starting to move to its mob appearing: Blood Camp's 2 s + 38 ticks, then 38 ticks. */
    private static final int FIRST_FLIGHT_TICKS = 40 + 38;
    private static final int FLIGHT_TICKS = 38;
    /** A thrown skull hangs still this long before it moves, so the helper has seen it at rest. */
    private static final int REST_TICKS = 6;
    /** The Watcher's height over the floor before and after he moves. */
    private static final int START_HEIGHT = 10;

    /** How far down the room's floor is looked for, below where he walked in or where a skull was aimed. */
    private static final int FLOOR_SEARCH = 32;
    private static final int MOVED_HEIGHT = 5;
    /** Skulls per wave, and the gap between waves. */
    private static final int WAVE_SIZE = 4;
    private static final int WAVE_GAP_TICKS = 100;
    /** "Let's see how you can handle this." this long after the opener; he moves 24 ticks later (Blood Camp's guess). */
    private static final int MOVE_LINE_TICKS = 60;
    private static final int MOVE_AFTER_LINE_TICKS = 24;

    private enum Stage { IDLE, SPAWNING, PORTAL, DONE }

    /** A skull in flight. */
    private static final class Skull {
        final ArmorStand stand;
        final Vec3 from;
        final Vec3 to;
        final int flightTicks;
        final String name;
        final String texture;
        int age;

        Skull(ArmorStand stand, Vec3 from, Vec3 to, int flightTicks, String name, String texture) {
            this.stand = stand;
            this.from = from;
            this.to = to;
            this.flightTicks = flightTicks;
            this.name = name;
            this.texture = texture;
        }
    }

    // Server-thread state.
    private static Stage stage = Stage.IDLE;
    private static int ticks;
    private static BlockPos centre;
    private static int floorY;
    private static int height;
    private static boolean moved;
    private static int total;
    private static int thrown;
    private static boolean entrance;
    private static Zombie watcher;
    private static ArmorStand watcherTag;
    private static ServerBossEvent bar;
    private static final List<Skull> SKULLS = new ArrayList<>();
    private static final List<UUID> MOBS = new ArrayList<>();
    private static int pingId;

    /** Set when the Watcher is done, for the score's Blood room. Read from the client. */
    private static volatile boolean cleared;

    /** Client-side latch, so the server is only asked once per floor. */
    private static boolean requested;

    private SimWatcher() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("SimWatcher.tick", SimWatcher::clientTick));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SimState.isActive()) {
                return;
            }
            ping(server);
            if (stage != Stage.IDLE && stage != Stage.DONE) {
                serverTick(server);
            }
        });
    }

    /** Whether the Watcher has been cleared on this floor. */
    public static boolean isCleared() {
        return cleared;
    }

    /**
     * Hypixel sends a ping packet every server tick and the mod counts server ticks by them
     * ({@code ServerTickCallback}) - the Blood Camp helper's countdowns among them. A local world sends none, so the
     * sim does, while it is active.
     */
    private static void ping(MinecraftServer server) {
        ClientboundPingPacket packet = new ClientboundPingPacket(++pingId);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(packet);
        }
    }

    private static void clientTick(Minecraft client) {
        if (requested || !SimState.canAct(client) || !SimState.isGeneratedFloor() || SimBoss.isActive()) {
            return;
        }
        MinecraftServer host = client.getSingleplayerServer();
        if (host == null) {
            // A sim party guest: the host's client starts the Watcher for the whole party.
            return;
        }
        // Whoever walks in first - the host or a party guest.
        BlockPos in = null;
        for (ServerPlayer p : host.getPlayerList().getPlayers()) {
            if ("Blood".equals(SimState.roomNameAt(p.getBlockX(), p.getBlockZ()))) {
                in = p.blockPosition();
                break;
            }
        }
        if (in == null) {
            return;
        }
        BlockPos room = bloodCentre();
        if (room == null) {
            return;
        }
        requested = true;
        BlockPos at = new BlockPos(room.getX(), in.getY(), room.getZ());
        String floor = SimState.floorLabel();
        MinecraftServer server = host;
        server.execute(() -> open(server, at, "E".equalsIgnoreCase(floor), mobCount(floor)));
    }

    /** The floor's own opener ("E", "F3", "M5"...); a label it cannot read gets one at random. */
    private static String opener(String floor) {
        char last = floor == null || floor.isEmpty() ? '?' : floor.charAt(floor.length() - 1);
        if (last == 'E' || last == 'e') {
            return OPENERS[0];
        }
        if (last >= '1' && last <= '7') {
            return OPENERS[last - '0'];
        }
        return OPENERS[ThreadLocalRandom.current().nextInt(OPENERS.length)];
    }

    /** The middle of the Blood room, from the map's own layout. Client thread. */
    private static BlockPos bloodCentre() {
        DungeonLayout layout = DungeonLayout.current();
        for (int r = 0; r < layout.roomCount(); r++) {
            if ("Blood".equals(layout.name(r))) {
                int[] tiles = layout.tiles(r);
                if (tiles == null || tiles.length == 0) {
                    return null;
                }
                long sx = 0;
                long sz = 0;
                for (int t : tiles) {
                    BlockPos c = DungeonLayout.cellCenter(t);
                    sx += c.getX();
                    sz += c.getZ();
                }
                return new BlockPos((int) (sx / tiles.length), 0, (int) (sz / tiles.length));
            }
        }
        return null;
    }

    /**
     * Blood mobs per floor. APPROXIMATE - Hypixel's exact counts are not published: Master Mode is taken as 12 plus
     * the floor number (M7: 19), which is also the total the Blood Camp helper's Watcher Bar assumes (Odin's
     * {@code 12 + floor}); the Normal floors as 8 plus the floor number (F7: 15); the Entrance 6.
     */
    static int mobCount(String floor) {
        if (floor == null || floor.isEmpty()) {
            return 19;
        }
        String f = floor.toUpperCase(Locale.ROOT);
        if (f.equals("E")) {
            return 6;
        }
        char last = f.charAt(f.length() - 1);
        int n = Character.isDigit(last) ? last - '0' : 7;
        return f.startsWith("M") ? 12 + n : 8 + n;
    }

    private static void open(MinecraftServer server, BlockPos at, boolean isEntrance, int count) {
        if (stage != Stage.IDLE) {
            return;
        }
        ServerLevel level = server.overworld();
        entrance = isEntrance;
        total = Math.max(1, count);
        thrown = 0;
        ticks = 0;
        moved = false;
        cleared = false;
        MOBS.clear();
        SKULLS.clear();
        floorY = floorAt(level, at);
        centre = new BlockPos(at.getX(), floorY, at.getZ());
        height = Math.max(3, Math.min(START_HEIGHT, headroom(level, centre) - 2));
        spawnWatcher(level);
        bar = new ServerBossEvent(UUID.randomUUID(), Component.literal("The Watcher").withStyle(ChatFormatting.RED),
                BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(1f);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }
        stage = Stage.SPAWNING;
        SimBoss.say(server, "The Watcher", opener(SimState.floorLabel()));
        LOGGER.info("Sim Watcher woke at {}: {} blood mob(s) on {}", centre, total, SimState.floorLabel());
    }

    /**
     * The floor under the room's middle: the first standable spot at or below where he walked in. Searched well
     * down, not 8 blocks: walking in mid-air (falling, a teleport) used to leave the floor at his own height, every
     * throw then found no floor and every blood mob fell into the void.
     */
    private static int floorAt(ServerLevel level, BlockPos at) {
        for (int dy = 2; dy >= -FLOOR_SEARCH; dy--) {
            BlockPos p = at.above(dy);
            if (standable(level, p)) {
                return p.getY();
            }
        }
        return at.getY();
    }

    /** Blocks of air above the floor at the room's middle, up to {@link #START_HEIGHT} + 2. */
    private static int headroom(ServerLevel level, BlockPos floor) {
        for (int dy = 0; dy <= START_HEIGHT + 2; dy++) {
            BlockPos p = floor.above(dy);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return dy;
            }
        }
        return START_HEIGHT + 2;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    private static void spawnWatcher(ServerLevel level) {
        WatcherHead head = new WatcherHead(level);
        head.setItemSlot(EquipmentSlot.HEAD, Compat.createSkull(BloodCampSkulls.watcherTexture()));
        head.snapTo(centre.getX() + 0.5, floorY + height, centre.getZ() + 0.5, 180f, 0f);
        level.addFreshEntity(head);
        watcher = head;
        ArmorStand tag = new ArmorStand(level, head.getX(), head.getY() + 2.1, head.getZ());
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setPermanentlyInvulnerable(true);
        byte flags = tag.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomName(Component.literal("The Watcher").withStyle(ChatFormatting.RED));
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        watcherTag = tag;
        level.sendParticles(ParticleTypes.LARGE_SMOKE, head.getX(), head.getY() + 1.5, head.getZ(), 20, 0.4, 0.4,
                0.4, 0.02);
    }

    private static void serverTick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ticks++;
        switch (stage) {
            case SPAWNING -> {
                if (watcher != null && watcher.isAlive() && ticks % 10 == 0) {
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, watcher.getX(), watcher.getY() + 1.6,
                            watcher.getZ(), 2, 0.2, 0.2, 0.2, 0.01);
                }
                // The first spawns go with the greeting; the rest after "Let's see how you can handle this."
                if (ticks >= 20 && ticks < 20 + WAVE_SIZE * 3 && (ticks - 20) % 3 == 0 && thrown < total) {
                    throwSkull(level, true);
                }
                if (ticks == MOVE_LINE_TICKS) {
                    SimBoss.say(server, "The Watcher", "Let's see how you can handle this.");
                }
                if (ticks == MOVE_LINE_TICKS + MOVE_AFTER_LINE_TICKS) {
                    move(level);
                }
                int sinceLine = ticks - MOVE_LINE_TICKS - MOVE_AFTER_LINE_TICKS;
                if (moved && thrown < total && sinceLine > 0) {
                    int inWave = sinceLine % WAVE_GAP_TICKS;
                    if (inWave < WAVE_SIZE * 3 && inWave % 3 == 0) {
                        throwSkull(level, false);
                    }
                }
                flySkulls(level);
                updateBar();
                if (thrown >= total && SKULLS.isEmpty() && allDead(level)) {
                    cleared(server, level);
                }
            }
            case PORTAL -> {
                if (ticks % 4 == 0) {
                    level.sendParticles(ParticleTypes.PORTAL, centre.getX() + 0.5, centre.getY() + 0.5,
                            centre.getZ() + 0.5, 20, 0.8, 0.6, 0.8, 0.4);
                }
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    double dx = p.getX() - (centre.getX() + 0.5);
                    double dz = p.getZ() - (centre.getZ() + 0.5);
                    if (dx * dx + dz * dz < 1.6 * 1.6 && Math.abs(p.getY() - centre.getY()) < 2
                            && !SimDeath.isGhost(p)) {
                        stage = Stage.DONE;
                        SimBoss.enterForFloor(server);
                        return;
                    }
                }
            }
            default -> {
            }
        }
    }

    /** The Watcher drops lower once the first spawns are out, as Hypixel's does. */
    private static void move(ServerLevel level) {
        moved = true;
        int to = Math.min(MOVED_HEIGHT, height);
        height = to;
        if (watcher != null && watcher.isAlive()) {
            watcher.snapTo(watcher.getX(), floorY + to, watcher.getZ(), watcher.getYRot(), 0f);
            if (watcherTag != null) {
                watcherTag.setPos(watcher.getX(), watcher.getY() + 2.1, watcher.getZ());
            }
            level.playSound(null, watcher.getX(), watcher.getY(), watcher.getZ(), SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.HOSTILE, 0.8f, 0.6f);
        }
    }

    /** Where a skull starts: the Watcher's head. */
    private static Vec3 throwOrigin() {
        double x = watcher != null ? watcher.getX() : centre.getX() + 0.5;
        double z = watcher != null ? watcher.getZ() : centre.getZ() + 0.5;
        return new Vec3(x, floorY + height + 0.5, z);
    }

    /**
     * Throws one skull at a standable spot exactly {@code first ? 16.1 : 11.9} blocks from the Watcher's head, the
     * distance the helper predicts. Tries angles round the room until one lands on the floor.
     */
    private static void throwSkull(ServerLevel level, boolean first) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double dist = first ? FIRST_THROW : THROW;
        Vec3 from = throwOrigin();
        Vec3 to = null;
        double start = rnd.nextDouble() * Math.PI * 2;
        outer:
        for (int i = 0; i < 24; i++) {
            double a = start + i * (Math.PI * 2 / 24);
            for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3}) {
                int y = floorY + dy;
                double h = from.y - y;
                if (Math.abs(h) >= dist) {
                    continue;
                }
                double r = Math.sqrt(dist * dist - h * h);
                double x = from.x + Math.cos(a) * r;
                double z = from.z + Math.sin(a) * r;
                if (standable(level, BlockPos.containing(x, y, z))) {
                    to = new Vec3(x, y, z);
                    break outer;
                }
            }
        }
        if (to == null) {
            // Nowhere on the floor at that distance: straight down in front of him, so the wave still completes -
            // onto the floor below that spot, not in the air above the void.
            BlockPos below = BlockPos.containing(from.x + 2, floorY, from.z);
            for (int dy = 0; dy <= FLOOR_SEARCH && !standable(level, below); dy++) {
                below = below.below();
            }
            to = standable(level, below) ? new Vec3(from.x + 2, below.getY(), from.z)
                    : new Vec3(from.x + 2, floorY, from.z);
        }
        int index = rnd.nextInt(MOB_NAMES.length);
        List<String> textures = BloodCampSkulls.mobTextures();
        String texture = textures.isEmpty() ? "" : textures.get(index % textures.size());
        ArmorStand stand = new ArmorStand(level, from.x, from.y, from.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setSilent(true);
        byte flags = stand.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        stand.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        stand.setItemSlot(EquipmentSlot.HEAD, Compat.createSkull(texture));
        level.addFreshEntity(stand);
        SKULLS.add(new Skull(stand, from, to, first ? FIRST_FLIGHT_TICKS : FLIGHT_TICKS, MOB_NAMES[index], texture));
        thrown++;
    }

    /** Moves every skull along its line; one that has arrived becomes its mob. */
    private static void flySkulls(ServerLevel level) {
        for (Iterator<Skull> it = SKULLS.iterator(); it.hasNext(); ) {
            Skull s = it.next();
            s.age++;
            if (s.stand.isRemoved()) {
                it.remove();
                land(level, s);
                continue;
            }
            int moving = s.age - REST_TICKS;
            if (moving <= 0) {
                continue;
            }
            double t = Math.min(1.0, moving / (double) s.flightTicks);
            Vec3 at = s.from.add(s.to.subtract(s.from).scale(t));
            s.stand.setPos(at.x, at.y, at.z);
            if (moving >= s.flightTicks) {
                it.remove();
                s.stand.discard();
                land(level, s);
            }
        }
    }

    private static void land(ServerLevel level, Skull s) {
        BlockPos at = BlockPos.containing(s.to.x, s.to.y, s.to.z);
        UUID id = SimMobs.spawnBloodMob(level, at, s.name, Compat.createSkull(s.texture));
        if (id != null) {
            MOBS.add(id);
        }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, s.to.x, s.to.y + 1, s.to.z, 10, 0.3, 0.5, 0.3, 0.02);
        level.playSound(null, s.to.x, s.to.y, s.to.z, SoundEvents.ZOMBIE_INFECT, SoundSource.HOSTILE, 0.6f, 0.8f);
    }

    /** The boss bar: the share of the floor's blood mobs not yet killed. */
    private static void updateBar() {
        if (bar == null || ticks % 5 != 0) {
            return;
        }
        int dead = 0;
        for (UUID id : MOBS) {
            Entity e = watcher == null ? null : ((ServerLevel) watcher.level()).getEntity(id);
            if (e == null || !e.isAlive()) {
                dead++;
            }
        }
        bar.setProgress(Math.max(0f, Math.min(1f, (total - dead) / (float) total)));
    }

    /**
     * Whether every blood mob is dead. A mob that cannot be found counts as dead only once the player is close
     * enough for its section to be ticking - a missing entity in an unloaded section is not a kill.
     */
    private static boolean allDead(ServerLevel level) {
        for (UUID id : MOBS) {
            Entity e = level.getEntity(id);
            if (e != null && e.isAlive()) {
                return false;
            }
            if (e == null && !level.isPositionEntityTicking(centre)) {
                return false;
            }
        }
        return true;
    }

    private static void cleared(MinecraftServer server, ServerLevel level) {
        SimBoss.say(server, "The Watcher", "You have proven yourself. You may pass.");
        MOBS.clear();
        cleared = true;
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
        if (entrance) {
            // The Entrance has no boss: Blood is the end of the run.
            boolean timed = SimRun.isRunning();
            SimRun.finish();
            SimBoss.tell(server, timed
                    ? new Component[]{ModChat.text("Entrance cleared, run time "), ModChat.value(SimRun.elapsedText())}
                    : new Component[]{ModChat.text("Entrance cleared.")});
            stage = Stage.DONE;
            return;
        }
        // A pad in the floor to step on, in place of Hypixel's portal - the vanilla portal blocks would try to
        // send him to a dimension the sim does not have.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlockAndUpdate(centre.offset(dx, -1, dz), Blocks.CRYING_OBSIDIAN.defaultBlockState());
            }
        }
        level.playSound(null, centre.getX(), centre.getY(), centre.getZ(), SoundEvents.END_PORTAL_SPAWN,
                SoundSource.HOSTILE, 0.8f, 1f);
        SimBoss.tell(server, ModChat.text("Step on the portal in the middle of Blood to go to the boss."));
        stage = Stage.PORTAL;
        ticks = 0;
        LOGGER.info("Sim Watcher cleared at {}", centre);
    }

    /** Forgets the Watcher for a new map or leaving. Safe from the client thread. */
    public static void reset(Minecraft client) {
        requested = false;
        cleared = false;
        MinecraftServer server = client == null ? null : client.getSingleplayerServer();
        List<UUID> mobs = List.copyOf(MOBS);
        List<Entity> own = new ArrayList<>();
        if (watcher != null) {
            own.add(watcher);
        }
        if (watcherTag != null) {
            own.add(watcherTag);
        }
        for (Skull s : SKULLS) {
            own.add(s.stand);
        }
        ServerBossEvent oldBar = bar;
        if (server != null && SimState.isActive()) {
            server.execute(() -> {
                ServerLevel level = server.overworld();
                for (UUID id : mobs) {
                    Entity e = level.getEntity(id);
                    if (e != null) {
                        e.discard();
                    }
                }
                for (Entity e : own) {
                    if (!e.isRemoved()) {
                        e.discard();
                    }
                }
                if (oldBar != null) {
                    oldBar.removeAllPlayers();
                }
                clearState();
            });
        } else {
            if (oldBar != null) {
                oldBar.removeAllPlayers();
            }
            clearState();
        }
    }

    private static void clearState() {
        stage = Stage.IDLE;
        MOBS.clear();
        SKULLS.clear();
        centre = null;
        watcher = null;
        watcherTag = null;
        bar = null;
        thrown = 0;
        total = 0;
        moved = false;
    }

    /**
     * The Watcher himself: an invisible zombie wearing his head, floating still, never hurt, never despawning. The
     * head is what shows, and what the Blood Camp helper looks for.
     */
    private static final class WatcherHead extends Zombie {
        WatcherHead(Level level) {
            super(com.epic60869.sky2m.sim.compat.McEntities.ZOMBIE, level);
            setNoAi(true);
            setNoGravity(true);
            setPermanentlyInvulnerable(true);
            setSilent(true);
            setInvisible(true);
            setPersistenceRequired();
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean isSunSensitive() {
            return false;
        }

        @Override
        protected boolean convertsInWater() {
            return false;
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
            return true;
        }
    }
}
