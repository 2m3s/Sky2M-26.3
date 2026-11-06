package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.features.dungeons.extras.CroesusOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dungeon Extras > Croesus > Hide Claimed Runs: a claimed run's item isn't drawn. Only the drawing is skipped, so its slot
 * still shows its highlight and tooltip when hovered and can be clicked, to use a Dungeon Chest Key on it.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MCroesusSlotMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideClaimedRun(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (CroesusOverlay.hideSlot(slot)) ci.cancel();
    }
}
