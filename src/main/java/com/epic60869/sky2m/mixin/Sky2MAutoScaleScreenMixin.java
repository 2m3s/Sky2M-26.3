package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.core.Sky2MAutoScale;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rescales before a screen opens, so a Sky2M screen lays itself out at {@link Sky2MAutoScale}'s scale. */
@Mixin(Gui.class)
public abstract class Sky2MAutoScaleScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void sky2m$autoScale(Screen screen, CallbackInfo ci) {
        Sky2MAutoScale.beforeScreen(screen);
    }
}
