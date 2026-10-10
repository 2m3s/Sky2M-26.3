// Ported from SkyOcean (https://github.com/meowdding/SkyOcean), features/item/search/screen/ItemSearchScreen.kt,
// SearchCategory.kt and SortModes.kt. SkyOcean's Olympus widgets are drawn with vanilla GUI calls here.
// SPDX-FileCopyrightText: SkyOcean Contributors
// SPDX-License-Identifier: MIT
package com.epic60869.sky2m;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** SkyOcean's item search: every cached item on this profile, grouped, filtered by category and sorted. */
public final class Sky2MStorageSearchScreen extends Screen {
    private static final int BG = 0xB905070B;
    private static final int PANEL = 0xFF121722;
    private static final int PANEL_DARK = 0xFF0B0F17;
    private static final int SLOT = 0xFF202735;
    private static final int SLOT_HOVER = 0xFF35445B;
    private static final int BORDER = 0xFF3B465B;
    private static final int PRIMARY = 0xFF5A65FF;
    private static final int GOLD = 0xFFB8862B;
    private static final int TEXT = 0xFFF3F6FF;
    private static final int MUTED = 0xFF8D98AB;
    private static final int CELL = 20;
    private static final int CATEGORY = 20;
    private static final int HEADER = 26;

    /** SkyOcean's SortModes, each with its tie breakers. */
    private enum SortMode {
        AMOUNT("Amount"), PRICE("Price"), RARITY("Rarity");

        final String label;

        SortMode(String label) {
            this.label = label;
        }

