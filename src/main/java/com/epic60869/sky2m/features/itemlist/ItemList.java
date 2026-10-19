package com.epic60869.sky2m.features.itemlist;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.misc.storage.CustomGui;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Item List: SkyBlock Item List's panel beside inventories and SkyBlock menus, written for Sky2M (SkyBlock Item List
 * is All Rights Reserved, so this copies how it looks and behaves, not its code).
 * <ul>
 *   <li>The list fills the space on one side of the menu: page arrows and "page / pages" at the top, the items, and the
 *   search bar at the bottom with its filter (left click: next category, right click: all) and settings buttons. The
 *   mouse wheel turns pages; Ctrl and the wheel resizes the items.</li>
 *   <li>Searches are SkyBlock Item List's: {@code @pets wolf}, {@code a | b}, {@code a & b}, {@code #ID_START}, typed
 *   characters coloured like theirs, and sums worked out in the bar ({@link ItemListCalculator}).</li>
 *   <li>Click an item for its recipes, right-click for its wiki page (both can be changed); the Recipe, Usage and
 *   Favourite keys work over items in the list, in recipes and in any menu.</li>
 *   <li>Favourites on the other side: items and recipes, and a pinned recipe on top (Ctrl and click a recipe).</li>
 *   <li>The items move out of the way of other things beside the menu (Sky2M's panels such as the Garden plot map,
 *   other mods' buttons, potion effects), and the list takes the Storage Overlay's size into account.</li>
 * </ul>
 */
public final class ItemList {
    private static final int TOP = 20, BOTTOM = 24, MIN_WIDTH = 80;
    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sky2M Item List search");
        t.setDaemon(true);
        return t;
    });

    /** A rectangle on screen. */
    record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        boolean intersects(int ox, int oy, int ow, int oh) {
            return ox < x + w && ox + ow > x && oy < y + h && oy + oh > y;
        }
    }

    // State kept between menus.
    private static EditBox box;
    private static ItemListData.Category filter = ItemListData.Category.ALL;
    private static int mainPage, favPage;
    private static volatile List<ItemListData.Entry> results = List.of();
    private static int searchSeq;
    private static Object searchedEntries;
    private static String searchedFor = "\u0000";
    private static String calcResult;
    private static boolean calcOk;

    // This frame's layout.
    private static Rect mainRect, favRect, boxRect, filterRect, settingsRect, prevRect, nextRect, favPrevRect, favNextRect, pinnedRect;
    private static final List<Rect> zones = new ArrayList<>();
    private static final List<Rect> mainCells = new ArrayList<>(), favCells = new ArrayList<>();
    private static String hoveredId;
    private static ItemListData.Recipe hoveredRecipe;
    private static ItemListCard pinnedCard;

    private ItemList() {}

    public static FeatureConfigs.ItemList config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.itemList;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register(ItemList::attach);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            FeatureConfigs.ItemList c = config();
            if (c != null && c.clearOnRestart) c.lastSearch = "";
        });
    }

    private static boolean shouldShow(Screen screen) {
        FeatureConfigs.ItemList c = config();
        if (c == null || !c.enabled || !(screen instanceof AbstractContainerScreen<?>)) return false;
        if (screen instanceof CreativeModeInventoryScreen) return false;
        return c.outsideSkyblock || Compat.isOnSkyblock();
    }

    private static void attach(Minecraft mc, Screen screen, int width, int height) {
        if (!shouldShow(screen)) return;
        FeatureConfigs.ItemList c = config();
        ItemListData.ensureLoaded();
        try {
            filter = ItemListData.Category.valueOf(c.lastFilter);
        } catch (IllegalArgumentException e) {
            filter = ItemListData.Category.ALL;
        }

        String text = box == null ? c.lastSearch : box.getValue();
        box = new EditBox(mc.font, 0, 0, 100, 16, Component.literal("Search"));
        box.setMaxLength(999);
        box.setValue(text == null ? "" : text);
        box.setHint(Component.literal("Search or Calculate...").withStyle(ChatFormatting.DARK_GRAY));
        box.addFormatter(ItemList::highlight);
        box.setResponder(value -> {
            c.lastSearch = value;
            box.setSuggestion(ItemListSearch.suggestion(value));
            mainPage = 0;
            refreshSearch();
        });
        box.setSuggestion(ItemListSearch.suggestion(box.getValue()));
        refreshSearch();

        Screens.getWidgets(screen).add(new Renderer(screen));
        Screens.getWidgets(screen).add(box);

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(s, event));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, h, v) -> !scroll(x, y, v));
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !keyPressed(s, event));
    }

    // ------------------------------------------------------------------------------------------------ search

    private static void refreshSearch() {
        if (box == null) return;
        FeatureConfigs.ItemList c = config();
        String raw = box.getValue();
        String calc = ItemListCalculator.evaluate(raw, c != null && c.calculatorNeedsEquals);
        calcResult = calc;
        calcOk = calc != null && !calc.startsWith("ERR");
        List<ItemListData.Entry> entries = ItemListData.entries();
        String key = raw + "\u0000" + filter + "\u0000" + (c != null && c.fuzzy) + (c != null && c.hideVanilla) + (c != null && c.prefixFirst);
        if (key.equals(searchedFor) && entries == searchedEntries) return;
        searchedFor = key;
        searchedEntries = entries;
        int seq = ++searchSeq;
        boolean fuzzy = c != null && c.fuzzy, hideVanilla = c != null && c.hideVanilla, prefixFirst = c == null || c.prefixFirst;
        ItemListData.Category chosen = filter;
        SEARCH.execute(() -> {
            ItemListSearch.Query q = ItemListSearch.parse(raw);
            if (q.category() == null && chosen != ItemListData.Category.ALL) q = new ItemListSearch.Query(chosen, q.groups(), q.text());
            List<ItemListData.Entry> first = new ArrayList<>(), rest = new ArrayList<>();
            for (ItemListData.Entry e : entries) {
                if (seq != searchSeq) return;
                if (hideVanilla && e.vanilla()) continue;
                if (!ItemListSearch.matches(e, q, fuzzy)) continue;
                if (prefixFirst && ItemListSearch.prefix(e, q)) first.add(e);
                else rest.add(e);
            }
            first.addAll(rest);
            if (seq == searchSeq) results = first;
        });
    }

    /** Whether the items show: by default only once you've searched (like SkyBlock Item List). */
    private static boolean listVisible() {
        FeatureConfigs.ItemList c = config();
        if (c == null || box == null) return false;
        boolean searching = !box.getValue().isBlank() && calcResult == null;
        return switch (c.hideWhen) {
            case SEARCH -> searching;
            case FILTER -> searching || filter != ItemListData.Category.ALL;
            case NEVER -> true;
        };
    }

    /** SkyBlock Item List's search colours: @category pink, | and & yellow, #ID green. */
    private static FormattedCharSequence highlight(String text, int offset) {
        return sink -> {
            Integer colour = null;
            for (int i = 0; i < text.length(); i++) {
                int cp = text.charAt(i);
                Style style = Style.EMPTY;
                int index = offset + i;
                if (cp == '@' && index == 0) colour = 0xFFFF55FF;
                else if (cp == ' ') colour = null;
                else if (cp == '#') colour = 0xFF55FF55;
                if (cp == '|' || cp == '&') style = style.withColor(0xFFFF55);
                else if (colour != null) style = style.withColor(colour & 0xFFFFFF);
                if (!sink.accept(i, style, cp)) return false;
            }
            return true;
        };
    }

    // ------------------------------------------------------------------------------------------------ layout

    /** Where the menu is: its own box, widened by the Storage Overlay (or any Sky2M menu replacement) when it's on. */
    private static int[] menuBounds(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return null;
        Sky2MContainerScreenAccessor a = (Sky2MContainerScreenAccessor) container;
        int left = a.sky2m$getLeftPos(), right = left + a.sky2m$getImageWidth();
        CustomGui custom = CustomGui.get(screen);
        if (custom != null) {
            List<CustomGui.Rect> bounds = custom.getBounds();
            if (bounds != null && !bounds.isEmpty()) {
                left = Integer.MAX_VALUE;
                right = Integer.MIN_VALUE;
                for (CustomGui.Rect r : bounds) {
                    left = Math.min(left, r.x());
                    right = Math.max(right, r.x() + r.width());
                }
            }
        }
        return new int[]{left, right, a.sky2m$getTopPos()};
    }

    /** Things the items must not cover: other widgets beside the menu, and the potion effects in your inventory. */
    private static void findZones(Screen screen, int[] menu) {
        zones.clear();
        for (GuiEventListener child : screen.children()) {
            if (!(child instanceof AbstractWidget w) || child == box || child instanceof Renderer || !w.visible) continue;
            // Widgets over the menu itself (its buttons) don't matter: the list is beside it.
            if (w.getX() >= menu[0] && w.getX() + w.getWidth() <= menu[1]) continue;
            zones.add(new Rect(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
        }
        Minecraft mc = Minecraft.getInstance();
        if (screen instanceof InventoryScreen inv && inv.showsActiveEffects() && mc.player != null && !mc.player.getActiveEffects().isEmpty()) {
            int count = mc.player.getActiveEffects().size();
            int x = menu[1] + 2, wide = screen.width - x >= 120 ? 120 : 32, step = count > 5 ? 132 / (count - 1) : 33;
            for (int i = 0; i < count; i++) zones.add(new Rect(x, menu[2] + i * step, wide, 32));
        }
    }

    private static void layout(Screen screen) {
        FeatureConfigs.ItemList c = config();
        mainRect = favRect = null;
        int[] menu = menuBounds(screen);
        if (menu == null || c == null || c.hidden) return;
        findZones(screen, menu);
        boolean right = c.side == FeatureConfigs.ItemList.Side.RIGHT;
        int rightSpace = screen.width - menu[1], leftSpace = menu[0];
        int mainW = (int) ((right ? rightSpace : leftSpace) * c.maxWidth) - 2;
        if (mainW >= MIN_WIDTH) mainRect = new Rect(right ? screen.width - mainW - 2 : 0, 0, mainW, screen.height);
        int favW = (int) ((right ? leftSpace : rightSpace) * c.maxWidth) - 2;
        if (c.favourites && favW >= MIN_WIDTH) favRect = new Rect(right ? 0 : screen.width - favW - 2, 0, favW, screen.height);

        if (mainRect != null) {
            boxRect = new Rect(mainRect.x() + 2, mainRect.y() + mainRect.h() - 20, mainRect.w() - 22, 16);
            filterRect = new Rect(boxRect.x() + boxRect.w() - 16, boxRect.y(), 16, 16);
            settingsRect = new Rect(boxRect.x() + boxRect.w() + 2, boxRect.y(), 16, 16);
            box.setX(boxRect.x());
            box.setY(boxRect.y());
            box.setWidth(boxRect.w() - 17);
            box.visible = true;
            cells(mainCells, mainRect, mainRect.y() + TOP, c.itemSize);
        } else {
            box.visible = false;
            if (box.isFocused()) box.setFocused(false);
        }

        if (favRect != null) {
            int top = favRect.y() + TOP;
            pinnedRect = null;
            ItemListData.Recipe pinned = ItemListFavourites.pinned();
            if (pinned == null) pinnedCard = null;
            else if (pinnedCard == null || pinnedCard.recipe != pinned) pinnedCard = new ItemListCard(pinned);
            if (pinnedCard != null) {
                int px;
                if (favRect.w() >= ItemListCard.WIDTH) px = favRect.x() + (favRect.w() - ItemListCard.WIDTH) / 2;
                else px = favRect.x() + favRect.w() - ItemListCard.WIDTH;
                pinnedRect = new Rect(px, 5, ItemListCard.WIDTH, pinnedCard.height());
                top = pinnedRect.y() + pinnedRect.h() + 4 + TOP - 4;
            }
            cells(favCells, new Rect(favRect.x(), top - TOP, favRect.w(), favRect.y() + favRect.h() - (top - TOP)), top, c.favouritesItemSize);
        }
    }

    /** The grid cells of a panel that aren't covered by anything, row by row. */
    private static void cells(List<Rect> out, Rect panel, int top, int size) {
        out.clear();
        int left = panel.x() + 4, width = panel.w() - 8, bottom = panel.y() + panel.h() - BOTTOM;
        int cols = Math.max(1, width / size), rows = Math.max(0, (bottom - top) / size);
        int pad = (width - cols * size) / 2;
        for (int r = 0; r < rows; r++) {
            for (int col = 0; col < cols; col++) {
                int x = left + pad + col * size, y = top + r * size;
                boolean covered = false;
                for (Rect z : zones) if (z.intersects(x, y, size, size)) covered = true;
                if (!covered) out.add(new Rect(x, y, size, size));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** Draws both panels as part of the menu (so the menu's tooltips and carried item go over it). */
    private static final class Renderer extends AbstractWidget {
        private final Screen screen;

        Renderer(Screen screen) {
            super(0, 0, 0, 0, Component.empty());
            this.screen = screen;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            refreshSearch();
            layout(screen);
            hoveredId = null;
            hoveredRecipe = null;
            Font font = Minecraft.getInstance().font;
            if (mainRect != null) drawMain(g, font, mouseX, mouseY);
            if (favRect != null) drawFavourites(g, font, mouseX, mouseY);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

    private static void drawMain(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        FeatureConfigs.ItemList c = config();
        List<ItemListData.Entry> list = listVisible() ? results : List.of();
        int perPage = Math.max(1, mainCells.size());
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        mainPage = Math.clamp(mainPage, 0, pages - 1);

        prevRect = nextRect = null;
        if (!ItemListData.loaded()) {
            String status = ItemListData.status();
            g.centeredText(font, status.isEmpty() ? "Loading items..." : status, mainRect.x() + mainRect.w() / 2, mainRect.y() + mainRect.h() / 2, 0xFFAAAAAA);
        } else if (listVisible()) {
            pageBar(g, font, mainRect, mainPage, pages, mouseX, mouseY, true);
            for (int i = 0; i < mainCells.size(); i++) {
                int index = mainPage * perPage + i;
                if (index >= list.size()) break;
                ItemListData.Entry e = list.get(index);
                drawItem(g, font, mainCells.get(i), ItemListStacks.stack(e), e.id(), false, mouseX, mouseY);
            }
        }

        // The search bar: red when nothing matches, the sum's result on the right.
        boolean nothing = listVisible() && ItemListData.loaded() && list.isEmpty() && calcResult == null;
        box.setTextColor(nothing ? 0xFFFF5555 : 0xFFE0E0E0);
        if (calcResult != null) {
            int colour = calcOk ? 0xFFFFFF55 : 0xFFFF5555;
            g.text(font, calcResult, boxRect.x() + boxRect.w() - 18 - font.width(calcResult), boxRect.y() + 4, colour, true);
        }
        // Filter: white for all, green for a category.
        boolean overFilter = filterRect.contains(mouseX, mouseY);
        g.fill(filterRect.x(), filterRect.y(), filterRect.x() + 16, filterRect.y() + 16, overFilter ? ItemListCard.BUTTON_HOVER : ItemListCard.BUTTON);
        g.outline(filterRect.x(), filterRect.y(), 16, 16, ItemListCard.EDGE);
        funnel(g, filterRect.x() + 4, filterRect.y() + 4, filter == ItemListData.Category.ALL ? 0xFFFFFFFF : 0xFF55FF55);
        if (overFilter) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal("Filter: " + filter.label).withStyle(ChatFormatting.GREEN));
            for (ItemListData.Category cat : ItemListData.Category.values()) {
                tip.add(Component.literal((cat == filter ? "> " : "  ") + cat.label).withStyle(cat == filter ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
            }
            tip.add(Component.literal("Click: next  ·  Right click: All").withStyle(ChatFormatting.GRAY));
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
        boolean overSettings = settingsRect.contains(mouseX, mouseY);
        g.fill(settingsRect.x(), settingsRect.y(), settingsRect.x() + 16, settingsRect.y() + 16, overSettings ? ItemListCard.BUTTON_HOVER : ItemListCard.BUTTON);
        g.outline(settingsRect.x(), settingsRect.y(), 16, 16, ItemListCard.EDGE);
        g.centeredText(font, "⚙", settingsRect.x() + 8, settingsRect.y() + 4, 0xFFFFFFFF);
        if (overSettings) g.setTooltipForNextFrame(font, Component.literal("Item List settings"), mouseX, mouseY);
        if (c != null && c.itemSize != lastSize) {
            lastSize = c.itemSize;
            sizeShownUntil = System.currentTimeMillis() + 750;
        }
        if (System.currentTimeMillis() < sizeShownUntil && c != null) {
            g.centeredText(font, Math.round(c.itemSize / 18f * 100) + "%", mainRect.x() + mainRect.w() / 2, mainRect.y() + mainRect.h() / 2, 0xFFFFFFFF);
        }
    }

    private static int lastSize = -1;
    private static long sizeShownUntil;

    /** A small funnel for the filter button. */
    private static void funnel(GuiGraphicsExtractor g, int x, int y, int colour) {
        g.fill(x, y, x + 8, y + 2, colour);
        g.fill(x + 1, y + 2, x + 7, y + 3, colour);
        g.fill(x + 2, y + 3, x + 6, y + 4, colour);
        g.fill(x + 3, y + 4, x + 5, y + 8, colour);
    }

    private static void pageBar(GuiGraphicsExtractor g, Font font, Rect panel, int page, int pages, int mouseX, int mouseY, boolean main) {
        int y = panel.y() + 5;
        g.centeredText(font, (page + 1) + "/" + pages, panel.x() + panel.w() / 2, y + 2, 0xFFFFFFFF);
        Rect prev = new Rect(panel.x() + 6, y, 14, 12), next = new Rect(panel.x() + panel.w() - 20, y, 14, 12);
        for (Rect r : new Rect[]{prev, next}) {
            boolean over = r.contains(mouseX, mouseY);
            g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), over ? ItemListCard.BUTTON_HOVER : ItemListCard.BUTTON);
            g.outline(r.x(), r.y(), r.w(), r.h(), ItemListCard.EDGE);
            g.centeredText(font, r == prev ? "◀" : "▶", r.x() + r.w() / 2, r.y() + 2, 0xFFFFFFFF);
        }
        if (main) {
            prevRect = prev;
            nextRect = next;
        } else {
            favPrevRect = prev;
            favNextRect = next;
        }
    }

    private static void drawItem(GuiGraphicsExtractor g, Font font, Rect cell, ItemStack stack, String id, boolean recipeTile, int mouseX, int mouseY) {
        boolean over = cell.contains(mouseX, mouseY);
        if (recipeTile) {
            g.fill(cell.x(), cell.y(), cell.x() + cell.w(), cell.y() + cell.h(), 0x603B5B8B);
            g.outline(cell.x(), cell.y(), cell.w(), cell.h(), ItemListCard.EDGE);
        }
        if (over) g.fill(cell.x(), cell.y(), cell.x() + cell.w(), cell.y() + cell.h(), 0x40FFFFFF);
        float scale = (cell.w() - 2) / 16f;
        g.pose().pushMatrix();
        g.pose().translate(cell.x() + 1, cell.y() + 1);
        g.pose().scale(scale, scale);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
        if (over) {
            hoveredId = id;
            List<Component> tip = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
            if (recipeTile) tip.add(Component.literal("[Ctrl + Click to pin this recipe]").withStyle(ChatFormatting.GRAY));
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    /** What the Favourites panel shows: items, then recipes (by what they make). */
    private record Favourite(String id, ItemListData.Recipe recipe) {}

    private static List<Favourite> favourites() {
        List<Favourite> out = new ArrayList<>();
        for (String id : ItemListFavourites.items()) if (ItemListData.entry(id) != null) out.add(new Favourite(id, null));
        for (ItemListData.Recipe r : ItemListFavourites.recipes()) {
            if (!r.outputs().isEmpty()) out.add(new Favourite(r.outputs().getFirst().id(), r));
        }
        return out;
    }

    private static void drawFavourites(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        favPrevRect = favNextRect = null;
        if (pinnedCard != null && pinnedRect != null) {
            String id = pinnedCard.draw(g, font, pinnedRect.x(), pinnedRect.y(), mouseX, mouseY);
            if (id != null) hoveredId = id;
            // The pinned card's own buttons: unpin.
            Rect unpin = new Rect(pinnedRect.x() + 3, pinnedRect.y() + pinnedRect.h() - 16, 13, 13);
            boolean over = unpin.contains(mouseX, mouseY);
            g.fill(unpin.x(), unpin.y(), unpin.x() + 13, unpin.y() + 13, over ? ItemListCard.BUTTON_HOVER : ItemListCard.BUTTON);
            g.outline(unpin.x(), unpin.y(), 13, 13, ItemListCard.EDGE);
            g.centeredText(font, "x", unpin.x() + 7, unpin.y() + 2, 0xFFFFFFFF);
            if (over) g.setTooltipForNextFrame(font, Component.literal("Unpin recipe"), mouseX, mouseY);
        }
        List<Favourite> favs = favourites();
        if (favs.isEmpty()) return;
        int perPage = Math.max(1, favCells.size());
        int pages = Math.max(1, (favs.size() + perPage - 1) / perPage);
        favPage = Math.clamp(favPage, 0, pages - 1);
        if (pages > 1) {
            Rect bar = new Rect(favRect.x(), favCells.isEmpty() ? favRect.y() : favCells.getFirst().y() - TOP, favRect.w(), TOP);
            pageBar(g, font, bar, favPage, pages, mouseX, mouseY, false);
        }
        for (int i = 0; i < favCells.size(); i++) {
            int index = favPage * perPage + i;
            if (index >= favs.size()) break;
            Favourite f = favs.get(index);
            drawItem(g, font, favCells.get(i), ItemListStacks.stack(f.id()), f.id(), f.recipe() != null, mouseX, mouseY);
            if (f.recipe() != null && favCells.get(i).contains(mouseX, mouseY)) hoveredRecipe = f.recipe();
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    /** A click on the menu: handled (true) if it was on the Item List. */
    private static boolean click(Screen screen, MouseButtonEvent event) {
        if (box == null || config() == null) return false;
        double x = event.x(), y = event.y();
        // Clicking anywhere but the search bar stops typing into it.
        if (boxRect == null || !boxRect.contains(x, y) || mainRect == null) {
            if (box.isFocused()) {
                box.setFocused(false);
                screen.setFocused(null);
            }
        }
        if (mainRect != null && mainRect.contains(x, y)) {
            if (filterRect.contains(x, y)) {
                ItemListData.Category[] all = ItemListData.Category.values();
                filter = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT ? ItemListData.Category.ALL : all[(filter.ordinal() + 1) % all.length];
                config().lastFilter = filter.name();
                mainPage = 0;
                refreshSearch();
                return true;
            }
            if (settingsRect.contains(x, y)) {
                Sky2MConfig.openGui("Item List");
                return true;
            }
            if (boxRect.contains(x, y)) {
                if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) box.setValue("");
                return false;
            }
            if (prevRect != null && prevRect.contains(x, y)) {
                mainPage--;
                if (mainPage < 0) mainPage = Integer.MAX_VALUE;
                return true;
            }
            if (nextRect != null && nextRect.contains(x, y)) {
                mainPage = turnForward(mainPage, results.size(), mainCells.size());
                return true;
            }
            for (int i = 0; i < mainCells.size() && listVisible(); i++) {
                if (!mainCells.get(i).contains(x, y)) continue;
                int index = mainPage * Math.max(1, mainCells.size()) + i;
                if (index < results.size()) {
                    click(event.button(), results.get(index).id(), screen);
                    return true;
                }
            }
        }
        if (favRect != null && favRect.contains(x, y)) {
            if (pinnedCard != null && pinnedRect != null && pinnedRect.contains(x, y)) {
                Rect unpin = new Rect(pinnedRect.x() + 3, pinnedRect.y() + pinnedRect.h() - 16, 13, 13);
                if (unpin.contains(x, y)) {
                    ItemListFavourites.pin(null);
                    return true;
                }
                String id = pinnedCard.click(pinnedRect.x(), pinnedRect.y(), x, y);
                if (id != null && !id.isEmpty()) click(event.button(), id, screen);
                return true;
            }
            if (favPrevRect != null && favPrevRect.contains(x, y)) {
                favPage = Math.max(0, favPage - 1);
                return true;
            }
            if (favNextRect != null && favNextRect.contains(x, y)) {
                favPage = turnForward(favPage, favourites().size(), favCells.size());
                return true;
            }
            List<Favourite> favs = favourites();
            for (int i = 0; i < favCells.size(); i++) {
                if (!favCells.get(i).contains(x, y)) continue;
                int index = favPage * Math.max(1, favCells.size()) + i;
                if (index >= favs.size()) break;
                Favourite f = favs.get(index);
                if (f.recipe() != null && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && event.hasControlDown()) {
                    ItemListFavourites.pin(ItemListFavourites.isPinned(f.recipe()) ? null : f.recipe());
                } else if (f.recipe() != null && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                    open(screen, ItemListStacks.stack(f.id()).getHoverName().getString(), List.of(f.recipe()));
                } else {
                    click(event.button(), f.id(), screen);
                }
                return true;
            }
        }
        return false;
    }

    private static int turnForward(int page, int items, int perPage) {
        int pages = Math.max(1, (items + Math.max(1, perPage) - 1) / Math.max(1, perPage));
        return page + 1 >= pages ? 0 : page + 1;
    }

    private static boolean scroll(double x, double y, double amount) {
        FeatureConfigs.ItemList c = config();
        if (c == null) return false;
        boolean ctrl = Minecraft.getInstance().hasControlDown();
        if (mainRect != null && mainRect.contains(x, y) && (boxRect == null || y < boxRect.y())) {
            if (ctrl) c.itemSize = Math.clamp(c.itemSize + (amount > 0 ? 1 : -1), 9, 54);
            else if (listVisible()) mainPage = amount < 0 ? turnForward(mainPage, results.size(), mainCells.size())
                : (mainPage == 0 ? Integer.MAX_VALUE : mainPage - 1);
            return true;
        }
        if (favRect != null && favRect.contains(x, y)) {
            if (ctrl) c.favouritesItemSize = Math.clamp(c.favouritesItemSize + (amount > 0 ? 1 : -1), 9, 54);
            else favPage = amount < 0 ? turnForward(favPage, favourites().size(), favCells.size()) : Math.max(0, favPage - 1);
            return true;
        }
        return false;
    }

    /** A key on the menu: handled (true) if it was for the Item List. */
    private static boolean keyPressed(Screen screen, KeyEvent event) {
        FeatureConfigs.ItemList c = config();
        if (c == null || box == null) return false;
        int key = event.key();
        if (key == c.hideKey && event.hasControlDown()) {
            c.hidden = !c.hidden;
            if (c.hidden && box.isFocused()) box.setFocused(false);
            box.visible = !c.hidden;
            return true;
        }
        if (c.hidden) return false;
        if (box.isFocused()) {
            if (event.isEscape()) {
                box.setFocused(false);
                screen.setFocused(null);
                return true;
            }
            if (event.isConfirmation()) {
                if (c.enterReplaces && calcOk && calcResult != null) {
                    String result = ItemListCalculator.plain(calcResult);
                    box.setValue(c.calculatorNeedsEquals ? "=" + result : result);
                    box.moveCursorToEnd(false);
                }
                return true;
            }
            // Everything else types into the search, so E doesn't close the menu and numbers don't move items.
            box.keyPressed(event);
            return true;
        }
        if (key == c.searchKey && key != -1 && mainRect != null) {
            box.setFocused(true);
            screen.setFocused(box);
            return true;
        }
        // Over a favourite recipe: the Favourite key takes it out of the favourites.
        if (hoveredRecipe != null && key == c.favouriteKey) {
            ItemListFavourites.toggleRecipe(hoveredRecipe);
            return true;
        }
        String id = hoveredId;
        if (id == null && screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = ((Sky2MContainerScreenAccessor) container).sky2m$getHoveredSlot();
            if (slot != null && slot.hasItem()) id = ItemListIds.of(slot.getItem());
        }
        return id != null && key(key, id, screen);
    }

    /** The Recipe, Usage or Favourite key over the item {@code id}: true if it was one of them. */
    static boolean key(int key, String id, Screen parent) {
        FeatureConfigs.ItemList c = config();
        if (c == null || id == null || key == -1) return false;
        if (key == c.recipeKey) {
            recipes(id, parent);
            return true;
        }
        if (key == c.usageKey) {
            usages(id, parent);
            return true;
        }
        if (key == c.favouriteKey) {
            ItemListFavourites.toggleItem(id);
            return true;
        }
        return false;
    }

    /** MoulConfig stores mouse buttons as keys below -1: button 0 is -100. */
    static boolean matchesMouse(int key, int button) {
        return key == -100 + button;
    }

    /** Clicking an item (in the list, a favourite, or on a recipe) with mouse {@code button}. */
    static void click(int button, String id, Screen parent) {
        FeatureConfigs.ItemList c = config();
        if (c == null) return;
        FeatureConfigs.ItemList.ClickAction action = switch (button) {
            case 0 -> c.leftClick;
            case 1 -> c.rightClick;
            case 2 -> c.middleClick;
            default -> FeatureConfigs.ItemList.ClickAction.NOTHING;
        };
        switch (action) {
            case RECIPE -> recipes(id, parent);
            case USAGE -> usages(id, parent);
            case WIKI -> wiki(id, parent);
            case NOTHING -> { }
        }
    }

    private static String name(String id) {
        ItemListData.Entry e = ItemListData.entry(id);
        return e == null ? id : ItemListData.strip(e.name()).replace("{LVL}", "100");
    }

    /** The recipes that make {@code id}; for a mob or NPC, its drops or shop. */
    static void recipes(String id, Screen parent) {
        ItemListData.Entry e = ItemListData.entry(id);
        boolean creature = e != null && (e.category() == ItemListData.Category.MOB || e.category() == ItemListData.Category.NPC);
        List<ItemListData.Recipe> list = creature ? ItemListData.usages(id) : ItemListData.recipes(id);
        if (list.isEmpty()) {
            message("No recipes found for ", name(id));
            return;
        }
        open(parent, name(id), list);
    }

    static void usages(String id, Screen parent) {
        List<ItemListData.Recipe> list = ItemListData.usages(id);
        if (list.isEmpty()) {
            message("No usages found for ", name(id));
            return;
        }
        open(parent, "Uses of " + name(id), list);
    }

    private static void open(Screen parent, String title, List<ItemListData.Recipe> recipes) {
        Minecraft.getInstance().gui.setScreen(new ItemListRecipeScreen(parent, title, recipes));
    }

    private static void wiki(String id, Screen parent) {
        ItemListData.Entry e = ItemListData.entry(id);
        String url = e == null ? null : e.wiki();
        if (url == null || url.isBlank()) {
            message("No wiki page found for ", name(id));
            return;
        }
        ConfirmLinkScreen.confirmLinkNow(parent, java.net.URI.create(url));
    }

    private static void message(String text, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.sendSystemMessage(Compat.PREFIX.get().append(Component.literal(text).withStyle(ChatFormatting.RED))
            .append(Component.literal(name).withStyle(ChatFormatting.LIGHT_PURPLE)).append(Component.literal("!").withStyle(ChatFormatting.RED)));
    }
}
