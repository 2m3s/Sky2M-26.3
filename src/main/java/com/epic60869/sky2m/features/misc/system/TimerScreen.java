package com.epic60869.sky2m.features.misc.system;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /s2 timer: pick Timer or Stopwatch, type a time or press a preset, and Start. Starting closes the screen, so you see the
 * HUD; while one is on this screen has its clock, Pause and Stop.
 */
public class TimerScreen extends Screen {
    private static final int PANEL_W = 240, PANEL_H = 148;
    private static final String[] PRESETS = {"1m", "5m", "10m", "30m"};

    /** Kept while the game runs. */
    private static String timeText = "5m";
    private static Sky2MTimer.Mode picked = Sky2MTimer.Mode.TIMER;

    private final Screen parent;
    private EditBox timeBox;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public TimerScreen(Screen parent) {
        super(Component.literal("Timer"));
        this.parent = parent;
    }

    private int panelX() {
        return (width - Math.min(PANEL_W, width - 20)) / 2;
    }

    private int panelW() {
        return Math.min(PANEL_W, width - 20);
    }

    private int panelY() {
        return Math.max(6, (height - PANEL_H) / 2);
    }

    @Override
    protected void init() {
        timeBox = new EditBox(font, 0, 0, 10, 10, Component.empty());
        timeBox.setMaxLength(16);
        Sky2MGuiStyle.placeSearch(timeBox, panelX() + 38, panelY() + 76, 64);
        timeBox.setValue(timeText);
        timeBox.setResponder(s -> timeText = s);
        addRenderableWidget(timeBox);
        syncBox();
    }

    /** The time box is only there while you can use it. */
    private void syncBox() {
        boolean show = !Sky2MTimer.isOn() && picked == Sky2MTimer.Mode.TIMER;
        timeBox.setVisible(show);
        if (!show && getFocused() == timeBox) setFocused(null);
    }

    @Override
    public void tick() {
        super.tick();
        // A countdown can end while this is open.
        syncBox();
    }

    private void pick(Sky2MTimer.Mode mode) {
        picked = mode;
        syncBox();
    }

    private void start() {
        long now = System.currentTimeMillis();
        if (picked == Sky2MTimer.Mode.TIMER) Sky2MTimer.startTimer(Sky2MTimer.parseMs(timeText), now);
        else Sky2MTimer.startStopwatch(now);
        onClose();
    }

    private void stop() {
        Sky2MTimer.stop();
        syncBox();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        boolean on = Sky2MTimer.isOn();
        // While one is on, its tab is the one showing.
        Sky2MTimer.Mode shown = on ? Sky2MTimer.mode() : picked;
        Sky2MGuiStyle.backdrop(g, width, height);
        int x = panelX(), y = panelY(), w = panelW();
        Sky2MGuiStyle.panel(g, x, y, w, PANEL_H, true);
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("Timer"));

        tab(g, "Timer", Sky2MTimer.Mode.TIMER, shown, on, x + 10, y + 30, mouseX, mouseY);
        tab(g, "Stopwatch", Sky2MTimer.Mode.STOPWATCH, shown, on, x + 10 + font.width("Timer") + 14, y + 30, mouseX, mouseY);

        // The clock, twice the size.
        Sky2MGuiStyle.inset(g, x + 8, y + 44, x + w - 8, y + 72);
        long typed = Sky2MTimer.parseMs(timeText);
        String clock = on ? Sky2MTimer.clock(System.currentTimeMillis())
            : shown == Sky2MTimer.Mode.STOPWATCH ? "0:00" : typed > 0 ? Sky2MTimer.format(typed) : "-:--";
        g.pose().pushMatrix();
        g.pose().translate((float) (x + w / 2), (float) (y + 50));
        g.pose().scale(2f, 2f);
        g.text(font, clock, -font.width(clock) / 2, 0, on && !Sky2MTimer.isPaused() ? 0xFFFFFFFF : Sky2MGuiStyle.TEXT_DIM, false);
        g.pose().popMatrix();

        boolean canStart = true;
        String hint;
        if (on) {
            hint = (Sky2MTimer.isPaused() ? "Paused. " : "") + "On your HUD. Move it in /s2 gui.";
        } else if (shown == Sky2MTimer.Mode.STOPWATCH) {
            hint = "Counts up until you stop it.";
        } else {
            canStart = typed > 0;
            hint = canStart ? "Like 5 (minutes), 90s, 1h 30m or 1:30." : "§cType a time up to 100 hours, like 5m or 1:30.";
            g.text(font, "Time", x + 10, y + 80, Sky2MGuiStyle.TEXT, false);
            Sky2MGuiStyle.field(g, timeBox);
            int px = x + 110, pw = Math.max(20, (x + w - 8 - px - 3 * 3) / PRESETS.length);
            for (String preset : PRESETS) {
                button(g, preset, px, y + 76, pw, 16, mouseX, mouseY, false, true, () -> {
                    timeBox.setValue(preset);
                    timeText = preset;
                });
                px += pw + 3;
            }
        }
        g.text(font, Component.literal(font.plainSubstrByWidth(hint, w - 20)), x + 10, y + 98, Sky2MGuiStyle.MUTED, false);

        // The buttons, evenly along the bottom.
        int by = y + PANEL_H - 26, bh = 18;
        if (on) {
            int bw = (w - 16 - 2 * 6) / 3;
            long now = System.currentTimeMillis();
            button(g, Sky2MTimer.isPaused() ? "Resume" : "Pause", x + 8, by, bw, bh, mouseX, mouseY, true, true, () -> Sky2MTimer.togglePause(now));
            button(g, "Stop", x + 8 + bw + 6, by, bw, bh, mouseX, mouseY, false, true, this::stop);
            button(g, "Done", x + 8 + 2 * (bw + 6), by, bw, bh, mouseX, mouseY, false, true, this::onClose);
        } else {
            int bw = (w - 16 - 6) / 2;
            button(g, "Start", x + 8, by, bw, bh, mouseX, mouseY, true, canStart, this::start);
            button(g, "Done", x + 8 + bw + 6, by, bw, bh, mouseX, mouseY, false, true, this::onClose);
        }

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void tab(GuiGraphicsExtractor g, String label, Sky2MTimer.Mode mode, Sky2MTimer.Mode shown, boolean on, int tx, int ty, int mouseX, int mouseY) {
        int tw = font.width(label);
        boolean hover = !on && mouseX >= tx - 3 && mouseX < tx + tw + 3 && mouseY >= ty - 3 && mouseY < ty + 11;
        Sky2MGuiStyle.category(g, font, label, tx, ty, mode == shown, hover);
        // Can't change over while one is on: Stop it first.
        if (!on) hits.add(new Hit(tx - 3, ty - 3, tw + 6, 14, () -> pick(mode)));
    }

    private void button(GuiGraphicsExtractor g, String label, int bx, int by, int bw, int bh, int mouseX, int mouseY, boolean chosen, boolean enabled, Runnable action) {
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh;
        Sky2MGuiStyle.button(g, font, label, bx, by, bw, bh, hover, chosen, enabled);
        if (enabled) hits.add(new Hit(bx, by, bw, bh, action));
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (timeBox.isVisible() && Sky2MGuiStyle.inField(timeBox, event.x(), event.y())) {
                setFocused(timeBox);
                return super.mouseClicked(event, doubleClick) || true;
            }
            for (Hit hit : List.copyOf(hits)) {
                if (event.x() >= hit.x && event.x() < hit.x + hit.w && event.y() >= hit.y && event.y() < hit.y + hit.h) {
                    Sky2MGuiStyle.click();
                    hit.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
