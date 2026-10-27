package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /s2 session: one section per day, with a Total tab and a tab for every profit tracker (today, or a past day with the
 * arrows or the All Days list). The green profit at the top is worked out again with the latest prices every Price
 * Refresh seconds. The Total tab lists every tracker; click one to open its tab.
 *
 * <p>Drawn like the /s2 settings ({@link Sky2MGuiStyle}), the same as /s2 leaderboard and /s2 collections: a header bar
 * with the title, a toolbar with the views and tracker tabs as category-style entries and MoulConfig buttons, and the
 * day's numbers in a panel's sunken box. Every clickable thing is recorded where it was drawn, so clicks hit what's seen.
 */
public final class SessionTrackerScreen extends Screen {
    private static final int ROW = 14, DROP_ROW = 18, GOLD = 0xFFFFD35A, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
    /** Secondary text, between the settings' grey and dark grey (as in /s2 collections). */
    private static final int GREY = 0xFF808080;
    /** The tabs: every profit tracker (shown after Total), then Total. */
    private static final int TOTAL = SessionTracker.ACTIVITIES.length;

    /** -1 is this session, 0 the last game's, and so on. */
    private int index;
    private int tab = TOTAL;
    private int scroll;
    private boolean confirmDelete;
    private boolean showDays;

    /** A clickable thing drawn this frame. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();
    /** What right-clicking does where it's drawn: a drop's row cycles its price source. */
    private final List<Hit> rightHits = new ArrayList<>();
    /** The Price Source the drops were last priced by (what an item's Default means). */
    private FeatureConfigs.ProfitPriceSource priceSource = FeatureConfigs.ProfitPriceSource.INSTANT_SELL;

    /** The profit and item values, worked out again every Price Refresh seconds. */
    private long pricedAt;
    private final double[] profits = new double[TOTAL];
    private final boolean[] unknown = new boolean[TOTAL];
    private final List<List<Item>> items = new ArrayList<>();
    private final Map<Integer, Double> dayProfits = new java.util.HashMap<>();

    private record Item(String id, String name, long amount, double value) {}

    private SessionTrackerScreen(int index) {
        super(Component.literal("Session Tracker"));
        this.index = index;
        tab = busiestTab();
    }

    public static int open(int index) {
        Compat.queueOpenScreen(new SessionTrackerScreen(index));
        return 1;
    }

    private SessionTracker.Session session() {
        return SessionTracker.session(index);
    }

    private boolean live() {
        return index < 0;
    }

    /** The activity you did most, or Total if you did more than one (or none). */
    private int busiestTab() {
        SessionTracker.Session s = session();
        int found = -1;
        for (int i = 0; i < TOTAL; i++) {
            if (!s.has(SessionTracker.ACTIVITIES[i])) continue;
            if (found >= 0) return TOTAL;
            found = i;
        }
        return found < 0 ? TOTAL : found;
    }

    private void reprice(boolean force) {
        FeatureConfigs.SessionTrackerSettings c = SessionTracker.config();
        long every = (c == null ? 30 : Math.clamp(c.priceRefreshSeconds, 5, 300)) * 1000L;
        long now = System.currentTimeMillis();
        if (!force && now - pricedAt < every && items.size() == TOTAL) return;
        pricedAt = now;
        dayProfits.clear();
        FeatureConfigs.ProfitPriceSource source = c == null || c.priceSource == null ? FeatureConfigs.ProfitPriceSource.INSTANT_SELL : c.priceSource;
        priceSource = source;
        items.clear();
        SessionTracker.Session s = session();
        for (int i = 0; i < TOTAL; i++) {
            ProfitTracker.Preset preset = SessionTracker.ACTIVITIES[i];
            ProfitTracker.Stats stats = s.get(preset);
            boolean[] u = {false};
            profits[i] = SessionTracker.profit(preset, stats, u);
            unknown[i] = u[0];
            List<Item> list = new ArrayList<>();
            if (stats != null) {
                for (Map.Entry<String, Long> e : stats.items.entrySet()) {
                    double unit = ProfitItemPrices.price(e.getKey(), source);
                    list.add(new Item(e.getKey(), ProfitTrackerHud.name(e.getKey()), e.getValue(), unit > 0 ? unit * e.getValue() : -1));
                }
            }
            list.sort(Comparator.comparingDouble(Item::value).reversed());
            items.add(list);
        }
    }

