package com.epic60869.sky2m.features.misc.system;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.config.Sky2MConfig;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Misc > Smart Disconnect: the pause menu's Disconnect button asks first, with Cancel and a red Disconnect button,
 * so a misclick doesn't kick you out of the server.
 */
public final class SmartDisconnect {
    private SmartDisconnect() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.smartDisconnect;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof PauseScreen) || !enabled()) return;
            Button disconnect = null;
            for (AbstractWidget widget : Screens.getWidgets(screen)) {
                if (widget instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("menu.disconnect")) {
                    disconnect = button;
                    break;
                }
            }
            if (disconnect == null) return;
            Button original = disconnect;
            // Hide the real button and put one in its place that asks first; confirming presses the real one.
            original.visible = false;
            Button ask = Button.builder(original.getMessage(), b -> client.gui.setScreen(new ConfirmScreen(screen, original)))
                .bounds(original.getX(), original.getY(), original.getWidth(), original.getHeight())
                .build();
            Screens.getWidgets(screen).add(ask);
        });
    }

    private static final class ConfirmScreen extends Screen {
        private final Screen parent;
        private final Button original;

        ConfirmScreen(Screen parent, Button original) {
            super(Component.literal("Disconnect?"));
            this.parent = parent;
            this.original = original;
        }

        @Override
        protected void init() {
            int y = height / 2;
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(width / 2 - 104, y, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Disconnect").withStyle(ChatFormatting.RED),
                    b -> original.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0)))
                .bounds(width / 2 + 4, y, 100, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            g.centeredText(font, "Are you sure you want to disconnect?", width / 2, height / 2 - 20, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            minecraft.gui.setScreen(parent);
        }
    }
}
