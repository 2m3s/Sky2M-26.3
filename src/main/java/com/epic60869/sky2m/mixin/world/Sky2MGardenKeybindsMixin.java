package com.epic60869.sky2m.mixin.world;

import com.epic60869.sky2m.features.garden.GardenKeybinds;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Garden > Custom Keybinds: remapped key mappings report the custom key's state ({@link GardenKeybinds}). */
@Mixin(KeyMapping.class)
public abstract class Sky2MGardenKeybindsMixin {
    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void sky2m$isDown(CallbackInfoReturnable<Boolean> cir) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (GardenKeybinds.overrides(self)) cir.setReturnValue(GardenKeybinds.isDown(self));
    }

    @Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
    private void sky2m$consumeClick(CallbackInfoReturnable<Boolean> cir) {
        KeyMapping self = (KeyMapping) (Object) this;
        if (GardenKeybinds.overrides(self)) cir.setReturnValue(GardenKeybinds.consumeClick(self));
    }
}
