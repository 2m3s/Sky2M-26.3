package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.FeatureConfigs;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editing one item of a profit tracker by hand: set how many there are, or remove it, and which price it counts at.
 * Opened by clicking an item in the tracker shown over a menu, or in /s2 session's drops. The amount is applied to every
 * list in {@code targets} as the same difference (so Session, Today and Total stay in step), and Remove takes the item
 * out of all of them. The price source (the tracker's Default, NPC Sell, Insta-Sell, Sell Order, Auction or a Custom
 * value, only those with a price for the item) is the item's own in every tracker ({@link ProfitItemPrices}). Closing
 * goes back to the screen it came from.
 *
 * <p>Drawn like the /s2 settings ({@link Sky2MGuiStyle}), as /s2 session is: a MoulConfig panel with a header bar,
 * the settings' text field and MoulConfig buttons, whose click areas are recorded where they're drawn.
 */
final class ProfitItemEditScreen extends Screen {
    private static final int W = 240, H = 176;

    private final Screen parent;
    private final String id;
    private final long amount;
    private final List<ProfitTracker.Stats> targets;
    /** The tracker's Price Source, which Default means. */
    private final FeatureConfigs.ProfitPriceSource fallback;
    private final String note;
    private final Runnable changed;
    private EditBox box;
    private EditBox customBox;
    private ProfitItemPrices.Source chosen;
    private String error = "";

    /** A button drawn this frame; {@code right} is what right-clicking it does, or null. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action, Runnable right) {}

    private final List<Hit> hits = new ArrayList<>();

    /** {@code targets}' first entry is the list the player clicked in; {@code amount} is its count there. */
    ProfitItemEditScreen(Screen parent, String id, long amount, List<ProfitTracker.Stats> targets,
                         FeatureConfigs.ProfitPriceSource fallback, String note, Runnable changed) {
        super(Component.literal("Edit Tracked Item"));
        this.parent = parent;
        this.id = id;
        this.amount = amount;
        this.targets = targets;
        this.fallback = fallback == null ? FeatureConfigs.ProfitPriceSource.INSTANT_SELL : fallback;
        this.note = note;
        this.changed = changed;
        this.chosen = ProfitItemPrices.source(id);
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

        double custom = ProfitItemPrices.custom(id);
        String typedCustom = customBox == null ? custom > 0 ? plain(custom) : "" : customBox.getValue();
        customBox = new EditBox(font, 0, 0, 80, 10, Component.literal("Custom Price"));
        customBox.setMaxLength(16);
        customBox.setValue(typedCustom);
        Sky2MGuiStyle.placeSearch(customBox, left() + 60, top() + 95, W - 70);
        addRenderableWidget(customBox);
        setInitialFocus(box);
    }

    /** The sources to cycle through here: those with a price for the item, and Custom (typed below) always. */
    private List<ProfitItemPrices.Source> sources() {
        List<ProfitItemPrices.Source> out = new ArrayList<>(ProfitItemPrices.available(id, fallback));
        if (!out.contains(ProfitItemPrices.Source.CUSTOM)) out.add(ProfitItemPrices.Source.CUSTOM);
        return out;
    }

    private void cycle(int step) {
        List<ProfitItemPrices.Source> sources = sources();
        int at = sources.indexOf(chosen);
        chosen = at < 0 ? sources.getFirst() : sources.get(Math.floorMod(at + step, sources.size()));
        error = "";
        if (chosen == ProfitItemPrices.Source.CUSTOM) setFocused(customBox);
    }

