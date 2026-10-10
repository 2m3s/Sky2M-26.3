package com.epic60869.sky2m.features.portfolio;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /s2 collections: how much each collection has gone up over the last day, week, month or since tracking began
 * ({@link CollectionPortfolio}), and a graph of one collection. Click a column header to sort by it, a row to graph it.
 */
public final class CollectionPortfolioScreen extends Screen {
    private enum Tab { TABLE, GRAPH }

    private static final int ROW = 14;
    private static final int ICON = 12;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
    private static final String[] HEADERS = {"Collection", "Total", "Gained", "Per day"};

    private static CollectionPortfolio.Period period = CollectionPortfolio.Period.DAY;
    private static boolean hideUnchanged = true;

    private Tab tab = Tab.TABLE;
    private String selected;
    private int scroll;
    /** Sorted column (-1 = biggest gain first) and direction. */
    private int sortColumn = -1;
    private boolean ascending;

    public CollectionPortfolioScreen() {
        super(Component.literal("Sky2M Collections"));
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int tableTop() { return top() + 54; }
    private int tableBottom() { return top() + panelHeight() - 8; }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int x = left() + 8, y = top() + 24;
        for (Tab t : Tab.values()) {
            String label = t == Tab.TABLE ? "Collections" : "Graph";
            addRenderableWidget(Button.builder(Component.literal(t == tab ? "» " + label + " «" : label), b -> {
                tab = t;
                scroll = 0;
                rebuild();
            }).bounds(x, y, 80, 20).build());
            x += 84;
        }
        x += 12;
        for (CollectionPortfolio.Period p : CollectionPortfolio.Period.values()) {
            addRenderableWidget(Button.builder(Component.literal(p == period ? "» " + p.label + " «" : p.label), b -> {
                period = p;
                scroll = 0;
                rebuild();
            }).bounds(x, y, 46, 20).build());
            x += 50;
        }
        int rx = left() + panelWidth() - 8 - 60;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> CollectionPortfolio.refresh())
            .bounds(rx, y, 60, 20).build());
        if (tab == Tab.TABLE) {
            rx -= 114;
            addRenderableWidget(Button.builder(Component.literal(hideUnchanged ? "Unchanged: Hidden" : "Unchanged: Shown"), b -> {
                hideUnchanged = !hideUnchanged;
                scroll = 0;
                rebuild();
            }).bounds(rx, y, 110, 20).build());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left(), top(), left() + panelWidth(), top() + panelHeight(), 0xFF181B21);
        g.fill(left(), top(), left() + panelWidth(), top() + 2, 0xFF55FF55);
        g.text(font, Component.literal("Collections").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), left() + 8, top() + 8, 0xFFFFFFFF, true);

        List<CollectionPortfolio.Change> changes = CollectionPortfolio.changes(period);
        long gainedTotal = 0;
        for (CollectionPortfolio.Change c : changes) gainedTotal += c.gained();
        long since = changes.isEmpty() ? 0 : changes.getFirst().since();
        Component summary = Component.literal("Gained: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(fmt(gainedTotal)).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(since > 0 ? "   since " + DATE.format(Instant.ofEpochMilli(since)) : "").withStyle(ChatFormatting.GRAY));
        g.text(font, summary, left() + 90, top() + 8, 0xFFFFFFFF, false);
        String status = CollectionPortfolio.status();
        long updated = CollectionPortfolio.lastUpdate();
        String right = !status.isEmpty() ? status : updated > 0 ? "updated " + ago(updated) : "";
        g.text(font, right, left() + panelWidth() - 8 - font.width(right), top() + 8, status.isEmpty() ? 0xFF7A8290 : 0xFFFFAA00, false);

        if (tab == Tab.TABLE) drawTable(g, changes, mouseX, mouseY);
        else drawGraph(g, changes);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    // ----- Table -----

    private List<CollectionPortfolio.Change> rows(List<CollectionPortfolio.Change> changes) {
        List<CollectionPortfolio.Change> rows = new ArrayList<>();
        for (CollectionPortfolio.Change c : changes) if (!hideUnchanged || c.gained() > 0) rows.add(c);
        if (sortColumn < 0) return rows;
        Comparator<CollectionPortfolio.Change> order = switch (sortColumn) {
            case 0 -> Comparator.comparing(c -> CollectionPortfolio.name(c.id()).toLowerCase(Locale.ROOT));
            case 1 -> Comparator.comparingLong(CollectionPortfolio.Change::total);
            case 2 -> Comparator.comparingLong(CollectionPortfolio.Change::gained);
            default -> Comparator.comparingDouble(CollectionPortfolio.Change::perDay);
        };
        rows.sort(ascending ? order : order.reversed());
        return rows;
    }

    private int[] columns() {
        int w = panelWidth() - 16;
        int x = left() + 8;
        return new int[]{x, x + w * 40 / 100, x + w * 60 / 100, x + w * 80 / 100};
    }

    private void drawTable(GuiGraphicsExtractor g, List<CollectionPortfolio.Change> changes, int mouseX, int mouseY) {
        int[] c = columns();
        int y = tableTop();
        for (int i = 0; i < HEADERS.length; i++) {
            String text = HEADERS[i] + (i == sortColumn ? (ascending ? " ▲" : " ▼") : "");
            g.text(font, text, c[i], y, i == sortColumn ? 0xFF9CFF9C : 0xFF55FF55, false);
        }
        g.fill(left() + 6, y + 10, left() + panelWidth() - 6, y + 11, 0xFF3A3F48);

        List<CollectionPortfolio.Change> rows = rows(changes);
        if (changes.isEmpty()) {
            g.text(font, "No collection history yet. Your collections are saved every 30 minutes while you're on SkyBlock.", c[0], y + 18, 0xFF7A8290, false);
            g.text(font, "Make sure the Collections API is on in your SkyBlock settings.", c[0], y + 30, 0xFF7A8290, false);
            return;
        }
        if (rows.isEmpty()) {
            g.text(font, "None of your collections went up in the last " + period.label + ".", c[0], y + 18, 0xFF7A8290, false);
            return;
        }
        int rowsVisible = (tableBottom() - y - 14) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - rowsVisible)));
        int ry = y + 16;
        for (int i = scroll; i < rows.size() && i < scroll + rowsVisible; i++) {
            CollectionPortfolio.Change ch = rows.get(i);
            boolean hover = mouseY >= ry - 3 && mouseY < ry + ROW - 3 && mouseX >= left() && mouseX < left() + panelWidth();
            if (ch.id().equals(selected)) g.fill(left() + 6, ry - 3, left() + panelWidth() - 6, ry + ROW - 3, 0xFF2E3440);
            else if (hover) g.fill(left() + 6, ry - 3, left() + panelWidth() - 6, ry + ROW - 3, 0xFF22262E);
            int nameX = c[0];
            ItemStack icon = CollectionPortfolio.icon(ch.id());
            if (icon != null && !icon.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(c[0], ry - 2);
                g.pose().scale(ICON / 16f, ICON / 16f);
                g.item(icon, 0, 0);
                g.pose().popMatrix();
                nameX += ICON + 3;
            }
            g.text(font, font.plainSubstrByWidth(CollectionPortfolio.name(ch.id()), c[1] - nameX - 6), nameX, ry, 0xFFFFFFFF, false);
            g.text(font, fmt(ch.total()), c[1], ry, 0xFFB8C0CC, false);
            g.text(font, ch.gained() > 0 ? "+" + fmt(ch.gained()) : "0", c[2], ry, ch.gained() > 0 ? 0xFF55FF55 : 0xFF7A8290, false);
            double perDay = ch.perDay();
            g.text(font, perDay > 0 ? Portfolio.coins(perDay) : "-", c[3], ry, 0xFFFFFFFF, false);
            ry += ROW;
        }
    }

    // ----- Graph -----

    private void drawGraph(GuiGraphicsExtractor g, List<CollectionPortfolio.Change> changes) {
        String id = selected;
        if (id == null && !changes.isEmpty()) id = changes.getFirst().id();
        int gx = left() + 70, gy = tableTop() + 14, gw = panelWidth() - 90, gh = tableBottom() - gy - 20;
        String label = id == null ? "Collection" : CollectionPortfolio.name(id) + " collection (click a row in Collections to pick another)";
        g.text(font, label, left() + 8, tableTop(), 0xFF55FF55, false);
        g.fill(gx, gy, gx + gw, gy + gh, 0xFF101318);
        g.outline(gx, gy, gw, gh, 0xFF3A3F48);
        List<long[]> points = id == null ? List.of() : CollectionPortfolio.graph(id, period);
        if (points.size() < 2) {
            g.text(font, "Not enough history yet. A snapshot is saved every 30 minutes while you're on SkyBlock.", gx + 8, gy + 8, 0xFF7A8290, false);
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
            if (lastX >= 0) line(g, lastX, lastY, x, y, 0xFF55FF55);
            g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF9CFF9C);
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

    // ----- Helpers -----

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String ago(long time) {
        long minutes = Math.max(0, (System.currentTimeMillis() - time) / 60_000L);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        if (minutes < 48 * 60) return minutes / 60 + "h ago";
        return minutes / (60 * 24) + "d ago";
    }

    // ----- Input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || tab != Tab.TABLE) return false;
        int y = tableTop();
        int[] c = columns();
        if (event.y() >= y - 2 && event.y() < y + 10) {
            for (int i = c.length - 1; i >= 0; i--) {
                if (event.x() < c[i]) continue;
                // First click sorts biggest first (names A-Z), the next reverses it.
                ascending = i == sortColumn ? !ascending : i == 0;
                sortColumn = i;
                return true;
            }
            return false;
        }
        int rowsTop = y + 13;
        if (event.y() >= rowsTop && event.y() < tableBottom()) {
            List<CollectionPortfolio.Change> rows = rows(CollectionPortfolio.changes(period));
            int index = scroll + (int) ((event.y() - rowsTop) / ROW);
            if (index >= 0 && index < rows.size()) {
                String id = rows.get(index).id();
                selected = id.equals(selected) ? null : id;
                return true;
            }
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
