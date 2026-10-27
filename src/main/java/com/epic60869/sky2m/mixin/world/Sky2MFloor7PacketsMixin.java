package com.epic60869.sky2m.mixin.world;

import com.epic60869.sky2m.features.dungeons.F7Extras;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeons > Floor 7 > F7 Extras: reads (and can hide) the F7/M7 boss titles and subtitles, and reads the sounds that
 * mark Necron's death, arrows hitting a dragon and Hypixel's terminal click.
 */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MFloor7PacketsMixin {
    @Inject(method = "setTitleText", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$f7Title(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        if (F7Extras.onTitle(packet.text())) ci.cancel();
    }

    @Inject(method = "setSubtitleText", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$f7Subtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        if (F7Extras.onSubtitle(packet.text())) ci.cancel();
    }

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$f7Sound(ClientboundSoundPacket packet, CallbackInfo ci) {
        String name = packet.getSound().value().location().getPath();
        // Dungeons > Dungeon Extras: Spring Boots charge sounds and Clean End's muted boss music.
        com.epic60869.sky2m.features.dungeons.extras.DungeonExtras.onSound(name, packet.getPitch());
        if (com.epic60869.sky2m.features.dungeons.extras.DungeonExtras.cancelSound(name)) ci.cancel();
        if (F7Extras.onSound(name, packet.getVolume(), packet.getPitch())) ci.cancel();
    }
}
