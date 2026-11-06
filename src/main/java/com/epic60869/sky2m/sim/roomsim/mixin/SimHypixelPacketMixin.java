package com.epic60869.sky2m.sim.roomsim.mixin;

import com.epic60869.sky2m.sim.roomsim.SimState;
import com.epic60869.sky2m.sim.relay.SimRelay;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the Hypixel Mod API's packets ({@code hypixel:party_info} and the rest) off a Dungeon Sim party connection.
 *
 * <p>Joining a party straight from Hypixel, the Hypixel Mod API mod sends its requests again on the new connection.
 * The host's server cannot decode them (the mod only writes the serverbound side) and drops the guest with
 * "Failed to decode packet 'serverbound/minecraft:custom_payload' (hypixel:party_info)". Nobody in a sim party
 * answers them anyway, so they are not sent there.
 */
@Mixin(Connection.class)
public abstract class SimHypixelPacketMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void sky2m$dropHypixelPackets(Packet<?> packet, ChannelFutureListener listener, boolean flush,
            CallbackInfo ci) {
        if (packet instanceof ServerboundCustomPayloadPacket custom
                && "hypixel".equals(custom.payload().type().id().getNamespace()) && toSimParty()) {
            ci.cancel();
        }
    }

    private static boolean toSimParty() {
        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        return SimRelay.isJoined() || SimState.isGuest(mc)
                || server != null && "Dungeon Sim party".equals(server.name);
    }
}
