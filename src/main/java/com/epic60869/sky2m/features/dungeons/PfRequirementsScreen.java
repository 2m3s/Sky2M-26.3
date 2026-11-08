package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
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

/**
 * /s2 autokick (also /s2 pfreq and the Party Finder settings' Requirements button): the auto-kick requirements, drawn
 * like /s2 hour ({@link Sky2MGuiStyle}). Tabs for the Default and each floor; for the one picked, boxes for the
 * slowest S+ (typed like 1:25), Catacombs, secrets (15k) and magical power, empty = don't check. Typing in a floor's
 * boxes gives it its own requirements; the row above them switches back to the Default's. Saved on close.
 */
public final class PfRequirementsScreen extends Screen {
    private static final int ROW = 12;
    private static final int SUMMARY_ROWS = 5;
    private static final String[] LABELS = {"Slowest S+", "Min Catacombs", "Min Secrets", "Min Magical Power"};
    private static final String[] HINTS = {"1:25", "40", "15k", "1200"};

    private final Screen parent;
    private String tab;
    private final EditBox[] boxes = new EditBox[4];
    /** Set while init fills the boxes, so it doesn't count as typing. */
    private boolean filling;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public PfRequirementsScreen(Screen parent, String tab) {
        super(Component.literal("Auto Kick"));
        this.parent = parent;
        this.tab = tab != null ? tab : startTab();
    }

    /** The floor you're queued for or in, else the Default. */
    private static String startTab() {
        String floor = PartyFinderStats.currentFloorKey();
        return floor != null && PfRequirements.FLOORS.contains(floor) ? floor : PfRequirements.DEFAULT;
    }

    private PfRequirements.Req req() {
        return PfRequirements.all().get(tab);
    }

    private boolean isDefault() {
        return PfRequirements.DEFAULT.equals(tab);
    }

    // ------------------------------------------------------------------------------------------------ layout

    private int panelW() { return Math.min(460, width - 20); }
    private int panelX() { return (width - panelW()) / 2; }
    /** Header, tabs, the two option rows, two rows of boxes, the summaries and Done. */
    private int panelH() { return 30 + 56 + 117 + 22 + SUMMARY_ROWS * ROW + 34; }
    private int panelY() { return Math.max(6, (height - panelH()) / 2); }
    private int tabsY() { return panelY() + 30; }
    private int optionsY() { return tabsY() + 56; }
    private int fieldW() { return (panelW() - 16 - 10) / 2; }
    private int fieldX(int i) { return panelX() + 8 + (i % 2) * (fieldW() + 10); }
    private int fieldY(int i) { return optionsY() + 46 + 11 + (i / 2) * 34; }

