package com.epic60869.sky2m.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Locale;
import com.epic60869.sky2m.config.Sky2MConfig;

/**
 * Resets the cursor when selected Hypixel storage menus open.
 *
 * The important part is preserving the cursor position across Hypixel's
 * storage-to-storage screen swaps. Minecraft/Hypixel can restore the mouse
 * position from the first container screen when a new container screen is
 * created, so simply avoiding a second reset is not enough.
 */
public final class Sky2MMouseReset {
    private static boolean storageGuiOpen;
    private static Screen lastStorageScreen;
    private static double lastCursorX;
    private static double lastCursorY;
    private static boolean haveCursorPosition;
    /** When a storage menu was last open; Hypixel closes it for a moment between pages, which isn't leaving. */
    private static long lastStorageOpen;

    private Sky2MMouseReset() {}

    public static void tick(Minecraft mc) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.inventory.mouseReset.enabled) {
            resetState();
            return;
        }

        Screen screen = mc.gui.screen();
        if (screen == null) {
            if (System.currentTimeMillis() - lastStorageOpen > 1000) resetState();
            return;
        }

        String title;
        try {
            title = screen.getTitle().getString()
                .replaceAll("§.", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        } catch (Throwable ignored) {
            return;
        }

        boolean isStorageGui =
            (config.inventory.mouseReset.accessoryBag && matches(title, "accessory bag"))
            || (config.inventory.mouseReset.enderChest && matches(title, "ender chest"))
            || (config.inventory.mouseReset.backpack && matches(title, "backpack"));

        if (!isStorageGui) {
            resetState();
            return;
        }

        long window = mc.getWindow().handle();

        if (!storageGuiOpen) {
            // First selected storage GUI after coming from outside storage:
            // perform the actual Mouse Reset.
            // The cursor works in window coordinates, not GUI-scaled ones (using those put the cursor near the
            // top-left corner at GUI scales above 1), so centre on the real window size.
            com.epic60869.sky2m.custom.util.Input.setCursor(mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0);
        } else if (screen != lastStorageScreen && haveCursorPosition) {
            // Hypixel opened another storage screen. Do NOT reset to the
            // centre and do NOT accept the position restored by the new
            // Screen. Put the cursor back where it was in the previous
            // storage GUI.
            com.epic60869.sky2m.custom.util.Input.setCursor(lastCursorX, lastCursorY);
        }

        storageGuiOpen = true;
        lastStorageScreen = screen;
        lastStorageOpen = System.currentTimeMillis();

        // Remember the position after handling the screen transition so the
        // next storage screen can restore exactly this position.
        readCursor(window);
    }

    private static void readCursor(long window) {
        try {
            lastCursorX = com.epic60869.sky2m.custom.util.Input.cursorX();
            lastCursorY = com.epic60869.sky2m.custom.util.Input.cursorY();
            haveCursorPosition = true;
        } catch (Throwable ignored) {
            // If the cursor cannot be read, the normal storage GUI still works.
        }
    }

    private static void resetState() {
        storageGuiOpen = false;
        lastStorageScreen = null;
        haveCursorPosition = false;
    }

    private static boolean matches(String title, String value) {
        return title.equals(value)
            || title.startsWith(value + " ")
            || title.contains(" " + value + " ")
            || title.endsWith(" " + value);
    }
}
