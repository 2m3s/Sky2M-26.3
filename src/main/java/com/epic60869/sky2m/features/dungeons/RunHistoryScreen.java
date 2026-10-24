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
 * /s2 runs: your saved dungeon runs (RunHistory), newest first, filtered by floor, with each floor's personal bests at
 * the top. Click a run for its splits, P3 terminal and device times and party. In Sky2M's own colours.
 */
public final class RunHistoryScreen extends Screen {
    private static final int W = 400, ROW = 20, BG = 0xC0121722, EDGE = 0xFF3B465B, GOLD = 0xFFFFD35A, TEXT = 0xFF9AA5B8,
        BUTTON = 0xFF2A3345, BUTTON_HOVER = 0xFF3B5B8B;
    private static final String[] FLOORS = {"All", "E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};

    private String floor = "All";
    private int scroll;
    private Run open;
    private boolean confirmDelete;
    private final List<int[]> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    public RunHistoryScreen() {
        super(Component.literal("Run History"));
    }

    private Run replayRun;
    private boolean replayExists;

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return 30;
    }

    private int bottom() {
        return height - 20;
    }

    private List<Run> shown() {
        List<Run> out = new ArrayList<>();
        for (Run run : RunHistory.runs()) if (floor.equals("All") || floor.equals(run.floor)) out.add(run);
        return out;
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean selected, int mouseX, int mouseY, Runnable action) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, selected ? BUTTON_HOVER : over ? 0xFF33405A : BUTTON);
        g.outline(x, y, w, h, EDGE);
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, selected ? GOLD : 0xFFFFFFFF);
        buttons.add(new int[]{x, y, w, h});
        actions.add(action);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        buttons.clear();
        actions.clear();
        int left = left(), top = top(), bottom = bottom();
        g.centeredText(font, "Run History", width / 2, 12, GOLD);
        g.fill(left, top, left + W, bottom, BG);
        g.outline(left, top, W, bottom - top, EDGE);
        if (open != null) {
            details(g, left, top, mouseX, mouseY);
            return;
        }

        int bw = (W - 8) / FLOORS.length;
        for (int i = 0; i < FLOORS.length; i++) {
            String f = FLOORS[i];
            button(g, left + 4 + i * bw, top + 4, bw - 1, 14, f, f.equals(floor), mouseX, mouseY, () -> {
                floor = f;
                scroll = 0;
            });
        }

        List<Run> runs = shown();
        int y = top + 24;
        if (runs.isEmpty()) {
            boolean on = RunHistory.config() != null && RunHistory.config().enabled;
            g.centeredText(font, on ? "No runs saved yet. Finish a dungeon run!" : "Turn on Run History (Dungeon > Timers and Alerts) to save your runs.",
                width / 2, y + 30, TEXT);
            return;
        }
        // Personal bests and averages for the floor (or all floors).
        long bestTime = Long.MAX_VALUE, total = 0;
        int bestScore = -1, completed = 0;
        for (Run run : runs) {
            if (!run.completed) continue;
            completed++;
            total += run.totalMs;
            if (run.totalMs > 0) bestTime = Math.min(bestTime, run.totalMs);
            bestScore = Math.max(bestScore, run.effectiveScore());
        }
        String stats = runs.size() + " runs (" + completed + " cleared)   Best: " + (bestTime == Long.MAX_VALUE ? "-" : RunHistory.time(bestTime))
            + "   Best score: " + (bestScore < 0 ? "-" : bestScore) + "   Average: " + (completed == 0 ? "-" : RunHistory.time(total / completed));
        g.text(font, font.plainSubstrByWidth(stats, W - 12), left + 6, y, 0xFFFFFFFF, true);
        y += 14;
        int[] cols = {left + 6, left + 106, left + 140, left + 220, left + 290, left + 340};
        String[] heads = {"Date", "Floor", "Time", "Score", "Secrets", "Deaths"};
        for (int i = 0; i < heads.length; i++) g.text(font, heads[i], cols[i], y, GOLD, true);
        y += 12;
        int visible = Math.max(1, (bottom - y - 4) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, runs.size() - visible));
        for (int i = scroll; i < runs.size() && i - scroll < visible; i++) {
            Run run = runs.get(i);
            int ry = y + (i - scroll) * ROW;
            boolean over = mouseX >= left + 2 && mouseX < left + W - 2 && mouseY >= ry && mouseY < ry + ROW;
            if (over) g.fill(left + 1, ry, left + W - 1, ry + ROW, 0x20FFFFFF);
            int ty = ry + 6;
            g.text(font, run.date(), cols[0], ty, TEXT, true);
            g.text(font, run.floor == null ? "?" : run.floor, cols[1], ty, run.floor != null && run.floor.startsWith("M") ? 0xFFFF5555 : 0xFF55FF55, true);
            g.text(font, (run.completed ? "" : "§c") + RunHistory.time(run.totalMs) + (run.bestTime ? " §6★" : ""), cols[2], ty, 0xFFFFFFFF, true);
            int score = run.effectiveScore();
            g.text(font, score < 0 ? "-" : score + " (" + run.effectiveRank() + ")" + (run.bestScore ? " §6★" : ""), cols[3], ty, 0xFF55FFFF, true);
            g.text(font, run.secrets < 0 ? "-" : String.valueOf(run.secrets), cols[4], ty, 0xFFFFFFFF, true);
            g.text(font, run.deaths < 0 ? "-" : String.valueOf(run.deaths), cols[5], ty, run.deaths > 0 ? 0xFFFF5555 : 0xFFFFFFFF, true);
            buttons.add(new int[]{left + 2, ry, W - 4, ROW});
            actions.add(() -> {
                open = run;
                confirmDelete = false;
            });
        }
        if (runs.size() > visible) {
            int trackH = visible * ROW, thumb = Math.max(10, trackH * visible / runs.size());
            int thumbY = y + (trackH - thumb) * scroll / Math.max(1, runs.size() - visible);
            g.fill(left + W - 4, y, left + W - 2, y + trackH, 0xFF0C0F16);
            g.fill(left + W - 4, thumbY, left + W - 2, thumbY + thumb, EDGE);
        }
    }

    private void details(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        Run run = open;
        button(g, left + 4, top + 4, 50, 14, "◀ Back", false, mouseX, mouseY, () -> open = null);
        if (replayRun != run) { // one disk check per opened run, not one per frame
            replayRun = run;
            replayExists = RouteReplay.has(run.startedAt);
        }
        if (replayExists) {
            button(g, left + 58, top + 4, 60, 14, "▶ Replay", false, mouseX, mouseY, () -> {
                RouteReplay.Replay replay = RouteReplay.load(run.startedAt);
                if (replay != null) minecraft.gui.setScreen(new RouteReplayScreen(replay, this));
            });
        }
        button(g, left + W - 74, top + 4, 70, 14, confirmDelete ? "Sure?" : "Delete", confirmDelete, mouseX, mouseY, () -> {
            if (!confirmDelete) {
                confirmDelete = true;
                return;
            }
            RunHistory.delete(run);
            open = null;
        });
        int y = top + 24, x = left + 8, half = left + W / 2 + 4;
        g.text(font, (run.floor == null ? "?" : run.floor) + "  " + run.date(), x, y, GOLD, true);
        y += 14;
        int ly = y;
        ly = stat(g, x, ly, "Time", RunHistory.time(run.totalMs) + (run.bestTime ? " (PB)" : ""));
        if (run.hypixelTime != null) ly = stat(g, x, ly, "Hypixel's time", run.hypixelTime + (run.hypixelRecord ? " (NEW RECORD!)" : ""));
        ly = stat(g, x, ly, "Cleared", run.completed ? "Yes" : "No");
        if (run.effectiveScore() >= 0) ly = stat(g, x, ly, "Score", run.effectiveScore() + " (" + run.effectiveRank() + ")" + (run.bestScore ? " (PB)" : ""));
        if (run.hypixelScore >= 0 && run.score >= 0) ly = stat(g, x, ly, "Sky2M's estimate", String.valueOf(run.score));
        if (run.secrets >= 0) ly = stat(g, x, ly, "Secrets", run.secrets + String.format(Locale.ROOT, " (%.0f%%)", Math.max(0, run.secretPercent)));
        if (run.crypts >= 0) ly = stat(g, x, ly, "Crypts", String.valueOf(run.crypts));
        if (run.deaths >= 0) ly = stat(g, x, ly, "Deaths", String.valueOf(run.deaths));
        if (run.dungeonClass != null) ly = stat(g, x, ly, "Class", run.dungeonClass);
        ly += 6;
        g.text(font, "Party", x, ly, GOLD, true);
        ly += 11;
        for (RunHistory.Member m : run.party) {
            g.text(font, m.name() + (m.dungeonClass() == null ? "" : " §7(" + m.dungeonClass() + ")"), x + 4, ly, 0xFFFFFFFF, true);
            ly += 10;
        }

        int ry = y;
        g.text(font, "Splits", half, ry, GOLD, true);
        ry += 11;
        if (run.splits.isEmpty()) {
            g.text(font, "None recorded", half + 4, ry, TEXT, true);
            ry += 10;
        }
        for (RunHistory.Split s : run.splits) ry = stat(g, half + 4, ry, s.name(), RunHistory.time(s.ms()));
        if (!run.devices.isEmpty()) {
            ry += 6;
            g.text(font, "P3 terminals and devices", half, ry, GOLD, true);
            ry += 11;
            for (RunHistory.Device d : run.devices) {
                if (ry > bottom() - 12) break;
                String when = d.msIntoPhase() < 0 ? "" : String.format(Locale.ROOT, " %.2fs", d.msIntoPhase() / 1000.0);
                g.text(font, font.plainSubstrByWidth(d.player() + " " + d.kind() + " (" + d.index() + "/" + d.total() + ")" + when, W / 2 - 16), half + 4, ry, 0xFFFFFFFF, true);
                ry += 10;
            }
        }
    }

    private int stat(GuiGraphicsExtractor g, int x, int y, String label, String value) {
        g.text(font, label + ": ", x, y, TEXT, true);
        g.text(font, value, x + font.width(label + ": "), y, 0xFFFFFFFF, true);
        return y + 10;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (open == null) scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
