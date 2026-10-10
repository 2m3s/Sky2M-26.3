package com.epic60869.sky2m.sb.utils;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

public interface Resettable extends ClientPlayConnectionEvents.Join {
	void reset();

	@Override
	default void onPlayReady(ClientPacketListener handler, PacketSender sender, Minecraft client) {
		reset();
	}
}
