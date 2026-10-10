package com.epic60869.sky2m.features.sbc;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * An inventory shared with [inv] (click its [Inventory] in chat): the armour, the inventory and the hotbar laid out
 * like your own inventory, with each item's tooltip on hover. Clicking an item shows it big, like a shared [item].
 */
public final class SbcInventoryScreen extends Screen {
    private static final int SLOT = 18;
    private static final int PANEL_WIDTH = 9 * SLOT + 14 + SLOT + 6;
    private static final int PANEL_HEIGHT = 4 * SLOT + 4 + 14 + 10;

    private final List<JsonObject> slots;
    private JsonObject hovered;

    /** {@code slots}: 0-8 the hotbar, 9-35 the inventory, 36-39 the armour from boots to helmet; null for empty. */
    public SbcInventoryScreen(String owner, List<JsonObject> slots) {
        super(Component.literal(owner == null || owner.isEmpty() ? "Shared Inventory" : owner + "'s Inventory"));
        this.slots = slots;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        g.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF0C6C6C6);
        g.outline(left, top, PANEL_WIDTH, PANEL_HEIGHT, 0xFF555555);
        g.text(font, title, left + 7, top + 6, 0xFF404040, false);

        int gridLeft = left + 7 + SLOT + 6;
        int gridTop = top + 18;
        hovered = null;
        // Armour down the left, helmet at the top.
        for (int i = 0; i < 4; i++) drawSlot(g, slot(39 - i), left + 7, gridTop + i * SLOT, mouseX, mouseY);
        // Inventory rows (9-35), then a gap, then the hotbar (0-8).
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) drawSlot(g, slot(9 + row * 9 + col), gridLeft + col * SLOT, gridTop + row * SLOT, mouseX, mouseY);
        }
        for (int col = 0; col < 9; col++) drawSlot(g, slot(col), gridLeft + col * SLOT, gridTop + 3 * SLOT + 4, mouseX, mouseY);
        if (hovered != null) {
            List<Component> tooltip = new java.util.ArrayList<>(SbcItems.tooltip(hovered));
            tooltip.add(Component.literal("Click to view").withStyle(s -> s.withColor(0xFFFF55)));
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
        g.centeredText(font, "Click an item to view it  ·  Esc to close", width / 2, top + PANEL_HEIGHT + 6, 0xFFAAAAAA);
    }

    private JsonObject slot(int index) {
        return index < slots.size() ? slots.get(index) : null;
    }

    /** Draws a slot and its item, remembering the item if the mouse is over it. */
    private void drawSlot(GuiGraphicsExtractor g, JsonObject item, int x, int y, int mouseX, int mouseY) {
        g.fill(x, y, x + SLOT, y + SLOT, 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + SLOT, y + SLOT, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF8B8B8B);
        boolean over = mouseX >= x && mouseX < x + SLOT && mouseY >= y && mouseY < y + SLOT;
        // Its real icon, even when the item is paper underneath.
        if (item != null) SbcItemIcons.draw(g, item, x + 1, y + 1);
        if (over) g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x80FFFFFF);
        if (over && item != null) hovered = item;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (hovered != null) {
            minecraft.gui.setScreen(new SbcItemScreen(hovered, this));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
