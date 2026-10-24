package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Misc > Visual Tweaks > Particle Hider: particles of hidden types are never created (vanilla handles a null result). */
@Mixin(ParticleEngine.class)
public abstract class Sky2MVisualParticleMixin {
    @Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideParticle(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
        if (VisualTweaks.hideParticle(options)) cir.setReturnValue(null);
    }
}
