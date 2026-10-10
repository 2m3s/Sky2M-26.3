package com.epic60869.sky2m.features.combat;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /s2 achievements: the Diana achievements (SBO's AchievementsGUI) by category, in their rarity colour once unlocked
 * and grey while locked. Secret ones stay hidden until you get them. Scrolls with the mouse wheel.
 */
public final class DianaAchievementsScreen extends Screen {
    private static final int PANEL = 0xC0101216;
    private static final int PANEL_BORDER = 0xFF2C313A;
    private static final int LINE = 11;
    private static final int PAD = 10;

    private record Line(FormattedCharSequence text, int x, int gapBefore) {}

    private final List<Line> lines = new ArrayList<>();
    private int contentHeight;
    private double scroll;
    private int unlocked;
    private int total;
    private int panelX, panelY, panelW, panelH;

    public DianaAchievementsScreen() {
        super(Component.literal("Diana Achievements"));
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 32, 480);
        panelX = (width - panelW) / 2;
        panelY = 44;
        panelH = Math.max(80, height - panelY - 36);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 - 50, height - 28, 100, 20).build());
        layoutLines();
    }

    private void layoutLines() {
        lines.clear();
        unlocked = 0;
        total = 0;
        Map<String, List<DianaAchievements.Achievement>> byCategory = new LinkedHashMap<>();
        for (DianaAchievements.Achievement a : DianaAchievements.all()) byCategory.computeIfAbsent(a.category(), k -> new ArrayList<>()).add(a);
        int textW = panelW - PAD * 2 - 8;
        boolean first = true;
        for (Map.Entry<String, List<DianaAchievements.Achievement>> e : byCategory.entrySet()) {
            int done = 0;
            for (DianaAchievements.Achievement a : e.getValue()) if (DianaAchievements.isUnlocked(a)) done++;
            unlocked += done;
            total += e.getValue().size();
            Component heading = Component.literal(e.getKey()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("  " + done + "/" + e.getValue().size()).withStyle(ChatFormatting.GRAY));
            lines.add(new Line(heading.getVisualOrderText(), 0, first ? 0 : 8));
            first = false;
            for (DianaAchievements.Achievement a : e.getValue()) {
                boolean has = DianaAchievements.isUnlocked(a);
                boolean secret = a.hidden() && !DianaAchievements.everUnlocked(a);
                MutableComponent row = Component.literal(has ? "✔ " : "✖ ").withStyle(has ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                    .append(Component.literal(secret ? "Secret Achievement" : a.name()).withStyle(has ? a.rarity().colour : ChatFormatting.GRAY))
                    .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY));
                if (secret) row.append(Component.literal(a.description()).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.OBFUSCATED));
                else row.append(Component.literal(a.description()).withStyle(has ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
                row.append(Component.literal(" (" + a.rarity().label + ")").withStyle(a.rarity().colour));
                List<FormattedCharSequence> wrapped = font.split(row, textW - 10);
                for (int i = 0; i < wrapped.size(); i++) lines.add(new Line(wrapped.get(i), i == 0 ? 6 : 16, i == 0 ? 2 : 0));
            }
        }
        int h = 0;
        for (Line line : lines) h += line.gapBefore() + LINE;
        contentHeight = h;
    }

    private int viewTop() {
        return panelY + PAD;
    }

    private int viewBottom() {
        return panelY + panelH - PAD;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (viewBottom() - viewTop()));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.centeredText(font, Component.literal("Diana Achievements").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), width / 2, 17, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(unlocked + " / " + total + " unlocked").withStyle(ChatFormatting.GRAY), width / 2, 29, 0xFFAAAAAA);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.outline(panelX, panelY, panelW, panelH, PANEL_BORDER);

        scroll = Mth.clamp(scroll, 0, maxScroll());
        g.enableScissor(panelX + 1, viewTop(), panelX + panelW - 1, viewBottom());
        int y = viewTop() - (int) scroll;
        for (Line line : lines) {
            y += line.gapBefore();
            if (y + LINE >= viewTop() && y <= viewBottom()) g.text(font, line.text(), panelX + PAD + line.x(), y, 0xFFFFFFFF, true);
            y += LINE;
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int trackH = viewBottom() - viewTop();
            int barH = Math.max(16, trackH * trackH / Math.max(trackH, contentHeight));
            int barY = viewTop() + (int) ((trackH - barH) * (scroll / maxScroll()));
            int barX = panelX + panelW - 6;
            g.fill(barX, viewTop(), barX + 3, viewBottom(), 0x40FFFFFF);
            g.fill(barX, barY, barX + 3, barY + barH, 0xFFAAAAAA);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
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
