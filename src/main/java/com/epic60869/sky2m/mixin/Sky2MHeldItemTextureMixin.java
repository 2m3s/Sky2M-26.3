// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), src/main/java/com/skysoft/mixin/ItemModelResolverMixin.java.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.helditem.HeldItemTextures;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Misc > Held Item: items set to their vanilla texture are drawn with the vanilla model instead of Hypixel's. */
@Mixin(ItemModelResolver.class)
public abstract class Sky2MHeldItemTextureMixin {
    @ModifyVariable(method = "updateForTopItem", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private ItemStack sky2m$replaceItemTexture(ItemStack stack) {
        try {
            return HeldItemTextures.renderStack(stack);
        } catch (Throwable e) {
            return stack;
        }
    }
}
