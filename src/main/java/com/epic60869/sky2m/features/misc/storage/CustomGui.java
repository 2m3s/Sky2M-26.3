// Ported from Firmament (https://github.com/FirmamentMC/Firmament), util/customgui/CustomGui.kt and HasCustomGui.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/**
 * Replaces how a container screen looks and where its slots are, while the game still handles the slots (clicks,
 * dragging, shift-click, tooltips). Attached to a screen with {@link #set}; see Sky2MCustomGuiScreenMixin and
 * Sky2MCustomGuiContainerMixin.
 */
public abstract class CustomGui {

    /** A rectangle; contains(x, y) is inclusive of the top-left edge and exclusive of the bottom-right. */
    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        public int maxX() {
            return x + width;
        }

        public int maxY() {
            return y + height;
        }
    }

    /** Implemented on Screen by Sky2MCustomGuiScreenMixin. */
    public interface Holder {
        CustomGui sky2m$getCustomGui();

        void sky2m$setCustomGui(CustomGui gui);

        /** Centres the screen on the custom gui's first bounds (container screens only). */
        default void sky2m$fixSize() {}

        /** Moves a container screen's origin (leftPos, topPos); its slots are placed relative to it. */
        default void sky2m$setPos(int x, int y) {}
    }

    /** Implemented on Slot by Sky2MSlotCoordsMixin: where the slot was before a custom gui moved it. */
    public interface RememberingSlot {
        void sky2m$rememberCoords();

        void sky2m$restoreCoords();
    }

    public static CustomGui get(Screen screen) {
        return screen == null ? null : ((Holder) screen).sky2m$getCustomGui();
    }

    public static void set(Screen screen, CustomGui gui) {
        ((Holder) screen).sky2m$setCustomGui(gui);
        ((Holder) screen).sky2m$fixSize();
    }

    public abstract List<Rect> getBounds();

    public void moveSlot(Slot slot) {}

    public void render(GuiGraphicsExtractor context, float delta, int mouseX, int mouseY) {}

    public boolean mouseClick(MouseButtonEvent click, boolean doubled) {
        return false;
    }

    public void afterSlotRender(GuiGraphicsExtractor context, Slot slot) {}

    public void beforeSlotRender(GuiGraphicsExtractor context, Slot slot) {}

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return false;
    }

    public boolean isClickOutsideBounds(double mouseX, double mouseY) {
        for (Rect r : getBounds()) if (r.contains(mouseX, mouseY)) return false;
        return true;
    }

    public boolean isPointWithinBounds(int x, int y, int width, int height, double pointX, double pointY) {
        boolean inBounds = false;
        for (Rect r : getBounds()) {
            if (r.contains(pointX, pointY)) {
                inBounds = true;
                break;
            }
        }
        return inBounds && new Rect(x, y, width, height).contains(pointX, pointY);
    }

    public boolean isPointOverSlot(Slot slot, int xOffset, int yOffset, double pointX, double pointY) {
        return isPointWithinBounds(slot.x + xOffset, slot.y + yOffset, 16, 16, pointX, pointY);
    }

    public void onInit() {}

    public boolean shouldDrawForeground() {
        return true;
    }

    public boolean onVoluntaryExit() {
        return true;
    }

    public boolean mouseReleased(MouseButtonEvent click) {
        return false;
    }

    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        return false;
    }

    public boolean keyPressed(KeyEvent input) {
        return false;
    }

    public boolean charTyped(CharacterEvent input) {
        return false;
    }

    public boolean keyReleased(KeyEvent input) {
        return false;
    }
}
