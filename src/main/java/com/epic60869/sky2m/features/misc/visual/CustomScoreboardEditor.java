package com.epic60869.sky2m.features.misc.visual;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.gui.Sky2MHudEditorScreen;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.Align;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.Element;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.Event;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The Custom Scoreboard editor, like the Custom Scoreboard mod's config screen: tabs for the Lines and Events (dragged
 * into order, added and removed), Position, Title and Footer, Background and Line Options, with the scoreboard drawn
 * live beside them as you change things. Opened from the Custom Scoreboard settings or /s2 scoreboard. Everything here
 * is also in the normal settings; this is just the easier way to set it up.
 *
 * <p>Each settings tab is a list of rows; the same list places the text boxes and draws the rest, so they always line
 * up, and a tab taller than the screen scrolls.
 */
public final class CustomScoreboardEditor extends Screen {
    private static final int PANEL = 0xC0121722, EDGE = 0xFF3B465B, GOLD = 0xFFFFD35A, TEXT = 0xFF9AA5B8, BUTTON = 0xFF2A3345,
        BUTTON_HOVER = 0xFF3B5B8B, ON = 0xFF2E5A3A, ROW = 16, TOP = 30;

    private enum Tab {
        LINES("Lines"), EVENTS("Events"), POSITION("Position"), TITLE("Title"), BACKGROUND("Background"), COLOURS("Colours"), OPTIONS("Options");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static Tab tab = Tab.LINES;

    /** One row of a settings tab: how tall it is, and how to draw it at (x, y) with width w. */
    private record Row(int height, RowDrawer draw, EditBox box) {}

    @FunctionalInterface
    private interface RowDrawer {
        void draw(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY);
    }

    private final Screen parent;
    private final List<int[]> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int dragging = -1, dragY;
    private int listScroll, rowScroll;
    private boolean adding;
    private int[] listArea;

    public CustomScoreboardEditor(Screen parent) {
        super(Component.literal("Custom Scoreboard Editor"));
        this.parent = parent;
    }

    private static FeatureConfigs.CustomScoreboard c() {
        return CustomScoreboard.config();
    }

    private void save() {
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null) Sky2MConfig.saveCurrent(config);
        CustomScoreboard.refresh();
    }

    private int left() {
        return Math.max(10, width / 2 - 270);
    }

    private int panelW() {
        return Math.min(320, width / 2 - 20);
    }

    private int panelTop() {
        return TOP + 20;
    }

    private int panelBottom() {
        return height - 30;
    }

    @Override
    protected void init() {
        clearWidgets();
        rows.clear();
        rowScroll = 0;
        FeatureConfigs.CustomScoreboard c = c();
        if (c == null) return;
        switch (tab) {
            case POSITION -> positionRows(c);
            case TITLE -> titleRows(c);
            case BACKGROUND -> backgroundRows(c);
            case OPTIONS -> optionRows(c);
            case COLOURS -> colourRows(c);
            default -> {}
        }
        for (Row row : rows) if (row.box() != null) addRenderableWidget(row.box());
    }

    // ------------------------------------------------------------------------------------------------ rows

    private void toggleRow(String label, BooleanSupplier get, Runnable flip) {
        rows.add(new Row(18, (g, x, y, w, mx, my) -> {
            g.text(font, label, x, y + 4, 0xFFFFFFFF, true);
            boolean v = get.getAsBoolean();
            button(g, x + w - 44, y, 44, 14, v ? "On" : "Off", v, mx, my, () -> {
                flip.run();
                save();
            });
        }, null));
    }

    private void stepperRow(String label, IntSupplier get, int min, int max, int step, Consumer<Integer> set) {
        rows.add(new Row(18, (g, x, y, w, mx, my) -> {
            g.text(font, label, x, y + 4, 0xFFFFFFFF, true);
            button(g, x + w - 70, y, 16, 14, "-", false, mx, my, () -> {
                set.accept(Math.max(min, get.getAsInt() - step));
                save();
            });
            g.centeredText(font, String.valueOf(get.getAsInt()), x + w - 35, y + 3, GOLD);
            button(g, x + w - 16, y, 16, 14, "+", false, mx, my, () -> {
                set.accept(Math.min(max, get.getAsInt() + step));
                save();
            });
        }, null));
    }

