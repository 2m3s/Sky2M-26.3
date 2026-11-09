package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 slayertimes: your kill times this session as a chart, one boss and tier at a time, with the count, average, best and
 * spread (how far kills usually land from the average). Drawn like /s2 catacalc ({@link Sky2MGuiStyle}).
 */
public class SlayerConsistencyScreen extends Screen {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final int LINE_COLOUR = 0xFF55FFFF, AVERAGE_COLOUR = 0x80FFFF55, BEST_COLOUR = 0xFF55FF55;

    private final Screen parent;
    private String boss;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public SlayerConsistencyScreen(Screen parent) {
        super(Component.literal("Slayer Times"));
        this.parent = parent;
        List<String> bosses = SlayerSessionTimes.bosses();
        String last = SlayerTimes.lastBoss();
        boss = last != null && bosses.contains(last) ? last : bosses.isEmpty() ? null : bosses.getLast();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        int w = Math.min(460, width - 20), h = Math.min(290, height - 12);
        int x = (width - w) / 2, y = Math.max(6, (height - h) / 2);
        Sky2MGuiStyle.panel(g, x, y, w, h, true);
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("Slayer Times"));

        // The bosses you've killed this session, as tabs.
        int tabX = x + 10, tabY = y + 28;
        for (String name : SlayerSessionTimes.bosses()) {
            int tw = font.width(name);
            if (tabX + tw > x + w - 10) {
                tabX = x + 10;
                tabY += 13;
            }
            int tx = tabX, ty = tabY;
            boolean hover = mouseX >= tx - 2 && mouseX < tx + tw + 2 && mouseY >= ty - 2 && mouseY < ty + 10;
            Sky2MGuiStyle.category(g, font, name, tx, ty, name.equals(boss), hover);
            hits.add(new Hit(tx - 2, ty - 2, tw + 4, 12, () -> boss = name));
            tabX += tw + 12;
        }

        // Done.
        int doneW = 80, doneX = x + (w - doneW) / 2, doneY = y + h - 24;
        boolean doneHover = mouseX >= doneX && mouseX < doneX + doneW && mouseY >= doneY && mouseY < doneY + 18;
        Sky2MGuiStyle.button(g, font, "Done", doneX, doneY, doneW, 18, doneHover);
        hits.add(new Hit(doneX, doneY, doneW, 18, this::onClose));

        List<SlayerSessionTimes.Kill> kills = boss == null ? List.of() : SlayerSessionTimes.kills(boss);
        int statsY = tabY + 16;
        if (kills.isEmpty()) {
            g.centeredText(font, "No timed kills this session yet.", x + w / 2, y + h / 2 - 8, Sky2MGuiStyle.TEXT_DIM);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }
        SlayerSessionTimes.Stats stats = SlayerSessionTimes.stats(kills);
        String[][] columns = {{"Kills", String.valueOf(stats.count())}, {"Average", SlayerTimes.format(stats.average())},
            {"Best", SlayerTimes.format(stats.best())}, {"Spread", "±" + SlayerTimes.format(stats.spread())}};
        int columnW = (w - 16) / columns.length;
        for (int i = 0; i < columns.length; i++) {
            int cx = x + 8 + i * columnW + columnW / 2;
            g.centeredText(font, columns[i][0], cx, statsY, Sky2MGuiStyle.HEADING);
            g.centeredText(font, columns[i][1], cx, statsY + 11, Sky2MGuiStyle.TEXT);
        }

        int left = x + 54, right = x + w - 12, top = statsY + 26, bottom = doneY - 8;
        Sky2MGuiStyle.inset(g, left, top, right, bottom);
        chart(g, kills, stats, left, top, right, bottom, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /** Kill times left to right, with lines at the average and the best, and the kill under the mouse in a tooltip. */
    private void chart(GuiGraphicsExtractor g, List<SlayerSessionTimes.Kill> kills, SlayerSessionTimes.Stats stats, int left, int top, int right, int bottom, int mouseX, int mouseY) {
        double min = stats.best(), max = Math.max(stats.worst(), min + 1);
        for (long value : new long[]{stats.worst(), stats.average(), stats.best()}) {
            int y = yOf(value, min, max, top, bottom);
            String label = SlayerTimes.format(value);
            g.text(font, label, left - 4 - font.width(label), y - 4, Sky2MGuiStyle.TEXT_DIM, false);
        }
        int averageY = yOf(stats.average(), min, max, top, bottom);
        g.fill(left + 1, averageY, right - 1, averageY + 1, AVERAGE_COLOUR);
        int[] previous = null;
        int hovered = -1, hoverX = -1;
        for (int i = 0; i < kills.size(); i++) {
            int x = kills.size() == 1 ? (left + right) / 2 : left + 4 + (right - left - 10) * i / (kills.size() - 1);
            int y = yOf(kills.get(i).ms(), min, max, top, bottom);
            int[] point = {x, y};
            if (previous != null) line(g, previous, point, LINE_COLOUR);
            g.fill(x - 1, y - 1, x + 2, y + 2, kills.get(i).ms() == stats.best() ? BEST_COLOUR : LINE_COLOUR);
            previous = point;
            if (mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom && (hovered < 0 || Math.abs(x - mouseX) < Math.abs(hoverX - mouseX))) {
                hovered = i;
                hoverX = x;
            }
        }
        if (hovered >= 0) {
            SlayerSessionTimes.Kill kill = kills.get(hovered);
            g.fill(hoverX, top + 1, hoverX + 1, bottom - 1, 0x60FFFFFF);
            g.setComponentTooltipForNextFrame(font, List.of(Component.literal("Kill " + (hovered + 1) + ": " + SlayerTimes.format(kill.ms())),
                Component.literal(CLOCK.format(Instant.ofEpochMilli(kill.at()).atZone(ZoneId.systemDefault()))).withColor(0xAAAAAA)), mouseX, mouseY);
        }
    }

    private static int yOf(double value, double min, double max, int top, int bottom) {
        return bottom - 6 - (int) ((bottom - top - 12) * (value - min) / (max - min));
    }

    private static void line(GuiGraphicsExtractor g, int[] a, int[] b, int colour) {
        int steps = Math.max(1, Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])));
        for (int i = 0; i <= steps; i++) {
            int x = a[0] + (b[0] - a[0]) * i / steps, y = a[1] + (b[1] - a[1]) * i / steps;
            g.fill(x, y, x + 1, y + 1, colour);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hit hit : List.copyOf(hits)) {
                if (event.x() >= hit.x && event.x() < hit.x + hit.w && event.y() >= hit.y && event.y() < hit.y + hit.h) {
                    Sky2MGuiStyle.click();
                    hit.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
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
