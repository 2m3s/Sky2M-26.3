package com.epic60869.sky2m.mixin.player;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Visual Tweaks > Armor Hider (only players; mobs and armor stands keep their armor). */
@Mixin(HumanoidArmorLayer.class)
public abstract class Sky2MVisualArmorMixin {
    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideArmor(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack, EquipmentSlot slot, int light, HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        var player = Minecraft.getInstance().player;
        boolean self = player != null && avatar.id == player.getId();
        if (VisualTweaks.hideArmor(slot, self)) ci.cancel();
    }
}
