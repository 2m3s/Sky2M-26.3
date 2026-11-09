package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
 * /s2 hour: {@link DungeonProfitHour} on one screen, drawn like the /s2 settings ({@link Sky2MGuiStyle}). Pick a floor,
 * and the run time and collection target fill in from your Run History and tab list (type over
 * them to try other numbers); the profit per hour with and without the RNG meter and the time to the boss collection
 * target update as you type. The floor and the boxes are kept when the screen closes, also across restarts.
 */
public class DungeonProfitScreen extends Screen {
    private static final String[] FLOORS = {"F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};
    private static final int ROW = 12;

    private final Screen parent;
    private String floor;
    /** The boxes' text, kept while widgets are rebuilt and saved on close. */
    private String runText = "", targetText = "";
    private boolean fillDefaults;
    private EditBox runBox, targetBox;
    /** What the result boxes show, worked out when the floor or a box changes (it reads the history files). */
    private final List<String[]> profit = new ArrayList<>(), collection = new ArrayList<>();
    private String meterText = "";
    /** Where the tabs and buttons were drawn last frame, for clicks. */
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public DungeonProfitScreen(Screen parent) {
        super(Component.literal("Dungeon Profit"));
        this.parent = parent;
        var saved = TrackLines.config();
        if (saved != null && saved.hourFloor >= 0 && saved.hourFloor < FLOORS.length) {
            floor = FLOORS[(int) saved.hourFloor];
            runText = savedTime(saved.hourRun);
            targetText = saved.hourTarget < 0 ? "" : DungeonProfitHour.whole(saved.hourTarget);
        } else {
            floor = lastFloor();
            fillDefaults = true;
        }
    }

    private static String savedTime(double seconds) {
        return seconds < 0 ? "" : DungeonProfitHour.time(seconds);
    }

    /** The floor of your latest completed run, else F7. */
    private static String lastFloor() {
        JsonArray runs = runs();
        for (int i = runs.size() - 1; i >= 0; i--) {
            JsonObject run = runs.get(i).getAsJsonObject();
            String f = run.has("floor") ? run.get("floor").getAsString().toUpperCase(Locale.ROOT) : "";
            for (String known : FLOORS) if (known.equals(f)) return f;
        }
        return "F7";
    }

    private static JsonArray runs() {
        return DungeonProfitHour.read(DungeonProfitHour.DIR.resolve("sky2m").resolve("run-history.json"));
    }

    // ------------------------------------------------------------------------------------------------ layout

    private int panelW() {
        return Math.min(460, width - 20);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    /** Fixed, so the panel (and the boxes placed in init) never move when the results change length. */
    private static final int RESULT_ROWS = 8;

    private int resultRows() {
        return RESULT_ROWS;
    }

    private int panelH() {
        return 128 + resultRows() * ROW + 22 + 34;
    }

    private int panelY() {
        return Math.max(6, (height - panelH()) / 2);
    }

    private int fieldW() {
        return (panelW() - 16 - 10) / 2;
    }

    private int fieldX(int i) {
        return panelX() + 8 + i * (fieldW() + 10);
    }

    private int fieldY() {
        return panelY() + 82;
    }

    @Override
    protected void init() {
        runBox = box(runText, 0);
        targetBox = box(targetText, 1);
        if (fillDefaults) {
            fillDefaults = false;
            double avg = DungeonProfitHour.averageRunSeconds(floor, runs());
            runBox.setValue(avg > 0 ? DungeonProfitHour.time(avg) : "");
            double next = DungeonProfitHour.nextTierFromTab(TrackLines.tabLines(), boss());
            targetBox.setValue(next > 0 ? DungeonProfitHour.whole(next) : "");
        }
        runBox.setResponder(s -> refresh());
        targetBox.setResponder(s -> refresh());
        refresh();
    }

    private EditBox box(String value, int i) {
        EditBox box = new EditBox(font, 0, 0, 10, 10, Component.empty());
        box.setMaxLength(12);
        Sky2MGuiStyle.placeSearch(box, fieldX(i), fieldY(), fieldW());
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private String boss() {
        return DungeonProfitHour.BOSSES[floor.charAt(1) - '0'];
    }

    private void pickFloor(String f) {
        if (f.equals(floor)) return;
        floor = f;
        fillDefaults = true;
        runText = targetText = "";
        rebuildWidgets();
    }

    /** The next meter item on this floor's list, then none, then the first again. */
    private void nextMeter() {
        DungeonProfitHour.FloorMeter meter = DungeonProfitHour.meterFor(floor);
        if (meter == null || meter.items.isEmpty()) return;
        List<String> names = new ArrayList<>(meter.items.keySet());
        int at = meter.selected == null ? -1 : names.indexOf(meter.selected);
        meter.selected = at + 1 < names.size() ? names.get(at + 1) : null;
        meter.progress = 0;
        DungeonProfitHour.save();
        refresh();
    }

    // ------------------------------------------------------------------------------------------------ numbers

    private void refresh() {
        runText = runBox.getValue();
        targetText = targetBox.getValue();
        profit.clear();
        collection.clear();
        double run = DungeonProfitHour.seconds(runText);
        DungeonProfitHour.History history = DungeonProfitHour.history(floor);

        DungeonProfitHour.FloorMeter meter = DungeonProfitHour.meterFor(floor);
        DungeonProfitHour.MeterItem item = meter == null || meter.selected == null ? null : meter.items.get(meter.selected);
        double price = item == null || item.id == null ? 0 : Sky2MPriceTooltip.unitPrice(item.id);
        double score = history.score() > 0 ? history.score() : 300;
        if (meter == null || meter.items.isEmpty()) meterText = "RNG Meter: none known yet (open the floor's RNG Meter menu)";
        else meterText = "RNG Meter: " + (item == null ? "none - click to pick" : item.name + " - click to change");

        if (run <= 0) {
            profit.add(new String[]{"§cType a run time, like 1:20", ""});
        } else {
            var e = DungeonProfitHour.estimate(run, 0, history.chestPerRun(), price, item == null ? 0 : item.required, meter == null ? 0 : meter.progress, score);
            profit.add(new String[]{"Runs per hour", String.format(Locale.US, "§f%.1f", e.runsPerHour())});
            profit.add(new String[]{"Chest profit / run", history.runs() == 0 ? "§cno chests saved" : "§6" + DungeonProfitHour.coins(e.chestPerRun())});
            if (history.runs() > 0) profit.add(new String[]{"§8  from " + history.runs() + " runs", "§8" + DungeonProfitHour.coins(history.chestTotal())});
            if (item != null) {
                profit.add(new String[]{"Meter / run", price > 0 ? "§6" + DungeonProfitHour.coins(e.meterPerRun()) : "§cno price"});
                profit.add(new String[]{"§8  " + DungeonProfitHour.whole(item.required) + " score", "§8" + e.runsToFill() + " runs to fill"});
            }
            profit.add(new String[]{"Without meter", "§e" + DungeonProfitHour.coins(e.withoutMeter()) + "/h"});
            if (item != null && price > 0) profit.add(new String[]{"With meter", "§a" + DungeonProfitHour.coins(e.withMeter()) + "/h"});
        }

        String boss = boss();
        double current = DungeonProfitHour.collectionFromTab(TrackLines.tabLines(), boss);
        double target = targetText.isBlank() ? -1 : number(targetText);
        collection.add(new String[]{"Boss", "§f" + boss});
        if (current < 0) {
            collection.add(new String[]{"§cNot in your tab list", ""});
            collection.add(new String[]{"§8Show Hypixel's Collection", ""});
            collection.add(new String[]{"§8widget in your tab", ""});
        } else {
            collection.add(new String[]{"Now", "§f" + DungeonProfitHour.whole(current)});
            if (target <= 0) collection.add(new String[]{"§7Type a target", ""});
            else if (current >= target) collection.add(new String[]{"§aAlready past " + DungeonProfitHour.whole(target), ""});
            else {
                int perRun = DungeonProfitHour.collectionPerRun(floor);
                int runsNeeded = (int) Math.ceil((target - current) / perRun);
                collection.add(new String[]{"Target", "§f" + DungeonProfitHour.whole(target)});
                collection.add(new String[]{"Runs needed", "§f" + DungeonProfitHour.whole(runsNeeded)});
                collection.add(new String[]{"§8  " + perRun + " a run", ""});
                if (run > 0) collection.add(new String[]{"Time", "§a" + DungeonProfitHour.hours(runsNeeded * run)});
            }
        }
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
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("Dungeon Profit"));

        // Floor tabs, like the settings' categories.
        int tabsY = y + 30;
        g.text(font, "Floor", x + 10, tabsY, Sky2MGuiStyle.TEXT_DIM, false);
        g.text(font, "Master", x + 10, tabsY + 16, Sky2MGuiStyle.TEXT_DIM, false);
        int tabsX = x + 56, step = Math.max(24, (w - 66) / 7);
        for (int i = 0; i < FLOORS.length; i++) {
            String f = FLOORS[i];
            int tx = tabsX + (i % 7) * step, ty = tabsY + (i / 7) * 16;
            int tw = font.width(f);
            boolean hover = mouseX >= tx - 3 && mouseX < tx + tw + 3 && mouseY >= ty - 3 && mouseY < ty + 11;
            Sky2MGuiStyle.category(g, font, f, tx, ty, f.equals(floor), hover);
            hits.add(new Hit(tx - 3, ty - 3, tw + 6, 14, () -> pickFloor(f)));
        }
        g.fill(x + 8, tabsY + 32, x + w - 8, tabsY + 33, Sky2MGuiStyle.LINE);

        // The boxes.
        String[] labels = {"Run time", boss() + " target"};
        EditBox[] boxes = {runBox, targetBox};
        for (int i = 0; i < boxes.length; i++) {
            g.text(font, labels[i], fieldX(i), fieldY() - 11, Sky2MGuiStyle.TEXT, false);
            Sky2MGuiStyle.field(g, boxes[i]);
        }

        // RNG meter: a row like the settings' options, clicked to cycle the item.
        int meterY = fieldY() + Sky2MGuiStyle.SEARCH_HEIGHT + 8;
        boolean meterHover = mouseX >= x + 8 && mouseX < x + w - 8 && mouseY >= meterY && mouseY < meterY + 18;
        g.fill(x + 8, meterY, x + w - 8, meterY + 18, meterHover ? Sky2MGuiStyle.ROW_HOVER : Sky2MGuiStyle.ROW);
        g.text(font, "RNG Meter", x + 14, meterY + 5, Sky2MGuiStyle.TEXT, false);
        String meterValue = font.plainSubstrByWidth(meterText.substring("RNG Meter: ".length()) + "  ▶", w - 100);
        g.text(font, meterValue, x + w - 14 - font.width(meterValue), meterY + 5, meterHover ? Sky2MGuiStyle.SELECTED : Sky2MGuiStyle.TEXT_DIM, false);
        hits.add(new Hit(x + 8, meterY, w - 16, 18, this::nextMeter));

        // Results.
        int top = meterY + 28;
        int boxH = 22 + resultRows() * ROW;
        int half = (w - 16 - 8) / 2;
        results(g, x + 8, top, half, boxH, "Profit per hour", profit);
        results(g, x + 8 + half + 8, top, half, boxH, "Boss collection", collection);

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
            for (EditBox box : new EditBox[]{runBox, targetBox}) {
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
        save();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    /** Keeps the floor and the boxes for next time. */
    private void save() {
        var saved = TrackLines.config();
        if (saved == null) return;
        for (int i = 0; i < FLOORS.length; i++) if (FLOORS[i].equals(floor)) saved.hourFloor = i;
        double run = DungeonProfitHour.seconds(runText), target = number(targetText);
        saved.hourRun = runText.isBlank() || run < 0 ? -1 : run;
        saved.hourTarget = targetText.isBlank() || target < 0 ? -1 : target;
        com.epic60869.sky2m.config.Sky2MConfig.saveCurrent(com.epic60869.sky2m.config.Sky2MConfig.current());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** For the game test: the result boxes' text. */
    public String debugText() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : profit) sb.append(r[0]).append('=').append(r[1]).append('\n');
        for (String[] r : collection) sb.append(r[0]).append('=').append(r[1]).append('\n');
        return sb.toString();
    }

    public void type(String run, String target) {
        runBox.setValue(run);
        targetBox.setValue(target);
        refresh();
    }
}
