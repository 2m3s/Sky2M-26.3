package com.epic60869.sky2m.features.portfolio;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One portfolio item, opened by clicking its row in /s2 portfolio: each purchase (lot) on its own line with its amount
 * and price each, so another copy bought at a different price is one "+ Add purchase" away. Save turns the lines into
 * the row's buy price (their average), the same lots "2x1.5m + 1x1.2m" makes. Extra, Qty, Log sale and Remove moved
 * here from the sheet's bottom bar.
 */
public final class PortfolioItemScreen extends Screen {
    private static final int ROW = 24;

    private final Screen parent;
    private final Portfolio.Entry entry;
    /** The purchase lines' text (amount, price each), kept while the widgets are rebuilt. */
    private final List<String[]> lines = new ArrayList<>();
    private final List<EditBox[]> boxes = new ArrayList<>();
    private EditBox extraBox, qtyBox;
    private String extraText, qtyText;
    private String status = "";
    private int scroll;

    public PortfolioItemScreen(Screen parent, Portfolio.Entry entry) {
        super(Component.literal(entry.name));
        this.parent = parent;
        this.entry = entry;
        if (entry.lots != null && !entry.lots.isEmpty()) {
            for (Portfolio.Lot lot : entry.lots) lines.add(new String[]{String.valueOf(lot.amount), Portfolio.coins(lot.price)});
        } else if (entry.buyPrice > 0) {
            lines.add(new String[]{String.valueOf(Math.max(1, Portfolio.count(entry).total())), Portfolio.coins(entry.buyPrice)});
        }
        if (lines.isEmpty()) lines.add(new String[]{"1", ""});
        extraText = entry.extra > 0 ? String.valueOf(entry.extra) : "";
        qtyText = entry.quantity != null ? String.valueOf(entry.quantity) : "";
    }

    private int left() { return Math.max(10, (width - 520) / 2); }
    private int panelWidth() { return Math.min(520, width - 20); }
    private int top() { return 10; }
    private int panelHeight() { return height - 20; }
    private int linesTop() { return top() + 92; }
    private int linesBottom() { return top() + panelHeight() - 70; }
    private int visibleLines() { return Math.max(1, (linesBottom() - linesTop()) / ROW); }

    @Override
    protected void init() {
        rebuild();
    }

    private void keepText() {
        // The boxes are the visible lines, from the scroll position down.
        for (int i = 0; i < boxes.size() && scroll + i < lines.size(); i++) {
            lines.get(scroll + i)[0] = boxes.get(i)[0].getValue();
            lines.get(scroll + i)[1] = boxes.get(i)[1].getValue();
        }
        if (extraBox != null) extraText = extraBox.getValue();
        if (qtyBox != null) qtyText = qtyBox.getValue();
    }

