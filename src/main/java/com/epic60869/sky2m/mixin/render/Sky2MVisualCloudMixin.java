package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Misc > Visual Tweaks > Hide Clouds: skips drawing the clouds. 26.2 cancels LevelRenderer.addCloudsPass; 26.3 has no
 * cloud pass, so both of CloudRenderer's draw calls (normal and order-independent transparency) are cancelled instead.
 */
@Mixin(CloudRenderer.class)
public abstract class Sky2MVisualCloudMixin {
    @Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true)
    private void sky2m$noClouds(CloudStatus status, com.mojang.renderpearl.api.commands.RenderPass pass, CallbackInfo ci) {
        if (VisualTweaks.hideClouds()) ci.cancel();
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void sky2m$noCloudsOit(CloudStatus status, net.minecraft.client.renderer.oit.OitStage stage,
                                   com.mojang.renderpearl.api.textures.GpuTextureView target,
                                   net.minecraft.client.renderer.oit.OitRenderPassProvider.Parameters parameters, CallbackInfo ci) {
        if (VisualTweaks.hideClouds()) ci.cancel();
    }
}
