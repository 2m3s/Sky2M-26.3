package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.combat.DianaAchievements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /s2 achievements (and /s2 feats): every Sky2M achievement in one screen, with a tab for each activity. Overview has
 * your stats and how far along each tab is; Dungeons, Slayers, Mining, Combat and Sky2M hold Sky2M's own achievements
 * with your progress; Diana holds the Diana achievements (SBO's), by their categories, with secret ones hidden until
 * you get them. Scrolls with the mouse wheel.
 */
public final class Sky2MAchievementsScreen extends Screen {
    public static final String OVERVIEW = "Overview", DIANA = "Diana";
    private static final List<String> TABS = List.of(OVERVIEW, "Dungeons", "Slayers", "Mining", "Combat", "Skills", DIANA, "Sky2M");
    private static final int PANEL = 0xC0101216;
    private static final int PANEL_BORDER = 0xFF2C313A;
    private static final int TAB_H = 14;
    private static final int LINE = 11;
    private static final int PAD = 10;

    private record Line(FormattedCharSequence text, int x, int gapBefore) {}

    private final List<Line> lines = new ArrayList<>();
    private String tab;
    private int contentHeight;
    private double scroll;
    private int panelX, panelY, panelW, panelH;

    public Sky2MAchievementsScreen(String tab) {
        super(Component.literal("Achievements"));
        this.tab = TABS.contains(tab) ? tab : OVERVIEW;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 32, 480);
        panelX = (width - panelW) / 2;
        panelY = 34 + TAB_H + 4;
        panelH = Math.max(80, height - panelY - 36);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 - 50, height - 28, 100, 20).build());
        layoutLines();
    }

    // ------------------------------------------------------------------------------------------------ counts

    private static List<Sky2MAchievements.Achievement> sky2m(String category) {
        List<Sky2MAchievements.Achievement> out = new ArrayList<>();
        for (Sky2MAchievements.Achievement a : Sky2MAchievements.all()) if (a.category().equals(category) || (category.equals("Skills") && a.category().startsWith(Sky2MAchievements.SKILLS_PREFIX))) out.add(a);
        return out;
    }

    /** {unlocked, total} for a tab. */
    private static int[] counts(String tab) {
        int done = 0, total = 0;
        if (tab.equals(DIANA)) {
            for (DianaAchievements.Achievement a : DianaAchievements.all()) {
                total++;
                if (DianaAchievements.isUnlocked(a)) done++;
            }
        } else {
            for (Sky2MAchievements.Achievement a : sky2m(tab)) {
                total++;
                if (Sky2MAchievements.unlocked(a)) done++;
            }
        }
        return new int[]{done, total};
    }

    private static int[] totalCounts() {
        int done = 0, total = 0;
        for (String t : TABS) {
            if (t.equals(OVERVIEW)) continue;
            int[] c = counts(t);
            done += c[0];
            total += c[1];
        }
        return new int[]{done, total};
    }

    private static String number(Sky2MAchievements.Stat stat, long value) {
        if (stat == Sky2MAchievements.Stat.NETWORTH) return CombatFeatures.formatCoins(value);
        if (stat == Sky2MAchievements.Stat.MINUTES_PLAYED) return String.format(Locale.US, "%,.1fh", value / 60.0);
        return String.format(Locale.US, "%,d", value);
    }

    // ------------------------------------------------------------------------------------------------ layout

    private void heading(Component text, boolean first) {
        lines.add(new Line(text.getVisualOrderText(), 0, first ? 0 : 8));
    }

    private void row(Component text) {
        int textW = panelW - PAD * 2 - 18;
        List<FormattedCharSequence> wrapped = font.split(text, textW);
        for (int i = 0; i < wrapped.size(); i++) lines.add(new Line(wrapped.get(i), i == 0 ? 6 : 16, i == 0 ? 2 : 0));
    }

    private void layoutLines() {
        lines.clear();
        scroll = 0;
        switch (tab) {
            case OVERVIEW -> overview();
            case DIANA -> diana();
            case "Skills" -> skills();
            default -> sky2mTab(tab);
        }
        int h = 0;
        for (Line line : lines) h += line.gapBefore() + LINE;
        contentHeight = h;
    }

    private void overview() {
        heading(Component.literal("Progress").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), true);
        for (String t : TABS) {
            if (t.equals(OVERVIEW)) continue;
            int[] c = counts(t);
            int percent = c[1] == 0 ? 0 : Math.round(c[0] * 100f / c[1]);
            row(Component.literal(t + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(c[0] + "/" + c[1]).withStyle(c[0] == c[1] && c[1] > 0 ? ChatFormatting.GREEN : ChatFormatting.WHITE))
                .append(Component.literal(" (" + percent + "%)").withStyle(ChatFormatting.DARK_GRAY)));
        }
        heading(Component.literal("Stats").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD), false);
        for (Sky2MAchievements.Stat stat : Sky2MAchievements.Stat.values()) {
            row(Component.literal(stat.label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(number(stat, Sky2MAchievements.get(stat))).withStyle(ChatFormatting.WHITE)));
        }
    }

    /** One section per skill, each tier with its emblem character. */
    private void skills() {
        Map<String, List<Sky2MAchievements.Achievement>> bySkill = new LinkedHashMap<>();
        for (Sky2MAchievements.Achievement a : sky2m("Skills")) bySkill.computeIfAbsent(a.category().substring(Sky2MAchievements.SKILLS_PREFIX.length()), k -> new ArrayList<>()).add(a);
        boolean first = true;
        for (Map.Entry<String, List<Sky2MAchievements.Achievement>> e : bySkill.entrySet()) {
            int done = 0;
            for (Sky2MAchievements.Achievement a : e.getValue()) if (Sky2MAchievements.unlocked(a)) done++;
            Sky2MAchievements.Stat stat = e.getValue().get(0).stat();
            heading(Component.literal(e.getValue().get(0).symbol() + " " + e.getKey()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("  " + done + "/" + e.getValue().size() + "  (level " + Sky2MAchievements.get(stat) + ")").withStyle(ChatFormatting.GRAY)), first);
            first = false;
            for (Sky2MAchievements.Achievement a : e.getValue()) {
                boolean has = Sky2MAchievements.unlocked(a);
                row(Component.literal(has ? "✔ " : "✖ ").withStyle(has ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                    .append(Component.literal(a.symbol() + " " + a.name()).withStyle(has ? a.rarity().colour : ChatFormatting.GRAY))
                    .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(a.description()).withStyle(has ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY))
                    .append(Component.literal(" (" + a.rarity().label + ")").withStyle(a.rarity().colour)));
            }
        }
    }

    private void sky2mTab(String category) {
        List<Sky2MAchievements.Achievement> list = sky2m(category);
        int[] c = counts(category);
        heading(Component.literal(category).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
            .append(Component.literal("  " + c[0] + "/" + c[1]).withStyle(ChatFormatting.GRAY)), true);
        for (Sky2MAchievements.Achievement a : list) {
            boolean has = Sky2MAchievements.unlocked(a);
            long progress = Math.min(Sky2MAchievements.get(a.stat()), a.goal());
            MutableComponent line = Component.literal(has ? "✔ " : "✖ ").withStyle(has ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                .append(Component.literal(a.name()).withStyle(has ? a.rarity().colour : ChatFormatting.GRAY))
                .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(a.description()).withStyle(has ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY))
                .append(Component.literal(" (" + a.rarity().label + ")").withStyle(a.rarity().colour));
            if (!has && a.goal() > 1) line.append(Component.literal("  " + number(a.stat(), progress) + "/" + number(a.stat(), a.goal())).withStyle(ChatFormatting.AQUA));
            row(line);
        }
    }

    private void diana() {
        Map<String, List<DianaAchievements.Achievement>> byCategory = new LinkedHashMap<>();
        for (DianaAchievements.Achievement a : DianaAchievements.all()) byCategory.computeIfAbsent(a.category(), k -> new ArrayList<>()).add(a);
        boolean first = true;
        for (Map.Entry<String, List<DianaAchievements.Achievement>> e : byCategory.entrySet()) {
            int done = 0;
            for (DianaAchievements.Achievement a : e.getValue()) if (DianaAchievements.isUnlocked(a)) done++;
            heading(Component.literal(e.getKey()).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
                .append(Component.literal("  " + done + "/" + e.getValue().size()).withStyle(ChatFormatting.GRAY)), first);
            first = false;
            for (DianaAchievements.Achievement a : e.getValue()) {
                boolean has = DianaAchievements.isUnlocked(a);
                boolean secret = a.hidden() && !DianaAchievements.everUnlocked(a);
                MutableComponent line = Component.literal(has ? "✔ " : "✖ ").withStyle(has ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                    .append(Component.literal(secret ? "Secret Achievement" : a.name()).withStyle(has ? a.rarity().colour : ChatFormatting.GRAY))
                    .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY));
                if (secret) line.append(Component.literal(a.description()).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.OBFUSCATED));
                else line.append(Component.literal(a.description()).withStyle(has ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
                line.append(Component.literal(" (" + a.rarity().label + ")").withStyle(a.rarity().colour));
                row(line);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private int tabsWidth() {
        int w = 0;
        for (String t : TABS) w += font.width(t) + 12 + 2;
        return w - 2;
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
        int[] all = totalCounts();
        g.centeredText(font, Component.literal("Achievements").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(all[0] + " / " + all[1] + " unlocked").withStyle(ChatFormatting.GRAY), width / 2, 21, 0xFFAAAAAA);

        int x = (width - tabsWidth()) / 2, y = 34;
        for (String t : TABS) {
            int w = font.width(t) + 12;
            boolean selected = t.equals(tab), hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_H;
            g.fill(x, y, x + w, y + TAB_H, selected ? 0xE0204060 : hover ? 0xC0283038 : PANEL);
            g.fill(x, y + TAB_H - 1, x + w, y + TAB_H, selected ? 0xFF33AAFF : PANEL_BORDER);
            g.text(font, t, x + 6, y + 3, selected ? 0xFFFFFFFF : 0xFFAAAAAA, false);
            x += w + 2;
        }

        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.outline(panelX, panelY, panelW, panelH, PANEL_BORDER);
        scroll = Mth.clamp(scroll, 0, maxScroll());
        g.enableScissor(panelX + 1, viewTop(), panelX + panelW - 1, viewBottom());
        int ly = viewTop() - (int) scroll;
        for (Line line : lines) {
            ly += line.gapBefore();
            if (ly + LINE >= viewTop() && ly <= viewBottom()) g.text(font, line.text(), panelX + PAD + line.x(), ly, 0xFFFFFFFF, true);
            ly += LINE;
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = (width - tabsWidth()) / 2, y = 34;
        if (event.y() >= y && event.y() < y + TAB_H) {
            for (String t : TABS) {
                int w = font.width(t) + 12;
                if (event.x() >= x && event.x() < x + w) {
                    if (!t.equals(tab)) {
                        tab = t;
                        layoutLines();
                    }
                    return true;
                }
                x += w + 2;
            }
        }
        return super.mouseClicked(event, doubleClick);
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
