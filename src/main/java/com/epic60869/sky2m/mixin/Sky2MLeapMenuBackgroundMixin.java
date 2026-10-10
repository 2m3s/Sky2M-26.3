package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.LeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The chest's texture is drawn as the screen's background, apart from its slots (Sky2MLeapMenuMixin): with the leap
 * menu up only the dark backdrop is drawn, so the Spirit Leap chest doesn't show behind it.
 */
@Mixin(ContainerScreen.class)
public abstract class Sky2MLeapMenuBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideLeapChest(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ContainerScreen screen = (ContainerScreen) (Object) this;
        if (!LeapMenu.isActive(screen)) return;
        graphics.fill(0, 0, screen.width, screen.height, 0x90000000);
        ci.cancel();
    }
}
