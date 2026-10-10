package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Random > Low Fire: lowers the burning overlay. The collector copies the pose, so pop at the end is safe. */
@Mixin(ScreenEffectRenderer.class)
public abstract class Sky2MLowFireMixin {
    private static boolean sky2m$pushed;

    @Inject(method = "submitFire", at = @At("HEAD"))
    private static void sky2m$lowerFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        Sky2MConfig config = Sky2MConfig.current();
        sky2m$pushed = config != null && config.misc.random.lowFire;
        if (!sky2m$pushed) return;
        poseStack.pushPose();
        poseStack.translate(0f, -config.misc.random.fireOffset, 0f);
    }

    @Inject(method = "submitFire", at = @At("TAIL"))
    private static void sky2m$restoreFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
        if (sky2m$pushed) poseStack.popPose();
        sky2m$pushed = false;
    }
}
