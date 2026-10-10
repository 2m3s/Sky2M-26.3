package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.CaseOpening;
import com.epic60869.sky2m.features.dungeons.OdinTerminals;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Case Opening and Terminals > Hide Menu: while they show, the chest's background texture isn't drawn either. */
@Mixin(ContainerScreen.class)
public abstract class Sky2MStorageOverlayBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideChestUnderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        var screen = (ContainerScreen) (Object) this;
        if (CaseOpening.hidesMenu(screen) || OdinTerminals.hidesMenu(screen)) ci.cancel();
    }
}
