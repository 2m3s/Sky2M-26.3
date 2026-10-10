// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StorageOverlayScreen.kt.
// The MoulConfig search field and "Edit Pages" button are vanilla widgets here.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class StorageOverlayScreen extends Screen {
    public static final int PLAYER_WIDTH = 184;
    public static final int PLAYER_HEIGHT = 91;
    public static final int PLAYER_Y_INSET = 3;
    public static final int SLOT_SIZE = 18;
    public static final int PADDING = 10;
    public static final int PAGE_SLOTS_WIDTH = SLOT_SIZE * 9;
    public static final int PAGE_WIDTH = PAGE_SLOTS_WIDTH + 4;
    public static final int HOTBAR_X = 12;
    public static final int HOTBAR_Y = 67;
    public static final int MAIN_INVENTORY_Y = 9;
    public static final int SCROLL_BAR_WIDTH = 8;
    public static final int SCROLL_BAR_HEIGHT = 16;
    public static final int CONTROL_X_INSET = 3;
    public static final int CONTROL_Y_INSET = 5;
    public static final int CONTROL_WIDTH = 70;
    public static final int CONTROL_BACKGROUND_WIDTH = CONTROL_WIDTH + CONTROL_X_INSET + 1;
    public static final int CONTROL_HEIGHT = 50;

    public static float scroll = 0F;
    public static int lastRenderedInnerHeight = 0;
    /** The search text, kept between screens like Firmament's searchText property. */
    public static String searchText = "";

    public static void resetScroll() {
        if (!StorageOverlay.config().retainScroll) scroll = 0F;
    }

    public boolean isExiting = false;
    public int pageWidthCount = StorageOverlay.config().columns;

    public final class Measurements {
        public final int innerScrollPanelWidth = PAGE_WIDTH * pageWidthCount + (pageWidthCount - 1) * PADDING;
        public final int overviewWidth = innerScrollPanelWidth + 3 * PADDING + SCROLL_BAR_WIDTH;
        public final int x = width / 2 - overviewWidth / 2;
        public final int overviewHeight = Math.min(height - PLAYER_HEIGHT - Math.min(80, height / 10), StorageOverlay.config().height);
        public final int innerScrollPanelHeight = overviewHeight - PADDING * 2;
        public final int y = height / 2 - (overviewHeight + PLAYER_HEIGHT) / 2;
        public final int playerX = width / 2 - PLAYER_WIDTH / 2;
        public final int playerY = y + overviewHeight - PLAYER_Y_INSET;
        public final int controlX = playerX - CONTROL_WIDTH + CONTROL_X_INSET;
        public final int controlY = playerY - CONTROL_Y_INSET;
        public final int totalWidth = overviewWidth;
        public final int totalHeight = overviewHeight - PLAYER_Y_INSET + PLAYER_HEIGHT;
    }

    public Measurements measurements = new Measurements();

    public static final Identifier PLAYER_INVENTORY_SPRITE = Identifier.parse("sky2m:storageoverlay/player_inventory");
    public static final Identifier UPPER_BACKGROUND_SPRITE = Identifier.parse("sky2m:storageoverlay/upper_background");
    public static final Identifier SLOT_ROW_SPRITE = Identifier.parse("sky2m:storageoverlay/storage_row");
    public static final Identifier SCROLLBAR_BACKGROUND = Identifier.parse("sky2m:storageoverlay/scroll_bar_background");
    public static final Identifier SCROLLBAR_KNOB = Identifier.parse("sky2m:storageoverlay/scroll_bar_knob");
    public static final Identifier CONTROLLER_BACKGROUND = Identifier.parse("sky2m:storageoverlay/storage_controls");

    public final EditBox searchField;
    public final Button editButton;
    /** The screen the controls are part of: this one, or the container screen the overlay is attached to. */
    private Screen host = this;
    private boolean knobGrabbed;

    public StorageOverlayScreen() {
        super(Component.literal(""));
        Minecraft mc = Minecraft.getInstance();
        searchField = new EditBox(mc.font, 0, 0, CONTROL_WIDTH - 10, 14, Component.literal("Search..."));
        searchField.setHint(Component.literal("Search...").withStyle(ChatFormatting.DARK_GRAY));
        searchField.setMaxLength(100);
        searchField.setValue(searchText);
        searchField.setResponder(text -> {
            searchText = text;
            layoutedForEach(StorageData.data(), (rect, page, inventory) -> {});
            coerceScroll(0F);
        });
        editButton = Button.builder(Component.literal("Edit Pages"), b -> editPages()).bounds(0, 0, CONTROL_WIDTH - 10, 16).build();
    }

    @Override
    public void init() {
        super.init();
        pageWidthCount = Math.max(1, Math.min(StorageOverlay.config().columns, (width - PADDING) / (PAGE_WIDTH + PADDING)));
        measurements = new Measurements();
        // Before the first render the content height isn't known yet: don't throw away the retained scroll.
        if (lastRenderedInnerHeight > 0) scroll = Math.max(0F, Math.min(scroll, getMaxScroll()));
        placeControls();
        if (host == this) {
            addWidget(searchField);
            addWidget(editButton);
        }
    }

    /** Used by the overlay on a container screen: the controls belong to that screen then. */
    public void setHost(Screen host) {
        this.host = host;
    }

    private void placeControls() {
        searchField.setX(measurements.controlX + 5);
        searchField.setY(measurements.controlY + 8);
        editButton.setX(measurements.controlX + 5);
        editButton.setY(measurements.controlY + 26);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        coerceScroll((float) StorageOverlay.adjustScrollSpeed(verticalAmount));
        return true;
    }

    public void coerceScroll(float offset) {
        scroll = Math.max(0F, Math.min(scroll + offset, getMaxScroll()));
    }

    public float getMaxScroll() {
        return lastRenderedInnerHeight - getScrollPanelInner().height();
    }

    @Override
    public void onClose() {
        isExiting = true;
        resetScroll();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        drawBackgrounds(context);
        drawPages(context, mouseX, mouseY, delta, null, null, 0, 0);
        drawScrollBar(context);
        drawPlayerInventory(context, mouseX, mouseY, delta);
        drawControls(context, mouseX, mouseY, delta);
    }

    /** The overlay's textures, tinted dark with Dark Mode on. */
    public static void sprite(GuiGraphicsExtractor context, Identifier id, int x, int y, int w, int h) {
        if (StorageOverlay.config().darkMode) {
            context.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h, 0xFF000000 | StorageOverlay.colour(StorageOverlay.config().darkModeShade));
        } else {
            context.blitSprite(RenderPipelines.GUI_TEXTURED, id, x, y, w, h);
        }
    }

    public float getScrollbarPercentage() {
        float max = getMaxScroll();
        return max <= 0 ? 0 : scroll / max;
    }

    public void drawScrollBar(GuiGraphicsExtractor context) {
        CustomGui.Rect sb = getScrollBarRect();
        sprite(context, SCROLLBAR_BACKGROUND, sb.x(), sb.y(), sb.width(), sb.height());
        sprite(context, SCROLLBAR_KNOB,
            sb.x(), sb.y() + (int) (getScrollbarPercentage() * (sb.height() - SCROLL_BAR_HEIGHT)), SCROLL_BAR_WIDTH, SCROLL_BAR_HEIGHT);
    }

    /** Scrolls so the scroll bar's knob is centred on {@code mouseY}. */
    private void scrollToKnobAt(double mouseY) {
        CustomGui.Rect sb = getScrollBarRect();
        int track = sb.height() - SCROLL_BAR_HEIGHT;
        double percentage = track <= 0 ? 0 : (mouseY - sb.y() - SCROLL_BAR_HEIGHT / 2.0) / track;
        scroll = (float) (getMaxScroll() * Math.max(0, Math.min(1, percentage)));
        coerceScroll(0F);
    }

    public void editPages() {
        isExiting = true;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            Screen current = mc.gui.screen();
            if (current instanceof AbstractContainerScreen<?> hs && StorageBackingHandle.fromScreen(hs) instanceof StorageBackingHandle.Overview) {
                CustomGui.set(hs, null);
                hs.init(width, height);
            } else if (mc.player != null) {
                mc.player.connection.sendCommand("storage");
            }
        });
    }

    public void drawControls(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        sprite(context, CONTROLLER_BACKGROUND, measurements.controlX, measurements.controlY, CONTROL_BACKGROUND_WIDTH, CONTROL_HEIGHT);
        placeControls();
        searchField.extractRenderState(context, mouseX, mouseY, delta);
        editButton.extractRenderState(context, mouseX, mouseY, delta);
    }

    public void drawBackgrounds(GuiGraphicsExtractor context) {
        sprite(context, UPPER_BACKGROUND_SPRITE, measurements.x, measurements.y, measurements.overviewWidth, measurements.overviewHeight);
        sprite(context, PLAYER_INVENTORY_SPRITE, measurements.playerX, measurements.playerY, PLAYER_WIDTH, PLAYER_HEIGHT);
    }

    public int[] getPlayerInventorySlotPosition(int index) {
        if (index < 9) return new int[]{measurements.playerX + index * SLOT_SIZE + HOTBAR_X, HOTBAR_Y + measurements.playerY};
        return new int[]{measurements.playerX + (index % 9) * SLOT_SIZE + HOTBAR_X, measurements.playerY + (index / 9 - 1) * SLOT_SIZE + MAIN_INVENTORY_Y};
    }

    public void drawPlayerInventory(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        List<ItemStack> items = mc.player.getInventory().getNonEquipmentItems();
        for (int index = 0; index < items.size(); index++) {
            int[] pos = getPlayerInventorySlotPosition(index);
            ItemStack item = items.get(index);
            context.item(item, pos[0], pos[1]);
            context.itemDecorations(font, item, pos[0], pos[1]);
        }
    }

    public CustomGui.Rect getScrollBarRect() {
        return new CustomGui.Rect(measurements.x + PADDING + measurements.innerScrollPanelWidth + PADDING, measurements.y + PADDING,
            SCROLL_BAR_WIDTH, measurements.innerScrollPanelHeight);
    }

    public CustomGui.Rect getScrollPanelInner() {
        return new CustomGui.Rect(measurements.x + PADDING, measurements.y + PADDING, measurements.innerScrollPanelWidth, measurements.innerScrollPanelHeight);
    }

    /** A scissor in screen coordinates, whatever the current pose (Firmament's enableScissorWithoutTranslation). */
    public void createScissors(GuiGraphicsExtractor context) {
        CustomGui.Rect rect = getScrollPanelInner();
        Matrix3x2f inverse = new Matrix3x2f(context.pose()).invert();
        Vector2f from = inverse.transformPosition(new Vector2f(rect.x(), rect.y()));
        Vector2f to = inverse.transformPosition(new Vector2f(rect.maxX(), rect.maxY()));
        context.enableScissor((int) from.x, (int) from.y, (int) to.x, (int) to.y);
    }

    public void drawPages(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta,
                          StoragePageSlot excluding, List<Slot> slots, int slotOffsetX, int slotOffsetY) {
        createScissors(context);
        StorageData data = StorageData.data();
        boolean[] placedSlots = {false};
        layoutedForEach(data, (rect, page, inventory) -> {
            boolean active = page.equals(excluding);
            int drawn = drawPage(context, rect.x(), rect.y(), page, inventory, active ? slots : null, slotOffsetX, slotOffsetY, mouseX, mouseY);
            if (active && drawn > 0 && inventory.inventory != null) placedSlots[0] = true;
        });
        context.disableScissor();
        // The open page is hidden by the search (or not loaded yet): keep its real slots out of sight instead of
        // leaving them where the chest had them, on top of the other pages in the top left corner.
        if (slots != null && !placedSlots[0]) {
            for (Slot slot : slots) {
                slot.x = -100000;
                slot.y = -100000;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return mouseClicked(click, doubled, null);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (knobGrabbed) {
            knobGrabbed = false;
            return true;
        }
        if (host == this) return super.mouseReleased(click);
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (knobGrabbed) {
            scrollToKnobAt(click.y());
            return true;
        }
        if (host == this) return super.mouseDragged(click, offsetX, offsetY);
        return false;
    }

    public boolean mouseClicked(MouseButtonEvent click, boolean doubled, StoragePageSlot activePage) {
        // Blur the search box; it is focused again below if it was clicked.
        searchField.setFocused(false);
        if (host.getFocused() == searchField) host.setFocused(null);
        double mouseX = click.x();
        double mouseY = click.y();
        if (getScrollPanelInner().contains(mouseX, mouseY)) {
            StoragePageSlot[] clicked = new StoragePageSlot[1];
            layoutedForEach(StorageData.data(), (rect, page, inventory) -> {
                if (rect.contains(mouseX, mouseY) && !page.equals(activePage) && click.button() == InputConstants.MOUSE_BUTTON_LEFT) clicked[0] = page;
            });
            if (clicked[0] != null) {
                clicked[0].navigateTo();
                return true;
            }
            return false;
        }
        CustomGui.Rect sb = getScrollBarRect();
        if (sb.contains(mouseX, mouseY)) {
            scrollToKnobAt(mouseY);
            knobGrabbed = true;
            return true;
        }
        if (searchField.isMouseOver(mouseX, mouseY)) {
            host.setFocused(searchField);
            searchField.setFocused(true);
            searchField.mouseClicked(click, doubled);
            return true;
        }
        if (editButton.isMouseOver(mouseX, mouseY)) {
            editButton.mouseClicked(click, doubled);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (searchField.isFocused() && searchField.charTyped(input)) return true;
        return super.charTyped(input);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return this == Minecraft.getInstance().gui.screen(); // Fixes this UI closing the handled screen on Escape press.
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (searchField.isFocused()) {
            if (input.key() == InputConstants.KEY_ESCAPE) {
                searchField.setFocused(false);
                if (host.getFocused() == searchField) host.setFocused(null);
                return true;
            }
            // Typing in the search box: letters come as characters; don't let E close the menu or 1-9 swap items.
            searchField.keyPressed(input);
            return true;
        }
        if (host == this) return super.keyPressed(input);
        return false;
    }

    private String searchCache = null;
    private StorageData searchCacheData = null;
    private int searchCacheVersion = -1;
    private Set<StoragePageSlot> filteredPagesCache = Set.of();

    public Set<StoragePageSlot> getFilteredPages() {
        String searchValue = searchText;
        StorageData data = StorageData.data();
        // Pages loaded after the last search (/ec 8, a profile switch) must show up too, so the cache also keys on the data.
        if (searchValue.equals(searchCache) && data == searchCacheData && StorageData.version() == searchCacheVersion) return filteredPagesCache;
        Set<StoragePageSlot> result = new HashSet<>();
        for (Map.Entry<StoragePageSlot, StorageData.StorageInventory> e : data.storageInventories.entrySet()) {
            VirtualInventory inv = e.getValue().inventory;
            if (inv == null || searchValue.isBlank()) {
                result.add(e.getKey());
                continue;
            }
            for (ItemStack stack : inv.stacks()) {
                if (matchesSearch(stack, searchValue)) {
                    result.add(e.getKey());
                    break;
                }
            }
        }
        searchCache = searchValue;
        searchCacheData = data;
        searchCacheVersion = StorageData.version();
        filteredPagesCache = result;
        return result;
    }

    public static boolean matchesSearch(ItemStack itemStack, String search) {
        Set<String> searchWords = new TreeSet<>(words(search));
        for (String word : words(plain(itemStack.getHoverName().getString()))) removeContained(searchWords, word);
        if (searchWords.isEmpty()) return true;
        ItemLore lore = itemStack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                for (String word : words(plain(line.getString()))) removeContained(searchWords, word);
            }
        }
        return searchWords.isEmpty();
    }

    private static void removeContained(Set<String> searchWords, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        searchWords.removeIf(w -> lower.contains(w.toLowerCase(Locale.ROOT)));
    }

    private static String plain(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? "" : stripped;
    }

    private static List<String> words(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\s+"));
    }

    @FunctionalInterface
    public interface PageConsumer {
        void accept(CustomGui.Rect rectangle, StoragePageSlot page, StorageData.StorageInventory inventory);
    }

    public void layoutedForEach(StorageData data, PageConsumer func) {
        int yOffset = -(int) scroll;
        int xOffset = 0;
        int maxHeight = 0;
        Set<StoragePageSlot> filter = getFilteredPages();
        for (Map.Entry<StoragePageSlot, StorageData.StorageInventory> e : data.storageInventories.entrySet()) {
            StoragePageSlot page = e.getKey();
            StorageData.StorageInventory inventory = e.getValue();
            if (!filter.contains(page)) continue;
            int currentHeight = inventory.inventory != null ? inventory.inventory.rows() * SLOT_SIZE + 6 + font.lineHeight : 18;
            maxHeight = Math.max(maxHeight, currentHeight);
            CustomGui.Rect rect = new CustomGui.Rect(measurements.x + PADDING + (PAGE_WIDTH + PADDING) * xOffset,
                yOffset + measurements.y + PADDING, PAGE_WIDTH, currentHeight);
            func.accept(rect, page, inventory);
            xOffset++;
            if (xOffset >= pageWidthCount) {
                yOffset += maxHeight;
                xOffset = 0;
                maxHeight = 0;
            }
        }
        lastRenderedInnerHeight = maxHeight + yOffset + (int) scroll;
    }

    public int drawPage(GuiGraphicsExtractor context, int x, int y, StoragePageSlot page, StorageData.StorageInventory inventory,
                        List<Slot> slots, int slotOffsetX, int slotOffsetY, int mouseX, int mouseY) {
        VirtualInventory inv = inventory.inventory;
        if (inv == null) {
            sprite(context, UPPER_BACKGROUND_SPRITE, x, y, PAGE_WIDTH, 18);
            context.text(font, Component.literal(StorageData.data().displayName(page) + ": click to load"), x + 4, y + 4, -1, true);
            return 18;
        }
        List<ItemStack> stacks = inv.stacks();
        if (slots != null && slots.size() != stacks.size()) return 0;
        String name = StorageData.data().displayName(page);
        int pageHeight = inv.rows() * SLOT_SIZE + 8 + font.lineHeight;
        if (slots != null && StorageOverlay.config().outlineActivePage) {
            int colour = StorageOverlay.colour(StorageOverlay.config().outlineActivePageColour);
            int top = y + 3 + font.lineHeight;
            int h = inv.rows() * SLOT_SIZE + 4;
            context.verticalLine(x, top, top + h, colour);
            context.verticalLine(x + PAGE_WIDTH, top, top + h, colour);
            context.horizontalLine(x, x + PAGE_WIDTH, top, colour);
            context.horizontalLine(x, x + PAGE_WIDTH, top + h, colour);
        }
        context.text(font, Component.literal(name), x + 6, y + 3, slots == null ? 0xFFFFFFFF : 0xFFFFFF00, true);
        sprite(context, SLOT_ROW_SPRITE, x + 2, y + 5 + font.lineHeight, PAGE_SLOTS_WIDTH, inv.rows() * SLOT_SIZE);
        CustomGui.Rect scrollPanel = getScrollPanelInner();
        for (int index = 0; index < stacks.size(); index++) {
            int slotX = (index % 9) * SLOT_SIZE + x + 3;
            int slotY = (index / 9) * SLOT_SIZE + y + 5 + font.lineHeight + 1;
            ItemStack stack = stacks.get(index);
            if (slots == null) {
                highlightSearchResult(context, stack, slotX, slotY);
                context.item(stack, slotX, slotY);
                context.itemDecorations(font, stack, slotX, slotY);
                if (StorageOverlay.config().inactivePageTooltips && !stack.isEmpty()
                    && mouseX >= slotX && mouseY >= slotY && mouseX <= slotX + 16 && mouseY <= slotY + 16
                    && scrollPanel.contains(mouseX, mouseY)) {
                    context.setTooltipForNextFrame(font, stack, mouseX, mouseY);
                }
            } else {
                Slot slot = slots.get(index);
                slot.x = slotX - slotOffsetX;
                slot.y = slotY - slotOffsetY;
            }
        }
        return pageHeight + 6;
    }

    /** Firmament's highlightSlots: search results get a coloured square behind them. */
    public static void highlightSearchResult(GuiGraphicsExtractor context, ItemStack stack, int x, int y) {
        if (!StorageOverlay.config().highlightSearchResults || stack == null || stack.isEmpty()) return;
        if (searchText.isBlank()) return;
        if (matchesSearch(stack, searchText)) {
            context.fill(x, y, x + 16, y + 16, StorageOverlay.colour(StorageOverlay.config().highlightSearchResultsColour));
        }
    }

    public List<CustomGui.Rect> getBounds() {
        return List.of(
            new CustomGui.Rect(measurements.x, measurements.y, measurements.overviewWidth, measurements.overviewHeight),
            new CustomGui.Rect(measurements.playerX, measurements.playerY, PLAYER_WIDTH, PLAYER_HEIGHT),
            new CustomGui.Rect(measurements.controlX, measurements.controlY, CONTROL_WIDTH, CONTROL_HEIGHT));
    }
}
