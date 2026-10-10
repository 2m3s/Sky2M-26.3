package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.slayer.BlazeSlayer;
import com.epic60869.sky2m.features.slayer.EndermanSlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slayers > Enderman and Blaze: hides particles around Voidgloom bosses (Hide Particles) and near Inferno Demonlords
 * (Clear View), and reads Hypixel's dagger attunement titles (Blaze Daggers), hiding them while the dagger HUD shows.
 */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MSlayerPacketsMixin {
    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$slayerParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        // Mayors > Diana > Burrows reads burrow, arrow and spade particles (never hides them).
        com.epic60869.sky2m.features.combat.DianaBurrows.onParticle(packet);
        com.epic60869.sky2m.features.combat.DianaRareMobs.onParticle(packet);
        if (BlazeSlayer.hideParticle() || EndermanSlayer.hideParticle(packet)) ci.cancel();
    }

    @Inject(method = "setTitleText", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$slayerTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        if (BlazeSlayer.onTitle(packet.text())) ci.cancel();
    }
}
