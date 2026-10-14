package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.OwnNameTag;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Misc > Visual > Own Name Tag ({@link OwnNameTag}): shows your own name tag in third person, which vanilla hides
 * because you're the camera entity, and draws the stats line one name-tag line above it, the same way vanilla draws
 * the scoreboard line under a name (so it looks and scales like a real name tag).
 */
@Mixin(AvatarRenderer.class)
public abstract class Sky2MOwnNameTagMixin {
    @ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("RETURN"))
    private boolean sky2m$showOwnName(boolean original, Avatar entity, double distanceSq) {
        return original || OwnNameTag.showName(entity);
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void sky2m$ownNameTagLine(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        // Render states are reused, so this is always set (null when there's no line).
        state.setData(OwnNameTag.LINE, state.nameTag == null ? null : OwnNameTag.line(entity));
    }

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("TAIL"))
    private void sky2m$submitOwnNameTagLine(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        Component line = state.getData(OwnNameTag.LINE);
        if (line == null || state.nameTag == null || state.nameTagAttachment == null) return;
        poseStack.pushPose();
        // One name tag line (vanilla's spacing) above the name, two when there's a scoreboard line under it.
        float step = 9 * 1.15f * 0.025f;
        poseStack.translate(0, step * (state.scoreText != null ? 2 : 1), 0);
        collector.submitNameTag(poseStack, state.nameTagAttachment, state.showExtraEars ? -10 : 0, line, !state.isDiscrete, state.lightCoords, camera);
        poseStack.popPose();
    }
}
