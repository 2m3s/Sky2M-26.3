package com.epic60869.sky2m.mixin.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat > Keep Chat Scroll Position. Vanilla already moves the scroll up by one for every new line while you are
 * scrolled up, but whenever the chat is rebuilt (the chat compactor merging a repeat, chat tabs, search, Stash
 * Messages, Sky2M chat edits) every line counts as new again, so the view jumped to the top or somewhere random. Here a
 * rebuild remembers which messages were on screen and puts the scroll back on the same ones afterwards. Scrolling down
 * yourself, closing chat and sending a message still go back to the bottom (vanilla resets the scroll there).
 */
@Mixin(ChatComponent.class)
public abstract class Sky2MChatScrollMixin {
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;
    @Shadow private boolean newMessageSinceScroll;

    @Shadow public abstract void resetChatScroll();
    @Shadow public abstract void scrollChat(int lines);

    /** True while refreshTrimmedMessages re-adds every message. */
    @Unique private boolean sky2m$rebuilding;
    /** The messages on screen before the rebuild, bottom first, and how far each one's newest line was from the scroll. */
    @Unique private final List<GuiMessage> sky2m$anchors = new ArrayList<>();
    @Unique private final List<Integer> sky2m$offsets = new ArrayList<>();

    @Unique
    private static boolean sky2m$enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.chat != null && c.chat.keepScrollPosition;
    }

    @Inject(method = "refreshTrimmedMessages", at = @At("HEAD"))
    private void sky2m$rememberView(CallbackInfo ci) {
        sky2m$anchors.clear();
        sky2m$offsets.clear();
        if (!sky2m$enabled() || chatScrollbarPos <= 0) return;
        sky2m$rebuilding = true;
        // Where each message's newest (lowest) line is, then the messages from the bottom of the view upwards.
        Map<GuiMessage, Integer> first = new IdentityHashMap<>();
        int end = Math.min(trimmedMessages.size(), chatScrollbarPos + 100);
        for (int i = 0; i < end; i++) first.putIfAbsent(trimmedMessages.get(i).parent(), i);
        for (int i = chatScrollbarPos; i < end; i++) {
            GuiMessage parent = trimmedMessages.get(i).parent();
            if (sky2m$anchors.contains(parent)) continue;
            sky2m$anchors.add(parent);
            sky2m$offsets.add(first.get(parent) - chatScrollbarPos);
        }
    }

    /** While rebuilding, re-added lines aren't new: don't scroll for them (vanilla does when chat is open). */
    @WrapWithCondition(
        method = "addMessageToDisplayQueue",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;scrollChat(I)V")
    )
    private boolean sky2m$noScrollWhileRebuilding(ChatComponent chat, int lines) {
        return !sky2m$rebuilding;
    }

    /**
     * Vanilla only keeps your place for new lines while chat is open. A preserved or reopened chat screen can leave the
     * scroll up with chat closed, so keep the place then too.
     */
    @ModifyExpressionValue(
        method = "addMessageToDisplayQueue",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z")
    )
    private boolean sky2m$keepPlaceWhenClosed(boolean focused) {
        return focused || (sky2m$enabled() && chatScrollbarPos > 0);
    }

    @Inject(method = "refreshTrimmedMessages", at = @At("TAIL"))
    private void sky2m$restoreView(CallbackInfo ci) {
        if (!sky2m$rebuilding) return;
        sky2m$rebuilding = false;
        Map<GuiMessage, Integer> first = new IdentityHashMap<>();
        for (int i = 0; i < trimmedMessages.size(); i++) first.putIfAbsent(trimmedMessages.get(i).parent(), i);
        int target = 0;
        // The lowest message that is still there (the compactor may have removed one, a tab may hide some).
        for (int i = 0; i < sky2m$anchors.size(); i++) {
            Integer now = first.get(sky2m$anchors.get(i));
            if (now == null) continue;
            target = now - sky2m$offsets.get(i);
            break;
        }
        sky2m$anchors.clear();
        sky2m$offsets.clear();
        // Starting from the bottom, scrollChat keeps it in range (and at 0 it clears the "new messages" state).
        boolean unread = newMessageSinceScroll;
        resetChatScroll();
        if (target > 0) scrollChat(target);
        if (chatScrollbarPos > 0) newMessageSinceScroll = unread;
    }
}