        Comparator<Entry> comparator() {
            Comparator<Entry> name = Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT));
            Comparator<Entry> amount = Comparator.comparingInt(Entry::count).reversed();
            Comparator<Entry> rarity = Comparator.comparingInt(Entry::rarity).reversed();
            Comparator<Entry> price = Comparator.comparingDouble(Entry::price).reversed();
            return switch (this) {
                case AMOUNT -> amount.thenComparing(name).thenComparing(rarity);
                case PRICE -> price.thenComparing(name);
                case RARITY -> rarity.thenComparing(amount).thenComparing(name);
            };
        }
    }

    /** SkyOcean's TrackedItemBundle: one item and every place a copy of it is kept. */
    private record Entry(ItemStack stack, List<Sky2MStorageSearch.Result> sources, int count, int rarity, double price) {
        String name() {
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            return name == null ? "" : name;
        }
    }

    // Kept between openings like SkyOcean's object screen (its "preserve last search" option).
    private static Sky2MStorageSearch.Category category = Sky2MStorageSearch.Category.ALL;
    private static SortMode sortMode = SortMode.AMOUNT;
    private static boolean ascending = true;

    private final Screen parent;
    private final String initialQuery;

    private EditBox searchBox;
    private int scrollRows;
    private List<Entry> items = List.of();
    private List<Entry> shown = List.of();

    public Sky2MStorageSearchScreen(Screen parent, String query) {
        super(Component.literal("Item Search"));
        this.parent = parent;
        this.initialQuery = query == null ? "" : query;
    }

    @Override
    protected void init() {
        clearWidgets();
        int right = panelLeft() + panelWidth();
        String text = searchBox == null ? initialQuery : searchBox.getValue(); // keep the search on resize
        searchBox = new EditBox(font, right - 104, panelTop() + 3, 100, 20, Component.literal("Search"));
        searchBox.setMaxLength(128);
        searchBox.setValue(text);
        searchBox.setHint(Component.literal("Search...").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setResponder(value -> refreshSearch());
        addRenderableWidget(searchBox);
        rebuildItems();
        setInitialFocus(searchBox);
    }

    // ---------------------------------------------------------------- layout (SkyOcean: a third of the screen + 50)

    private int panelWidth() {
        return Math.min(width - CATEGORY * 2 - 8, Math.max(100, width / 3) + 50);
    }

    private int panelHeight() {
        return Math.min(height - 8, Math.max(100, height / 3) + 50);
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return (height - panelHeight()) / 2;
    }

    private int gridLeft() {
        return panelLeft() + 6;
    }

    private int gridTop() {
        return panelTop() + HEADER + 4;
    }

    private int columns() {
        return Math.max(1, (panelWidth() - 20) / CELL);
    }

    private int rows() {
        return Math.max(1, (panelHeight() - HEADER - 8) / CELL);
    }

    private int maxScroll() {
        return Math.max(0, (shown.size() + columns() - 1) / columns() - rows());
    }

    // ---------------------------------------------------------------- items

    /** SkyOcean's rebuildItems: every source in the category, with equal items folded into one entry. */
    private void rebuildItems() {
        List<Sky2MStorageSearch.Result> results = Sky2MStorageSearch.search(Minecraft.getInstance(), "", true, true);
        List<List<Sky2MStorageSearch.Result>> groups = new ArrayList<>();
        for (Sky2MStorageSearch.Result result : results) {
            if (category != Sky2MStorageSearch.Category.ALL && result.category() != category) continue;
            List<Sky2MStorageSearch.Result> group = null;
            for (List<Sky2MStorageSearch.Result> g : groups) {
                if (Sky2MStorageSearch.sameForStacking(g.getFirst().stack(), result.stack())) {
                    group = g;
                    break;
                }
            }
            if (group == null) groups.add(group = new ArrayList<>());
            group.add(result);
        }
        List<Entry> entries = new ArrayList<>(groups.size());
        for (List<Sky2MStorageSearch.Result> group : groups) {
            int count = 0;
            for (Sky2MStorageSearch.Result r : group) count += r.stack().getCount();
            ItemStack stack = group.getFirst().stack().copy();
            String market = Sky2MPriceTooltip.marketId(stack);
            double price = market.isEmpty() ? 0 : Sky2MPriceTooltip.unitPrice(market) * count;
            entries.add(new Entry(stack, group, count, Sky2MStorageSearch.rarity(stack), price));
        }
        items = entries;
        refreshSort();
    }

    private void refreshSort() {
        Comparator<Entry> comparator = sortMode.comparator();
        items = items.stream().sorted(ascending ? comparator : comparator.reversed()).toList();
        refreshSearch();
    }

    /** SkyOcean's matches: the name or any lore line contains the search. */
    private void refreshSearch() {
        String search = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        shown = search.isEmpty() ? items : items.stream().filter(e -> matches(e.stack(), search)).toList();
        scrollRows = Math.max(0, Math.min(scrollRows, maxScroll()));
    }

    private static boolean matches(ItemStack stack, String search) {
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        if (name != null && name.toLowerCase(Locale.ROOT).contains(search)) return true;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text != null && text.toLowerCase(Locale.ROOT).contains(search)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, BG);
        int left = panelLeft();
        int top = panelTop();
        int right = left + panelWidth();
        int bottom = top + panelHeight();

        g.fill(left, top, right, bottom, PANEL);
        g.fill(left, top, right, top + HEADER, PANEL_DARK);
        outline(g, left, top, right, bottom, BORDER);

        // Sort dropdown and direction on the left, total value and search on the right.
        int sortX = left + 3;
        button(g, sortX, top + 3, 80, 20, false, mouseX, mouseY);
        g.text(font, sortMode.label, sortX + 6, top + 9, TEXT, false);
        g.text(font, "▼", sortX + 70, top + 9, MUTED, false);
        button(g, sortX + 82, top + 3, 20, 20, false, mouseX, mouseY);
        g.text(font, ascending ? "▲" : "▼", sortX + 89, top + 9, MUTED, false);

        int valueX = right - 128;
        double total = 0;
        for (Entry e : shown) total += e.price();
        fillButton(g, valueX, top + 3, 20, 20, GOLD, mouseX, mouseY);
        g.text(font, "$", valueX + 8, top + 9, 0xFF55FF55, true);

        // Categories down the left side.
        Sky2MStorageSearch.Category[] categories = Sky2MStorageSearch.Category.values();
        for (int i = 0; i < categories.length; i++) {
            int x = left - CATEGORY;
            int y = top + HEADER + i * CATEGORY;
            fillButton(g, x, y, CATEGORY, CATEGORY, categories[i] == category ? PRIMARY : SLOT, mouseX, mouseY);
            g.item(new ItemStack(categories[i].icon), x + 2, y + 2);
        }

        // The inventory-style grid.
        int gridL = gridLeft();
        int gridT = gridTop();
        int gridR = gridL + columns() * CELL;
        int gridB = gridT + rows() * CELL;
        g.fill(gridL - 2, gridT - 2, gridR + 2, gridB + 2, PANEL_DARK);
        outline(g, gridL - 2, gridT - 2, gridR + 2, gridB + 2, BORDER);

        Entry hovered = null;
        int start = scrollRows * columns();
        for (int visible = 0; visible < rows() * columns(); visible++) {
            int x = gridL + (visible % columns()) * CELL;
            int y = gridT + (visible / columns()) * CELL;
            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, hover ? SLOT_HOVER : SLOT);
            int index = start + visible;
            if (index >= shown.size()) continue;
            Entry entry = shown.get(index);
            g.item(entry.stack(), x + 2, y + 2);
            g.itemDecorations(font, entry.stack(), x + 2, y + 2, entry.count() > 1 ? shorten(entry.count()) : "");
            if (hover) hovered = entry;
        }

        // Scroll bar, always shown like SkyOcean's.
        int barX = gridR + 4;
        g.fill(barX, gridT, barX + 4, gridB, SLOT);
        int max = maxScroll();
        int knob = Math.max(10, (gridB - gridT) * rows() / Math.max(rows(), rows() + max));
        int knobY = gridT + (max == 0 ? 0 : (gridB - gridT - knob) * scrollRows / max);
        g.fill(barX, knobY, barX + 4, knobY + knob, BORDER);

        if (shown.isEmpty()) {
            String empty = items.isEmpty() ? "Nothing cached yet: open your storage, chests or museum." : "No items found.";
            g.centeredText(font, empty, (left + right) / 2, gridT + 8, MUTED);
        }

        super.extractRenderState(g, mouseX, mouseY, delta);

        if (hovered != null) {
            g.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
        } else if (inside(mouseX, mouseY, valueX, top + 3, 20, 20)) {
            g.setComponentTooltipForNextFrame(font, List.of(
                Component.literal("Total Value:").withStyle(ChatFormatting.GRAY),
                Component.literal(String.format(Locale.ENGLISH, "%,.0f", total)).withStyle(ChatFormatting.GOLD)), mouseX, mouseY);
        } else {
            for (int i = 0; i < categories.length; i++) {
                if (inside(mouseX, mouseY, left - CATEGORY, top + HEADER + i * CATEGORY, CATEGORY, CATEGORY)) {
                    g.setTooltipForNextFrame(font, Component.literal(categories[i].label), mouseX, mouseY);
                }
            }
        }
    }

    /** The item's own tooltip, then the amount or price, then where each copy is. */
    private List<Component> tooltip(Entry entry) {
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, entry.stack()));
        lines.add(Component.empty());
        if (sortMode == SortMode.PRICE) {
            if (entry.count() > 1) {
                lines.add(Component.literal("Avg. Price Per: " + shorten(entry.price() / entry.count())).withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("Total Price: " + shorten(entry.price())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.empty());
        } else if (sortMode == SortMode.AMOUNT) {
            lines.add(Component.literal("Amount: " + String.format(Locale.ENGLISH, "%,d", entry.count())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.empty());
        }
        List<Sky2MStorageSearch.Result> sources = entry.sources();
        if (sources.size() == 1) {
            lines.addAll(sources.getFirst().contextLines());
        } else {
            int limit = Math.min(sources.size(), 8);
            for (int i = 0; i < limit; i++) {
                Sky2MStorageSearch.Result r = sources.get(i);
                lines.add(Component.literal(r.stack().getCount() + "x " + r.location()).withStyle(ChatFormatting.GRAY));
            }
            if (sources.size() > limit) {
                lines.add(Component.literal("... and " + (sources.size() - limit) + " more").withStyle(ChatFormatting.DARK_GRAY));
            }
            lines.add(sources.getFirst().contextLines().getLast());
        }
        return lines;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        double mx = event.x();
        double my = event.y();
        int left = panelLeft();
        int top = panelTop();

        if (inside(mx, my, left + 3, top + 3, 80, 20)) {
            sortMode = SortMode.values()[(sortMode.ordinal() + 1) % SortMode.values().length];
            refreshSort();
            return true;
        }
        if (inside(mx, my, left + 85, top + 3, 20, 20)) {
            ascending = !ascending;
            refreshSort();
            return true;
        }
        Sky2MStorageSearch.Category[] categories = Sky2MStorageSearch.Category.values();
        for (int i = 0; i < categories.length; i++) {
            if (inside(mx, my, left - CATEGORY, top + HEADER + i * CATEGORY, CATEGORY, CATEGORY)) {
                category = categories[i];
                scrollRows = 0;
                rebuildItems();
                return true;
            }
        }
        int gridL = gridLeft();
        int gridT = gridTop();
        if (inside(mx, my, gridL, gridT, columns() * CELL, rows() * CELL)) {
            int index = scrollRows * columns() + (int) ((my - gridT) / CELL) * columns() + (int) ((mx - gridL) / CELL);
            if (index < shown.size()) {
                // SkyOcean highlights the item and opens where it is (for a bundle, the first place).
                Sky2MStorageSearch.openResult(Minecraft.getInstance(), shown.get(index).sources().getFirst());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0) {
            scrollRows = Math.max(0, Math.min(scrollRows + (scrollY > 0 ? -1 : 1), maxScroll()));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    // ---------------------------------------------------------------- helpers

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean selected, int mouseX, int mouseY) {
        fillButton(g, x, y, w, h, selected ? PRIMARY : SLOT, mouseX, mouseY);
    }

    private static void fillButton(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, colour);
        outline(g, x, y, x + w, y + h, inside(mouseX, mouseY, x, y, w, h) ? TEXT : BORDER);
    }

    private static void outline(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int colour) {
        g.fill(left, top, right, top + 1, colour);
        g.fill(left, bottom - 1, right, bottom, colour);
        g.fill(left, top, left + 1, bottom, colour);
        g.fill(right - 1, top, right, bottom, colour);
    }

    /** 1234 -> 1.2k, like SkyOcean's shorten(). */
    private static String shorten(double value) {
        if (value >= 1_000_000_000) return trimZero(value / 1_000_000_000) + "b";
        if (value >= 1_000_000) return trimZero(value / 1_000_000) + "m";
        if (value >= 1_000) return trimZero(value / 1_000) + "k";
        return trimZero(value);
    }

    private static String trimZero(double value) {
        String text = String.format(Locale.ENGLISH, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
