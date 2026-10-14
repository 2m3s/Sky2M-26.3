package com.epic60869.sky2m.features.portfolio;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import com.epic60869.sky2m.mixin.Sky2MDeferredTooltipAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bazaar Utils' bookmarks: a ★ over the top-left slot of a Bazaar product page bookmarks (or un-bookmarks) that item,
 * and your bookmarks sit in a column to the right of every Bazaar menu. Hover one for its instant buy and sell prices,
 * click it to open it (clicking it in the menu when it's on the page, /bz otherwise), shift-click to remove it.
 */
public final class BazaarBookmarks {
    private static final int TOGGLE_SLOT = 0;
    private static final int DISPLAY_SLOT = 13;
    private static final int GRAPHS_SLOT = 33;
    private static final int BUTTON = 18;
    private static final int SPACING = 2;

    /** Whether the open menu chain started at the Bazaar; reset once no screen is open. */
    private static boolean inBazaar;
    /** Icons from product pages seen this session, so a bookmark looks like it does in the menu. */
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    private BazaarBookmarks() {}

    public static final class Bookmark {
        @com.google.gson.annotations.Expose
        public String id;
        @com.google.gson.annotations.Expose
        public String name;

        public Bookmark() {}

        Bookmark(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static Sky2MConfig.BazaarNotificationsSettings config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.bazaar;
    }

    private static boolean enabled() {
        Sky2MConfig.BazaarNotificationsSettings c = config();
        return c != null && c.bookmarks && Compat.isOnSkyblock();
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.gui.screen() == null) inBazaar = false;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container)) return;
            if (title(container).startsWith("Bazaar")) inBazaar = true;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(container, g, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(container, event));
        });
    }

    // ------------------------------------------------------------------------------------------------ screens

    private static String title(ContainerScreen screen) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return title == null ? "" : title.trim();
    }

    private static String name(ItemStack stack) {
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        return name == null ? "" : name.trim();
    }

    private static boolean isProductPage(ContainerScreen screen) {
        var slots = screen.getMenu().slots;
        if (slots.size() <= GRAPHS_SLOT || !title(screen).contains("➜")) return false;
        ItemStack graphs = slots.get(GRAPHS_SLOT).getItem();
        return graphs.is(Items.PAPER) && name(graphs).equals("View Graphs") && slots.get(DISPLAY_SLOT).hasItem();
    }

    private static boolean isBazaar(ContainerScreen screen) {
        if (inBazaar) return true;
        if (isProductPage(screen)) return inBazaar = true;
        return false;
    }

    /** The product on the open product page, or null. */
    private static Bookmark currentProduct(ContainerScreen screen) {
        if (!isProductPage(screen)) return null;
        ItemStack display = screen.getMenu().slots.get(DISPLAY_SLOT).getItem();
        String name = name(display);
        String id = Portfolio.bazaarProductId(name);
        if (id == null || id.isBlank()) id = RepoItems.idByName(name);
        if (id == null || id.isBlank()) return null;
        ICONS.putIfAbsent(id, display.copyWithCount(1));
        return new Bookmark(id, name);
    }

    private static Bookmark find(String id) {
        Sky2MConfig.BazaarNotificationsSettings c = config();
        if (c == null) return null;
        for (Bookmark bookmark : c.bookmarkedItems) {
            if (id.equals(bookmark.id)) return bookmark;
        }
        return null;
    }

    private static ItemStack icon(Bookmark bookmark) {
        return ICONS.computeIfAbsent(bookmark.id, id -> {
            ItemStack stack = RepoItems.itemStack(id);
            return stack == null || stack.isEmpty() ? new ItemStack(Items.PAPER) : stack;
        });
    }

    // ------------------------------------------------------------------------------------------------ layout

    private static int columnX(ContainerScreen screen) {
        Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
        return access.sky2m$getLeftPos() + access.sky2m$getImageWidth() + SPACING;
    }

    private static int columnY(ContainerScreen screen) {
        return ((Sky2MContainerScreenAccessor) screen).sky2m$getTopPos() + SPACING;
    }

    /** How many bookmarks fit down the screen. */
    private static int shown(ContainerScreen screen, int count) {
        int room = (screen.height - columnY(screen)) / (BUTTON + SPACING);
        return Math.max(0, Math.min(count, room));
    }

    private static int bookmarkAt(ContainerScreen screen, List<Bookmark> bookmarks, double mouseX, double mouseY) {
        int x = columnX(screen);
        int y = columnY(screen);
        if (mouseX < x || mouseX >= x + BUTTON || mouseY < y) return -1;
        int index = (int) ((mouseY - y) / (BUTTON + SPACING));
        if (index >= shown(screen, bookmarks.size()) || mouseY - y - index * (BUTTON + SPACING) >= BUTTON) return -1;
        return index;
    }

    private static Slot toggleSlot(ContainerScreen screen) {
        return screen.getMenu().slots.get(TOGGLE_SLOT);
    }

    private static boolean overToggle(ContainerScreen screen, double mouseX, double mouseY) {
        Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
        Slot slot = toggleSlot(screen);
        double x = mouseX - access.sky2m$getLeftPos() - slot.x;
        double y = mouseY - access.sky2m$getTopPos() - slot.y;
        return x >= -1 && x < 17 && y >= -1 && y < 17;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void render(ContainerScreen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!enabled() || !isBazaar(screen)) return;
        Sky2MConfig.BazaarNotificationsSettings c = config();
        Portfolio.refreshPrices(false);
        var font = Minecraft.getInstance().font;
        List<Component> tooltip = null;

        Bookmark product = currentProduct(screen);
        if (product != null) {
            boolean bookmarked = find(product.id) != null;
            Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
            Slot slot = toggleSlot(screen);
            int x = access.sky2m$getLeftPos() + slot.x;
            int y = access.sky2m$getTopPos() + slot.y;
            boolean hovered = overToggle(screen, mouseX, mouseY);
            g.fill(x, y, x + 16, y + 16, hovered ? 0xFF6E6E6E : 0xFF8B8B8B);
            String star = "★";
            g.text(font, star, x + 8 - font.width(star) / 2, y + 4, bookmarked ? 0xFFFFD700 : 0xFF3A3A3A, bookmarked);
            if (hovered) {
                tooltip = List.of(
                    Component.literal(bookmarked ? "Remove " + product.name + " Bookmark" : "Bookmark " + product.name)
                        .withStyle(bookmarked ? ChatFormatting.RED : ChatFormatting.GOLD),
                    Component.literal(bookmarked ? "Click to remove it from your bookmarks." : "Click to add it to your bookmarks.")
                        .withStyle(ChatFormatting.GRAY));
            }
        }

        List<Bookmark> bookmarks = c.bookmarkedItems;
        int x = columnX(screen);
        if (!bookmarks.isEmpty() && x + BUTTON <= screen.width) {
            int hovered = bookmarkAt(screen, bookmarks, mouseX, mouseY);
            int y = columnY(screen);
            for (int i = 0; i < shown(screen, bookmarks.size()); i++) {
                Bookmark bookmark = bookmarks.get(i);
                int by = y + i * (BUTTON + SPACING);
                g.fill(x, by, x + BUTTON, by + BUTTON, i == hovered ? 0xE0505050 : 0xD0101010);
                g.outline(x, by, BUTTON, BUTTON, product != null && product.id.equals(bookmark.id) ? 0xFFFFD700 : 0xFF404040);
                g.item(icon(bookmark), x + 1, by + 1);
            }
            if (hovered >= 0) tooltip = bookmarkTooltip(bookmarks.get(hovered));
        }

        if (tooltip != null) {
            ((Sky2MDeferredTooltipAccessor) g).sky2m$setDeferredTooltip(null);
            g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
    }

    private static List<Component> bookmarkTooltip(Bookmark bookmark) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(bookmark.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        // Instant buy fills from the lowest sell offer, instant sell from the highest buy order.
        lines.add(priceLine("Insta Buy: ", Portfolio.bazaarOrderBookPrice(bookmark.id, false)));
        lines.add(priceLine("Insta Sell: ", Portfolio.bazaarOrderBookPrice(bookmark.id, true)));
        lines.add(Component.empty());
        lines.add(Component.literal("Click to open!").withStyle(ChatFormatting.YELLOW));
        lines.add(Component.literal("Shift-click to remove.").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private static Component priceLine(String label, double price) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY)
            .append(Component.literal(price > 0 ? Portfolio.coins(price) + " coins" : "unknown").withStyle(ChatFormatting.GOLD));
    }

    // ------------------------------------------------------------------------------------------------ clicks

    /** True when the click was ours, so the menu doesn't get it. */
    private static boolean click(ContainerScreen screen, MouseButtonEvent event) {
        if (!enabled() || !isBazaar(screen)) return false;
        Sky2MConfig.BazaarNotificationsSettings c = config();

        Bookmark product = currentProduct(screen);
        if (product != null && overToggle(screen, event.x(), event.y())) {
            Bookmark existing = find(product.id);
            if (existing != null) c.bookmarkedItems.remove(existing);
            else c.bookmarkedItems.add(product);
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
            clickSound();
            Sky2MAlerts.chat(Component.literal(existing != null ? "Removed bookmark: " : "Bookmarked: ")
                .withStyle(existing != null ? ChatFormatting.YELLOW : ChatFormatting.GREEN)
                .append(Component.literal(product.name).withStyle(ChatFormatting.GOLD)));
            return true;
        }

        int index = bookmarkAt(screen, c.bookmarkedItems, event.x(), event.y());
        if (index < 0) return false;
        Bookmark bookmark = c.bookmarkedItems.get(index);
        clickSound();
        if (event.hasShiftDown()) {
            c.bookmarkedItems.remove(index);
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
            return true;
        }
        open(screen, bookmark);
        return true;
    }

    /** Clicks the item when it's in the open menu, so the page opens without a command; /bz searches for it otherwise. */
    private static void open(ContainerScreen screen, Bookmark bookmark) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) return;
        if (!isProductPage(screen)) {
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container instanceof Inventory || !slot.hasItem()) continue;
                if (name(slot.getItem()).equals(bookmark.name)) {
                    mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, 0, ContainerInput.PICKUP, mc.player);
                    return;
                }
            }
        }
        mc.getConnection().sendCommand("bz " + bookmark.name);
    }

    private static void clickSound() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }
}
