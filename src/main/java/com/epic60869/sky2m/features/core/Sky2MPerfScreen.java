package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * /s2 perf: which Sky2M HUDs, in-world renderers and chat listeners take the most time, from {@link Sky2MPerf}, so you
 * can turn off what costs you frames. Laid out like /s2 collections; click a HUD to find its settings.
 */
public final class Sky2MPerfScreen extends Screen {
    private static final int ROW = 13;
    private static final int GREY = 0xFF7A8290;
    private static final String[] HEADERS = {"Feature", "Type", "Per frame", "Share", "Calls/s", "Slowest"};

    private int scroll;

    public Sky2MPerfScreen() {
        super(Component.literal("Sky2M Performance"));
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("perf")
                    .executes(c -> Compat.queueOpenScreen(new Sky2MPerfScreen()))));
            }
        });
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int tableTop() { return top() + 48; }
    private int tableBottom() { return top() + panelHeight() - 22; }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(left() + panelWidth() - 8 - 60, top() + 22, 60, 20).build());
    }

    private int[] columns() {
        int w = panelWidth() - 16, x = left() + 8;
        return new int[]{x, x + w * 46 / 100, x + w * 58 / 100, x + w * 71 / 100, x + w * 81 / 100, x + w * 91 / 100};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        g.fill(left(), top(), left() + panelWidth(), top() + panelHeight(), 0xFF181B21);
        g.fill(left(), top(), left() + panelWidth(), top() + 2, 0xFF55FFFF);
        g.text(font, Component.literal("Performance").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), left() + 8, top() + 8, 0xFFFFFFFF, true);

        List<Sky2MPerf.Stat> stats = Sky2MPerf.stats();
        long frames = Math.max(1, Sky2MPerf.frames());
        double seconds = Math.max(0.1, Sky2MPerf.seconds());
        double fps = frames / seconds;
        long total = 0;
        for (Sky2MPerf.Stat s : stats) total += s.nanos();
        double frameMs = 1000.0 / Math.max(1, fps);
        double sky2mMs = total / 1e6 / frames;
        String summary = String.format(Locale.US, "%.0f FPS  ·  Sky2M (measured) %.2f ms a frame, %.1f%% of each frame", fps, sky2mMs, 100 * sky2mMs / frameMs);
        g.text(font, summary, left() + 8, top() + 28, 0xFFB8C0CC, false);

        int[] c = columns();
        int y = tableTop();
        for (int i = 0; i < HEADERS.length; i++) g.text(font, HEADERS[i], c[i], y, 0xFF55FFFF, false);
        g.fill(left() + 6, y + 10, left() + panelWidth() - 6, y + 11, 0xFF3A3F48);

        if (stats.isEmpty()) {
            g.text(font, "Measuring... the first numbers come after 5 seconds in a world.", c[0], y + 18, GREY, false);
        } else {
            int rowsTop = y + 16;
            int visible = Math.max(1, (tableBottom() - rowsTop) / ROW);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, stats.size() - visible)));
            for (int i = scroll; i < stats.size() && i < scroll + visible; i++) {
                Sky2MPerf.Stat s = stats.get(i);
                int ry = rowsTop + (i - scroll) * ROW;
                boolean hover = mouseY >= ry - 2 && mouseY < ry + ROW - 2 && mouseX >= left() && mouseX < left() + panelWidth();
                if (hover && s.kind() == Sky2MPerf.Kind.HUD) g.fill(left() + 6, ry - 2, left() + panelWidth() - 6, ry + ROW - 2, 0xFF22262E);
                double perFrameMs = s.nanos() / 1e6 / frames;
                double share = 100 * perFrameMs / frameMs;
                int colour = share >= 5 ? 0xFFFF5555 : share >= 1 ? 0xFFFFAA00 : 0xFFFFFFFF;
                g.text(font, font.plainSubstrByWidth(s.name(), c[1] - c[0] - 6), c[0], ry, colour, false);
                g.text(font, kind(s.kind()), c[1], ry, GREY, false);
                g.text(font, time(perFrameMs), c[2], ry, colour, false);
                g.text(font, String.format(Locale.US, "%.2f%%", share), c[3], ry, colour, false);
                g.text(font, String.format(Locale.US, "%.0f", s.calls() / seconds), c[4], ry, 0xFFB8C0CC, false);
                g.text(font, time(s.worstNanos() / 1e6), c[5], ry, 0xFFB8C0CC, false);
            }
        }
        g.text(font, "Updates every 5 seconds. Red is 5%+ of a frame, orange 1%+. Click a HUD to find its settings. Tick-time features aren't measured.",
            left() + 8, tableBottom() + 6, GREY, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private static String kind(Sky2MPerf.Kind kind) {
        return switch (kind) {
            case HUD -> "HUD";
            case WORLD -> "In world";
            case CHAT -> "Chat";
        };
    }

    private static String time(double ms) {
        return ms >= 1 ? String.format(Locale.US, "%.2f ms", ms) : String.format(Locale.US, "%.0f µs", ms * 1000);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int rowsTop = tableTop() + 16;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || event.y() < rowsTop - 2 || event.y() >= tableBottom()) return false;
        List<Sky2MPerf.Stat> stats = Sky2MPerf.stats();
        int index = scroll + (int) ((event.y() - rowsTop + 2) / ROW);
        if (index < 0 || index >= stats.size() || stats.get(index).kind() != Sky2MPerf.Kind.HUD) return false;
        Sky2MConfig.openGui(stats.get(index).name());
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
