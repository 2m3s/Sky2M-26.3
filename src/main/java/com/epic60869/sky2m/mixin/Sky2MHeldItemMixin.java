// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), src/main/java/com/skysoft/mixin/ItemInHandRendererMixin.java.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.helditem.HeldItemSwing;
import com.epic60869.sky2m.features.helditem.HeldItemTransforms;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Misc > Held Item: the first-person item's transform and swing style. Minecraft 26.3 draws the first-person item in
 * FirstPersonHandsAndItemsRenderer.submitArmWithItem (ItemInHandRenderer.renderItem is gone), so the item's submit call
 * there is wrapped. The Held Item Update Fix is Sky2MHeldItemUpdateMixin.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class Sky2MHeldItemMixin {
    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
    private void sky2m$transformHeldItem(ItemStackRenderState state, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, int outline,
                                            Operation<Void> original, @Local(argsOnly = true) ItemStack stack) {
        pose.pushPose();
        try {
            HeldItemTransforms.apply(stack, pose);
        } catch (Throwable ignored) {}
        try {
            HeldItemSwing.apply(stack, pose);
        } catch (Throwable ignored) {}
        original.call(state, pose, collector, light, overlay, outline);
        pose.popPose();
    }

    @WrapMethod(method = "submitArmWithItem")
    private void sky2m$renderWithHeldItemSwing(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, float frameInterp,
                                                  float xRot, InteractionHand hand, float attack, ItemStack stack, float inverseArmHeight,
                                                  PoseStack pose, SubmitNodeCollector collector, int light, Operation<Void> original) {
        var local = Minecraft.getInstance().player;
        HumanoidArm mainArm = local == null ? HumanoidArm.RIGHT : local.getMainArm();
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
        HeldItemSwing.renderWithSwing(stack, attack, arm,
            () -> original.call(player, hands, frameInterp, xRot, hand, attack, stack, inverseArmHeight, pose, collector, light));
    }

    /** Swing In Place: the swing still turns the item, without its slide across the screen. */
    @WrapOperation(method = "swingArm", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
    private void sky2m$swingInPlace(PoseStack pose, float x, float y, float z, Operation<Void> original) {
        boolean skip;
        try {
            skip = HeldItemSwing.skipSwingSlide();
        } catch (Throwable e) {
            skip = false;
        }
        if (!skip) original.call(pose, x, y, z);
    }

    /** Swing style Item Only: the arm doesn't swing (the item does, when it's submitted). */
    @Inject(method = "swingArm", at = @At("HEAD"), cancellable = true)
    private void sky2m$replaceHeldItemSwing(float attack, PoseStack pose, int invert, HumanoidArm arm, CallbackInfo ci) {
        boolean replaced;
        try {
            replaced = HeldItemSwing.replaceVanillaSwing();
        } catch (Throwable e) {
            replaced = false;
        }
        if (replaced) ci.cancel();
    }
}
