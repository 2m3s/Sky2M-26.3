// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StorageOverlayCustom.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.List;

/** The storage overlay drawn over a real storage menu (the Storage menu, an Ender Chest page or a backpack). */
public class StorageOverlayCustom extends CustomGui {
    public final StorageBackingHandle handler;
    public final ContainerScreen screen;
    public final StorageOverlayScreen overview;

    public StorageOverlayCustom(StorageBackingHandle handler, ContainerScreen screen, StorageOverlayScreen overview) {
        this.handler = handler;
        this.screen = screen;
        this.overview = overview;
    }

    private StoragePageSlot activePage() {
        return handler instanceof StorageBackingHandle.Page page ? page.storagePageSlot() : null;
    }

    private int leftPos() {
        return ((Sky2MContainerScreenAccessor) screen).sky2m$getLeftPos();
    }

    private int topPos() {
        return ((Sky2MContainerScreenAccessor) screen).sky2m$getTopPos();
    }

    @Override
    public boolean onVoluntaryExit() {
        overview.isExiting = true;
        StorageOverlayScreen.resetScroll();
        return super.onVoluntaryExit();
    }

    @Override
    public List<Rect> getBounds() {
        return overview.getBounds();
    }

    @Override
    public void afterSlotRender(GuiGraphicsExtractor context, Slot slot) {
        if (!(slot.container instanceof Inventory)) context.disableScissor();
    }

    @Override
    public void beforeSlotRender(GuiGraphicsExtractor context, Slot slot) {
        if (!(slot.container instanceof Inventory)) overview.createScissors(context);
        StorageOverlayScreen.highlightSearchResult(context, slot.getItem(), slot.x, slot.y);
    }

    @Override
    public void onInit() {
        overview.setHost(screen);
        overview.init(screen.width, screen.height);
        ((CustomGui.Holder) screen).sky2m$setPos(overview.measurements.x, overview.measurements.y);
        // The search box and button belong to the menu, so typing reaches the search box.
        if (!screen.children().contains(overview.searchField)) {
            ((com.epic60869.sky2m.mixin.Sky2MScreenWidgetsInvoker) screen).sky2m$addWidget(overview.searchField);
            ((com.epic60869.sky2m.mixin.Sky2MScreenWidgetsInvoker) screen).sky2m$addWidget(overview.editButton);
        }
    }

    @Override
    public boolean isPointOverSlot(Slot slot, int xOffset, int yOffset, double pointX, double pointY) {
        if (!super.isPointOverSlot(slot, xOffset, yOffset, pointX, pointY)) return false;
        if (!(slot.container instanceof Inventory)) {
            return overview.getScrollPanelInner().contains(pointX, pointY);
        }
        return true;
    }

    @Override
    public boolean shouldDrawForeground() {
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return overview.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        return overview.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean keyReleased(KeyEvent input) {
        return overview.keyReleased(input);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        return overview.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        return overview.charTyped(input);
    }

    @Override
    public boolean mouseClick(MouseButtonEvent click, boolean doubled) {
        return overview.mouseClicked(click, doubled, activePage());
    }

    @Override
    public void render(GuiGraphicsExtractor drawContext, float delta, int mouseX, int mouseY) {
        overview.drawBackgrounds(drawContext);
        List<Slot> pageSlots = null;
        if (handler instanceof StorageBackingHandle.Page) {
            List<Slot> all = screen.getMenu().slots;
            int end = Math.min(all.size(), screen.getMenu().getRowCount() * 9);
            if (end > 9) pageSlots = all.subList(9, end);
        }
        overview.drawPages(drawContext, mouseX, mouseY, delta, activePage(), pageSlots, leftPos(), topPos());
        overview.drawScrollBar(drawContext);
        overview.drawControls(drawContext, mouseX, mouseY, delta);
    }

    @Override
    public void moveSlot(Slot slot) {
        int index = slot.getContainerSlot();
        if (slot.container instanceof Inventory && index >= 0 && index < 36) {
            int[] pos = overview.getPlayerInventorySlotPosition(index);
            slot.x = pos[0] - leftPos();
            slot.y = pos[1] - topPos();
        } else if (handler instanceof StorageBackingHandle.Page && !(slot.container instanceof Inventory)
            && index >= 9 && index < screen.getMenu().getRowCount() * 9) {
            // Placed on its page by drawPages.
        } else {
            slot.x = -100000;
            slot.y = -100000;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Slot hovered = ((Sky2MContainerScreenAccessor) screen).sky2m$getHoveredSlot();
        if (hovered != null && hovered.hasItem() && StorageOverlay.config().itemsBlockScroll) return false;
        return overview.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}
