package com.epic60869.sky2m;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * What's New: each feature added since your last version, with its description, an ON/OFF toggle for its setting
 * and a Settings button that opens the settings searched for it. Scrolls like /s2 log.
 */
public final class Sky2MWhatsNewScreen extends Screen {
    private static final int PANEL = 0xC0101216;
    private static final int PANEL_BORDER = 0xFF2C313A;
    private static final int CARD = 0x60202630;
    private static final int LINE = 10;
    private static final int PAD = 8;
    private static final int BUTTON_H = 14;
    private static final int TOGGLE_W = 34;
    private static final int SETTINGS_W = 50;

    private record Card(Sky2MWhatsNew.Feature feature, List<FormattedCharSequence> lines, int height) {}

    private final List<Sky2MWhatsNew.Feature> features;
    private final String installed;
    private final List<Card> cards = new ArrayList<>();
    private int contentHeight;
    private double scroll;
    private int panelX, panelY, panelW, panelH;

    public Sky2MWhatsNewScreen(List<Sky2MWhatsNew.Feature> features, String installed) {
        super(Component.literal("What's New in Sky2M"));
        this.features = features;
        this.installed = installed;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 32, 480);
        panelX = (width - panelW) / 2;
        panelY = 40;
        panelH = Math.max(80, height - panelY - 36);
        boolean anyToggle = features.stream().anyMatch(f -> f.toggle() != null);
        if (anyToggle) {
            addRenderableWidget(Button.builder(Component.literal("All On"), b -> setAll(true))
                .bounds(panelX, 14, 60, 20).build());
            addRenderableWidget(Button.builder(Component.literal("All Off"), b -> setAll(false))
                .bounds(panelX + panelW - 60, 14, 60, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Full Changelog"), b -> minecraft.execute(() -> minecraft.gui.setScreen(
                new Sky2MChangelogScreen(Sky2MChangelog.versions(), 0, installed))))
            .bounds(width / 2 - 105, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 + 5, height - 28, 100, 20).build());
        layout();
    }

    private void layout() {
        cards.clear();
        int textW = panelW - PAD * 4 - TOGGLE_W - SETTINGS_W - 8;
        int total = 0;
        for (Sky2MWhatsNew.Feature feature : features) {
            // The description without the name and settings path it starts with.
            String text = feature.text();
            int colon = text.indexOf(": ");
            if (colon > 0 && colon < 160) text = text.substring(colon + 2);
            List<FormattedCharSequence> lines = font.split(FormattedText.of(text), textW);
            int h = PAD + LINE + 2 + lines.size() * LINE + PAD;
            cards.add(new Card(feature, lines, h));
            total += h + 4;
        }
        contentHeight = total;
    }

    private void setAll(boolean on) {
        for (Sky2MWhatsNew.Feature feature : features) if (feature.toggle() != null) feature.toggle().set(on);
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
    }

    private int viewTop() {
        return panelY + 4;
    }

    private int viewBottom() {
        return panelY + panelH - 4;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewBottom() - viewTop()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.centeredText(font, Component.literal("What's New in Sky2M " + installed).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
            width / 2, 15, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(features.size() + " new feature" + (features.size() == 1 ? "" : "s")).withStyle(ChatFormatting.GRAY),
            width / 2, 27, 0xFFAAAAAA);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.outline(panelX, panelY, panelW, panelH, PANEL_BORDER);

        if (cards.isEmpty()) {
            g.centeredText(font, Component.literal("No new features since your last version.").withStyle(ChatFormatting.GRAY),
                width / 2, panelY + panelH / 2 - 4, 0xFFFFFFFF);
        } else {
            scroll = Mth.clamp(scroll, 0, maxScroll());
            g.enableScissor(panelX + 1, viewTop(), panelX + panelW - 1, viewBottom());
            int y = viewTop() - (int) scroll;
            boolean insideView = mouseY >= viewTop() && mouseY < viewBottom();
            for (Card card : cards) {
                if (y + card.height() >= viewTop() && y <= viewBottom()) drawCard(g, card, y, insideView ? mouseX : -1, mouseY);
                y += card.height() + 4;
            }
            g.disableScissor();
            if (maxScroll() > 0) {
                int trackH = viewBottom() - viewTop();
                int barH = Math.max(16, trackH * trackH / Math.max(trackH, contentHeight));
                int barY = viewTop() + (int) ((trackH - barH) * (scroll / maxScroll()));
                g.fill(panelX + panelW - 5, viewTop(), panelX + panelW - 2, viewBottom(), 0x40FFFFFF);
                g.fill(panelX + panelW - 5, barY, panelX + panelW - 2, barY + barH, 0xFFAAAAAA);
            }
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawCard(GuiGraphicsExtractor g, Card card, int y, int mouseX, int mouseY) {
        int x = panelX + PAD;
        int w = panelW - PAD * 2 - 6;
        g.fill(x, y, x + w, y + card.height(), CARD);
        Sky2MWhatsNew.Feature feature = card.feature();
        g.text(font, Component.literal(feature.name()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), x + PAD, y + PAD, 0xFFFFFFFF, true);
        if (!feature.version().equalsIgnoreCase(installed)) {
            int nameW = font.width(Component.literal(feature.name()).withStyle(ChatFormatting.BOLD));
            g.text(font, Component.literal(feature.version()).withStyle(ChatFormatting.DARK_GRAY), x + PAD + nameW + 6, y + PAD, 0xFF777777, false);
        }
        int ty = y + PAD + LINE + 2;
        for (FormattedCharSequence line : card.lines()) {
            g.text(font, line, x + PAD, ty, 0xFFD0D0D0, false);
            ty += LINE;
        }

        int by = y + PAD - 2;
        int settingsX = x + w - PAD - SETTINGS_W;
        int toggleX = settingsX - 4 - TOGGLE_W;
        if (feature.toggle() != null) {
            boolean on = feature.toggle().get();
            boolean hover = over(mouseX, mouseY, toggleX, by, TOGGLE_W);
            g.fill(toggleX, by, toggleX + TOGGLE_W, by + BUTTON_H, on ? (hover ? 0xFF44BB44 : 0xFF339933) : (hover ? 0xFFCC4444 : 0xFF993333));
            g.centeredText(font, Component.literal(on ? "ON" : "OFF"), toggleX + TOGGLE_W / 2, by + 3, 0xFFFFFFFF);
        }
        if (feature.option() != null) {
            boolean hover = over(mouseX, mouseY, settingsX, by, SETTINGS_W);
            g.fill(settingsX, by, settingsX + SETTINGS_W, by + BUTTON_H, hover ? 0xFF4A5566 : 0xFF353D4A);
            g.centeredText(font, Component.literal("Settings"), settingsX + SETTINGS_W / 2, by + 3, 0xFFFFFFFF);
        } else if (feature.text().contains("Controls")) {
            g.text(font, Component.literal("In Controls").withStyle(ChatFormatting.GRAY), settingsX, by + 3, 0xFFAAAAAA, false);
        }
    }

    private static boolean over(double mouseX, double mouseY, int x, int y, int w) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + BUTTON_H;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
        if (click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.y() >= viewTop() && click.y() < viewBottom()) {
            int y = viewTop() - (int) scroll;
            int x = panelX + PAD;
            int w = panelW - PAD * 2 - 6;
            for (Card card : cards) {
                int by = y + PAD - 2;
                int settingsX = x + w - PAD - SETTINGS_W;
                int toggleX = settingsX - 4 - TOGGLE_W;
                Sky2MWhatsNew.Feature feature = card.feature();
                if (feature.toggle() != null && over(click.x(), click.y(), toggleX, by, TOGGLE_W)) {
                    feature.toggle().set(!feature.toggle().get());
                    Sky2MConfig.saveCurrent(Sky2MConfig.current());
                    playClick();
                    return true;
                }
                if (feature.option() != null && over(click.x(), click.y(), settingsX, by, SETTINGS_W)) {
                    playClick();
                    String search = feature.option().name();
                    int i = search.lastIndexOf(" > ");
                    Sky2MConfig.openGui(i < 0 ? search : search.substring(i + 3));
                    return true;
                }
                y += card.height() + 4;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void playClick() {
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Mth.clamp(scroll - vertical * LINE * 3, 0, maxScroll());
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
