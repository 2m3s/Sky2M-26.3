package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.custom.util.Input;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;
import io.github.notenoughupdates.moulconfig.platform.ModernKeybindHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 26.3 only. The names MoulConfig's keybind settings show: its 26.3 build called every bind 0-9 "Button N",
 * which on SDL includes the keys A-F (B showed as "Button 6"). Mouse binds are -100 + the button now (Input).
 */
@Mixin(value = ModernKeybindHelper.class, remap = false)
public abstract class Sky2MKeybindNameMixin {
    @Inject(method = "getKeyName", at = @At("HEAD"), cancellable = true, require = 1)
    private static void sky2m$name(int keyCode, CallbackInfoReturnable<StructuredText> cir) {
        // Keys keep MoulConfig's own name (it reads Minecraft's); only unset and mouse binds are named here.
        if (keyCode == 0 || keyCode == -1 || Input.bindMouseButton(keyCode) >= 0) {
            cir.setReturnValue(StructuredText.of(Input.bindName(keyCode)));
        } else if (keyCode >= 1 && keyCode <= 9) {
            cir.setReturnValue(StructuredText.of(Input.keyName(keyCode)));
        }
    }
}
