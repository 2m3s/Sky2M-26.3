package com.epic60869.sky2m.features.portfolio;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 *
 * <p>Drawn like the /s2 settings ({@link Sky2MGuiStyle}): a header bar with the title, a toolbar with the tabs and
 * periods as category-style entries and MoulConfig buttons, and the table or graph in a panel's sunken box.
 */
public final class CollectionPortfolioScreen extends Screen {
    private enum Tab { TABLE, GRAPH }

    private static final int ROW = 14;
    private static final int ICON = 12;
    /** Secondary text, between the settings' grey and dark grey. */
    private static final int GREY = 0xFF808080;
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

    /** A clickable toolbar entry drawn this frame. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();

    public CollectionPortfolioScreen() {
        super(Component.literal("Sky2M Collections"));
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int toolbarTop() { return top() + 29; }
    private int toolbarRow() { return toolbarTop() + 4; }
    private int contentTop() { return toolbarTop() + 28; }
    /** The sunken box the table or graph sits in, under the summary line. */
    private int insetLeft() { return left() + 9; }
    private int insetRight() { return left() + panelWidth() - 9; }
    private int insetTop() { return contentTop() + 16; }
    private int insetBottom() { return top() + panelHeight() - 10; }
    private int tableTop() { return insetTop() + 5; }
    private int tableBottom() { return insetBottom() - 2; }
    /** Table rows span these x's (the scroll bar is right of them). */
    private int rowLeft() { return insetLeft() + 3; }
    private int rowRight() { return insetRight() - 12; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelWidth(), panelHeight(), true);

        String status = CollectionPortfolio.status();
        long updated = CollectionPortfolio.lastUpdate();
        String right = !status.isEmpty() ? status : updated > 0 ? "updated " + ago(updated) : "";
        drawHeader(g, right, !status.isEmpty());
        hits.clear();
        drawToolbar(g, mouseX, mouseY);

        Sky2MGuiStyle.panel(g, left() + 4, contentTop(), panelWidth() - 9, insetBottom() + 5 - contentTop(), false);
        List<CollectionPortfolio.Change> changes = CollectionPortfolio.changes(period);
        long gainedTotal = 0;
        for (CollectionPortfolio.Change c : changes) gainedTotal += c.gained();
        long since = changes.isEmpty() ? 0 : changes.getFirst().since();
        // The summary sits where the settings put a category's description.
        Component summary = Component.literal("Gained: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(fmt(gainedTotal)).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(since > 0 ? "   since " + DATE.format(Instant.ofEpochMilli(since)) : "").withStyle(ChatFormatting.GRAY));
        g.text(font, summary, insetLeft() + 1, contentTop() + 5, Sky2MGuiStyle.DESCRIPTION, true);
        Sky2MGuiStyle.inset(g, insetLeft(), insetTop(), insetRight(), insetBottom());

        if (tab == Tab.TABLE) drawTable(g, changes, mouseX, mouseY);
        else drawGraph(g, changes);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /** The settings' header bar: the title in the middle, the last update (or a warning, orange) on the right. */
    private void drawHeader(GuiGraphicsExtractor g, String status, boolean warning) {
        int x = left() + 4, w = panelWidth() - 9;
        Component title = Sky2MGuiStyle.title("Collections");
        int titleX = x + (w - font.width(title)) / 2;
        int statusRoom = x + w - 6 - (titleX + font.width(title) + 10);
        if (statusRoom < font.width(status)) {
            // Too narrow for both with the title in the middle: title on the left, the status after it.
            titleX = x + 6;
            statusRoom = x + w - 6 - (titleX + font.width(title) + 10);
        }
        Sky2MGuiStyle.panel(g, x, top() + 5, w, 20, false);
        g.text(font, title, titleX, top() + 11, Sky2MGuiStyle.TITLE, false);
        String fit = font.plainSubstrByWidth(status, Math.max(0, statusRoom));
        g.text(font, fit, x + w - 6 - font.width(fit), top() + 11, warning ? 0xFFFFAA00 : Sky2MGuiStyle.TITLE, false);
    }

    /** Tabs and periods as category-style entries; Refresh and the unchanged switch as MoulConfig buttons. */
    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.panel(g, left() + 4, toolbarTop(), panelWidth() - 9, 24, false);
        int x = left() + 12;
        for (Tab t : Tab.values()) {
            x += entry(g, t == Tab.TABLE ? "Collections" : "Graph", x, t == tab, mouseX, mouseY, () -> {
                tab = t;
                scroll = 0;
            }) + 12;
        }
        x += 8;
        g.fill(x - 10, toolbarRow() + 2, x - 9, toolbarRow() + 14, Sky2MGuiStyle.LINE);
        for (CollectionPortfolio.Period p : CollectionPortfolio.Period.values()) {
            x += entry(g, p.label, x, p == period, mouseX, mouseY, () -> {
                period = p;
                scroll = 0;
            }) + 10;
        }
        int bw = Sky2MGuiStyle.buttonWidth(font, "Refresh");
        int bx = left() + panelWidth() - 10 - bw;
        button(g, "Refresh", bx, bw, mouseX, mouseY, CollectionPortfolio::refresh);
        if (tab == Tab.TABLE) {
            bw = Sky2MGuiStyle.buttonWidth(font, "Unchanged: Hidden");
            bx -= bw + 4;
            button(g, hideUnchanged ? "Unchanged: Hidden" : "Unchanged: Shown", bx, bw, mouseX, mouseY, () -> {
                hideUnchanged = !hideUnchanged;
                scroll = 0;
            });
        }
    }

