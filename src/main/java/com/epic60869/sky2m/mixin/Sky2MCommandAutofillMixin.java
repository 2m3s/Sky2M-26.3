package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.CommandAutofill;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * Command Autofill: after vanilla works out the suggestions for the chat input, swaps in Sky2M's own for
 * {@code /bz}, {@code /ahs}, {@code /viewrecipe}... (see {@link CommandAutofill}) as the pending suggestions, so the
 * popup, Tab, arrow keys and clicking work exactly as for server suggestions. Whatever the server or another mod
 * suggests for the same argument is merged in, now or when its answer arrives.
 */
@Mixin(CommandSuggestions.class)
public abstract class Sky2MCommandAutofillMixin {
    @Shadow
    @Final
    EditBox input;

    @Shadow
    @Final
    private Screen screen;

    @Shadow
    @Nullable
    private CompletableFuture<Suggestions> pendingSuggestions;

    @Shadow
    @Nullable
    private CommandSuggestions.SuggestionsList suggestions;

    @Shadow
    private boolean keepSuggestions;

    @Shadow
    private boolean allowSuggestions;

    @Shadow
    public abstract void showSuggestions(boolean narrateFirstSuggestion);

    @Inject(method = "updateCommandInfo", at = @At("TAIL"))
    private void sky2m$commandAutofill(CallbackInfo ci) {
        if (!(screen instanceof ChatScreen)) return;
        // Vanilla keeps the current list while Tab cycles through it.
        if (suggestions != null && keepSuggestions) return;
        String text = input.getValue();
        int cursor = input.getCursorPosition();
        if (cursor <= 0 || cursor > text.length()) return;
        String uptoCursor = text.substring(0, cursor);
        Suggestions ours = CommandAutofill.suggest(uptoCursor);
        if (ours == null) return;

        CompletableFuture<Suggestions> original = pendingSuggestions;
        CompletableFuture<Suggestions> mine;
        if (original != null && original.isDone()) {
            Suggestions theirs = original.isCompletedExceptionally() ? null : original.getNow(null);
            mine = CompletableFuture.completedFuture(theirs == null ? ours : CommandAutofill.merge(uptoCursor, theirs, ours));
        } else {
            mine = CompletableFuture.completedFuture(ours);
            if (original != null) {
                // The server answers later: add its suggestions if it has any and the input hasn't changed since.
                original.thenAccept(theirs -> {
                    if (pendingSuggestions != mine || theirs == null || theirs.isEmpty()) return;
                    boolean shown = suggestions != null;
                    pendingSuggestions = CompletableFuture.completedFuture(CommandAutofill.merge(uptoCursor, theirs, ours));
                    if (shown) showSuggestions(false);
                });
            }
        }
        pendingSuggestions = mine;
        if (allowSuggestions && Minecraft.getInstance().options.autoSuggestions().get()) showSuggestions(false);
    }
}
