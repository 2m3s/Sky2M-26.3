package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
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

    @Inject(method = "getTextColor", at = @At("HEAD"), cancellable = true)
    private void sky2m$chroma(TextColor textColor, CallbackInfoReturnable<Integer> cir) {
        if (textColor == null) return;
        int value = textColor.getValue();
        if ((value & 0xFFFF00) != Sky2MNick.CHROMA_MARK) return;
        cir.setReturnValue(ARGB.color(ARGB.alpha(color), Sky2MNick.chroma((value & 0xFF) / 256f)));
    }
}
