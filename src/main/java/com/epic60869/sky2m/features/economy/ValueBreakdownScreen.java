package com.epic60869.sky2m.features.economy;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The Value Breakdown window: one item's Estimated Item Value, row by row. */
public final class ValueBreakdownScreen extends Screen {
    private final Screen parent;
    private final ItemStack stack;
    private final List<ValueBreakdown.Row> rows;

    public ValueBreakdownScreen(Screen parent, ItemStack stack) {
        super(Component.literal("Value Breakdown"));
        this.parent = parent;
        this.stack = stack.copy();
        this.rows = ValueBreakdown.rows(stack);
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int w = Math.min(width - 32, 300);
        int x = (width - w) / 2;
        int y = 30;
        int h = 40 + Math.max(1, rows.size()) * 11 + 24;
        g.fill(x, y, x + w, y + h, 0xC0101216);
        g.outline(x, y, w, h, 0xFF2C313A);
        g.item(stack, x + 8, y + 8);
        g.text(font, stack.getHoverName(), x + 30, y + 12, 0xFFFFFFFF, true);
        int line = y + 36;
        if (rows.isEmpty()) {
            g.text(font, Component.literal("This item has no price.").withStyle(ChatFormatting.RED), x + 10, line, 0xFFFFFFFF, true);
        }
        double sum = 0;
        for (ValueBreakdown.Row row : rows) {
            sum += row.coins();
            g.text(font, row.label(), x + 10, line, 0xFFCCCCCC, true);
            String value = ValueBreakdown.coins(row.coins());
            g.text(font, value, x + w - 10 - font.width(value), line, 0xFF55FFFF, true);
            line += 11;
        }
        if (!rows.isEmpty()) {
            line += 6;
            g.text(font, Component.literal("Total").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), x + 10, line, 0xFFFFFFFF, true);
            String value = ValueBreakdown.coins(sum * Math.max(1, stack.getCount())) + (stack.getCount() > 1 ? " (x" + stack.getCount() + ")" : "");
            g.text(font, value, x + w - 10 - font.width(value), line, 0xFFFFAA00, true);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }
}
