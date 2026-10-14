package com.epic60869.sky2m.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MGlobalChat;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class Sky2MChatScreenMixin {
    @Shadow
    protected EditBox input;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sky2m$sendChannelMessage(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!Sky2MGlobalChat.isInSky2MChannel()) {
            return;
        }

        int key = event.key();
        if (key != InputConstants.KEY_RETURN && key != InputConstants.KEY_NUMPADENTER) {
            return;
        }

        String message = input.getValue().trim();
        // Let Minecraft handle slash commands normally. This is what makes
        // /s2 chat leave (and every other /command) usable while channel mode is active.
        if (message.startsWith("/")) {
            return;
        }
        if (!message.isEmpty()) {
            if (message.startsWith("!")) {
                Sky2MGlobalChat.sendBotCommand(message);
            } else {
                Sky2MGlobalChat.send(message);
            }
        }

        input.setValue("");
        ((ChatScreen) (Object) this).onClose();
        cir.setReturnValue(true);
    }

    @Shadow
    private net.minecraft.client.gui.components.ChatComponent.DisplayMode displayMode;

    /** Image preview when hovering an image link in chat. */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void sky2m$currentChatDisplay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        com.epic60869.sky2m.Sky2MCurrentChat.render(graphics, ((ChatScreen) (Object) this).height);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void sky2m$imagePreview(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        var finder = new net.minecraft.client.gui.ActiveTextCollector.ClickableStyleFinder(mc.font, mouseX, mouseY);
        mc.gui.hud.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), mc.gui.hud.getGuiTicks(), displayMode);
        com.epic60869.sky2m.Sky2MImagePreview.render(graphics, finder.result(), mouseX, mouseY);
        com.epic60869.sky2m.features.sbc.SbcChatOverlay.render(graphics, ((ChatScreen) (Object) this).height, mouseX, mouseY);
        com.epic60869.sky2m.features.misc.ChatTabs.render(graphics, ((ChatScreen) (Object) this).height, input, mouseX, mouseY);
    }

    /** Chat Tabs: clicking a tab, Ctrl+F, and Enter while searching. */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sky2m$chatTabsClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (com.epic60869.sky2m.features.misc.ChatTabs.mouseClicked(event.x(), event.y(), ((ChatScreen) (Object) this).height, input)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sky2m$chatTabsKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (com.epic60869.sky2m.features.misc.ChatTabs.keyPressed(event.key(), event.hasControlDown(), input)) cir.setReturnValue(true);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void sky2m$chatTabsClosed(CallbackInfo ci) {
        com.epic60869.sky2m.features.misc.ChatTabs.closed();
    }
}
