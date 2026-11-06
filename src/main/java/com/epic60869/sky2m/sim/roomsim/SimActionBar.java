/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hypixel's action bar, sent by the sim's server: health, defense, the room's "x/y Secrets" and mana.
 *
 * <p>Found 2026-10-05 writing the Auto Routes tests: the sim never sent this line, so everything that reads it saw
 * nothing - an Auto Routes {@code await:<n>} node waited forever in the sim (only secret bats counted), and the live
 * map never learnt a room's found count. Client features read it exactly as they do on Hypixel; nothing on the client
 * special-cases the sim.
 *
 * <p>Until the stats arrived only the Secrets part was sent, and only in a room with secrets. Now the whole line
 * goes out every few ticks, with the health, defense and mana {@link SimVitals} keeps, in Hypixel's own shape (see
 * {@link SimVitals#actionBar}) so Status Bars and Low Stats read it too; the Secrets part is still only there in a
 * room whose database entry has secrets.
 */
public final class SimActionBar {

    /** Every quarter second, so a hit or a cast shows almost at once. Hypixel's own is about as often. */
    private static final int EVERY_TICKS = 5;
    private static int tick;

    private SimActionBar() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SimActionBar::serverTick);
    }

    private static void serverTick(MinecraftServer server) {
        if (++tick % EVERY_TICKS != 0) {
            return;
        }
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!SimAbilities.simServer(sp)) {
                return;
            }
            sp.sendSystemMessage(Component.literal(SimVitals.actionBar(sp, secrets(sp))), true);
        }
    }

    /** "§77/9 Secrets" for the room he is in, or null where there are none to count. */
    private static String secrets(ServerPlayer sp) {
        String room = SimScore.roomAt(sp.blockPosition());
        if (room == null) {
            return null;
        }
        com.epic60869.sky2m.sim.roomdatabase.RoomEntry entry =
                com.epic60869.sky2m.sim.roomdatabase.RoomDatabase.lookupByName(room);
        if (entry == null || entry.secrets <= 0) {
            return null;
        }
        return "§7" + SimScore.foundInRoom(room) + "/" + entry.secrets + " Secrets";
    }
}
