/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.FeatureGuard;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import com.epic60869.sky2m.sim.compat.McEntities;

/**
 * The dungeon sim's mobs - zombies, skeletons, Fels and the minibosses.
 *
 * <p>They used to be one-HP dummies that never moved (killer560's first spec, 2026-09-28: "all mobs have one HP"
 * and "I would rather just have them never move"). The user reversed that for Master Mode practice: sim mobs now
 * have Hypixel-sized health for the floor ({@link SimHealth}: regular, starred and miniboss rows, Entrance lowest
 * and M7 in the millions), walk at you, and fight - zombies and Fels in melee, skeletons with bows, minibosses
 * with their own mechanics ({@link SimMiniboss}). They still never despawn, never burn and shrug off the
 * environment. Fels are still a skull that does not move until you get close, then "the upside down Enderman" -
 * see {@link #wake}.
 *
 * <p>Everything below is gated on {@link SimState#canAct}, same as {@link SimAbilities}: this package is the one
 * place this mod is allowed to write positions and spawn entities, and that is only true inside the sim's own
 * integrated server. All spawning happens there, via {@code server.execute(...)}, never on the client.
 */
public final class SimMobs {

    /** Hypixel's Fel wakes once a player closes to about this range: 4 blocks, 9 since SkyBlock 0.27.2 ("+5 blocks";
     *  SimHypixelRules). */
    public static final double FEL_WAKE_RADIUS = SimHypixelRules.FEL_WAKE_RADIUS;
    private static final double FEL_WAKE_RADIUS_SQ = FEL_WAKE_RADIUS * FEL_WAKE_RADIUS;

    /** A secret bat still dies to one hit: it is a secret, not a fight. */
    private static final double ONE_HP = 1.0;

    /**
     * How far from where it spawned a room mob will walk or chase. About half a room tile (31 blocks), so mobs
     * fight in their own room the way Hypixel's do instead of trailing the player round the map - and the room
     * clear, which goes by where a starred mob SPAWNED, keeps meaning the room it is fighting in.
     */
    private static final int HOME_RADIUS = 18;
    private static final int MINIBOSS_HOME_RADIUS = 24;

    /**
     * The mob kinds the sim can place.
     *
     * <p>killer560's spec (2026-09-29), for testing an auto clear later: "regular star zombies that you can
     * spawn anywhere these will mimic things like skeleton grunts, or bouncy archers or any other starred mob
     * in a room" ({@link #ZOMBIE}/{@link #SKELETON}); Fels, which are "the skull and then whenever you step
     * near them, they are the upside down Enderman" ({@link #FEL}); and "essentially a mini boss style [that]
     * should maybe take more than just one hit just to be sure that your config works with something that
     * doesn't get one shot" ({@link #MINIBOSS}) - which is a {@link SimMiniboss}, one of Hypixel's minibosses
     * with its own fight.
     *
     * <p>Every kind can be spawned starred or not - see {@link #spawn} against {@link #spawnStarred} - because
     * telling a starred mob from an ordinary one is the whole job of a clear, and a sim where everything is
     * starred cannot test that.
     */
    public enum Kind {
        ZOMBIE, SKELETON, FEL, BAT, MINIBOSS
    }

    /**
     * Which miniboss {@link Kind#MINIBOSS} places when the caller does not say: Lost Adventurer, the one that
     * turns up in the most rooms.
     */
    static final SimMiniboss.Name DEFAULT_MINIBOSS = SimMiniboss.Name.LOST_ADVENTURER;

    /** Every entity this class has spawned (dummies and woken Fels), for {@link #clear}. */
    private static final List<UUID> SPAWNED = new CopyOnWriteArrayList<>();

    /** UUIDs of spawned mobs flagged "starred". Stale (dead) entries are harmless and are wiped by clear(). */
    private static final List<UUID> STARRED = new CopyOnWriteArrayList<>();

    /** Dormant Fel markers not yet woken into an enderman. */
    private static final List<FelMarker> FELS = new CopyOnWriteArrayList<>();

    /**
     * Published each server tick by {@link #refreshStarred}, read directly by {@link #starredAliveIn} and
     * {@link #anyStarredAlive}. A volatile reference to an immutable list is the whole thread-safety story: the
     * refresh runs on the server thread (inside {@code server.execute}), those two queries are meant to be
     * called from whichever thread the door/key logic runs on, and swapping an immutable reference needs no
     * lock in either direction.
     */
    private static volatile List<StarredEntry> starredSnapshot = List.of();

    /** True once any starred mob has been alive; lets {@link #refreshStarred} tell "just spawned, none yet" (0,
     *  never fire) apart from "the last one just died" (nonzero, then 0 - fire once). Server thread only. */
    private static boolean hadStarred;

    /** Set by whichever file owns the wither-key drop and the doors - not this one; see the class prompt. */
    private static volatile Runnable onLastStarredDeath;

    private SimMobs() {
    }

    /** One position snapshot of a starred mob still alive, taken on the server thread. */
    private record StarredEntry(UUID id, double x, double y, double z) {
    }

    /** A dormant Fel: an invisible armour stand wearing a skull. See {@link #spawnFel}. */
    private static final class FelMarker {
        final ArmorStand skull;
        final boolean starred;
        boolean woken;

        FelMarker(ArmorStand skull, boolean starred) {
            this.skull = skull;
            this.starred = starred;
        }
    }

