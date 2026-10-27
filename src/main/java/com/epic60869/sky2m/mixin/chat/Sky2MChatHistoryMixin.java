package com.epic60869.sky2m.mixin.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chat > Longer Chat History: the chat keeps Chat History Lines lines (1000 by default) instead of Minecraft's 100,
 * both the messages and the wrapped lines drawn from them.
 */
@Mixin(ChatComponent.class)
public abstract class Sky2MChatHistoryMixin {
    @ModifyExpressionValue(method = {"addMessageToDisplayQueue", "addMessageToQueue"},
        at = @At(value = "CONSTANT", args = "intValue=100"))
    private int sky2m$historyLines(int vanilla) {
        return limit(vanilla);
    }

    private static int limit(int vanilla) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.chat.longerChatHistory) return vanilla;
        return Math.max(vanilla, config.chat.chatHistoryLines);
    }
}
