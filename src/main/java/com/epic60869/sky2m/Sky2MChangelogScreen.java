package com.epic60869.sky2m;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 log: the changelog in a window. The arrows at the top switch between versions (it opens on the one you have
 * installed), and the list scrolls with the mouse wheel or by dragging the scroll bar.
 */
public final class Sky2MChangelogScreen extends Screen {
    private static final int PANEL = 0xC0101216;
    private static final int PANEL_BORDER = 0xFF2C313A;
    private static final int LINE = 11;
    private static final int PAD = 10;

    private record Line(FormattedCharSequence text, int x, int colour, int gapBefore) {}

    private final List<Sky2MChangelog.Version> versions;
    private final String installed;
    private int index;
    private final List<Line> lines = new ArrayList<>();
    private int contentHeight;
    private double scroll;
    private boolean draggingBar;

    private int panelX, panelY, panelW, panelH;
    private Button older;
    private Button newer;

    public Sky2MChangelogScreen(List<Sky2MChangelog.Version> versions, int index, String installed) {
        super(Component.literal("Sky2M Changelog"));
        this.versions = versions;
        this.index = Mth.clamp(index, 0, Math.max(0, versions.size() - 1));
        this.installed = installed;
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 32, 460);
        panelX = (width - panelW) / 2;
        panelY = 44;
        panelH = Math.max(80, height - panelY - 36);

        // The list is newest first, so "older" moves down it.
        older = addRenderableWidget(Button.builder(Component.literal("◀ Older"), b -> show(index + 1))
            .bounds(panelX, 16, 70, 20).build());
        newer = addRenderableWidget(Button.builder(Component.literal("Newer ▶"), b -> show(index - 1))
            .bounds(panelX + panelW - 70, 16, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(width / 2 - 50, height - 28, 100, 20).build());
        layoutLines();
    }

    private void show(int newIndex) {
        if (newIndex < 0 || newIndex >= versions.size()) return;
        index = newIndex;
        scroll = 0;
        layoutLines();
    }

    /** Wraps the version's entries to the panel width. */
    private void layoutLines() {
        lines.clear();
        older.active = index + 1 < versions.size();
        newer.active = index > 0;
        if (versions.isEmpty()) {
            contentHeight = 0;
            return;
        }
        int textW = panelW - PAD * 2 - 8;
        Sky2MChangelog.Version version = versions.get(index);
        boolean first = true;
        for (Sky2MChangelog.Section section : version.sections()) {
            int colour = sectionColour(section.title());
            Component heading = Component.literal(section.title() + " (" + section.entries().size() + ")")
                .withStyle(Style.EMPTY.withBold(true));
            lines.add(new Line(heading.getVisualOrderText(), 0, colour, first ? 0 : 8));
            first = false;
            for (String entry : section.entries()) {
                List<FormattedCharSequence> wrapped = font.split(FormattedText.of(entry), textW - 10);
                for (int i = 0; i < wrapped.size(); i++) {
                    lines.add(new Line(wrapped.get(i), 10, 0xFFE0E0E0, i == 0 ? 3 : 0));
                }
            }
        }
        int h = 0;
        for (Line line : lines) h += line.gapBefore() + LINE;
        contentHeight = h;
    }

    private static int sectionColour(String title) {
        return switch (title.toLowerCase(Locale.ROOT)) {
            case "added" -> 0xFF55FF55;
            case "fixed" -> 0xFF55FFFF;
            case "removed" -> 0xFFFF5555;
            default -> 0xFFFFFF55;
        };
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
        // Version title between the arrows.
        if (!versions.isEmpty()) {
            Sky2MChangelog.Version version = versions.get(index);
            Component title = Component.literal("Sky2M " + version.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
            g.centeredText(font, title, width / 2, 17, 0xFFFFFFFF);
            String sub = version.date() + (version.name().equalsIgnoreCase(installed) ? (version.date().isEmpty() ? "" : "  •  ") + "installed" : "");
            g.centeredText(font, Component.literal(sub).withStyle(ChatFormatting.GRAY), width / 2, 29, 0xFFAAAAAA);
        }

        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.outline(panelX, panelY, panelW, panelH, PANEL_BORDER);

        if (versions.isEmpty()) {
            g.centeredText(font, Component.literal("The changelog isn't available in this build.").withStyle(ChatFormatting.RED),
                width / 2, panelY + panelH / 2 - 4, 0xFFFFFFFF);
        } else {
            scroll = Mth.clamp(scroll, 0, maxScroll());
            g.enableScissor(panelX + 1, viewTop(), panelX + panelW - 1, viewBottom());
            int y = viewTop() - (int) scroll;
            int x = panelX + PAD;
            for (Line line : lines) {
                y += line.gapBefore();
                if (y + LINE >= viewTop() && y <= viewBottom()) {
                    if (line.x() > 0 && line.gapBefore() > 0) {
                        g.text(font, Component.literal("•"), x + 2, y, 0xFF888888, false);
                    }
                    g.text(font, line.text(), x + line.x(), y, line.colour(), true);
                }
                y += LINE;
            }
            g.disableScissor();
            drawScrollBar(g);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private int[] barBounds() {
        int trackTop = viewTop();
        int trackH = viewBottom() - viewTop();
        int barH = Math.max(16, trackH * trackH / Math.max(trackH, contentHeight));
        int barY = trackTop + (int) ((trackH - barH) * (maxScroll() == 0 ? 0 : scroll / maxScroll()));
        return new int[]{panelX + panelW - 6, barY, barH};
    }

    private void drawScrollBar(GuiGraphicsExtractor g) {
        if (maxScroll() <= 0) return;
        int[] bar = barBounds();
        g.fill(bar[0], viewTop(), bar[0] + 3, viewBottom(), 0x40FFFFFF);
        g.fill(bar[0], bar[1], bar[0] + 3, bar[1] + bar[2], 0xFFAAAAAA);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Mth.clamp(scroll - vertical * LINE * 3, 0, maxScroll());
        return true;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
        if (maxScroll() > 0 && click.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int[] bar = barBounds();
            if (click.x() >= bar[0] - 2 && click.x() <= bar[0] + 5 && click.y() >= viewTop() && click.y() <= viewBottom()) {
                draggingBar = true;
                dragTo(click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent click, double dx, double dy) {
        if (draggingBar) {
            dragTo(click.y());
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent click) {
        draggingBar = false;
        return super.mouseReleased(click);
    }

    private void dragTo(double mouseY) {
        int[] bar = barBounds();
        double track = (viewBottom() - viewTop()) - bar[2];
        if (track <= 0) return;
        scroll = Mth.clamp((mouseY - viewTop() - bar[2] / 2.0) / track * maxScroll(), 0, maxScroll());
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Left / right arrows switch versions.
        if (event.key() == InputConstants.KEY_LEFT) {
            show(index + 1);
            return true;
        }
        if (event.key() == InputConstants.KEY_RIGHT) {
            show(index - 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
