package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.itemlist.ItemListStacks;
import com.epic60869.sky2m.features.portfolio.Portfolio;
import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 mp: the accessories you're missing ({@link MissingAccessories}), cheapest Magical Power first, with soulbound,
 * untradeable and unpriced ones in their own section at the bottom. Hover a row for the item, click it to search the
 * Auction House or Bazaar for it (or see its recipe). The list is rebuilt while it's open, so a new accessory drops off
 * as soon as it's in your inventory.
 *
 * <p>Drawn like the /s2 settings ({@link Sky2MGuiStyle}), the same way as /s2 collections.
 */
public final class MissingAccessoriesScreen extends Screen {
    private enum Sort {
        COINS_PER_MP("Coins / MP"), PRICE("Price"), GAIN("MP Gain");

        final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    private static final int ROW = 14;
    private static final int ICON = 12;
    private static final int GREY = 0xFF808080;
    private static final int GOLD = 0xFFFFAA00;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final String[] HEADERS = {"Accessory", "MP", "Price", "Coins / MP", "How"};

    private static Sort sort = Sort.COINS_PER_MP;
    private static String search = "";

    private int scroll;
    private EditBox searchBox;
    /** The table's lines, rebuilt only when the list, the sort or the search changes (not every frame). */
    private List<Line> lines = List.of();
    private MissingAccessories.View linesFor;
    private Sort linesSort;
    private String linesSearch;

    /** A clickable toolbar entry drawn this frame. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {}

    /** A line of the table: a row, or the bottom section's heading (row null). */
    private record Line(MissingAccessories.Row row, String heading) {}

    private final List<Hit> hits = new ArrayList<>();

    public MissingAccessoriesScreen() {
        super(Component.literal("Sky2M Missing Accessories"));
    }

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int toolbarTop() { return top() + 29; }
    private int toolbarRow() { return toolbarTop() + 4; }
    private int contentTop() { return toolbarTop() + 28; }
    private int insetLeft() { return left() + 9; }
    private int insetRight() { return left() + panelWidth() - 9; }
    private int insetTop() { return contentTop() + 16; }
    /** The totals bar sits under the table. */
    private int insetBottom() { return top() + panelHeight() - 26; }
    private int tableTop() { return insetTop() + 5; }
    private int tableBottom() { return insetBottom() - 2; }
    private int rowLeft() { return insetLeft() + 3; }
    private int rowRight() { return insetRight() - 12; }
    private int rowsTop() { return tableTop() + 13; }

    @Override
    protected void init() {
        clearWidgets();
        searchBox = new EditBox(font, 0, 0, 80, 10, Component.literal("Search"));
        searchBox.setMaxLength(40);
        searchBox.setHint(Component.literal("Search...").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value;
            scroll = 0;
        });
        MissingAccessories.refreshIfChanged();
        int fieldRight = left() + panelWidth() - 10 - Sky2MGuiStyle.SEARCH_ICON;
        int fieldWidth = Math.max(60, Math.min(120, panelWidth() / 5));
        Sky2MGuiStyle.placeSearch(searchBox, fieldRight - fieldWidth, toolbarRow(), fieldWidth);
        addRenderableWidget(searchBox);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelWidth(), panelHeight(), true);
        MissingAccessories.View view = MissingAccessories.view();

        drawHeader(g, view);
        hits.clear();
        drawToolbar(g, mouseX, mouseY);