    private <E extends Enum<E>> void cycleRow(String label, Supplier<E> get, E[] values, Consumer<E> set) {
        rows.add(new Row(18, (g, x, y, w, mx, my) -> {
            g.text(font, label, x, y + 4, 0xFFFFFFFF, true);
            button(g, x + w - 120, y, 120, 14, SbcItems.parseLegacy(get.get().toString()).getString(), false, mx, my, () -> {
                set.accept(values[(get.get().ordinal() + 1) % values.length]);
                save();
            });
        }, null));
    }

    private void labelRow(String text, int colour) {
        rows.add(new Row(12, (g, x, y, w, mx, my) -> g.text(font, text, x, y + 2, colour, true), null));
    }

    private void headerRow(String text) {
        rows.add(new Row(16, (g, x, y, w, mx, my) -> {
            g.text(font, text, x, y + 5, GOLD, true);
            g.fill(x, y + 14, x + w, y + 15, EDGE);
        }, null));
    }

    private void textRow(String label, String value, Consumer<String> set) {
        EditBox box = new EditBox(font, 0, 0, 100, 16, Component.empty());
        box.setMaxLength(256);
        box.setValue(value == null ? "" : value);
        box.setResponder(v -> {
            set.accept(v);
            save();
        });
        rows.add(new Row(20, (g, x, y, w, mx, my) -> g.text(font, label, x, y + 5, 0xFFFFFFFF, true), box));
    }

    private void buttonRow(String label, Runnable action) {
        rows.add(new Row(18, (g, x, y, w, mx, my) -> button(g, x, y, Math.min(w, 160), 14, label, false, mx, my, action), null));
    }

    private void positionRows(FeatureConfigs.CustomScoreboard c) {
        headerRow("Where it sits");
        cycleRow("Side to Side", () -> c.horizontal, FeatureConfigs.CustomScoreboard.HorizontalPosition.values(), v -> c.horizontal = v);
        cycleRow("Up and Down", () -> c.vertical, FeatureConfigs.CustomScoreboard.VerticalPosition.values(), v -> c.vertical = v);
        stepperRow("Margin", () -> c.background.margin, 0, 30, 1, v -> c.background.margin = v);
        stepperRow("Scale (%)", () -> Math.round(c.scale * 100), 50, 200, 5, v -> c.scale = v / 100f);
        buttonRow("Move in /s2 gui (Free)", () -> minecraft.gui.setScreen(new Sky2MHudEditorScreen(this)));
        headerRow("Text");
        cycleRow("Text Alignment", () -> c.lineAlignment, Align.values(), v -> c.lineAlignment = v);
        stepperRow("Line Spacing", () -> c.lineSpacing, 0, 6, 1, v -> c.lineSpacing = v);
        toggleRow("Text Shadow", () -> c.textShadow, () -> c.textShadow = !c.textShadow);
        headerRow("When it shows");
        toggleRow("Hide Hypixel's Sidebar", () -> c.hideHypixel, () -> c.hideHypixel = !c.hideHypixel);
        toggleRow("Custom Lines", () -> c.customLines, () -> c.customLines = !c.customLines);
        toggleRow("Outside SkyBlock", () -> c.outsideSkyblock, () -> c.outsideSkyblock = !c.outsideSkyblock);
        toggleRow("Hide While Tab Is Held", () -> c.hideWhenTab, () -> c.hideWhenTab = !c.hideWhenTab);
        toggleRow("Hide While Chat Is Open", () -> c.hideWhenChat, () -> c.hideWhenChat = !c.hideWhenChat);
        toggleRow("Hide With F3", () -> c.hideWhenF3, () -> c.hideWhenF3 = !c.hideWhenF3);
        toggleRow("Click and Hover Actions", () -> c.actions, () -> c.actions = !c.actions);
    }

    private void titleRows(FeatureConfigs.CustomScoreboard c) {
        FeatureConfigs.ScoreboardTitle t = c.title;
        headerRow("Title");
        toggleRow("Hypixel's Title", () -> t.hypixelTitle, () -> t.hypixelTitle = !t.hypixelTitle);
        cycleRow("Title Alignment", () -> t.titleAlignment, Align.values(), v -> t.titleAlignment = v);
        textRow("Your Title", t.titleText, v -> t.titleText = v);
        headerRow("Footer");
        toggleRow("Hypixel's Footer", () -> t.hypixelFooter, () -> t.hypixelFooter = !t.hypixelFooter);
        cycleRow("Footer Alignment", () -> t.footerAlignment, Align.values(), v -> t.footerAlignment = v);
        textRow("Your Footer", t.footerText, v -> t.footerText = v);
        labelRow("Your text is used when Hypixel's is off.", TEXT);
        labelRow("& colours (&6&l), \\n for a new line.", TEXT);
    }