    /** "1500000", "1.5m", "200k" or "2,000" as coins; -1 if it isn't a number, 0 if blank. */
    private static double coins(String text) {
        String t = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return 0;
        double scale = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            scale = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1).trim();
        }
        try {
            double value = Double.parseDouble(t) * scale;
            return Double.isFinite(value) && value >= 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private void save() {
        long wanted;
        try {
            wanted = Long.parseLong(box.getValue().replace(",", "").trim());
        } catch (NumberFormatException e) {
            error = "Type a number (0 removes it).";
            return;
        }
        double custom = coins(customBox.getValue());
        if (custom < 0) {
            error = "Custom price: type coins, like 1.5m.";
            return;
        }
        // Typing a new custom price picks Custom; Custom with no price goes back to Default.
        ProfitItemPrices.Source source = chosen;
        double before = ProfitItemPrices.custom(id);
        if (custom > 0 && Math.abs(custom - before) > 1e-9) source = ProfitItemPrices.Source.CUSTOM;
        if (source == ProfitItemPrices.Source.CUSTOM && custom <= 0) {
            error = "Type a custom price, or pick another source.";
            return;
        }
        ProfitItemPrices.set(id, source, custom);

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

    /** The coins per item the chosen source gives now (a typed custom price for Custom), or 0. */
    private double shownPrice() {
        if (chosen == ProfitItemPrices.Source.CUSTOM) return Math.max(0, coins(customBox.getValue()));
        return ProfitItemPrices.priceBy(id, chosen, fallback);
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
        hits.clear();
        g.text(font, "Price:", x + 10, y + 79, Sky2MGuiStyle.TEXT, false);
        String label = chosen == ProfitItemPrices.Source.DEFAULT ? "Default (" + fallback + ")" : chosen.toString();
        button(g, "< " + label + " >", x + 60, y + 74, W - 70, mouseX, mouseY, () -> cycle(1), () -> cycle(-1));
        g.text(font, "Custom:", x + 10, y + 99, Sky2MGuiStyle.TEXT, false);
        Sky2MGuiStyle.field(g, customBox);
        double each = shownPrice();
        String price = each > 0 ? "§6" + ProfitTrackerHud.coins(each) + " §7each, §6" + ProfitTrackerHud.coins(each * amount) + " §7total"
            : "§8No price" + (chosen == ProfitItemPrices.Source.DEFAULT ? "" : ", counts at Default");
        g.text(font, com.epic60869.sky2m.features.sbc.SbcItems.parseLegacy(price), x + 10, y + 116, Sky2MGuiStyle.TEXT, false);
        String line = error.isEmpty() ? note : error;
        g.text(font, font.plainSubstrByWidth(line, W - 20), x + 10, y + 128, error.isEmpty() ? 0xFF808080 : 0xFFFF5555, false);
        int by = y + H - 24, bw = (W - 28) / 3;
        button(g, "Save", x + 10, by, bw, mouseX, mouseY, this::save, null);
        button(g, "Remove", x + 10 + bw + 4, by, bw, mouseX, mouseY, this::remove, null);
        button(g, Component.translatable("gui.cancel").getString(), x + 10 + (bw + 4) * 2, by, bw, mouseX, mouseY, this::onClose, null);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void button(GuiGraphicsExtractor g, String text, int x, int y, int w, int mouseX, int mouseY, Runnable action, Runnable right) {
        Hit hit = new Hit(x, y, x + w, y + 16, action, right);
        hits.add(hit);
        Sky2MGuiStyle.button(g, font, text, x, y, w, 16, inside(hit, mouseX, mouseY));
    }

    private static boolean inside(Hit hit, double x, double y) {
        return x >= hit.x0() && x < hit.x1() && y >= hit.y0() && y < hit.y1();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        boolean rightClick = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;
        if (rightClick) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.right() != null && inside(hit, event.x(), event.y())) {
                    Sky2MGuiStyle.click();
                    hit.right().run();
                    return true;
                }
            }
            return false;
        }
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return false;
        // The fields' borders, which the borderless EditBoxes inside them don't cover.
        for (EditBox field : List.of(box, customBox)) {
            if (Sky2MGuiStyle.inField(field, event.x(), event.y())) {
                setFocused(field);
                return true;
            }
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
