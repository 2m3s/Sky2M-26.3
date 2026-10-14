package com.epic60869.sky2m.sim.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.UUID;
import java.util.function.Consumer;

/** Name and UUID lookups for the sim's class overrides, from the tab list only (Killer560's asks Mojang too). */
public final class PlayerNames {

    private PlayerNames() {
    }

    public static UUID uuidFor(String name) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || name == null) {
            return null;
        }
        PlayerInfo info = connection.getPlayerInfo(name);
        return info == null ? null : info.getProfile().id();
    }

    public static String nameFor(UUID uuid) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || uuid == null) {
            return null;
        }
        PlayerInfo info = connection.getPlayerInfo(uuid);
        return info == null ? null : info.getProfile().name();
    }

    public static void resolveAsync(String name, Consumer<UUID> done) {
        UUID uuid = uuidFor(name);
        if (uuid != null) {
            done.accept(uuid);
        }
    }
}
