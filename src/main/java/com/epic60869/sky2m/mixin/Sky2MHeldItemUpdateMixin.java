// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), src/main/java/com/skysoft/mixin/ItemInHandRendererMixin.java.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.helditem.HeldItemTextures;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Held Item Update Fix: Hypixel updating the same item doesn't play the re-equip animation (26.3 moved this check here). */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class Sky2MHeldItemUpdateMixin {
    @ModifyReturnValue(method = "shouldInstantlyReplaceVisibleItem", at = @At("RETURN"))
    private boolean sky2m$keepUpdatedItemVisible(boolean original, ItemStack currentlyVisible, ItemStack expected, LocalPlayer player) {
        if (original) return true;
        try {
            return HeldItemTextures.shouldPreserveUpdate(currentlyVisible, expected);
        } catch (Throwable e) {
            return false;
        }
    }
}
