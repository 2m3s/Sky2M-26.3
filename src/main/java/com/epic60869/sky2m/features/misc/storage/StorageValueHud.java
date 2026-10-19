package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Value breakdown for an open Ender Chest page or backpack. Its place is set in /s2 gui ("Storage Value"): it used to
 * sit right of the menu, which with the storage overlay on covered the overlay's second column of pages.
 */
public final class StorageValueHud {
    private static final int WIDTH = 176;
    private static final int MAX_ITEMS = 5;
    private static final long REFRESH_MS = 500;
    private static final String HUD_ID = "storage_value";

    private StorageValueHud() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container)
                || !(StorageBackingHandle.fromScreen(screen) instanceof StorageBackingHandle.Page)
                || !StorageOverlay.config().valueBreakdown) return;
            Sky2MPriceTooltip.warmup();
            ValueBox box = new ValueBox(container);
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> box.render(graphics));
        });
        com.epic60869.sky2m.features.core.Sky2MHuds.registerCustom(HUD_ID, "Storage Value",
            () -> StorageOverlay.config().valueBreakdown, new com.epic60869.sky2m.features.core.Sky2MHuds.CustomHud() {
                @Override
                public int width() {
                    return WIDTH;
                }

                @Override
                public int height() {
                    return heightFor(PREVIEW.size());
                }

                /** Only drawn over an Ender Chest page or backpack, never on the HUD. */
                @Override
                public boolean visible() {
                    return false;
                }

                @Override
                public void render(GuiGraphicsExtractor graphics, boolean preview) {
                    draw(graphics, PREVIEW);
                }
            }, 4, 60);
        com.epic60869.sky2m.features.core.Sky2MHuds.setSettingsSearch(HUD_ID, "Storage Value Breakdown");
    }

    private static final List<Component> PREVIEW = List.of(
        Component.literal("Estimated Storage Value").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
        Component.literal("Total: 12.40M coins").withStyle(ChatFormatting.WHITE),
        Component.literal("1x Hyperion  9.80M coins").withStyle(ChatFormatting.GRAY),
        Component.literal("64x Enchanted ..  1.20M coins").withStyle(ChatFormatting.GRAY));

    private static int heightFor(int lines) {
        return 8 + lines * 10;
    }

    private static void draw(GuiGraphicsExtractor graphics, List<Component> lines) {
        int h = heightFor(lines.size());
        graphics.fill(0, 0, WIDTH, h, 0xD0101010);
        graphics.fill(0, 0, WIDTH, 1, 0xFFFFD54F);
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, lines.get(i), 6, 5 + i * 10, 0xFFFFFFFF);
        }
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

    private static final class ValueBox {
        private final ContainerScreen screen;
        private List<Component> lines = List.of();
        private long lastRefresh;

        private ValueBox(ContainerScreen screen) {
            this.screen = screen;
        }

        private void render(GuiGraphicsExtractor graphics) {
            if (!StorageOverlay.config().valueBreakdown) return;
            long now = System.currentTimeMillis();
            if (now - lastRefresh >= REFRESH_MS) {
                lastRefresh = now;
                updateLines();
            }
            var p = com.epic60869.sky2m.features.core.Sky2MHuds.placement(HUD_ID);
            int x = com.epic60869.sky2m.features.core.Sky2MHuds.mapX(p.x, Math.round(WIDTH * p.scale));
            int y = com.epic60869.sky2m.features.core.Sky2MHuds.mapY(p.y, Math.round(heightFor(lines.size()) * p.scale));
            graphics.nextStratum();
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale(p.scale, p.scale);
            draw(graphics, lines);
            graphics.pose().popMatrix();
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
    }
}