package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.dungeons.RunHistory.Run;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Run Summary card: the run's floor, time, score, secrets, crypts, deaths (with the death log), splits, party and
 * chest profit. Opened by /s2 runsummary (your latest run) or by the [View] button on a run summary in S2C, which shows
 * "Loading" until the Sky2M server sends the data.
 */
final class RunSummaryScreen extends Screen {
    private static final int W = 380, BG = 0xE0121722, EDGE = 0xFF3B465B, GOLD = 0xFFFFD35A, TEXT = 0xFF9AA5B8,
        BUTTON = 0xFF2A3345, WHITE = 0xFFFFFFFF;

    private Run run;
    private Double profit;
    private String owner;
    private final boolean own;
    private String status = "Loading run summary...";
    private final long openedAt = System.currentTimeMillis();
    private final List<int[]> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    /** A summary that is still being fetched from the server. */
    RunSummaryScreen() {
        super(Component.literal("Run Summary"));
        this.own = false;
    }

    RunSummaryScreen(Run run, Double profit, String owner, boolean own) {
        super(Component.literal("Run Summary"));
        this.run = run;
        this.profit = profit;
        this.owner = owner;
        this.own = own;
        this.status = null;
    }

    void show(Run run, Double profit, String owner) {
        this.run = run;
        this.profit = profit;
        this.owner = owner;
        this.status = null;
    }

    void fail(String message) {
        if (run == null) status = message;
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mouseX, int mouseY, Runnable action) {
        int h = 16;
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, over ? 0xFF3B5B8B : BUTTON);
        g.outline(x, y, w, h, EDGE);
        g.centeredText(font, label, x + w / 2, y + 4, WHITE);
        buttons.add(new int[]{x, y, w, h});
        actions.add(action);
    }

    private int stat(GuiGraphicsExtractor g, int x, int y, String label, String value, int colour) {
        g.text(font, label + ": ", x, y, TEXT, true);
        g.text(font, value, x + font.width(label + ": "), y, colour, true);
        return y + 10;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        buttons.clear();
        actions.clear();
        int left = (width - W) / 2, top = 20, bottom = height - 20;
        g.fill(left, top, left + W, bottom, BG);
        g.outline(left, top, W, bottom - top, EDGE);
        g.centeredText(font, owner == null ? "Run Summary" : "Run Summary - " + owner, width / 2, top + 6, GOLD);
        int by = bottom - 24;
        if (run == null) {
            if (status != null && status.startsWith("Loading") && System.currentTimeMillis() - openedAt > 10_000L) {
                status = "Couldn't load this run summary. Try clicking [View] again.";
            }
            g.centeredText(font, status == null ? "" : status, width / 2, top + 40, TEXT);
            button(g, left + W - 10 - 80, by, 80, "Close", mouseX, mouseY, this::onClose);
            return;
        }
        int x = left + 10, half = left + W / 2 + 6, y = top + 22;

        String floor = run.floor == null ? "?" : run.floor;
        g.text(font, floor + (run.completed ? " cleared" : " run") + "  " + run.date(), x, y, floor.startsWith("M") ? 0xFFFF5555 : 0xFF55FF55, true);
        y += 14;
        int ly = y;
        ly = stat(g, x, ly, "Time", RunHistory.time(run.totalMs) + (run.bestTime ? " (PB)" : ""), WHITE);
        if (run.effectiveScore() >= 0) ly = stat(g, x, ly, "Score", run.effectiveScore() + (run.effectiveRank() == null || run.effectiveRank().isEmpty() ? "" : " (" + run.effectiveRank() + ")") + (run.bestScore ? " (PB)" : ""), 0xFF55FFFF);
        if (run.secrets >= 0) ly = stat(g, x, ly, "Secrets", run.secrets + String.format(Locale.ROOT, " (%.0f%%)", Math.max(0, run.secretPercent)), WHITE);
        if (run.crypts >= 0) ly = stat(g, x, ly, "Crypts", String.valueOf(run.crypts), WHITE);
        int deaths = run.deathLog != null ? run.deathLog.size() : Math.max(0, run.deaths);
        ly = stat(g, x, ly, "Deaths", String.valueOf(deaths), deaths > 0 ? 0xFFFF5555 : WHITE);
        if (profit != null) {
            ly = stat(g, x, ly, "Chest profit", RunSummary.coins(profit), profit >= 0 ? 0xFF55FF55 : 0xFFFF5555);
        }
        ly += 6;
        g.text(font, "Party", x, ly, GOLD, true);
        ly += 11;
        for (RunHistory.Member m : run.party) {
            g.text(font, m.name() + (m.dungeonClass() == null ? "" : " §7(" + m.dungeonClass() + ")"), x + 4, ly, WHITE, true);
            ly += 10;
        }

        int ry = y;
        g.text(font, "Splits", half, ry, GOLD, true);
        ry += 11;
        if (run.splits.isEmpty()) {
            g.text(font, "None recorded", half + 4, ry, TEXT, true);
            ry += 10;
        }
        for (RunHistory.Split s : run.splits) {
            if (ry > bottom - 60) break;
            ry = stat(g, half + 4, ry, s.name(), RunHistory.time(s.ms()), WHITE);
        }
        if (run.deathLog != null && !run.deathLog.isEmpty()) {
            ry += 6;
            g.text(font, "Deaths", half, ry, GOLD, true);
            ry += 11;
            for (DeathRecap.DeathEntry d : run.deathLog) {
                if (ry > bottom - 36) break;
                g.text(font, font.plainSubstrByWidth(DeathRecap.line(d), W / 2 - 20), half + 4, ry, 0xFFFF8888, true);
                ry += 10;
            }
        }

        if (own && !RunSummary.posted(run)) {
            button(g, left + 10, by, 110, "Post to /s2c", mouseX, mouseY, () -> RunSummary.post(run, false));
        }
        button(g, left + W - 10 - 80, by, 80, "Close", mouseX, mouseY, this::onClose);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < buttons.size(); i++) {
            int[] b = buttons.get(i);
            if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
                actions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
