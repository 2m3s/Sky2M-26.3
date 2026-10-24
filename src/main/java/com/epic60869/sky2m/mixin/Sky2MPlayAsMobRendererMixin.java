package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.playasmob.CustomModels;
import com.epic60869.sky2m.features.misc.playasmob.PlayAsMobRender;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Misc > Play as Mob inside {@code LivingEntityRenderer.submit}: Player Size on mobs (right after the
 * renderer's own {@code scale}, where Sky2MPlayerSizeMixin stretches the player), and an imported model drawn in place
 * of your player model, with the layers on it (armour, held items, cape) left out; CustomModels draws the held items in
 * the model's own hands.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class Sky2MPlayAsMobRendererMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;scale(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V", shift = At.Shift.AFTER),
            require = 0)
    private void sky2m$mobSize(LivingEntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        PlayAsMobRender.applySize(state, pose);
    }

    @WrapWithCondition(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/UvMapping;I)V"),
            require = 0)
    private boolean sky2m$customModel(SubmitNodeCollector collector, Model<?> model, Object state, PoseStack pose, RenderType type,
                                      int light, int overlay, int tint, UvMapping uvMapping, int outline) {
        PlayAsMobRender.Drawn custom = PlayAsMobRender.modelFor(state);
        if (custom == null) return true;
        try {
            CustomModels.draw(custom.model(), custom.placement(), (AvatarRenderState) state, pose, collector, light, overlay);
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    @WrapWithCondition(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"),
            require = 0)
    private boolean sky2m$hideLayers(RenderLayer<?, ?> layer, PoseStack pose, SubmitNodeCollector collector, int light,
                                     EntityRenderState state, float yRot, float xRot) {
        return PlayAsMobRender.modelFor(state) == null;
    }
}
