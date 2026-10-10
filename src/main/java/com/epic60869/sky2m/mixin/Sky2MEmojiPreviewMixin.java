package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.ItemEmojis;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Emoji autocomplete: each emoji suggestion shows its picture before its name. */
@Mixin(targets = "net.minecraft.client.gui.components.CommandSuggestions$SuggestionsList")
public abstract class Sky2MEmojiPreviewMixin {
    @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
    private void sky2m$emojiPreview(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color, Operation<Void> original) {
        if (!ItemEmojis.isEmojiSuggestion(text)) {
            original.call(graphics, font, text, x, y, color);
            return;
        }
        ItemEmojis.drawPreview(graphics, text, x, y - 1);
        original.call(graphics, font, text, x + ItemEmojis.PREVIEW_WIDTH, y, color);
    }
}
