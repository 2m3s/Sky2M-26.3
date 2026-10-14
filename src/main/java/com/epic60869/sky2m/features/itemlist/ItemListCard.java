package com.epic60869.sky2m.features.itemlist;

import com.epic60869.sky2m.features.itemlist.ItemListData.Ingredient;
import com.epic60869.sky2m.features.itemlist.ItemListData.Recipe;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One recipe drawn as a card, the way SkyBlock Item List lays them out (crafting grid, Forge, Kat, NPC shop, trade, mob
 * drops), in Sky2M's colours. Used by the recipe screen and for the recipe pinned in the Favourites panel. Written for
 * Sky2M; no code or textures are taken from SkyBlock Item List.
 */
final class ItemListCard {
    static final int WIDTH = 176, SLOT = 18;
    static final int PANEL = 0xE0121722, EDGE = 0xFF3B465B, TITLE = 0xFF9AA5B8, GOLD = 0xFFFFD35A, SLOT_BG = 0xFF1A2130,
        BUTTON = 0xFF2A3345, BUTTON_HOVER = 0xFF3B5B8B;
    private static final DecimalFormat SHORT = new DecimalFormat("#.#", DecimalFormatSymbols.getInstance(Locale.US));

    /** An item on the card: where, what, how many, and anything to add under its tooltip. */
    private record Cell(int dx, int dy, String id, ItemStack stack, double amount, List<Component> extra) {}

    /** A small button on the card. */
    private record Action(int dx, int dy, String label, int colour, String tooltip, Runnable run) {}

    final Recipe recipe;
    private final String title;
    private final int height;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    /** Where the arrow goes, {dx, dy}, or null. */
    private int[] arrow;

