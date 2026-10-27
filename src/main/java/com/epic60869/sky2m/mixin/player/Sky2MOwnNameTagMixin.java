package com.epic60869.sky2m.mixin.player;

import com.epic60869.sky2m.features.misc.visual.OwnNameTag;
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
        state.setData(OwnNameTag.BOARD_LINE, state.nameTag == null ? null : OwnNameTag.boardLine(entity));
    }

    @Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("TAIL"))
    private void sky2m$submitOwnNameTagLine(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (state.nameTag == null || state.nameTagAttachment == null) return;
        // Bottom to top: the name (and a scoreboard line under it), the stats line, then the leaderboard line.
        int lines = state.scoreText != null ? 1 : 0;
        Component stats = state.getData(OwnNameTag.LINE);
        if (stats != null) submitLine(state, poseStack, collector, camera, stats, ++lines);
        Component board = state.getData(OwnNameTag.BOARD_LINE);
        if (board != null) submitLine(state, poseStack, collector, camera, board, ++lines);
    }

    /** One line {@code linesUp} name-tag lines (vanilla's spacing) above the name. */
    private static void submitLine(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
                                   Component line, int linesUp) {
        poseStack.pushPose();
        float step = 9 * 1.15f * 0.025f;
        poseStack.translate(0, step * linesUp, 0);
        collector.submitNameTag(poseStack, state.nameTagAttachment, state.showExtraEars ? -10 : 0, line, !state.isDiscrete, state.lightCoords, camera);
        poseStack.popPose();
    }
}
