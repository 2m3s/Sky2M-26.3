package com.epic60869.sky2m.features.misc.waypoints;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Drawing helpers for the Area Waypoints screens: rounded dark panels with a blue accent, like Odin's manager. */
final class WaypointUi {
    static final int BACKGROUND = 0xF01B1B1B;
    static final int SURFACE = 0xFF262626;
    static final int SURFACE_HOVER = 0xFF303030;
    static final int ACCENT = 0xFF2B9BDA;
    static final int ACCENT_FILL = 0xFF1C4A60;
    static final int LINE = 0xFF3A3A3A;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFFAAAAAA;
    static final int DIM = 0xFF555555;

    private WaypointUi() {}

    static Font font() {
        return Minecraft.getInstance().font;
    }

    /** A rectangle with its corners cut by {@code r} pixels. */
    static void round(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int colour, int r) {
        g.fill(x1 + r, y1, x2 - r, y2, colour);
        g.fill(x1, y1 + r, x1 + r, y2 - r, colour);
        g.fill(x2 - r, y1 + r, x2, y2 - r, colour);
        if (r > 1) {
            g.fill(x1 + 1, y1 + 1, x1 + r, y1 + r, colour);
            g.fill(x2 - r, y1 + 1, x2 - 1, y1 + r, colour);
            g.fill(x1 + 1, y2 - r, x1 + r, y2 - 1, colour);
            g.fill(x2 - r, y2 - r, x2 - 1, y2 - 1, colour);
        }
    }

    /** An outlined, rounded box: the accent border with {@code fill} inside. */
    static void outlined(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        round(g, x, y, x + w, y + h, border, 3);
        round(g, x + 1, y + 1, x + w - 1, y + h - 1, fill, 2);
    }

    /** A choice or action button; {@code selected} fills it with the accent. */
    static void button(GuiGraphicsExtractor g, String label, int x, int y, int w, int h, boolean selected, boolean hover, boolean enabled) {
        outlined(g, x, y, w, h, selected ? ACCENT_FILL : hover && enabled ? SURFACE_HOVER : SURFACE, enabled ? ACCENT : DIM);
        Font font = font();
        g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, !enabled ? DIM : selected || hover ? TEXT : MUTED, false);
    }

    /** "Title ───": a section heading with a line after it. */
    static void section(GuiGraphicsExtractor g, String title, int x, int y, int w) {
        Font font = font();
        g.text(font, title, x, y, ACCENT, false);
        g.fill(x + font.width(title) + 4, y + 4, x + w, y + 5, 0x5A2B9BDA);
    }

    static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
