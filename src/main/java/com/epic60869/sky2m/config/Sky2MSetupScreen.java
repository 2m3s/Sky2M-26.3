package com.epic60869.sky2m.config;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * /s2 setup: each area of the mod with an ON/OFF button (what you play) and a Reset button (Sky2M's defaults). Changes
 * apply when you press Apply, like /s2 collections' look.
 */
public final class Sky2MSetupScreen extends Screen {
    private static final int ROW = 26;

    /** What the buttons say now (applied on Apply). */
    private final Map<String, Boolean> wanted = new HashMap<>();
    /** Areas whose Reset was pressed once (press again to confirm). */
    private final Map<String, Boolean> confirmReset = new HashMap<>();
    private String status = "";

    public Sky2MSetupScreen() {
        super(Component.literal("Set up Sky2M"));
        for (Sky2MSetup.Area area : Sky2MSetup.AREAS) wanted.put(area.key(), Sky2MSetup.isOn(area));
    }

    private int panelWidth() { return Math.min(460, width - 20); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return 10; }
    private int listTop() { return top() + 52; }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int right = left() + panelWidth() - 8;
        int y = listTop();
        for (Sky2MSetup.Area area : Sky2MSetup.AREAS) {
            boolean on = wanted.get(area.key());
            addRenderableWidget(Button.builder(Component.literal(on ? "I play this" : "Off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY), b -> {
                wanted.put(area.key(), !wanted.get(area.key()));
                status = "";
                rebuild();
            }).bounds(right - 150, y + 2, 86, 20).build());
            boolean sure = confirmReset.getOrDefault(area.key(), false);
            addRenderableWidget(Button.builder(Component.literal(sure ? "Sure?" : "Reset").withStyle(sure ? ChatFormatting.RED : ChatFormatting.WHITE), b -> {
                if (!confirmReset.getOrDefault(area.key(), false)) {
                    confirmReset.put(area.key(), true);
                    rebuild();
                    return;
                }
                confirmReset.remove(area.key());
                status = Sky2MSetup.reset(area) ? area.label() + " is back to Sky2M's defaults." : "Couldn't reset " + area.label() + ".";
                wanted.put(area.key(), true);
                rebuild();
            }).bounds(right - 60, y + 2, 60, 20)
                .tooltip(Tooltip.create(Component.literal("Put every " + area.label() + " setting back to Sky2M's defaults.")))
                .build());
            y += ROW;
        }
        int by = y + 10;
        addRenderableWidget(Button.builder(Component.literal("All On"), b -> setAll(true)).bounds(left() + 8, by, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Apply").withStyle(ChatFormatting.GREEN), b -> apply())
            .bounds(right - 150, by, 86, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(right - 60, by, 60, 20).build());
    }

    private void setAll(boolean on) {
        for (Sky2MSetup.Area area : Sky2MSetup.AREAS) wanted.put(area.key(), on);
        rebuild();
    }

    private void apply() {
        int changed = 0;
        for (Sky2MSetup.Area area : Sky2MSetup.AREAS) {
            boolean want = wanted.get(area.key());
            if (want == Sky2MSetup.isOn(area)) continue;
            if (Sky2MSetup.set(area, want)) changed++;
        }
        status = changed == 0 ? "Nothing to change." : "Done: " + changed + " area" + (changed == 1 ? "" : "s") + " changed. Areas you turned off keep their settings for when you turn them on again.";
        rebuild();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int left = left(), w = panelWidth();
        int bottom = listTop() + Sky2MSetup.AREAS.size() * ROW + 44;
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left, top(), left + w, Math.min(height - 4, bottom + 18), 0xFF181B21);
        g.fill(left, top(), left + w, top() + 2, 0xFF55FF55);
        g.text(font, Component.literal("Set up Sky2M").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), left + 8, top() + 8, 0xFFFFFFFF, true);
        g.text(font, "Pick what you play. Turning an area off switches off all its features; its settings are kept", left + 8, top() + 22, 0xFFB8C0CC, false);
        g.text(font, "for when you turn it on again. Chat, HUDs, items and the rest of Misc aren't touched.", left + 8, top() + 33, 0xFFB8C0CC, false);
        int y = listTop();
        for (Sky2MSetup.Area area : Sky2MSetup.AREAS) {
            boolean on = wanted.get(area.key());
            boolean pending = on != Sky2MSetup.isOn(area);
            g.fill(left + 6, y, left + w - 6, y + ROW - 2, pending ? 0xFF2A2F1E : 0xFF1E222A);
            g.text(font, Component.literal(area.label()).withStyle(on ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY, ChatFormatting.BOLD),
                left + 12, y + 3, 0xFFFFFFFF, false);
            g.text(font, font.plainSubstrByWidth(area.description(), w - 190), left + 12, y + 13, 0xFF7A8290, false);
            y += ROW;
        }
        if (!status.isEmpty()) g.text(font, font.plainSubstrByWidth(status, w - 16), left + 8, y + 34, 0xFF9CFF9C, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
