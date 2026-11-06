package com.epic60869.sky2m.features.dungeons.mapstyle;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blood Rush Path, like BetterMap's: the fewest-doors route from your room (or the Entrance) to Blood, drawn on the
 * dungeon map. Legit by construction: it only walks the rooms and doors of the {@link MapView}, which on Hypixel holds
 * only what the map item itself shows (unopened rooms have no type, door types are the map item's own tiles), so
 * there is nothing to draw until Hypixel's map shows Blood or its door.
 */
public final class BloodRushPath {
    private static final int COLOUR = 0xFFFF3030;

    private BloodRushPath() {}

    /** Draws the path, in map pixels (the caller has translated and scaled to the map). */
    static void render(GuiGraphicsExtractor graphics, MapView view) {
        List<int[]> cells = path(view);
        if (cells == null || cells.size() < 2) return;
        for (int i = 0; i + 1 < cells.size(); i++) {
            int[] a = cells.get(i), b = cells.get(i + 1);
            int ax = centreX(view, a[0]), ay = centreY(view, a[1]), bx = centreX(view, b[0]), by = centreY(view, b[1]);
            graphics.fill(Math.min(ax, bx) - 1, Math.min(ay, by) - 1, Math.max(ax, bx) + 1, Math.max(ay, by) + 1, COLOUR);
        }
    }

    private static int centreX(MapView view, int gx) {
        return view.cellX(gx) + view.cellSize(gx) / 2;
    }

    private static int centreY(MapView view, int gz) {
        return view.cellY(gz) + view.cellSize(gz) / 2;
    }

    /**
     * The route as 11x11 grid cells (even = room slot, odd = the door or connector between two), from the start slot
     * to the first Blood slot or Blood door; null when the map doesn't show Blood yet or nothing connects to it.
     */
    public static List<int[]> path(MapView view) {
        if (!view.valid() || view.inBoss() || view.rooms().isEmpty()) return null;
        // Which room each slot belongs to, and the edges: inside a room (its own connectors) and through doors.
        Map<Long, MapView.Room> roomAt = new HashMap<>();
        for (MapView.Room room : view.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED) continue;
            for (int[] t : room.tiles()) roomAt.put(key(t[0] * 2, t[1] * 2), room);
        }
        Map<Long, MapView.Door> doorAt = new HashMap<>();
        for (MapView.Door door : view.doors()) doorAt.put(key(door.gx(), door.gz()), door);

        int[] start = startSlot(view, roomAt);
        if (start == null) return null;
        Map<Long, Long> cameFrom = new HashMap<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(start);
        cameFrom.put(key(start[0], start[1]), Long.MIN_VALUE);
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] at = queue.poll();
            MapView.Room here = roomAt.get(key(at[0], at[1]));
            if (here != null && here.type() == MapView.RoomType.BLOOD) return walkBack(cameFrom, at);
            for (int[] s : steps) {
                int mx = at[0] + s[0], mz = at[1] + s[1];
                int nx = at[0] + 2 * s[0], nz = at[1] + 2 * s[1];
                MapView.Room next = roomAt.get(key(nx, nz));
                if (next == null || cameFrom.containsKey(key(nx, nz))) continue;
                MapView.Door door = doorAt.get(key(mx, mz));
                // Two tiles of one room are joined by its connector; different rooms only by a door.
                if (next != here && door == null) continue;
                cameFrom.put(key(mx, mz), key(at[0], at[1]));
                cameFrom.put(key(nx, nz), key(mx, mz));
                if (door != null && door.type() == MapView.DoorType.BLOOD) return walkBack(cameFrom, new int[]{mx, mz});
                queue.add(new int[]{nx, nz});
            }
        }
        return null;
    }

    /** Your own head's slot when it's on a room, else the Entrance's. */
    private static int[] startSlot(MapView view, Map<Long, MapView.Room> roomAt) {
        for (MapView.Head head : view.heads()) {
            if (!head.self()) continue;
            int col = (int) Math.floor((head.x() - view.startX()) / (view.roomSize() + view.gap()));
            int row = (int) Math.floor((head.y() - view.startY()) / (view.roomSize() + view.gap()));
            if (roomAt.containsKey(key(col * 2, row * 2))) return new int[]{col * 2, row * 2};
        }
        for (MapView.Room room : view.rooms()) {
            if (room.type() == MapView.RoomType.ENTRANCE && !room.tiles().isEmpty()) {
                return new int[]{room.tiles().get(0)[0] * 2, room.tiles().get(0)[1] * 2};
            }
        }
        return null;
    }

    private static List<int[]> walkBack(Map<Long, Long> cameFrom, int[] end) {
        List<int[]> out = new ArrayList<>();
        long k = key(end[0], end[1]);
        while (k != Long.MIN_VALUE) {
            out.add(0, new int[]{(int) (k >> 32), (int) k});
            Long prev = cameFrom.get(k);
            if (prev == null) break;
            k = prev;
        }
        return out;
    }

    private static long key(int gx, int gz) {
        return ((long) gx << 32) | (gz & 0xFFFFFFFFL);
    }
}
