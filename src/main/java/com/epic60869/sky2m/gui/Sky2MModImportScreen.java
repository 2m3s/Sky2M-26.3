package com.epic60869.sky2m.gui;

import com.epic60869.sky2m.config.importer.ModConfigImporter;
import com.epic60869.sky2m.config.importer.ModConfigImporter.Choice;
import com.epic60869.sky2m.config.importer.ModConfigImporter.Detected;
import com.epic60869.sky2m.config.importer.ModConfigImporter.Row;
import com.epic60869.sky2m.social.Sky2MProfiles;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /s2 import: the other mods whose config files are in the config folder on the left, and on the right every Sky2M
 * setting the chosen mod has an equivalent of, with that mod's value, Sky2M's current value and a choice per row
 * (Enable / Disable / Skip). Mapped settings start on the other mod's value; settings only guessed by name start on
 * Skip. Apply sets the chosen settings and saves. Styled like the /s2 settings, as /s2 leaderboard is.
 */
public final class Sky2MModImportScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int LIST_ROW = 14;
    private static final int CHOICE_W = 32;
    private static final int VALUE_W = 44;
    private static final int BAR_H = 28;

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean inside(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final Screen parent;
    private final List<Detected> mods;
    private final Map<String, List<Row>> rows = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private Detected selected;
    private int tableScroll;
    private int listScroll;

    public Sky2MModImportScreen(Screen parent, String initialId) {
        super(Component.literal("Import Settings From Other Mods"));
        this.parent = parent;
        this.mods = ModConfigImporter.detect();
        for (Detected mod : mods) rows.put(mod.id(), ModConfigImporter.rows(mod));
        Detected first = null;
        for (Detected mod : mods) {
            if (initialId != null && (mod.id().equalsIgnoreCase(initialId) || mod.name().equalsIgnoreCase(initialId))) {
                first = mod;
                break;
            }
            if (first == null && !rows.get(mod.id()).isEmpty()) first = mod;
        }
        if (first == null && !mods.isEmpty()) first = mods.get(0);
        this.selected = first;
    }

    /** Opens the screen after the chat or settings screen it's opened from has closed. */
    public static void open(Screen parent, String initialId) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MModImportScreen(parent, initialId)));
    }

    // ---------------------------------------------------------------- for tests

    public List<Detected> mods() {
        return mods;
    }

    public Detected selected() {
        return selected;
    }

    public List<Row> selectedRows() {
        return selected == null ? List.of() : rows.get(selected.id());
    }

    public void select(String id) {
        for (Detected mod : mods) if (mod.id().equalsIgnoreCase(id)) {
            selected = mod;
            tableScroll = 0;
        }
    }

    /** Applies the selected mod's choices, says so in chat and closes. Returns how many settings were set. */
    public int applySelected() {
        if (selected == null) return 0;
        int applied = ModConfigImporter.apply(selectedRows());
        String from = selected.name();
        Sky2MProfiles.say(Component.literal(applied == 0 ? "Nothing was imported from " + from + "."
            : "Imported " + applied + " setting" + (applied == 1 ? "" : "s") + " from " + from + ".").withStyle(applied == 0 ? ChatFormatting.GRAY : ChatFormatting.GREEN));
        onClose();
        return applied;
    }

    // ---------------------------------------------------------------- layout

    private int panelW() { return Math.min(width - 16, 660); }
    private int left() { return (width - panelW()) / 2; }
    private int top() { return 8; }
    private int panelH() { return height - 16; }
    private int bodyTop() { return top() + 30; }
    private int bodyBottom() { return top() + panelH() - BAR_H - 6; }
    private int listW() { return Math.min(140, panelW() / 4); }
    private int tableX() { return left() + 4 + listW() + 4; }
    private int tableRight() { return left() + panelW() - 5; }
    private int tableTop() { return bodyTop() + 22 + (selected != null && selected.guessedOnly() ? 12 : 0); }
    private int tableBottom() { return bodyBottom() - 4; }

    private int visibleRows() {
        return Math.max(1, (tableBottom() - tableTop()) / ROW_H);
    }

    private int visibleMods() {
        return Math.max(1, (bodyBottom() - bodyTop() - 22) / LIST_ROW);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelW(), panelH(), true);
        drawHeader(g);
        int bodyH = bodyBottom() - bodyTop();
        Sky2MGuiStyle.panel(g, left() + 4, bodyTop(), listW(), bodyH, false);
        Sky2MGuiStyle.panel(g, tableX(), bodyTop(), tableRight() - tableX(), bodyH, false);
        drawMods(g, mouseX, mouseY);
        drawTable(g, mouseX, mouseY);
        drawBar(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        int x = left() + 4, w = panelW() - 9;
        Sky2MGuiStyle.panel(g, x, top() + 5, w, 20, false);
        Component title = Sky2MGuiStyle.title("Import Settings");
        g.text(font, title, x + 6, top() + 11, Sky2MGuiStyle.TITLE, false);
        if (selected != null) {
            List<Row> list = selectedRows();
            long chosen = list.stream().filter(r -> r.choice != Choice.SKIP).count();
            long changes = list.stream().filter(Row::changes).count();
            String status = list.size() + " matching · " + chosen + " to import · " + changes + " would change";
            String fit = font.plainSubstrByWidth(status, Math.max(0, w - 20 - font.width(title)));
            g.text(font, fit, x + w - 6 - font.width(fit), top() + 11, Sky2MGuiStyle.TITLE, false);
        }
    }

    private void drawMods(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left() + 4;
        Sky2MGuiStyle.heading(g, font, "Mods", x, bodyTop() + 6, listW());
        if (mods.isEmpty()) {
            for (int i = 0; i < 3; i++) {
                String[] lines = {"No other mod's", "config was found", "in config/."};
                g.text(font, lines[i], x + 6, bodyTop() + 22 + i * 10, Sky2MGuiStyle.DESCRIPTION, false);
            }
            return;
        }
        listScroll = Math.max(0, Math.min(listScroll, mods.size() - visibleMods()));
        int y = bodyTop() + 20;
        for (int i = listScroll; i < mods.size() && i < listScroll + visibleMods(); i++) {
            Detected mod = mods.get(i);
            int n = rows.get(mod.id()).size();
            String text = font.plainSubstrByWidth(mod.name() + (mod.guessedOnly() ? " ?" : ""), listW() - 34);
            boolean hover = mouseX >= x + 2 && mouseX < x + listW() - 2 && mouseY >= y && mouseY < y + LIST_ROW;
            Sky2MGuiStyle.category(g, font, text, x + 6, y + 3, mod == selected, hover);
            String count = String.valueOf(n);
            g.text(font, count, x + listW() - 8 - font.width(count), y + 3, n == 0 ? Sky2MGuiStyle.MUTED : Sky2MGuiStyle.TEXT_DIM, false);
            if (hover && mod.guessedOnly()) {
                g.setTooltipForNextFrame(font, List.of(Component.literal(mod.name()),
                    Component.literal("Not a mod Sky2M knows: its settings are matched to Sky2M's by name only.").withStyle(ChatFormatting.GRAY)),
                    java.util.Optional.empty(), mouseX, mouseY);
            }
            Detected pick = mod;
            hits.add(new Hit(x + 2, y, listW() - 4, LIST_ROW, () -> {
                selected = pick;
                tableScroll = 0;
            }));
            y += LIST_ROW;
        }
        Sky2MGuiStyle.scrollbar(g, x + listW() - 6, bodyTop() + 20, bodyBottom() - 4, listScroll, visibleMods(), mods.size());
    }

    private void drawTable(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = tableX() + 6;
        int right = tableRight() - 12;
        if (selected == null) return;
        String modName = selected.name();
        int choiceX = right - CHOICE_W * 3 - 4;
        int sky2mX = choiceX - 6 - VALUE_W;
        int otherX = sky2mX - 4 - VALUE_W;
        int hy = bodyTop() + 6;
        g.text(font, "Sky2M setting", x, hy, Sky2MGuiStyle.HEADING, false);
        String otherHead = font.plainSubstrByWidth(modName, VALUE_W + 2);
        g.text(font, otherHead, otherX + (VALUE_W - font.width(otherHead)) / 2, hy, Sky2MGuiStyle.HEADING, false);
        g.text(font, "Sky2M", sky2mX + (VALUE_W - font.width("Sky2M")) / 2, hy, Sky2MGuiStyle.HEADING, false);
        g.text(font, "Import as", choiceX + (CHOICE_W * 3 + 4 - font.width("Import as")) / 2, hy, Sky2MGuiStyle.HEADING, false);
        g.fill(x - 2, hy + 10, right + 2, hy + 11, Sky2MGuiStyle.LINE);
        if (selected.guessedOnly()) {
            g.text(font, font.plainSubstrByWidth("Matched by name only: check each one. They start on Skip.", right - x),
                x, hy + 14, 0xFFFFDD55, false);
        }

        List<Row> list = selectedRows();
        if (list.isEmpty()) {
            g.text(font, font.plainSubstrByWidth("None of " + modName + "'s settings match a Sky2M setting.", right - x),
                x, tableTop() + 6, Sky2MGuiStyle.DESCRIPTION, false);
            return;
        }
        tableScroll = Math.max(0, Math.min(tableScroll, list.size() - visibleRows()));
        int ry = tableTop();
        for (int i = tableScroll; i < list.size() && i < tableScroll + visibleRows(); i++) {
            Row row = list.get(i);
            boolean over = mouseX >= x - 2 && mouseX < right + 2 && mouseY >= ry && mouseY < ry + ROW_H;
            if (over) g.fill(x - 2, ry, right + 2, ry + ROW_H, Sky2MGuiStyle.ROW_HOVER);
            else if (i % 2 == 1) g.fill(x - 2, ry, right + 2, ry + ROW_H, Sky2MGuiStyle.ROW);
            int textW = otherX - 6 - x;
            g.text(font, font.plainSubstrByWidth(row.label, textW), x, ry + 2, Sky2MGuiStyle.TEXT, false);
            String sub = row.guessed ? "guessed · " + row.where : row.where;
            g.text(font, font.plainSubstrByWidth(sub, textW), x, ry + 12, row.guessed ? 0xFFCCAA44 : Sky2MGuiStyle.MUTED, false);
            onOff(g, row.otherValue, otherX, ry + 7);
            onOff(g, row.current(), sky2mX, ry + 7);
            Choice[] choices = Choice.values();
            String[] labels = {"On", "Off", "Skip"};
            for (int c = 0; c < choices.length; c++) {
                int bx = choiceX + c * (CHOICE_W + 2);
                Choice choice = choices[c];
                boolean active = row.choice == choice;
                boolean hover = mouseX >= bx && mouseX < bx + CHOICE_W && mouseY >= ry + 4 && mouseY < ry + 18;
                int colour = !active ? (hover ? 0xFF4A4A52 : 0xFF2A2A30)
                    : choice == Choice.ENABLE ? 0xFF2E8B3A : choice == Choice.DISABLE ? 0xFF9B3030 : 0xFF5A5A62;
                g.fill(bx, ry + 4, bx + CHOICE_W, ry + 18, colour);
                g.text(font, labels[c], bx + (CHOICE_W - font.width(labels[c])) / 2, ry + 7, active ? 0xFFFFFFFF : Sky2MGuiStyle.UNSELECTED, false);
                hits.add(new Hit(bx, ry + 4, CHOICE_W, 14, () -> row.choice = choice));
            }
            if (over && mouseX < choiceX) {
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(row.label).withStyle(ChatFormatting.WHITE));
                if (!row.where.isEmpty()) tip.add(Component.literal("Sky2M: " + row.where).withStyle(ChatFormatting.GRAY));
                tip.add(Component.literal(modName + ": " + row.otherPath + " = " + (row.otherValue ? "on" : "off")).withStyle(ChatFormatting.GRAY));
                if (row.guessed) tip.add(Component.literal("Guessed from the name; it may not be the same feature.").withStyle(ChatFormatting.YELLOW));
                g.setTooltipForNextFrame(font, tip, java.util.Optional.empty(), mouseX, mouseY);
            }
            ry += ROW_H;
        }
        Sky2MGuiStyle.scrollbar(g, tableRight() - 8, tableTop(), tableBottom(), tableScroll, visibleRows(), list.size());
    }

    private void onOff(GuiGraphicsExtractor g, boolean on, int x, int y) {
        String text = on ? "ON" : "OFF";
        g.text(font, text, x + (VALUE_W - font.width(text)) / 2, y, on ? 0xFF55FF55 : 0xFFFF5555, false);
    }

    private void drawBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int y = top() + panelH() - BAR_H;
        Sky2MGuiStyle.panel(g, left() + 4, y, panelW() - 9, BAR_H - 4, false);
        int by = y + 4;
        int x = left() + 10;
        if (selected != null && !selectedRows().isEmpty()) {
            x = button(g, "Match " + selected.name(), x, by, mouseX, mouseY, () -> selectedRows().forEach(r -> r.choice = r.matching()));
            x = button(g, "All On", x, by, mouseX, mouseY, () -> setAll(Choice.ENABLE));
            x = button(g, "All Off", x, by, mouseX, mouseY, () -> setAll(Choice.DISABLE));
            button(g, "Skip All", x, by, mouseX, mouseY, () -> setAll(Choice.SKIP));
        }
        int right = left() + panelW() - 10;
        int applyW = Sky2MGuiStyle.buttonWidth(font, "Apply");
        int cancelW = Sky2MGuiStyle.buttonWidth(font, "Cancel");
        if (selected != null) button(g, "Apply", right - applyW, by, mouseX, mouseY, this::applySelected);
        button(g, "Cancel", right - applyW - 4 - cancelW, by, mouseX, mouseY, this::onClose);
    }

    private void setAll(Choice choice) {
        for (Row row : selectedRows()) row.choice = choice;
    }

    /** A settings-style button; returns the x after it. */
    private int button(GuiGraphicsExtractor g, String text, int x, int y, int mouseX, int mouseY, Runnable action) {
        int w = Sky2MGuiStyle.buttonWidth(font, text);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 16;
        Sky2MGuiStyle.button(g, font, text, x, y, w, 16, hover);
        hits.add(new Hit(x, y, w, 16, action));
        return x + w + 4;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        for (Hit hit : List.copyOf(hits)) {
            if (hit.inside(event.x(), event.y())) {
                Sky2MGuiStyle.click();
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY) * 3;
        if (mouseX < tableX()) listScroll = Math.max(0, listScroll - step);
        else tableScroll = Math.max(0, tableScroll - step);
        return true;
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
