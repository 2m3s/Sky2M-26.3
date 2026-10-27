package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Visual Tweaks > Fullbright. Ported from NoFrills' LightmapRenderStateExtractorMixin. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class Sky2MVisualLightmapMixin {
    /** 26.3 reads the ambient colour from the camera's environment attributes (26.2: ARGB.vector3fFromRGB24), so it is set afterwards. */
    @Inject(method = "extract", at = @At("RETURN"))
    private void sky2m$ambientLight(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        if (state.needsUpdate && VisualTweaks.ambientLight()) state.ambientColor = new Vector3f(1f, 1f, 1f);
    }

    @ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 0))
    private float sky2m$gamma(float original) {
        return VisualTweaks.gammaLight() ? 1600f : original;
    }
}
