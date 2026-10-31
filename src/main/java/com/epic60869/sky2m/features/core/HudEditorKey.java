package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.gui.Sky2MHudEditorScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * GUI > HUDs > Edit HUDs Key: opens /s2 gui from the world or over an open menu, so HUDs that are only drawn in a menu
 * can be moved with that menu behind them.
 */
public final class HudEditorKey {
    private static boolean wasDown;

    private HudEditorKey() {}

    private static int key() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? -1 : c.gui.editHudsKey;
    }

    public static void init() {
        // In the world: no screen open.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            int key = key();
            boolean down = key > 0 && mc.gui.screen() == null && InputConstants.isKeyDown(key);
            if (down && !wasDown) mc.gui.setScreen(new Sky2MHudEditorScreen(null));
            wasDown = down;
        });
        // Over a menu (the inventory, the Chocolate Factory...), unless a text box is being typed in.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                int key = key();
                if (key <= 0 || event.key() != key || s.getFocused() instanceof EditBox) return true;
                Minecraft.getInstance().gui.setScreen(new Sky2MHudEditorScreen(s));
                wasDown = true;
                return false;
            });
        });
    }
}
