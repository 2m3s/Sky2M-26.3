package com.epic60869.sky2m.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Minecraft 26.3 only. MoulConfig's keybind editor saves a clicked mouse button as its plain number (0 left, 1 right,
 * ...). On 26.2 keys start at 32, but 26.3's SDL keys A-F are 4-9, so a mouse bind and a key could be the same number
 * and A-F read as mouse buttons. Saved here as -100 + the button instead, a range no key uses (see Input).
 */
@Mixin(targets = "io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorKeybind$1", remap = false)
public abstract class Sky2MKeybindEditorMixin {
    // The first set() in mouseEvent is the click that picks a mouse button; the second resets to the default.
    @ModifyArg(method = "mouseEvent", at = @At(value = "INVOKE",
        target = "Lio/github/notenoughupdates/moulconfig/processor/ProcessedOption;set(Ljava/lang/Object;)Z", ordinal = 0), require = 1)
    private Object sky2m$storeMouseBind(Object value) {
        return value instanceof Integer button && button >= 0 && button <= 9 ? -100 + button : value;
    }
}
