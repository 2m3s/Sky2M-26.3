package com.epic60869.sky2m.features.dungeons.mapstyle;

import net.minecraft.world.entity.player.PlayerSkin;

import java.util.List;

/**
 * Snapshot of everything a dungeon map style needs, independent of where it came from (Hypixel's map item + the room
 * scanner, or the Dungeon Sim). Built once per client tick by {@code sim.livemap.MapViewSource}; immutable.
 *
 * <p>Geometry: the map is a 128x128 pixel square like the vanilla map item. Room slot {@code (col, row)} (0-5) has its
 * top-left at {@code (startX + col * (roomSize + gap), startY + row * (roomSize + gap))} and is {@code roomSize} px wide;
 * {@code gap} (4) px of connector/door lie between slots. {@link Door#gx}/{@link Door#gz} are 11x11 grid cells
 * (even = room slot, odd = gap), see {@link #cellX}. Head positions are already in map pixels.
 */
public record MapView(
        boolean valid,        // false: nothing to draw (not in a dungeon / no data yet)
        boolean simulated,    // true: Dungeon Sim floor, no map item exists
        boolean inBoss,       // boss room entered: rooms are frozen, heads are normally hidden
        String floor,         // "F7", "M3", "E" or ""
        int cols, int rows,   // floor size in room slots (4x4 E, 4x5 F1, 5x5 F2-3, 6x5 F4, 6x6 F5-7)
        int roomSize,         // 16, or 18 on small floors (map pixels)
        int gap,              // connector length between slots, 4
        int startX, int startY, // map pixel of room slot (0,0)'s top-left
        List<Room> rooms,
        List<Door> doors,
        List<Head> heads,
        RunInfo run) {

    public static final int MAP_SIZE = 128;

    public enum RoomType { ENTRANCE, NORMAL, PUZZLE, TRAP, MINIBOSS, FAIRY, BLOOD, RARE, UNKNOWN }

    /** Ordered from least to most progressed, like the map item's own colours. */
    public enum RoomState { UNDISCOVERED, UNOPENED, DISCOVERED, CLEARED, GREEN, FAILED }

    public enum DoorType { NORMAL, WITHER, BLOOD, ENTRANCE }

    /**
     * One room (all tiles of a multi-tile room together). {@code tiles} are {@code {col, row}} room slots;
     * {@code labelCol/labelRow} is where its text/checkmark belongs in slot units (centre of the room, may be fractional).
     * {@code name} is null and {@code secretsTotal} is -1 while Hypixel has not revealed it (unopened).
     */
    public record Room(String name, RoomType type, RoomState state, List<int[]> tiles, float labelCol, float labelRow,
                       int secretsFound, int secretsTotal, boolean lShape) {
        public int minCol() { int m = 99; for (int[] t : tiles) m = Math.min(m, t[0]); return m; }
        public int minRow() { int m = 99; for (int[] t : tiles) m = Math.min(m, t[1]); return m; }
        public int maxCol() { int m = -1; for (int[] t : tiles) m = Math.max(m, t[0]); return m; }
        public int maxRow() { int m = -1; for (int[] t : tiles) m = Math.max(m, t[1]); return m; }
    }

    /** A door (or the connector of a bigger room is not a door - it is part of {@link Room#tiles}). {@code locked}: a wither/blood door not yet opened; {@code unopened}: the map shows it as "not seen yet" grey. */
    public record Door(int gx, int gz, DoorType type, boolean locked, boolean unopened) {}

    /** A player marker: {@code x, y} in map pixels, {@code yaw} Minecraft degrees, {@code classColor} 0xRRGGBB or -1. */
    public record Head(String name, boolean self, float x, float y, float yaw, int classColor, PlayerSkin skin) {}

    /** Run info; ints are -1 when unknown, {@code started} false before the run's score is live. */
    public record RunInfo(boolean started, int score, int secretsFound, int secretsNeeded, int secretsTotal, int crypts,
                          int deaths, boolean mimicKilled, boolean princeKilled, int seconds) {}

    public static final RunInfo NO_RUN = new RunInfo(false, 0, 0, 0, 0, 0, 0, false, false, 0);

    public static MapView empty() {
        return new MapView(false, false, false, "", 6, 6, 16, 4, 6, 6, List.of(), List.of(), List.of(), NO_RUN);
    }

    /** Map pixel x of the left edge of grid cell column {@code gx} (0-10; odd cells are the gap after a slot). */
    public int cellX(int gx) { return startX + (gx / 2) * (roomSize + gap) + (gx % 2 == 1 ? roomSize : 0); }

    /** Map pixel y of the top edge of grid cell row {@code gz}. */
    public int cellY(int gz) { return startY + (gz / 2) * (roomSize + gap) + (gz % 2 == 1 ? roomSize : 0); }

    public int cellSize(int g) { return g % 2 == 0 ? roomSize : gap; }
}