    private void rebuild() {
        clearWidgets();
        boxes.clear();
        int x = left() + 8;
        scroll = Math.max(0, Math.min(scroll, lines.size() - visibleLines()));

        for (int i = scroll; i < Math.min(lines.size(), scroll + visibleLines()); i++) {
            int y = linesTop() + (i - scroll) * ROW;
            String[] line = lines.get(i);
            EditBox amount = new EditBox(font, x + 24, y, 50, 18, Component.literal("Amount"));
            amount.setMaxLength(9);
            amount.setValue(line[0]);
            amount.setHint(Component.literal("1").withStyle(ChatFormatting.DARK_GRAY));
            EditBox price = new EditBox(font, x + 104, y, 110, 18, Component.literal("Price each"));
            price.setMaxLength(20);
            price.setValue(line[1]);
            price.setHint(Component.literal("e.g. 1.5m").withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(amount);
            addRenderableWidget(price);
            boxes.add(new EditBox[]{amount, price});
            int index = i;
            addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
                keepText();
                lines.remove(index);
                rebuild();
            }).bounds(left() + panelWidth() - 30, y - 1, 20, 20).build());
        }

        int by = linesBottom() + 4;
        addRenderableWidget(Button.builder(Component.literal("+ Add purchase"), b -> {
            keepText();
            double now = Portfolio.price(entry.id);
            lines.add(new String[]{"1", now > 0 ? Portfolio.coins(now) : ""});
            scroll = Math.max(0, lines.size() - visibleLines());
            rebuild();
        }).bounds(x, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Save prices"), b -> {
            keepText();
            status = savePrices();
        }).bounds(x + 104, by, 80, 20).build());

        int ey = by + 26;
        extraBox = new EditBox(font, x + 40, ey, 50, 18, Component.literal("Extra"));
        extraBox.setHint(Component.literal("0").withStyle(ChatFormatting.DARK_GRAY));
        extraBox.setValue(extraText);
        addRenderableWidget(extraBox);
        addRenderableWidget(Button.builder(Component.literal("Set extra"), b -> {
            keepText();
            double v = Portfolio.parse(extraText.isBlank() ? "0" : extraText);
            if (v < 0) status = "Extra: type a whole number.";
            else {
                entry.extra = (int) v;
                Portfolio.changed();
                status = "Extra set (copies Sky2M can't see, e.g. applied skins).";
            }
        }).bounds(x + 94, ey - 1, 60, 20).build());
        qtyBox = new EditBox(font, x + 196, ey, 50, 18, Component.literal("Qty"));
        qtyBox.setHint(Component.literal("Auto").withStyle(ChatFormatting.DARK_GRAY));
        qtyBox.setValue(qtyText);
        addRenderableWidget(qtyBox);
        addRenderableWidget(Button.builder(Component.literal("Set qty"), b -> {
            keepText();
            if (qtyText.isBlank()) {
                entry.quantity = null;
                Portfolio.changed();
                status = "Quantity is counted automatically again.";
                return;
            }
            double v = Portfolio.parse(qtyText);
            if (v < 0) status = "Qty: type a whole number, or leave it empty to count automatically.";
            else {
                entry.quantity = (int) v;
                Portfolio.changed();
                status = "Quantity set to " + (int) v + ".";
            }
        }).bounds(x + 250, ey - 1, 56, 20).build());

        int rx = left() + panelWidth() - 8;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(rx - 60, ey - 1, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Remove item"), b -> {
            Portfolio.remove(entry);
            Minecraft.getInstance().gui.setScreen(parent);
        }).bounds(rx - 148, ey - 1, 84, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Log sale"), b -> {
            Portfolio.recordSale(entry, Math.max(1, Portfolio.count(entry).total()), Portfolio.value(entry), true);
            Minecraft.getInstance().gui.setScreen(parent);
        }).bounds(rx - 212, ey - 1, 60, 20).build());
    }

    /** Reads the lines into the row's lots and buy price; returns what to tell you. */
    private String savePrices() {
        List<Portfolio.Lot> lots = new ArrayList<>();
        int amount = 0;
        for (int i = 0; i < lines.size(); i++) {
            String[] line = lines.get(i);
            if (line[0].isBlank() && line[1].isBlank()) continue;
            double a = Portfolio.parse(line[0].isBlank() ? "1" : line[0]);
            double p = Portfolio.parse(line[1]);
            if (a <= 0 || a != Math.floor(a)) return "Purchase " + (i + 1) + ": the amount must be a whole number.";
            if (p < 0 || line[1].isBlank()) return "Purchase " + (i + 1) + ": type a price like 1.5m.";
            Portfolio.Lot lot = new Portfolio.Lot();
            lot.amount = (int) a;
            lot.price = p;
            lots.add(lot);
            amount += lot.amount;
        }
        Portfolio.setLots(entry, lots);
        int count = Portfolio.count(entry).total();
        String saved = lots.isEmpty() ? "Buy price cleared." : "Saved: " + amount + " bought, " + Portfolio.coins(entry.buyPrice) + " each on average.";
        return lots.isEmpty() || amount == count ? saved : saved + " (You have " + count + "; set Qty or Extra if that's wrong.)";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xFF07090D);
        int l = left(), t = top(), w = panelWidth();
        g.fill(l, t, l + w, t + panelHeight(), 0xFF181B21);
        g.fill(l, t, l + w, t + 2, 0xFFFFAA00);

        g.item(Portfolio.icon(entry), l + 8, t + 8);
        g.text(font, Component.literal(entry.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), l + 30, t + 12, 0xFFFFFFFF, true);

        // Summary.
        double now = Portfolio.price(entry.id);
        int count = Portfolio.count(entry).total();
        double value = Portfolio.value(entry);
        double pl = entry.buyPrice > 0 ? value - entry.buyPrice * count : 0;
        Component summary = Component.literal("Price each: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(now > 0 ? Portfolio.coins(now) : "-").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("   Have: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal("   Avg buy: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(entry.buyPrice > 0 ? Portfolio.coins(entry.buyPrice) : "-").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("   Value: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(Portfolio.coins(value)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("   P/L: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(entry.buyPrice > 0 ? Portfolio.coins(pl) : "-").withStyle(pl >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        g.text(font, summary, l + 8, t + 34, 0xFFFFFFFF, false);

        // Purchase lines.
        int hy = linesTop() - 14;
        g.text(font, Component.literal("Purchases").withStyle(ChatFormatting.GOLD), l + 8, hy - 16, 0xFFFFFFFF, false);
        g.text(font, "Amount", l + 32, hy, 0xFFFFAA00, false);
        g.text(font, "Price each", l + 112, hy, 0xFFFFAA00, false);
        g.text(font, "Cost", l + 236, hy, 0xFFFFAA00, false);
        g.text(font, "Profit/loss now", l + 330, hy, 0xFFFFAA00, false);
        g.fill(l + 8, hy + 10, l + w - 8, hy + 11, 0xFF2A2F38);
        for (int i = 0; i < boxes.size(); i++) {
            int y = linesTop() + i * ROW;
            g.text(font, "#" + (scroll + i + 1), l + 8, y + 5, 0xFF7A8290, false);
            g.text(font, "x", l + 92, y + 5, 0xFF7A8290, false);
            double a = Portfolio.parse(boxes.get(i)[0].getValue().isBlank() ? "1" : boxes.get(i)[0].getValue());
            double p = Portfolio.parse(boxes.get(i)[1].getValue());
            if (a > 0 && p >= 0 && !boxes.get(i)[1].getValue().isBlank()) {
                g.text(font, Portfolio.coins(a * p), l + 236, y + 5, 0xFFB8C0CC, false);
                if (now > 0) {
                    double lotPl = (now - p) * a;
                    String pct = p > 0 ? String.format(" (%+.0f%%)", (now - p) / p * 100) : "";
                    g.text(font, Portfolio.coins(lotPl) + pct, l + 330, y + 5, lotPl >= 0 ? 0xFF55FF55 : 0xFFFF5555, false);
                }
            }
        }
        if (lines.size() > visibleLines()) {
            g.text(font, "Scroll for more (" + lines.size() + " purchases)", l + 8, linesBottom() - 10, 0xFF7A8290, false);
        }

        int ey = linesBottom() + 4 + 26;
        g.text(font, "Extra", l + 8, ey + 5, 0xFFB8C0CC, false);
        g.text(font, "Qty", l + 172, ey + 5, 0xFFB8C0CC, false);
        if (!status.isEmpty()) g.text(font, status, l + 192, linesBottom() + 10, 0xFFB8C0CC, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int max = Math.max(0, lines.size() - visibleLines());
        int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY)));
        if (next != scroll) {
            keepText();
            scroll = next;
            rebuild();
        }
        return true;
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
