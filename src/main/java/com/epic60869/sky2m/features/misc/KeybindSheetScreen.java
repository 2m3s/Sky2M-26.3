package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * /s2 keybinds: every key Sky2M uses (its settings, its Controls and Command Keys macros) in one table, with what else
 * uses the same key marked in red, and a search. Click a settings key to open it in /s2.
 */
public final class KeybindSheetScreen extends Screen {
    private static final int ROW = 13;
    private static final String[] HEADERS = {"Key", "Feature", "Set in", "Also on this key"};

    private List<KeyConflicts.SheetRow> rows = List.of();
    private EditBox search;
    private int scroll;

    public KeybindSheetScreen() {
        super(Component.literal("Sky2M Keybinds"));
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("keybinds")
                    .executes(c -> Compat.queueOpenScreen(new KeybindSheetScreen()))));
            }
        });
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int tableTop() { return top() + 50; }
    private int tableBottom() { return top() + panelHeight() - 8; }

    @Override
    protected void init() {
        rows = KeyConflicts.sheet();
        int right = left() + panelWidth() - 8;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(right - 60, top() + 24, 60, 20).build());
        String kept = search == null ? "" : search.getValue();
        search = new EditBox(font, right - 60 - 6 - 160, top() + 25, 160, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search keys or features...").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(kept);
        search.setResponder(v -> scroll = 0);
        addRenderableWidget(search);
    }

    private List<KeyConflicts.SheetRow> shown() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return rows;
        return rows.stream().filter(r -> (r.key() + " " + r.name() + " " + r.path()).toLowerCase(Locale.ROOT).contains(q)).toList();
    }

    private int[] columns() {
        int w = panelWidth() - 16, x = left() + 8;
        return new int[]{x, x + w * 14 / 100, x + w * 52 / 100, x + w * 66 / 100};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left(), top(), left() + panelWidth(), top() + panelHeight(), 0xFF181B21);
        g.fill(left(), top(), left() + panelWidth(), top() + 2, 0xFFFFFF55);
        g.text(font, Component.literal("Keybinds").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), left() + 8, top() + 8, 0xFFFFFFFF, true);
        long clashes = rows.stream().filter(r -> !r.clashesWith().isEmpty()).count();
        g.text(font, rows.size() + " Sky2M keys" + (clashes > 0 ? "  ·  " + clashes + " share a key with something else (red)" : "  ·  no conflicts"),
            left() + 8, top() + 29, clashes > 0 ? 0xFFFF7777 : 0xFF9CFF9C, false);

        int[] c = columns();
        int y = tableTop();
        for (int i = 0; i < HEADERS.length; i++) g.text(font, HEADERS[i], c[i], y, 0xFFFFFF55, false);
        g.fill(left() + 6, y + 10, left() + panelWidth() - 6, y + 11, 0xFF3A3F48);
        List<KeyConflicts.SheetRow> list = shown();
        if (list.isEmpty()) {
            g.text(font, rows.isEmpty() ? "Sky2M has no keys set." : "Nothing matches.", c[0], y + 18, 0xFF7A8290, false);
        }
        int rowsTop = y + 16;
        int visible = Math.max(1, (tableBottom() - rowsTop) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - visible)));
        for (int i = scroll; i < list.size() && i < scroll + visible; i++) {
            KeyConflicts.SheetRow r = list.get(i);
            int ry = rowsTop + (i - scroll) * ROW;
            boolean clash = !r.clashesWith().isEmpty();
            boolean over = mouseY >= ry - 2 && mouseY < ry + ROW - 2 && mouseX >= left() && mouseX < left() + panelWidth();
            if (clash) g.fill(left() + 6, ry - 2, left() + panelWidth() - 6, ry + ROW - 2, 0xFF3A1E1E);
            else if (over) g.fill(left() + 6, ry - 2, left() + panelWidth() - 6, ry + ROW - 2, 0xFF22262E);
            g.text(font, r.key(), c[0], ry, clash ? 0xFFFF5555 : 0xFF55FF55, false);
            g.text(font, font.plainSubstrByWidth(r.name(), c[2] - c[1] - 6), c[1], ry, 0xFFFFFFFF, false);
            g.text(font, r.where(), c[2], ry, 0xFF7A8290, false);
            g.text(font, font.plainSubstrByWidth(clash ? r.clashesWith() : "-", left() + panelWidth() - 8 - c[3]), c[3], ry, clash ? 0xFFFF7777 : 0xFF555555, false);
            if (over) g.setTooltipForNextFrame(font, Component.literal(r.path() + (r.where().equals("Settings") ? "\nClick to open it in /s2" : "")), mouseX, mouseY);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int rowsTop = tableTop() + 16;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || event.y() < rowsTop - 2 || event.y() >= tableBottom()) return false;
        List<KeyConflicts.SheetRow> list = shown();
        int index = scroll + (int) ((event.y() - rowsTop + 2) / ROW);
        if (index < 0 || index >= list.size() || !list.get(index).where().equals("Settings")) return false;
        Sky2MConfig.openGui(list.get(index).name());
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
