package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.SlotLocking;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Slot Locking: Q does nothing while the selected hotbar slot is locked. */
@Mixin(LocalPlayer.class)
public abstract class Sky2MDropMixin {
    @Inject(method = "drop", at = @At("HEAD"), cancellable = true)
    private void sky2m$lockedDrop(boolean all, CallbackInfoReturnable<Boolean> cir) {
        if (SlotLocking.blockDrop()) cir.setReturnValue(false);
    }
}