    private void backgroundRows(FeatureConfigs.CustomScoreboard c) {
        FeatureConfigs.ScoreboardBackground b = c.background;
        headerRow("Box");
        toggleRow("Background", () -> b.enabled, () -> b.enabled = !b.enabled);
        textRow("Colour", b.colour, v -> b.colour = v);
        labelRow("chroma:alpha:red:green:blue, e.g. 0:85:0:0:0", TEXT);
        stepperRow("Padding", () -> b.padding, 0, 15, 1, v -> b.padding = v);
        stepperRow("Rounded Corners", () -> b.radius, 0, 12, 1, v -> b.radius = v);
        stepperRow("Min Width", () -> b.minWidth, 0, 250, 5, v -> b.minWidth = v);
        stepperRow("Min Height", () -> b.minHeight, 0, 250, 5, v -> b.minHeight = v);
        headerRow("Border");
        toggleRow("Border", () -> b.outline, () -> b.outline = !b.outline);
        stepperRow("Border Size", () -> b.outlineWidth, 1, 6, 1, v -> b.outlineWidth = v);
        textRow("Top Left", b.outlineTop, v -> b.outlineTop = v);
        textRow("Top Right", b.outlineTopRight, v -> b.outlineTopRight = v);
        textRow("Bottom Left", b.outlineBottom, v -> b.outlineBottom = v);
        textRow("Bottom Right", b.outlineBottomRight, v -> b.outlineBottomRight = v);
        headerRow("Picture");
        textRow("File", b.image, v -> b.image = v);
        labelRow("A PNG or GIF in config/sky2m/scoreboard.", TEXT);
        buttonRow("Open Picture Folder", CustomScoreboard::openFolder);
        stepperRow("Picture Opacity", () -> b.imageOpacity, 0, 100, 5, v -> b.imageOpacity = v);
    }

    /** Minecraft's 16 colours, which a line's colour button steps through (any colour can be picked in All Settings). */
    private static final int[] PALETTE = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private static String chroma(int rgb) {
        return "0:255:" + (rgb >> 16 & 0xFF) + ":" + (rgb >> 8 & 0xFF) + ":" + (rgb & 0xFF);
    }

    private static int rgb(String chroma) {
        return com.epic60869.sky2m.custom.util.ChromaColours.parse(chroma).getEffectiveColourRGB() & 0xFFFFFF;
    }

