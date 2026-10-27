package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.gui.Sky2MHudEditorScreen;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 huds: every registered HUD with whether it's on, where it is saved (x, y, scale) and whether that is past the
 * screen edge, with [Reset] (back to its registered default) and [Edit] (/s2 gui) per HUD. A screen rather than
 * settings rows, because MoulConfig builds its options from fixed fields and HUDs are registered at runtime.
 */
public final class Sky2MHudOverviewScreen extends Screen {
    private static final int ROW = 14;
    private static final int GREY = 0xFF7A8290;
    private static final int RESET_W = 40, EDIT_W = 32, GAP = 4;

    private final Screen parent;
    private EditBox search;
    private String filter;
    private int scroll;
    private String status = "";

    public Sky2MHudOverviewScreen(Screen parent, String filter) {
        super(Component.literal("Sky2M HUDs"));
        this.parent = parent;
        this.filter = filter == null ? "" : filter;
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("huds")
                    .executes(c -> Compat.queueOpenScreen(new Sky2MHudOverviewScreen(null, "")))
                    .then(ClientCommands.argument("search", StringArgumentType.greedyString())
                        .executes(c -> Compat.queueOpenScreen(new Sky2MHudOverviewScreen(null, StringArgumentType.getString(c, "search")))))));
            }
        });
    }

    /** Opens the overview over {@code parent} (the settings' HUD Overview button). */
    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MHudOverviewScreen(parent, "")));
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int tableTop() { return top() + 52; }
    private int tableBottom() { return top() + panelHeight() - 20; }
    private int rowsTop() { return tableTop() + 16; }

    @Override
    protected void init() {
        if (search != null) filter = search.getValue();
        int right = left() + panelWidth() - 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(right - 50, top() + 6, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open /s2 gui"), b -> minecraft.gui.setScreen(new Sky2MHudEditorScreen(this)))
            .bounds(right - 50 - 4 - 90, top() + 6, 90, 20).build());
        search = new EditBox(font, left() + 8, top() + 28, Math.min(200, panelWidth() - 16), 18, Component.literal("Search"));
        search.setHint(Component.literal("Search HUDs").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(filter);
        search.setResponder(text -> {
            filter = text;
            scroll = 0;
        });
        addRenderableWidget(search);
    }

    private List<Sky2MHuds.Element> shown() {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        List<Sky2MHuds.Element> out = new ArrayList<>();
        for (Sky2MHuds.Element e : Sky2MHuds.elements()) {
            if (f.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(f) || e.id().toLowerCase(Locale.ROOT).contains(f)) out.add(e);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private int[] columns() {
        int w = panelWidth() - 16 - RESET_W - EDIT_W - GAP * 2, x = left() + 8;
        return new int[]{x, x + w * 45 / 100, x + w * 56 / 100, x + w * 80 / 100};
    }

    private int resetX() { return left() + panelWidth() - 8 - EDIT_W - GAP - RESET_W; }
    private int editX() { return left() + panelWidth() - 8 - EDIT_W; }

    private int visibleRows() {
        return Math.max(1, (tableBottom() - rowsTop()) / ROW);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left(), top(), left() + panelWidth(), top() + panelHeight(), 0xFF181B21);
        g.fill(left(), top(), left() + panelWidth(), top() + 2, 0xFF55FFFF);
        g.text(font, Component.literal("HUDs").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), left() + 8, top() + 10, 0xFFFFFFFF, true);

        int[] c = columns();
        int y = tableTop();
        String[] headers = {"HUD", "Status", "Position (x, y, scale)", "Screen"};
        for (int i = 0; i < headers.length; i++) g.text(font, headers[i], c[i], y, 0xFF55FFFF, false);
        g.fill(left() + 6, y + 10, left() + panelWidth() - 6, y + 11, 0xFF3A3F48);

        List<Sky2MHuds.Element> shown = shown();
        if (shown.isEmpty()) {
            g.text(font, filter.isBlank() ? "No HUDs are registered." : "No HUD matches \"" + filter.trim() + "\".", c[0], rowsTop(), GREY, false);
        }
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - visible)));
        for (int i = scroll; i < shown.size() && i < scroll + visible; i++) {
            Sky2MHuds.Element e = shown.get(i);
            int ry = rowsTop() + (i - scroll) * ROW;
            boolean on = Sky2MHuds.turnedOn(e);
            Sky2MHuds.Placement p = Sky2MHuds.placement(e.id());
            boolean off = Sky2MHuds.offScreen(e);
            boolean hoverName = mouseX >= c[0] && mouseX < c[1] - 6 && mouseY >= ry - 2 && mouseY < ry + ROW - 2;
            g.text(font, font.plainSubstrByWidth(e.name(), c[1] - c[0] - 6), c[0], ry, hoverName ? 0xFF55FFFF : 0xFFFFFFFF, false);
            g.text(font, on ? "On" : "Off", c[1], ry, on ? 0xFF55FF55 : GREY, false);
            g.text(font, String.format(Locale.US, "%d, %d  ×%.1f", p.x, p.y, p.scale), c[2], ry, 0xFFB8C0CC, false);
            g.text(font, off ? "Off-screen" : "On screen", c[3], ry, off ? 0xFFFFAA00 : GREY, false);
            drawButton(g, resetX(), ry - 2, RESET_W, "Reset", true, mouseX, mouseY);
            drawButton(g, editX(), ry - 2, EDIT_W, "Edit", on, mouseX, mouseY);
        }
        String footer = status.isEmpty()
            ? "Click a name for its settings. Reset puts a HUD back at its default position and scale. Off-screen HUDs are drawn at the nearest edge."
            : status;
        g.text(font, font.plainSubstrByWidth(footer, panelWidth() - 16), left() + 8, tableBottom() + 6, status.isEmpty() ? GREY : 0xFF55FF55, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawButton(GuiGraphicsExtractor g, int x, int y, int w, String label, boolean active, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW - 2;
        g.fill(x, y, x + w, y + ROW - 2, hover ? 0xFF3A4150 : 0xFF262A33);
        g.centeredText(font, Component.literal(label), x + w / 2, y + 2, active ? 0xFFFFFFFF : 0xFF555A64);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || event.y() < rowsTop() - 2 || event.y() >= tableBottom()) return false;
        List<Sky2MHuds.Element> shown = shown();
        int index = scroll + (int) ((event.y() - rowsTop() + 2) / ROW);
        if (index < 0 || index >= shown.size() || index >= scroll + visibleRows()) return false;
        Sky2MHuds.Element e = shown.get(index);
        double x = event.x();
        int[] c = columns();
        if (x >= resetX() && x < resetX() + RESET_W) {
            Sky2MHuds.resetPlacement(e.id());
            status = "Reset " + e.name() + " to its default position.";
            return true;
        }
        if (x >= editX() && x < editX() + EDIT_W) {
            if (!Sky2MHuds.turnedOn(e)) {
                // /s2 gui only lists HUDs whose feature is on.
                status = "Turn " + e.name() + " on in its settings first (click its name).";
                return true;
            }
            minecraft.gui.setScreen(new Sky2MHudEditorScreen(this));
            return true;
        }
        if (x >= c[0] && x < c[1] - 6) {
            Sky2MConfig.openGui(Sky2MHuds.settingsSearch(e.id(), e.name()));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
