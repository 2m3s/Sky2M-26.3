package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Visual Tweaks > Hide Fire Overlay. */
@Mixin(value = ScreenEffectRenderer.class, priority = 500)
public abstract class Sky2MVisualFireMixin {
    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void sky2m$noFireOverlay(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (VisualTweaks.hideFireOverlay()) ci.cancel();
    }
}
