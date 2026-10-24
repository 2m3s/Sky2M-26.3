package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.playasmob.PlayAsMobRender;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
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
 * Draw the selected model's arm in first person while Minecraft continues to render and swing held items: in place of
 * the vanilla arm when the hand is empty, and reaching to the fist holding the item otherwise (vanilla draws no arm then).
 * Minecraft 26.3 draws first-person hands in FirstPersonHandsAndItemsRenderer (26.2: ItemInHandRenderer): the empty
 * hand is renderPlayerArm's renderPlayerHand call, and the held item is the ItemStackRenderState.submit call in
 * submitArmWithItem.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class Sky2MFirstPersonModelMixin {
    @WrapWithCondition(method = "renderPlayerArm",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderPlayerHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V"),
            require = 0)
    private boolean sky2m$customArm(FirstPersonHandsAndItemsRenderer renderer, PoseStack pose, SubmitNodeCollector collector,
                                    int light, HumanoidArm arm, PlayerRenderState player) {
        return !PlayAsMobRender.renderFirstPersonArm(arm, pose, collector, light);
    }

    /** Just before vanilla draws the held item (the equip, swing and use transforms are already on the pose). */
    @Inject(method = "submitArmWithItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
            require = 0)
    private void sky2m$armHoldingItem(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState hands, float frameInterp,
                                      float xRot, InteractionHand hand, float attack, ItemStack stack, float inverseArmHeight,
                                      PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        PlayAsMobRender.renderFirstPersonHeldArm(player, arm, pose, collector, light);
    }
}
