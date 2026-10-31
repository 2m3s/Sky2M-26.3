package com.epic60869.sky2m.features.misc.waypoints;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2w: the Area Waypoints manager, laid out like Odin's: the areas on the left (search them, ● marks where you are,
 * the number is how many waypoints it has), the selected area's waypoints on the right (switch, colour, label and
 * position; click one to edit it, X to delete it), and Add Waypoint, Import, Export and Done along the bottom.
 */
public final class WaypointsScreen extends Screen {
    private static final int DIALOG_W = 440, PAD = 10, AREA_W = 140, SEARCH_Y = 24, SEARCH_H = 18, LIST_Y = 48;
    private static final int AREA_ROW = 17, HEADER = 14, WP_ROW = 24, WP_GAP = 4, BUTTON_H = 20;

    private final Screen parent;
    private EditBox search;
    private String selected;
    private String status = "";
    private double areaScroll, listScroll;
    private int px, py, listH;

    public WaypointsScreen(Screen parent) {
        super(Component.literal("Waypoints"));
        this.parent = parent;
        String here = AreaWaypoints.currentKey();
        selected = here != null ? here : AreaWaypoints.areas().getFirst().key();
    }

    WaypointsScreen(Screen parent, String selected, String status) {
        this(parent);
        this.selected = selected;
        this.status = status;
    }

    private int dialogH() {
        return LIST_Y + listH + 14 + PAD + BUTTON_H + PAD;
    }

    private int rightX() {
        return px + PAD + AREA_W + PAD;
    }

    private int rightW() {
        return DIALOG_W - (PAD + AREA_W + PAD) - PAD;
    }

    @Override
    protected void init() {
        listH = Mth.clamp(height - 130, 48, 230);
        px = (width - DIALOG_W) / 2;
        py = Math.max(6, (height - dialogH()) / 2);
        String typed = search == null ? "" : search.getValue();
        search = new EditBox(font, px + PAD + 4, py + SEARCH_Y + 5, AREA_W - 8, SEARCH_H - 8, Component.literal("Search areas"));
        search.setBordered(false);
        search.setCentered(true);
        search.setMaxLength(24);
        search.setHint(Component.literal("Search areas...").withStyle(ChatFormatting.GRAY));
        search.setValue(typed);
        addRenderableWidget(search);
    }

    // ------------------------------------------------------------------------------------------------ layout

    private record AreaRow(AreaWaypoints.Area area, String header, int y, int h) {}

    private List<AreaRow> areaRows() {
        List<AreaRow> rows = new ArrayList<>();
        String filter = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        int y = 0;
        for (AreaWaypoints.Group group : AreaWaypoints.Group.values()) {
            List<AreaWaypoints.Area> areas = new ArrayList<>();
            for (AreaWaypoints.Area a : AreaWaypoints.areas()) if (a.group() == group && a.label().toLowerCase(Locale.ROOT).contains(filter)) areas.add(a);
            if (areas.isEmpty()) continue;
            rows.add(new AreaRow(null, group.title, y, HEADER));
            y += HEADER;
            for (AreaWaypoints.Area a : areas) {
                rows.add(new AreaRow(a, null, y, AREA_ROW));
                y += AREA_ROW;
            }
            y += 7;
        }
        return rows;
    }

