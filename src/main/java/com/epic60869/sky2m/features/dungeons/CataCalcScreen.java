package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.profileviewer.ProfileApi;
import com.epic60869.sky2m.features.tracker.DungeonProfitHour;
import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 catacalc: {@link CataCalculator} on one screen, drawn like /s2 hour ({@link Sky2MGuiStyle}). Pick a floor; the
 * target level (50), XP per run (your last run of the floor, else its base S+ XP with the XP Bonus setting) and run time
 * (your Run History average plus your usual gap between runs) fill in, and you can type over them. Your Catacombs XP is
 * loaded from your profile when the screen opens. One panel has the runs and time to the target, the other the runs to
 * each level on the way. Below them, After runs: type how many runs of the floor and it says the Catacombs level you'll
 * be, the XP they give and how long they take.
 */
public class CataCalcScreen extends Screen {
    private static final String[] FLOORS = {"E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};
    private static final int ROW = 12;
    private static final int RESULT_ROWS = 8;

    /** Kept while the game runs. */
    private static String floor;
    private static String targetText = "50";
    private static String runsText = "100";
    /** The After runs section's rows: label, value. */
    private static final int AFTER_ROWS = 3;

    private final Screen parent;
    private String xpText = "", timeText = "";
    private boolean fillDefaults = true;
    private EditBox targetBox, xpBox, timeBox, runsBox;
    private final List<String[]> after = new ArrayList<>();
    /** Your Catacombs XP once loaded, -1 while loading, -2 if it couldn't be. */
    private double xp = -1;
    private String xpError = "";
    private final List<String[]> totals = new ArrayList<>(), levels = new ArrayList<>();
    private String perRunSource = "";
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public CataCalcScreen(Screen parent) {
        super(Component.literal("Catacombs Calculator"));
        this.parent = parent;
        if (floor == null) floor = CataCalculator.startFloor();
        CataCalculator.yourXp().whenComplete((value, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null || value == null) {
                xp = -2;
                xpError = ProfileApi.reason(error);
            } else {
                xp = value;
            }
            if (targetBox != null) refresh();
        }));
    }

    // ------------------------------------------------------------------------------------------------ layout

    private int panelW() {
        return Math.min(460, width - 20);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelH() {
        return 128 + RESULT_ROWS * ROW + 22 + 34 + afterH() + 8;
    }

    /** Where the two result panels start. */
    private int resultsTop() {
        return fieldY() + Sky2MGuiStyle.SEARCH_HEIGHT + 6 + 16;
    }

    /** The After runs section, under the result panels. */
    private int afterY() {
        return resultsTop() + 22 + RESULT_ROWS * ROW + 8;
    }

    private int afterH() {
        return 22 + AFTER_ROWS * ROW;
    }

    private int panelY() {
        return Math.max(6, (height - panelH()) / 2);
    }

    private int fieldW() {
        return (panelW() - 16 - 2 * 10) / 3;
    }

    private int fieldX(int i) {
        return panelX() + 8 + i * (fieldW() + 10);
    }

    private int fieldY() {
        return panelY() + 82;
    }

    @Override
    protected void init() {
        targetBox = box(targetText, 0);
        xpBox = box(xpText, 1);
        timeBox = box(timeText, 2);
        if (fillDefaults) {
            fillDefaults = false;
            CataCalculator.PerRun per = CataCalculator.perRun(floor);
            xpBox.setValue(per.xp() > 0 ? DungeonProfitHour.whole(per.xp()) : "");
            double run = DungeonProfitHour.averageRunSeconds(floor, DungeonProfitHour.runHistory());
            double gap = DungeonProfitHour.history(floor).gapSeconds();
            timeBox.setValue(run > 0 ? DungeonProfitHour.time(run + Math.max(0, gap)) : "");
        }
        runsBox = new EditBox(font, 0, 0, 10, 10, Component.empty());
        runsBox.setMaxLength(8);
        Sky2MGuiStyle.placeSearch(runsBox, panelX() + 16, afterY() + 32, 80);
        runsBox.setValue(runsText);
        addRenderableWidget(runsBox);
        targetBox.setResponder(s -> refresh());
        xpBox.setResponder(s -> refresh());
        timeBox.setResponder(s -> refresh());
        runsBox.setResponder(s -> refresh());
        refresh();
    }

    private EditBox box(String value, int i) {
        EditBox box = new EditBox(font, 0, 0, 10, 10, Component.empty());
        box.setMaxLength(14);
        Sky2MGuiStyle.placeSearch(box, fieldX(i), fieldY(), fieldW());
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private void pickFloor(String f) {
        if (f.equals(floor)) return;
        floor = f;
        fillDefaults = true;
        xpText = timeText = "";
        targetText = targetBox.getValue();
        rebuildWidgets();
    }

    // ------------------------------------------------------------------------------------------------ numbers

    private void refresh() {
        targetText = targetBox.getValue();
        xpText = xpBox.getValue();
        timeText = timeBox.getValue();
        if (runsBox != null) runsText = runsBox.getValue();
        totals.clear();
        levels.clear();
        after.clear();
        CataCalculator.PerRun per = CataCalculator.perRun(floor);
        double perRun = number(xpText);
        double auto = per.xp();
        perRunSource = perRun > 0 && Math.round(perRun) == Math.round(auto) ? "From " + per.source() : perRun > 0 ? "XP per run typed in" : "";
        int target = (int) number(targetText);
        double runSeconds = timeText.isBlank() ? -1 : DungeonProfitHour.seconds(timeText);

        if (xp == -1) {
            totals.add(new String[]{"§7Loading your Catacombs XP...", ""});
            after.add(new String[]{"§7Loading your Catacombs XP...", ""});
            return;
        }
        if (xp == -2) {
            totals.add(new String[]{"§cCouldn't load your profile", ""});
            totals.add(new String[]{"§8" + xpError, ""});
            after.add(new String[]{"§cCouldn't load your profile", ""});
            return;
        }
        afterRuns(perRun, runSeconds);
        totals.add(new String[]{"Now", String.format(Locale.US, "§fCatacombs %.2f", CataCalculator.level(xp))});
        totals.add(new String[]{"§8  XP", "§8" + DungeonProfitHour.whole(xp)});
        if (target < 1 || target > 100) {
            totals.add(new String[]{"§cType a target level, 1-100", ""});
            return;
        }
        double need = CataCalculator.xpFor(target) - xp;
        if (need <= 0) {
            totals.add(new String[]{"§aAlready past " + target, ""});
            return;
        }
        totals.add(new String[]{"Target", "§fCatacombs " + target});
        totals.add(new String[]{"XP to go", "§6" + CombatFeatures.formatCoins(need)});
        if (perRun <= 0) {
            totals.add(new String[]{"§cType the XP per run", ""});
            return;
        }
        long runs = (long) Math.ceil(need / perRun);
        totals.add(new String[]{"Runs needed", "§b" + DungeonProfitHour.whole(runs) + " " + floor});
        if (runSeconds > 0) totals.add(new String[]{"Time", "§a" + DungeonProfitHour.hours(runs * runSeconds)});
        else totals.add(new String[]{"§8Type a run time for the time", ""});

        // The runs (from now) to each level on the way: every level when they fit, else spread out, ending on the target.
        int from = (int) Math.floor(CataCalculator.level(xp)) + 1;
        int count = target - from + 1;
        int shown = Math.min(count, RESULT_ROWS);
        for (int k = 0; k < shown; k++) {
            int lv = count <= RESULT_ROWS ? from + k : from + (int) Math.round(k * (count - 1) / (double) (RESULT_ROWS - 1));
            long r = (long) Math.ceil((CataCalculator.xpFor(lv) - xp) / perRun);
            String time = runSeconds > 0 ? "  §8" + DungeonProfitHour.hours(r * runSeconds) : "";
            levels.add(new String[]{"Catacombs " + lv, "§f" + DungeonProfitHour.whole(r) + " runs" + time});
        }
    }

    /** After runs: the level you'll be after that many runs of the floor, the XP they give and the time they take. */
    private void afterRuns(double perRun, double runSeconds) {
        double runs = Math.floor(number(runsText));
        if (runs <= 0) {
            after.add(new String[]{"§7Type how many runs", ""});
            return;
        }
        if (perRun <= 0) {
            after.add(new String[]{"§cType the XP per run", ""});
            return;
        }
        double gained = runs * perRun;
        double now = CataCalculator.level(xp), then = CataCalculator.level(xp + gained);
        after.add(new String[]{"Catacombs", String.format(Locale.US, "§f%.2f §7→ §a%.2f §8(+%.2f)", now, then, then - now)});
        after.add(new String[]{"XP gained", "§6" + CombatFeatures.formatCoins(gained) + " §8(" + DungeonProfitHour.whole(runs) + " " + floor + " runs)"});
        after.add(new String[]{"Time", runSeconds > 0 ? "§a" + DungeonProfitHour.hours(runs * runSeconds) : "§8Type a run time"});
    }

    private static double number(String text) {
        String t = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double mult = t.endsWith("k") ? 1_000 : t.endsWith("m") ? 1_000_000 : 1;
        if (mult != 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        Sky2MGuiStyle.panel(g, x, y, w, h, true);
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("Catacombs Calculator"));

        // Floor tabs: Entrance and F1-F7, then M1-M7.
        int tabsY = y + 30;
        g.text(font, "Floor", x + 10, tabsY, Sky2MGuiStyle.TEXT_DIM, false);
        g.text(font, "Master", x + 10, tabsY + 16, Sky2MGuiStyle.TEXT_DIM, false);
        int tabsX = x + 56, step = Math.max(24, (w - 66) / 8);
        for (int i = 0; i < FLOORS.length; i++) {
            String f = FLOORS[i];
            boolean master = f.startsWith("M");
            int col = master ? i - 7 : i;
            int tx = tabsX + col * step, ty = tabsY + (master ? 16 : 0);
            int tw = font.width(f);
            boolean hover = mouseX >= tx - 3 && mouseX < tx + tw + 3 && mouseY >= ty - 3 && mouseY < ty + 11;
            Sky2MGuiStyle.category(g, font, f, tx, ty, f.equals(floor), hover);
            hits.add(new Hit(tx - 3, ty - 3, tw + 6, 14, () -> pickFloor(f)));
        }
        g.fill(x + 8, tabsY + 32, x + w - 8, tabsY + 33, Sky2MGuiStyle.LINE);

        // The boxes.
        String[] labels = {"Target level", "XP per run", "Run time"};
        EditBox[] boxes = {targetBox, xpBox, timeBox};
        for (int i = 0; i < 3; i++) {
            g.text(font, labels[i], fieldX(i), fieldY() - 11, Sky2MGuiStyle.TEXT, false);
            Sky2MGuiStyle.field(g, boxes[i]);
        }
        int noteY = fieldY() + Sky2MGuiStyle.SEARCH_HEIGHT + 6;
        String note = perRunSource + (timeText.isBlank() ? "" : (perRunSource.isEmpty() ? "" : "  ·  ") + "Run time includes the gap between runs");
        g.text(font, font.plainSubstrByWidth(note, w - 20), x + 10, noteY, Sky2MGuiStyle.MUTED, false);

        // Results.
        int top = noteY + 16;
        int boxH = 22 + RESULT_ROWS * ROW;
        int half = (w - 16 - 8) / 2;
        results(g, x + 8, top, half, boxH, "To the target", totals);
        results(g, x + 8 + half + 8, top, half, boxH, "Levels on the way", levels);

        // After runs: the Runs box on the left, what they get you on the right.
        int ay = afterY();
        Sky2MGuiStyle.inset(g, x + 8, ay, x + w - 8, ay + afterH());
        Sky2MGuiStyle.heading(g, font, "After runs of " + floor, x + 8, ay + 6, w - 16);
        g.text(font, "Runs", runsBox.getX(), ay + 21, Sky2MGuiStyle.TEXT, false);
        Sky2MGuiStyle.field(g, runsBox);
        int rx0 = runsBox.getX() + 80 + 18, rx1 = x + w - 15, ry = ay + 20;
        for (String[] row : after) {
            g.text(font, Component.literal(row[0]), rx0, ry, Sky2MGuiStyle.TEXT_DIM, false);
            if (!row[1].isEmpty()) {
                Component value = Component.literal(row[1]);
                g.text(font, value, rx1 - font.width(value), ry, Sky2MGuiStyle.TEXT, false);
            }
            ry += ROW;
        }

        // Done.
        int doneW = 80, doneX = x + (w - doneW) / 2, doneY = y + h - 26;
        boolean doneHover = mouseX >= doneX && mouseX < doneX + doneW && mouseY >= doneY && mouseY < doneY + 18;
        Sky2MGuiStyle.button(g, font, "Done", doneX, doneY, doneW, 18, doneHover);
        hits.add(new Hit(doneX, doneY, doneW, 18, this::onClose));

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void results(GuiGraphicsExtractor g, int x, int y, int w, int h, String heading, List<String[]> rows) {
        Sky2MGuiStyle.inset(g, x, y, x + w, y + h);
        Sky2MGuiStyle.heading(g, font, heading, x, y + 6, w);
        int ry = y + 20;
        for (String[] row : rows) {
            g.text(font, Component.literal(row[0]), x + 7, ry, Sky2MGuiStyle.TEXT_DIM, false);
            if (!row[1].isEmpty()) {
                Component value = Component.literal(row[1]);
                g.text(font, value, x + w - 7 - font.width(value), ry, Sky2MGuiStyle.TEXT, false);
            }
            ry += ROW;
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (EditBox box : new EditBox[]{targetBox, xpBox, timeBox, runsBox}) {
                if (Sky2MGuiStyle.inField(box, event.x(), event.y())) {
                    setFocused(box);
                    return super.mouseClicked(event, doubleClick) || true;
                }
            }
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

    /** For the game test: what the panels say, with your XP set instead of loaded. */
    public String debugText(double yourXp, String target, String perRun, String runTime) {
        xp = yourXp;
        targetBox.setValue(target);
        xpBox.setValue(perRun);
        timeBox.setValue(runTime);
        refresh();
        StringBuilder sb = new StringBuilder();
        for (String[] r : totals) sb.append(r[0]).append('=').append(r[1]).append('\n');
        for (String[] r : levels) sb.append(r[0]).append('=').append(r[1]).append('\n');
        return sb.toString();
    }

    /** For the game test: the After runs section for that many runs. */
    public String afterText(double yourXp, String perRun, String runTime, String runs) {
        xp = yourXp;
        xpBox.setValue(perRun);
        timeBox.setValue(runTime);
        runsBox.setValue(runs);
        refresh();
        StringBuilder sb = new StringBuilder();
        for (String[] r : after) sb.append(r[0]).append('=').append(r[1]).append('\n');
        return sb.toString();
    }
}
