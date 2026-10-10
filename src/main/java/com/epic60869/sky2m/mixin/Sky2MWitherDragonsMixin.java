package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.WitherDragons;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dungeons > M7 Dragons and Relics: dragon spawn particles and dragon health updates (WitherDragons). */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MWitherDragonsMixin {
    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
    private void sky2m$dragonParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        WitherDragons.onParticle(packet);
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void sky2m$dragonData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        WitherDragons.onEntityData(packet.id());
    }
}
