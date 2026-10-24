package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Misc > Visual Tweaks > Remove Fog / Fog Distance: pushes the fog distances out. */
@Mixin(FogRenderer.class)
public abstract class Sky2MVisualFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void sky2m$fogDistance(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkenWorld, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        float factor = VisualTweaks.fogFactor();
        FogData fog = cir.getReturnValue();
        if (factor <= 1f || fog == null) return;
        fog.renderDistanceStart *= factor;
        fog.renderDistanceEnd *= factor;
        fog.environmentalStart *= factor;
        fog.environmentalEnd *= factor;
        fog.skyEnd *= factor;
        fog.cloudEnd *= factor;
    }
}
