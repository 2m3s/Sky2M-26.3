package com.epic60869.sky2m.gui;

import com.epic60869.sky2m.api.Sky2MAddons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** /s2 > Add-ons: every installed Sky2M add-on with a button that opens its features and settings. */
public final class Sky2MAddonsScreen extends Screen {
    private static final int ROW = 36;

    private final Screen parent;

    public Sky2MAddonsScreen(Screen parent) {
        super(Component.literal("Sky2M Add-ons"));
        this.parent = parent;
    }

    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MAddonsScreen(parent)));
    }

    private int panelWidth() {
        return Math.min(420, width - 40);
    }

    @Override
    protected void init() {
        int w = panelWidth();
        int left = (width - w) / 2;
        int y = 50;
        for (Sky2MAddons.Entry entry : Sky2MAddons.list()) {
            addRenderableWidget(Button.builder(Component.literal("Open"), b -> entry.addon().openSettings(this))
                .bounds(left + w - 64, y + 6, 60, 20).build());
            y += ROW;
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width / 2 - 50, height - 30, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int w = panelWidth();
        int left = (width - w) / 2;
        List<Sky2MAddons.Entry> addons = Sky2MAddons.list();
        g.centeredText(font, title, width / 2, 24, Sky2MGuiStyle.HEADING);
        int y = 50;
        if (addons.isEmpty()) {
            g.centeredText(font, Component.literal("No add-ons installed."), width / 2, y + 10, Sky2MGuiStyle.TEXT_DIM);
            g.centeredText(font, Component.literal("Add-on mods for Sky2M show here once they're in your mods folder."), width / 2, y + 24, Sky2MGuiStyle.DESCRIPTION);
        }
        for (Sky2MAddons.Entry entry : addons) {
            boolean hover = mouseX >= left && mouseX < left + w && mouseY >= y && mouseY < y + ROW - 4;
            g.fill(left, y, left + w, y + ROW - 4, hover ? Sky2MGuiStyle.ROW_HOVER : Sky2MGuiStyle.ROW);
            g.text(font, Component.literal(entry.addon().name()), left + 6, y + 6, Sky2MGuiStyle.TEXT, false);
            String desc = entry.addon().description();
            String line = (desc == null || desc.isBlank() ? "" : desc + "  ") + "v" + entry.version();
            g.text(font, Component.literal(font.plainSubstrByWidth(line, w - 80)), left + 6, y + 18, Sky2MGuiStyle.DESCRIPTION, false);
            y += ROW;
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
