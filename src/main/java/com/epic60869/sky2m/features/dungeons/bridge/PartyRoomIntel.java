package com.epic60869.sky2m.features.dungeons.bridge;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What NoammAddons and Devonian users' sockets say about the map and players that has no place in the party's
 * score facts: rooms and doors a teammate's map found (by grid cell) and each teammate's own secret count. Kept per
 * run for whatever shows teammates' rooms on Sky2M's map; from Killer560's Mod (MIT, partydata/PartyRoomIntel).
 */
public final class PartyRoomIntel {
    public record Cell(String name, int x, int z, int col, int row, String reporter) {}

    public record Door(int x, int z, int col, int row, String type, String reporter) {}

    private static final Map<String, Cell> ROOMS = new LinkedHashMap<>();
    private static final Map<String, Door> DOORS = new LinkedHashMap<>();
    private static final Map<String, Integer> PLAYER_SECRETS = new LinkedHashMap<>();

    private PartyRoomIntel() {}

    static synchronized void offerRoom(String name, int x, int z, int col, int row, String reporter) {
        if (name == null || name.isBlank() || col < 0 || col > 10 || row < 0 || row > 10 || ROOMS.size() > 256) return;
        ROOMS.put(col + "," + row, new Cell(name, x, z, col, row, reporter));
    }

    static synchronized void offerDoor(int x, int z, int col, int row, String type, String reporter) {
        if (col < 0 || col > 10 || row < 0 || row > 10 || DOORS.size() > 256) return;
        DOORS.put(col + "," + row, new Door(x, z, col, row, type, reporter));
    }

    static synchronized void offerPlayerSecrets(String player, int secrets) {
        if (player == null || secrets < 0 || secrets > 999) return;
        PLAYER_SECRETS.put(player.toLowerCase(Locale.ROOT), secrets);
    }

    public static synchronized Map<String, Cell> rooms() {
        return new LinkedHashMap<>(ROOMS);
    }

    public static synchronized Map<String, Door> doors() {
        return new LinkedHashMap<>(DOORS);
    }

    public static synchronized Map<String, Integer> playerSecrets() {
        return new LinkedHashMap<>(PLAYER_SECRETS);
    }

    static synchronized void reset() {
        ROOMS.clear();
        DOORS.clear();
        PLAYER_SECRETS.clear();
    }
}
