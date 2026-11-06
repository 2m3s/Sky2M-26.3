package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.features.dungeons.extras.CroesusOverlay;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dungeon Extras > Croesus > Hide Claimed Runs: an inactive slot isn't drawn, hovered or clicked by the menu screen, so a
 * claimed run's slot acts like an empty one.
 */
@Mixin(Slot.class)
public abstract class Sky2MCroesusSlotMixin {
    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideClaimedRun(CallbackInfoReturnable<Boolean> cir) {
        if (CroesusOverlay.hideSlot((Slot) (Object) this)) cir.setReturnValue(false);
    }
}
