package com.epic60869.sky2m.mixin.world;

import com.epic60869.sky2m.features.dungeons.MageBeam;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dungeons > Custom Mage Beam: reads the beam's firework particles, and hides them if asked (MageBeam). */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MMageBeamMixin {
    @Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
    private void sky2m$mageBeam(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (MageBeam.onParticle(packet)) ci.cancel();
    }
}