    private double shownProfit() {
        if (tab < TOTAL) return profits[tab];
        double total = 0;
        for (double p : profits) total += p;
        return total;
    }

    private double dayProfit(int dayIndex, SessionTracker.Session session) {
        return dayProfits.computeIfAbsent(dayIndex, ignored -> {
            double total = 0;
            for (ProfitTracker.Preset preset : SessionTracker.ACTIVITIES) {
                total += SessionTracker.profit(preset, session.get(preset), null);
            }
            return total;
        });
    }

    private void go(int newIndex) {
        index = newIndex;
        scroll = 0;
        confirmDelete = false;
        tab = busiestTab();
        reprice(true);
    }

    /** Opens a day from the All Days list or its bar on the chart, on its Total tab. */
    private void openDay(int dayIndex) {
        index = dayIndex;
        showDays = false;
        scroll = 0;
        tab = TOTAL;
        reprice(true);
    }

    // ----- Layout -----

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int toolbarTop() { return top() + 29; }
    private int toolbarRow() { return toolbarTop() + 4; }
    /** One row for the views, the day and the buttons; under it (on a day) the tracker tabs, as many rows as they need. */
    private int toolbarHeight() { return 24 + (showDays ? 0 : 18 * (tabLayout()[TOTAL][1] + 1)); }
    private int contentTop() { return toolbarTop() + toolbarHeight() + 4; }
    /** The sunken box the day or the list sits in; on a day, under the big profit. */
    private int insetLeft() { return left() + 9; }
    private int insetRight() { return left() + panelWidth() - 9; }
    private int insetTop() { return contentTop() + (showDays ? 16 : 28); }
    private int insetBottom() { return top() + panelHeight() - 10; }
    /** List rows span these x's (the scroll bar is right of them). */
    private int rowLeft() { return insetLeft() + 3; }
    private int rowRight() { return insetRight() - 12; }

    /** Tab {@code pos} 0 is Total, then the trackers in order. */
    private static int tabAt(int pos) {
        return pos == 0 ? TOTAL : pos - 1;
    }

    private static String tabLabel(int t) {
        return t < TOTAL ? SessionTracker.name(SessionTracker.ACTIVITIES[t]) : "Total";
    }

    /**
     * Where each tracker tab goes ({x, row}, by position), wrapping onto another row when the screen is too narrow for
     * all 12 on one, so none is cut off at a large GUI scale.
     */
    private int[][] tabLayout() {
        int[][] out = new int[TOTAL + 1][];
        int x0 = left() + 12, right = left() + panelWidth() - 12, x = x0, row = 0;
        for (int pos = 0; pos <= TOTAL; pos++) {
            int w = font.width(tabLabel(tabAt(pos)));
            if (x > x0 && x + w > right) {
                x = x0;
                row++;
            }
            out[pos] = new int[]{x, row};
            x += w + 10;
        }
        return out;
    }

