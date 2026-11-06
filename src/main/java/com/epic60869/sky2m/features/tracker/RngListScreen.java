package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /s2 rnglist: every RNG drop, drawn like /s2 hour ({@link Sky2MGuiStyle}). Categories on the left (Slayer, Catacombs,
 * Master Mode, Other), the drops on the right with a tick box each. Drops tick themselves ({@link RngTracker}); click
 * a row to tick or untick it by hand. Search and the All / Missing / Obtained filter narrow the list.
 */
public class RngListScreen extends Screen {
    private static final int ROW = 14;
    private static final int HEAD = 12;
    private static final int LEFT_W = 132;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);

    private final Screen parent;
    private EditBox search;
    private double listScroll, catScroll;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public RngListScreen(Screen parent) {
        super(Component.literal("RNG Drops"));
        this.parent = parent;
    }

    // ------------------------------------------------------------------------------------------------ layout

    private int panelW() {
        return Math.min(520, width - 20);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelH() {
        return Math.max(160, Math.min(340, height - 12));
    }

    private int panelY() {
        return Math.max(6, (height - panelH()) / 2);
    }

    private int bodyTop() {
        return panelY() + 50;
    }

    private int bodyBottom() {
        return panelY() + panelH() - 32;
    }

    private int listX() {
        return panelX() + 8 + LEFT_W + 6;
    }

    private int listW() {
        return panelX() + panelW() - 8 - listX();
    }

    @Override
    protected void init() {
        search = new EditBox(font, 0, 0, 10, 10, Component.empty());
        search.setMaxLength(40);
        Sky2MGuiStyle.placeSearch(search, panelX() + 8, panelY() + 28, LEFT_W - Sky2MGuiStyle.SEARCH_ICON);
        search.setValue(RngView.search);
        search.setResponder(s -> {
            RngView.search = s;
            listScroll = 0;
        });
        addRenderableWidget(search);
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Map<String, RngTracker.Got> got = RngTracker.obtained();
        Sky2MGuiStyle.backdrop(g, width, height);
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        Sky2MGuiStyle.panel(g, x, y, w, h, true);
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("RNG Drops"));

        // Search, the filter tabs and the total.
        Sky2MGuiStyle.search(g, search);
        int fx = listX() + 2, fy = y + 32;
        for (int i = 0; i < RngView.FILTERS.length; i++) {
            int index = i;
            int fw = font.width(RngView.FILTERS[i]);
            boolean hover = mouseX >= fx - 3 && mouseX < fx + fw + 3 && mouseY >= fy - 3 && mouseY < fy + 11;
            Sky2MGuiStyle.category(g, font, RngView.FILTERS[i], fx, fy, RngView.filter == i, hover);
            hits.add(new Hit(fx - 3, fy - 3, fw + 6, 14, () -> {
                RngView.filter = index;
                listScroll = 0;
            }));
            fx += fw + 12;
        }
        String total = got.size() + " / " + RngTracker.total() + " obtained";
        g.text(font, total, x + w - 10 - font.width(total), fy, got.size() == RngTracker.total() ? 0xFF55FF55 : Sky2MGuiStyle.TEXT_DIM, false);

        categories(g, mouseX, mouseY, got);
        drops(g, mouseX, mouseY, got);

        // Done.
        int doneW = 80, doneX = x + (w - doneW) / 2, doneY = y + h - 24;
        boolean doneHover = mouseX >= doneX && mouseX < doneX + doneW && mouseY >= doneY && mouseY < doneY + 18;
        Sky2MGuiStyle.button(g, font, "Done", doneX, doneY, doneW, 18, doneHover);
        hits.add(new Hit(doneX, doneY, doneW, 18, this::onClose));

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /** The left box: "All drops", then each group's categories with how many you have. */
    private void categories(GuiGraphicsExtractor g, int mouseX, int mouseY, Map<String, RngTracker.Got> got) {
        int x0 = panelX() + 8, x1 = x0 + LEFT_W, top = bodyTop(), bottom = bodyBottom();
        Sky2MGuiStyle.inset(g, x0, top, x1, bottom);
        Sky2MGuiStyle.heading(g, font, "Categories", x0, top + 5, LEFT_W);
        int viewTop = top + 17, viewBottom = bottom - 2;

        int content = HEAD;
        String lastGroup = null;
        for (RngDropList.Category c : RngDropList.ALL) {
            if (!c.group().equals(lastGroup)) content += HEAD + 2;
            lastGroup = c.group();
            content += HEAD;
        }
        int view = viewBottom - viewTop;
        catScroll = Math.max(0, Math.min(catScroll, Math.max(0, content - view)));

        g.enableScissor(x0 + 1, viewTop, x1 - 1, viewBottom);
        int cy = viewTop + 2 - (int) catScroll;
        boolean inView = mouseY >= viewTop && mouseY < viewBottom;
        cy = categoryEntry(g, mouseX, mouseY, inView, x0, x1, cy, "All drops", null, got.size(), RngTracker.total());
        lastGroup = null;
        for (RngDropList.Category c : RngDropList.ALL) {
            if (!c.group().equals(lastGroup)) {
                cy += 2;
                g.text(font, c.group(), x0 + 6, cy, Sky2MGuiStyle.HEADING, false);
                cy += HEAD;
                lastGroup = c.group();
            }
            cy = categoryEntry(g, mouseX, mouseY, inView, x0, x1, cy, c.name(), c.name(), RngView.have(c, got), c.items().size());
        }
        g.disableScissor();
        Sky2MGuiStyle.scrollbar(g, x1 - 6, viewTop, viewBottom, (int) catScroll, view, content);
    }

    private int categoryEntry(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean inView, int x0, int x1, int cy,
                              String label, String key, int have, int size) {
        String count = have + "/" + size;
        int maxLabel = x1 - x0 - 22 - font.width(count);
        String fit = font.plainSubstrByWidth(label, maxLabel);
        int lx = x0 + 10;
        boolean selected = key == null ? RngView.category == null : key.equals(RngView.category);
        boolean hover = inView && mouseX >= x0 && mouseX < x1 && mouseY >= cy - 2 && mouseY < cy + HEAD - 2;
        Sky2MGuiStyle.category(g, font, fit, lx, cy, selected, hover);
        g.text(font, count, x1 - 9 - font.width(count), cy, have == size ? 0xFF55FF55 : Sky2MGuiStyle.MUTED, false);
        if (inView) hits.add(new Hit(x0, cy - 2, x1 - x0, HEAD, () -> {
            RngView.category = key;
            listScroll = 0;
        }));
        return cy + HEAD;
    }

    /** The right box: the drops, each with a tick box. */
    private void drops(GuiGraphicsExtractor g, int mouseX, int mouseY, Map<String, RngTracker.Got> got) {
        int x0 = listX(), x1 = x0 + listW(), top = bodyTop(), bottom = bodyBottom();
        Sky2MGuiStyle.inset(g, x0, top, x1, bottom);
        String title = RngView.category == null ? "All drops" : RngView.category;
        Sky2MGuiStyle.heading(g, font, title, x0, top + 5, listW());
        int viewTop = top + 17, viewBottom = bottom - 2;

        List<RngView.Line> lines = RngView.lines(got);
        int content = 0;
        for (RngView.Line line : lines) content += line.item() == null ? HEAD + 4 : ROW + 1;
        int view = viewBottom - viewTop;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, content - view)));

        g.enableScissor(x0 + 1, viewTop, x1 - 1, viewBottom);
        int ry = viewTop + 2 - (int) listScroll;
        int rx0 = x0 + 5, rx1 = x1 - 9;
        String tooltip = null;
        for (RngView.Line line : lines) {
            if (line.item() == null) {
                if (ry + HEAD + 4 > viewTop && ry < viewBottom) {
                    g.text(font, line.heading(), rx0 + 1, ry + 4, Sky2MGuiStyle.HEADING, false);
                    g.fill(rx0 + font.width(line.heading()) + 6, ry + 8, rx1, ry + 9, Sky2MGuiStyle.LINE);
                }
                ry += HEAD + 4;
                continue;
            }
            if (ry + ROW > viewTop && ry < viewBottom) {
                String item = line.item();
                RngTracker.Got have = got.get(item);
                boolean hover = mouseX >= rx0 && mouseX < rx1 && mouseY >= Math.max(ry, viewTop) && mouseY < Math.min(ry + ROW, viewBottom);
                g.fill(rx0, ry, rx1, ry + ROW, hover ? Sky2MGuiStyle.ROW_HOVER : Sky2MGuiStyle.ROW);
                checkbox(g, rx0 + 3, ry + 2, have != null, hover);
                String when = have == null ? "" : (RngTracker.BY_HAND.equals(have.how()) ? "ticked" : "dropped") + " "
                    + DATE.format(Instant.ofEpochMilli(have.time()).atZone(ZoneId.systemDefault()));
                // Not got yet: its base drop chance in this category instead of a date (RngChances).
                if (have == null && line.category() != null) {
                    String chance = RngChances.of(line.category(), item);
                    if (chance != null) when = chance;
                }
                int whenW = font.width(when);
                String fit = font.plainSubstrByWidth(item, rx1 - rx0 - 22 - whenW - 8);
                Component name = have != null ? Component.literal(fit).withStyle(ChatFormatting.STRIKETHROUGH) : Component.literal(fit);
                g.text(font, name, rx0 + 17, ry + 3, have != null ? 0xFF55FF55 : Sky2MGuiStyle.TEXT, false);
                if (!when.isEmpty()) g.text(font, when, rx1 - 5 - whenW, ry + 3, Sky2MGuiStyle.MUTED, false);
                if (hover) {
                    hits.add(new Hit(rx0, Math.max(ry, viewTop), rx1 - rx0, Math.min(ry + ROW, viewBottom) - Math.max(ry, viewTop), () -> RngTracker.toggle(item)));
                    tooltip = have == null ? "Click to tick by hand"
                        : RngTracker.BY_HAND.equals(have.how()) ? "Ticked by hand - click to untick"
                        : "Got from: " + have.how() + " - click to untick";
                }
            }
            ry += ROW + 1;
        }
        if (lines.isEmpty()) {
            String none = RngView.search.isBlank() ? "Nothing here" : "No drops match \"" + RngView.search.trim() + "\"";
            g.text(font, none, x0 + (listW() - font.width(none)) / 2, viewTop + 8, Sky2MGuiStyle.MUTED, false);
        }
        g.disableScissor();
        Sky2MGuiStyle.scrollbar(g, x1 - 6, viewTop, viewBottom, (int) listScroll, view, content);
        if (tooltip != null) {
            g.setTooltipForNextFrame(font, List.of(Component.literal(tooltip)), java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /** A tick box like the settings' fields: white border (aqua when hovered), black inside, green with a tick when on. */
    private void checkbox(GuiGraphicsExtractor g, int x, int y, boolean on, boolean hover) {
        g.fill(x, y, x + 10, y + 10, hover ? Sky2MGuiStyle.SELECTED : 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 9, y + 9, on ? 0xFF2E8B3A : 0xFF000000);
        if (on) g.text(font, "✔", x + 2, y + 1, 0xFFFFFFFF, false);
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (Sky2MGuiStyle.inSearch(search, event.x(), event.y())) {
                setFocused(search);
                return super.mouseClicked(event, doubleClick) || true;
            }
            for (Hit hit : List.copyOf(hits)) {
                if (event.x() >= hit.x && event.x() < hit.x + hit.w && event.y() >= hit.y && event.y() < hit.y + hit.h) {
                    Sky2MGuiStyle.click();
                    hit.action.run();
                    return true;
                }
            }
            setFocused(null);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < listX()) catScroll -= scrollY * HEAD * 2;
        else listScroll -= scrollY * (ROW + 1) * 3;
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
