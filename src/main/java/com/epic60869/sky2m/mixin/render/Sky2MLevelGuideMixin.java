package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.qol.LevelGuide;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > SkyBlock Level Guide: red highlight behind unfinished tasks. */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MLevelGuideMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void sky2m$levelGuide(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        LevelGuide.renderSlot(graphics, slot);
    }
}
