package com.epic60869.sky2m.features.misc.waypoints;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/**
 * Area Waypoints' Add / Edit Waypoint form, laid out like Odin's: At (one block grown by a radius) or In (everything
 * between two corners), the position, the label shown above it, the trigger (Visual, Proximity or Click) and the
 * command it runs, and a colour picker with a hex box and presets on the right. Enter adds or saves it.
 */
public final class WaypointFormScreen extends Screen {
    private static final int FORM_W = 392, FORM_H = 322, LEFT_X = 10, LEFT_W = 176, RIGHT_X = 206, RIGHT_W = 176, FIELD_H = 16;
    private static final int SV_X = 8, SV_Y = 70, SV_W = 118, SV_H = 112, BAR_W = 10;
    private static final int[] PRESETS = {0xFF55FF55, 0xFF55FFFF, 0xFF3399FF, 0xFFFFFF55, 0xFFFFAA00, 0xFFFF5555, 0xFFAA00AA};

    private enum Drag { NONE, SV, HUE, ALPHA, RADIUS }

    private final WaypointsScreen parent;
    private final String area;
    private final AreaWaypoints.Waypoint edit;

    private boolean inArea;
    private AreaWaypoints.Trigger trigger;
    private float radius;
    private float hue, sat, val, alpha;
    private String message = "";
    private Drag drag = Drag.NONE;
    private boolean settingHex;

    private EditBox fx, fy, fz, tx, ty, tz, label, command, hex;
    private int ox, oy;

    WaypointFormScreen(WaypointsScreen parent, String area, AreaWaypoints.Waypoint edit) {
        super(Component.literal(edit == null ? "Add Waypoint" : "Edit Waypoint"));
        this.parent = parent;
        this.area = area;
        this.edit = edit;
        inArea = edit != null && edit.isArea();
        trigger = edit == null ? AreaWaypoints.Trigger.VISUAL : edit.trigger;
        radius = edit == null || edit.radius == null ? 1f : edit.radius;
        setColour(edit == null ? 0xFF55FF55 : edit.color);
    }

    // ------------------------------------------------------------------------------------------------ colour