    /** A category-style toolbar entry, clickable; returns its width. */
    private int entry(GuiGraphicsExtractor g, String text, int x, boolean isSelected, int mouseX, int mouseY, Runnable action) {
        int w = font.width(text);
        Hit hit = new Hit(x - 3, toolbarRow(), x + w + 3, toolbarRow() + 16, action);
        hits.add(hit);
        return Sky2MGuiStyle.category(g, font, text, x, toolbarRow() + 4, isSelected, inside(hit, mouseX, mouseY));
    }

    private void button(GuiGraphicsExtractor g, String text, int x, int w, int mouseX, int mouseY, Runnable action) {
        Hit hit = new Hit(x, toolbarRow(), x + w, toolbarRow() + 16, action);
        hits.add(hit);
        Sky2MGuiStyle.button(g, font, text, x, toolbarRow(), w, 16, inside(hit, mouseX, mouseY));
    }

    private static boolean inside(Hit hit, double x, double y) {
        return x >= hit.x0() && x < hit.x1() && y >= hit.y0() && y < hit.y1();
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
        int x = insetLeft() + 6;
        int w = rowRight() - 2 - x;
        return new int[]{x, x + w * 40 / 100, x + w * 60 / 100, x + w * 80 / 100};
    }

    /** Table rows that fit: each is {@link #ROW} tall, the first one's highlight starting 13px below the headers. */
    private int rowsVisible() {
        return Math.max(1, (tableBottom() - tableTop() - 13) / ROW);
    }

