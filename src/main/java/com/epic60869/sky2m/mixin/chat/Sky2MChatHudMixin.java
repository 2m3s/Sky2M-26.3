package com.epic60869.sky2m.mixin.chat;

import com.epic60869.sky2m.chat.Sky2MChatCompactor;
import com.epic60869.sky2m.misc.Sky2MNopoFeatures;
import com.epic60869.sky2m.nick.Sky2MNick;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

@Mixin(ChatComponent.class)
public abstract class Sky2MChatHudMixin {
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow private void refreshTrimmedMessages() {}

    /*
     * On 26.3 every chat path (addClientSystemMessage, addServerSystemMessage,
     * addPlayerMessage) goes through the 4-argument addMessage, so one hook
     * covers server chat and Sky2M relay chat alike. (26.2's 1-argument
     * addMessage overload no longer exists.)
     */
    @ModifyVariable(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private Component sky2m$replaceFullChat(Component message) {
        return com.epic60869.sky2m.features.misc.chat.ChatTabs.timestamp(com.epic60869.sky2m.features.misc.items.ItemEmojis.replace(Sky2MNopoFeatures.replaceChatEmojis(Sky2MNick.replaceOtherNamesInChat(Sky2MNick.replaceOwnNameInChat(com.epic60869.sky2m.features.misc.social.ScreenshotShare.decorate(com.epic60869.sky2m.features.sbc.SbcItems.decoratePublicItems(message)))))));
    }

    /** The follow-up parts of an inventory shared with [inv] are remembered, not shown (the first part is the link). */
    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"), cancellable = true
    )
    private void sky2m$hideInventoryParts(Component message, MessageSignature signature,
                                             GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        if (com.epic60869.sky2m.features.sbc.SbcItems.recordInventoryParts(message)) ci.cancel();
    }

    @Inject(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("TAIL")
    )
    private void sky2m$compact(Component message, MessageSignature signature,
                                GuiMessageSource source, GuiMessageTag tag, CallbackInfo ci) {
        if (!Sky2MChatCompactor.enabled() || allMessages.isEmpty()) return;

        // The new message is already present at index 0. Find the most recent
        // matching message anywhere in the visible history instead of requiring
        // it to be directly adjacent.
        if (Sky2MChatCompactor.compact(allMessages)) {
            refreshTrimmedMessages();
        }
    }
}