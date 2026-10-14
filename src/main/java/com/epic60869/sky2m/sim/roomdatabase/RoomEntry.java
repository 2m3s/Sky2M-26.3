/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560; see THIRD_PARTY_NOTICES.md.
 */
package com.epic60869.sky2m.sim.roomdatabase;

import java.util.List;

/**
 * One dungeon room's real, known data - shape matches NoammAddons' own {@code rooms-modern.json}
 * exactly (field names included) so Gson can deserialize it directly with zero mapping code. Real,
 * confirmed data downloaded from the same public endpoint NoammAddons' own client uses - see
 * {@link RoomDatabase}'s class doc.
 */
public final class RoomEntry {
    public String name;
    public String type;
    public String shape;
    public int[] cores;
    public SecretDetails secretDetails;
    public SecretCoords secretCoords;
    public int trappedChests;
    public int reviveStones;
    public int secrets;
    public int crypts;

    public static final class SecretDetails {
        public int redstoneKey;
        public int wither;
        public int bat;
        public int item;
        public int chest;
    }

    public static final class SecretCoords {
        public List<Pos> redstoneKey;
        public List<Pos> wither;
        public List<Pos> bat;
        public List<Pos> item;
        public List<Pos> chest;
    }

    public static final class Pos {
        public int x;
        public int y;
        public int z;
    }
}