        Sky2MGuiStyle.panel(g, left() + 4, contentTop(), panelWidth() - 9, top() + panelHeight() - 5 - contentTop(), false);
        Component summary;
        if (!view.status().isEmpty()) {
            summary = Component.literal(view.status()).withStyle(ChatFormatting.GOLD);
        } else if (!view.ready()) {
            summary = Component.literal("Loading the accessory list and prices...").withStyle(ChatFormatting.GRAY);
        } else {
            summary = Component.literal(view.main().size() + view.bottom().size() + " missing").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("   " + view.owned() + " owned   Click a row to search the Auction House or Bazaar for it, or see its recipe.")
                    .withStyle(ChatFormatting.GRAY));
        }
        g.text(font, font.plainSubstrByWidth(summary.getString(), insetRight() - insetLeft()).equals(summary.getString()) ? summary
            : Component.literal(font.plainSubstrByWidth(summary.getString(), insetRight() - insetLeft() - 6) + "...").withStyle(summary.getStyle()),
            insetLeft() + 1, contentTop() + 5, Sky2MGuiStyle.DESCRIPTION, true);
        Sky2MGuiStyle.inset(g, insetLeft(), insetTop(), insetRight(), insetBottom());

        List<Line> lines = lines(view);
        MissingAccessories.Row hovered = drawTable(g, lines, view, mouseX, mouseY);
        drawTotals(g, view);
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (hovered != null) g.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
    }

    /** The settings' header bar: the title in the middle, your Magical Power on the right. */
    private void drawHeader(GuiGraphicsExtractor g, MissingAccessories.View view) {
        int x = left() + 4, w = panelWidth() - 9;
        Component title = Sky2MGuiStyle.title("Missing Accessories");
        String status = view.magicalPower() < 0 ? ""
            : "Magical Power: " + (view.estimated() ? "~" : "") + String.format(Locale.US, "%,d", view.magicalPower());
        int titleX = x + (w - font.width(title)) / 2;
        int statusRoom = x + w - 6 - (titleX + font.width(title) + 10);
        if (statusRoom < font.width(status)) {
            titleX = x + 6;
            statusRoom = x + w - 6 - (titleX + font.width(title) + 10);
        }
        Sky2MGuiStyle.panel(g, x, top() + 5, w, 20, false);
        g.text(font, title, titleX, top() + 11, Sky2MGuiStyle.TITLE, false);
        String fit = font.plainSubstrByWidth(status, Math.max(0, statusRoom));
        g.text(font, fit, x + w - 6 - font.width(fit), top() + 11, GOLD, false);
    }

    /** The sorts as category-style entries, the Upgrades switch as a MoulConfig button, the search on the right. */
    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.panel(g, left() + 4, toolbarTop(), panelWidth() - 9, 24, false);
        int x = left() + 12;
        g.text(font, "Sort:", x, toolbarRow() + 4, GREY, false);
        x += font.width("Sort:") + 8;
        for (Sort s : Sort.values()) {
            x += entry(g, s.label, x, s == sort, mouseX, mouseY, () -> {
                sort = s;
                scroll = 0;
            }) + 10;
        }
        x += 6;
        g.fill(x - 8, toolbarRow() + 2, x - 7, toolbarRow() + 14, Sky2MGuiStyle.LINE);
        boolean upgrades = MissingAccessories.includeUpgrades();
        String label = upgrades ? "Upgrades: Shown" : "Upgrades: Hidden";
        int bw = Sky2MGuiStyle.buttonWidth(font, "Upgrades: Hidden");
        if (x + bw < searchBox.getX() - 8) {
            Hit hit = new Hit(x, toolbarRow(), x + bw, toolbarRow() + 16, () -> {
                MissingAccessories.setIncludeUpgrades(!MissingAccessories.includeUpgrades());
                scroll = 0;
            });
            hits.add(hit);
            Sky2MGuiStyle.button(g, font, label, x, toolbarRow(), bw, 16, inside(hit, mouseX, mouseY));
        }
        Sky2MGuiStyle.search(g, searchBox);
    }

    private int entry(GuiGraphicsExtractor g, String text, int x, boolean selected, int mouseX, int mouseY, Runnable action) {
        int w = font.width(text);
        Hit hit = new Hit(x - 3, toolbarRow(), x + w + 3, toolbarRow() + 16, action);
        hits.add(hit);
        return Sky2MGuiStyle.category(g, font, text, x, toolbarRow() + 4, selected, inside(hit, mouseX, mouseY));
    }

    private static boolean inside(Hit hit, double x, double y) {
        return x >= hit.x0() && x < hit.x1() && y >= hit.y0() && y < hit.y1();
    }

    // ----- Table -----

    /**
     * The main rows in the chosen order (sorted in the background), then (if any) the bottom section's heading and
     * rows; both filtered by the search. Worked out again only when the list, sort or search changed.
     */
    private List<Line> lines(MissingAccessories.View view) {
        if (view == linesFor && sort == linesSort && search.equals(linesSearch)) return lines;
        linesFor = view;
        linesSort = sort;
        linesSearch = search;
        String query = search.trim().toLowerCase(Locale.ROOT);
        List<MissingAccessories.Row> ordered = switch (sort) {
            case COINS_PER_MP -> view.main();
            case PRICE -> view.byPrice();
            case GAIN -> view.byGain();
        };
        List<Line> lines = new ArrayList<>();
        for (MissingAccessories.Row r : ordered) if (matches(r, query)) lines.add(new Line(r, null));
        List<MissingAccessories.Row> bottom = new ArrayList<>();
        for (MissingAccessories.Row r : view.bottom()) if (matches(r, query)) bottom.add(r);
        if (!bottom.isEmpty()) {
            lines.add(new Line(null, "Soulbound, not tradeable or no price (" + bottom.size() + ")"));
            for (MissingAccessories.Row r : bottom) lines.add(new Line(r, null));
        }
        this.lines = List.copyOf(lines);
        return this.lines;
    }

    private static boolean matches(MissingAccessories.Row row, String query) {
        return query.isEmpty() || row.name().toLowerCase(Locale.ROOT).contains(query);
    }

    private int[] columns() {
        int x = insetLeft() + 6;
        int w = rowRight() - 2 - x;
        return new int[]{x, x + w * 36 / 100, x + w * 44 / 100, x + w * 55 / 100, x + w * 67 / 100};
    }

    private int rowsVisible() {
        return Math.max(1, (tableBottom() - tableTop() - 13) / ROW);
    }

    /** Draws the table; returns the row under the mouse, if any. */
    private MissingAccessories.Row drawTable(GuiGraphicsExtractor g, List<Line> lines, MissingAccessories.View view, int mouseX, int mouseY) {
        int[] c = columns();
        int y = tableTop();
        for (int i = 0; i < HEADERS.length; i++) {
            boolean sorted = (i == 1 && sort == Sort.GAIN) || (i == 2 && sort == Sort.PRICE) || (i == 3 && sort == Sort.COINS_PER_MP);
            g.text(font, HEADERS[i], c[i], y, sorted ? Sky2MGuiStyle.SELECTED : Sky2MGuiStyle.HEADING, false);
        }
        g.fill(rowLeft(), y + 10, rowRight(), y + 11, Sky2MGuiStyle.LINE);
        if (lines.isEmpty()) {
            String text = !view.ready() || !view.known() ? "Waiting for your accessories..."
                : !search.isBlank() ? "No missing accessory matches \"" + search.trim() + "\"."
                : "You're not missing any accessory Sky2M knows of.";
            g.text(font, text, c[0], y + 18, GREY, false);
            return null;
        }
        int visible = rowsVisible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        MissingAccessories.Row hovered = null;
        int ry = y + 16;
        for (int i = scroll; i < lines.size() && i < scroll + visible; i++) {
            Line line = lines.get(i);
            if (line.row() == null) {
                // The bottom section: a divider and its heading.
                g.fill(rowLeft(), ry - 3, rowRight(), ry - 2, Sky2MGuiStyle.LINE);
                g.text(font, font.plainSubstrByWidth(line.heading(), rowRight() - c[0]), c[0], ry + 1, Sky2MGuiStyle.HEADING, false);
                ry += ROW;
                continue;
            }
            MissingAccessories.Row row = line.row();
            boolean hover = mouseY >= ry - 3 && mouseY < ry + ROW - 3 && mouseX >= rowLeft() && mouseX < rowRight();
            if (hover) {
                g.fill(rowLeft(), ry - 3, rowRight(), ry + ROW - 3, Sky2MGuiStyle.ROW_HOVER);
                hovered = row;
            }
            int nameX = c[0];
            ItemStack icon = ItemListStacks.stack(row.id());
            if (!icon.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(c[0], ry - 2);
                g.pose().scale(ICON / 16f, ICON / 16f);
                g.item(icon, 0, 0);
                g.pose().popMatrix();
                nameX += ICON + 3;
            }
            ChatFormatting colour = RepoItems.tierColour(row.rarity());
            net.minecraft.network.chat.TextColor textColour = net.minecraft.network.chat.TextColor.fromLegacyFormat(colour);
            int rgb = textColour == null ? 0xFFFFFF : textColour.getValue();
            String upgrade = row.kind() == MissingAccessories.Kind.UPGRADE ? " ↑" : "";
            String name = font.plainSubstrByWidth(row.name(), c[1] - nameX - 6 - font.width(upgrade));
            g.text(font, name, nameX, ry, 0xFF000000 | rgb, false);
            if (!upgrade.isEmpty()) g.text(font, upgrade, nameX + font.width(name), ry, GREY, false);
            g.text(font, "+" + row.gain(), c[1], ry, GREEN, false);
            boolean main = row.reason() == null;
            g.text(font, row.price() > 0 ? Portfolio.coins(row.price()) : "-", c[2], ry, main ? GOLD : GREY, false);
            g.text(font, main ? Portfolio.coins(row.coinsPerMp()) : "-", c[3], ry, main ? Sky2MGuiStyle.TEXT : GREY, false);
            if (main) {
                // How it's paid for, like "Upgrade, AH, after selling Witch's Talisman" or "12x Recombobulator 3000".
                String how = how(row);
                g.text(font, font.plainSubstrByWidth(how, rowRight() - c[4]), c[4], ry,
                    row.kind() == MissingAccessories.Kind.ITEM ? Sky2MGuiStyle.TEXT_DIM : 0xFF55FFFF, false);
            } else {
                g.text(font, font.plainSubstrByWidth(row.reason(), rowRight() - c[4]), c[4], ry, RED, false);
            }
            ry += ROW;
        }
        Sky2MGuiStyle.scrollbar(g, insetRight() - 10, insetTop() + 4, insetBottom() - 4, scroll, visible, lines.size());
        return hovered;
    }

    /** The bar under the table: what the 10 cheapest Magical Power upgrades cost together. */
    private void drawTotals(GuiGraphicsExtractor g, MissingAccessories.View view) {
        int n = view.cheapestCount();
        double coins = view.cheapestCoins();
        int mp = view.cheapestMp();
        Component text = n == 0 ? Component.literal("Nothing to buy.").withStyle(ChatFormatting.GRAY)
            : Component.literal("Cheapest " + n + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(Portfolio.coins(coins) + " coins").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(" for ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("+" + mp + " MP").withStyle(ChatFormatting.GREEN));
        g.text(font, text, insetLeft() + 1, insetBottom() + 6, Sky2MGuiStyle.DESCRIPTION, true);
    }

    /** The How column: what kind of row it is and how it's paid for. */
    private static String how(MissingAccessories.Row row) {
        String note = row.note() == null ? "" : row.note();
        return switch (row.kind()) {
            case ITEM -> row.source();
            case UPGRADE -> "Upgrade, " + row.source() + (note.isEmpty() ? "" : ", " + note);
            case RARITY -> "Upgrade, " + note;
            case RECOMB -> note;
        };
    }

    private static List<Component> tooltip(MissingAccessories.Row row) {
        List<Component> tip = new ArrayList<>();
        if (row.kind() == MissingAccessories.Kind.RECOMB) {
            tip.add(Component.literal(row.name()).withStyle(RepoItems.tierColour(row.rarity())));
            tip.add(Component.literal("Each goes up to the next rarity:").withStyle(ChatFormatting.GRAY));
            for (String member : row.members()) tip.add(Component.literal(" - " + member).withStyle(ChatFormatting.GRAY));
        } else if (row.kind() == MissingAccessories.Kind.RARITY) {
            tip.add(Component.literal(row.name()).withStyle(RepoItems.tierColour(row.rarity())));
            tip.add(Component.literal("Charge your Pulse Ring with " + row.note() + " in an anvil").withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal("to reach " + row.rarity().replace('_', ' ') + ".").withStyle(ChatFormatting.GRAY));
        } else {
            tip.addAll(Sky2MPriceTooltip.itemListTooltip(ItemListStacks.stack(row.id())));
        }
        tip.add(Component.empty());
        tip.add(Component.literal("+" + row.gain() + " Magical Power").withStyle(ChatFormatting.GREEN));
        if (row.kind() == MissingAccessories.Kind.UPGRADE && row.note() != null && !row.note().isEmpty()) {
            tip.add(Component.literal("Price is the upgrade's, less what your " + row.note().substring("after selling ".length()) + " sells for")
                .withStyle(ChatFormatting.GRAY));
        }
        if (row.kind() == MissingAccessories.Kind.UPGRADE && row.from() != null) {
            String from = RepoItems.displayName(row.from());
            tip.add(Component.literal("Upgrade from your " + (from == null ? row.from() : ChatFormatting.stripFormatting(from))).withStyle(ChatFormatting.GRAY));
        }
        if (row.price() > 0) {
            String via = row.kind() == MissingAccessories.Kind.RECOMB || row.kind() == MissingAccessories.Kind.RARITY ? row.note() + ", " + switch (row.source()) {
                case "AH" -> "lowest BIN";
                default -> "Bazaar insta-buy";
            } : switch (row.source()) {
                case "AH" -> "lowest BIN";
                case "Bazaar" -> "Bazaar insta-buy";
                case "NPC" -> "NPC shop";
                case "Forge" -> "Forge cost";
                default -> "craft cost";
            };
            tip.add(Component.literal(Portfolio.coins(row.price()) + " coins (" + via + ")").withStyle(ChatFormatting.GOLD));
            if (row.reason() == null) tip.add(Component.literal(Portfolio.coins(row.coinsPerMp()) + " coins per Magical Power").withStyle(ChatFormatting.GRAY));
        }
        if (row.reason() != null) tip.add(Component.literal(row.reason()).withStyle(ChatFormatting.RED));
        String item = row.buyName() == null ? row.name() : row.buyName();
        tip.add(Component.literal(switch (row.source()) {
            case "AH" -> "Click to search the Auction House for " + item;
            case "Bazaar" -> "Click to open " + item + " in the Bazaar";
            default -> "Click to see its recipe";
        }).withStyle(ChatFormatting.YELLOW));
        return tip;
    }

    // ----- Input -----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (Sky2MGuiStyle.inSearch(searchBox, event.x(), event.y())) {
            setFocused(searchBox);
            return true;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (inside(hit, event.x(), event.y())) {
                Sky2MGuiStyle.click();
                hit.action().run();
                return true;
            }
        }
        // Column headers sort too.
        int[] c = columns();
        if (event.y() >= tableTop() - 2 && event.y() < tableTop() + 10) {
            Sort s = event.x() >= c[3] && event.x() < c[4] ? Sort.COINS_PER_MP : event.x() >= c[2] && event.x() < c[3] ? Sort.PRICE
                : event.x() >= c[1] && event.x() < c[2] ? Sort.GAIN : null;
            if (s != null) {
                Sky2MGuiStyle.click();
                sort = s;
                scroll = 0;
                return true;
            }
            return false;
        }
        if (event.y() >= rowsTop() && event.y() < rowsTop() + rowsVisible() * ROW && event.x() >= rowLeft() && event.x() < rowRight()) {
            List<Line> lines = lines(MissingAccessories.view());
            int index = scroll + (int) ((event.y() - rowsTop()) / ROW);
            if (index >= 0 && index < lines.size() && lines.get(index).row() != null) {
                Sky2MGuiStyle.click();
                MissingAccessories.act(lines.get(index).row());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    /** Asks for a background rebuild if anything changed (20 times a second, not every frame). */
    @Override
    public void tick() {
        super.tick();
        MissingAccessories.refreshIfChanged();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
