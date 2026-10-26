package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.CustomWardrobe;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With the Custom Wardrobe up the Wardrobe chest's texture isn't drawn behind it (Sky2MCustomWardrobeMixin draws the rest). */
@Mixin(ContainerScreen.class)
public abstract class Sky2MCustomWardrobeBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideWardrobeChest(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (CustomWardrobe.isActive((ContainerScreen) (Object) this)) ci.cancel();
    }
}
