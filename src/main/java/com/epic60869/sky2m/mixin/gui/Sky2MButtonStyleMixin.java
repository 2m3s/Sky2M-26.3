package com.epic60869.sky2m.mixin.gui;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plain buttons (Button.builder's) in Sky2M's own screens are drawn as Sky2M's dark button ({@link Sky2MGuiStyle#button}),
 * so every Sky2M screen matches /s2. Other screens, and buttons with their own look, keep Minecraft's.
 */
@Mixin(AbstractButton.class)
public abstract class Sky2MButtonStyleMixin {
    @Inject(method = "extractWidgetRenderState", at = @At("HEAD"), cancellable = true)
    private void sky2m$settingsStyle(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AbstractButton button = (AbstractButton) (Object) this;
        if (button.getClass() != Button.Plain.class) return;
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null || !screen.getClass().getName().startsWith("com.epic60869.sky2m.")) return;
        Sky2MGuiStyle.button(graphics, Minecraft.getInstance().font, sky2m$label(button.getMessage()), button.getX(), button.getY(),
            button.getWidth(), button.getHeight(), button.isHoveredOrFocused(), false, button.active);
        ci.cancel();
    }

    /** The label's text, keeping a whole-label colour (Smart Disconnect's red "Disconnect") as a colour code. */
    private static String sky2m$label(net.minecraft.network.chat.Component message) {
        String text = message.getString();
        net.minecraft.network.chat.TextColor colour = message.getStyle().getColor();
        if (colour == null) return text;
        for (net.minecraft.ChatFormatting code : net.minecraft.ChatFormatting.values()) {
            // ChatFormatting's toString() is its "§c"-style code.
            if (colour.equals(net.minecraft.network.chat.TextColor.fromLegacyFormat(code))) return code + text;
        }
        return text;
    }
}
