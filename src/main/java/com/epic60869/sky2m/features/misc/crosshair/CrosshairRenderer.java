package com.epic60869.sky2m.features.misc.crosshair;

import com.epic60869.sky2m.features.FeatureConfigs.Crosshair;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Draws a custom crosshair in screen pixels: the pose is scaled by 1 / GUI scale so every size rounds to whole screen
 * pixels and one-pixel lines stay crisp at any GUI scale. The shape is laid out from its centre square (the line
 * thickness), so the arms are exactly symmetric; quarter turns are done in whole numbers, and any other rotation (the
 * X style's 45 degrees too) through the pose. The circle is a cached list of rectangles, rebuilt only when its size
 * changes, so nothing is allocated per frame.
 * <p>
 * Ported from Killer560's Mod (crosshair/CrosshairRenderer), MIT License, Copyright (c) 2026 Killer560.
 */
public final class CrosshairRenderer {
    private CrosshairRenderer() {}

    /** The rectangles covering every pixel whose centre is inside the ring, with identical rows merged. */
    private static final class Ring {
        int radius = Integer.MIN_VALUE, width, anchor, n;
        int[] rects = new int[64];

        void ensure(int radius, int width, int anchor) {
            if (radius == this.radius && width == this.width && anchor == this.anchor) return;
            this.radius = radius;
            this.width = width;
            this.anchor = anchor;
            build();
        }

        private void build() {
            n = 0;
            float c = anchor / 2f;
            float outer = radius + width / 2f;
            float inner = radius - width / 2f;
            int prevStart = -1, prevCount = 0;
            for (int y = (int) Math.floor(c - outer) - 1; y <= (int) Math.ceil(c + outer) + 1; y++) {
                float py = y + 0.5f - c;
                if (Math.abs(py) >= outer) {
                    prevCount = 0;
                    continue;
                }
                float wo = (float) Math.sqrt(outer * outer - py * py);
                int xl = (int) Math.ceil(c - wo - 0.5f + 1e-4f);
                int xr = (int) Math.floor(c + wo - 0.5f - 1e-4f);
                int il = Integer.MAX_VALUE, ir = Integer.MIN_VALUE;
                if (inner > 0 && Math.abs(py) < inner) {
                    float wi = (float) Math.sqrt(inner * inner - py * py);
                    il = (int) Math.ceil(c - wi - 0.5f + 1e-4f);
                    ir = (int) Math.floor(c + wi - 0.5f - 1e-4f);
                }
                // This row's spans, as [x0, x1).
                int s0a, s0b, s1a = 0, s1b = 0, count;
                if (il > ir) {
                    s0a = xl;
                    s0b = xr + 1;
                    count = 1;
                } else {
                    s0a = xl;
                    s0b = il;
                    s1a = ir + 1;
                    s1b = xr + 1;
                    count = 2;
                    if (s0b <= s0a) {
                        s0a = s1a;
                        s0b = s1b;
                        count = 1;
                    } else if (s1b <= s1a) {
                        count = 1;
                    }
                }
                if (s0b <= s0a) {
                    prevCount = 0;
                    continue;
                }
                if (prevCount == count && rects[prevStart] == s0a && rects[prevStart + 2] == s0b
                    && (count == 1 || (rects[prevStart + 4] == s1a && rects[prevStart + 6] == s1b))) {
                    rects[prevStart + 3] = y + 1;
                    if (count == 2) rects[prevStart + 7] = y + 1;
                    continue;
                }
                if ((n + 2) * 4 > rects.length) rects = java.util.Arrays.copyOf(rects, rects.length * 2);
                prevStart = n * 4;
                rects[n * 4] = s0a;
                rects[n * 4 + 1] = y;
                rects[n * 4 + 2] = s0b;
                rects[n * 4 + 3] = y + 1;
                n++;
                if (count == 2) {
                    rects[n * 4] = s1a;
                    rects[n * 4 + 1] = y;
                    rects[n * 4 + 2] = s1b;
                    rects[n * 4 + 3] = y + 1;
                    n++;
                }
                prevCount = count;
            }
        }
    }

    private static final Ring RING = new Ring();
    private static final Ring RING_OUTLINE = new Ring();

    /**
     * Draws the crosshair centred on screen pixel ({@code centreX}, {@code centreY}).
     *
     * @param unit   screen pixels per crosshair unit
     * @param spread extra gap and radius (movement and swing), in crosshair units
     */
    public static void draw(GuiGraphicsExtractor g, Crosshair c, float centreX, float centreY, int guiScale, float unit,
                            float spread, int mainColour, int dotColour, int outlineColour) {
        Crosshair.Style style = c.style;
        boolean arms = style.hasArms();
        boolean circle = style.hasCircle();
        boolean dot = style == Crosshair.Style.DOT || c.dot;
        int t = Math.max(1, Math.round(c.thickness * unit));
        int len = Math.max(0, Math.round(c.length * unit));
        int gap = Math.round((c.gap + spread) * unit);
        int d = Math.max(1, Math.round(c.dotSize * unit));
        // Everything is laid out from this centre square; the dot keeps its parity so it sits dead centre.
        int a = arms ? t : d;
        if (arms && ((d - a) & 1) != 0) d++;
        int o = c.outline ? Math.max(1, Math.round(c.outlineThickness * unit)) : 0;
        boolean drawMain = (mainColour >>> 24) != 0;
        boolean drawDot = dot && (dotColour >>> 24) != 0;
        boolean drawOutline = o > 0 && (outlineColour >>> 24) != 0;

        float rot = c.rotation + (style == Crosshair.Style.X ? 45f : 0f);
        rot = ((rot % 360f) + 360f) % 360f;
        boolean free = rot % 90f != 0f;
        int k = free ? 0 : Math.round(rot / 90f) & 3;

        int cx0 = Math.round(centreX - a / 2f);
        int cy0 = Math.round(centreY - a / 2f);
        if (circle) {
            int radius = Math.max(1, Math.round((c.circleRadius + spread) * unit));
            int ringWidth = Math.max(1, Math.round(c.circleThickness * unit));
            RING.ensure(radius, ringWidth, a);
            if (drawOutline) RING_OUTLINE.ensure(radius, ringWidth + 2 * o, a);
        }
        boolean top = arms && c.armTop && style != Crosshair.Style.T_SHAPE && len > 0;
        boolean bottom = arms && c.armBottom && len > 0;
        boolean left = arms && c.armLeft && len > 0;
        boolean right = arms && c.armRight && len > 0;
        int dOff = (a - d) / 2;
        boolean invert = c.invert;

        var pose = g.pose();
        pose.pushMatrix();
        try {
            pose.identity();
            pose.scale(1f / guiScale, 1f / guiScale);
            pose.translate(cx0, cy0);
            if (free) {
                pose.translate(a / 2f, a / 2f);
                pose.rotate((float) Math.toRadians(rot));
                pose.translate(-a / 2f, -a / 2f);
            }
            if (drawOutline) {
                if (right) rect(g, k, a, a + gap - o, -o, a + gap + len + o, a + o, outlineColour, false);
                if (left) rect(g, k, a, -gap - len - o, -o, -gap + o, a + o, outlineColour, false);
                if (bottom) rect(g, k, a, -o, a + gap - o, a + o, a + gap + len + o, outlineColour, false);
                if (top) rect(g, k, a, -o, -gap - len - o, a + o, -gap + o, outlineColour, false);
                if (circle) ring(g, k, a, RING_OUTLINE, outlineColour, false);
                if (dot) rect(g, k, a, dOff - o, dOff - o, dOff + d + o, dOff + d + o, outlineColour, false);
            }
            if (drawMain) {
                if (right) rect(g, k, a, a + gap, 0, a + gap + len, a, mainColour, invert);
                if (left) rect(g, k, a, -gap - len, 0, -gap, a, mainColour, invert);
                if (bottom) rect(g, k, a, 0, a + gap, a, a + gap + len, mainColour, invert);
                if (top) rect(g, k, a, 0, -gap - len, a, -gap, mainColour, invert);
                if (circle) ring(g, k, a, RING, mainColour, invert);
            }
            if (drawDot) rect(g, k, a, dOff, dOff, dOff + d, dOff + d, dotColour, invert);
        } finally {
            pose.popMatrix();
        }
    }

    private static void ring(GuiGraphicsExtractor g, int k, int a, Ring ring, int colour, boolean invert) {
        int[] r = ring.rects;
        for (int i = 0; i < ring.n; i++) rect(g, k, a, r[i * 4], r[i * 4 + 1], r[i * 4 + 2], r[i * 4 + 3], colour, invert);
    }

    /** One rectangle in the centre square's frame, turned {@code k} quarter turns about its middle. */
    private static void rect(GuiGraphicsExtractor g, int k, int a, int x0, int y0, int x1, int y1, int colour, boolean invert) {
        int rx0, ry0, rx1, ry1;
        switch (k) {
            case 1 -> { rx0 = a - y1; rx1 = a - y0; ry0 = x0; ry1 = x1; }
            case 2 -> { rx0 = a - x1; rx1 = a - x0; ry0 = a - y1; ry1 = a - y0; }
            case 3 -> { rx0 = y0; rx1 = y1; ry0 = a - x1; ry1 = a - x0; }
            default -> { rx0 = x0; rx1 = x1; ry0 = y0; ry1 = y1; }
        }
        if (rx1 <= rx0 || ry1 <= ry0) return;
        if (invert) g.fill(RenderPipelines.GUI_INVERT, rx0, ry0, rx1, ry1, colour);
        else g.fill(rx0, ry0, rx1, ry1, colour);
    }
}
