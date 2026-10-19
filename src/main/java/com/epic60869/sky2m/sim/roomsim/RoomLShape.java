package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.roomdatabase.RoomDatabase;
import com.epic60869.sky2m.sim.roomdatabase.RoomEntry;
import com.epic60869.sky2m.sim.util.ModLog;
import org.slf4j.Logger;

public final class RoomLShape {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");

    private RoomLShape() {
    }

    static int tiles(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    public static int[] missingTile(RoomLibrary.Room room) {
        if (room == null || tiles(room.sizeX) != 2 || tiles(room.sizeZ) != 2) {
            return null;
        }
        RoomEntry entry = RoomDatabase.lookupByName(room.name);
        if (entry == null || entry.shape == null || !"L".equalsIgnoreCase(entry.shape.trim())) {
            return null;
        }
        int[] own = new int[4];
        java.util.List<String> foreign = new java.util.ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int tx = t / 2;
            int tz = t % 2;
            int x = room.margin + RoomLibrary.TILE / 2 + tx * (RoomLibrary.TILE + 1);
            int z = room.margin + RoomLibrary.TILE / 2 + tz * (RoomLibrary.TILE + 1);
            int core = RoomDatabase.coreOfColumn(y -> {
                short id = room.at(x, y, z);
                return id >= 0 && id < room.palette.size() ? room.palette.get(id) : null;
            });
            RoomEntry hit = RoomDatabase.lookup(core);
            if (hit != null) {
                if (room.name.equals(hit.name)) {
                    own[t] = 1;
                } else {
                    own[t] = -1;
                    foreign.add(tx + "," + tz + "=" + hit.name);
                }
            }
        }
        int foreignTile = only(own, -1);
        if (foreignTile >= 0) {
            return new int[]{foreignTile / 2, foreignTile % 2};
        }
        if (count(own, 1) == 3) {
            int t = only(own, 0);
            if (t >= 0) {
                return new int[]{t / 2, t % 2};
            }
        }
        int empty = -1;
        int empties = 0;
        long bestSolid = Long.MAX_VALUE;
        int best = 0;
        for (int t = 0; t < 4; t++) {
            int x0 = room.margin + (t / 2) * (RoomLibrary.TILE + 1);
            int z0 = room.margin + (t % 2) * (RoomLibrary.TILE + 1);
            long all = 0;
            long band = 0;
            for (int y = room.minY; y <= room.maxY; y++) {
                for (int a = 0; a < RoomLibrary.TILE; a++) {
                    for (int b = 0; b < RoomLibrary.TILE; b++) {
                        short id = room.at(x0 + a, y, z0 + b);
                        if (id >= 0 && id < room.palette.size() && !room.palette.get(id).endsWith("air")) {
                            all++;
                            if (y >= 66 && y <= 99) {
                                band++;
                            }
                        }
                    }
                }
            }
            if (all == 0) {
                empty = t;
                empties++;
            }
            if (band < bestSolid) {
                bestSolid = band;
                best = t;
            }
        }
        if (empties == 1) {
            return new int[]{empty / 2, empty % 2};
        }
        LOGGER.warn("Sim: L room {} - no tile names itself missing (cores {} {} {} {}, foreign {}); using the emptiest,"
                        + " tile {},{} ({} blocks in y66..99)", room.name, own[0], own[1], own[2], own[3], foreign,
                best / 2, best % 2, bestSolid);
        return new int[]{best / 2, best % 2};
    }

    public static int[] missingTilePasted(RoomLibrary.Room room, int rotation) {
        int[] m = missingTile(room);
        if (m == null) {
            return null;
        }
        return switch (Math.floorMod(rotation, 360)) {
            case 90 -> new int[]{1 - m[1], m[0]};
            case 180 -> new int[]{1 - m[0], 1 - m[1]};
            case 270 -> new int[]{m[1], 1 - m[0]};
            default -> m;
        };
    }

    private static int only(int[] v, int want) {
        int at = -1;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == want) {
                if (at >= 0) {
                    return -1;
                }
                at = i;
            }
        }
        return at;
    }

    private static int count(int[] v, int want) {
        int n = 0;
        for (int x : v) {
            if (x == want) {
                n++;
            }
        }
        return n;
    }
}