    private void setColour(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f, g = ((argb >> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6;
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
            if (h < 0) h += 1;
        }
        hue = h;
        sat = max == 0 ? 0 : d / max;
        val = max;
        alpha = ((argb >>> 24) & 0xFF) / 255f;
    }

    private int colour() {
        return Mth.hsvToRgb(hue, sat, val) & 0xFFFFFF | Math.round(alpha * 255) << 24;
    }

    private void colourChanged() {
        if (hex == null) return;
        settingHex = true;
        hex.setValue(String.format("%08X", colour()).substring(2) + String.format("%02X", Math.round(alpha * 255)));
        settingHex = false;
    }

    // ------------------------------------------------------------------------------------------------ setup

    private EditBox box(int x, int y, int w, String value, String hint, int max) {
        EditBox box = new EditBox(font, ox + x + 4, oy + y + 4, w - 8, FIELD_H - 8, Component.literal(hint));
        box.setBordered(false);
        box.setMaxLength(max);
        box.setValue(value);
        box.setHint(Component.literal(hint).withStyle(ChatFormatting.GRAY));
        addRenderableWidget(box);
        return box;
    }

    private EditBox number(int x, int y, double value) {
        EditBox box = box(x, y, coordW(), WaypointsScreen.coord(value), "0", 9);
        box.setCentered(true);
        box.setResponder(text -> {
            String clean = text.replaceAll("[^0-9.\\-]", "");
            if (!clean.equals(text)) box.setValue(clean);
        });
        return box;
    }

    private static int coordW() {
        return (LEFT_W - 6 * 2 - 10 * 3) / 3;
    }

    private static int coordX(int i) {
        return LEFT_X + 10 + i * (coordW() + 16);
    }

    @Override
    protected void init() {
        ox = (width - FORM_W) / 2;
        oy = Math.max(4, (height - FORM_H) / 2);
        String fromX = fx == null ? null : fx.getValue(), fromY = fy == null ? null : fy.getValue(), fromZ = fz == null ? null : fz.getValue();
        Minecraft mc = Minecraft.getInstance();
        BlockPos here = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        double sx = edit != null ? edit.x : here.getX(), sy = edit != null ? edit.y : here.getY(), sz = edit != null ? edit.z : here.getZ();
        double ex = edit != null ? edit.ex : here.getX(), ey = edit != null ? edit.ey : here.getY(), ez = edit != null ? edit.ez : here.getZ();
        String oldTo = tx == null ? null : tx.getValue() + ";" + ty.getValue() + ";" + tz.getValue();
        String oldLabel = label == null ? (edit == null ? "" : edit.label) : label.getValue();
        String oldCommand = command == null ? (edit == null || edit.command == null ? "" : edit.command) : command.getValue();
        fx = number(coordX(0), 74, sx);
        fy = number(coordX(1), 74, sy);
        fz = number(coordX(2), 74, sz);
        if (fromX != null) {
            fx.setValue(fromX);
            fy.setValue(fromY);
            fz.setValue(fromZ);
        }
        tx = number(coordX(0), 108, ex);
        ty = number(coordX(1), 108, ey);
        tz = number(coordX(2), 108, ez);
        if (oldTo != null) {
            String[] p = oldTo.split(";", -1);
            tx.setValue(p[0]);
            ty.setValue(p[1]);
            tz.setValue(p[2]);
        }
        label = box(LEFT_X, 144, LEFT_W, oldLabel, "Shown above the waypoint", 32);
        command = box(LEFT_X, 218, LEFT_W, oldCommand, "warp hub  (no /)", 128);
        hex = box(RIGHT_X + SV_X, 190, 64, "", "RRGGBBAA", 8);
        hex.setResponder(text -> {
            if (settingHex) return;
            String t = text.trim();
            try {
                if (t.length() == 6) setColour(0xFF000000 | Integer.parseInt(t, 16));
                else if (t.length() == 8) {
                    long v = Long.parseLong(t, 16);
                    setColour((int) (((v & 0xFF) << 24) | (v >>> 8)));
                }
            } catch (NumberFormatException ignored) {}
        });
        colourChanged();
        updateVisibility();
    }

    private void updateVisibility() {
        tx.visible = ty.visible = tz.visible = inArea;
        command.setEditable(trigger != AreaWaypoints.Trigger.VISUAL);
        command.visible = trigger != AreaWaypoints.Trigger.VISUAL;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0x80000000);
        WaypointUi.round(g, ox, oy, ox + FORM_W, oy + FORM_H, WaypointUi.BACKGROUND, 4);
        String title = edit == null ? "Add Waypoint" : "Edit Waypoint";
        g.text(font, Component.literal(title).withStyle(ChatFormatting.BOLD), ox + (FORM_W - font.width(title) - 1) / 2, oy + 9, WaypointUi.ACCENT, false);
        String sub = AreaWaypoints.label(area);
        g.text(font, sub, ox + (FORM_W - font.width(sub)) / 2, oy + 22, WaypointUi.MUTED, false);

        int half = (LEFT_W - 4) / 2;
        WaypointUi.button(g, "At", ox + LEFT_X, oy + 38, half, 22, !inArea, WaypointUi.inside(mouseX, mouseY, ox + LEFT_X, oy + 38, half, 22), true);
        WaypointUi.button(g, "In", ox + LEFT_X + half + 4, oy + 38, half, 22, inArea, WaypointUi.inside(mouseX, mouseY, ox + LEFT_X + half + 4, oy + 38, half, 22), true);

        WaypointUi.section(g, inArea ? "From" : "Position", ox + LEFT_X, oy + 62, LEFT_W);
        corner(g, 74);
        if (inArea) {
            WaypointUi.section(g, "To", ox + LEFT_X, oy + 96, LEFT_W);
            corner(g, 108);
        } else {
            g.text(font, "Radius", ox + LEFT_X + 8, oy + 98, WaypointUi.TEXT, false);
            String value = String.format(Locale.US, "%s blocks", WaypointsScreen.coord(radius));
            g.text(font, value, ox + LEFT_X + LEFT_W - 8 - font.width(value), oy + 98, WaypointUi.TEXT, false);
            int tx0 = ox + LEFT_X + 8, tw = LEFT_W - 16, ty0 = oy + 113;
            WaypointUi.round(g, tx0, ty0, tx0 + tw, ty0 + 6, WaypointUi.SURFACE_HOVER, 2);
            int knob = tx0 + Math.round((radius - 0.1f) / 9.9f * tw);
            WaypointUi.round(g, knob - 4, ty0 - 2, knob + 4, ty0 + 8, WaypointUi.TEXT, 3);
        }

        WaypointUi.section(g, "Label", ox + LEFT_X, oy + 132, LEFT_W);
        field(g, LEFT_X, 144, LEFT_W, label.isFocused());
        WaypointUi.section(g, "Trigger", ox + LEFT_X, oy + 166, LEFT_W);
        int third = (LEFT_W - 8) / 3;
        AreaWaypoints.Trigger[] triggers = AreaWaypoints.Trigger.values();
        for (int i = 0; i < triggers.length; i++) {
            int bx = ox + LEFT_X + i * (third + 4);
            WaypointUi.button(g, triggers[i].label, bx, oy + 178, third, 22, trigger == triggers[i], WaypointUi.inside(mouseX, mouseY, bx, oy + 178, third, 22), true);
        }
        WaypointUi.section(g, "Command", ox + LEFT_X, oy + 206, LEFT_W);
        if (trigger == AreaWaypoints.Trigger.VISUAL) {
            WaypointUi.round(g, ox + LEFT_X, oy + 218, ox + LEFT_X + LEFT_W, oy + 218 + FIELD_H, WaypointUi.SURFACE, 2);
            String note = "Visual waypoints never run a command";
            g.text(font, note, ox + LEFT_X + (LEFT_W - font.width(note)) / 2, oy + 222, WaypointUi.DIM, false);
        } else {
            field(g, LEFT_X, 218, LEFT_W, command.isFocused());
        }

        // Colour.
        int rx = ox + RIGHT_X;
        WaypointUi.section(g, "Color", rx, oy + 38, RIGHT_W);
        g.text(font, "Color", rx + SV_X, oy + 54, WaypointUi.TEXT, false);
        WaypointUi.outlined(g, rx + RIGHT_W - 30, oy + 50, 26, 14, colour() | 0xFF000000, 0xFF2A7A2A);
        int sx = rx + SV_X, sy = oy + SV_Y;
        for (int i = 0; i < SV_W; i++) {
            int top = 0xFF000000 | Mth.hsvToRgb(hue, i / (float) (SV_W - 1), 1f);
            g.fillGradient(sx + i, sy, sx + i + 1, sy + SV_H, top, 0xFF000000);
        }
        int kx = sx + Math.round(sat * (SV_W - 1)), ky = sy + Math.round((1 - val) * (SV_H - 1));
        WaypointUi.round(g, kx - 3, ky - 3, kx + 4, ky + 4, WaypointUi.TEXT, 2);
        int hx = sx + SV_W + 6;
        for (int i = 0; i < SV_H; i++) g.fill(hx, sy + i, hx + BAR_W, sy + i + 1, 0xFF000000 | Mth.hsvToRgb(i / (float) SV_H, 1f, 1f));
        int hy = sy + Math.round(hue * (SV_H - 1));
        g.fill(hx - 1, hy - 1, hx + BAR_W + 1, hy + 2, WaypointUi.TEXT);
        int ax = hx + BAR_W + 6, rgb = colour() & 0xFFFFFF;
        g.fillGradient(ax, sy, ax + BAR_W, sy + SV_H, 0xFF000000 | rgb, rgb);
        int ay = sy + Math.round((1 - alpha) * (SV_H - 1));
        g.fill(ax - 1, ay - 1, ax + BAR_W + 1, ay + 2, WaypointUi.TEXT);
        field(g, RIGHT_X + SV_X, 190, 64, hex.isFocused());
        for (int i = 0; i < PRESETS.length; i++) {
            int px = presetX(i);
            WaypointUi.outlined(g, px, oy + 191, 14, 14, PRESETS[i], WaypointUi.LINE);
        }

        if (!message.isEmpty()) g.text(font, message, ox + (FORM_W - font.width(message)) / 2, oy + FORM_H - 42, WaypointUi.TEXT, false);
        int by = oy + FORM_H - 10 - 20, bx = ox + (FORM_W - 204) / 2;
        WaypointUi.button(g, edit == null ? "Add" : "Save", bx, by, 100, 20, false, WaypointUi.inside(mouseX, mouseY, bx, by, 100, 20), true);
        WaypointUi.button(g, edit == null ? "Close" : "Cancel", bx + 104, by, 100, 20, false, WaypointUi.inside(mouseX, mouseY, bx + 104, by, 100, 20), true);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private int presetX(int i) {
        return ox + RIGHT_X + SV_X + 70 + i * 16;
    }

    private void field(GuiGraphicsExtractor g, int x, int y, int w, boolean focused) {
        WaypointUi.outlined(g, ox + x, oy + y, w, FIELD_H, WaypointUi.SURFACE, focused ? WaypointUi.TEXT : WaypointUi.ACCENT);
    }

    private void corner(GuiGraphicsExtractor g, int y) {
        String[] axes = {"X", "Y", "Z"};
        int[] colours = {0xFFFF5555, 0xFF55FF55, 0xFF5555FF};
        EditBox[] boxes = y == 74 ? new EditBox[]{fx, fy, fz} : new EditBox[]{tx, ty, tz};
        for (int i = 0; i < 3; i++) {
            int x = coordX(i);
            g.text(font, axes[i], ox + x - 9, oy + y + 4, colours[i], false);
            field(g, x, y, coordW(), boxes[i].isFocused());
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (!WaypointUi.inside(mx, my, ox, oy, FORM_W, FORM_H)) {
            onClose();
            return true;
        }
        int half = (LEFT_W - 4) / 2;
        if (WaypointUi.inside(mx, my, ox + LEFT_X, oy + 38, half, 22)) return choose(() -> inArea = false);
        if (WaypointUi.inside(mx, my, ox + LEFT_X + half + 4, oy + 38, half, 22)) return choose(() -> inArea = true);
        int third = (LEFT_W - 8) / 3;
        AreaWaypoints.Trigger[] triggers = AreaWaypoints.Trigger.values();
        for (int i = 0; i < triggers.length; i++) {
            AreaWaypoints.Trigger t = triggers[i];
            if (WaypointUi.inside(mx, my, ox + LEFT_X + i * (third + 4), oy + 178, third, 22)) return choose(() -> trigger = t);
        }
        if (!inArea && WaypointUi.inside(mx, my, ox + LEFT_X + 4, oy + 108, LEFT_W - 8, 16)) {
            drag = Drag.RADIUS;
            dragTo(mx, my);
            return true;
        }
        int sx = ox + RIGHT_X + SV_X, sy = oy + SV_Y, hx = sx + SV_W + 6, ax = hx + BAR_W + 6;
        if (WaypointUi.inside(mx, my, sx, sy, SV_W, SV_H)) drag = Drag.SV;
        else if (WaypointUi.inside(mx, my, hx - 2, sy, BAR_W + 4, SV_H)) drag = Drag.HUE;
        else if (WaypointUi.inside(mx, my, ax - 2, sy, BAR_W + 4, SV_H)) drag = Drag.ALPHA;
        if (drag != Drag.NONE) {
            dragTo(mx, my);
            return true;
        }
        for (int i = 0; i < PRESETS.length; i++) {
            if (WaypointUi.inside(mx, my, presetX(i), oy + 191, 14, 14)) {
                setColour(PRESETS[i]);
                colourChanged();
                return true;
            }
        }
        int by = oy + FORM_H - 10 - 20, bx = ox + (FORM_W - 204) / 2;
        if (WaypointUi.inside(mx, my, bx, by, 100, 20)) {
            submit();
            return true;
        }
        if (WaypointUi.inside(mx, my, bx + 104, by, 100, 20)) {
            onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean choose(Runnable change) {
        change.run();
        updateVisibility();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (drag == Drag.NONE) return super.mouseDragged(event, dx, dy);
        dragTo(event.x(), event.y());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        drag = Drag.NONE;
        return super.mouseReleased(event);
    }

    private void dragTo(double mx, double my) {
        int sy = oy + SV_Y;
        float fy = Mth.clamp((float) (my - sy) / (SV_H - 1), 0f, 1f);
        switch (drag) {
            case SV -> {
                sat = Mth.clamp((float) (mx - (ox + RIGHT_X + SV_X)) / (SV_W - 1), 0f, 1f);
                val = 1 - fy;
            }
            case HUE -> hue = fy;
            case ALPHA -> alpha = 1 - fy;
            case RADIUS -> {
                float f = Mth.clamp((float) (mx - (ox + LEFT_X + 8)) / (LEFT_W - 16), 0f, 1f);
                radius = Math.round((0.1f + f * 9.9f) * 10) / 10f;
                return;
            }
            default -> {
                return;
            }
        }
        colourChanged();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    private static Double parse(EditBox box) {
        try {
            double v = Double.parseDouble(box.getValue().trim());
            return Double.isFinite(v) ? Math.round(v * 100) / 100.0 : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void submit() {
        Double x = parse(fx), y = parse(fy), z = parse(fz);
        Double ex = inArea ? parse(tx) : x, ey = inArea ? parse(ty) : y, ez = inArea ? parse(tz) : z;
        if (x == null || y == null || z == null || ex == null || ey == null || ez == null) {
            message = "§cCoordinates must be numbers.";
            return;
        }
        AreaWaypoints.Waypoint wp = new AreaWaypoints.Waypoint();
        wp.area = area;
        wp.x = x;
        wp.y = y;
        wp.z = z;
        wp.ex = ex;
        wp.ey = ey;
        wp.ez = ez;
        wp.label = label.getValue().trim();
        wp.color = colour();
        wp.trigger = trigger;
        String cmd = command.getValue().trim().replaceFirst("^/", "");
        wp.command = trigger != AreaWaypoints.Trigger.VISUAL && !cmd.isEmpty() ? cmd : null;
        wp.radius = inArea ? null : radius;
        wp.enabled = edit == null || edit.enabled;
        String error = AreaWaypoints.submit(wp, edit);
        if (error != null) {
            message = "§c" + error;
            return;
        }
        if (edit != null) {
            parent.setStatus("§aSaved the waypoint.");
            onClose();
            return;
        }
        message = "§aWaypoint added.";
        label.setValue("");
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
