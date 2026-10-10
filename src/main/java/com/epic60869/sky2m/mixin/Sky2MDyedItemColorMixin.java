package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.custom.CustomArmorAnimatedDyes;
import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.util.ARGB;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ported from Skyblocker's DyedItemColorMixin (LGPL-3.0). */
@Mixin(DyedItemColor.class)
public class Sky2MDyedItemColorMixin {

    @ModifyReturnValue(method = "getOrDefault", at = @At("RETURN"))
    private static int sky2m$customDyeColor(int originalColor, ItemStack stack, int defaultColor) {
        if (Compat.isOnSkyblock()) {
            String itemUuid = Compat.uuid(stack);
            CustomConfigManager.GeneralConfig general = CustomConfigManager.get().general;

            if (general.customAnimatedDyes.containsKey(itemUuid)) {
                return ARGB.opaque(CustomArmorAnimatedDyes.animateColorTransition(general.customAnimatedDyes.get(itemUuid)));
            }

            if (general.customDyeColors.containsKey(itemUuid)) {
                return ARGB.opaque(general.customDyeColors.getInt(itemUuid));
            }
        }

        return originalColor;
    }
}
