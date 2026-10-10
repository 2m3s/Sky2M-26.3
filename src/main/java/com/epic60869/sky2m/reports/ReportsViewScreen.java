package com.epic60869.sky2m.reports;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * /s2 viewboth, /s2 viewreport and /s2 viewsuggest (owner only): every report as a row with its title and who sent
 * it. Click a row to open it and read the description; Delete removes it once you've dealt with it.
 */
public final class ReportsViewScreen extends Screen {
    public enum Filter { ALL, BUGS, SUGGESTIONS }

    private static final int ROW = 22;
    private static final int LINE = 10;

    private final Filter filter;
    private List<Reports.Report> reports = List.of();
    private final Set<String> open = new HashSet<>();
    private String status = "Loading...";
    private double scroll;
    private int listX, listY, listW, listH;

    public ReportsViewScreen(Filter filter) {
        super(Component.literal(switch (filter) {
            case ALL -> "Bug Reports, Suggestions & Feedback";
            case BUGS -> "Bug Reports";
            case SUGGESTIONS -> "Suggestions & Feedback";
        }));
        this.filter = filter;
        load();
    }

    private void load() {
        status = "Loading...";
        Reports.fetchAll().thenAccept(list -> minecraft().execute(() -> {
            reports = list.stream().filter(r -> switch (filter) {
                case ALL -> true;
                case BUGS -> r.type() == Reports.Type.BUG;
                case SUGGESTIONS -> r.type() != Reports.Type.BUG;
            }).toList();
            status = reports.isEmpty() ? "Nothing here. All caught up!" : "";
        })).exceptionally(e -> {
            minecraft().execute(() -> status = "Couldn't load them: " + Reports.rootMessage(e));
            return null;
        });
    }

    private static net.minecraft.client.Minecraft minecraft() {
        return net.minecraft.client.Minecraft.getInstance();
    }

    @Override
    protected void init() {
        listW = Math.min(560, width - 40);
        listX = (width - listW) / 2;
        listY = 40;
        listH = height - listY - 40;
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> load()).bounds(listX, height - 30, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(listX + listW - 80, height - 30, 80, 20).build());
    }

    /** A report's rows on screen: its header, and when open, its description lines. */
    private record Entry(Reports.Report report, int y, int height, List<FormattedCharSequence> lines) {}

    private List<Entry> layout() {
        List<Entry> out = new ArrayList<>();
        int y = listY - (int) scroll;
        for (Reports.Report r : reports) {
            List<FormattedCharSequence> lines = open.contains(r.id())
                ? font.split(Component.literal(r.description().isBlank() ? "(no description)" : r.description()), listW - 20)
                : List.of();
            int h = ROW + (lines.isEmpty() ? 0 : lines.size() * LINE + 6);
            out.add(new Entry(r, y, h, lines));
            y += h + 2;
        }
        return out;
    }

    private int contentHeight() {
        int h = 0;
        for (Entry e : layout()) h += e.height() + 2;
        return h;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, getTitle().copy().withStyle(ChatFormatting.BOLD), width / 2, 16, 0xFFFFFFFF);
        g.centeredText(font, Component.literal(reports.size() + " waiting  •  click one to read it").withStyle(ChatFormatting.GRAY), width / 2, 28, 0xFFAAAAAA);
        if (!status.isEmpty()) {
            g.centeredText(font, Component.literal(status), width / 2, listY + 20, 0xFFAAAAAA);
            return;
        }
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight() - listH));
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        SimpleDateFormat date = new SimpleDateFormat("d MMM HH:mm");
        for (Entry e : layout()) {
            if (e.y() + e.height() < listY || e.y() > listY + listH) continue;
            Reports.Report r = e.report();
            boolean hovered = mouseX >= listX && mouseX < listX + listW - 60 && mouseY >= e.y() && mouseY < e.y() + ROW;
            g.fill(listX, e.y(), listX + listW, e.y() + e.height(), hovered ? 0xC0262A33 : 0xC0151820);
            int tagColour = switch (r.type()) {
                case BUG -> 0xFFFF5555;
                case SUGGESTION -> 0xFF55FFFF;
                case FEEDBACK -> 0xFFFF55FF;
            };
            String tag = switch (r.type()) {
                case BUG -> "BUG";
                case SUGGESTION -> "IDEA";
                case FEEDBACK -> "FEEDBACK";
            };
            g.text(font, Component.literal(tag).withStyle(ChatFormatting.BOLD), listX + 6, e.y() + 7, tagColour, true);
            int tx = listX + 6 + font.width(Component.literal("FEEDBACK").withStyle(ChatFormatting.BOLD)) + 8;
            String title = font.plainSubstrByWidth(r.title(), listW - (tx - listX) - 190);
            g.text(font, Component.literal(title), tx, e.y() + 7, 0xFFFFFFFF, true);
            String by = "by " + r.username() + (r.createdAt() > 0 ? "  " + date.format(new Date(r.createdAt())) : "");
            g.text(font, Component.literal(by), listX + listW - 70 - font.width(by), e.y() + 7, 0xFFAAAAAA, false);
            // Delete button.
            int bx = listX + listW - 56;
            boolean overDelete = mouseX >= bx && mouseX < bx + 50 && mouseY >= e.y() + 3 && mouseY < e.y() + ROW - 3;
            g.fill(bx, e.y() + 3, bx + 50, e.y() + ROW - 3, overDelete ? 0xFFB33A3A : 0xFF7A2626);
            g.centeredText(font, Component.literal("Delete"), bx + 25, e.y() + 7, 0xFFFFFFFF);
            int ly = e.y() + ROW + 2;
            for (FormattedCharSequence line : e.lines()) {
                g.text(font, line, listX + 10, ly, 0xFFD0D0D0, false);
                ly += LINE;
            }
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (status.isEmpty() && click.button() == InputConstants.MOUSE_BUTTON_LEFT && click.y() >= listY && click.y() < listY + listH) {
            for (Entry e : layout()) {
                if (click.y() < e.y() || click.y() >= e.y() + ROW) continue;
                String id = e.report().id();
                int bx = listX + listW - 56;
                if (click.x() >= bx && click.x() < bx + 50) {
                    Reports.delete(id).thenAccept(ok -> minecraft().execute(() -> {
                        if (ok) {
                            reports = reports.stream().filter(r -> !r.id().equals(id)).toList();
                            if (reports.isEmpty()) status = "Nothing here. All caught up!";
                        } else {
                            status = "Couldn't delete it; press Refresh.";
                        }
                    })).exceptionally(err -> {
                        minecraft().execute(() -> status = "Couldn't delete it: " + Reports.rootMessage(err));
                        return null;
                    });
                } else if (click.x() >= listX && click.x() < listX + listW) {
                    if (!open.remove(id)) open.add(id);
                }
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Mth.clamp(scroll - vertical * 20, 0, Math.max(0, contentHeight() - listH));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
