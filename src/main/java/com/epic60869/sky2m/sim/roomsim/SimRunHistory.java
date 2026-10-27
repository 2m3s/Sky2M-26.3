/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;

import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * "Load a Previous Run", read out of the run history this mod already keeps.
 *
 * <p>killer560 (2026-09-28): "Won't the previous run portion be able to be filled from that tracker that shows
 * the map who you did the run with the splits of the run who did what secrets and all of that?" - and he is
 * right. {@link RunRecord} already stores a {@code MapSnapshot} with the room in every cell, the door in every
 * cell and the room names, next to the splits, the party and the secrets.
 *
 * <p>So this reads that rather than keeping a second store. An earlier version of this file wrote its own map
 * codes to their own folder, which meant two records of the same run that could disagree, and a "previous runs"
 * list that would have been empty for every floor he had already played. Reading the existing history means his
 * whole back catalogue is available immediately.
 *
 * <p>The one thing the snapshot does not carry is ROTATION - the dungeon map has no notion of it - so a replayed
 * floor has the right rooms in the right cells with the right doors, and each room in its default orientation.
 * That is said plainly rather than hidden, because a route practised against a wrongly-rotated room is worse
 * than no route.
 */
public final class SimRunHistory {

    private SimRunHistory() {
    }

    /** Saved runs the sim can rebuild. Sky2M's Run History doesn't keep the map, so there are none to offer. */
    public static synchronized List<String> savedRuns() {
        return List.of();
    }

    public static synchronized void load(Minecraft client, String label) {
        ModChat.send("Sim", ModChat.text("That run has no map saved with it."));
    }
}
