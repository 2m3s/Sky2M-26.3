/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Lets the sim's tab list build a player-info packet from hand-made entries.
 *
 * <p>Every public constructor of {@link ClientboundPlayerInfoUpdatePacket} takes {@code ServerPlayer}s (javap,
 * 26.1.2 and 26.2 alike); the entry list is a private final field. Hypixel's tab list is eighty fake profiles
 * with display names, and the only way to send those from the integrated server is to build the packet with
 * no players and put the entries in afterwards. {@code SimTabList} is the only user.
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface SimPlayerInfoPacketAccessor {

    @Mutable
    @Accessor("entries")
    void killer560smod$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
