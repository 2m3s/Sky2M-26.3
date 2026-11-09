package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.livemap.DungeonLayout;
import com.epic60869.sky2m.sim.util.ModLog;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Mobs for the rooms the room library recorded none for.
 *
 * <p>The library holds a few dozen recorded mob positions, nearly all of them in one room (Archway), so most rooms of
 * a generated floor used to be empty. This fills them the way a Hypixel room is filled: a handful of mobs in a 1x1,
 * more in the bigger shapes, Master Mode a little denser, most of them starred with a few skeletons and the odd Fel
 * among them (Fels from Floor V, as on Hypixel); the rooms the room database marks as miniboss rooms
 * ({@code CHAMPION}, the map's yellow rooms) get their miniboss alone, and the Redstone Warrior room its two
 * Redstone Warriors on top of its mobs - see {@link #miniboss} and {@link #REDSTONE_WARRIOR_ROOM}.
 *
 * <p><b>Where.</b> Spots are read from the room's own CAPTURE (rotation 0, capture coordinates), not from the world:
 * the build has long moved on from the room by the time its mobs are queued, and the capture is what was pasted. A
 * spot is a block with two blocks of room above it on something solid, covered by a ceiling, on the room's walking
 * levels, away from the room's outer walls (where its doorways are) and away from anything that looks like a secret
 * (chests, levers, skulls, buttons). Each spot is turned into the world through the paste's own transform - the same
 * one {@code SimBuilder.queueCapturedMobs} uses - and queued with {@link SimMobs#queueRoomMob}, so these mobs too are
 * placed only when the player comes near.
 *
 * <p><b>Deterministic.</b> Every choice comes from a {@link Random} seeded from the map code, the room and where it
 * sits, so the same map rebuilt has the same mobs in the same places.
 *
 * <p>The counts and the mix are APPROXIMATE - Hypixel's real per-room spawns are not available offline. See
 * {@link #count}.
 */
public final class SimRoomMobs {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");

    /** Walking levels, in capture (Hypixel) y: a dungeon room's floor is at about 68, and multi-level rooms rise above it. */
    private static final int MIN_FEET_Y = 62;
    private static final int MAX_FEET_Y = 92;
    /** A spot this close to the room's outer wall is in or next to a doorway. */
    private static final int WALL_CLEARANCE = 5;
    /** How far up a ceiling is looked for: a spot under open sky is on top of the room, not in it. */
    private static final int CEILING_SEARCH = 48;
    /** Two mobs are never placed closer than this (Manhattan, horizontally). */
    private static final int MIN_SPACING = 3;
    private static final int MAX_MOBS = 22;

    /**
     * The room whose two Redstone Warriors are minibosses, from Floor II (the wiki's Dungeon Mobs and
     * https://hypixelskyblock.minecraft.wiki/w/Redstone_Warrior). On Hypixel they come out when the room's lever is
     * pressed; the sim has no tie to that lever yet, so they stand in the room from the start.
     */
    static final String REDSTONE_WARRIOR_ROOM = "Redstone Warrior";
    /** How many Redstone Warriors its room holds. */
    static final int REDSTONE_WARRIORS = 2;
    /** Hypixel's first floor with Fels: "Floor V to Floor VII" (the wiki's Dungeon Mobs). */
    static final int FEL_FLOOR = 5;

    private SimRoomMobs() {
    }

    /**
     * Queues the room's generated mobs. Server thread, from the build's completion work, the way
     * {@code SimBuilder.queueCapturedMobs} runs.
     *
     * @param fill whether to place the regular mobs (false when the room's own recorded mobs were queued); a miniboss
     *             room's miniboss is placed either way
     */
    static void generate(RoomLibrary.Room room, int gridX, int gridZ, int rotation, boolean fill) {
        if (room == null || room.name == null) {
            return;
        }
        String type = SimFloorGen.typeOf(room.name).toUpperCase(Locale.ROOT);
        switch (type) {
            case "PUZZLE", "BLOOD", "ENTRANCE", "FAIRY" -> {
                return;
            }
            default -> {
            }
        }
        boolean champion = "CHAMPION".equals(type);
        String floor = SimState.floorLabel() == null ? "F7" : SimState.floorLabel().toUpperCase(Locale.ROOT);
        int warriors = redstoneWarriors(room.name, floor);
        if (!fill && !champion && warriors == 0) {
            return;
        }
        long seed = (SimState.mapCode() == null ? 0L : SimState.mapCode().hashCode()) * 31L
                + room.name.hashCode() * 17L + gridX * 131L + gridZ * 7919L + floor.hashCode();
        Random rnd = new Random(seed);

        var origin = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int x0 = origin.getX() - RoomLibrary.TILE / 2 - room.margin;
        int z0 = origin.getZ() - RoomLibrary.TILE / 2 - room.margin;
        int tilesX = Math.max(1, (room.sizeX - 1) / (RoomLibrary.TILE + 1));
        int tilesZ = Math.max(1, (room.sizeZ - 1) / (RoomLibrary.TILE + 1));
        boolean[][] inRoom = tilesInRoom(room, tilesX, tilesZ, x0, z0, rotation);
        int tiles = 0;
        for (boolean[] row : inRoom) {
            for (boolean b : row) {
                tiles += b ? 1 : 0;
            }
        }
        List<int[]> spots = spots(room, inRoom);
        if (spots.isEmpty()) {
            LOGGER.info("Sim mobs: no standable spot found in {} - left empty", room.name);
            return;
        }
        // Shuffled by the seeded generator, so which spots are used is the map's, not the scan order's.
        java.util.Collections.shuffle(spots, rnd);
        List<int[]> chosen = new ArrayList<>();
        int queued = 0;
        if (champion) {
            int[] centre = nearestTo(spots, room.sizeX / 2, room.sizeZ / 2);
            chosen.add(centre);
            queue(room, x0, z0, rotation, centre, SimMobs.Kind.MINIBOSS, true, miniboss(room.name, floor, rnd));
            queued++;
        }
        // The Redstone Warriors stand apart from the room's other mobs, near its middle.
        for (int i = 0; i < warriors; i++) {
            int[] spot = null;
            for (int[] s : spots) {
                if (!tooClose(chosen, s) && (spot == null || Math.abs(s[0] - room.sizeX / 2)
                        + Math.abs(s[2] - room.sizeZ / 2) < Math.abs(spot[0] - room.sizeX / 2)
                        + Math.abs(spot[2] - room.sizeZ / 2))) {
                    spot = s;
                }
            }
            if (spot == null) {
                break;
            }
            chosen.add(spot);
            queue(room, x0, z0, rotation, spot, SimMobs.Kind.MINIBOSS, true, SimMiniboss.Name.REDSTONE_WARRIOR);
            queued++;
        }
        if (fill && !champion) {
            int want = count(tiles, floor, rnd);
            int filled = 0;
            boolean master = floor.startsWith("M");
            int floorNumber = floorNumber(floor);
            for (int[] spot : spots) {
                if (filled >= want) {
                    break;
                }
                if (tooClose(chosen, spot)) {
                    continue;
                }
                chosen.add(spot);
                // Most of a Hypixel room's mobs carry the star; the rest are filler.
                boolean starred = rnd.nextDouble() < (master ? 0.85 : 0.78);
                SimMobs.Kind kind;
                double r = rnd.nextDouble();
                if (starred && floorNumber >= FEL_FLOOR && r < 0.08) {
                    kind = SimMobs.Kind.FEL;
                } else if (r < (starred ? 0.33 : 0.38)) {
                    kind = SimMobs.Kind.SKELETON;
                } else {
                    kind = SimMobs.Kind.ZOMBIE;
                }
                queue(room, x0, z0, rotation, spot, kind, starred, null);
                queued++;
                filled++;
            }
        }
        if (queued > 0) {
            LOGGER.info("Sim mobs: {} generated mob(s) queued for {} ({} tile(s), {})", queued, room.name, tiles,
                    floor);
        }
    }

    /**
     * How many mobs a room of {@code tiles} tiles holds on this floor. APPROXIMATE: a 1x1 holds about 4 to 6 on the
     * Normal floors (fewer on the Entrance and the early floors) and 5 to 7 in Master Mode; each further tile adds
     * about four fifths of a 1x1's worth, so a 2x2 lands near 15 (Normal) to 20 (Master Mode).
     */
    static int count(int tiles, String floor, Random rnd) {
        int n = floorNumber(floor);
        double perTile;
        if (floor.startsWith("M")) {
            perTile = 6;
        } else if (floor.equals("E")) {
            perTile = 3;
        } else {
            perTile = n <= 3 ? 4 : 5;
        }
        double base = perTile * (1 + 0.8 * (Math.max(1, tiles) - 1));
        return Math.max(1, Math.min(MAX_MOBS, (int) Math.round(base) + rnd.nextInt(3) - 1));
    }

    /**
     * Which miniboss a yellow room ({@code CHAMPION}: Default, Dragon, King Midas and Shadow Assassin in the room
     * database) holds, by Hypixel's floors:
     * <ul>
     * <li>King Midas only in his own room ("King Midas"), Floor III to VII (/w/King_Midas, Dungeon Mobs). Below
     * Floor III his room holds a Lost Adventurer (APPROXIMATE: the wiki does not say what it holds there).</li>
     * <li>The Shadow Assassin's room holds him from Floor III (/w/Shadow_Assassin: "Floor III and above"); below it,
     * a Floor I-II miniboss.</li>
     * <li>Any other yellow room a random one of the floor's: the Lost Adventurer and the Angry Archaeologist on every
     * floor (/w/Lost_Adventurer, /w/Angry_Archaeologist: "any floor"), the Shadow Assassin from Floor III and the
     * Frozen Adventurer from Floor IV (/w/Frozen_Adventurer: "Floors IV through VII"). The Lost Adventurer is
     * "rarer on floors 3 and above", so there it is drawn half as often as each of the others (APPROXIMATE weight).
     * </li>
     * </ul>
     * Master Mode follows its floor number the same way. The Redstone Warrior is never a yellow room's: it belongs to
     * its own room ({@link #redstoneWarriors}).
     */
    public static SimMiniboss.Name miniboss(String roomName, String floor, Random rnd) {
        String lower = roomName == null ? "" : roomName.toLowerCase(Locale.ROOT);
        int n = floorNumber(floor);
        if (lower.equals("king midas")) {
            return n >= 3 ? SimMiniboss.Name.KING_MIDAS : SimMiniboss.Name.LOST_ADVENTURER;
        }
        if (n >= 3 && lower.equals("shadow assassin")) {
            return SimMiniboss.Name.SHADOW_ASSASSIN;
        }
        // Weights in halves: the Lost Adventurer 2 below Floor III, 1 from it; every other one 2.
        List<SimMiniboss.Name> pool = new ArrayList<>();
        int lostWeight = n >= 3 ? 1 : 2;
        for (int i = 0; i < lostWeight; i++) {
            pool.add(SimMiniboss.Name.LOST_ADVENTURER);
        }
        pool.add(SimMiniboss.Name.ANGRY_ARCHAEOLOGIST);
        pool.add(SimMiniboss.Name.ANGRY_ARCHAEOLOGIST);
        if (n >= 3) {
            pool.add(SimMiniboss.Name.SHADOW_ASSASSIN);
            pool.add(SimMiniboss.Name.SHADOW_ASSASSIN);
        }
        if (n >= 4) {
            pool.add(SimMiniboss.Name.FROZEN);
            pool.add(SimMiniboss.Name.FROZEN);
        }
        return pool.get(rnd.nextInt(pool.size()));
    }

    /**
     * How many Redstone Warriors a room holds on this floor: {@link #REDSTONE_WARRIORS} in the Redstone Warrior room
     * from Floor II ("Floor II to Floor VII", the wiki's Dungeon Mobs; Master Mode from M1, which its page lists),
     * none anywhere else.
     */
    public static int redstoneWarriors(String roomName, String floor) {
        if (roomName == null || !roomName.equalsIgnoreCase(REDSTONE_WARRIOR_ROOM)) {
            return 0;
        }
        boolean master = floor != null && floor.toUpperCase(Locale.ROOT).startsWith("M");
        return floorNumber(floor) >= (master ? 1 : 2) ? REDSTONE_WARRIORS : 0;
    }

    /** Whether a generated room mob of this floor can be a Fel (Floor V and up, Normal or Master Mode). */
    public static boolean felsOn(String floor) {
        return floorNumber(floor) >= FEL_FLOOR;
    }

    private static int floorNumber(String floor) {
        if (floor == null || floor.isEmpty() || !Character.isDigit(floor.charAt(floor.length() - 1))) {
            return 0;
        }
        return floor.charAt(floor.length() - 1) - '0';
    }

    private static void queue(RoomLibrary.Room room, int x0, int z0, int rotation, int[] spot, SimMobs.Kind kind,
                              boolean starred, SimMiniboss.Name boss) {
        int[] local = RoomPlacer.rotateLocal(spot[0], spot[2], room.sizeX, room.sizeZ, rotation);
        SimMobs.queueRoomMob(new BlockPos(x0 + local[0], SimAltitude.toWorld(spot[1]), z0 + local[1]), kind,
                starred, boss);
    }

    /**
     * Which of the capture's tiles belong to this room on the floor that was built. A 2x2 capture of an L-shaped
     * room has a quarter that is some other room's (or nothing), and mobs there would be in the wrong room. Asked of
     * the map code itself, one tile at a time.
     */
    private static boolean[][] tilesInRoom(RoomLibrary.Room room, int tilesX, int tilesZ, int x0, int z0,
                                           int rotation) {
        boolean[][] out = new boolean[tilesX][tilesZ];
        boolean any = false;
        for (int tx = 0; tx < tilesX; tx++) {
            for (int tz = 0; tz < tilesZ; tz++) {
                int cx = room.margin + tx * (RoomLibrary.TILE + 1) + RoomLibrary.TILE / 2;
                int cz = room.margin + tz * (RoomLibrary.TILE + 1) + RoomLibrary.TILE / 2;
                int[] local = RoomPlacer.rotateLocal(cx, cz, room.sizeX, room.sizeZ, rotation);
                String name = SimState.roomNameAt(x0 + local[0], z0 + local[1]);
                out[tx][tz] = name == null || name.equalsIgnoreCase(room.name);
                any |= out[tx][tz];
            }
        }
        if (!any) {
            // The map could not be read (a single room, a code that does not decode): the whole capture is the room.
            for (boolean[] row : out) {
                java.util.Arrays.fill(row, true);
            }
        }
        return out;
    }

    /** Every usable spot, as {x, feetY, z} in capture coordinates - the lowest one per column. */
    private static List<int[]> spots(RoomLibrary.Room room, boolean[][] inRoom) {
        int n = room.palette.size();
        byte[] kind = new byte[n];
        for (int i = 0; i < n; i++) {
            kind[i] = classify(room.palette.get(i));
        }
        List<int[]> out = new ArrayList<>();
        int lo = Math.max(room.minY + 1, MIN_FEET_Y);
        int hi = Math.min(room.maxY - 2, MAX_FEET_Y);
        for (int x = WALL_CLEARANCE; x < room.sizeX - WALL_CLEARANCE; x++) {
            for (int z = WALL_CLEARANCE; z < room.sizeZ - WALL_CLEARANCE; z++) {
                int tx = Math.min(inRoom.length - 1, (x - room.margin) / (RoomLibrary.TILE + 1));
                int tz = Math.min(inRoom[0].length - 1, (z - room.margin) / (RoomLibrary.TILE + 1));
                if (tx < 0 || tz < 0 || !inRoom[tx][tz] || nearInnerSeam(room, x, z, inRoom)) {
                    continue;
                }
                for (int y = lo; y <= hi; y++) {
                    if (kind(room, kind, x, y - 1, z) != SOLID || kind(room, kind, x, y, z) != OPEN
                            || kind(room, kind, x, y + 1, z) != OPEN) {
                        continue;
                    }
                    if (!covered(room, kind, x, y + 2, z) || nearSecret(room, kind, x, y, z)) {
                        continue;
                    }
                    out.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        return out;
    }

    /** Close to the edge of a tile that is not the room's (the open side of an L), which is a wall too. */
    private static boolean nearInnerSeam(RoomLibrary.Room room, int x, int z, boolean[][] inRoom) {
        int step = RoomLibrary.TILE + 1;
        for (int dx = -WALL_CLEARANCE; dx <= WALL_CLEARANCE; dx += WALL_CLEARANCE) {
            for (int dz = -WALL_CLEARANCE; dz <= WALL_CLEARANCE; dz += WALL_CLEARANCE) {
                int tx = (x + dx - room.margin) / step;
                int tz = (z + dz - room.margin) / step;
                if (tx >= 0 && tz >= 0 && tx < inRoom.length && tz < inRoom[0].length && !inRoom[tx][tz]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final byte UNKNOWN = 0;
    private static final byte OPEN = 1;
    private static final byte SOLID = 2;
    /** Open, but a secret or something a secret sits on - nothing spawns next to it. */
    private static final byte SECRET = 3;
    /** Solid, but not something to stand a mob on (fences, walls, glass panes, liquids) or not passable. */
    private static final byte OTHER = 4;

    private static byte kind(RoomLibrary.Room room, byte[] kinds, int x, int y, int z) {
        short id = room.at(x, y, z);
        return id < 0 || id >= kinds.length ? UNKNOWN : kinds[id];
    }

    /** A block state's role for standing, from its id alone (the capture holds ids, not shapes). */
    static byte classify(String state) {
        if (state == null) {
            return UNKNOWN;
        }
        String id = state.toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) {
            id = id.substring(0, bracket);
        }
        if (id.endsWith(":air") || id.endsWith("void_air") || id.endsWith("cave_air")) {
            return OPEN;
        }
        if (id.contains("chest") || id.contains("lever") || id.contains("skull") || id.contains("_head")
                || id.contains("button") || id.contains("redstone")) {
            return SECRET;
        }
        if (id.contains("water") || id.contains("lava") || id.contains("fire") || id.contains("cobweb")) {
            return OTHER;
        }
        if (id.contains("torch") || id.contains("carpet") || id.contains("pressure_plate") || id.contains("rail")
                || id.contains("vine") || id.contains("ladder") || id.contains("sign") || id.contains("banner")
                || id.contains("flower") || id.contains("grass") && !id.contains("grass_block") || id.contains("fern")
                || id.contains("snow") && !id.contains("snow_block") || id.contains("tripwire")
                || id.contains("sapling") || id.contains("mushroom") && !id.contains("block")
                || id.contains("dead_bush") || id.endsWith(":string") || id.endsWith(":light")) {
            return OPEN;
        }
        if (id.contains("fence") || id.contains("wall") && !id.contains("wall_") || id.contains("pane")
                || id.contains("bars") || id.contains("door") || id.contains("barrier") || id.contains("chain")
                || id.contains("lantern") || id.contains("pot")) {
            return OTHER;
        }
        return SOLID;
    }

    /** Something solid somewhere above, within {@link #CEILING_SEARCH}. */
    private static boolean covered(RoomLibrary.Room room, byte[] kinds, int x, int fromY, int z) {
        for (int y = fromY; y <= Math.min(room.maxY, fromY + CEILING_SEARCH); y++) {
            byte k = kind(room, kinds, x, y, z);
            if (k == SOLID || k == OTHER) {
                return true;
            }
        }
        return false;
    }

    /** A chest, lever, skull or button within two blocks. */
    private static boolean nearSecret(RoomLibrary.Room room, byte[] kinds, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    if (kind(room, kinds, x + dx, y + dy, z + dz) == SECRET) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean tooClose(List<int[]> chosen, int[] spot) {
        for (int[] c : chosen) {
            if (Math.abs(c[0] - spot[0]) + Math.abs(c[2] - spot[2]) < MIN_SPACING && Math.abs(c[1] - spot[1]) < 3) {
                return true;
            }
        }
        return false;
    }

    private static int[] nearestTo(List<int[]> spots, int x, int z) {
        int[] best = spots.get(0);
        int bestD = Integer.MAX_VALUE;
        for (int[] s : spots) {
            int d = Math.abs(s[0] - x) + Math.abs(s[2] - z);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }
}
