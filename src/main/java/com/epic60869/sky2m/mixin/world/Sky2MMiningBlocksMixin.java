package com.epic60869.sky2m.mixin.world;

import com.epic60869.sky2m.features.mining.dwarven.PrecisionMining;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mining > Precision Mining & Flowstate: tells the Flowstate helper about blocks the server changes (single and
 * batched updates) before the client applies them, so it can see which block was mined.
 */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MMiningBlocksMixin {
    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), require = 0)
    private void sky2m$miningBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        PrecisionMining.onBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), require = 0)
    private void sky2m$miningBlocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates(PrecisionMining::onBlockUpdate);
    }
}