    private void drawTable(GuiGraphicsExtractor g, List<CollectionPortfolio.Change> changes, int mouseX, int mouseY) {
        int[] c = columns();
        int y = tableTop();
        for (int i = 0; i < HEADERS.length; i++) {
            String text = HEADERS[i] + (i == sortColumn ? (ascending ? " ▲" : " ▼") : "");
            g.text(font, text, c[i], y, i == sortColumn ? Sky2MGuiStyle.SELECTED : Sky2MGuiStyle.HEADING, false);
        }
        g.fill(rowLeft(), y + 10, rowRight(), y + 11, Sky2MGuiStyle.LINE);

        List<CollectionPortfolio.Change> rows = rows(changes);
        if (changes.isEmpty()) {
            int ly = y + 18;
            for (var line : font.split(Component.literal("No collection history yet. Your collections are saved every 30 minutes while"
                + " you're on SkyBlock. Make sure the Collections API is on in your SkyBlock settings."), rowRight() - c[0])) {
                g.text(font, line, c[0], ly, GREY, false);
                ly += 10;
            }
            return;
        }
        if (rows.isEmpty()) {
            g.text(font, "None of your collections went up in the last " + period.label + ".", c[0], y + 18, GREY, false);
            return;
        }
        int rowsVisible = rowsVisible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - rowsVisible)));
        int ry = y + 16;
        for (int i = scroll; i < rows.size() && i < scroll + rowsVisible; i++) {
            CollectionPortfolio.Change ch = rows.get(i);
            boolean hover = mouseY >= ry - 3 && mouseY < ry + ROW - 3 && mouseX >= rowLeft() && mouseX < rowRight();
            boolean isSelected = ch.id().equals(selected);
            if (isSelected) g.fill(rowLeft(), ry - 3, rowRight(), ry + ROW - 3, Sky2MGuiStyle.ROW_HOVER);
            else if (hover) g.fill(rowLeft(), ry - 3, rowRight(), ry + ROW - 3, Sky2MGuiStyle.ROW);
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
            // The graphed collection is aqua and underlined, like the settings' selected category.
            String name = font.plainSubstrByWidth(CollectionPortfolio.name(ch.id()), c[1] - nameX - 6);
            g.text(font, isSelected ? Component.literal(name).withStyle(ChatFormatting.UNDERLINE) : Component.literal(name), nameX, ry,
                isSelected ? Sky2MGuiStyle.SELECTED : Sky2MGuiStyle.TEXT, false);
            g.text(font, fmt(ch.total()), c[1], ry, Sky2MGuiStyle.TEXT_DIM, false);
            g.text(font, ch.gained() > 0 ? "+" + fmt(ch.gained()) : "0", c[2], ry, ch.gained() > 0 ? 0xFF55FF55 : GREY, false);
            double perDay = ch.perDay();
            g.text(font, perDay > 0 ? Portfolio.coins(perDay) : "-", c[3], ry, Sky2MGuiStyle.TEXT, false);
            ry += ROW;
        }
        Sky2MGuiStyle.scrollbar(g, insetRight() - 10, insetTop() + 4, insetBottom() - 4, scroll, rowsVisible, rows.size());
    }

    // ----- Graph -----

    private void drawGraph(GuiGraphicsExtractor g, List<CollectionPortfolio.Change> changes) {
        String id = selected;
        if (id == null && !changes.isEmpty()) id = changes.getFirst().id();
        int gx = insetLeft() + 62, gy = tableTop() + 14, gw = insetRight() - 10 - gx, gh = tableBottom() - gy - 14;
        String label = id == null ? "Collection" : CollectionPortfolio.name(id) + " collection (click a row in Collections to pick another)";
        g.text(font, font.plainSubstrByWidth(label, insetRight() - insetLeft() - 12), insetLeft() + 6, tableTop(), Sky2MGuiStyle.HEADING, false);
        // The plot area in the settings' scroll bar track colour, edged like its panels.
        g.fill(gx, gy, gx + gw, gy + gh, 0xFF101010);
        g.outline(gx, gy, gw, gh, 0xFF303036);
        List<long[]> points = id == null ? List.of() : CollectionPortfolio.graph(id, period);
        if (points.size() < 2) {
            int ly = gy + 8;
            for (var line : font.split(Component.literal("Not enough history yet. A snapshot is saved every 30 minutes while you're on SkyBlock."), gw - 16)) {
                g.text(font, line, gx + 8, ly, GREY, false);
                ly += 10;
            }
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
            g.fill(gx, ly, gx + gw, ly + 1, Sky2MGuiStyle.ROW_HOVER);
            String v = Portfolio.coins(minV + (maxV - minV) * i / 4.0);
            g.text(font, v, gx - 4 - font.width(v), ly - 4, GREY, false);
        }
        g.text(font, DATE.format(Instant.ofEpochMilli(minT)), gx, gy + gh + 4, GREY, false);
        String end = DATE.format(Instant.ofEpochMilli(maxT));
        g.text(font, end, gx + gw - font.width(end), gy + gh + 4, GREY, false);
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
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        // Tabs, periods and buttons, where they were drawn last frame.
        for (Hit hit : List.copyOf(hits)) {
            if (inside(hit, event.x(), event.y())) {
                Sky2MGuiStyle.click();
                hit.action().run();
                return true;
            }
        }
        if (tab != Tab.TABLE) return false;
        int y = tableTop();
        int[] c = columns();
        if (event.y() >= y - 2 && event.y() < y + 10 && event.x() < rowRight()) {
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
        if (event.y() >= rowsTop && event.y() < rowsTop + rowsVisible() * ROW && event.x() >= rowLeft() && event.x() < rowRight()) {
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
