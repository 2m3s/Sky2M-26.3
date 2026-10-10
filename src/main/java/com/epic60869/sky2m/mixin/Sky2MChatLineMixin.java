package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.CopyChat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Copy Chat: records where each chat line is really drawn while chat is open (vanilla's own layout, including any
 * chat scale, spacing or position another mod applies), so copying picks the line that's under the mouse.
 */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent$DrawingFocusedGraphicsAccess")
public abstract class Sky2MChatLineMixin {
    @Shadow @Final private GuiGraphicsExtractor graphics;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sky2m$newFrame(CallbackInfo ci) {
        CopyChat.beginFrame();
        com.epic60869.sky2m.features.sbc.SbcChatOverlay.beginFrame();
    }

    @Inject(method = "handleMessage", at = @At("HEAD"))
    private void sky2m$recordLine(int textTop, float opacity, FormattedCharSequence content, CallbackInfoReturnable<Boolean> cir) {
        Vector2f top = graphics.pose().transformPosition(new Vector2f(0, textTop));
        Vector2f bottom = graphics.pose().transformPosition(new Vector2f(0, textTop + 9));
        CopyChat.recordLine(content, top.y, bottom.y);
        com.epic60869.sky2m.features.sbc.SbcChatOverlay.recordLine(content, top.y, bottom.y);
        com.epic60869.sky2m.features.sbc.SbcChatOverlay.drawLineBackground(graphics, textTop, opacity, content);
    }

    /** Item emojis: their icons go into the gaps left for them in the line. */
    @Inject(method = "handleMessage", at = @At("TAIL"))
    private void sky2m$itemEmojis(int textTop, float opacity, FormattedCharSequence content, CallbackInfoReturnable<Boolean> cir) {
        com.epic60869.sky2m.features.misc.ItemEmojis.drawChatIcons(graphics, content, textTop, opacity);
    }
}
