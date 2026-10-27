package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.features.misc.items.CustomWardrobe;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws Sky2M's Custom Wardrobe instead of the Wardrobe chest and handles its clicks. */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MCustomWardrobeMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void sky2m$renderWardrobe(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (!CustomWardrobe.isActive(screen)) return;
        CustomWardrobe.render(screen, graphics, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sky2m$clickWardrobe(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (!CustomWardrobe.isActive(screen)) return;
        if (event.button() == 0) CustomWardrobe.click(screen, event.x(), event.y());
        cir.setReturnValue(true);
    }

    /** No slot is under the mouse in the custom wardrobe, so releases and drags do nothing either. */
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void sky2m$releaseWardrobe(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (CustomWardrobe.isActive((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }
}
