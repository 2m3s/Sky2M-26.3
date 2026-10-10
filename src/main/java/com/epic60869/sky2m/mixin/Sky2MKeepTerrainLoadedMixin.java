// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), mixin/KeepTerrainLoadedPacketMixin.java, LGPL-3.0.
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.KeepTerrainLoaded;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep Terrain Loaded: keep chunks the server unloads, and save the ones it sends. */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MKeepTerrainLoadedMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "handleForgetLevelChunk", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$keepTerrain(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        try {
            if (KeepTerrainLoaded.didRetain(this.level, packet.pos())) ci.cancel();
        } catch (Exception e) {
            System.err.println("[Sky2M] Keep Terrain Loaded: " + e);
        }
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER))
    private void sky2m$cacheTerrain(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        try {
            KeepTerrainLoaded.onServerChunk(this.level, packet);
        } catch (Exception e) {
            System.err.println("[Sky2M] Keep Terrain Loaded: " + e);
        }
    }
}
