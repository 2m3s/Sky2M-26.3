// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), mixin/ServerInfoPacketMixin.java, LGPL-3.0.
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.ServerInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server's time packets (for TPS) and pongs (for ping), for the Server Info Display and !tps / !ping. */
@Mixin(ClientPacketListener.class)
public class Sky2MServerInfoPacketMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void sky2m$recordServerTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerInfo.onServerTime(packet.gameTime(), System.nanoTime());
    }

    @Inject(method = "handlePongResponse", at = @At("TAIL"))
    private void sky2m$recordPong(ClientboundPongResponsePacket packet, CallbackInfo ci) {
        long receivedAt = System.nanoTime();
        net.minecraft.client.Minecraft.getInstance().execute(() -> ServerInfo.onPong(packet.time(), receivedAt));
    }
}