    ItemListCard(Recipe recipe) {
        this.recipe = recipe;
        switch (recipe) {
            case ItemListData.Crafting c -> {
                title = "Crafting Recipe";
                height = 86;
                for (int i = 0; i < c.grid().length; i++) if (c.grid()[i] != null) cell(30 + i % 3 * SLOT, 17 + i / 3 * SLOT, c.grid()[i], null);
                arrow = new int[]{90, 35};
                cell(124, 35, c.result(), null);
                actions.add(new Action(0, 0, "+", 0xFFFFFFFF, "Show the SkyBlock craft (/viewrecipe)", () -> {
                    var connection = Minecraft.getInstance().getConnection();
                    if (connection != null) connection.sendCommand("viewrecipe " + c.result().id().replace('-', ':'));
                }));
            }
            case ItemListData.Forge f -> {
                title = "Forge Recipe";
                height = 86;
                for (int i = 0; i < f.inputs().size(); i++) cell(22 + i % 4 * SLOT, 17 + i / 4 * SLOT, f.inputs().get(i), null);
                arrow = new int[]{100, 35};
                cell(103, 54, clock("Forge Duration", f.seconds()), null, 1, List.of());
                cell(134, 35, f.result(), null);
            }
            case ItemListData.Kat k -> {
                title = "Kat Upgrade";
                height = 86;
                boolean extra = !k.items().isEmpty();
                cell(extra ? 20 : 35, 35, k.input(), null);
                cell(extra ? 46 : 61, 35, coins(k.coins()), "SKYBLOCK_COIN", k.coins(), List.of());
                for (int i = 0; i < k.items().size(); i++) cell(72, 17 + i * SLOT, k.items().get(i), null);
                arrow = new int[]{extra ? 98 : 87, 35};
                cell(extra ? 101 : 90, 54, clock("Kat Duration", k.seconds()), null, 1, List.of());
                cell(extra ? 135 : 124, 35, k.output(), null);
            }
            case ItemListData.Shop s -> {
                title = "NPC Shop";
                height = 86;
                for (int i = 0; i < s.cost().size(); i++) cell(22 + i % 4 * SLOT, 17 + i / 4 * SLOT, s.cost().get(i), null);
                cell(103, 16, ItemListStacks.stack(s.owner()), s.owner(), 1, List.of(Component.literal("Sold by this NPC").withStyle(ChatFormatting.GRAY)));
                arrow = new int[]{100, 35};
                cell(134, 35, s.result(), null);
            }
            case ItemListData.Trade t -> {
                title = "Trade";
                height = 86;
                cell(50, 35, t.cost(), null);
                arrow = new int[]{87, 35};
                cell(124, 35, t.result(), null);
            }
            case ItemListData.Drops d -> {
                title = d.name().isEmpty() ? "Mob Drops" : ItemListData.strip(d.name()) + (d.level() > 0 ? " (Lv " + d.level() + ")" : "");
                int rows = Math.max(1, (d.drops().size() + 6) / 7);
                height = 32 + rows * SLOT;
                for (int i = 0; i < d.drops().size(); i++) {
                    ItemListData.Drop drop = d.drops().get(i);
                    List<Component> extra = new ArrayList<>();
                    if (drop.chance() != null && !drop.chance().isEmpty()) extra.add(Component.literal("Chance: " + drop.chance()).withStyle(ChatFormatting.GOLD));
                    for (String line : drop.extra()) extra.add(Component.literal(ItemListData.strip(line)).withStyle(ChatFormatting.GRAY));
                    cell(26 + i % 7 * SLOT, 17 + i / 7 * SLOT, drop.item(), extra);
                }
                List<Component> mobInfo = new ArrayList<>();
                if (d.coins() > 0) mobInfo.add(Component.literal("Coins: " + String.format(Locale.ROOT, "%,d", d.coins())).withStyle(ChatFormatting.GOLD));
                if (d.xp() > 0) mobInfo.add(Component.literal("Combat XP: " + String.format(Locale.ROOT, "%,d", d.xp())).withStyle(ChatFormatting.DARK_AQUA));
                for (String line : d.extra()) mobInfo.add(Component.literal(ItemListData.strip(line)).withStyle(ChatFormatting.GRAY));
                cell(4, 4, ItemListStacks.stack(d.owner()), d.owner(), 1, mobInfo);
            }
        }
        int favX = WIDTH - 17;
        actions.add(new Action(favX, 0, "❤", 0, null, () -> ItemListFavourites.toggleRecipe(recipe)));
        // Place the buttons along the bottom right, the "+" (if any) left of the heart.
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            int dx = WIDTH - 17 - (actions.size() - 1 - i) * 15;
            actions.set(i, new Action(dx, height - 17, a.label(), a.colour(), a.tooltip(), a.run()));
        }
    }

    int height() {
        return height;
    }

    private void cell(int dx, int dy, Ingredient ingredient, List<Component> extra) {
        if (ingredient == null) return;
        cell(dx, dy, ItemListStacks.stack(ingredient.id()), ingredient.id(), ingredient.amount(), extra == null ? List.of() : extra);
    }

    private void cell(int dx, int dy, ItemStack stack, String id, double amount, List<Component> extra) {
        cells.add(new Cell(dx, dy, id, stack, amount, extra));
    }

    private static ItemStack clock(String name, int seconds) {
        ItemStack stack = new ItemStack(Items.CLOCK);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false)));
        stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal("Time: " + duration(seconds)).withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))));
        return stack;
    }

    private static ItemStack coins(long amount) {
        ItemStack stack = ItemListStacks.stack("SKYBLOCK_COIN");
        stack.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(
            Component.literal(String.format(Locale.ROOT, "%,d coins", amount)).withStyle(s -> s.withColor(ChatFormatting.GOLD).withItalic(false)))));
        return stack;
    }

    static String duration(int seconds) {
        if (seconds <= 0) return "Instant";
        int d = seconds / 86400, h = seconds / 3600 % 24, m = seconds / 60 % 60, s = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (d > 0) out.append(d).append("d ");
        if (h > 0) out.append(h).append("h ");
        if (m > 0) out.append(m).append("m ");
        if (s > 0 && d == 0) out.append(s).append("s");
        return out.toString().trim();
    }

    static String shortAmount(double amount) {
        if (amount >= 1e9) return SHORT.format(amount / 1e9) + "b";
        if (amount >= 1e6) return SHORT.format(amount / 1e6) + "m";
        if (amount >= 1e3) return SHORT.format(amount / 1e3) + "k";
        return SHORT.format(amount);
    }

    /** A slot like the rest of Sky2M's screens; {@code hover} lights it up. */
    static void slot(GuiGraphicsExtractor g, int x, int y, boolean hover) {
        g.fill(x, y, x + SLOT, y + SLOT, SLOT_BG);
        g.outline(x, y, SLOT, SLOT, EDGE);
        if (hover) g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0x40FFFFFF);
    }

    /** An item with its amount in the corner (shortened past a thousand, since recipes ask for 160 or 2.5k of things). */
    static void item(GuiGraphicsExtractor g, Font font, ItemStack stack, double amount, int x, int y) {
        g.item(stack, x, y);
        if (amount > 1 || (amount > 0 && amount != Math.floor(amount))) {
            String text = shortAmount(amount);
            g.pose().pushMatrix();
            g.pose().translate(0, 0);
            g.text(font, text, x + 17 - font.width(text), y + 9, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
    }

    /** Draws the card at (x, y); returns the id under the mouse, if any. */
    String draw(GuiGraphicsExtractor g, Font font, int x, int y, int mouseX, int mouseY) {
        g.fill(x, y, x + WIDTH, y + height, PANEL);
        g.outline(x, y, WIDTH, height, EDGE);
        String shown = font.plainSubstrByWidth(title, WIDTH - 50);
        g.text(font, shown, x + (WIDTH - font.width(shown)) / 2, y + 5, TITLE, false);
        if (arrow != null) arrow(g, x + arrow[0], y + arrow[1]);
        String hoveredId = null;
        for (Cell c : cells) {
            int cx = x + c.dx(), cy = y + c.dy();
            boolean over = mouseX >= cx && mouseX < cx + SLOT && mouseY >= cy && mouseY < cy + SLOT;
            slot(g, cx, cy, over);
            item(g, font, c.stack(), c.amount(), cx + 1, cy + 1);
            if (over) {
                hoveredId = c.id();
                List<Component> tip = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), c.stack()));
                if (c.amount() > 1) tip.add(Component.literal("Amount: " + String.format(Locale.ROOT, "%,.0f", c.amount())).withStyle(ChatFormatting.DARK_GRAY));
                tip.addAll(c.extra());
                g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
            }
        }
        for (Action a : actions) {
            int bx = x + a.dx(), by = y + a.dy();
            boolean over = mouseX >= bx && mouseX < bx + 13 && mouseY >= by && mouseY < by + 13;
            g.fill(bx, by, bx + 13, by + 13, over ? BUTTON_HOVER : BUTTON);
            g.outline(bx, by, 13, 13, EDGE);
            boolean heart = a.label().equals("❤");
            boolean favourite = heart && ItemListFavourites.isRecipe(recipe);
            int colour = heart ? (favourite ? 0xFFFF5555 : 0xFFFFFFFF) : a.colour();
            g.text(font, a.label(), bx + 7 - font.width(a.label()) / 2, by + 3, colour, false);
            if (over) {
                String tip = heart ? (favourite ? "Remove from favourites" : "Add to favourites") : a.tooltip();
                g.setTooltipForNextFrame(font, Component.literal(tip), mouseX, mouseY);
            }
        }
        return hoveredId;
    }

    /** An arrow pointing right, drawn with fills (22 by 15, like a furnace's). */
    private static void arrow(GuiGraphicsExtractor g, int x, int y) {
        int colour = 0xFF6B7891;
        g.fill(x, y + 6, x + 15, y + 9, colour);
        for (int i = 0; i < 7; i++) g.fill(x + 15 + i, y + 1 + i, x + 16 + i, y + 14 - i, colour);
    }

    /** A click at (mouseX, mouseY) on the card at (x, y): runs a button, or returns the id of the item clicked. */
    String click(int x, int y, double mouseX, double mouseY) {
        for (Action a : actions) {
            int bx = x + a.dx(), by = y + a.dy();
            if (mouseX >= bx && mouseX < bx + 13 && mouseY >= by && mouseY < by + 13) {
                a.run().run();
                return "";
            }
        }
        for (Cell c : cells) {
            int cx = x + c.dx(), cy = y + c.dy();
            if (mouseX >= cx && mouseX < cx + SLOT && mouseY >= cy && mouseY < cy + SLOT) return c.id();
        }
        return null;
    }

    /** The id of the item under the mouse on the card at (x, y), or null. */
    String idAt(int x, int y, double mouseX, double mouseY) {
        for (Cell c : cells) {
            int cx = x + c.dx(), cy = y + c.dy();
            if (mouseX >= cx && mouseX < cx + SLOT && mouseY >= cy && mouseY < cy + SLOT) return c.id();
        }
        return null;
    }

    boolean contains(int x, int y, double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
    }
}