    /** The next of Minecraft's 16 colours after this one (the first, if it's a custom colour). */
    private static String nextColour(String chroma) {
        int now = rgb(chroma);
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == now) return chroma(PALETTE[(i + 1) % PALETTE.length]);
        return chroma(PALETTE[0]);
    }

    /** A line's colour (a swatch you click to step through the colours) and its name (a text box). */
    private void colourRow(String label, Supplier<String> get, Consumer<String> set, Supplier<String> getName, Consumer<String> setName) {
        EditBox box = null;
        if (getName != null) {
            box = new EditBox(font, 0, 0, 100, 16, Component.empty());
            box.setMaxLength(64);
            box.setValue(getName.get() == null ? "" : getName.get());
            box.setResponder(v -> {
                setName.accept(v);
                save();
            });
        }
        rows.add(new Row(20, (g, x, y, w, mx, my) -> {
            g.text(font, label, x, y + 5, 0xFF000000 | rgb(get.get()), true);
            int sx = x + 70;
            boolean over = mx >= sx && mx < sx + 16 && my >= y + 2 && my < y + 18;
            g.fill(sx, y + 2, sx + 16, y + 18, over ? 0xFFFFFFFF : EDGE);
            g.fill(sx + 1, y + 3, sx + 15, y + 17, 0xFF000000 | rgb(get.get()));
            buttons.add(new int[]{sx, y + 2, 16, 16});
            actions.add(() -> {
                set.accept(nextColour(get.get()));
                save();
            });
        }, box));
    }

    private void colourRows(FeatureConfigs.CustomScoreboard c) {
        FeatureConfigs.ScoreboardColours k = c.colours;
        headerRow("Colours and names");
        labelRow("Click a swatch to change its colour; the box is its name.", TEXT);
        labelRow("Any colour (and chroma): All Settings > Colours and Labels.", TEXT);
        colourRow("Name Colour", () -> k.label, v -> k.label = v, null, null);
        colourRow("Purse", () -> k.purse, v -> k.purse = v, () -> k.purseLabel, v -> k.purseLabel = v);
        colourRow("Piggy", () -> k.piggy, v -> k.piggy = v, () -> k.piggyLabel, v -> k.piggyLabel = v);
        colourRow("Bank", () -> k.bank, v -> k.bank = v, () -> k.bankLabel, v -> k.bankLabel = v);
        colourRow("Motes", () -> k.motes, v -> k.motes = v, () -> k.motesLabel, v -> k.motesLabel = v);
        colourRow("Bits", () -> k.bits, v -> k.bits = v, () -> k.bitsLabel, v -> k.bitsLabel = v);
        colourRow("Copper", () -> k.copper, v -> k.copper = v, () -> k.copperLabel, v -> k.copperLabel = v);
        colourRow("Sowdust", () -> k.sowdust, v -> k.sowdust = v, () -> k.sowdustLabel, v -> k.sowdustLabel = v);
        colourRow("Gems", () -> k.gems, v -> k.gems = v, () -> k.gemsLabel, v -> k.gemsLabel = v);
        colourRow("Heat", () -> k.heat, v -> k.heat = v, () -> k.heatLabel, v -> k.heatLabel = v);
        colourRow("Cold", () -> k.cold, v -> k.cold = v, () -> k.coldLabel, v -> k.coldLabel = v);
        colourRow("North Stars", () -> k.northStars, v -> k.northStars = v, () -> k.northStarsLabel, v -> k.northStarsLabel = v);
        colourRow("Soulflow", () -> k.soulflow, v -> k.soulflow = v, () -> k.soulflowLabel, v -> k.soulflowLabel = v);
        colourRow("Players", () -> k.players, v -> k.players = v, () -> k.playersLabel, v -> k.playersLabel = v);
        buttonRow("Reset Colours and Names", () -> {
            c.colours = new FeatureConfigs.ScoreboardColours();
            save();
            init();
        });
    }

    private void optionRows(FeatureConfigs.CustomScoreboard c) {
        FeatureConfigs.ScoreboardLines l = c.lineOptions;
        headerRow("Numbers");
        cycleRow("Number Format", () -> l.numberFormat, FeatureConfigs.CustomScoreboard.NumberFormat.values(), v -> l.numberFormat = v);
        cycleRow("Number Display", () -> l.numberDisplay, FeatureConfigs.CustomScoreboard.NumberDisplay.values(), v -> l.numberDisplay = v);
        toggleRow("Show Changes (+12k)", () -> l.showChanges, () -> l.showChanges = !l.showChanges);
        toggleRow("Show Active Only", () -> l.hideEmpty, () -> l.hideEmpty = !l.hideEmpty);
        toggleRow("Show Piggy", () -> l.showPiggy, () -> l.showPiggy = !l.showPiggy);
        toggleRow("Hide Purse in Dungeons", () -> l.hidePurseInDungeons, () -> l.hidePurseInDungeons = !l.hidePurseInDungeons);
        headerRow("Time and Place");
        toggleRow("24-Hour Time", () -> l.time24h, () -> l.time24h = !l.time24h);
        toggleRow("Indent Location and Time", () -> l.indentLocationTime, () -> l.indentLocationTime = !l.indentLocationTime);
        headerRow("Separators");
        toggleRow("Condense Separators", () -> l.condenseSeparators, () -> l.condenseSeparators = !l.condenseSeparators);
        toggleRow("Hide at Top and Bottom", () -> l.hideSeparatorsAtEnds, () -> l.hideSeparatorsAtEnds = !l.hideSeparatorsAtEnds);
        headerRow("Events");
        toggleRow("Show All Active Events", () -> l.showAllEvents, () -> l.showAllEvents = !l.showAllEvents);
        toggleRow("Separator Between Events", () -> l.separatorBetweenEvents, () -> l.separatorBetweenEvents = !l.separatorBetweenEvents);
        toggleRow("Hide Tab Events in Dungeons", () -> l.tabEventsInDungeon, () -> l.tabEventsInDungeon = !l.tabEventsInDungeon);
        headerRow("Party and Powder");
        stepperRow("Max Party Members", () -> l.maxParty, 1, 25, 1, v -> l.maxParty = v);
        toggleRow("Party in Dungeons", () -> l.partyInDungeons, () -> l.partyInDungeons = !l.partyInDungeons);
        toggleRow("Powder Only While Mining", () -> l.powderOnlyMining, () -> l.powderOnlyMining = !l.powderOnlyMining);
    }

    // ------------------------------------------------------------------------------------------------ widgets

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean on, int mouseX, int mouseY, Runnable action) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, over ? BUTTON_HOVER : on ? ON : BUTTON);
        g.outline(x, y, w, h, EDGE);
        g.centeredText(font, font.plainSubstrByWidth(label, w - 4), x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        buttons.add(new int[]{x, y, w, h});
        actions.add(action);
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        buttons.clear();
        actions.clear();
        listArea = null;
        FeatureConfigs.CustomScoreboard c = c();
        int left = left(), pw = panelW(), top = panelTop(), bottom = panelBottom();
        // Place the text boxes for this frame before they draw (super draws the widgets).
        if (c != null && !rows.isEmpty()) layoutBoxes(left + 8, top + 6, pw - 16, bottom - 4);
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (c == null) return;
        g.centeredText(font, "Custom Scoreboard Editor", width / 2, 10, GOLD);

        // Tabs.
        Tab[] tabs = Tab.values();
        int tw = pw / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            button(g, left + i * tw, TOP, tw - 2, 16, t.label, t == tab, mouseX, mouseY, () -> {
                tab = t;
                adding = false;
                listScroll = 0;
                init();
            });
        }
        g.fill(left, top, left + pw, bottom, PANEL);
        g.outline(left, top, pw, bottom - top, EDGE);
        int x = left + 8, w = pw - 16;
        switch (tab) {
            case LINES -> lines(g, c, x, top + 8, w, bottom, mouseX, mouseY);
            case EVENTS -> events(g, c, x, top + 8, w, bottom, mouseX, mouseY);
            default -> drawRows(g, x, top + 6, w, bottom - 4, mouseX, mouseY);
        }

        // Live preview on the right, scaled down to fit.
        int px = left + pw + 20, pr = width - 10;
        g.fill(px, TOP, pr, bottom, 0x60000000);
        g.outline(px, TOP, pr - px, bottom - TOP, EDGE);
        g.text(font, "Preview", px + 6, TOP + 5, TEXT, true);
        int[] size = CustomScoreboard.previewSize();
        float fit = Math.min(1f, Math.min((pr - px - 12) / (float) Math.max(1, size[0]), (bottom - TOP - 26) / (float) Math.max(1, size[1])));
        float s = Math.min(fit, Math.max(0.25f, c.scale));
        int sx = px + Math.round((pr - px - size[0] * s) / 2), sy = TOP + 20;
        g.pose().pushMatrix();
        g.pose().translate((float) sx, (float) sy);
        g.pose().scale(s, s);
        CustomScoreboard.drawPreview(g);
        g.pose().popMatrix();

        // Bottom bar.
        int by = height - 24;
        button(g, left, by, 120, 18, c.enabled ? "Scoreboard: On" : "Scoreboard: Off", c.enabled, mouseX, mouseY, () -> {
            c.enabled = !c.enabled;
            save();
        });
        button(g, left + 124, by, 90, 18, "All Settings", false, mouseX, mouseY, () -> Sky2MConfig.openGui("Custom Scoreboard"));
        button(g, width - 70, by, 60, 18, "Done", false, mouseX, mouseY, this::onClose);
    }

    private int rowsHeight() {
        int h = 0;
        for (Row row : rows) h += row.height();
        return h;
    }

    private void clampRowScroll(int viewHeight) {
        rowScroll = Math.clamp(rowScroll, 0, Math.max(0, rowsHeight() - viewHeight));
    }

    private void layoutBoxes(int x, int top, int w, int bottom) {
        clampRowScroll(bottom - top);
        int y = top - rowScroll;
        for (Row row : rows) {
            if (row.box() != null) {
                int bx = x + 90;
                row.box().setX(bx);
                row.box().setY(y + 1);
                row.box().setWidth(Math.max(40, x + w - bx));
                row.box().visible = y >= top && y + row.height() <= bottom;
            }
            y += row.height();
        }
    }

    private void drawRows(GuiGraphicsExtractor g, int x, int top, int w, int bottom, int mouseX, int mouseY) {
        clampRowScroll(bottom - top);
        g.enableScissor(x - 4, top, x + w + 4, bottom);
        int y = top - rowScroll;
        for (Row row : rows) {
            // Only rows fully in view take clicks (their buttons are added as they draw).
            if (y >= top && y + row.height() <= bottom) row.draw().draw(g, x, y, w, mouseX, mouseY);
            y += row.height();
        }
        g.disableScissor();
        if (rowsHeight() > bottom - top) g.text(font, "scroll for more", x + w - font.width("scroll for more"), bottom - 9, TEXT, false);
    }

    private void lines(GuiGraphicsExtractor g, FeatureConfigs.CustomScoreboard c, int x, int y, int w, int bottom, int mouseX, int mouseY) {
        g.text(font, "Lines: drag to reorder, x to remove", x, y, TEXT, true);
        button(g, x + w - 70, y - 3, 70, 14, adding ? "Cancel" : "+ Add Line", adding, mouseX, mouseY, () -> adding = !adding);
        y += 14;
        int listBottom = bottom - 26;
        if (adding) {
            List<Element> addable = new ArrayList<>();
            boolean separatorShown = false;
            for (Element e : Element.values()) {
                if (e.isSeparator()) {
                    // One "Separator" button; it adds whichever separator isn't in use yet.
                    if (!separatorShown && c.lines.stream().filter(l -> l != null && l.isSeparator()).count() < 4) addable.add(e);
                    separatorShown = true;
                } else if (!c.lines.contains(e)) {
                    addable.add(e);
                }
            }
            if (addable.isEmpty()) g.text(font, "Every line is already shown.", x, y + 4, TEXT, true);
            int cols = 2, cw = (w - 4) / cols;
            for (int i = 0; i < addable.size(); i++) {
                Element e = addable.get(i);
                int bx = x + (i % cols) * (cw + 4), byy = y + (i / cols) * 16;
                if (byy + 14 > listBottom) break;
                button(g, bx, byy, cw, 14, SbcItems.parseLegacy(e.isSeparator() ? "Separator" : e.toString()).getString(), false, mouseX, mouseY, () -> {
                    Element add = e;
                    if (add.isSeparator()) {
                        for (Element s : Element.values()) if (s.isSeparator() && !c.lines.contains(s)) { add = s; break; }
                    }
                    int at = c.lines.contains(Element.FOOTER) ? c.lines.indexOf(Element.FOOTER) : c.lines.size();
                    c.lines.add(at, add);
                    save();
                });
            }
        } else {
            draggable(g, c.lines, x, y, w, listBottom, mouseX, mouseY);
        }
        button(g, x, listBottom + 6, w, 14, "SkyBlock Preset (the mod's default lines and events)", false, mouseX, mouseY, () -> {
            FeatureConfigs.CustomScoreboard fresh = new FeatureConfigs.CustomScoreboard();
            c.lines = fresh.lines;
            c.events = fresh.events;
            save();
        });
    }

    private void events(GuiGraphicsExtractor g, FeatureConfigs.CustomScoreboard c, int x, int y, int w, int bottom, int mouseX, int mouseY) {
        g.text(font, "Events: shown in this order while going on", x, y, TEXT, true);
        button(g, x + w - 70, y - 3, 70, 14, adding ? "Cancel" : "+ Add", adding, mouseX, mouseY, () -> adding = !adding);
        y += 14;
        int listBottom = bottom - 26;
        if (adding) {
            List<Event> missing = new ArrayList<>();
            for (Event e : Event.values()) if (!c.events.contains(e)) missing.add(e);
            if (missing.isEmpty()) g.text(font, "Every event is already in the list.", x, y + 4, TEXT, true);
            int cols = 2, cw = (w - 4) / cols;
            for (int i = 0; i < missing.size(); i++) {
                Event e = missing.get(i);
                int bx = x + (i % cols) * (cw + 4), byy = y + (i / cols) * 16;
                if (byy + 14 > listBottom) break;
                button(g, bx, byy, cw, 14, SbcItems.parseLegacy(e.toString()).getString(), false, mouseX, mouseY, () -> {
                    c.events.add(e);
                    save();
                });
            }
        } else {
            draggable(g, c.events, x, y, w, listBottom, mouseX, mouseY);
        }
        if (!c.lines.contains(Element.EVENTS)) {
            g.text(font, "The Events line isn't in your Lines, so these don't show.", x, listBottom + 9, 0xFFFF7070, true);
        } else {
            g.text(font, "Other: lines Sky2M doesn't know, so nothing is lost.", x, listBottom + 9, TEXT, true);
        }
    }

    /** A list you drag into order, with x to remove; its rows are what mouseClicked/mouseReleased move. */
    private <E> void draggable(GuiGraphicsExtractor g, List<E> list, int x, int y, int w, int listBottom, int mouseX, int mouseY) {
        int visible = Math.max(1, (listBottom - y) / ROW);
        listScroll = Math.clamp(listScroll, 0, Math.max(0, list.size() - visible));
        listArea = new int[]{x, y, w, visible * ROW};
        for (int i = listScroll; i < list.size() && i - listScroll < visible; i++) {
            int ry = y + (i - listScroll) * ROW;
            if (i == dragging) ry = Math.clamp(mouseY - dragY, y, y + (visible - 1) * ROW);
            E e = list.get(i);
            boolean over = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW - 1;
            g.fill(x, ry, x + w, ry + ROW - 1, i == dragging ? BUTTON_HOVER : over ? 0xFF243048 : 0xFF1A2130);
            g.text(font, "≡", x + 4, ry + 4, TEXT, false);
            boolean separator = e instanceof Element el && el.isSeparator();
            g.text(font, SbcItems.parseLegacy(e == null ? "?" : e.toString()), x + 16, ry + 4, separator ? TEXT : 0xFFFFFFFF, true);
            int index = i;
            button(g, x + w - 14, ry + 1, 13, 13, "x", false, mouseX, mouseY, () -> {
                list.remove(index);
                save();
            });
        }
        if (list.size() > visible) g.text(font, "scroll for more", x + w - 20 - font.width("scroll for more"), listBottom - 9, TEXT, false);
    }

    // ------------------------------------------------------------------------------------------------ input

    @SuppressWarnings("unchecked")
    private List<Object> draggedList() {
        FeatureConfigs.CustomScoreboard c = c();
        if (c == null) return null;
        return switch (tab) {
            case LINES -> (List<Object>) (List<?>) c.lines;
            case EVENTS -> (List<Object>) (List<?>) c.events;
            default -> null;
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = actions.size() - 1; i >= 0; i--) {
            int[] b = buttons.get(i);
            if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
                actions.get(i).run();
                return true;
            }
        }
        List<Object> list = draggedList();
        if (list != null && !adding && listArea != null && event.x() >= listArea[0] && event.x() < listArea[0] + listArea[2]
            && event.y() >= listArea[1] && event.y() < listArea[1] + listArea[3]) {
            int index = listScroll + (int) ((event.y() - listArea[1]) / ROW);
            if (index < list.size()) {
                dragging = index;
                dragY = (int) (event.y() - listArea[1] - (index - listScroll) * ROW);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        List<Object> list = draggedList();
        if (dragging >= 0 && list != null && listArea != null) {
            int target = Math.clamp(listScroll + (int) ((event.y() - listArea[1]) / ROW), 0, list.size() - 1);
            if (target != dragging && dragging < list.size()) {
                Object e = list.remove(dragging);
                list.add(target, e);
                save();
            }
            dragging = -1;
            return true;
        }
        dragging = -1;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < left() + panelW()) {
            if (tab == Tab.LINES || tab == Tab.EVENTS) listScroll = Math.max(0, listScroll - (int) Math.signum(scrollY));
            else rowScroll = Math.max(0, rowScroll - (int) Math.signum(scrollY) * 18);
        }
        return true;
    }

    @Override
    public void onClose() {
        save();
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
