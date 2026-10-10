// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), src/main/java/com/skysoft/mixin/MultiPlayerGameModeMixin.java
// (isSkysoftSameDestroyTargetAfterItemUpdate).
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.helditem.HeldItemTextures;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Held Item Update Fix: Hypixel updating your tool while you mine doesn't restart breaking the block. */
@Mixin(MultiPlayerGameMode.class)
public abstract class Sky2MHeldItemMiningMixin {
    @WrapOperation(method = "sameDestroyTarget", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;isSameItemSameComponents(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean sky2m$sameDestroyTargetAfterItemUpdate(ItemStack current, ItemStack previous, Operation<Boolean> original) {
        if (original.call(current, previous)) return true;
        try {
            return HeldItemTextures.shouldPreserveUpdate(previous, current);
        } catch (Throwable e) {
            return false;
        }
    }
}
