/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.livemap;

import com.epic60869.sky2m.sim.dungeonclass.DungeonClass;
import com.epic60869.sky2m.sim.leapmenu.PartyTracker;
import com.epic60869.sky2m.sim.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interactive Map - port of QUOI's {@code InteractiveMap} module (plus NoammAddons/Odin map UX): keybinds, the HUD peek,
 * clicking the HUD map from chat, per-room "cleared by" tracking and the player marker list the
 * {@link InteractiveMapScreen} draws. Teleport pathing ({@code livemap.autoclear}) and Auto Blood Rush are cheat-gated.
 */
public final class InteractiveMapFeature {

    /** Per-room (group main tile) last map state, for cleared-by tracking. */
    private static final Map<Integer, Integer> lastRoomState = new HashMap<>();
    /** Room key -> players who were in it when it turned cleared/green. */
    private static final Map<String, List<String>> clearedBy = new HashMap<>();
    private static int seenGeneration = -1;
    private static int clearTick = 0;

    private InteractiveMapFeature() {
    }

    /** Sky2M keeps only the map's player markers and "cleared by" from Killer560's Interactive Map: clicking the map
     *  to walk, path or clear rooms (its auto-clear) is automation and isn't ported. */
    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(InteractiveMapFeature::trackClears);
    }

    static boolean isPeeking() {
        return false;
    }

    // ------------------------------------------------------------------------------------------- cleared by

    private static void trackClears(Minecraft client) {
        if (seenGeneration != LiveMapFeature.resetGeneration()) {
            seenGeneration = LiveMapFeature.resetGeneration();
            lastRoomState.clear();
            clearedBy.clear();
        }
        if (++clearTick < 5 || !DungeonState.isInDungeon() || !DungeonMapScanner.isCalibrated()) {
            return;
        }
        clearTick = 0;
        List<MapPlayer> players = null;
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            int state = DungeonMapScanner.stateAt(group.mainIdx);
            Integer before = lastRoomState.put(group.mainIdx, state);
            if (before == null || before == state) {
                continue;
            }
            boolean wasOpen = before == DungeonMapScanner.STATE_UNDISCOVERED || before == DungeonMapScanner.STATE_DISCOVERED
                    || before == DungeonMapScanner.STATE_UNOPENED;
            boolean nowDone = state == DungeonMapScanner.STATE_CLEARED || state == DungeonMapScanner.STATE_GREEN;
            if (!wasOpen || !nowDone) {
                continue;
            }
            if (players == null) {
                players = playersCached(client);
            }
            Set<Integer> cells = new HashSet<>();
            for (int c : group.cells) {
                cells.add(c);
            }
            List<String> inside = new ArrayList<>();
            for (MapPlayer p : players) {
                int[] cell = LiveMapFeature.gridCellFor(new net.minecraft.world.phys.Vec3(p.worldX(), 70, p.worldZ()));
                if (cells.contains(cell[0] + cell[1] * LiveMapFeature.GRID)) {
                    inside.add(p.name());
                }
            }
            if (!inside.isEmpty()) {
                clearedBy.put(roomKey(group), inside);
            }
        }
    }

    static String roomKey(LiveMapFeature.RoomGroup group) {
        return group.entry != null && group.entry.name != null ? group.entry.name : "cell:" + group.mainIdx;
    }

    static List<String> clearedBy(LiveMapFeature.RoomGroup group) {
        return clearedBy.get(roomKey(group));
    }

    // ------------------------------------------------------------------------------------------- players

    /** One marker on the map: world position, heading, and how to draw it. No skin field any more - killer560,
     *  2026-09-20: "i do not want it showing the white heads for mobs", so every marker is always the arrow. */
    record MapPlayer(String name, double worldX, double worldZ, float yaw, boolean self, DungeonClass dungeonClass) {
    }

    private static List<MapPlayer> cachedPlayers = List.of();
    private static int cachedPlayersTick = Integer.MIN_VALUE;

    /** Per-tick cached {@link #players}, for anything that runs per frame. {@link #players} scans the whole party
     *  ({@code level.players()} plus a {@code getName().getString()} per member) and reads the map item's
     *  decorations; the fps report (2026-09-20) caught the HUD map doing that on every frame. Entity positions only
     *  move on a tick, so a per-tick snapshot is the same picture. */
    static List<MapPlayer> playersCached(Minecraft client) {
        int tick = LiveMapFeature.tickCount();
        if (tick != cachedPlayersTick) {
            cachedPlayersTick = tick;
            cachedPlayers = players(client);
        }
        return cachedPlayers;
    }

    /** Self first, then teammates: loaded player entities are exact; the rest come from the map item's markers in
     *  Hypixel's order (QUOI/NoammAddons assign non-self decorations to living teammates in tab order).
     *  killer560: "on the map it still shows heads for mobs/things besides teammates which it shouldn't." -
     *  {@link LeapMenuFeature#currentPartyMembers()} returns every {@code Player}-typed entity in the level
     *  except yourself, with no check at all; several dungeon mobs (and, on p3sim, other real people sharing
     *  the world) are also {@code Player}-typed entities, so the old "names.isEmpty() -> trust every one of
     *  them" bootstrap drew markers for them too whenever {@link PartyTracker#teammates()} had nothing yet
     *  (always, on p3sim - see {@link #isTeammate} below). Entities are now filtered through the same
     *  teammate test {@code teammates.TeammatesFeature.isTeammate} already uses (that feature is verified
     *  live), before anything is added to {@code entities}/{@code names}. */
    static List<MapPlayer> players(Minecraft client) {
        List<MapPlayer> out = new ArrayList<>();
        if (client.player == null || client.level == null) {
            return out;
        }
        String selfName = client.player.getGameProfile().name();
        out.add(new MapPlayer(selfName, client.player.getX(), client.player.getZ(), client.player.getYRot(), true,
                PartyTracker.selfClass()));

        List<String> party = PartyTracker.teammates();
        Map<String, Player> entities = new HashMap<>();
        for (Player p : client.level.players()) {
            if (p == client.player) {
                continue;
            }
            if (isTeammate(p, party)) {
                entities.put(p.getGameProfile().name().toLowerCase(Locale.US), p);
            }
        }
        Set<String> names = new LinkedHashSet<>(party);
        if (names.isEmpty()) {
            for (Player p : entities.values()) {
                names.add(p.getGameProfile().name());
            }
        }
        List<double[]> markers = new ArrayList<>();
        for (double[] m : DungeonMapScanner.playerMarkers(client)) {
            if (m[3] == 0) {
                markers.add(m);
            }
        }
        int markerIdx = 0;
        for (String name : names) {
            if (PartyTracker.isDead(name)) {
                continue;
            }
            double[] marker = markerIdx < markers.size() ? markers.get(markerIdx) : null;
            markerIdx++;
            Player entity = entities.get(name.toLowerCase(Locale.US));
            DungeonClass cls = PartyTracker.classOf(name);
            if (entity != null) {
                out.add(new MapPlayer(name, entity.getX(), entity.getZ(), entity.getYRot(), false, cls));
            } else if (marker != null) {
                out.add(new MapPlayer(name, marker[0], marker[1], (float) marker[2], false, cls));
            }
        }
        return out;
    }

    /** Same rule as {@code teammates.TeammatesFeature.isTeammate} (that class is not mine to import from - it's
     *  {@code private} there too, so this is a deliberate mirror, not a copy-paste accident): a real (v4-UUID)
     *  player the party tracker can vouch for. Rejects disguised-mob {@code Player} entities outright (a
     *  non-v4 UUID), which is exactly what let mobs onto the map before - {@link PartyTracker#teammates()}
     *  only ever lists real IGNs, so a mob could only get in via the raw {@code level.players()} scan. */
    private static boolean isTeammate(Player player, List<String> party) {
        if (player.getUUID().version() != 4) {
            return false;
        }
        String name = player.getGameProfile().name();
        for (String member : party) {
            if (member.equalsIgnoreCase(name)) {
                return true;
            }
        }
        if (PartyTracker.classOf(name) != null) {
            return true;
        }
        // Nothing known at all (p3sim.net's tab list has no class entries and there is no party) - in a real
        // dungeon the only other real players present are your party, so fall back to "every real player",
        // same reasoning teammates.TeammatesFeature uses for its own p3sim fallback.
        return party.isEmpty();
    }
}
