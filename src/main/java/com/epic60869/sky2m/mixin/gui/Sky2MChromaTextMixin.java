package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.nick.Sky2MNick;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chroma nicknames: letters coloured {@link Sky2MNick#CHROMA_MARK} + their place in the name get the moving
 * rainbow colour when they're drawn, so the name animates everywhere text is drawn (chat, tab, nametags, screens).
 */
@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class Sky2MChromaTextMixin {
    @Shadow @Final private int color;
    @Shadow private float x;
    @Shadow private float y;

    @Inject(method = "getTextColor", at = @At("HEAD"), cancellable = true)
    private void sky2m$chroma(TextColor textColor, CallbackInfoReturnable<Integer> cir) {
        if (textColor == null) return;
        int value = textColor.getValue();
        int mark = value & 0xFFFF00;
        if (mark == Sky2MNick.CHROMA_MARK) {
            cir.setReturnValue(ARGB.color(ARGB.alpha(color), Sky2MNick.chroma((value & 0xFF) / 256f)));
        } else if (mark == Sky2MNick.CHROMA_SCREEN_MARK) {
            // x is where this glyph starts, so the hue moves smoothly letter by letter and line by line.
            cir.setReturnValue(ARGB.color(ARGB.alpha(color), Sky2MNick.screenChroma(x, y)));
        }
    }
}
