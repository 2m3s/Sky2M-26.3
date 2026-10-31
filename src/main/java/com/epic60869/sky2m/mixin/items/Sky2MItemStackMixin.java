package com.epic60869.sky2m.mixin.items;

import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Custom item names, as in Skyblocker's ItemStackMixin (LGPL-3.0). */
@Mixin(ItemStack.class)
public abstract class Sky2MItemStackMixin {
    @ModifyReturnValue(method = "getHoverName", at = @At("RETURN"))
    private Component sky2m$customItemNames(Component original) {
        if (Compat.isOnSkyblock() && !Compat.bypassCustomNames) {
            // getHoverName is asked for all the time (every slot, tooltip and tick feature): nothing renamed, no lookup.
            var names = CustomConfigManager.get().general.customItemNames;
            Component custom = names.isEmpty() ? null : names.get(Compat.uuid((ItemStack) (Object) this));
            return custom != null ? custom : com.epic60869.sky2m.features.misc.items.RevertMasterStars.apply(original);
        }

        return original;
    }
}