    @Override
    protected void init() {
        PfRequirements.Req req = req();
        String[] values = {PfRequirements.time(req.maxPbSeconds), req.minCata > 0 ? Integer.toString(req.minCata) : "",
            PfRequirements.number(req.minSecrets), req.minMagicalPower > 0 ? Integer.toString(req.minMagicalPower) : ""};
        filling = true;
        for (int i = 0; i < boxes.length; i++) {
            EditBox box = new EditBox(font, 0, 0, 10, 10, Component.literal(LABELS[i]));
            box.setMaxLength(10);
            Sky2MGuiStyle.placeSearch(box, fieldX(i), fieldY(i), fieldW());
            box.setValue(values[i]);
            box.setHint(Component.literal(HINTS[i]).withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            box.setResponder(s -> typed());
            addRenderableWidget(box);
            boxes[i] = box;
        }
        typed();
        filling = false;
    }

    private void pick(String t) {
        if (t.equals(tab)) return;
        tab = t;
        rebuildWidgets();
    }

    /** Copies the boxes into the requirements (a bad box keeps its old number and goes red). */
    private void typed() {
        PfRequirements.Req req = req();
        int pb = PfRequirements.parseTime(boxes[0].getValue());
        long cata = PfRequirements.parseNumber(boxes[1].getValue());
        long secrets = PfRequirements.parseNumber(boxes[2].getValue());
        long mp = PfRequirements.parseNumber(boxes[3].getValue());
        long[] parsed = {pb, cata, secrets, mp};
        for (int i = 0; i < boxes.length; i++) boxes[i].setTextColor(parsed[i] < 0 ? 0xFFFF5555 : 0xFFE0E0E0);
        if (pb >= 0) req.maxPbSeconds = pb;
        if (cata >= 0) req.minCata = (int) Math.min(cata, 100);
        if (secrets >= 0) req.minSecrets = secrets;
        if (mp >= 0) req.minMagicalPower = (int) Math.min(mp, 100_000);
        // Typing in a floor's boxes gives it its own requirements.
        if (!filling && !isDefault()) req.enabled = true;
    }

    private void toggleAutoKick() {
        var c = PartyFinderStatsConfig.get();
        if (c == null) return;
        c.autoKick = !c.autoKick;
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
    }

    /** The Default: the next floor whose S+ it checks. A floor: own requirements on or off. */
    private void optionClicked() {
        PfRequirements.Req req = req();
        if (isDefault()) {
            int at = PfRequirements.FLOORS.indexOf(req.pbFloor);
            req.pbFloor = PfRequirements.FLOORS.get((at + 1) % PfRequirements.FLOORS.size());
        } else {
            req.enabled = !req.enabled;
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        Sky2MGuiStyle.panel(g, x, y, w, h, true);
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("Auto Kick"));

        // Tabs, like /s2 hour's floors: the Default on its own row, then F1-F7 and M1-M7.
        int tabsY = tabsY(), tabsX = x + 56, step = Math.max(24, (w - 66) / 7);
        String[] rowNames = {"Default", "Floor", "Master"};
        for (int r = 0; r < 3; r++) g.text(font, rowNames[r], x + 10, tabsY + r * 16, Sky2MGuiStyle.TEXT_DIM, false);
        tabButton(g, PfRequirements.DEFAULT, "All floors", tabsX, tabsY, mouseX, mouseY);
        for (int i = 0; i < PfRequirements.FLOORS.size(); i++) {
            String f = PfRequirements.FLOORS.get(i);
            boolean own = PfRequirements.all().get(f).enabled;
            tabButton(g, f, own ? f + "*" : f, tabsX + (i % 7) * step, tabsY + 16 + (i / 7) * 16, mouseX, mouseY);
        }
        g.fill(x + 8, tabsY + 48, x + w - 8, tabsY + 49, Sky2MGuiStyle.LINE);

        // Option rows, like the settings' options: Auto Kick itself, then this tab's choice.
        var config = PartyFinderStatsConfig.get();
        boolean on = config != null && config.autoKick;
        int oy = optionsY();
        option(g, oy, "Auto Kick", on ? "§aOn" : "§cOff", mouseX, mouseY, this::toggleAutoKick);
        PfRequirements.Req req = req();
        if (isDefault()) option(g, oy + 20, "S+ checked on", "§f" + req.pbFloor, mouseX, mouseY, this::optionClicked);
        else option(g, oy + 20, tab + " uses", req.enabled ? "§fIts own requirements" : "§7The Default's", mouseX, mouseY, this::optionClicked);

        // The boxes, dimmed while a floor uses the Default's.
        boolean used = isDefault() || req.enabled;
        for (int i = 0; i < boxes.length; i++) {
            String label = LABELS[i] + (i == 0 ? " (on " + (isDefault() ? req.pbFloor : tab) + ")" : "");
            g.text(font, label, fieldX(i), fieldY(i) - 11, used ? Sky2MGuiStyle.TEXT : Sky2MGuiStyle.MUTED, false);
            Sky2MGuiStyle.field(g, boxes[i]);
        }

        // What's checked here, and every floor that has its own.
        int top = fieldY(2) + Sky2MGuiStyle.SEARCH_HEIGHT + 10;
        int boxH = 22 + SUMMARY_ROWS * ROW, half = (w - 16 - 8) / 2;
        List<String> here = new ArrayList<>();
        PfRequirements.Choice choice = PfRequirements.forKick(isDefault() ? null : tab);
        if (!isDefault() && !choice.own()) here.add("§7Uses the Default's:");
        List<String> checks = PfRequirements.describe(choice.req(), choice.floor());
        if (checks.isEmpty()) here.add("§7Nothing: no one is kicked");
        else for (String line : checks) here.add("§f" + line);
        if (choice.req().minMagicalPower > 0 && here.size() < SUMMARY_ROWS) here.add("§8MP skipped when their API is off");
        String heading = isDefault() ? "Kicks (floors without their own)" : "Kicks on " + tab;
        summary(g, x + 8, top, half, boxH, heading, here);
        List<String> floors = new ArrayList<>();
        for (String f : PfRequirements.FLOORS) {
            PfRequirements.Req r = PfRequirements.all().get(f);
            if (r.enabled) floors.add("§f" + f + " §7" + PfRequirements.brief(r));
        }
        if (floors.isEmpty()) floors.add("§7None: all use the Default");
        if (floors.size() > SUMMARY_ROWS) {
            int more = floors.size() - SUMMARY_ROWS + 1;
            floors = new ArrayList<>(floors.subList(0, SUMMARY_ROWS - 1));
            floors.add("§8+" + more + " more");
        }
        summary(g, x + 8 + half + 8, top, half, boxH, "Floors with their own (*)", floors);

        // Done.
        int doneW = 80, doneX = x + (w - doneW) / 2, doneY = y + h - 26;
        boolean doneHover = mouseX >= doneX && mouseX < doneX + doneW && mouseY >= doneY && mouseY < doneY + 18;
        Sky2MGuiStyle.button(g, font, "Done", doneX, doneY, doneW, 18, doneHover);
        hits.add(new Hit(doneX, doneY, doneW, 18, this::onClose));

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void tabButton(GuiGraphicsExtractor g, String id, String text, int tx, int ty, int mouseX, int mouseY) {
        int tw = font.width(text);
        boolean hover = mouseX >= tx - 3 && mouseX < tx + tw + 3 && mouseY >= ty - 3 && mouseY < ty + 11;
        Sky2MGuiStyle.category(g, font, text, tx, ty, id.equals(tab), hover);
        hits.add(new Hit(tx - 3, ty - 3, tw + 6, 14, () -> pick(id)));
    }

    private void option(GuiGraphicsExtractor g, int oy, String name, String value, int mouseX, int mouseY, Runnable action) {
        int x = panelX(), w = panelW();
        boolean hover = mouseX >= x + 8 && mouseX < x + w - 8 && mouseY >= oy && mouseY < oy + 18;
        g.fill(x + 8, oy, x + w - 8, oy + 18, hover ? Sky2MGuiStyle.ROW_HOVER : Sky2MGuiStyle.ROW);
        g.text(font, name, x + 14, oy + 5, Sky2MGuiStyle.TEXT, false);
        Component v = Component.literal(value + "  §7▶");
        g.text(font, v, x + w - 14 - font.width(v), oy + 5, Sky2MGuiStyle.TEXT, false);
        hits.add(new Hit(x + 8, oy, w - 16, 18, action));
    }

    private void summary(GuiGraphicsExtractor g, int x, int y, int w, int h, String heading, List<String> rows) {
        Sky2MGuiStyle.inset(g, x, y, x + w, y + h);
        Sky2MGuiStyle.heading(g, font, heading, x, y + 6, w);
        int ry = y + 20;
        for (String row : rows) {
            g.text(font, Component.literal(font.plainSubstrByWidth(row, w - 14)), x + 7, ry, Sky2MGuiStyle.TEXT_DIM, false);
            ry += ROW;
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (EditBox box : boxes) {
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
        PfRequirements.save();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------ game test

    /** For the game test: picks a tab and types into its boxes (null leaves a box as it is). */
    public void type(String tab, String pb, String cata, String secrets, String mp) {
        pick(tab);
        String[] values = {pb, cata, secrets, mp};
        for (int i = 0; i < boxes.length; i++) if (values[i] != null) boxes[i].setValue(values[i]);
    }

    /** For the game test: clicks the tab's option row (the Default's S+ floor, or a floor's own on/off). */
    public void clickOption() {
        optionClicked();
    }

    /** For the game test: what the "Kicks" box says. */
    public String debugText() {
        PfRequirements.Choice choice = PfRequirements.forKick(isDefault() ? null : tab);
        return (choice.own() ? "own " : "default ") + choice.floor() + ": " + String.join("; ", PfRequirements.describe(choice.req(), choice.floor()));
    }

    /** The Party Finder Stats settings. */
    private static final class PartyFinderStatsConfig {
        static com.epic60869.sky2m.features.FeatureConfigs.PartyFinderStats get() {
            Sky2MConfig c = Sky2MConfig.current();
            return c == null ? null : c.dungeons.partyFinderStats;
        }
    }
}
