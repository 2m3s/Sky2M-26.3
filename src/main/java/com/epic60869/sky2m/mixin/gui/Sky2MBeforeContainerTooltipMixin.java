package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.features.misc.items.SackDisplay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Panels beside a menu that the hovered item's tooltip should cover (Sack Display): drawn just before the tooltip. */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MBeforeContainerTooltipMixin {
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V"))
    private void sky2m$beforeTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        SackDisplay.beforeTooltip(graphics, (AbstractContainerScreen<?>) (Object) this);
    }
}
