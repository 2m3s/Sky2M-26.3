package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.Sky2MGuiStyle;
import com.epic60869.sky2m.custom.RepoItems;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editing one item of a profit tracker by hand: set how many there are, or remove it. Opened by clicking an item in
 * the tracker shown over a menu, or in /s2 session's drops. The change is applied to every list in {@code targets}
 * as the same difference (so Session, Today and Total stay in step), and Remove takes the item out of all of them.
 * Closing goes back to the screen it came from.
 *
 * <p>Drawn like the /s2 settings ({@link Sky2MGuiStyle}), as /s2 session is: a MoulConfig panel with a header bar,
 * the settings' text field and MoulConfig buttons, whose click areas are recorded where they're drawn.
 */
final class ProfitItemEditScreen extends Screen {
    private static final int W = 240, H = 132;

    private final Screen parent;
    private final String id;
    private final long amount;
    private final List<ProfitTracker.Stats> targets;
    private final String note;
    private final Runnable changed;
    private EditBox box;
    private String error = "";

    /** A button drawn this frame. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();

    /** {@code targets}' first entry is the list the player clicked in; {@code amount} is its count there. */
    ProfitItemEditScreen(Screen parent, String id, long amount, List<ProfitTracker.Stats> targets, String note, Runnable changed) {
        super(Component.literal("Edit Tracked Item"));
        this.parent = parent;
        this.id = id;
        this.amount = amount;
        this.targets = targets;
        this.note = note;
        this.changed = changed;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        String typed = box == null ? Long.toString(amount) : box.getValue();
        box = new EditBox(font, 0, 0, 80, 10, Component.literal("Amount"));
        box.setMaxLength(13);
        box.setValue(typed);
        Sky2MGuiStyle.placeSearch(box, left() + 60, top() + 55, W - 70);
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    private void save() {
        long wanted;
        try {
            wanted = Long.parseLong(box.getValue().replace(",", "").trim());
        } catch (NumberFormatException e) {
            error = "Type a number (0 removes it).";
            return;
        }
        long difference = wanted - amount;
        for (int i = 0; i < targets.size(); i++) {
            ProfitTracker.Stats stats = targets.get(i);
            if (stats == null) continue;
            long next = i == 0 ? wanted : Math.max(0, stats.items.getOrDefault(id, 0L) + difference);
            if (next <= 0) stats.items.remove(id);
            else stats.items.put(id, next);
        }
        changed.run();
        onClose();
    }

    private void remove() {
        for (ProfitTracker.Stats stats : targets) if (stats != null) stats.items.remove(id);
        changed.run();
        onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
            save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = left(), y = top();
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, x, y, W, H, true);
        Sky2MGuiStyle.header(g, font, x + 4, y + 4, W - 8, Sky2MGuiStyle.title("Edit Item"));
        g.item(RepoItems.itemStack(id), x + 10, y + 30);
        g.text(font, font.plainSubstrByWidth(ProfitTrackerHud.name(id), W - 44), x + 32, y + 30, Sky2MGuiStyle.TEXT, true);
        g.text(font, "Tracked: " + String.format(Locale.US, "%,d", amount), x + 32, y + 41, Sky2MGuiStyle.DESCRIPTION, false);
        g.text(font, "Amount:", x + 10, y + 59, Sky2MGuiStyle.TEXT, false);
        Sky2MGuiStyle.field(g, box);
        String line = error.isEmpty() ? note : error;
        g.text(font, font.plainSubstrByWidth(line, W - 20), x + 10, y + 80, error.isEmpty() ? 0xFF808080 : 0xFFFF5555, false);
        hits.clear();
        int by = y + H - 24, bw = (W - 28) / 3;
        button(g, "Save", x + 10, by, bw, mouseX, mouseY, this::save);
        button(g, "Remove", x + 10 + bw + 4, by, bw, mouseX, mouseY, this::remove);
        button(g, Component.translatable("gui.cancel").getString(), x + 10 + (bw + 4) * 2, by, bw, mouseX, mouseY, this::onClose);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void button(GuiGraphicsExtractor g, String text, int x, int y, int w, int mouseX, int mouseY, Runnable action) {
        Hit hit = new Hit(x, y, x + w, y + 16, action);
        hits.add(hit);
        Sky2MGuiStyle.button(g, font, text, x, y, w, 16, inside(hit, mouseX, mouseY));
    }

    private static boolean inside(Hit hit, double x, double y) {
        return x >= hit.x0() && x < hit.x1() && y >= hit.y0() && y < hit.y1();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return false;
        // The field's border, which the borderless EditBox inside it doesn't cover.
        if (Sky2MGuiStyle.inField(box, event.x(), event.y())) {
            setFocused(box);
            return true;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (inside(hit, event.x(), event.y())) {
                Sky2MGuiStyle.click();
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
