package com.epic60869.sky2m.mixin.chat;

import com.epic60869.sky2m.features.qol.ChatPeek;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Chat Peeking: draws the HUD's chat in the same mode as an open chat (full history, no fading) while the key is held. */
@Mixin(Hud.class)
public abstract class Sky2MChatPeekMixin {
    @ModifyArg(method = "extractChat", index = 5,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V"))
    private ChatComponent.DisplayMode sky2m$peek(ChatComponent.DisplayMode mode) {
        return ChatPeek.peeking() ? ChatComponent.DisplayMode.FOREGROUND : mode;
    }
}