    public static void register() {
        SimHealth.register();
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerSummon(dispatcher));
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerDummy(dispatcher));
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimMobs.tick", SimMobs::tick));
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Reading and mutating mob/armour-stand state has to happen on the server thread, same reasoning as
        // SimAbilities.teleport: the integrated server, not the client, owns these entities.
        server.execute(() -> {
            ServerLevel level = server.overworld();
            wakeFels(level);
            spawnPendingRoomMobs(level);
            keepInRooms(level);
            refreshStarred(level);
            countDeadBats(level);
            SimMimic.tickServer(level);
            countDeadCrypts(level);
        });
        // Client side: the snapshot the server task above publishes is read here, against the map's rooms.
        if (++roomClearTicks >= 10) {
            roomClearTicks = 0;
            markClearedRooms();
        }
    }

    private static int roomClearTicks = 0;

    /**
     * The room each sim mob belongs to (its index in the map's name table), from where it was placed. Server thread.
     *
     * <p>{@link #HOME_RADIUS} is a circle round the spawn, and a room is 31 blocks across: a mob placed near a
     * doorway chased a player up to 13 blocks into the next room, so a key carrier could end up (and drop its key)
     * in a different room from the one it belongs to. {@link #keepInRooms} holds every mob to its own room.
     */
    private static final java.util.Map<UUID, Integer> HOME_ROOM = new java.util.concurrent.ConcurrentHashMap<>();

    /** Each pinned mob's last position inside its room, to put it back to. Server thread. */
    private static final java.util.Map<UUID, net.minecraft.world.phys.Vec3> LAST_IN_ROOM =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Pins a just-placed mob to the room it stands in. Nothing off the map (boss arenas). Server thread. */
    private static void pinToRoom(Mob mob) {
        int room = SimState.roomIdAt(mob.getBlockX(), mob.getBlockZ());
        if (room >= 0) {
            HOME_ROOM.put(mob.getUUID(), room);
            LAST_IN_ROOM.put(mob.getUUID(), mob.position());
        }
    }

    /**
     * Keeps every pinned mob in its own room, as Hypixel's are: it drops a player who is not in its room, and one
     * that has stepped into another room stops and goes back to where it last stood in its own. Server thread.
     */
    private static void keepInRooms(ServerLevel level) {
        if (HOME_ROOM.isEmpty()) {
            return;
        }
        for (var it = HOME_ROOM.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Entity entity = level.getEntity(entry.getKey());
            if (entity == null) {
                continue;   // its section is not ticking: it is not moving either
            }
            if (!entity.isAlive() || !(entity instanceof Mob mob)) {
                it.remove();
                LAST_IN_ROOM.remove(entry.getKey());
                continue;
            }
            int home = entry.getValue();
            if (mob.getTarget() instanceof net.minecraft.world.entity.player.Player target
                    && SimState.roomIdAt(target.getBlockX(), target.getBlockZ()) != home) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
            if (SimState.roomIdAt(mob.getBlockX(), mob.getBlockZ()) == home) {
                LAST_IN_ROOM.put(entry.getKey(), mob.position());
                continue;
            }
            mob.getNavigation().stop();
            mob.setTarget(null);
            net.minecraft.world.phys.Vec3 back = LAST_IN_ROOM.get(entry.getKey());
            if (back != null) {
                mob.teleportTo(back.x, back.y, back.z);
            }
        }
    }

    /** Rooms holding at least one starred mob (alive, dead or still queued), refreshed with the room clears. */
    private static volatile java.util.Set<String> ROOMS_WITH_STARRED = java.util.Set.of();

    /**
     * Whether a room has starred mobs of its own. A room that has none clears by being visited
     * ({@link SimScore}); one that has clears when the last of them dies.
     */
    static boolean roomHasStarred(String room) {
        return room != null && ROOMS_WITH_STARRED.contains(room);
    }

    /**
     * Where each starred mob stood when it was spawned (or woken), so its ROOM is known. Hypixel clears a room the moment
     * its last starred mob dies (the map's white checkmark); until 2026-10-06 the sim never cleared any room, so nothing
     * client-side (Auto Clear, the score's room count) could see a clear. Written on the server thread.
     */
    private static final java.util.Map<UUID, double[]> STARRED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Starred mobs confirmed DEAD: the entity is there and not alive, or its section is entity-ticking and it is gone. A
     * mob in a section the server is not ticking entities in reads back as null, and missing is never killed (LESSONS:
     * "An entity reads back only from a section the server ticks entities in"). Server thread writes, client reads.
     */
    private static final java.util.Set<UUID> STARRED_DEAD = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void recordStarred(UUID id, double x, double y, double z) {
        STARRED_AT.put(id, new double[]{x, y, z});
        // A placed room mob takes over its stand-in's key, if it carries one (SimKeys).
        UUID marker = handingOver;
        if (marker != null) {
            handingOver = null;
            SimKeys.handOver(marker, id);
        }
    }

    /** The stand-in id of the pending mob being placed right now, for {@link #recordStarred}. Server thread. */
    private static UUID handingOver;

    /**
     * Every starred mob not yet dead - placed, still queued (by its stand-in id) or a dormant Fel - with where it
     * spawned, for {@link SimKeys#assignForFloor}. Any thread.
     */
    static java.util.Map<UUID, double[]> starredPositions() {
        java.util.Map<UUID, double[]> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<UUID, double[]> e : STARRED_AT.entrySet()) {
            if (!STARRED_DEAD.contains(e.getKey())) {
                out.put(e.getKey(), e.getValue().clone());
            }
        }
        return out;
    }

    /**
     * Marks every room whose recorded starred mobs are all confirmed dead as cleared ({@link SimRoomState#markCleared}),
     * and counts it in the score. Client thread: the room of a position comes from the live map's own layout.
     */
    private static void markClearedRooms() {
        if (STARRED_AT.isEmpty()) {
            ROOMS_WITH_STARRED = java.util.Set.of();
            return;
        }
        com.epic60869.sky2m.sim.livemap.DungeonLayout layout = com.epic60869.sky2m.sim.livemap.DungeonLayout.current();
        java.util.Map<String, Boolean> allDead = new java.util.HashMap<>();
        for (java.util.Map.Entry<UUID, double[]> e : STARRED_AT.entrySet()) {
            double[] p = e.getValue();
            int room = layout.roomAtWorld(p[0], p[2]);
            String name = layout.name(room);
            if (name == null || "Unknown".equals(name)) {
                continue;
            }
            boolean dead = STARRED_DEAD.contains(e.getKey());
            allDead.merge(name, dead, Boolean::logicalAnd);
        }
        ROOMS_WITH_STARRED = java.util.Set.copyOf(allDead.keySet());
        for (java.util.Map.Entry<String, Boolean> e : allDead.entrySet()) {
            if (e.getValue() && SimRoomState.markCleared(e.getKey())) {
                SimScore.roomCleared();
                com.epic60869.sky2m.sim.util.ModLog.get("sky2m-sim").info("[Sim] room cleared: {} (every starred mob"
                        + " in it is dead)", e.getKey());
            }
        }
    }

    public static void spawn(Minecraft client, BlockPos pos, Kind kind) {
        spawn(client, pos, kind, false, DEFAULT_MINIBOSS);
    }

    public static void spawnStarred(Minecraft client, BlockPos pos, Kind kind) {
        spawn(client, pos, kind, true, DEFAULT_MINIBOSS);
    }

    /**
     * Places one named Catacombs miniboss with its own fight. See {@link SimMiniboss}.
     *
     * @param starred whether it counts toward the room's starred-mob tally (and so toward the last-starred-death
     *                hook) and carries the "✯" on its name stand. A Hypixel miniboss has to die for a full
     *                clear, so a room that gates on one wants this true.
     */
    public static void spawnMiniboss(Minecraft client, BlockPos pos, SimMiniboss.Name name, boolean starred) {
        spawn(client, pos, Kind.MINIBOSS, starred, name == null ? DEFAULT_MINIBOSS : name);
    }

    private static void spawn(Minecraft client, BlockPos pos, Kind kind, boolean starred, SimMiniboss.Name boss) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> spawnOnServer(server.overworld(), pos, kind, starred, boss));
    }

    /** {@link #spawn} on the server thread: also a staff party guest's /summon, sent to the host (SimParty). */
    static void spawnOnServer(ServerLevel level, BlockPos pos, Kind kind, boolean starred, SimMiniboss.Name boss) {
        switch (kind) {
            case ZOMBIE -> spawnDummy(level, new SimZombie(McEntities.ZOMBIE, level), pos, starred);
            case SKELETON -> spawnDummy(level, new SimSkeleton(McEntities.SKELETON, level), pos, starred);
            case MINIBOSS -> spawnMiniboss(level, pos, boss, starred);
            case FEL -> spawnFel(level, pos, starred);
            // A bat is a SECRET on Hypixel, not a mob worth points - which is exactly why it is here: a
            // 300 run needs every secret, and a player who cannot tell a bat secret from a chest secret
            // cannot tell why their count is short.
            case BAT -> spawnBat(level, pos);
        }
    }

    /**
     * Places a miniboss: a {@link SimMiniboss} with its own fight, a miniboss-sized pool and a name stand.
     * Server thread only.
     */
    private static void spawnMiniboss(ServerLevel level, BlockPos pos, SimMiniboss.Name name, boolean starred) {
        Mob boss = SimMiniboss.create(level, name);
        SimHealth.Pool pool = spawnDummy(level, boss, pos, starred, SimHealth.Role.MINIBOSS, name.text(),
                MINIBOSS_HOME_RADIUS);
        if (pool != null) {
            SimMiniboss.tunePool(boss, pool);
        }
    }

    /**
     * One of the Watcher's blood mobs ({@link SimWatcher}): a chasing zombie with a starred mob's pool for the floor
     * and a name stand, but NOT starred - blood mobs are the Watcher's, not a room's, so they never count toward a
     * room clear. Wears a blood-mob head when one is given. Server thread only.
     *
     * @return the mob's id, or null when the level refused it
     */
    static UUID spawnBloodMob(ServerLevel level, BlockPos pos, String name, ItemStack head) {
        SimZombie mob = new SimZombie(McEntities.ZOMBIE, level);
        if (head != null && !head.isEmpty()) {
            mob.setItemSlot(EquipmentSlot.HEAD, head);
            mob.setDropChance(EquipmentSlot.HEAD, 0f);
        }
        SimHealth.Pool pool = spawnDummy(level, mob, pos, false, SimHealth.Role.STARRED, name, HOME_RADIUS);
        return pool == null ? null : mob.getUUID();
    }

    /** A zombie or skeleton, regular or starred, at this floor's health. Server thread only. */
    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred) {
        spawnDummy(level, mob, pos, starred, starred ? SimHealth.Role.STARRED : SimHealth.Role.REGULAR,
                mob.getType().getDescription().getString(), HOME_RADIUS);
    }

    /**
     * Places a fighting sim mob with a health pool and a name stand. Server thread only - called from
     * {@link #spawn}.
     *
     * <p>{@code setPersistenceRequired()} is the normal vanilla answer to "don't despawn", but it is not enough
     * on its own: {@code Mob.checkDespawn()} discards any hostile mob whose {@code EntityType.isAllowedInPeaceful()}
     * is false the moment the level's difficulty reads PEACEFUL, BEFORE it ever looks at persistence (confirmed
     * with {@code javap -c} against {@code Mob.class}). {@link SimZombie}, {@link SimSkeleton} and the miniboss
     * override {@code checkDespawn()} to do nothing at all.
     *
     * <p>The mob's own vanilla health is left at its type's default: {@link SimHealth} keeps the real number and
     * refuses every vanilla hit but the last, so the vanilla bar never moves.
     *
     * @return the mob's pool, or null if the level refused the mob
     */
    private static SimHealth.Pool spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred,
                                             SimHealth.Role role, String name, int homeRadius) {
        String floor = SimState.floorLabel();
        prepareFighter(mob, SimHealth.meleeDamage(floor, role));
        mob.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (mob instanceof net.minecraft.world.entity.PathfinderMob pm) {
            pm.setHomeTo(pos, homeRadius);
        }
        if (!level.addFreshEntity(mob)) {
            return null;
        }
        SPAWNED.add(mob.getUUID());
        pinToRoom(mob);
        ArmorStand tag = attachTag(level, mob);
        SimHealth.Pool pool = SimHealth.register(mob, SimHealth.maxHealth(floor, role), name, starred, tag);
        if (starred) {
            STARRED.add(mob.getUUID());
            recordStarred(mob.getUUID(), mob.getX(), mob.getY(), mob.getZ());
            STAR_TAGS.put(mob.getUUID(), tag.getUUID());
        }
        return pool;
    }

    /**
     * The parts of making a sim mob fight that every kind shares: persistent, hitting for the floor's damage,
     * no zombie reinforcements, and a bow in a skeleton's hand (vanilla only hands one out in
     * {@code finalizeSpawn}, which a mob built in code never goes through, and without one a skeleton just
     * walks up and punches).
     */
    private static void prepareFighter(Mob mob, float damage) {
        mob.setPersistenceRequired();
        var attack = mob.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(damage);
        }
        var range = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (range != null) {
            range.setBaseValue(24);
        }
        var reinforcements = mob.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        if (reinforcements != null) {
            reinforcements.setBaseValue(0);
        }
        if (mob instanceof SimSkeleton skeleton) {
            skeleton.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            skeleton.reassessWeaponGoal();
        }
    }

    /**
     * Gives a sim mob the name-tag armour stand Hypixel gives it, as an invisible marker above the mob that
     * {@link SimHealth} keeps over it and writes the live health into.
     *
     * <p>Hypixel puts a mob's name on a separate invisible armour stand rather than on the mob, and every mod -
     * this one's Starred Mobs and the room-clear counters included - resolves a "✯" stand to the mob beneath it.
     * So rather than teaching those features about the sim, the sim produces what they already read. The shape
     * is the one killer560's Map Logger logged off the live server:
     *
     * <pre>
     * " ✯ Frozen Adventurer 6.6M❤"
     * " ✯ Lost Adventurer 337.5k❤"
     * </pre>
     *
     * a LEADING space, the star, the name, then the health run straight into the heart with no space.
     * Non-starred mobs get the same stand without the star.
     */
    private static ArmorStand attachTag(ServerLevel level, net.minecraft.world.entity.LivingEntity mob) {
        ArmorStand tag = new ArmorStand(level, mob.getX(), mob.getY() + mob.getBbHeight() + 0.1, mob.getZ());
        tag.setInvisible(true);
        tag.setNoGravity(true);
        tag.setNoBasePlate(true);
        tag.setPermanentlyInvulnerable(true);
        // A marker, so the tag has no hitbox to catch hits meant for the mob (the same fix as the blaze labels).
        byte flags = tag.getEntityData().get(ArmorStand.DATA_CLIENT_FLAGS);
        tag.getEntityData().set(ArmorStand.DATA_CLIENT_FLAGS, (byte) (flags | ArmorStand.CLIENT_FLAG_MARKER));
        tag.setCustomNameVisible(true);
        level.addFreshEntity(tag);
        SPAWNED.add(tag.getUUID());
        return tag;
    }

    /**
     * A captured room mob waiting to be placed - see {@link #queueRoomMob}.
     *
     * @param marker the stand-in id the room-clear bookkeeping knows a pending STARRED mob by, or null
     */
    private record PendingMob(BlockPos at, Kind kind, boolean starred, UUID marker, SimMiniboss.Name boss) {
    }

    /** Captured room mobs not yet placed. Server thread writes and reads; cleared with everything else. */
    private static final List<PendingMob> PENDING = new CopyOnWriteArrayList<>();

    /**
     * How close (horizontally) a player has to come before a room's captured mobs are placed: a little under a
     * room tile, so they are standing there by the time you are through the door.
     */
    private static final double ROOM_MOB_PLACE_RADIUS = 28.0;

    /** How far below its recorded position a captured mob is looked for a floor to stand on. */
    private static final int FLOOR_SEARCH_DOWN = 24;

    /**
     * Queues one of a room's captured mobs ({@code RoomLibrary.Room.mobSpawns}, rotated into the world by
     * {@code SimBuilder}). Server thread.
     *
     * <p>Placed LATER, when a player comes near ({@link #spawnPendingRoomMobs}), not now, for two reasons. The
     * build runs while the player waits far away in the holding area, so every room's chunks unload behind the
     * build: a mob added then would be written to disk as a plain vanilla zombie (the sim's subclasses are not
     * registered types) and come back without its pool rules, and {@code SimBuilder.clearFloorMobs} only ever sees
     * loaded entities, so the next rebuild could not remove it. And the recorded positions are the name STANDS',
     * which float above the mob - dropping them to the floor ({@link #toFloor}) needs the room's blocks, which are
     * only certainly there once its chunk is live again.
     *
     * <p>A pending STARRED mob is already counted: it stands in {@link #STARRED_AT} under a marker id, so its room is
     * not "every starred mob dead" before you have even walked in, and in the starred snapshot like a dormant Fel.
     */
    static void queueRoomMob(BlockPos at, Kind kind, boolean starred) {
        queueRoomMob(at, kind, starred, null);
    }

    /**
     * {@link #queueRoomMob(BlockPos, Kind, boolean)} for a room's miniboss as well: {@code boss} names which one
     * a {@link Kind#MINIBOSS} is (null means the default). Used by {@link SimRoomMobs} for the rooms the room
     * database marks as miniboss rooms.
     */
    static void queueRoomMob(BlockPos at, Kind kind, boolean starred, SimMiniboss.Name boss) {
        UUID marker = null;
        if (starred) {
            marker = UUID.randomUUID();
            recordStarred(marker, at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }
        PENDING.add(new PendingMob(at.immutable(), kind, starred, marker, boss));
    }

    /** How many captured room mobs are still waiting to be placed - for tests and the build log. */
    public static int pendingRoomMobs() {
        return PENDING.size();
    }

    /** Places the queued room mobs a player has come close to. Server thread, every tick. */
    private static void spawnPendingRoomMobs(ServerLevel level) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        for (PendingMob mob : PENDING) {
            boolean near = false;
            for (ServerPlayer player : players) {
                double dx = player.getX() - (mob.at().getX() + 0.5);
                double dz = player.getZ() - (mob.at().getZ() + 0.5);
                if (dx * dx + dz * dz <= ROOM_MOB_PLACE_RADIUS * ROOM_MOB_PLACE_RADIUS) {
                    near = true;
                    break;
                }
            }
            if (!near || !level.isPositionEntityTicking(mob.at())) {
                continue;
            }
            PENDING.remove(mob);
            BlockPos at = toFloor(level, mob.at());
            handingOver = mob.marker();
            switch (mob.kind()) {
                case SKELETON -> spawnDummy(level, new SimSkeleton(McEntities.SKELETON, level), at, mob.starred());
                case FEL -> spawnFel(level, at, mob.starred());
                case MINIBOSS -> spawnMiniboss(level, at, mob.boss() == null ? DEFAULT_MINIBOSS : mob.boss(),
                        mob.starred());
                default -> spawnDummy(level, new SimZombie(McEntities.ZOMBIE, level), at, mob.starred());
            }
            handingOver = null;
            if (mob.marker() != null) {
                // The real mob was recorded above; the stand-in goes, the same hand-over a waking Fel does.
                STARRED_DEAD.add(mob.marker());
            }
        }
    }

    /**
     * The floor under a captured position: the first spot at or below it (within {@link #FLOOR_SEARCH_DOWN})
     * with two blocks of room and something solid underneath, else a little above it, else the position itself.
     */
    private static BlockPos toFloor(ServerLevel level, BlockPos at) {
        for (int dy = 0; dy <= FLOOR_SEARCH_DOWN; dy++) {
            BlockPos p = at.below(dy);
            if (standable(level, p)) {
                return p;
            }
        }
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos p = at.above(dy);
            if (standable(level, p)) {
                return p;
            }
        }
        return at;
    }

    private static boolean standable(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
    }

    /** Star tag per starred mob, so the tag can be removed when the mob is. */
    private static final java.util.Map<UUID, UUID> STAR_TAGS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A bat secret. Counted toward secrets when it dies, the way Hypixel counts it.
     *
     * <p>It does not move: a bat that flies away is a secret whose difficulty depends on where it drifted
     * rather than on the route. Unlike the room mobs, it keeps its one health.
     */
    private static void spawnBat(ServerLevel level, BlockPos pos) {
        var bat = new net.minecraft.world.entity.ambient.Bat(
                McEntities.BAT, level);
        secretBatHealth(bat);
        bat.setHealth((float) ONE_HP);
        bat.setNoAi(true);
        bat.setNoGravity(true);
        bat.setPersistenceRequired();
        bat.setPos(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        level.addFreshEntity(bat);
        SPAWNED.add(bat.getUUID());
        BATS.put(bat.getUUID(), pos.above().immutable());
    }

    /** Crypt undead alive in the world, id to whether it is the prince that scores. */
    private static final java.util.Map<UUID, Boolean> CRYPT_MOBS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A crypt's undead (or the prince's), spawned when its crypt is blown. Its crypt point is counted when it DIES,
     * as on Hypixel, where "Crypts: N" in the tab list rises on the kill and the prince's kill says
     * "A Prince falls. +1 Bonus Score" in chat. Until 2026-10-05 the sim scored the crypt when the wall broke, which
     * left a client feature that watches for the kill (Auto Routes' crypt node) nothing to see.
     */
    public static void spawnCrypt(Minecraft client, BlockPos pos, boolean scoringPrince) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            SimZombie undead = new SimZombie(McEntities.ZOMBIE, level);
            // killer560 (2026-10-06): "have crypt mobs spawn with the word crypt above them". No star, so nothing
            // that looks for starred mobs counts it.
            // The name goes on the zombie itself, with its health after it (SimHealth writes it).
            String name = scoringPrince ? "Crypt Prince" : "Crypt Undead";
            String floor = SimState.floorLabel();
            prepareFighter(undead, SimHealth.meleeDamage(floor, SimHealth.Role.REGULAR));
            undead.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            undead.setHomeTo(pos, HOME_RADIUS);
            if (!level.addFreshEntity(undead)) {
                return;
            }
            SPAWNED.add(undead.getUUID());
            SimHealth.register(undead, SimHealth.maxHealth(floor, SimHealth.Role.REGULAR), name, false, null);
            CRYPT_MOBS.put(undead.getUUID(), scoringPrince);
        });
    }

    private static void countDeadCrypts(ServerLevel level) {
        if (CRYPT_MOBS.isEmpty()) {
            return;
        }
        for (var it = CRYPT_MOBS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Entity mob = level.getEntity(e.getKey());
            if (mob != null && mob.isAlive()) {
                continue;
            }
            it.remove();
            SimScore.cryptBlown();
            if (e.getValue()) {
                SimScore.princeKilled();
                for (ServerPlayer p : level.players()) {
                    p.sendSystemMessage(net.minecraft.network.chat.Component.literal("A Prince falls. +1 Bonus Score"));
                }
            }
        }
    }

    /**
     * The mimic: a baby zombie where its trapped chest stood, as on Hypixel (the score calculator and Auto Routes'
     * Kill Mimic know it as a baby zombie dying). Starred, as the sim's mimic always was, with a starred mob's
     * health for the floor. Server thread. @return its id
     */
    static java.util.UUID spawnMimic(ServerLevel level, BlockPos chest) {
        SimZombie mimic = new SimZombie(McEntities.ZOMBIE, level);
        mimic.setBaby(true);
        spawnDummy(level, mimic, chest, true, SimHealth.Role.STARRED, "Mimic", HOME_RADIUS);
        return mimic.getUUID();
    }

    /**
     * Gives a sim bat secret the MAX health a Hypixel secret bat carries (Skyblock health, 100 on the lower floors),
     * which is what the client's own secret-bat test reads ({@code AwaitEvents.isSecretBat}, QUOI's 100/200/400/800):
     * with vanilla's 6, or the 1 this used to set, nothing on the client could tell a sim bat secret from any bat. The
     * CURRENT health is left as it is, so a bat still dies to the same hit as before.
     */
    static void secretBatHealth(net.minecraft.world.entity.ambient.Bat bat) {
        bat.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0);
    }

    /**
     * Counts bats that have died since the last tick, as SECRETS.
     *
     * <p>Polled rather than hooked on a death event: the sim owns these entities and knows exactly which ones
     * are its bats, so checking the handful it spawned is cheaper and more certain than filtering every death
     * in the world.
     */
    private static void countDeadBats(ServerLevel level) {
        if (BATS.isEmpty()) {
            return;
        }
        for (var it = BATS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Entity e = level.getEntity(entry.getKey());
            // Missing is only dead where entities tick: a bat whose chunk unloaded behind him used to count as a
            // found secret (the same rule as the starred mobs, refreshStarred).
            if (e == null && !level.isPositionEntityTicking(entry.getValue())) {
                continue;
            }
            if (e == null || !e.isAlive()) {
                it.remove();
                com.epic60869.sky2m.sim.roomsim.SimScore.batKilled();
                // Bats are secrets, and a secret can hold a blessing (level I or II), as on Hypixel.
                ServerPlayer finder = nearestPlayer(level, entry.getValue());
                if (finder != null) {
                    SimBlessings.itemSecret(level.getServer(), finder.getGameProfile().name(), entry.getValue());
                }
            }
        }
    }

    /** Whether no starred mob of the room that spawned at {@code at} is left (alive, queued or a dormant Fel). */
    private static boolean lastStarredOfRoom(double[] at) {
        int room = SimState.roomIdAt((int) Math.floor(at[0]), (int) Math.floor(at[2]));
        if (room < 0) {
            return false;
        }
        for (double[] other : starredPositions().values()) {
            if (SimState.roomIdAt((int) Math.floor(other[0]), (int) Math.floor(other[2])) == room) {
                return false;
            }
        }
        return true;
    }

    /** The player nearest a spot, or null with nobody in the world. */
    private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos at) {
        ServerPlayer best = null;
        for (ServerPlayer p : level.players()) {
            if (best == null || p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at)) < best.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(at))) {
                best = p;
            }
        }
        return best;
    }

    /** Bats spawned by the sim and where, so their death can be counted as a secret rather than as a kill. */
    private static final java.util.Map<UUID, BlockPos> BATS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Places a dormant Fel: an invisible, no-base-plate armour stand wearing a skull, so only the skull shows -
     * an invisible armour stand still renders its equipped items, which is the whole trick. It is invulnerable
     * while dormant because a Fel is not a fightable mob yet on Hypixel either; it only becomes one once it
     * wakes, in {@link #wake}.
     */
    private static void spawnFel(ServerLevel level, BlockPos pos, boolean starred) {
        ArmorStand skull = new ArmorStand(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        skull.setInvisible(true);
        skull.setNoBasePlate(true);
        skull.setShowArms(false);
        skull.setPermanentlyInvulnerable(true);
        skull.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.SKELETON_SKULL));
        level.addFreshEntity(skull);
        FELS.add(new FelMarker(skull, starred));
        if (starred) {
            recordStarred(skull.getUUID(), skull.getX(), skull.getY(), skull.getZ());
        }
    }

    /** Checks every dormant Fel against every player each tick and wakes the ones close enough. */
    private static void wakeFels(ServerLevel level) {
        if (FELS.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        for (FelMarker fel : FELS) {
            if (fel.woken || fel.skull.isRemoved()) {
                continue;
            }
            for (ServerPlayer player : players) {
                if (player.distanceToSqr(fel.skull) <= FEL_WAKE_RADIUS_SQ) {
                    wake(level, fel);
                    break;
                }
            }
        }
    }

    /**
     * Replaces a dormant Fel with an enderman where it stood.
     *
     * <p><b>Upside down, using vanilla's own rule.</b> killer560's spec is explicit that the woken form is "the
     * upside down Enderman". This needs no renderer and no mixin: {@code LivingEntityRenderer} checks whether an
     * entity is named "Dinnerbone" or "Grumm" and sets {@code isUpsideDown} on the render state, which flips the
     * model (verified in the 26.1.2 bytecode, 2026-09-28). So the enderman is named Grumm and the name tag is
     * hidden - the flip is a property of the NAME, not of the tag being drawn, so hiding it costs nothing.
     *
     * <p>That is worth preferring over a mixin even though a mixin would work: this is a rendering behaviour
     * the game already has, and a mixin on an entity renderer is a thing that breaks quietly on the next
     * Minecraft version.
     */
    private static void wake(ServerLevel level, FelMarker fel) {
        fel.woken = true;
        double x = fel.skull.getX();
        double y = fel.skull.getY();
        double z = fel.skull.getZ();
        fel.skull.discard();

        SimEnderman enderman = new SimEnderman(McEntities.ENDERMAN, level);
        SimHealth.Role role = fel.starred ? SimHealth.Role.STARRED : SimHealth.Role.REGULAR;
        String floor = SimState.floorLabel();
        prepareFighter(enderman, SimHealth.meleeDamage(floor, role));
        // Vanilla flips anything called Grumm or Dinnerbone. The tag itself is hidden: the flip comes from the
        // name being set, not from it being drawn. Its health is on the stand above it instead.
        enderman.setCustomName(net.minecraft.network.chat.Component.literal("Grumm"));
        enderman.setCustomNameVisible(false);
        enderman.setPos(x, y, z);
        enderman.setHomeTo(BlockPos.containing(x, y, z), HOME_RADIUS);
        if (!level.addFreshEntity(enderman)) {
            return;
        }
        SPAWNED.add(enderman.getUUID());
        pinToRoom(enderman);
        ArmorStand tag = attachTag(level, enderman);
        SimHealth.register(enderman, SimHealth.maxHealth(floor, role), "Fel", fel.starred, tag);
        // Woken because a player came close: it goes straight for the nearest one.
        ServerPlayer nearest = null;
        for (ServerPlayer p : level.players()) {
            if (nearest == null || p.distanceToSqr(enderman) < nearest.distanceToSqr(enderman)) {
                nearest = p;
            }
        }
        if (nearest != null && !nearest.isCreative() && !nearest.isSpectator()) {
            enderman.setTarget(nearest);
        }
        if (fel.starred) {
            STARRED.add(enderman.getUUID());
            STAR_TAGS.put(enderman.getUUID(), tag.getUUID());
            // Recorded BEFORE the skull counts as gone below, so the room never reads "all dead" in between.
            recordStarred(enderman.getUUID(), x, y, z);
            SimKeys.handOver(fel.skull.getUUID(), enderman.getUUID());
            STARRED_DEAD.add(fel.skull.getUUID());
        }
    }

    /**
     * Rebuilds {@link #starredSnapshot} from whatever is still alive, and fires the last-death hook on the 0-to-
     * nonzero-to-0 transition. A dormant, un-woken starred Fel counts as alive: on Hypixel it has not died yet
     * either, it just has not been fought yet, and a room where it was never approached must not look like every
     * starred mob is already dead.
     */
    private static void refreshStarred(ServerLevel level) {
        List<StarredEntry> alive = new ArrayList<>();
        for (UUID id : STARRED) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                // Where it SPAWNED, not where it is now: mobs walk and chase, and a starred mob that has followed
                // the player into the doorway still belongs to its own room.
                double[] at = STARRED_AT.get(id);
                alive.add(at != null ? new StarredEntry(id, at[0], at[1], at[2])
                        : new StarredEntry(id, entity.getX(), entity.getY(), entity.getZ()));
            } else if (!STARRED_DEAD.contains(id)) {
                double[] at = STARRED_AT.get(id);
                if (entity != null || (at != null
                        && level.isPositionEntityTicking(BlockPos.containing(at[0], at[1], at[2])))) {
                    STARRED_DEAD.add(id);
                    // A key holder drops its key where it fell (SimKeys).
                    SimKeys.holderDied(level, id, entity != null ? entity.position()
                            : at != null ? new net.minecraft.world.phys.Vec3(at[0], at[1], at[2]) : null, at);
                    if (at != null && lastStarredOfRoom(at)) {
                        // Hypixel's final starred mob of a room can hold a blessing (level V).
                        ServerPlayer finder = nearestPlayer(level, BlockPos.containing(at[0], at[1], at[2]));
                        if (finder != null) {
                            SimBlessings.lastStarredMob(level.getServer(), finder.getGameProfile().name(),
                                    BlockPos.containing(at[0], at[1], at[2]));
                        }
                    }
                    // Hypixel's "star ... heart" name goes with its mob; the sim's stand used to stay up forever,
                    // a starred name with nothing under it.
                    UUID tag = STAR_TAGS.remove(id);
                    Entity stand = tag == null ? null : level.getEntity(tag);
                    if (stand != null) {
                        stand.discard();
                    }
                }
            }
        }
        for (FelMarker fel : FELS) {
            if (fel.starred && !fel.woken && !fel.skull.isRemoved()) {
                alive.add(new StarredEntry(fel.skull.getUUID(), fel.skull.getX(), fel.skull.getY(), fel.skull.getZ()));
            }
        }
        // A captured starred mob not placed yet has not died either - same reasoning as the dormant Fel.
        for (PendingMob mob : PENDING) {
            if (mob.marker() != null) {
                alive.add(new StarredEntry(mob.marker(), mob.at().getX() + 0.5, mob.at().getY(),
                        mob.at().getZ() + 0.5));
            }
        }
        starredSnapshot = List.copyOf(alive);

        if (!alive.isEmpty()) {
            hadStarred = true;
        } else if (hadStarred) {
            hadStarred = false;
            Runnable callback = onLastStarredDeath;
            if (callback != null) {
                callback.run();
            }
        }
    }

    /** How many starred mobs are still alive inside {@code area}. Door/key logic owns what happens at zero. */
    public static int starredAliveIn(AABB area) {
        int count = 0;
        for (StarredEntry entry : starredSnapshot) {
            if (area.contains(entry.x(), entry.y(), entry.z())) {
                count++;
            }
        }
        return count;
    }

    /** Whether any starred mob spawned by this class is still alive, anywhere in the sim. */
    /**
     * How many entities this class has spawned, and how many of those were starred.
     *
     * <p>{@link #anyStarredAlive()} reads a snapshot refreshed on tick, so it says "no" both when nothing was
     * spawned and when the snapshot has not caught up - and a gametest cannot tell those apart. These two are
     * written the moment the entity is added, which is the question a test actually needs answered.
     */
    public static int spawnedCount() {
        return SPAWNED.size();
    }

    public static int starredCount() {
        return STARRED.size();
    }

    /**
     * Every starred mob paired with its name-tag armour stand, as {@code {mobUuid, tagUuid}}.
     *
     * <p>For a test that has to check the pair the way Starred Mobs reads it, WITHOUT enumerating the level:
     * entity enumeration does not work in a gametest client (a vanilla entity added with
     * {@code addFreshEntity} returning true, in a loaded chunk, still never appears in
     * {@code getAllEntities()}), so a scenario has to look each one up by id instead.
     */
    public static java.util.List<UUID[]> starredPairs() {
        java.util.List<UUID[]> out = new java.util.ArrayList<>();
        for (UUID mob : STARRED) {
            UUID tag = STAR_TAGS.get(mob);
            if (tag != null) {
                out.add(new UUID[]{mob, tag});
            }
        }
        return out;
    }

    public static boolean anyStarredAlive() {
        return !starredSnapshot.isEmpty();
    }

    /** Registers the "last starred mob just died" hook. Does not itself drop a key or open a door. */
    public static void setOnLastStarredDeath(Runnable callback) {
        onLastStarredDeath = callback;
    }

    /** Removes every mob and Fel marker this class spawned, for a run restart. Never touches anything else. */
    /**
     * Forgets the tracked mobs without touching the world.
     *
     * <p>For {@code SimBuilder}, which discards every mob in the level itself and then only needs the
     * bookkeeping cleared - calling {@link #clear(Minecraft)} there would try to reach the client from the
     * server thread mid-build.
     *
     * <p>ALL of it, the starred set included. This used to clear SPAWNED but keep STARRED, FELS and the star tags,
     * so a floor built in a world whose previous leave had not reset the sim (SimState already off when the
     * world unloaded) started with the old floor's starred mobs still counted: 89-sim-starred-mobs read
     * "1 spawned, 5 starred" after spawning one PLAIN zombie, the 5 being mimics from 96-ar (2026-10-06 and
     * 2026-10-07). Every mob of the old floor is gone after a build, so none of them can still be starred.
     */
    public static void forget() {
        // A rebuilt floor's rooms must not be cleared by the last floor's dead, and discarded is not killed: a
        // crypt or mimic the build threw away must not score when it goes. forgetAll covers both.
        forgetAll();
    }

    public static void clear(Minecraft client) {
        // The world's entities are only touched while the sim can act; the bookkeeping below is forgotten either
        // way. It used to return here too, and the leave path (SimWorld.onWorldUnloaded, now on the render thread
        // after the level has gone) would then carry this floor's starred set into the next session.
        MinecraftServer server = SimState.canAct(client) ? client.getSingleplayerServer() : null;
        if (server == null) {
            forgetAll();
            return;
        }
        List<UUID> toDiscard = List.copyOf(SPAWNED);
        List<FelMarker> dormant = List.copyOf(FELS);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (UUID id : toDiscard) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
            for (FelMarker fel : dormant) {
                if (!fel.skull.isRemoved()) {
                    fel.skull.discard();
                }
            }
        });
        forgetAll();
    }

    private static void forgetAll() {
        SPAWNED.clear();
        HOME_ROOM.clear();
        LAST_IN_ROOM.clear();
        BATS.clear();
        STARRED_AT.clear();
        STARRED_DEAD.clear();
        CRYPT_MOBS.clear();
        SimMimic.forgetMob();
        STARRED.clear();
        STAR_TAGS.clear();
        FELS.clear();
        PENDING.clear();
        DUMMIES.clear();
        SimHealth.forget();
        starredSnapshot = List.of();
        ROOMS_WITH_STARRED = java.util.Set.of();
        hadStarred = false;
    }

    /**
     * Plain {@link Zombie} minus the peaceful-discard half of {@code checkDespawn()} - see
     * {@link #spawnDummy}. {@code isSunSensitive()} is overridden too, belt-and-suspenders: dungeon rooms have
     * no sky exposure, but the spec asked for it explicitly ("there is no daylight, but persistence matters").
     */

    /**
     * Whether a sim mob should shrug this off.
     *
     * <p>A practice target that dies to the ceiling before he reaches it is worse than no target at all.
     * Measured 2026-09-29: a zombie placed in a two-block-high room suffocated within a tick, because a zombie
     * is 1.95 blocks tall and a player is 1.8, so its head was in the ceiling - and now that mobs walk, falls
     * and water are in play too. {@link SimHealth} refuses the same damage for every pooled mob; this is the
     * vanilla-side half, so the hurt never even starts.
     */
    private static boolean environmental(net.minecraft.world.damagesource.DamageSource source) {
        return source.getEntity() == null && source.getDirectEntity() == null;
    }

    /**
     * Plain {@link Zombie} that never despawns ({@link #spawnDummy}), never burns and never drowns into a
     * Drowned. Burning in daylight is decided by the {@code BURN_IN_DAYLIGHT} entity tag in {@code Mob.aiStep}
     * (26.2 bytecode), not by {@code isSunSensitive}, so the fire is refused where it is lit instead.
     */
    private static final class SimZombie extends Zombie {
        SimZombie(EntityType<? extends Zombie> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.damagesource.DamageSource source) {
            return environmental(source) || super.isInvulnerableTo(level, source);
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
        public void igniteForTicks(int ticks) {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }
    }

    /** Plain {@link Skeleton} that never despawns and never burns - see {@link SimZombie}. Holds a bow
     *  ({@link #prepareFighter}) and shoots with vanilla's own bow goal. */
    private static final class SimSkeleton extends Skeleton {
        SimSkeleton(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        /**
         * Its arrows hit for the floor too. Vanilla arrow damage is the arrow's base damage times its speed
         * (about 1.6 from a skeleton's bow), so half the floor's melee damage lands about where a zombie's
         * swing does.
         */
        @Override
        protected net.minecraft.world.entity.projectile.arrow.AbstractArrow getArrow(ItemStack arrow, float power,
                                                                                    ItemStack weapon) {
            var shot = super.getArrow(arrow, power, weapon);
            shot.setBaseDamage(getAttributeValue(Attributes.ATTACK_DAMAGE) / 2.0);
            return shot;
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.world.damagesource.DamageSource source) {
            return environmental(source) || super.isInvulnerableTo(level, source);
        }

        @Override
        public void igniteForTicks(int ticks) {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }
    }

    /**
     * A woken Fel: an {@link Enderman} that fights like a melee mob instead of like an enderman.
     *
     * <p>Vanilla's enderman only turns on you when stared at, wanders off with blocks, and teleports away from
     * water, daylight and every projectile - so the Terminator could never hit one. A Fel on Hypixel just comes
     * at you, so the goals are replaced with a plain melee set that targets the nearest player, teleporting is
     * switched off, and a player's arrow is handed on as that player's attack (vanilla's
     * {@code Enderman.hurtServer} dodges anything tagged as a projectile before the damage events ever run).
     */
    private static final class SimEnderman extends Enderman {
        SimEnderman(EntityType<? extends Enderman> type, Level level) {
            super(type, level);
        }

        @Override
        protected void registerGoals() {
            goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(this));
            goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(this, 1.0, false));
            goalSelector.addGoal(7, new net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal(this, 1.0, 0f));
            goalSelector.addGoal(8, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(this,
                    net.minecraft.world.entity.player.Player.class, 8f));
            targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                    this, net.minecraft.world.entity.player.Player.class, true));
        }

        @Override
        protected boolean teleport() {
            return false;
        }

        @Override
        public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source,
                                  float amount) {
            if (source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
                    && source.getEntity() instanceof net.minecraft.world.entity.player.Player shooter) {
                return super.hurtServer(level, level.damageSources().playerAttack(shooter), amount);
            }
            return super.hurtServer(level, source, amount);
        }

        @Override
        public boolean isInvulnerableTo(ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
            return environmental(source) || super.isInvulnerableTo(level, source);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        public void igniteForTicks(int ticks) {
        }

        @Override
        public boolean fireImmune() {
            return true;
        }
    }
    // ------------------------------------------------------------------ training dummy

    /** Training dummies standing, so {@code /simdummy clear} can take them away. */
    private static final List<UUID> DUMMIES = new CopyOnWriteArrayList<>();

    /** A dummy's health when {@code /simdummy} is given none: 10M, a few M7 mobs' worth. */
    private static final double DEFAULT_DUMMY_HEALTH = 10_000_000;

    /**
     * Places a training dummy: a zombie that never moves, fights or dies, with a health pool that shows each hit's
     * damage, its DPS over the last five seconds, and fills back up when emptied (saying how long that took) -
     * see {@link SimHealth}. {@code defense} reduces each hit by Hypixel's {@code 100 / (100 + defense)}, so
     * damage against an armoured target can be tried too.
     */
    public static void spawnTrainingDummy(Minecraft client, BlockPos pos, double health, double defense) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            SimZombie dummy = new SimZombie(McEntities.ZOMBIE, level);
            dummy.setPersistenceRequired();
            dummy.setNoAi(true);
            dummy.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (!level.addFreshEntity(dummy)) {
                return;
            }
            SPAWNED.add(dummy.getUUID());
            DUMMIES.add(dummy.getUUID());
            ArmorStand tag = attachTag(level, dummy);
            SimHealth.Pool pool = SimHealth.register(dummy, health, "Training Dummy", false, tag);
            pool.dummy = true;
            pool.taken = 100.0 / (100.0 + Math.max(0, defense));
            pool.dirty = true;
        });
    }

    private static void clearDummies(Minecraft client) {
        MinecraftServer server = SimState.canAct(client) ? client.getSingleplayerServer() : null;
        List<UUID> doomed = List.copyOf(DUMMIES);
        DUMMIES.clear();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (UUID id : doomed) {
                Entity e = level.getEntity(id);
                if (e != null) {
                    e.discard();
                }
            }
        });
    }

    /**
     * {@code /simdummy [health] [defense]} - a training dummy three blocks in front of him; {@code /simdummy clear}
     * removes them. Health takes k/m/b ("50m"). Sim-only, like {@code /summon}.
     */
    private static void registerDummy(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2
                    .FabricClientCommandSource> dispatcher) {
        dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("simdummy")
                .requires(src -> SimState.canAct(Minecraft.getInstance()))
                .executes(ctx -> placeDummy(DEFAULT_DUMMY_HEALTH, 0))
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("clear").executes(ctx -> {
                    clearDummies(Minecraft.getInstance());
                    com.epic60869.sky2m.sim.util.ModChat.send("Sim", com.epic60869.sky2m.sim.util.ModChat.text("Training dummies removed"));
                    return 1;
                }))
                .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("health", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes(ctx -> placeDummy(parseAmount(
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "health")), 0))
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("defense",
                                        com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 1_000_000))
                                .executes(ctx -> placeDummy(parseAmount(
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx,
                                                        "health")),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,
                                                "defense"))))));
    }

    /** "10m" is 10,000,000; also "2.5k", "1b", "750000". Unreadable or not positive gives -1. */
    public static double parseAmount(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String t = text.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "").replace("_", "");
        double mult = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1);
        }
        try {
            double v = Double.parseDouble(t) * mult;
            return v > 0 && Double.isFinite(v) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int placeDummy(double health, int defense) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || client.player == null) {
            com.epic60869.sky2m.sim.util.ModChat.send("Sim", com.epic60869.sky2m.sim.util.ModChat.text("Training dummies only work inside the sim."));
            return 1;
        }
        if (health <= 0) {
            com.epic60869.sky2m.sim.util.ModChat.send("Sim", com.epic60869.sky2m.sim.util.ModChat.text("Health must be a positive number, like 10m or 500k."));
            return 1;
        }
        net.minecraft.world.phys.Vec3 look = client.player.getLookAngle();
        net.minecraft.world.phys.Vec3 flat = new net.minecraft.world.phys.Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1e-4 ? new net.minecraft.world.phys.Vec3(0, 0, 1) : flat.normalize();
        BlockPos at = BlockPos.containing(client.player.position().add(flat.scale(3)));
        spawnTrainingDummy(client, at, health, defense);
        com.epic60869.sky2m.sim.util.ModChat.send("Sim", com.epic60869.sky2m.sim.util.ModChat.text("Training Dummy: "),
                com.epic60869.sky2m.sim.util.ModChat.value(SimHealth.shortHealth(health) + "❤" + (defense > 0 ? ", " + defense + " defense" : "")),
                com.epic60869.sky2m.sim.util.ModChat.dim("  (/simdummy clear to remove)"));
        return 1;
    }

    /**
     * {@code /summon <kind>} - one mob of that kind, standing on him.
     *
     * <p>Starred, so Starred Mobs and the clear's own counters see it exactly as they would a real one.
     * Spawning at his own feet is deliberate: he asked for "ontop of me", and his own block is guaranteed to be
     * air, which a block two over is not. {@code /summon miniboss <name>} picks the miniboss
     * ({@code /summon miniboss} alone is a Lost Adventurer).
     */
    private static void registerSummon(
            com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2
                    .FabricClientCommandSource> dispatcher) {
        var root = net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("summon")
                .requires(src -> SimState.canAct(Minecraft.getInstance()));
        for (Kind kind : Kind.values()) {
            final Kind chosen = kind;
            var node = net.fabricmc.fabric.api.client.command.v2.ClientCommands
                    .literal(kind.name().toLowerCase(java.util.Locale.ROOT))
                    .executes(ctx -> summonOne(chosen));
            if (kind == Kind.MINIBOSS) {
                for (SimMiniboss.Name name : SimMiniboss.Name.values()) {
                    node = node.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands
                            .literal(name.name().toLowerCase(java.util.Locale.ROOT))
                            .executes(ctx -> summonMiniboss(name)));
                }
            }
            root = root.then(node);
        }
        dispatcher.register(root);
    }

    private static int summonMiniboss(SimMiniboss.Name name) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || client.player == null) {
            com.epic60869.sky2m.sim.util.ModChat.send("Sim",
                    com.epic60869.sky2m.sim.util.ModChat.text("Summoning only works inside the sim."));
            return 1;
        }
        if (!SimParty.sendAction("staff_summon", "miniboss " + name.name())) {
            spawnMiniboss(client, client.player.blockPosition(), name, true);
        }
        com.epic60869.sky2m.sim.util.ModChat.send("Sim",
                com.epic60869.sky2m.sim.util.ModChat.text("Summoned "),
                com.epic60869.sky2m.sim.util.ModChat.value(name.text()));
        return 1;
    }

    private static int summonOne(Kind kind) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || client.player == null) {
            com.epic60869.sky2m.sim.util.ModChat.send("Sim",
                    com.epic60869.sky2m.sim.util.ModChat.text("Summoning only works inside the sim."));
            return 1;
        }
        net.minecraft.core.BlockPos at = client.player.blockPosition();
        if (!SimParty.sendAction("staff_summon", kind.name())) {
            spawnStarred(client, at, kind);
        }
        com.epic60869.sky2m.sim.util.ModChat.send("Sim",
                com.epic60869.sky2m.sim.util.ModChat.text("Summoned "),
                com.epic60869.sky2m.sim.util.ModChat.value(kind.name().toLowerCase(java.util.Locale.ROOT)));
        return 1;
    }

}
