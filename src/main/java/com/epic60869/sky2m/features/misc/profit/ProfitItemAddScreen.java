package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Adding an item to a profit tracker by hand (the tracker's [+ Add Item] over a menu): your inventory is shown, and
 * clicking an item picks it, by the id the tracker counts it as (so it's priced like a drop); the item's editor then
 * opens to set how many. Only SkyBlock items (with an id) can be picked. Drawn like {@link ProfitItemEditScreen}.
 */
final class ProfitItemAddScreen extends Screen {
    private static final int SLOT = 18, COLS = 9, W = COLS * SLOT + 20, H = 4 * SLOT + 4 + 70;

    private final Screen parent;
    private final String trackerName;
    private final Consumer<String> picked;

    ProfitItemAddScreen(Screen parent, String trackerName, Consumer<String> picked) {
        super(Component.literal("Add Tracked Item"));
        this.parent = parent;
        this.trackerName = trackerName;
        this.picked = picked;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    /** Inventory slot for grid cell {@code i}: the 27 main slots, then the hotbar below them as in the inventory. */
    private static int slot(int i) {
        return i < 27 ? i + 9 : i - 27;
    }

    /** Where cell {@code i} is drawn: a gap above the hotbar row, like the inventory. */
    private int cellX(int i) { return left() + 10 + (i % COLS) * SLOT; }
    private int cellY(int i) { return top() + 30 + (i / COLS) * SLOT + (i >= 27 ? 4 : 0); }

    private ItemStack stack(int i) {
        return minecraft.player == null ? ItemStack.EMPTY : minecraft.player.getInventory().getItem(slot(i));
    }

    private int cellAt(double mx, double my) {
        for (int i = 0; i < 36; i++) {
            int x = cellX(i), y = cellY(i);
            if (mx >= x && mx < x + SLOT && my >= y && my < y + SLOT) return i;
        }
        return -1;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = left(), y = top();
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, x, y, W, H, true);
        Sky2MGuiStyle.header(g, font, x + 4, y + 4, W - 8, Sky2MGuiStyle.title("Add to " + trackerName));
        int hovered = cellAt(mouseX, mouseY);
        for (int i = 0; i < 36; i++) {
            int cx = cellX(i), cy = cellY(i);
            ItemStack stack = stack(i);
            boolean usable = !stack.isEmpty() && !Sky2MPriceTooltip.marketId(stack).isEmpty();
            g.fill(cx, cy, cx + SLOT - 1, cy + SLOT - 1, i == hovered && usable ? 0x60FFFFFF : 0x40000000);
            if (stack.isEmpty()) continue;
            g.item(stack, cx + 1, cy + 1);
            g.itemDecorations(font, stack, cx + 1, cy + 1);
            if (!usable) g.fill(cx, cy, cx + SLOT - 1, cy + SLOT - 1, 0x90000000);
        }
        int ty = cellY(35) + SLOT + 6;
        g.text(font, "Click an item to add it.", x + 10, ty, Sky2MGuiStyle.DESCRIPTION, false);
        int by = y + H - 24;
        boolean overCancel = mouseX >= x + W - 70 && mouseX < x + W - 10 && mouseY >= by && mouseY < by + 16;
        Sky2MGuiStyle.button(g, font, Component.translatable("gui.cancel").getString(), x + W - 70, by, 60, 16, overCancel);
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (hovered >= 0 && !stack(hovered).isEmpty()) {
            g.setTooltipForNextFrame(font, Screen.getTooltipFromItem(minecraft, stack(hovered)), Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return false;
        int x = left(), by = top() + H - 24;
        if (event.x() >= x + W - 70 && event.x() < x + W - 10 && event.y() >= by && event.y() < by + 16) {
            Sky2MGuiStyle.click();
            onClose();
            return true;
        }
        int cell = cellAt(event.x(), event.y());
        if (cell < 0) return false;
        String id = Sky2MPriceTooltip.marketId(stack(cell));
        if (id.isEmpty()) return true;
        Sky2MGuiStyle.click();
        picked.accept(id);
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
