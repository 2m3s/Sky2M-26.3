package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.SlotLocking;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slot Locking: Q does nothing while the selected hotbar slot is locked. On 26.3 the drop key goes through
 * MultiPlayerGameMode.dropItem (26.2's LocalPlayer.drop is gone).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class Sky2MDropMixin {
    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void sky2m$lockedDrop(LocalPlayer player, boolean all, CallbackInfo ci) {
        if (SlotLocking.blockDrop()) ci.cancel();
    }
}