    // ----- Drawing -----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        reprice(false);
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelWidth(), panelHeight(), true);
        Sky2MGuiStyle.header(g, font, left() + 4, top() + 5, panelWidth() - 9, Sky2MGuiStyle.title("Session"));
        hits.clear();
        rightHits.clear();
        drawToolbar(g, mouseX, mouseY);

        Sky2MGuiStyle.panel(g, left() + 4, contentTop(), panelWidth() - 9, insetBottom() + 5 - contentTop(), false);
        if (showDays) {
            // The hint sits where the settings put a category's description.
            g.text(font, font.plainSubstrByWidth("Click a day (or its bar) to open its total and tracker breakdown.",
                insetRight() - insetLeft() - 2), insetLeft() + 1, contentTop() + 5, Sky2MGuiStyle.DESCRIPTION, true);
            Sky2MGuiStyle.inset(g, insetLeft(), insetTop(), insetRight(), insetBottom());
            drawDays(g, mouseX, mouseY);
        } else {
            drawDay(g, mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /**
     * The views (this day, or every day) as category-style entries, then the day with the arrows (older to the right)
     * and Copy/Delete as MoulConfig buttons; on a day, the tracker tabs below.
     */
    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.panel(g, left() + 4, toolbarTop(), panelWidth() - 9, toolbarHeight(), false);
        int x = left() + 12;
        x += entry(g, "Day", x, toolbarRow(), !showDays, mouseX, mouseY, () -> {
            showDays = false;
            scroll = 0;
        }) + 12;
        x += entry(g, "All Days", x, toolbarRow(), showDays, mouseX, mouseY, () -> {
            showDays = true;
            scroll = 0;
        }) + 12;
        if (showDays) {
            int days = SessionTracker.pastSessions() + 1;
            String count = days + (days == 1 ? " day" : " days");
            g.text(font, count, left() + panelWidth() - 12 - font.width(count), toolbarRow() + 4, GREY, false);
            return;
        }

        // Copy this day (or this tracker) as text, to paste in Discord; Delete (twice) a past day.
        int right = left() + panelWidth() - 10;
        if (!live()) {
            int bw = Sky2MGuiStyle.buttonWidth(font, "Delete");
            right -= bw;
            button(g, confirmDelete ? "Sure?" : "Delete", right, toolbarRow(), bw, mouseX, mouseY, () -> {
                if (!confirmDelete) {
                    confirmDelete = true;
                    return;
                }
                SessionTracker.delete(index);
                go(Math.min(index, SessionTracker.pastSessions() - 1));
            });
            right -= 4;
        }
        boolean justCopied = System.currentTimeMillis() - copiedAt < 2_000L;
        int bw = Sky2MGuiStyle.buttonWidth(font, "Copied!");
        right -= bw;
        button(g, justCopied ? "Copied!" : "Copy", right, toolbarRow(), bw, mouseX, mouseY, () -> {
            minecraft.keyboardHandler.setClipboard(exportText());
            copiedAt = System.currentTimeMillis();
        });

        // Which day: this one, or a past one with the arrows (older to the right).
        x += 8;
        g.fill(x - 10, toolbarRow() + 2, x - 9, toolbarRow() + 14, Sky2MGuiStyle.LINE);
        SessionTracker.Session s = session();
        int last = SessionTracker.pastSessions() - 1;
        if (index > -1) button(g, "◀", x, toolbarRow(), 16, mouseX, mouseY, () -> go(index - 1));
        x += 20;
        String which = live() ? "Today · " + SessionTracker.date(s.started)
            : SessionTracker.date(s.started) + " · Day " + (index + 1) + "/" + (last + 1);
        which = font.plainSubstrByWidth(which, Math.max(0, right - 26 - x));
        g.text(font, which, x, toolbarRow() + 4, Sky2MGuiStyle.TEXT, false);
        x += font.width(which) + 4;
        if (index < last && x + 16 <= right - 4) button(g, "▶", x, toolbarRow(), 16, mouseX, mouseY, () -> go(index + 1));

        int[][] layout = tabLayout();
        for (int pos = 0; pos <= TOTAL; pos++) {
            int t = tabAt(pos);
            entry(g, tabLabel(t), layout[pos][0], toolbarRow() + 18 * (layout[pos][1] + 1), tab == t, mouseX, mouseY, () -> {
                tab = t;
                scroll = 0;
            });
        }
    }

    /** A category-style toolbar entry, clickable; returns its width. */
    private int entry(GuiGraphicsExtractor g, String text, int x, int y, boolean selected, int mouseX, int mouseY, Runnable action) {
        int w = font.width(text);
        Hit hit = new Hit(x - 3, y, x + w + 3, y + 16, action);
        hits.add(hit);
        return Sky2MGuiStyle.category(g, font, text, x, y + 4, selected, inside(hit, mouseX, mouseY));
    }

    private void button(GuiGraphicsExtractor g, String text, int x, int y, int w, int mouseX, int mouseY, Runnable action) {
        Hit hit = new Hit(x, y, x + w, y + 16, action);
        hits.add(hit);
        Sky2MGuiStyle.button(g, font, text, x, y, w, 16, inside(hit, mouseX, mouseY));
    }

    private static boolean inside(Hit hit, double x, double y) {
        return x >= hit.x0() && x < hit.x1() && y >= hit.y0() && y < hit.y1();
    }

    // ----- All days -----

    private static final int CHART_DAYS = 30, CHART_H = 64;

    /** The 30-day chart, then every day as a row (today first) with its profit; click either to open the day. */
    private void drawDays(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int rowY = drawChart(g, insetLeft() + 6, insetTop() + 5, insetRight() - 6 - (insetLeft() + 6), mouseX, mouseY) + 8;
        int visible = Math.max(1, (insetBottom() - 2 - rowY) / ROW);
        int count = SessionTracker.pastSessions() + 1;
        scroll = Math.clamp(scroll, 0, Math.max(0, count - visible));
        for (int row = 0; row < visible && row + scroll < count; row++) {
            int dayIndex = row + scroll == 0 ? -1 : row + scroll - 1;
            SessionTracker.Session daySession = SessionTracker.session(dayIndex);
            int ry = rowY + row * ROW;
            Hit hit = new Hit(rowLeft(), ry, rowRight(), ry + ROW, () -> openDay(dayIndex));
            hits.add(hit);
            if (inside(hit, mouseX, mouseY)) g.fill(rowLeft(), ry, rowRight(), ry + ROW, Sky2MGuiStyle.ROW_HOVER);
            else if ((row + scroll) % 2 == 1) g.fill(rowLeft(), ry, rowRight(), ry + ROW, Sky2MGuiStyle.ROW);
            double value = dayProfit(dayIndex, daySession);
            String profit = signed(value);
            int profitX = rowRight() - 4 - font.width(profit);
            String when = dayIndex < 0 ? "Today" : SessionTracker.date(daySession.started);
            g.text(font, font.plainSubstrByWidth(when, Math.max(0, profitX - rowLeft() - 10)), rowLeft() + 3, ry + 3,
                dayIndex < 0 ? Sky2MGuiStyle.SELECTED : Sky2MGuiStyle.TEXT, false);
            g.text(font, profit, profitX, ry + 3, value >= 0 ? GREEN : RED, false);
        }
        Sky2MGuiStyle.scrollbar(g, insetRight() - 10, rowY, insetBottom() - 4, scroll, visible, count);
    }

    /**
     * The last 30 days' profit as bars (oldest on the left, today on the right), green or red, the best day in gold,
     * with the date and profit when hovered. Clicking a bar opens that day. Returns the y below the chart.
     */
    private int drawChart(GuiGraphicsExtractor g, int x0, int y, int w, int mouseX, int mouseY) {
        int days = Math.min(CHART_DAYS, SessionTracker.pastSessions() + 1);
        int base = y + CHART_H / 2;
        // The plot area in the settings' scroll bar track colour, edged like its panels (as /s2 collections' graph).
        g.fill(x0, y, x0 + w, y + CHART_H, 0xFF101010);
        g.outline(x0, y, w, CHART_H, 0xFF303036);
        g.fill(x0 + 1, base, x0 + w - 1, base + 1, Sky2MGuiStyle.LINE);
        if (days < 2) {
            g.centeredText(font, font.plainSubstrByWidth("Your daily profit shows here as a chart after a couple of days.", w - 8),
                x0 + w / 2, y + CHART_H / 2 - 12, GREY);
            return y + CHART_H;
        }
        double[] values = new double[days];
        double most = 1;
        int best = 0;
        for (int i = 0; i < days; i++) {
            int dayIndex = i == 0 ? -1 : i - 1; // i = 0 is today
            values[i] = dayProfit(dayIndex, SessionTracker.session(dayIndex));
            most = Math.max(most, Math.abs(values[i]));
            if (values[i] > values[best]) best = i;
        }
        int inner = w - 2, slot = Math.max(3, inner / CHART_DAYS);
        int hovered = -1;
        for (int i = 0; i < days; i++) {
            int bx = x0 + 1 + inner - (i + 1) * slot + 1;
            int h = (int) Math.round(Math.abs(values[i]) / most * (CHART_H / 2 - 4));
            int colour = i == best && values[i] > 0 ? GOLD : values[i] >= 0 ? 0xFF3FBF5F : 0xFFBF3F3F;
            int dayIndex = i == 0 ? -1 : i - 1;
            Hit hit = new Hit(bx, y, bx + slot - 1, y + CHART_H, () -> openDay(dayIndex));
            hits.add(hit);
            if (inside(hit, mouseX, mouseY)) {
                hovered = i;
                g.fill(bx - 1, y + 1, bx + slot - 1, y + CHART_H - 1, 0x20FFFFFF);
            }
            if (values[i] >= 0) g.fill(bx, base - h, bx + slot - 2, base, colour);
            else g.fill(bx, base + 1, bx + slot - 2, base + 1 + h, colour);
        }
        g.text(font, "Best: " + signed(values[best]), x0 + 4, y + 4, Sky2MGuiStyle.DESCRIPTION, false);
        if (hovered >= 0) {
            int dayIndex = hovered == 0 ? -1 : hovered - 1;
            String when = dayIndex < 0 ? "Today" : SessionTracker.date(SessionTracker.session(dayIndex).started);
            String text = when + ": " + signed(values[hovered]);
            g.text(font, text, x0 + w - 4 - font.width(text), y + 4, Sky2MGuiStyle.TEXT, false);
        }
        return y + CHART_H;
    }

    // ----- One day -----

    /** The day's (or tracker's) profit big at the top, then Total or one tracker in the sunken box. */
    private void drawDay(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SessionTracker.Session s = session();
        // The profit, big and green, kept up to date with the prices.
        double profit = shownProfit();
        String profitText = (profit >= 0 ? "+" : "-") + ProfitTrackerHud.coins(Math.abs(profit));
        g.pose().pushMatrix();
        g.pose().translate(insetLeft() + 1, contentTop() + 6);
        g.pose().scale(2f, 2f);
        g.text(font, profitText, 0, 0, profit >= 0 ? GREEN : RED, true);
        g.pose().popMatrix();
        boolean anyUnknown = tab < TOTAL ? unknown[tab] : anyUnknown();
        long ago = (System.currentTimeMillis() - pricedAt) / 1000;
        int descX = insetLeft() + 1 + font.width(profitText) * 2 + 10, descY = contentTop() + 10;
        String desc = font.plainSubstrByWidth((anyUnknown ? "Known profit" : "Profit") + " · prices updated " + ago + "s ago",
            Math.max(0, insetRight() - 2 - descX));
        g.text(font, desc, descX, descY, Sky2MGuiStyle.DESCRIPTION, true);
        String hint = !s.hasAny() ? "" : tab == TOTAL ? "Click an activity for its drops."
            : s.has(SessionTracker.ACTIVITIES[tab]) && !items.get(tab).isEmpty() ? "Click a drop to change its amount or remove it." : "";
        // The hint goes on the right when there's room for it next to the description.
        if (!hint.isEmpty() && descX + font.width(desc) + 12 + font.width(hint) <= insetRight() - 2) {
            g.text(font, hint, insetRight() - 2 - font.width(hint), descY, GREY, false);
        }
        Sky2MGuiStyle.inset(g, insetLeft(), insetTop(), insetRight(), insetBottom());

        if (!s.hasAny()) {
            message(g, SessionTracker.on() ? "No profit tracked for this day yet."
                : "Turn on the Session Tracker (Misc > Profit Trackers > Session Tracker).");
        } else if (tab == TOTAL) {
            total(g, mouseX, mouseY);
        } else {
            activity(g, SessionTracker.ACTIVITIES[tab], mouseX, mouseY);
        }
    }

    /** A grey line in the middle of the sunken box, wrapped when it's narrow. */
    private void message(GuiGraphicsExtractor g, String text) {
        int y = insetTop() + 20;
        for (var line : font.split(Component.literal(text), insetRight() - insetLeft() - 16)) {
            g.text(font, line, (insetLeft() + insetRight() - font.width(line)) / 2, y, GREY, false);
            y += 10;
        }
    }

    private int[] totalColumns() {
        int x = insetLeft() + 6;
        int w = rowRight() - 2 - x;
        return new int[]{x, x + w * 32 / 100, x + w * 56 / 100, x + w * 76 / 100};
    }

    /** Total: the day's numbers, then every activity as a row (click one for its tab). */
    private void total(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        SessionTracker.Session s = session();
        int x = insetLeft() + 6, half = (insetLeft() + insetRight()) / 2, y = insetTop() + 5;
        long playtime = s.playtime();
        double profit = shownProfit();
        stat(g, x, y, half - 6 - x, "Date", SessionTracker.date(s.firstActiveAny()) + " at " + SessionTracker.time(s.firstActiveAny()));
        stat(g, half, y, insetRight() - 6 - half, "Total profit", signed(profit));
        y += 11;
        stat(g, x, y, half - 6 - x, "Total playtime", SessionTracker.duration(playtime));
        stat(g, half, y, insetRight() - 6 - half, "Profit per hour", playtime < 60_000 ? "-" : signed(profit * 3_600_000.0 / playtime));
        y += 14;
        g.fill(rowLeft(), y, insetRight() - 3, y + 1, Sky2MGuiStyle.LINE);
        y += 5;

        int[] c = totalColumns();
        String[] heads = {"Activity", "Started", "Playtime", "Profit"};
        for (int i = 0; i < heads.length; i++) g.text(font, heads[i], c[i], y, Sky2MGuiStyle.HEADING, false);
        g.fill(rowLeft(), y + 10, rowRight(), y + 11, Sky2MGuiStyle.LINE);
        int rowsTop = y + 13;
        int visible = Math.max(1, (insetBottom() - 2 - rowsTop) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, TOTAL - visible));
        for (int i = scroll; i < TOTAL && i < scroll + visible; i++) {
            ProfitTracker.Preset preset = SessionTracker.ACTIVITIES[i];
            int ry = rowsTop + (i - scroll) * ROW;
            boolean has = s.has(preset);
            boolean over = false;
            if (has) {
                int t = i;
                Hit hit = new Hit(rowLeft(), ry, rowRight(), ry + ROW, () -> {
                    tab = t;
                    scroll = 0;
                });
                hits.add(hit);
                over = inside(hit, mouseX, mouseY);
            }
            if (over) g.fill(rowLeft(), ry, rowRight(), ry + ROW, Sky2MGuiStyle.ROW_HOVER);
            else if (i % 2 == 1) g.fill(rowLeft(), ry, rowRight(), ry + ROW, Sky2MGuiStyle.ROW);
            int ty = ry + 3;
            int colour = 0xFF000000 | net.minecraft.network.chat.TextColor.fromLegacyFormat(SessionTracker.colour(preset)).getValue();
            String status = live() && SessionTracker.running(preset) ? " §a●" : "";
            g.text(font, fit(SessionTracker.name(preset) + status, c, 0), c[0], ty, has ? colour : Sky2MGuiStyle.MUTED, false);
            if (has) {
                ProfitTracker.Stats stats = s.get(preset);
                String started = SessionTracker.date(s.firstActive(preset)).replaceFirst(" \\d{4}$", "") + " " + SessionTracker.time(s.firstActive(preset));
                g.text(font, fit(started, c, 1), c[1], ty, Sky2MGuiStyle.TEXT_DIM, false);
                g.text(font, fit(SessionTracker.duration(stats.activeMs), c, 2), c[2], ty, Sky2MGuiStyle.TEXT_DIM, false);
                g.text(font, fit(signed(profits[i]), c, 3), c[3], ty, profits[i] >= 0 ? GREEN : RED, false);
            } else {
                g.text(font, "-", c[1], ty, Sky2MGuiStyle.MUTED, false);
            }
        }
        Sky2MGuiStyle.scrollbar(g, insetRight() - 10, rowsTop, insetBottom() - 4, scroll, visible, TOTAL);
    }

    /** {@code text} cut to column {@code i}'s width, so a narrow screen doesn't run columns into each other. */
    private String fit(String text, int[] c, int i) {
        int end = i + 1 < c.length ? c[i + 1] - 4 : rowRight() - 2;
        return font.plainSubstrByWidth(text, Math.max(0, end - c[i]));
    }

    /** One activity: when, how long, its numbers and its drops. */
    private void activity(GuiGraphicsExtractor g, ProfitTracker.Preset preset, int mouseX, int mouseY) {
        SessionTracker.Session s = session();
        ProfitTracker.Stats stats = s.get(preset);
        if (!s.has(preset)) {
            message(g, "No " + SessionTracker.name(preset) + " this session.");
            return;
        }
        int x = insetLeft() + 6, half = (insetLeft() + insetRight()) / 2, y = insetTop() + 5;
        int lw = half - 6 - x, rw = insetRight() - 6 - half;
        int ly = y;
        ly = stat(g, x, ly, lw, "Date", SessionTracker.date(s.firstActive(preset)));
        ly = stat(g, x, ly, lw, "Started", SessionTracker.time(s.firstActive(preset)));
        ly = stat(g, x, ly, lw, "Playtime", SessionTracker.duration(stats.activeMs)
            + (live() ? SessionTracker.running(preset) ? " §a(active)" : " §7(paused)" : ""));
        int ry = y;
        ry = stat(g, half, ry, rw, "Profit per hour", stats.activeMs < 60_000 ? "-" : signed(profits[tab] * 3_600_000.0 / stats.activeMs));
        if (stats.actions > 0) ry = stat(g, half, ry, rw, preset.actionLabel, String.format(Locale.US, "%,d", stats.actions));
        if (stats.coins != 0) ry = stat(g, half, ry, rw, preset.coinLabel, "§6" + ProfitTrackerHud.coins(stats.coins));
        if (stats.kernels > 0) ry = stat(g, half, ry, rw, "Kernels", String.valueOf(stats.kernels));
        y = Math.max(ly, ry) + 3;
        g.fill(rowLeft(), y, insetRight() - 3, y + 1, Sky2MGuiStyle.LINE);
        y += 4;

        List<Item> list = items.get(tab);
        if (list.isEmpty()) {
            g.text(font, "No drops counted yet.", x, y + 4, GREY, false);
            return;
        }
        int visible = Math.max(1, (insetBottom() - 2 - y) / DROP_ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - visible));
        int amountX = x + (rowRight() - x) * 58 / 100;
        for (int i = scroll; i < list.size() && i - scroll < visible; i++) {
            Item item = list.get(i);
            int iy = y + (i - scroll) * DROP_ROW;
            // Click a drop to change how many there are or remove it (this day only), or its price; right-click it to
            // cycle its price source.
            Hit hit = new Hit(rowLeft(), iy, rowRight(), iy + DROP_ROW, () -> minecraft.gui.setScreen(new ProfitItemEditScreen(this,
                item.id(), item.amount(), List.of(stats), priceSource, "Amounts change on this day of /s2 session.", () -> {
                    SessionTracker.markDirty();
                    reprice(true);
                })));
            hits.add(hit);
            rightHits.add(new Hit(hit.x0(), hit.y0(), hit.x1(), hit.y1(), () -> {
                ProfitItemPrices.cycle(item.id(), 1, priceSource);
                reprice(true);
            }));
            if (inside(hit, mouseX, mouseY)) g.fill(rowLeft(), iy, rowRight(), iy + DROP_ROW, Sky2MGuiStyle.ROW_HOVER);
            else if (i % 2 == 1) g.fill(rowLeft(), iy, rowRight(), iy + DROP_ROW, Sky2MGuiStyle.ROW);
            g.item(RepoItems.itemStack(item.id()), x, iy + 1);
            String value = (item.value() < 0 ? "§8Unknown" : "§6" + ProfitTrackerHud.coins(item.value())) + ProfitItemPrices.tag(item.id(), priceSource);
            int valueX = rowRight() - 4 - font.width(value);
            String amount = "§7x" + String.format(Locale.US, "%,d", item.amount());
            int amountAt = Math.min(amountX, valueX - 8 - font.width(amount));
            g.text(font, font.plainSubstrByWidth(item.name(), Math.max(0, amountAt - x - 26)), x + 20, iy + 5, Sky2MGuiStyle.TEXT, false);
            g.text(font, amount, amountAt, iy + 5, Sky2MGuiStyle.TEXT, false);
            g.text(font, value, valueX, iy + 5, Sky2MGuiStyle.TEXT, false);
        }
        Sky2MGuiStyle.scrollbar(g, insetRight() - 10, y, insetBottom() - 4, scroll, visible, list.size());
    }

    private long copiedAt;

    /**
     * The shown day as plain text for Discord: the date, total profit, profit per hour and playtime, then each tracker
     * with its profit and its top drops (just that tracker's when its tab is open).
     */
    private String exportText() {
        SessionTracker.Session s = session();
        StringBuilder out = new StringBuilder();
        out.append("**Sky2M profit · ").append(live() ? "Today, " : "").append(SessionTracker.date(s.firstActiveAny())).append("**\n");
        long playtime = s.playtime();
        double total = 0;
        for (double p : profits) total += p;
        double shown = tab < TOTAL ? profits[tab] : total;
        long time = tab < TOTAL && s.get(SessionTracker.ACTIVITIES[tab]) != null ? s.get(SessionTracker.ACTIVITIES[tab]).activeMs : playtime;
        out.append(tab < TOTAL ? SessionTracker.name(SessionTracker.ACTIVITIES[tab]) + ": " : "Total: ").append(plain(shown));
        if (time >= 60_000) out.append(" (").append(plain(shown * 3_600_000.0 / time)).append("/h, ").append(SessionTracker.duration(time)).append(')');
        out.append('\n');
        for (int i = 0; i < TOTAL; i++) {
            if (tab < TOTAL && i != tab) continue;
            ProfitTracker.Preset preset = SessionTracker.ACTIVITIES[i];
            if (!s.has(preset)) continue;
            if (tab == TOTAL) out.append("• ").append(SessionTracker.name(preset)).append(": ").append(plain(profits[i]))
                .append(" (").append(SessionTracker.duration(s.get(preset).activeMs)).append(")\n");
            List<Item> list = items.size() > i ? items.get(i) : List.of();
            int shownItems = 0;
            for (Item item : list) {
                if (shownItems++ >= (tab == TOTAL ? 3 : 15)) break;
                String name = net.minecraft.ChatFormatting.stripFormatting(item.name());
                out.append(tab == TOTAL ? "    " : "• ").append(name).append(" x").append(String.format(Locale.US, "%,d", item.amount()));
                if (item.value() >= 0) out.append(" = ").append(ProfitTrackerHud.coins(item.value()));
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    private static String plain(double value) {
        return (value >= 0 ? "+" : "-") + ProfitTrackerHud.coins(Math.abs(value));
    }

    private boolean anyUnknown() {
        for (boolean value : unknown) if (value) return true;
        return false;
    }

    private static String signed(double value) {
        return (value >= 0 ? "§a+" : "§c-") + ProfitTrackerHud.coins(Math.abs(value));
    }

    /** "Label: value", the value cut to {@code w} with the label; returns the next line's y. */
    private int stat(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
        String head = label + ": ";
        g.text(font, head, x, y, Sky2MGuiStyle.DESCRIPTION, false);
        g.text(font, font.plainSubstrByWidth(value, Math.max(0, w - font.width(head))), x + font.width(head), y, Sky2MGuiStyle.TEXT, false);
        return y + 11;
    }

    // ----- Input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            for (Hit hit : List.copyOf(rightHits)) {
                if (inside(hit, event.x(), event.y())) {
                    Sky2MGuiStyle.click();
                    hit.action().run();
                    return true;
                }
            }
            return false;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        // Tabs, buttons, bars and rows, where they were drawn last frame.
        for (Hit hit : List.copyOf(hits)) {
            if (inside(hit, event.x(), event.y())) {
                Sky2MGuiStyle.click();
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
