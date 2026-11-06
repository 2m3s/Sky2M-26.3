/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.gui.profit;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The pieces a profit screen is built from - headline cards and a net-per-run graph.
 *
 * <p>Separate from the Croesus screen on purpose. killer560 (2026-09-28): "I plan on using that concept for all
 * skills in the future but just dungeons for now" - so nothing in here knows what a dungeon is. Everything
 * arrives as plain numbers and strings, and a mining or fishing tracker can draw the same screen by handing over
 * its own.
 *
 * <p>Colours are Sky2M's settings look ({@link Sky2MGuiStyle}): purple headings and accents on MoulConfig's dark
 * grey, so the Dungeon Sim's screens sit next to /s2 without looking like a different mod. They were the amber of
 * the mod the sim came from.
 */
public final class ProfitPanels {

    public static final int ACCENT = Sky2MGuiStyle.HEADING;
    public static final int BORDER = Sky2MGuiStyle.LINE;
    public static final int PANEL_BG = Sky2MGuiStyle.ROW;
    public static final int INNER_BG = 0xFF101016;
    public static final int DIM = 0xFF8C8C8C;
    public static final int TEXT = Sky2MGuiStyle.TEXT;
    public static final int GOOD = 0xFF4ADE80;
    public static final int BAD = 0xFFEF4444;

    private ProfitPanels() {
    }

    /**
     * A Dungeon Sim screen's frame in the settings look: the dimmed backdrop, a MoulConfig panel, and a title strip
     * {@code bar} tall across its top with "Dungeon Sim" and the screen's name. Every sim screen drew its own copy of
     * the old black-and-amber frame; this is the one place it lives now.
     */
    public static void frame(GuiGraphicsExtractor g, Font font, int screenW, int screenH, int x, int y, int w, int h,
                             int bar, String title) {
        Sky2MGuiStyle.backdrop(g, screenW, screenH);
        Sky2MGuiStyle.panel(g, x, y, w, h, true);
        Sky2MGuiStyle.panel(g, x + 4, y + 4, w - 8, bar - 6, false);
        g.text(font, titleText(title), x + 10, y + (bar - 8) / 2, Sky2MGuiStyle.TITLE, false);
    }

    /** How wide {@link #frame}'s title is, for text that shares the strip with it. */
    public static int titleWidth(Font font, String title) {
        return font.width(titleText(title));
    }

    private static Component titleText(String title) {
        return Component.literal("Dungeon Sim").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(title == null || title.isEmpty() ? "" : "  " + title)
                        .withStyle(ChatFormatting.GRAY));
    }

    /**
     * One headline figure: a small label, a large value, and a quieter line underneath.
     *
     * <p>The left edge is a solid bar in {@code stripe}, which is what carries the good/bad read at a glance -
     * the mockup's cards are told apart by that stripe rather than by colouring the whole box, and a full box of
     * red over a losing session is a lot to look at.
     */
    public static void card(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                            String label, String value, int valueColor, String sub, int stripe) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        g.outline(x, y, w, h, BORDER);
        g.fill(x, y, x + 2, y + h, stripe);
        g.text(font, label.toUpperCase(Locale.ROOT), x + 10, y + 8, DIM, false);
        g.text(font, value, x + 10, y + 22, valueColor, false);
        if (sub != null && !sub.isEmpty()) {
            g.text(font, font.plainSubstrByWidth(sub, w - 16), x + 10, y + h - 14, DIM, false);
        }
    }

    /**
     * Net-per-run bars, newest on the right, with a zero line.
     *
     * <p>Drawn straight rather than averaged because the shape of this data is the point: dungeon profit is a
     * long flat run of small chests with occasional enormous spikes, and any smoothing turns the one thing worth
     * seeing into a gentle slope. Bars narrower than a pixel are merged by taking the largest magnitude in the
     * bucket, so a spike can never be averaged away by the runs either side of it.
     *
     * @param values net profit per run, oldest first; may be empty
     */
    public static void barGraph(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, long[] values) {
        g.fill(x, y, x + w, y + h, INNER_BG);
        g.outline(x, y, w, h, BORDER);
        if (values.length == 0) {
            String none = "No runs logged yet";
            g.text(font, none, x + (w - font.width(none)) / 2, y + h / 2 - 4, DIM, false);
            return;
        }
        long max = 0;
        long min = 0;
        for (long v : values) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        if (max == 0 && min == 0) {
            max = 1;
        }
        int plotX = x + 4;
        int plotW = w - 8;
        int plotY = y + 4;
        int plotH = h - 16;
        long span = Math.max(1L, max - min);
        int zeroY = plotY + (int) ((double) max / span * plotH);

        g.fill(plotX, zeroY, plotX + plotW, zeroY + 1, 0xFF2A2A2A);

        int buckets = Math.min(plotW, values.length);
        for (int b = 0; b < buckets; b++) {
            int from = (int) ((long) b * values.length / buckets);
            int to = (int) ((long) (b + 1) * values.length / buckets);
            long peak = 0;
            for (int i = from; i < Math.max(to, from + 1) && i < values.length; i++) {
                if (Math.abs(values[i]) > Math.abs(peak)) {
                    peak = values[i];
                }
            }
            int barX = plotX + (int) ((long) b * plotW / buckets);
            int barW = Math.max(1, plotW / buckets);
            int px = (int) ((double) Math.abs(peak) / span * plotH);
            if (px < 1 && peak != 0) {
                px = 1;
            }
            if (peak >= 0) {
                g.fill(barX, zeroY - px, barX + barW, zeroY, ACCENT);
            } else {
                g.fill(barX, zeroY, barX + barW, zeroY + px, BAD);
            }
        }
        g.text(font, coins(max), plotX, plotY - 1, DIM, false);
        String lo = coins(min);
        g.text(font, lo, plotX, y + h - 10, DIM, false);
        String count = values.length + " runs";
        g.text(font, count, x + w - 4 - font.width(count), y + h - 10, DIM, false);
    }

    /** Short coin formatting - 1.23B / 45.6M / 789K. The screens deal in billions and a raw figure is unreadable. */
    public static String coins(long v) {
        long a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1_000_000_000L) {
            return String.format(Locale.US, "%s%.2fB", sign, a / 1_000_000_000.0);
        }
        if (a >= 1_000_000L) {
            return String.format(Locale.US, "%s%.2fM", sign, a / 1_000_000.0);
        }
        if (a >= 1_000L) {
            return String.format(Locale.US, "%s%.1fK", sign, a / 1_000.0);
        }
        return sign + a;
    }

    /** Same, but always carrying an explicit + so a profit reads as one at a glance. */
    public static String signedCoins(long v) {
        return (v >= 0 ? "+" : "") + coins(v);
    }
}
