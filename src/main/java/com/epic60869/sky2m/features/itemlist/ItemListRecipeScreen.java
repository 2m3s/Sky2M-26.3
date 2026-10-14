package com.epic60869.sky2m.features.itemlist;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The recipes (or usages) of an item, like SkyBlock Item List's recipe screen: cards stacked in the middle over the
 * dimmed menu you came from, as many as fit, with arrows (or the mouse wheel) for the rest. Click an item on a card for
 * its recipes; the Back key goes to the recipe before, Esc or your inventory key closes them all. Written for Sky2M.
 */
public final class ItemListRecipeScreen extends Screen {
    private static final int GAP = 5;

    private final Screen parent;
    private final List<ItemListCard> cards;
    private final List<List<ItemListCard>> pages = new ArrayList<>();
    private int page;
    private int[] prevArrow, nextArrow;

    ItemListRecipeScreen(Screen parent, String title, List<ItemListData.Recipe> recipes) {
        super(Component.literal(title));
        this.parent = parent;
        List<ItemListData.Recipe> sorted = new ArrayList<>(recipes);
        // Favourite recipes first.
        sorted.sort((a, b) -> Boolean.compare(ItemListFavourites.isRecipe(b), ItemListFavourites.isRecipe(a)));
        this.cards = sorted.stream().map(ItemListCard::new).toList();
    }

    /** The menu under all the recipe screens. */
    Screen root() {
        Screen s = parent;
        while (s instanceof ItemListRecipeScreen r) s = r.parent;
        return s;
    }

    @Override
    protected void init() {
        pages.clear();
        int allowed = height * 4 / 5 - 20;
        List<ItemListCard> current = new ArrayList<>();
        int used = 0;
        for (ItemListCard card : cards) {
            if (!current.isEmpty() && used + card.height() > allowed) {
                pages.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(card);
            used += card.height() + GAP;
        }
        if (!current.isEmpty()) pages.add(current);
        page = Math.clamp(page, 0, Math.max(0, pages.size() - 1));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // The menu stays visible behind, dimmed.
        if (parent != null) parent.extractRenderState(g, -1, -1, delta);
        g.fill(0, 0, width, height, 0x80000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        prevArrow = nextArrow = null;
        if (pages.isEmpty()) return;
        List<ItemListCard> shown = pages.get(page);
        int total = shown.stream().mapToInt(c -> c.height() + GAP).sum() - GAP + (pages.size() > 1 ? 16 : 0);
        int x = (width - ItemListCard.WIDTH) / 2, y = (height - total) / 2;
        g.centeredText(font, title, width / 2, y - 12, ItemListCard.GOLD);
        if (pages.size() > 1) {
            String text = (page + 1) + " / " + pages.size();
            g.centeredText(font, text, width / 2, y + 2, 0xFFFFFFFF);
            prevArrow = new int[]{x, y, 16, 12};
            nextArrow = new int[]{x + ItemListCard.WIDTH - 16, y, 16, 12};
            arrowButton(g, prevArrow, "◀", mouseX, mouseY);
            arrowButton(g, nextArrow, "▶", mouseX, mouseY);
            y += 16;
        }
        for (ItemListCard card : shown) {
            card.draw(g, font, x, y, mouseX, mouseY);
            y += card.height() + GAP;
        }
    }

    private void arrowButton(GuiGraphicsExtractor g, int[] r, String label, int mouseX, int mouseY) {
        boolean over = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], over ? ItemListCard.BUTTON_HOVER : ItemListCard.BUTTON);
        g.outline(r[0], r[1], r[2], r[3], ItemListCard.EDGE);
        g.centeredText(font, label, r[0] + r[2] / 2, r[1] + 2, 0xFFFFFFFF);
    }

    private static boolean in(int[] r, double x, double y) {
        return r != null && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    /** The card under the mouse and its top-left corner, or null. */
    private Object[] cardAt(double mouseX, double mouseY) {
        if (pages.isEmpty()) return null;
        List<ItemListCard> shown = pages.get(page);
        int total = shown.stream().mapToInt(c -> c.height() + GAP).sum() - GAP + (pages.size() > 1 ? 16 : 0);
        int x = (width - ItemListCard.WIDTH) / 2, y = (height - total) / 2 + (pages.size() > 1 ? 16 : 0);
        for (ItemListCard card : shown) {
            if (card.contains(x, y, mouseX, mouseY)) return new Object[]{card, x, y};
            y += card.height() + GAP;
        }
        return null;
    }

    private void turn(int by) {
        if (pages.size() > 1) page = Math.floorMod(page + by, pages.size());
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (in(prevArrow, event.x(), event.y())) {
            turn(-1);
            return true;
        }
        if (in(nextArrow, event.x(), event.y())) {
            turn(1);
            return true;
        }
        if (ItemList.config() != null && ItemList.matchesMouse(ItemList.config().backKey, event.button())) {
            back();
            return true;
        }
        Object[] hit = cardAt(event.x(), event.y());
        if (hit != null) {
            String id = ((ItemListCard) hit[0]).click((int) hit[1], (int) hit[2], event.x(), event.y());
            if (id != null && !id.isEmpty()) ItemList.click(event.button(), id, this);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        turn(scrollY < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        var c = ItemList.config();
        if (c != null && event.key() == c.backKey) {
            back();
            return true;
        }
        if (minecraft != null && minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        double mx = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()), my = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
        Object[] hit = cardAt(mx, my);
        if (hit != null && c != null) {
            ItemListCard card = (ItemListCard) hit[0];
            // Over an item on the card: its recipes, usages or favourite; elsewhere on the card, the Favourite key stars the recipe.
            String id = card.idAt((int) hit[1], (int) hit[2], mx, my);
            if (id != null && ItemList.key(event.key(), id, this)) return true;
            if (id == null && event.key() == c.favouriteKey) {
                ItemListFavourites.toggleRecipe(card.recipe);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** The recipe screen before this one, or the menu. */
    void back() {
        minecraft.gui.setScreen(parent);
    }

    /** Esc goes all the way back to the menu, like closing the recipe. */
    @Override
    public void onClose() {
        minecraft.gui.setScreen(root());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
