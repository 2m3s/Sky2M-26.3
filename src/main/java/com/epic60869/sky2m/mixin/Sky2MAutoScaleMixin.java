package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.core.Sky2MAutoScale;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** GUI scale Auto: one step smaller while a Sky2M screen is open ({@link Sky2MAutoScale}). */
@Mixin(Minecraft.class)
public abstract class Sky2MAutoScaleMixin {
    @ModifyExpressionValue(method = "resizeGui", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;calculateScale(IZ)I"))
    private int sky2m$autoScale(int scale) {
        return Sky2MAutoScale.adjust(scale);
    }
}
