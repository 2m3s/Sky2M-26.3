package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.ItemEmojis;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Item emojis in chat while it's closed (the HUD chat): their icons go into the gaps left for them. */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingBackgroundGraphicsAccess")
public abstract class Sky2MChatLineBackgroundMixin {
    @Shadow @Final private GuiGraphicsExtractor graphics;

    /** SBC mentions: the highlight behind the line. */
    @Inject(method = "handleMessage", at = @At("HEAD"))
    private void sky2m$mentionHighlight(int textTop, float opacity, FormattedCharSequence content, CallbackInfoReturnable<Boolean> cir) {
        com.epic60869.sky2m.features.sbc.SbcChatOverlay.drawLineBackground(graphics, textTop, opacity, content);
    }

    @Inject(method = "handleMessage", at = @At("TAIL"))
    private void sky2m$itemEmojis(int textTop, float opacity, FormattedCharSequence content, CallbackInfoReturnable<Boolean> cir) {
        ItemEmojis.drawChatIcons(graphics, content, textTop, opacity);
    }
}
