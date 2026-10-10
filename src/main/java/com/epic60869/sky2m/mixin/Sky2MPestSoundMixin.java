package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.garden.PestTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the Pest Spawn Sound setting keep, mute or replace Hypixel's pest spawn sound (SkyHanni's PestSpawnSound), and
 * mutes the nether portal sound of OVERFLOW! drops.
 */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MPestSoundMixin {
    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String name = packet.getSound().value().location().getPath();
        double distance = Math.sqrt(mc.player.distanceToSqr(packet.getX(), packet.getY(), packet.getZ()));
        if (PestTimer.onPlaySound(name, packet.getVolume(), packet.getPitch(), distance)) ci.cancel();
        else if (com.epic60869.sky2m.features.garden.OverflowDropSound.onPlaySound(name)) ci.cancel();
    }

    /** Sounds played on an entity (you) rather than at a position. */
    @Inject(method = "handleSoundEntityEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$onEntitySound(net.minecraft.network.protocol.game.ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        String name = packet.getSound().value().location().getPath();
        if (com.epic60869.sky2m.features.garden.OverflowDropSound.onPlaySound(name)) ci.cancel();
    }
}
