package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MGuiStyle;
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
 * Plain buttons (Button.builder's) in Sky2M's own screens are drawn as the settings' button ({@link Sky2MGuiStyle#button}),
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
        Sky2MGuiStyle.button(graphics, Minecraft.getInstance().font, button.getMessage().getString(), button.getX(), button.getY(),
            button.getWidth(), button.getHeight(), button.active && button.isHoveredOrFocused());
        if (!button.active) graphics.fill(button.getX(), button.getY(), button.getRight(), button.getBottom(), 0x80202026);
        ci.cancel();
    }
}
