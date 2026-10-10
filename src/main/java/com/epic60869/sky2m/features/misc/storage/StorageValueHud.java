package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Value breakdown for an open Ender Chest page or backpack. */
public final class StorageValueHud {
    private static final int WIDTH = 176;
    private static final int MAX_ITEMS = 5;
    private static final long REFRESH_MS = 500;

    private StorageValueHud() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container)
                || !(StorageBackingHandle.fromScreen(screen) instanceof StorageBackingHandle.Page)
                || !StorageOverlay.config().valueBreakdown) return;
            Sky2MPriceTooltip.warmup();
            Screens.getWidgets(screen).add(new ValueWidget(container));
        });
    }

    private record ItemValue(String name, int count, double value) {}

    private static final class MutableItemValue {
        private final String name;
        private int count;
        private double value;

        private MutableItemValue(String name) {
            this.name = name;
        }
    }

    private static final class ValueWidget extends AbstractWidget {
        private final ContainerScreen screen;
        private List<Component> lines = List.of();
        private long lastRefresh;

        private ValueWidget(ContainerScreen screen) {
            super(0, 0, WIDTH, 88, Component.empty());
            this.screen = screen;
            this.active = false;
            updatePosition();
        }

        private void updatePosition() {
            int left = ((com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor) screen).sky2m$getLeftPos();
            int top = ((com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor) screen).sky2m$getTopPos();
            int x = left + 180;
            if (x + WIDTH > screen.width - 4) x = left - WIDTH - 4;
            if (x < 4) x = 4;
            int y = Math.max(4, Math.min(top + 8, screen.height - getHeight() - 4));
            setPosition(x, y);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            if (!StorageOverlay.config().valueBreakdown) return;
            updatePosition();
            long now = System.currentTimeMillis();
            if (now - lastRefresh >= REFRESH_MS) {
                lastRefresh = now;
                updateLines();
            }

            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xD0101010);
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + 1, 0xFFFFD54F);
            var font = Minecraft.getInstance().font;
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(font, lines.get(i), getX() + 6, getY() + 5 + i * 10, 0xFFFFFFFF);
            }
        }

        private void updateLines() {
            Map<String, MutableItemValue> grouped = new HashMap<>();
            double total = 0;
            for (var slot : screen.getMenu().slots) {
                if (slot.container instanceof Inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                String id = Sky2MPriceTooltip.marketId(stack);
                if (id.isEmpty()) continue;
                double unitPrice = Sky2MPriceTooltip.unitPrice(id);
                if (unitPrice <= 0) continue;

                double value = unitPrice * stack.getCount();
                MutableItemValue item = grouped.computeIfAbsent(id,
                    key -> new MutableItemValue(stack.getHoverName().getString()));
                item.count += stack.getCount();
                item.value += value;
                total += value;
            }

            List<ItemValue> items = grouped.values().stream()
                .map(item -> new ItemValue(item.name, item.count, item.value))
                .sorted(Comparator.comparingDouble(ItemValue::value).reversed())
                .toList();
            List<Component> next = new ArrayList<>();
            next.add(Component.literal("Estimated Storage Value").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            next.add(Component.literal("Total: " + coins(total)).withStyle(ChatFormatting.WHITE));
            if (items.isEmpty()) {
                next.add(Component.literal("No priced items found").withStyle(ChatFormatting.GRAY));
            } else {
                for (int i = 0; i < Math.min(MAX_ITEMS, items.size()); i++) {
                    ItemValue item = items.get(i);
                    String name = item.name().length() > 12 ? item.name().substring(0, 11) + ".." : item.name();
                    next.add(Component.literal(item.count() + "x " + name + "  " + coins(item.value()))
                        .withStyle(ChatFormatting.GRAY));
                }
                if (items.size() > MAX_ITEMS) {
                    next.add(Component.literal("+ " + (items.size() - MAX_ITEMS) + " more item types")
                        .withStyle(ChatFormatting.DARK_GRAY));
                }
            }
            lines = List.copyOf(next);
        }

        private static String coins(double value) {
            if (value >= 1_000_000_000) return String.format(Locale.US, "%.2fB coins", value / 1_000_000_000);
            if (value >= 1_000_000) return String.format(Locale.US, "%.2fM coins", value / 1_000_000);
            if (value >= 1_000) return String.format(Locale.US, "%.1fK coins", value / 1_000);
            return String.format(Locale.US, "%,.0f coins", value);
        }

        @Override
        public void onClick(MouseButtonEvent click, boolean doubled) {}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput builder) {}
    }
}