// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StorageOverviewScreen.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StorageOverviewScreen extends Screen {
    public static final List<Item> EMPTY_STORAGE_SLOT_ITEMS = List.of(Items.STAINED_GLASS_PANE.red(), Items.STAINED_GLASS_PANE.brown(), Items.DYE.gray());
    public static final int PAGE_WIDTH = 19 * 9;

    public static int scroll = 0;
    public static int lastRenderedHeight = 0;
    private double scrollRemainder;

    public final StorageData content = StorageData.data();
    public boolean isClosing = false;

    public StorageOverviewScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        super.init();
        // Before the first render the content height isn't known yet: don't throw away the retained scroll.
        if (lastRenderedHeight > 0) scroll = Math.max(0, Math.min(scroll, getMaxScroll()));
    }

    @Override
    public void onClose() {
        if (!StorageOverlay.config().retainScroll) scroll = 0;
        super.onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0x90000000);
        layoutedForEach((key, value, offsetX, offsetY) -> {
            context.pose().pushMatrix();
            context.pose().translate((float) offsetX, (float) offsetY);
            renderStoragePage(context, value, mouseX - offsetX, mouseY - offsetY);
            context.pose().popMatrix();
            return false;
        });
    }

    @FunctionalInterface
    interface PageAction {
        /** Returns true to stop. */
        boolean accept(StoragePageSlot key, StorageData.StorageInventory value, int offsetX, int offsetY);
    }

    void layoutedForEach(PageAction onEach) {
        var config = StorageOverlay.config();
        int offsetY = 0;
        int currentMaxHeight = config.margin - config.padding - scroll;
        int totalHeight = -currentMaxHeight;
        int index = 0;
        for (Map.Entry<StoragePageSlot, StorageData.StorageInventory> e : new ArrayList<>(content.storageInventories.entrySet())) {
            int pageX = index % config.columns;
            if (pageX == 0) {
                currentMaxHeight += config.padding;
                offsetY += currentMaxHeight;
                totalHeight += currentMaxHeight;
                currentMaxHeight = 0;
            }
            int xPosition = width / 2 - (config.columns * (PAGE_WIDTH + config.padding) - config.padding) / 2 + pageX * (PAGE_WIDTH + config.padding);
            if (onEach.accept(e.getKey(), e.getValue(), xPosition, offsetY)) return;
            currentMaxHeight = Math.max(currentMaxHeight, getStorePageHeight(e.getValue()));
            index++;
        }
        lastRenderedHeight = totalHeight + currentMaxHeight;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        boolean[] handled = new boolean[1];
        layoutedForEach((k, p, x, y) -> {
            double rx = click.x() - x;
            double ry = click.y() - y;
            if (rx >= 0 && rx <= PAGE_WIDTH && ry >= 0 && ry <= getStorePageHeight(p)) {
                onClose();
                StorageOverlay.lastStorageOverlay = this;
                k.navigateTo();
                handled[0] = true;
                return true;
            }
            return false;
        });
        return handled[0] || super.mouseClicked(click, doubled);
    }

    public int getStorePageHeight(StorageData.StorageInventory page) {
        return page.inventory != null ? page.inventory.rows() * 19 + Minecraft.getInstance().font.lineHeight + 2 : 60;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Smooth-scrolling mice and touchpads send fractions of a notch: add them up rather than rounding each to 0.
        scrollRemainder += StorageOverlay.adjustScrollSpeed(verticalAmount);
        int step = (int) scrollRemainder;
        scrollRemainder -= step;
        scroll = Math.max(0, Math.min(scroll + step, getMaxScroll()));
        return true;
    }

    private int getMaxScroll() {
        return lastRenderedHeight - height + 2 * StorageOverlay.config().margin;
    }

    private void renderStoragePage(GuiGraphicsExtractor context, StorageData.StorageInventory page, int mouseX, int mouseY) {
        var font = Minecraft.getInstance().font;
        context.text(font, content.displayName(page.slot), 2, 2, -1, true);
        VirtualInventory inventory = page.inventory;
        if (inventory == null) {
            context.fill(0, 0, PAGE_WIDTH, 60, 0xFF3A0D0B);
            context.centeredText(font, Component.literal("Not loaded yet"), PAGE_WIDTH / 2, 30, -1);
            return;
        }
        List<ItemStack> stacks = inventory.stacks();
        for (int index = 0; index < stacks.size(); index++) {
            int x = (index % 9) * 19;
            int y = (index / 9) * 19 + font.lineHeight + 2;
            boolean over = mouseX - x >= 0 && mouseX - x < 18 && mouseY - y >= 0 && mouseY - y < 18;
            context.fill(x, y, x + 18, y + 18, over ? 0x80808080 : 0x40808080);
            context.item(stacks.get(index), x + 1, y + 1);
            context.itemDecorations(font, stacks.get(index), x + 1, y + 1);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == InputConstants.KEY_ESCAPE) isClosing = true;
        return super.keyPressed(input);
    }
}
