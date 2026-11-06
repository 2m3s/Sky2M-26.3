/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.livemap.DungeonLayout;
import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The sim's dungeon keys, shaped like Hypixel's (hypixelskyblock.minecraft.wiki, Wither Key / Blood Key, 2026-10-06): a
 * dropped key is a named, invisible armour stand ("Wither Key" / "Blood Key" - what Door Highlight's key box and its
 * "spawned" title look for) wearing the key's head ({@link #keyHead}: Hypixel's skins, a black orb and a red one),
 * it is picked up by being near it, and the key then belongs to the whole team ("usable by any person within the
 * dungeon, regardless of whether or not they are the player who obtained it"). A pickup says
 * {@code "<name> has obtained Wither Key!"} in chat to everyone, as Hypixel does, followed by Hypixel's
 * {@code "RIGHT CLICK on a WITHER door to open it. This key can only be used to open 1 door!"}; a wither door opened
 * with the team's key says {@code "<name> opened a WITHER door!"} ({@link SimDoors}).
 *
 * <p><b>Who carries them.</b> The sim used to drop no keys at all: on 2026-10-04 killer560 had the last-starred-death
 * Wither Key drop removed ("we have nothing that should be dropping it right now"), and the blood door was made to
 * open on a plain click. Now a generated floor builds Hypixel's wither doors on the Entrance-to-Blood path
 * ({@link SimWitherDoors#path}) and {@link #assignForFloor} gives each door's key to a room: one Wither Key per wither
 * door and the Blood Key for the blood door, each to the room on the path right BEFORE that door (or, when that room
 * has no starred mobs, the nearest one back towards the Entrance that does). The key drops where the room's LAST
 * starred mob dies ({@link #holderDied}) - the user's rule (2026-11-02): "if the next room is a wither door, in the
 * previous room once all star mobs are dead the key drops". A door whose rooms have no starred mob at all gets its
 * key lying on the floor of the room in front of it instead, as Hypixel's floors sometimes have.
 *
 * <p><b>Practice override</b>: {@code /simkeys free} lets every wither and blood door open without a key;
 * {@code /simkeys give wither|blood} hands the team a key. A floor with no key planned (a single room, a map with no
 * path to Blood) keeps its blood door opening on a click, as before.
 *
 * <p>The pickup range is {@link #pickupRange}.
 */
public final class SimKeys {

    /**
     * Blocks from the player's feet to the stand: Hypixel's range as the mod models it (the 0.27.2 +5 is sourced, the
     * base 1 block is vanilla's item reach and unverified for keys; the Magnetic Talisman does not apply to keys,
     * confirmed in game). Was a flat 3-block guess.
     */
    public static double pickupRange() {
        return 1.0 + 5.0; // vanilla reach plus Hypixel's +5
    }

    /** Dropped keys not yet picked up: the stand's UUID -> blood key? */
    private static final Map<UUID, Boolean> DROPPED = new ConcurrentHashMap<>();
    /** Key holders: a starred mob's UUID (or its pending stand-in's) -> carries the Blood Key? */
    private static final Map<UUID, Boolean> HOLDERS = new ConcurrentHashMap<>();
    /** Keys waiting on a room: room (name-table index) -> its keys (blood key?), dropped when it has no starred mob left. */
    private static final Map<Integer, List<Boolean>> ROOM_KEYS = new ConcurrentHashMap<>();
    /** The cells' rooms of the floor the keys were planned for. */
    private static volatile int[] keyRoomCells = new int[0];
    /** Stand-in id -> the mob that replaced it (a placed room mob, a woken Fel), so a late assignment still lands. */
    private static final Map<UUID, UUID> HANDED = new ConcurrentHashMap<>();
    private static volatile int witherKeys;
    private static volatile boolean bloodKey;
    private static volatile boolean bloodKeyPlanned;
    private static volatile int witherKeysPlanned;
    private static volatile boolean free;
    private static volatile String lastPickup;

    private SimKeys() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimKeys::tick);
        // A right click on a key's stand would take its head off (vanilla armour-stand swap). Refused on the server
        // only, so the client sends exactly what it would send on Hypixel.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                !level.isClientSide() && entity != null && DROPPED.containsKey(entity.getUUID())
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("simkeys")
                        .then(ClientCommands.literal("free").executes(ctx -> {
                            free = !free;
                            ModChat.send("Sim", ModChat.text("Doors without keys: "),
                                    ModChat.value(free ? "ON (practice)" : "OFF (Hypixel)"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("give")
                                .then(ClientCommands.literal("wither").executes(ctx -> {
                                    give(false);
                                    return 1;
                                }))
                                .then(ClientCommands.literal("blood").executes(ctx -> {
                                    give(true);
                                    return 1;
                                })))
                        .executes(ctx -> {
                            ModChat.send("Sim", ModChat.text("Keys: "), ModChat.value(witherKeys + " wither"),
                                    ModChat.text(", blood "), ModChat.value(bloodKey ? "yes" : "no"),
                                    ModChat.text(" | waiting on a room's starred mobs: "),
                                    ModChat.value(String.valueOf(holderCount())),
                                    ModChat.text(", on the ground: "), ModChat.value(String.valueOf(DROPPED.size())),
                                    ModChat.text(" | doors without keys: "), ModChat.value(free ? "on" : "off"));
                            ModChat.send("Sim", ModChat.dim("/simkeys free | give wither | give blood"));
                            return 1;
                        })));
    }

    private static void give(boolean blood) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || client.player == null) {
            ModChat.send("Sim", ModChat.text("Not in the sim."));
            return;
        }
        pickedUp(client.getSingleplayerServer(), client.player.getGameProfile().name(), blood);
    }

    // ------------------------------------------------------------------ holders

    /**
     * Hangs every key the floor needs on a starred mob, for a floor that was just built. Server thread, after the
     * rooms' mobs are queued ({@link SimMobs#starredPositions}).
     *
     * @param doorCells the floor's doors as BUILT (the path's wither doors already {@code DOOR_WITHER})
     */
    static void assignForFloor(ServerLevel level, int[] roomCells, int[] doorCells, String[] names, String mapCode) {
        HOLDERS.clear();
        ROOM_KEYS.clear();
        keyRoomCells = roomCells == null ? new int[0] : roomCells.clone();
        witherKeysPlanned = 0;
        bloodKeyPlanned = false;
        List<int[]> path = SimWitherDoors.path(roomCells, doorCells, names);
        if (path.isEmpty()) {
            return;
        }
        // The starred mobs by the room (name-table index) they stand in, each list in a fixed order.
        Map<Integer, List<Map.Entry<UUID, double[]>>> byRoom = new java.util.HashMap<>();
        for (Map.Entry<UUID, double[]> e : SimMobs.starredPositions().entrySet()) {
            int cell = cellOf(e.getValue()[0], e.getValue()[2]);
            int room = cell >= 0 && cell < roomCells.length ? roomCells[cell] : -1;
            if (room >= 0) {
                byRoom.computeIfAbsent(room, k -> new ArrayList<>()).add(e);
            }
        }
        Comparator<Map.Entry<UUID, double[]>> order = Comparator
                .comparingDouble((Map.Entry<UUID, double[]> e) -> e.getValue()[0])
                .thenComparingDouble(e -> e.getValue()[2])
                .thenComparingDouble(e -> e.getValue()[1]);
        for (List<Map.Entry<UUID, double[]>> list : byRoom.values()) {
            list.sort(order);
        }
        int assigned = 0;
        int onFloor = 0;
        for (int i = 0; i < path.size(); i++) {
            int door = path.get(i)[1];
            int type = doorCells[door];
            boolean blood = type == DungeonLayout.DOOR_BLOOD;
            if (!blood && type != DungeonLayout.DOOR_WITHER) {
                continue;
            }
            if (blood) {
                bloodKeyPlanned = true;
            } else {
                witherKeysPlanned++;
            }
            // The room right in front of the door first, then back towards the Entrance: the first one with starred
            // mobs. Its key drops when the last of them dies (holderDied), not off one chosen mob - killing every
            // starred mob in the room before a wither door is what gets you through it.
            int keyRoom = -1;
            for (int back = i; back >= 0 && keyRoom < 0; back--) {
                List<Map.Entry<UUID, double[]>> list = byRoom.get(path.get(back)[0]);
                if (list != null && !list.isEmpty()) {
                    keyRoom = path.get(back)[0];
                }
            }
            if (keyRoom >= 0) {
                ROOM_KEYS.computeIfAbsent(keyRoom, k -> new CopyOnWriteArrayList<>()).add(blood);
                assigned++;
            } else {
                // No starred mob on the way: the key lies in the room in front of the door.
                // Placed when a player comes near, like a room's mobs (SimMobs.queueRoomMob): the build runs with
                // every room's chunks unloading behind it, and a stand added now could outlive the floor on disk.
                BlockPos at = floorIn(level, roomCells, path.get(i)[0]);
                if (at != null) {
                    WAITING.put(Vec3.atBottomCenterOf(at), blood);
                    onFloor++;
                }
            }
        }
        ModLog.get("sky2m-roomsim").info("Sim keys: {} wither door(s) and {} blood door on the path; {} key(s) carried "
                        + "by starred mobs, {} lying in a room", witherKeysPlanned, bloodKeyPlanned ? 1 : 0, assigned,
                onFloor);
    }

    /** A stand-in id has become a real mob (a placed room mob, a woken Fel): its key goes with it. Server thread. */
    static void handOver(UUID from, UUID to) {
        if (from == null || to == null) {
            return;
        }
        HANDED.put(from, to);
        Boolean blood = HOLDERS.remove(from);
        if (blood != null) {
            HOLDERS.put(to, blood);
        }
    }

    /**
     * A starred mob died at {@code pos}, having spawned at {@code spawnAt}: when it was the last starred mob of a room
     * that holds a key (alive, still queued or a dormant Fel all count as not dead, {@link SimMobs#starredPositions}),
     * the room's keys drop where it fell. Server thread.
     */
    static void holderDied(ServerLevel level, UUID id, Vec3 pos, double[] spawnAt) {
        Boolean blood = id == null ? null : HOLDERS.remove(id);
        if (blood != null && pos != null) {
            dropOn(level, pos, blood);
        }
        if (ROOM_KEYS.isEmpty() || spawnAt == null || pos == null) {
            return;
        }
        int room = roomOf(spawnAt[0], spawnAt[2]);
        List<Boolean> keys = room < 0 ? null : ROOM_KEYS.get(room);
        if (keys == null) {
            return;
        }
        for (double[] at : SimMobs.starredPositions().values()) {
            if (roomOf(at[0], at[2]) == room) {
                return;   // a starred mob of the room is still alive
            }
        }
        ROOM_KEYS.remove(room);
        for (Boolean b : keys) {
            dropOn(level, pos, b);
        }
    }

    /** The room (name-table index) at a position on the floor the keys were planned for, or -1. */
    private static int roomOf(double x, double z) {
        int[] cells = keyRoomCells;
        int cell = cellOf(x, z);
        return cell >= 0 && cell < cells.length ? cells[cell] : -1;
    }

    /** Whether this mob carries a key - for tests. */
    public static boolean isHolder(UUID id) {
        return id != null && HOLDERS.containsKey(id);
    }

    /** Keys still waiting on a room's starred mobs (or held by a mob), for tests and {@code /simkeys}. */
    public static int holderCount() {
        int n = HOLDERS.size();
        for (List<Boolean> keys : ROOM_KEYS.values()) {
            n += keys.size();
        }
        return n;
    }

    /** The rooms (name-table index) whose starred mobs drop a key, mapped to their keys (blood key?) - for tests. */
    public static Map<Integer, List<Boolean>> roomKeys() {
        return Map.copyOf(ROOM_KEYS);
    }

    /** The mob ids carrying a key, mapped to "carries the Blood Key" - for tests. */
    public static Map<UUID, Boolean> holders() {
        return Map.copyOf(HOLDERS);
    }

    /** How many wither keys and whether a blood key this floor's doors need. */
    public static int witherKeysPlanned() {
        return witherKeysPlanned;
    }

    public static boolean bloodKeyPlanned() {
        return bloodKeyPlanned;
    }

    /** Whether {@code /simkeys free} is on: every door opens without a key. */
    public static boolean free() {
        return free;
    }

    public static void setFree(boolean on) {
        free = on;
    }

    /** Whether the blood door needs the Blood Key: a key was planned for it and the override is off. */
    static boolean bloodDoorNeedsKey() {
        return bloodKeyPlanned && !free;
    }

    private static int cellOf(double x, double z) {
        int grid = DungeonLayout.GRID;
        int gx = Math.max(0, Math.min(grid - 1, (int) Math.round((Math.floor(x)
                - com.epic60869.sky2m.sim.livemap.LiveMapFeature.START_X) / 32.0) * 2));
        int gz = Math.max(0, Math.min(grid - 1, (int) Math.round((Math.floor(z)
                - com.epic60869.sky2m.sim.livemap.LiveMapFeature.START_Z) / 32.0) * 2));
        return gz * grid + gx;
    }

    /** Somewhere to stand in a room: down from above a doorway's height at one of its cells' centres. */
    private static BlockPos floorIn(ServerLevel level, int[] roomCells, int room) {
        for (int cell = 0; cell < roomCells.length; cell++) {
            int gx = cell % DungeonLayout.GRID;
            int gz = cell / DungeonLayout.GRID;
            if (roomCells[cell] != room || gx % 2 != 0 || gz % 2 != 0) {
                continue;
            }
            BlockPos c = DungeonLayout.cellCenter(cell);
            int top = SimAltitude.toWorld(90);
            int bottom = SimAltitude.toWorld(55);
            for (int y = top; y >= bottom; y--) {
                BlockPos feet = new BlockPos(c.getX(), y, c.getZ());
                if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                        && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                    return feet;
                }
            }
            return new BlockPos(c.getX(), SimAltitude.toWorld(69), c.getZ());
        }
        return null;
    }

    // ------------------------------------------------------------------ dropped keys

    /** Drops a key at {@code pos} (server side). Client thread or any. */
    public static void drop(Minecraft client, Vec3 pos, boolean blood) {
        var server = client.getSingleplayerServer();
        if (!SimState.canAct(client) || server == null || pos == null) {
            return;
        }
        server.execute(() -> dropOn(server.overworld(), pos, blood));
    }

    /** Drops a key at {@code pos}. Server thread. */
    static void dropOn(ServerLevel level, Vec3 pos, boolean blood) {
        ArmorStand stand = new ArmorStand(level, pos.x, pos.y, pos.z);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setNoBasePlate(true);
        stand.setPermanentlyInvulnerable(true);
        stand.setCustomName(Component.literal(blood ? "Blood Key" : "Wither Key"));
        stand.setCustomNameVisible(true);
        // A full-size stand standing on the floor: the head it wears floats at head height (helmet ~1.4-1.9 above
        // the floor), the name above it, and the stand's own position - what Door Highlight reads and both pickup
        // checks measure to - sits directly under the head.
        stand.setItemSlot(EquipmentSlot.HEAD, keyHead(blood));
        if (level.addFreshEntity(stand)) {
            DROPPED.put(stand.getUUID(), blood);
        }
    }

    /**
     * Hypixel's key skins. hypixelskyblock.minecraft.wiki: "The Wither Key and Wither Essence use the same texture",
     * "The Blood Key and Overflux Power Orb use the same texture" (Wither Key / Blood Key pages, Trivia, 2026-10-06).
     * The Wither Essence item's skin is from the NotEnoughUpdates item repo (items/ESSENCE_WITHER.json, a black orb);
     * the Overflux Power Orb's from Hypixel's own api.hypixel.net/v2/resources/skyblock/items (a red orb in a purple
     * frame). Re-encoded with only the skin URL, as Hypixel's own unsigned heads carry.
     */
    private static final String WITHER_KEY_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzRkYjRhZGZhOWJmNDhmZjVkNDE3MDdhZTM0ZWE3OGJkMjM3MTY1OWZjZDhjZDg5MzQ3NDlhZjRjY2U5YiJ9fX0=";
    private static final String BLOOD_KEY_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjBkZTVlODk3NDk0MDM3NTkzNGQzMmY3MWM5MWFkMmQ1NzI4ZDM4ZTUxNjQ3ZGNjOGYzOTIwNmMwOTlhNTRjMiJ9fX0=";

    /** The player head a dropped key wears, carrying its skin through the {@code minecraft:profile} component. */
    static ItemStack keyHead(boolean blood) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        head.set(DataComponents.PROFILE,
                com.epic60869.sky2m.sim.util.SimItemIds.skullProfile(blood ? BLOOD_KEY_TEXTURE : WITHER_KEY_TEXTURE));
        return head;
    }

    /** The team's wither keys (picked up, not yet used on a door). */
    public static int witherKeys() {
        return witherKeys;
    }

    public static boolean bloodKey() {
        return bloodKey;
    }

    /** Who picked up the last key and how far from it, for tests: "name|distance", or null. */
    public static String lastPickup() {
        return lastPickup;
    }

    public static int droppedCount() {
        return DROPPED.size();
    }

    /** Spends one team wither key; false when there is none. With {@code /simkeys free} on, always true. */
    public static boolean useWitherKey() {
        if (witherKeys <= 0) {
            return free;
        }
        witherKeys--;
        return true;
    }

    static void useBloodKey() {
        bloodKey = false;
    }

    public static void clear() {
        DROPPED.clear();
        WAITING.clear();
        HOLDERS.clear();
        ROOM_KEYS.clear();
        HANDED.clear();
        witherKeys = 0;
        bloodKey = false;
        bloodKeyPlanned = false;
        witherKeysPlanned = 0;
        lastPickup = null;
    }

    /** The team gets a key: Hypixel's two chat lines, to everyone. Any thread. */
    public static void pickedUp(MinecraftServer server, String name, boolean blood) {
        if (blood) {
            bloodKey = true;
        } else {
            witherKeys++;
        }
        if (server == null) {
            return;
        }
        Component line = Component.literal(name + " has obtained " + (blood ? "Blood Key" : "Wither Key") + "!");
        Component how = Component.literal(blood
                ? "RIGHT CLICK on the BLOOD DOOR to open it. This key can only be used to open 1 door!"
                : "RIGHT CLICK on a WITHER door to open it. This key can only be used to open 1 door!");
        server.execute(() -> {
            for (ServerPlayer to : server.getPlayerList().getPlayers()) {
                to.sendSystemMessage(line);
                to.sendSystemMessage(how);
            }
        });
    }

    /** Keys lying in a room, waiting for a player to come near before they are placed. */
    private static final Map<Vec3, Boolean> WAITING = new ConcurrentHashMap<>();

    /** How close a player comes before a waiting key is placed - SimMobs' room-mob radius. */
    private static final double PLACE_RADIUS = 28.0;

    private static void tick(MinecraftServer server) {
        if (!SimState.isActive() || (DROPPED.isEmpty() && WAITING.isEmpty())) {
            return;
        }
        try {
            ServerLevel level = server.overworld();
            for (Map.Entry<Vec3, Boolean> w : WAITING.entrySet()) {
                Vec3 at = w.getKey();
                if (!level.isPositionEntityTicking(BlockPos.containing(at))) {
                    continue;
                }
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    double dx = sp.getX() - at.x;
                    double dz = sp.getZ() - at.z;
                    if (dx * dx + dz * dz <= PLACE_RADIUS * PLACE_RADIUS) {
                        WAITING.remove(at);
                        dropOn(level, at, w.getValue());
                        break;
                    }
                }
            }
            double range = pickupRange();
            for (Map.Entry<UUID, Boolean> e : DROPPED.entrySet()) {
                Entity stand = level.getEntity(e.getKey());
                if (stand == null) {
                    continue;   // not in an entity-ticking section right now - never read as gone
                }
                for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                    // A ghost picks up nothing, as on Hypixel.
                    if (SimDeath.isGhost(sp)) {
                        continue;
                    }
                    double d = sp.position().distanceTo(stand.position());
                    if (d > range) {
                        continue;
                    }
                    boolean blood = e.getValue();
                    DROPPED.remove(e.getKey());
                    stand.discard();
                    String name = sp.getGameProfile().name();
                    lastPickup = name + "|" + String.format(java.util.Locale.US, "%.2f", d);
                    pickedUp(server, name, blood);
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // a key must never stall the server tick
        }
    }
}