    private static int height(List<AreaRow> rows) {
        return rows.isEmpty() ? 0 : rows.getLast().y() + rows.getLast().h();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int top = py + LIST_Y;
        WaypointUi.round(g, px, py, px + DIALOG_W, py + dialogH(), WaypointUi.BACKGROUND, 4);
        String title = "Waypoints";
        g.text(font, Component.literal(title).withStyle(ChatFormatting.BOLD), px + (DIALOG_W - font.width(title) - 1) / 2, py + PAD, WaypointUi.ACCENT, false);
        WaypointUi.outlined(g, px + PAD, py + SEARCH_Y, AREA_W, SEARCH_H, WaypointUi.SURFACE, WaypointUi.ACCENT);

        // Areas.
        List<AreaRow> rows = areaRows();
        areaScroll = Mth.clamp(areaScroll, 0, Math.max(0, height(rows) - listH));
        String here = AreaWaypoints.currentKey();
        g.enableScissor(px + PAD, top, px + PAD + AREA_W, top + listH);
        for (int i = 0; i < rows.size(); i++) {
            AreaRow row = rows.get(i);
            int y = top + row.y() - (int) areaScroll;
            if (y + row.h() < top || y > top + listH) continue;
            int x = px + PAD;
            if (row.header() != null) {
                g.text(font, Component.literal(row.header()).withStyle(ChatFormatting.BOLD), x + 3, y + 3, WaypointUi.ACCENT, false);
                continue;
            }
            boolean sel = row.area().key().equals(selected);
            boolean hover = WaypointUi.inside(mouseX, mouseY, x, y, AREA_W, row.h()) && mouseY >= top && mouseY < top + listH;
            g.fill(x, y, x + AREA_W, y + row.h(), WaypointUi.SURFACE);
            if (sel) g.fill(x + 3, y + 3, x + 5, y + row.h() - 3, WaypointUi.ACCENT);
            boolean lastInGroup = i + 1 >= rows.size() || rows.get(i + 1).header() != null;
            if (!lastInGroup) g.fill(x + 8, y + row.h() - 1, x + AREA_W - 8, y + row.h(), WaypointUi.LINE);
            String label = (row.area().key().equals(here) ? "● " : "") + row.area().label();
            g.text(font, label, x + 8, y + (row.h() - 8) / 2, sel || hover ? WaypointUi.TEXT : WaypointUi.MUTED, false);
            int count = AreaWaypoints.count(row.area().key());
            if (count > 0) g.text(font, String.valueOf(count), x + AREA_W - 6 - font.width(String.valueOf(count)), y + (row.h() - 8) / 2, WaypointUi.ACCENT, false);
        }
        g.disableScissor();

        // The selected area's waypoints.
        int rx = rightX(), rw = rightW();
        Sky2MConfig c = Sky2MConfig.current();
        boolean off = c != null && !c.misc.areaWaypoints.enabled;
        g.text(font, AreaWaypoints.label(selected) + (off ? " §c(turned off)" : ""), rx + 2, py + SEARCH_Y + (SEARCH_H - 8) / 2, WaypointUi.ACCENT, false);
        List<AreaWaypoints.Waypoint> list = AreaWaypoints.in(selected);
        if (list.isEmpty()) g.text(font, "Nothing here yet, use Add Waypoint.", rx + 2, top + 6, WaypointUi.MUTED, false);
        int listContent = list.size() * (WP_ROW + WP_GAP);
        listScroll = Mth.clamp(listScroll, 0, Math.max(0, listContent - listH));
        g.enableScissor(rx, top, rx + rw, top + listH);
        AreaWaypoints.Waypoint hovered = null;
        for (int i = 0; i < list.size(); i++) {
            AreaWaypoints.Waypoint wp = list.get(i);
            int y = top + i * (WP_ROW + WP_GAP) - (int) listScroll;
            if (y + WP_ROW < top || y > top + listH) continue;
            WaypointUi.round(g, rx, y, rx + rw, y + WP_ROW, WaypointUi.SURFACE, 3);
            // Switch.
            int sx = rx + 8, sy = y + (WP_ROW - 10) / 2;
            WaypointUi.round(g, sx, sy, sx + 18, sy + 10, wp.enabled ? WaypointUi.ACCENT : WaypointUi.DIM, 2);
            g.fill(wp.enabled ? sx + 10 : sx + 2, sy + 2, wp.enabled ? sx + 16 : sx + 8, sy + 8, WaypointUi.TEXT);
            // Colour, label and position.
            int dx = rx + 36;
            g.fill(dx, y + WP_ROW / 2 - 3, dx + 7, y + WP_ROW / 2 + 4, wp.enabled ? 0xFF000000 | wp.color : WaypointUi.DIM);
            boolean hover = WaypointUi.inside(mouseX, mouseY, dx, y, rw - 36 - 30, WP_ROW) && mouseY >= top && mouseY < top + listH;
            if (hover) hovered = wp;
            String name = wp.label.isBlank() ? wp.trigger.label : wp.label;
            g.text(font, name, dx + 12, y + (WP_ROW - 8) / 2, !wp.enabled ? WaypointUi.DIM : hover ? WaypointUi.ACCENT : WaypointUi.TEXT, false);
            String at = coord(wp.x) + " " + coord(wp.y) + " " + coord(wp.z);
            g.text(font, at, rx + rw - 30 - font.width(at), y + (WP_ROW - 8) / 2, WaypointUi.DIM, false);
            // Delete.
            int bx = rx + rw - 24, by = y + 3;
            WaypointUi.button(g, "X", bx, by, 18, 18, false, WaypointUi.inside(mouseX, mouseY, bx, by, 18, 18), true);
        }
        g.disableScissor();

        g.text(font, status, px + PAD, top + listH + 3, WaypointUi.TEXT, false);
        int fy = top + listH + 14 + PAD;
        for (Footer f : footer()) WaypointUi.button(g, f.label, f.x, fy, f.w, BUTTON_H, false, WaypointUi.inside(mouseX, mouseY, f.x, fy, f.w, BUTTON_H), true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (hovered != null) g.setComponentTooltipForNextFrame(font, info(hovered), mouseX, mouseY);
    }

    static String coord(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static List<Component> info(AreaWaypoints.Waypoint wp) {
        String text = wp.trigger.label + " at " + coord(wp.x) + ", " + coord(wp.y) + ", " + coord(wp.z)
            + (wp.isArea() ? " | to " + coord(wp.ex) + ", " + coord(wp.ey) + ", " + coord(wp.ez) : "")
            + (wp.radius != null ? " | radius " + wp.radius : "")
            + (wp.command != null ? " | /" + wp.command : "");
        return List.of(Component.literal(text), Component.literal("Click to edit").withStyle(ChatFormatting.GRAY));
    }

    private record Footer(String label, int x, int w, Runnable action) {}

    private List<Footer> footer() {
        int gap = 4, total = 100 + 70 * 3 + gap * 3;
        int x = px + (DIALOG_W - total) / 2;
        List<Footer> out = new ArrayList<>();
        out.add(new Footer("Add Waypoint", x, 100, () -> Compat.queueOpenScreen(new WaypointFormScreen(this, selected, null))));
        out.add(new Footer("Import", x += 100 + gap, 70, () -> status = AreaWaypoints.importFromClipboard()));
        out.add(new Footer("Export", x += 70 + gap, 70, () -> status = AreaWaypoints.exportToClipboard()));
        out.add(new Footer("Done", x + 70 + gap, 70, this::onClose));
        return out;
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        int top = py + LIST_Y;
        int fy = top + listH + 14 + PAD;
        for (Footer f : footer()) {
            if (WaypointUi.inside(mx, my, f.x, fy, f.w, BUTTON_H)) {
                f.action.run();
                return true;
            }
        }
        if (my < top || my >= top + listH) return false;
        if (WaypointUi.inside(mx, my, px + PAD, top, AREA_W, listH)) {
            for (AreaRow row : areaRows()) {
                int y = top + row.y() - (int) areaScroll;
                if (row.area() != null && my >= y && my < y + row.h()) {
                    selected = row.area().key();
                    listScroll = 0;
                    return true;
                }
            }
            return false;
        }
        int rx = rightX(), rw = rightW();
        List<AreaWaypoints.Waypoint> list = AreaWaypoints.in(selected);
        for (int i = 0; i < list.size(); i++) {
            AreaWaypoints.Waypoint wp = list.get(i);
            int y = top + i * (WP_ROW + WP_GAP) - (int) listScroll;
            if (my < y || my >= y + WP_ROW || mx < rx || mx >= rx + rw) continue;
            if (mx < rx + 30) AreaWaypoints.toggle(wp);
            else if (mx >= rx + rw - 26) AreaWaypoints.remove(wp);
            else Compat.queueOpenScreen(new WaypointFormScreen(this, selected, wp));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX < rightX()) areaScroll -= vertical * 17 * 2;
        else listScroll -= vertical * (WP_ROW + WP_GAP);
        return true;
    }

    void setStatus(String status) {
        this.status = status;
    }

    @Override
    public void onClose() {
        Compat.queueOpenScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
