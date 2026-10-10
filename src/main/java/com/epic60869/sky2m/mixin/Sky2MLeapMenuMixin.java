package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.LeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws Sky2M's leap menu instead of the Spirit Leap chest and handles its clicks. */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MLeapMenuMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void sky2m$renderLeapMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!LeapMenu.isActive((AbstractContainerScreen<?>) (Object) this)) return;
        LeapMenu.render(graphics, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sky2m$clickLeapMenu(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        if (!LeapMenu.isActive(screen)) return;
        LeapMenu.click(screen, event.x(), event.y());
        cir.setReturnValue(true);
    }
}
