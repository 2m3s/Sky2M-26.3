package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.economy.ValueBreakdown;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Storage Value Breakdown: what your Ender Chest pages and backpacks are worth, priced like the Estimated Item Value
 * (upgrades included, {@link Sky2MPriceTooltip#itemValue}) and drawn like its panel, with no background. In the storage
 * overlay each page's title has its value, and a panel left of the overlay shows the hovered (or open) page's most valuable
 * items, or with none, your most valuable pages and the storage's total. On a page opened without the overlay, the panel
 * goes where "Storage Value" is placed in /s2 gui.
 */
public final class StorageValueHud {
    private static final int MAX_ROWS = 5;
    private static final int MIN_WIDTH = 150;
    private static final long REFRESH_MS = 2000;
    private static final String HUD_ID = "storage_value";

    /** One kind of item on a page (counts added up), or one page in the storage summary. */
    public record Row(String label, int colour, double value) {}

    /** What a page holds: its total and its rows, most valuable first. Public for the game test. */
    public record Summary(double total, List<Row> rows) {}

    /** What the panel says. */
    public record Panel(Component title, Component subtitle, List<Row> rows, int more, double moreValue, double total) {}

    private record Cached(long at, Summary summary) {}

    private static final Map<VirtualInventory, Cached> CACHE = new WeakHashMap<>();

    private StorageValueHud() {}

    public static boolean enabled() {
        return StorageOverlay.config().valueBreakdown;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container)
                || !(StorageBackingHandle.fromScreen(screen) instanceof StorageBackingHandle.Page)
                || !enabled()) return;
            Sky2MPriceTooltip.warmup();
            ValueBox box = new ValueBox(container);
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> box.render(graphics));
        });
        com.epic60869.sky2m.features.core.Sky2MHuds.registerCustom(HUD_ID, "Storage Value",
            StorageValueHud::enabled, new com.epic60869.sky2m.features.core.Sky2MHuds.CustomHud() {
                @Override
                public int width() {
                    return StorageValueHud.width(Minecraft.getInstance().font, PREVIEW);
                }

                @Override
                public int height() {
                    return StorageValueHud.height(PREVIEW);
                }

                /** Only drawn over an Ender Chest page or backpack, never on the HUD. */
                @Override
                public boolean visible() {
                    return false;
                }

                @Override
                public void render(GuiGraphicsExtractor graphics, boolean preview) {
                    draw(graphics, PREVIEW, 0, 0);
                }
            }, 4, 60);
        com.epic60869.sky2m.features.core.Sky2MHuds.setSettingsSearch(HUD_ID, "Storage Value Breakdown");
    }

    private static final Panel PREVIEW = new Panel(Component.literal("Ender Chest #5").withStyle(ChatFormatting.WHITE),
        header(), List.of(new Row("16x Recombobulator 3000", 0xFFAA00, 141_955_152), new Row("Hyperion", 0xFF55FF, 980_000_000)),
        0, 0, 1_121_955_152);

    private static Component header() {
        return Component.literal("Estimated Storage Value").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    // ------------------------------------------------------------------------------------------------ values

    /** A page's value, worked out at most every {@link #REFRESH_MS}: the overlay asks for every page every frame. */
    public static Summary of(VirtualInventory inventory) {
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(inventory);
        if (cached == null || now - cached.at() >= REFRESH_MS) {
            cached = new Cached(now, summarize(inventory.stacks()));
            CACHE.put(inventory, cached);
        }
        return cached.summary();
    }

    /** The items' value, each kind with its count added up. Public for the game test. */
    public static Summary summarize(List<ItemStack> stacks) {
        Map<String, double[]> values = new LinkedHashMap<>();
        Map<String, ItemStack> first = new LinkedHashMap<>();
        double total = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            double value = Sky2MPriceTooltip.itemValue(stack);
            if (value <= 0) continue;
            String key = Sky2MPriceTooltip.marketId(stack);
            if (key.isEmpty()) key = stack.getHoverName().getString();
            double[] sum = values.computeIfAbsent(key, k -> new double[2]);
            sum[0] += stack.getCount();
            sum[1] += value;
            first.putIfAbsent(key, stack);
            total += value;
        }
        List<Row> rows = new ArrayList<>();
        values.forEach((key, sum) -> {
            Component name = first.get(key).getHoverName();
            int count = (int) sum[0];
            String label = (count > 1 ? count + "x " : "") + ChatFormatting.stripFormatting(name.getString());
            rows.add(new Row(label, colour(name), sum[1]));
        });
        rows.sort(Comparator.comparingDouble(Row::value).reversed());
        return new Summary(total, rows);
    }

    /** The first colour in the name (its rarity), white with none. */
    private static int colour(Component name) {
        int[] found = {0xFFFFFF};
        name.visit((style, text) -> {
            if (!text.isBlank() && style.getColor() != null) {
                found[0] = style.getColor().getValue();
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    /** The panel for one page. */
    public static Panel pagePanel(String pageName, Summary summary) {
        return panel(Component.literal(pageName).withStyle(ChatFormatting.WHITE), summary.rows(), summary.total());
    }

    /** The panel for the whole storage: the most valuable pages and everything loaded added up. */
    public static Panel storagePanel(StorageData data) {
        List<Row> pages = new ArrayList<>();
        double total = 0;
        for (var e : data.storageInventories.entrySet()) {
            VirtualInventory inventory = e.getValue().inventory;
            if (inventory == null) continue;
            double value = of(inventory).total();
            if (value <= 0) continue;
            pages.add(new Row(data.displayName(e.getKey()), 0xFFFFFF, value));
            total += value;
        }
        pages.sort(Comparator.comparingDouble(Row::value).reversed());
        return panel(Component.literal("Your storage").withStyle(ChatFormatting.WHITE), pages, total);
    }

    private static Panel panel(Component title, List<Row> rows, double total) {
        List<Row> shown = rows.subList(0, Math.min(MAX_ROWS, rows.size()));
        double rest = 0;
        for (Row row : rows.subList(shown.size(), rows.size())) rest += row.value();
        return new Panel(title, header(), List.copyOf(shown), rows.size() - shown.size(), rest, total);
    }

    /** "78.5M", for a page's title. */
    public static String shortCoins(double value) {
        return CombatFeatures.formatCoins(value);
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static Component value(double coins) {
        return Component.literal(shortCoins(coins)).withStyle(ChatFormatting.GOLD);
    }

    private static Component totalLine(double total) {
        return Component.literal("Total: ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(ValueBreakdown.coins(total) + " Coins").withStyle(ChatFormatting.YELLOW));
    }

    private static int lines(Panel panel) {
        return Math.max(1, panel.rows().size() + (panel.more() > 0 ? 1 : 0));
    }

    public static int width(Font font, Panel panel) {
        int w = Math.max(MIN_WIDTH, Math.max(font.width(panel.title()), font.width(panel.subtitle())));
        for (Row row : panel.rows()) w = Math.max(w, Math.min(font.width(row.label()), 150) + 14 + font.width(value(row.value())));
        return Math.max(w, font.width(totalLine(panel.total()))) + 16;
    }

    public static int height(Panel panel) {
        return 8 + 20 + 4 + lines(panel) * 10 + 6 + 12;
    }

    /** The panel at x, y, like the Estimated Item Value's, with no background: the text has a shadow instead. */
    public static void draw(GuiGraphicsExtractor g, Panel panel, int x, int y) {
        Font font = Minecraft.getInstance().font;
        int w = width(font, panel);
        g.text(font, panel.title(), x + 8, y + 7, 0xFFFFFFFF, true);
        g.text(font, panel.subtitle(), x + 8, y + 17, 0xFFFFFFFF, true);
        int line = y + 8 + 20 + 4;
        if (panel.rows().isEmpty()) {
            g.text(font, Component.literal("No priced items").withStyle(ChatFormatting.GRAY), x + 8, line, 0xFFFFFFFF, true);
            line += 10;
        }
        for (Row row : panel.rows()) {
            Component value = value(row.value());
            String label = font.plainSubstrByWidth(row.label(), w - 16 - 10 - font.width(value));
            if (label.length() < row.label().length()) label = font.plainSubstrByWidth(label, font.width(label) - font.width("..")) + "..";
            g.text(font, Component.literal(label).withColor(row.colour()), x + 8, line, 0xFFFFFFFF, true);
            g.text(font, value, x + w - 8 - font.width(value), line, 0xFFFFFFFF, true);
            line += 10;
        }
        if (panel.more() > 0) {
            Component value = value(panel.moreValue());
            g.text(font, Component.literal("+" + panel.more() + " more").withStyle(ChatFormatting.DARK_GRAY), x + 8, line, 0xFFFFFFFF, true);
            g.text(font, value, x + w - 8 - font.width(value), line, 0xFFFFFFFF, true);
            line += 10;
        }
        line += 7;
        g.text(font, totalLine(panel.total()), x + 8, line, 0xFFFFFFFF, true);
    }

    // ------------------------------------------------------------------------------------------------ without the overlay

    private static final class ValueBox {
        private final ContainerScreen screen;
        private Panel panel;
        private long lastRefresh;

        private ValueBox(ContainerScreen screen) {
            this.screen = screen;
        }

        private void render(GuiGraphicsExtractor graphics) {
            // The storage overlay draws its own panel.
            if (!enabled() || CustomGui.get(screen) != null) return;
            long now = System.currentTimeMillis();
            if (panel == null || now - lastRefresh >= 500) {
                lastRefresh = now;
                List<ItemStack> stacks = new ArrayList<>();
                for (var slot : screen.getMenu().slots) if (!(slot.container instanceof Inventory)) stacks.add(slot.getItem());
                panel = pagePanel(net.minecraft.ChatFormatting.stripFormatting(screen.getTitle().getString()), summarize(stacks));
            }
            Font font = Minecraft.getInstance().font;
            var p = com.epic60869.sky2m.features.core.Sky2MHuds.placement(HUD_ID);
            int x = com.epic60869.sky2m.features.core.Sky2MHuds.mapX(p.x, Math.round(width(font, panel) * p.scale));
            int y = com.epic60869.sky2m.features.core.Sky2MHuds.mapY(p.y, Math.round(height(panel) * p.scale));
            graphics.nextStratum();
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale(p.scale, p.scale);
            draw(graphics, panel, 0, 0);
            graphics.pose().popMatrix();
        }
    }
}
