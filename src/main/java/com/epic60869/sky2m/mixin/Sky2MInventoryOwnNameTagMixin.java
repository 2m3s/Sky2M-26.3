package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.OwnNameTag;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Own Name Tag ({@link OwnNameTag}): keeps your name tag, stats and leaderboard lines off the inventory's player preview in F5. */
@Mixin(InventoryScreen.class)
public abstract class Sky2MInventoryOwnNameTagMixin {
    @ModifyReturnValue(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;", at = @At("RETURN"))
    private static EntityRenderState sky2m$noOwnNameTag(EntityRenderState state, LivingEntity entity) {
        if (entity == Minecraft.getInstance().player) {
            state.nameTag = null;
            state.setData(OwnNameTag.LINE, null);
            state.setData(OwnNameTag.BOARD_LINE, null);
        }
        return state;
    }
}
