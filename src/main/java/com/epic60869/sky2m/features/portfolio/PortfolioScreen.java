package com.epic60869.sky2m.features.portfolio;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /s2 portfolio: the portfolio sheet, sold log and value graph. Click a column header to sort by it (again to reverse).
 */
public final class PortfolioScreen extends Screen {
    private enum Tab { SHEET, SOLD, GRAPH }

    private static final int ROW = 14;
    private static final int ICON = 12;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] SHEET_HEADERS = {"Item", "Qty (inv/stor/extra)", "Buy each", "Price each", "Value", "Profit/loss"};
    private static final String[] SOLD_HEADERS = {"Item", "Sold", "Amount", "Sold for", "Bought for", "Profit"};

    private Tab tab = Tab.SHEET;
    private Portfolio.Entry selected;
    private Portfolio.Sale selectedSale;
    private int scroll;
    private EditBox addBox, buyBox, extraBox, qtyBox;
    private String status = "";
    /** Sorted column (-1 = the order you added them in) and direction, per tab. */
    private int sortColumn = -1;
    private boolean ascending = true;
    private int soldSortColumn = -1;
    private boolean soldAscending = true;

    public PortfolioScreen() {
        super(Component.literal("Sky2M Portfolio"));
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int tableTop() { return top() + 78; }
    private int tableBottom() { return top() + panelHeight() - (tab == Tab.GRAPH ? 8 : 34); }

    @Override
    protected void init() {
        Portfolio.refreshPrices(false);
        Portfolio.recount();
        rebuild();
    }

    private void rebuild() {
        String addText = addBox == null ? "" : addBox.getValue();
        clearWidgets();
        int x = left() + 8, y = top() + 24;

        for (Tab t : Tab.values()) {
            String label = switch (t) { case SHEET -> "Portfolio"; case SOLD -> "Sold"; case GRAPH -> "Graph"; };
            Button b = Button.builder(Component.literal(t == tab ? "» " + label + " «" : label), btn -> {
                tab = t;
                scroll = 0;
                rebuild();
            }).bounds(x, y, 70, 20).build();
            addRenderableWidget(b);
            x += 74;
        }

        int rx = left() + panelWidth() - 8;
        rx -= 60;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            Portfolio.refreshPrices(true);
            Portfolio.recount();
            status = "Refreshing prices...";
        }).bounds(rx, y, 60, 20).build());
        rx -= 84;
        addRenderableWidget(Button.builder(Component.literal("Export CSV"), b -> {
            try {
                Path out = Portfolio.exportCsv();
                com.mojang.blaze3d.Blaze3D.openPath(out);
                status = "Exported to " + out;
            } catch (Exception e) {
                status = "Export failed: " + e.getMessage();
            }
        }).bounds(rx, y, 80, 20).build());
        rx -= 124;
        addRenderableWidget(Button.builder(Component.literal(Portfolio.autoRemove() ? "On sale: Auto-log" : "On sale: Ask"), b -> {
            Portfolio.toggleAutoRemove();
            rebuild();
        }).bounds(rx, y, 120, 20).build());
        // Auction house and bazaar prices are chosen separately.
        rx -= 114;
        addRenderableWidget(Button.builder(Component.literal("BZ: " + Portfolio.bzMode().label()), b -> {
            Portfolio.toggleBzMode();
            rebuild();
        }).bounds(rx, y, 110, 20).build());
        rx -= 114;
        addRenderableWidget(Button.builder(Component.literal("AH: " + Portfolio.ahMode().label()), b -> {
            Portfolio.toggleAhMode();
            rebuild();
        }).bounds(rx, y, 110, 20).build());

        if (tab == Tab.SHEET) {
            int ay = top() + 50;
            addBox = new EditBox(font, left() + 8, ay, 220, 20, Component.literal("Item name or ID"));
            addBox.setHint(Component.literal("Item name or ID (e.g. Enderman Skin)").withStyle(ChatFormatting.DARK_GRAY));
            addBox.setMaxLength(80);
            addBox.setValue(addText);
            addRenderableWidget(addBox);
            addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
                Portfolio.Entry e = Portfolio.add(addBox.getValue());
                status = e == null ? (Portfolio.pricesLoaded() ? "Couldn't find \"" + addBox.getValue() + "\". Try its SkyBlock ID." : "Item list is still loading, try again in a moment.") : "Added " + e.name + ".";
                if (e != null) {
                    addBox.setValue("");
                    selected = e;
                }
                rebuild();
            }).bounds(left() + 232, ay, 50, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Add held item"), b -> {
                Portfolio.Entry e = Portfolio.addHeld();
                status = e == null ? "Hold a SkyBlock item first." : "Added " + e.name + ".";
                if (e != null) selected = e;
                rebuild();
            }).bounds(left() + 286, ay, 90, 20).build());

            if (selected != null && Portfolio.entries().contains(selected)) {
                int by = top() + panelHeight() - 28;
                int bx = left() + 8 + Math.min(220, font.width(selected.name) + 12);
                buyBox = new EditBox(font, bx, by, 90, 20, Component.literal("Buy price each"));
                buyBox.setHint(Component.literal("Buy price each").withStyle(ChatFormatting.DARK_GRAY));
                buyBox.setMaxLength(200);
                buyBox.setValue(Portfolio.buyPriceText(selected));
                buyBox.setTooltip(Tooltip.create(Component.literal("One price (1.5m), or copies bought at different prices: 2x1.5m + 1x1.2m")));
                addRenderableWidget(buyBox);
                addRenderableWidget(Button.builder(Component.literal("Set buy"), b -> {
                    if (!Portfolio.setBuyPrice(selected, buyBox.getValue())) status = "Type a price like 1.5m, or 2x1.5m + 1x1.2m for different prices.";
                    else status = selected.lots != null ? "Buy prices set (average " + Portfolio.coins(selected.buyPrice) + " each)." : "Buy price set.";
                }).bounds(bx + 94, by, 56, 20).build());
                bx += 156;
                extraBox = new EditBox(font, bx, by, 50, 20, Component.literal("Extra"));
                extraBox.setHint(Component.literal("Extra").withStyle(ChatFormatting.DARK_GRAY));
                if (selected.extra > 0) extraBox.setValue(String.valueOf(selected.extra));
                addRenderableWidget(extraBox);
                addRenderableWidget(Button.builder(Component.literal("Set extra"), b -> {
                    double v = Portfolio.parse(extraBox.getValue().isBlank() ? "0" : extraBox.getValue());
                    if (v < 0) status = "Type a whole number.";
                    else {
                        selected.extra = (int) v;
                        Portfolio.changed();
                        status = "Extra amount set (copies Sky2M can't see, e.g. applied skins).";
                    }
                }).bounds(bx + 54, by, 60, 20).build());
                bx += 120;
                qtyBox = new EditBox(font, bx, by, 50, 20, Component.literal("Qty"));
                qtyBox.setHint(Component.literal("Auto").withStyle(ChatFormatting.DARK_GRAY));
                if (selected.quantity != null) qtyBox.setValue(String.valueOf(selected.quantity));
                addRenderableWidget(qtyBox);
                addRenderableWidget(Button.builder(Component.literal("Set qty"), b -> {
                    if (qtyBox.getValue().isBlank()) {
                        selected.quantity = null;
                        Portfolio.changed();
                        status = "Quantity is counted automatically again.";
                        return;
                    }
                    double v = Portfolio.parse(qtyBox.getValue());
                    if (v < 0) status = "Type a whole number, or leave it empty to count automatically.";
                    else {
                        selected.quantity = (int) v;
                        Portfolio.changed();
                        status = "Quantity set to " + (int) v + " (clear the box and Set qty to count automatically).";
                    }
                }).bounds(bx + 54, by, 50, 20).build());
                bx += 110;
                addRenderableWidget(Button.builder(Component.literal("Log sale"), b -> {
                    Portfolio.recordSale(selected, Math.max(1, Portfolio.count(selected).total()), Portfolio.value(selected), true);
                    selected = null;
                    rebuild();
                }).bounds(bx, by, 60, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
                    Portfolio.remove(selected);
                    selected = null;
                    rebuild();
                }).bounds(bx + 64, by, 56, 20).build());
            }
        } else if (tab == Tab.SOLD && selectedSale != null && Portfolio.sold().contains(selectedSale)) {
            int by = top() + panelHeight() - 28;
            addRenderableWidget(Button.builder(Component.literal("Remove sale"), b -> {
                Portfolio.removeSale(selectedSale);
                status = "Removed " + selectedSale.name + " from the sold list.";
                selectedSale = null;
                rebuild();
            }).bounds(left() + 8, by, 90, 20).build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left(), top(), left() + panelWidth(), top() + panelHeight(), 0xFF181B21);
        g.fill(left(), top(), left() + panelWidth(), top() + 2, 0xFFFFAA00);

        double total = Portfolio.totalValue(), profit = Portfolio.totalProfit(), perHour = Portfolio.profitPerHour();
        g.text(font, Component.literal("Portfolio").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), left() + 8, top() + 8, 0xFFFFFFFF, true);
        Component summary = Component.literal("Value: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(Portfolio.coins(total)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("   Unrealised P/L: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(Portfolio.coins(profit)).withStyle(profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED))
            .append(Component.literal(" (" + Portfolio.coins(perHour) + "/h)").withStyle(perHour >= 0 ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED))
            .append(Component.literal("   Realised: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(Portfolio.coins(Portfolio.realisedProfit())).withStyle(Portfolio.realisedProfit() >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        g.text(font, summary, left() + 80, top() + 8, 0xFFFFFFFF, false);
        String prices = Portfolio.pricesLoaded() ? "prices " + (System.currentTimeMillis() - Portfolio.lastPriceRefresh()) / 1000 + "s old" : "loading prices...";
        g.text(font, prices, left() + panelWidth() - 8 - font.width(prices), top() + 8, 0xFF7A8290, false);

        switch (tab) {
            case SHEET -> drawSheet(g, mouseX, mouseY);
            case SOLD -> drawSold(g, mouseX, mouseY);
            case GRAPH -> drawGraph(g);
        }
        if (!status.isEmpty()) g.text(font, status, left() + 382, top() + 56, 0xFFB8C0CC, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    // ----- Columns and sorting -----

    /**
     * Column x positions, sized to fit their widest cell so nothing overlaps; the first (item) column gets
     * whatever room is left.
     */
    private int[] columns(String[] headers, List<String[]> cells) {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) widths[i] = font.width(headers[i] + " ▲") + 10;
        for (String[] row : cells) for (int i = 1; i < row.length; i++) widths[i] = Math.max(widths[i], font.width(row[i]) + 10);
        int x = left() + 8;
        int available = panelWidth() - 16;
        int others = 0;
        for (int i = 1; i < widths.length; i++) others += widths[i];
        int first = Math.max(90, available - others);
        int[] c = new int[headers.length];
        c[0] = x;
        c[1] = x + first;
        for (int i = 2; i < c.length; i++) c[i] = c[i - 1] + widths[i - 1];
        return c;
    }

    private void drawHeaders(GuiGraphicsExtractor g, String[] headers, int[] c, int y, int sorted, boolean asc) {
        for (int i = 0; i < headers.length; i++) {
            String text = headers[i] + (i == sorted ? (asc ? " ▲" : " ▼") : "");
            g.text(font, text, c[i], y, i == sorted ? 0xFFFFD27A : 0xFFFFAA00, false);
        }
        g.fill(left() + 6, y + 10, left() + panelWidth() - 6, y + 11, 0xFF3A3F48);
    }

    /** Column under x, or -1. */
    private static int columnAt(int[] c, double x, int right) {
        for (int i = c.length - 1; i >= 0; i--) if (x >= c[i] && x < right) return i;
        return -1;
    }

    private double profitOf(Portfolio.Entry e) {
        double price = Portfolio.price(e.id);
        return e.buyPrice > 0 && price > 0 ? Portfolio.count(e).total() * (price - e.buyPrice) : Double.NEGATIVE_INFINITY;
    }

    private List<Portfolio.Entry> sortedEntries() {
        List<Portfolio.Entry> entries = new ArrayList<>(Portfolio.entries());
        if (sortColumn < 0) return entries;
        Comparator<Portfolio.Entry> order = switch (sortColumn) {
            case 0 -> Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingInt(e -> Portfolio.count(e).total());
            case 2 -> Comparator.comparingDouble(e -> e.buyPrice);
            case 3 -> Comparator.comparingDouble(e -> Portfolio.price(e.id));
            case 4 -> Comparator.comparingDouble(Portfolio::value);
            default -> Comparator.comparingDouble(this::profitOf);
        };
        entries.sort(ascending ? order : order.reversed());
        return entries;
    }

    private List<Portfolio.Sale> sortedSales() {
        List<Portfolio.Sale> sold = new ArrayList<>(Portfolio.sold());
        if (soldSortColumn < 0) {
            java.util.Collections.reverse(sold); // newest first
            return sold;
        }
        Comparator<Portfolio.Sale> order = switch (soldSortColumn) {
            case 0 -> Comparator.comparing(s -> s.name.toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingLong(s -> s.time);
            case 2 -> Comparator.comparingInt(s -> s.amount);
            case 3 -> Comparator.comparingDouble(s -> s.price);
            case 4 -> Comparator.comparingDouble(s -> s.buyPrice * s.amount);
            default -> Comparator.comparingDouble(s -> s.buyPrice > 0 && s.price > 0 ? s.price - s.buyPrice * s.amount : Double.NEGATIVE_INFINITY);
        };
        sold.sort(soldAscending ? order : order.reversed());
        return sold;
    }

    // ----- Sheet -----

    private String[] sheetCells(Portfolio.Entry e) {
        Portfolio.Count count = Portfolio.count(e);
        double price = Portfolio.price(e.id);
        String profit = "-";
        if (e.buyPrice > 0 && price > 0) {
            double pl = count.total() * (price - e.buyPrice);
            profit = Portfolio.coins(pl) + String.format(Locale.US, " (%+.0f%%)", (price - e.buyPrice) / e.buyPrice * 100);
        }
        return new String[]{
            e.name,
            count.manual() != null ? count.total() + " (set)" : count.total() + " (" + count.inventory() + "/" + count.storage() + "/" + count.extra() + ")",
            e.buyPrice > 0 ? Portfolio.coins(e.buyPrice) + (e.lots != null ? " avg" : "") : (e.buyPricePending ? "..." : "-"),
            price > 0 ? Portfolio.coins(price) : "?",
            Portfolio.coins(count.total() * price),
            profit
        };
    }

    private int[] sheetColumns() {
        List<String[]> cells = new ArrayList<>();
        for (Portfolio.Entry e : Portfolio.entries()) cells.add(sheetCells(e));
        return columns(SHEET_HEADERS, cells);
    }

    private void drawSheet(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] c = sheetColumns();
        int y = tableTop();
        drawHeaders(g, SHEET_HEADERS, c, y, sortColumn, ascending);

        List<Portfolio.Entry> entries = sortedEntries();
        if (entries.isEmpty()) {
            g.text(font, "Add an item above, or hold one and click Add held item. Quantities update from your inventory and every Ender Chest / backpack page you have opened.", c[0], y + 18, 0xFF7A8290, false);
        }
        int rowsVisible = (tableBottom() - y - 14) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, entries.size() - rowsVisible)));
        int ry = y + 16;
        for (int i = scroll; i < entries.size() && i < scroll + rowsVisible; i++) {
            Portfolio.Entry e = entries.get(i);
            String[] cells = sheetCells(e);
            Portfolio.Count count = Portfolio.count(e);
            boolean hover = mouseY >= ry - 3 && mouseY < ry + ROW - 3 && mouseX >= left() && mouseX < left() + panelWidth();
            if (e == selected) g.fill(left() + 6, ry - 3, left() + panelWidth() - 6, ry + ROW - 3, 0xFF2E3440);
            else if (hover) g.fill(left() + 6, ry - 3, left() + panelWidth() - 6, ry + ROW - 3, 0xFF22262E);
            ItemStack icon = Portfolio.icon(e);
            int nameX = c[0];
            if (icon != null) {
                g.pose().pushMatrix();
                g.pose().translate(c[0], ry - 2);
                g.pose().scale(ICON / 16f, ICON / 16f);
                g.item(icon, 0, 0);
                g.pose().popMatrix();
                nameX += ICON + 3;
            }
            g.text(font, font.plainSubstrByWidth(cells[0], c[1] - nameX - 6), nameX, ry, 0xFFFFFFFF, false);
            g.text(font, cells[1], c[1], ry, count.total() > 0 ? 0xFFFFFFFF : 0xFF7A8290, false);
            g.text(font, cells[2], c[2], ry, 0xFFB8C0CC, false);
            g.text(font, cells[3], c[3], ry, 0xFFFFFFFF, false);
            g.text(font, cells[4], c[4], ry, 0xFFFFAA00, false);
            double pl = profitOf(e);
            g.text(font, cells[5], c[5], ry, cells[5].equals("-") ? 0xFF7A8290 : pl >= 0 ? 0xFF55FF55 : 0xFFFF5555, false);
            ry += ROW;
        }
        if (selected != null && Portfolio.entries().contains(selected)) {
            g.text(font, font.plainSubstrByWidth(selected.name, 210), left() + 8, top() + panelHeight() - 22, 0xFFFFFFFF, true);
        }
    }

    // ----- Sold -----

    private String[] soldCells(Portfolio.Sale s) {
        String profit = s.buyPrice > 0 && s.price > 0 ? Portfolio.coins(s.price - s.buyPrice * s.amount) : "";
        return new String[]{
            s.name, DATE.format(Instant.ofEpochMilli(s.time)), String.valueOf(s.amount),
            s.price > 0 ? Portfolio.coins(s.price) : "-", s.buyPrice > 0 ? Portfolio.coins(s.buyPrice * s.amount) : "-", profit
        };
    }

    private int[] soldColumns() {
        List<String[]> cells = new ArrayList<>();
        for (Portfolio.Sale s : Portfolio.sold()) cells.add(soldCells(s));
        return columns(SOLD_HEADERS, cells);
    }

    private void drawSold(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] c = soldColumns();
        int y = tableTop();
        drawHeaders(g, SOLD_HEADERS, c, y, soldSortColumn, soldAscending);
        List<Portfolio.Sale> sold = sortedSales();
        if (sold.isEmpty()) g.text(font, "No sales yet. Auction and bazaar sales of tracked items show up here.", c[0], y + 18, 0xFF7A8290, false);
        int rowsVisible = (tableBottom() - y - 14) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, sold.size() - rowsVisible)));
        int ry = y + 16;
        for (int i = scroll; i < sold.size() && i < scroll + rowsVisible; i++) {
            Portfolio.Sale s = sold.get(i);
            String[] cells = soldCells(s);
            boolean hover = mouseY >= ry - 3 && mouseY < ry + ROW - 3 && mouseX >= left() && mouseX < left() + panelWidth();
            if (s == selectedSale) g.fill(left() + 6, ry - 3, left() + panelWidth() - 6, ry + ROW - 3, 0xFF2E3440);
            else if (hover) g.fill(left() + 6, ry - 3, left() + panelWidth() - 6, ry + ROW - 3, 0xFF22262E);
            g.text(font, font.plainSubstrByWidth(cells[0], c[1] - c[0] - 6), c[0], ry, 0xFFFFFFFF, false);
            g.text(font, cells[1], c[1], ry, 0xFFB8C0CC, false);
            g.text(font, cells[2], c[2], ry, 0xFFFFFFFF, false);
            g.text(font, cells[3], c[3], ry, 0xFFFFAA00, false);
            g.text(font, cells[4], c[4], ry, 0xFFB8C0CC, false);
            if (!cells[5].isEmpty()) {
                double p = s.price - s.buyPrice * s.amount;
                g.text(font, cells[5], c[5], ry, p >= 0 ? 0xFF55FF55 : 0xFFFF5555, false);
            }
            ry += ROW;
        }
        if (!sold.isEmpty() && selectedSale == null) {
            g.text(font, "Click a sale to select it, then Remove sale.", left() + 8, top() + panelHeight() - 22, 0xFF7A8290, false);
        }
    }

    // ----- Graph -----

    private void drawGraph(GuiGraphicsExtractor g) {
        List<long[]> points = new ArrayList<>(); // time, value
        String label;
        if (selected != null && Portfolio.entries().contains(selected)) {
            label = selected.name + " price (select nothing to see total value)";
            for (Portfolio.Snapshot s : Portfolio.history()) {
                Double p = s.prices.get(selected.id);
                if (p != null && p > 0) points.add(new long[]{s.time, Math.round(p)});
            }
            double now = Portfolio.price(selected.id);
            if (now > 0) points.add(new long[]{System.currentTimeMillis(), Math.round(now)});
        } else {
            label = "Total portfolio value";
            for (Portfolio.Snapshot s : Portfolio.history()) points.add(new long[]{s.time, Math.round(s.total)});
            if (!Portfolio.entries().isEmpty() && Portfolio.pricesLoaded()) points.add(new long[]{System.currentTimeMillis(), Math.round(Portfolio.totalValue())});
        }

        int gx = left() + 70, gy = tableTop() + 14, gw = panelWidth() - 90, gh = tableBottom() - gy - 20;
        g.text(font, label, left() + 8, tableTop(), 0xFFFFAA00, false);
        g.fill(gx, gy, gx + gw, gy + gh, 0xFF101318);
        g.outline(gx, gy, gw, gh, 0xFF3A3F48);
        if (points.size() < 2) {
            g.text(font, "Not enough history yet. A snapshot is saved every 30 minutes while you play.", gx + 8, gy + 8, 0xFF7A8290, false);
            return;
        }
        long minT = points.getFirst()[0], maxT = points.getLast()[0];
        long minV = Long.MAX_VALUE, maxV = Long.MIN_VALUE;
        for (long[] p : points) {
            minV = Math.min(minV, p[1]);
            maxV = Math.max(maxV, p[1]);
        }
        if (maxV == minV) {
            maxV += 1;
            minV -= 1;
        }
        if (maxT == minT) maxT++;
        for (int i = 0; i <= 4; i++) {
            int ly = gy + gh - gh * i / 4;
            g.fill(gx, ly, gx + gw, ly + 1, 0xFF22262E);
            String v = Portfolio.coins(minV + (maxV - minV) * i / 4.0);
            g.text(font, v, gx - 4 - font.width(v), ly - 4, 0xFF7A8290, false);
        }
        g.text(font, DATE.format(Instant.ofEpochMilli(minT)), gx, gy + gh + 4, 0xFF7A8290, false);
        String end = DATE.format(Instant.ofEpochMilli(maxT));
        g.text(font, end, gx + gw - font.width(end), gy + gh + 4, 0xFF7A8290, false);

        int lastX = -1, lastY = -1;
        for (long[] p : points) {
            int x = gx + (int) ((p[0] - minT) * (gw - 1) / (double) (maxT - minT));
            int y = gy + gh - 1 - (int) ((p[1] - minV) * (gh - 1) / (double) (maxV - minV));
            if (lastX >= 0) line(g, lastX, lastY, x, y, 0xFFFFAA00);
            g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFD27A);
            lastX = x;
            lastY = y;
        }
    }

    private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int colour) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (x1 - x0) * i / Math.max(1, steps);
            int y = y0 + (y1 - y0) * i / Math.max(1, steps);
            g.fill(x, y, x + 1, y + 2, colour);
        }
    }

    // ----- Input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || tab == Tab.GRAPH) return false;
        int y = tableTop();
        int right = left() + panelWidth() - 6;
        // Header: sort by that column, click again to reverse.
        if (event.y() >= y - 2 && event.y() < y + 10) {
            int column = columnAt(tab == Tab.SHEET ? sheetColumns() : soldColumns(), event.x(), right);
            if (column < 0) return false;
            if (tab == Tab.SHEET) {
                ascending = column != sortColumn || !ascending;
                sortColumn = column;
            } else {
                soldAscending = column != soldSortColumn || !soldAscending;
                soldSortColumn = column;
            }
            return true;
        }
        int rowsTop = y + 13;
        if (event.y() >= rowsTop && event.y() < tableBottom()) {
            int index = scroll + (int) ((event.y() - rowsTop) / ROW);
            if (tab == Tab.SHEET) {
                List<Portfolio.Entry> entries = sortedEntries();
                Portfolio.Entry clicked = index >= 0 && index < entries.size() ? entries.get(index) : null;
                selected = clicked == selected ? null : clicked;
            } else {
                List<Portfolio.Sale> sold = sortedSales();
                Portfolio.Sale clicked = index >= 0 && index < sold.size() ? sold.get(index) : null;
                selectedSale = clicked == selectedSale ? null : clicked;
            }
            rebuild();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
