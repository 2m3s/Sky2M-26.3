package com.epic60869.sky2m.features.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * GUI scale Auto picks the biggest scale that fits, which leaves Sky2M's screens little room (1080p gets 480x270): while
 * one of them is open on Auto, the GUI is one step smaller, and goes back when it closes. Any other GUI scale, the HUD
 * editor (HUD positions are kept for the normal scale) and menus Sky2M draws over are left as they are.
 * Sky2MAutoScaleMixin applies it.
 */
public final class Sky2MAutoScale {
    private static boolean active;

    private Sky2MAutoScale() {}

    /** Gui.setScreen is about to show {@code next}: rescale if it starts or stops being a Sky2M screen. */
    public static void beforeScreen(Screen next) {
        boolean want = wanted(next);
        if (want == active) return;
        active = want;
        Minecraft.getInstance().resizeGui();
    }

    /** The scale Window.calculateScale chose, one step smaller while a Sky2M screen is open on Auto. */
    public static int adjust(int scale) {
        if (!active || scale <= 1) return scale;
        Minecraft mc = Minecraft.getInstance();
        return mc.options != null && mc.options.guiScale().get() == 0 ? scale - 1 : scale;
    }

    private static boolean wanted(Screen screen) {
        if (screen == null || screen instanceof AbstractContainerScreen<?>) return false;
        if (screen instanceof com.epic60869.sky2m.gui.Sky2MHudEditorScreen) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null || mc.options.guiScale().get() != 0) return false;
        return screen.getClass().getName().startsWith("com.epic60869.sky2m.");
    }
}
