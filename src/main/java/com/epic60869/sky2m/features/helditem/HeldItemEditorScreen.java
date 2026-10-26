// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/HeldItemEditorScreen.kt and
// HeldItemDisabledOverlay.kt, with the pixel controls from utils/gui/PixelButtonRenderer.kt, PixelControlRenderer.kt,
// OverlayPanelStyle.kt and TextElision.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The held item editor: drag the item to move it (right-drag for depth), scroll to scale it, sliders for everything,
 * Global or This Item, texture toggle, undo and redo. The game keeps rendering behind it, so changes show live.
 */
public final class HeldItemEditorScreen {
    private static HeldItemEditorState.Target preferredTarget = HeldItemEditorState.Target.GLOBAL;
    private static final HeldItemEditorHistory.Store HISTORY = new HeldItemEditorHistory.Store();

    private HeldItemEditorScreen() {}

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Editor(mc.gui.screen())));
    }

    /** For /s2 helditem: opened after the chat screen closes. */
    public static Screen create() {
        return new Editor(null);
    }

    static HeldItemTransform previewTransform(ItemStack stack) {
        if (!HeldItemTransforms.isEligible(stack)) return null;
        return Minecraft.getInstance().gui.screen() instanceof Editor editor ? editor.previewTransform(stack) : null;
    }

    static Boolean previewUsesVanillaTexture(ItemStack stack) {
        if (!HeldItemTransforms.isEligible(stack)) return null;
        return Minecraft.getInstance().gui.screen() instanceof Editor editor ? editor.previewUsesVanillaTexture(stack) : null;
    }

    record Rect(int x, int y, int width, int height) {
        boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        boolean contains(double px, double py) {
            return contains((int) px, (int) py);
        }
    }

    private enum DragKind { PANEL, MOVE_ITEM, MOVE_DEPTH, SLIDER }

    // ================================================================ the screen

    /** Whether the editor is open (Sky2M's HUDs hide meanwhile). */
    public static boolean editing() {
        return net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof Editor;
    }

    private static final class Editor extends Screen {
        private static final long SAVE_DELAY_MILLIS = 400L;

        private final Screen returnScreen;
        private final HeldItemEditorState state;
        private final Layout layout = new Layout();
        private final OpeningAnimation openingAnimation = new OpeningAnimation();
        private final DisabledOverlay disabledOverlay = new DisabledOverlay();
        private final HeldItemEditorHistory.Controller history;
        private DragKind dragKind;
        private TransformField draggedField;
        private int panelDragOffsetX;
        private int panelDragOffsetY;
        private int lastDragX;
        private int lastDragY;
        private boolean hasUnsavedChanges;
        private long lastChangedAt;

        Editor(Screen returnScreen) {
            super(Component.literal("Sky2M Held Item"));
            this.returnScreen = returnScreen;
            this.state = new HeldItemEditorState(config(), preferredTarget, t -> preferredTarget = t);
            this.history = new HeldItemEditorHistory.Controller(config(), HISTORY, state::historyKey);
        }

        private static HeldItemConfig config() {
            return Sky2MConfig.current().misc.heldItem;
        }

        HeldItemTransform previewTransform(ItemStack stack) {
            return stack == state.currentItem() ? state.displayTransform() : null;
        }

        Boolean previewUsesVanillaTexture(ItemStack stack) {
            return stack == state.currentItem() ? state.usesVanillaTexture() : null;
        }

        @Override
        protected void init() {
            layout.initialize(width, height, config().editorX, config().editorY);
            openingAnimation.start();
            disabledOverlay.initialize(config().enabled);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            state.ensureTargetAvailable();
            boolean blocked = disabledOverlay.isEditingBlocked(config().enabled);
            openingAnimation.render(g, layout.panelBounds(), complete -> {
                boolean interactive = complete && !blocked;
                int mx = interactive ? mouseX : Integer.MIN_VALUE;
                int my = interactive ? mouseY : Integer.MIN_VALUE;
                Renderer.render(g, font, state, layout, history.canUndo(), history.canRedo(), mx, my);
            });
            DisabledOverlay.render(g, font, width, height, disabledOverlay.visuals(config().enabled));
        }

        /** No dimmed background: the item in your hand is what you're editing. */
        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {}

        @Override
        public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
            int button = click.button();
            if (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT) {
                return super.mouseClicked(click, doubled);
            }
            if (!openingAnimation.isComplete()) return true;
            int mouseX = (int) click.x();
            int mouseY = (int) click.y();
            if (disabledOverlay.isEditingBlocked(config().enabled)) {
                if (!config().enabled && button == InputConstants.MOUSE_BUTTON_LEFT
                    && DisabledOverlay.toggleBounds(width, height).contains(mouseX, mouseY)) {
                    click(() -> {
                        config().enabled = true;
                        disabledOverlay.beginEnableTransition();
                        Sky2MConfig.saveCurrent(Sky2MConfig.current());
                    });
                }
                return true;
            }
            processClick(mouseX, mouseY, button);
            return true;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
            if (disabledOverlay.isEditingBlocked(config().enabled)) {
                cancelDrag();
                return true;
            }
            DragKind drag = dragKind;
            if (drag == null) return super.mouseDragged(click, deltaX, deltaY);
            int expected = drag == DragKind.MOVE_DEPTH ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT;
            if (click.button() != expected) return super.mouseDragged(click, deltaX, deltaY);
            int mouseX = (int) click.x();
            int mouseY = (int) click.y();
            switch (drag) {
                case PANEL -> {
                    layout.movePanel(mouseX - panelDragOffsetX, mouseY - panelDragOffsetY);
                    config().editorX = layout.panelX;
                    config().editorY = layout.panelY;
                    markChanged(true);
                }
                case SLIDER -> {
                    if (draggedField != null) {
                        state.updateSlider(draggedField, mouseX, layout.sliderTrackBounds(draggedField));
                        markChanged(true);
                    }
                }
                case MOVE_ITEM -> {
                    float unitsPerPixel = HeldItemTransforms.unitsPerPixel(height, state.displayTransform().z);
                    state.moveItem(mouseX - lastDragX, mouseY - lastDragY, unitsPerPixel);
                    markChanged(true);
                }
                case MOVE_DEPTH -> {
                    state.moveItemDepth(mouseX - lastDragX);
                    markChanged(true);
                }
            }
            lastDragX = mouseX;
            lastDragY = mouseY;
            return true;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent click) {
            if (disabledOverlay.isEditingBlocked(config().enabled)) {
                cancelDrag();
                return true;
            }
            int button = click.button();
            if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT) && dragKind != null) {
                history.commitGesture();
                cancelDrag();
                saveChanges();
                return true;
            }
            return super.mouseReleased(click);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (!openingAnimation.isComplete() || disabledOverlay.isEditingBlocked(config().enabled)) return true;
            if (scrollY == 0) return false;
            TransformField hovered = layout.sliderFieldAt((int) mouseX, (int) mouseY);
            if (hovered != null) {
                markChanged(history.mutateScroll(hovered, () -> state.changeFieldBy(hovered, (float) scrollY * hovered.step)));
                return true;
            }
            if (layout.panelBounds().contains(mouseX, mouseY)) return true;
            // Scrolling anywhere else resizes the item.
            markChanged(history.mutateScroll(TransformField.SCALE,
                () -> state.changeFieldBy(TransformField.SCALE, (float) scrollY * TransformField.SCALE.step)));
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (disabledOverlay.isEditingBlocked(config().enabled)) return super.keyPressed(event);
            int key = event.key();
            // 1-9 pick a hotbar slot, to edit another item without closing the editor.
            if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
                history.flushPending();
                cancelDrag();
                var player = Minecraft.getInstance().player;
                if (player != null) player.getInventory().setSelectedSlot(key - InputConstants.KEY_1);
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void tick() {
            history.commitIdleScroll();
            if (hasUnsavedChanges && System.currentTimeMillis() - lastChangedAt >= SAVE_DELAY_MILLIS) saveChanges();
        }

        @Override
        public void onClose() {
            history.flushPending();
            saveChanges();
            if (returnScreen == null) super.onClose();
            else minecraft.gui.setScreen(returnScreen);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        private void processClick(int mouseX, int mouseY, int button) {
            boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
            if (left && layout.previewButtonBounds().contains(mouseX, mouseY)) {
                click(HeldItemSwing::preview);
            } else if (left && layout.closeBounds().contains(mouseX, mouseY)) {
                click(this::onClose);
            } else if (left && layout.undoBounds().contains(mouseX, mouseY) && history.canUndo()) {
                click(() -> markChanged(history.undo()));
            } else if (left && layout.redoBounds().contains(mouseX, mouseY) && history.canRedo()) {
                click(() -> markChanged(history.redo()));
            } else if (left && layout.textureToggleBounds().contains(mouseX, mouseY) && state.canToggleTexture()) {
                click(() -> markChanged(history.mutate(state::toggleTexture)));
            } else if (left && activateTargetAt(mouseX, mouseY)) {
                // handled
            } else if (left && activateAdvancedControlAt(mouseX, mouseY)) {
                // handled
            } else if (left && layout.sliderFieldAt(mouseX, mouseY) != null) {
                startSliderDrag(mouseX, mouseY);
            } else if (left && layout.resetBounds().contains(mouseX, mouseY) && state.canResetCurrentTarget()) {
                click(() -> markChanged(history.mutate(state::resetCurrentTarget)));
            } else if (left && layout.doneBounds().contains(mouseX, mouseY)) {
                click(this::onClose);
            } else if (left && layout.titleDragBounds().contains(mouseX, mouseY)) {
                dragKind = DragKind.PANEL;
                panelDragOffsetX = mouseX - layout.panelX;
                panelDragOffsetY = mouseY - layout.panelY;
            } else if (layout.panelBounds().contains(mouseX, mouseY)) {
                // Clicks on the panel's background do nothing.
            } else {
                // Outside the panel: left-drag moves the item, right-drag moves it closer or further.
                history.beginGesture();
                dragKind = left ? DragKind.MOVE_ITEM : DragKind.MOVE_DEPTH;
                lastDragX = mouseX;
                lastDragY = mouseY;
            }
        }

        private boolean activateTargetAt(int mouseX, int mouseY) {
            HeldItemEditorState.Target target;
            if (layout.targetBounds(HeldItemEditorState.Target.GLOBAL).contains(mouseX, mouseY)) {
                target = HeldItemEditorState.Target.GLOBAL;
            } else if (layout.targetBounds(HeldItemEditorState.Target.ITEM).contains(mouseX, mouseY) && state.currentItemId() != null) {
                target = HeldItemEditorState.Target.ITEM;
            } else {
                return false;
            }
            click(() -> {
                history.flushPending();
                state.selectTarget(target);
            });
            return true;
        }

        private boolean activateAdvancedControlAt(int mouseX, int mouseY) {
            if (layout.advancedToggleBounds().contains(mouseX, mouseY)) {
                click(() -> {
                    int previousX = layout.panelX;
                    int previousY = layout.panelY;
                    layout.toggleAdvanced();
                    if (layout.panelX != previousX || layout.panelY != previousY) {
                        config().editorX = layout.panelX;
                        config().editorY = layout.panelY;
                        markChanged(true);
                    }
                });
                return true;
            }
            HeldItemTransform.SwingStyle style = layout.swingStyleAt(mouseX, mouseY);
            if (style == null) return false;
            click(() -> markChanged(history.mutate(() -> {
                state.selectSwingStyle(style);
                return true;
            })));
            return true;
        }

        private void startSliderDrag(int mouseX, int mouseY) {
            TransformField field = layout.sliderFieldAt(mouseX, mouseY);
            if (field == null) return;
            history.beginGesture();
            draggedField = field;
            dragKind = DragKind.SLIDER;
            state.updateSlider(field, mouseX, layout.sliderTrackBounds(field));
            markChanged(true);
        }

        private void cancelDrag() {
            dragKind = null;
            draggedField = null;
        }

        private void markChanged(boolean changed) {
            if (!changed) return;
            hasUnsavedChanges = true;
            lastChangedAt = System.currentTimeMillis();
        }

        private void saveChanges() {
            if (!hasUnsavedChanges) return;
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
            hasUnsavedChanges = false;
        }

        private static void click(Runnable action) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
            action.run();
        }
    }

    // ================================================================ layout

    private static final int PANEL_WIDTH = 224, PANEL_MIN_WIDTH = 184, PANEL_BASE_HEIGHT = 190, PANEL_MARGIN = 8, PANEL_INSET = 9;
    private static final int HEADER_HEIGHT = 24, HEADER_ITEM_SIZE = 16, HEADER_ITEM_Y = (HEADER_HEIGHT - HEADER_ITEM_SIZE) / 2;
    private static final int HEADER_ITEM_TEXT_GAP = 4, HEADER_TEXT_Y = (HEADER_HEIGHT - 9 + 2) / 2, HEADER_CONTENT_INSET = 22;
    private static final int HEADER_BUTTON_HEIGHT = 14, HEADER_BUTTON_Y = (HEADER_HEIGHT - HEADER_BUTTON_HEIGHT) / 2;
    private static final int CLOSE_RIGHT_INSET = 18, CLOSE_SIZE = 14, PREVIEW_LEFT_INSET = 4, PREVIEW_SIZE = 14;
    private static final int TABS_Y = HEADER_HEIGHT + 6, TABS_HEIGHT = 17;
    private static final int SLIDERS_START_Y = 48, SLIDER_ROW_HEIGHT = 22, SLIDER_TEXT_Y = 6, SLIDER_TRACK_Y = 9, SLIDER_TRACK_HEIGHT = 3;
    private static final int SLIDER_LABEL_WIDTH = 42, SLIDER_RESERVED_WIDTH = 84;
    private static final int ADVANCED_TOGGLE_Y = 154, ADVANCED_TOGGLE_WIDTH = 18, ADVANCED_TOGGLE_HEIGHT = 10, ADVANCED_CONTENT_Y = 164;
    private static final int STYLE_ROW_HEIGHT = 16, STYLE_LABEL_WIDTH = 66, STYLE_BUTTON_GAP = 4, STYLE_BUTTON_HEIGHT = 16, STYLE_TEXT_Y = 4;
    private static final int ROTATION_START_Y = ADVANCED_CONTENT_Y + STYLE_ROW_HEIGHT, ROTATION_ROW_HEIGHT = 16;
    private static final int ROTATION_LABEL_WIDTH = 50, ROTATION_RESERVED_WIDTH = 92;
    private static final int ADVANCED_EXPANDED_HEIGHT = STYLE_ROW_HEIGHT + ROTATION_ROW_HEIGHT * 3;
    private static final int ACTIONS_BASE_Y = 166, ACTIONS_HEIGHT = 16, RESET_WIDTH = 70, COMPACT_RESET_WIDTH = 58;
    private static final int DONE_WIDTH = 48, COMPACT_DONE_WIDTH = 34, COMPACT_CONTENT_WIDTH = 190, HISTORY_SIZE = 16;
    private static final int TEXTURE_WIDTH = 32, GROUP_GAP = 4, GROUP_BASE_WIDTH = HISTORY_SIZE * 2 + TEXTURE_WIDTH;

    private static final class Layout {
        int panelX;
        int panelY;
        private int screenWidth;
        private int screenHeight;
        boolean advancedExpanded;

        void initialize(int width, int height, int configuredX, int configuredY) {
            screenWidth = width;
            screenHeight = height;
            panelX = configuredX == HeldItemConfig.AUTO_EDITOR_POSITION ? PANEL_MARGIN : configuredX;
            panelY = configuredY == HeldItemConfig.AUTO_EDITOR_POSITION ? (height - PANEL_BASE_HEIGHT) / 2 : configuredY;
            constrainPanel();
        }

        void movePanel(int x, int y) {
            panelX = x;
            panelY = y;
            constrainPanel();
        }

        Rect panelBounds() {
            return new Rect(panelX, panelY, panelWidth(), panelHeight());
        }

        Rect titleDragBounds() {
            return new Rect(panelX, panelY, panelWidth(), HEADER_HEIGHT);
        }

        Rect closeBounds() {
            return new Rect(panelX + panelWidth() - CLOSE_RIGHT_INSET, panelY + HEADER_BUTTON_Y, CLOSE_SIZE, HEADER_BUTTON_HEIGHT);
        }

        Rect previewButtonBounds() {
            return new Rect(panelX + PREVIEW_LEFT_INSET, panelY + HEADER_BUTTON_Y, PREVIEW_SIZE, HEADER_BUTTON_HEIGHT);
        }

        Rect targetBounds(HeldItemEditorState.Target target) {
            int width = contentWidth() / 2;
            return new Rect(panelX + PANEL_INSET + target.ordinal() * width, panelY + TABS_Y, width, TABS_HEIGHT);
        }

        void toggleAdvanced() {
            advancedExpanded = !advancedExpanded;
            constrainPanel();
        }

        List<TransformField> visibleSliderFields() {
            return advancedExpanded ? TransformField.ALL : TransformField.BASIC;
        }

        Rect sliderRowBounds(TransformField field) {
            int basicIndex = TransformField.BASIC.indexOf(field);
            int rowY = basicIndex >= 0 ? SLIDERS_START_Y + basicIndex * SLIDER_ROW_HEIGHT
                : ROTATION_START_Y + TransformField.ROTATION.indexOf(field) * ROTATION_ROW_HEIGHT;
            int rowHeight = basicIndex >= 0 ? SLIDER_ROW_HEIGHT : ROTATION_ROW_HEIGHT;
            return new Rect(panelX + PANEL_INSET, panelY + rowY, contentWidth(), rowHeight);
        }

        Rect sliderTrackBounds(TransformField field) {
            Rect row = sliderRowBounds(field);
            boolean rotation = TransformField.ROTATION.contains(field);
            int labelWidth = rotation ? ROTATION_LABEL_WIDTH : SLIDER_LABEL_WIDTH;
            int reserved = rotation ? ROTATION_RESERVED_WIDTH : SLIDER_RESERVED_WIDTH;
            return new Rect(row.x() + labelWidth, row.y() + SLIDER_TRACK_Y, row.width() - reserved, SLIDER_TRACK_HEIGHT);
        }

        TransformField sliderFieldAt(int mouseX, int mouseY) {
            for (TransformField field : visibleSliderFields()) {
                if (sliderRowBounds(field).contains(mouseX, mouseY)) return field;
            }
            return null;
        }

        Rect advancedToggleBounds() {
            Rect panel = panelBounds();
            return new Rect(panel.x() + (panel.width() - ADVANCED_TOGGLE_WIDTH) / 2, panel.y() + ADVANCED_TOGGLE_Y,
                ADVANCED_TOGGLE_WIDTH, ADVANCED_TOGGLE_HEIGHT);
        }

        Rect swingStyleRowBounds() {
            Rect panel = panelBounds();
            return new Rect(panel.x() + PANEL_INSET, panel.y() + ADVANCED_CONTENT_Y, panel.width() - PANEL_INSET * 2, STYLE_ROW_HEIGHT);
        }

        Rect swingStyleBounds(HeldItemTransform.SwingStyle style) {
            Rect row = swingStyleRowBounds();
            int buttonsX = row.x() + STYLE_LABEL_WIDTH;
            int buttonsWidth = row.width() - STYLE_LABEL_WIDTH;
            int count = HeldItemTransform.SwingStyle.values().length;
            int buttonWidth = (buttonsWidth - STYLE_BUTTON_GAP) / count;
            return new Rect(buttonsX + style.ordinal() * (buttonWidth + STYLE_BUTTON_GAP), row.y(), buttonWidth, STYLE_BUTTON_HEIGHT);
        }

        HeldItemTransform.SwingStyle swingStyleAt(int mouseX, int mouseY) {
            if (!advancedExpanded) return null;
            for (HeldItemTransform.SwingStyle style : HeldItemTransform.SwingStyle.values()) {
                if (swingStyleBounds(style).contains(mouseX, mouseY)) return style;
            }
            return null;
        }

        Rect resetBounds() {
            return actionBounds()[0];
        }

        Rect undoBounds() {
            return actionBounds()[1];
        }

        Rect textureToggleBounds() {
            return actionBounds()[2];
        }

        Rect redoBounds() {
            return actionBounds()[3];
        }

        Rect doneBounds() {
            return actionBounds()[4];
        }

        /** Reset, undo, texture, redo, done. */
        private Rect[] actionBounds() {
            boolean compact = contentWidth() < COMPACT_CONTENT_WIDTH;
            int resetWidth = compact ? COMPACT_RESET_WIDTH : RESET_WIDTH;
            int doneWidth = compact ? COMPACT_DONE_WIDTH : DONE_WIDTH;
            int actionY = panelY + ACTIONS_BASE_Y + (advancedExpanded ? ADVANCED_EXPANDED_HEIGHT : 0);
            Rect reset = new Rect(panelX + PANEL_INSET, actionY, resetWidth, ACTIONS_HEIGHT);
            Rect done = new Rect(panelX + panelWidth() - PANEL_INSET - doneWidth, actionY, doneWidth, ACTIONS_HEIGHT);
            int available = done.x() - (reset.x() + reset.width());
            int gap = Math.max(0, Math.min(GROUP_GAP, (available - GROUP_BASE_WIDTH) / 2));
            int groupWidth = GROUP_BASE_WIDTH + gap * 2;
            int groupX = reset.x() + reset.width() + (available - groupWidth) / 2;
            Rect undo = new Rect(groupX, actionY, HISTORY_SIZE, ACTIONS_HEIGHT);
            Rect texture = new Rect(undo.x() + undo.width() + gap, actionY, TEXTURE_WIDTH, ACTIONS_HEIGHT);
            Rect redo = new Rect(texture.x() + texture.width() + gap, actionY, HISTORY_SIZE, ACTIONS_HEIGHT);
            return new Rect[]{reset, undo, texture, redo, done};
        }

        private int contentWidth() {
            return panelWidth() - PANEL_INSET * 2;
        }

        private int panelWidth() {
            return Math.min(PANEL_WIDTH, Math.max(PANEL_MIN_WIDTH, screenWidth - PANEL_MARGIN * 2));
        }

        private int panelHeight() {
            return PANEL_BASE_HEIGHT + (advancedExpanded ? ADVANCED_EXPANDED_HEIGHT : 0);
        }

        private void constrainPanel() {
            panelX = Math.max(PANEL_MARGIN, Math.min(panelX, Math.max(PANEL_MARGIN, screenWidth - panelWidth() - PANEL_MARGIN)));
            panelY = Math.max(PANEL_MARGIN, Math.min(panelY, Math.max(PANEL_MARGIN, screenHeight - panelHeight() - PANEL_MARGIN)));
        }
    }

    // ================================================================ drawing

    private static final List<String> TEXTURE_REMOVE_ICON = List.of("X...X", ".X.X.", "..X..", ".X.X.", "X...X");
    private static final List<String> TEXTURE_RESTORE_ICON = List.of("..X....", ".XX....", "XXXXXXX", ".XX....", "..X....");
    private static final List<String> UNDO_ICON = List.of("..X..", ".XX..", "XXXXX", ".XX..", "..X..");
    private static final List<String> REDO_ICON = List.of("..X..", "..XX.", "XXXXX", "..XX.", "..X..");
    private static final List<String> PREVIEW_ICON = List.of("X....", "XXX..", "XXXXX", "XXX..", "X....");
    private static final List<String> EXPAND_ICON = List.of("X...X", ".X.X.", "..X..");
    private static final List<String> COLLAPSE_ICON = List.of("..X..", ".X.X.", "X...X");

    private static final class Renderer {
        static void render(GuiGraphicsExtractor g, Font font, HeldItemEditorState state, Layout layout,
                           boolean canUndo, boolean canRedo, int mouseX, int mouseY) {
            Rect bounds = layout.panelBounds();
            Pixel.panel(g, bounds, HEADER_HEIGHT);

            // Header: the held item and its name.
            ItemStack item = state.previewItem();
            boolean hasItem = !item.isEmpty();
            Component itemName = hasItem ? item.getHoverName() : Component.literal("Empty hand");
            int itemTextWidth = bounds.width() - HEADER_CONTENT_INSET * 2 - (hasItem ? HEADER_ITEM_SIZE + HEADER_ITEM_TEXT_GAP : 0);
            FormattedCharSequence itemText = elide(font, itemName, itemTextWidth);
            int groupWidth = font.width(itemText) + (hasItem ? HEADER_ITEM_SIZE + HEADER_ITEM_TEXT_GAP : 0);
            int groupX = bounds.x() + (bounds.width() - groupWidth) / 2;
            int textX = groupX + (hasItem ? HEADER_ITEM_SIZE + HEADER_ITEM_TEXT_GAP : 0);
            if (hasItem) g.item(item, groupX, bounds.y() + HEADER_ITEM_Y);
            g.text(font, itemText, textX, bounds.y() + HEADER_TEXT_Y, Pixel.TEXT, false);

            if (state.isTextureToggleVisible()) {
                boolean vanilla = state.usesVanillaTexture();
                boolean enabled = state.canToggleTexture();
                Rect toggle = layout.textureToggleBounds();
                Pixel.button(g, font, toggle, "", false, enabled && toggle.contains(mouseX, mouseY), enabled,
                    vanilla ? Pixel.Tone.CONFIRM : Pixel.Tone.DANGER);
                Pixel.icon(g, toggle, vanilla ? TEXTURE_RESTORE_ICON : TEXTURE_REMOVE_ICON, 2, enabled);
                if (toggle.contains(mouseX, mouseY)) tooltip(g, font, textureTooltip(state), mouseX, mouseY);
            }

            Rect preview = layout.previewButtonBounds();
            Pixel.button(g, font, preview, "", false, preview.contains(mouseX, mouseY), true, Pixel.Tone.NORMAL);
            Pixel.icon(g, preview, PREVIEW_ICON, 1, true);
            if (preview.contains(mouseX, mouseY)) tooltip(g, font, "Preview swing", mouseX, mouseY);
            button(g, font, layout.closeBounds(), "X", false, mouseX, mouseY, true, Pixel.Tone.DANGER);

            // Global / This Item.
            boolean hasItemId = state.currentItemId() != null;
            button(g, font, layout.targetBounds(HeldItemEditorState.Target.GLOBAL), "Global",
                state.target() == HeldItemEditorState.Target.GLOBAL, mouseX, mouseY, true, Pixel.Tone.NORMAL);
            Rect itemTab = layout.targetBounds(HeldItemEditorState.Target.ITEM);
            button(g, font, itemTab, "This Item", state.target() == HeldItemEditorState.Target.ITEM, mouseX, mouseY, hasItemId, Pixel.Tone.NORMAL);
            if (!hasItemId && itemTab.contains(mouseX, mouseY)) tooltip(g, font, "Requires a SkyBlock ID", mouseX, mouseY);

            for (TransformField field : layout.visibleSliderFields()) slider(g, font, state, layout, field, mouseX, mouseY);

            Rect advanced = layout.advancedToggleBounds();
            Pixel.button(g, font, advanced, "", layout.advancedExpanded, advanced.contains(mouseX, mouseY), true, Pixel.Tone.NORMAL);
            Pixel.icon(g, advanced, layout.advancedExpanded ? COLLAPSE_ICON : EXPAND_ICON, 1, true);
            if (advanced.contains(mouseX, mouseY)) tooltip(g, font, layout.advancedExpanded ? "Fewer options" : "More options", mouseX, mouseY);

            if (layout.advancedExpanded) {
                Rect row = layout.swingStyleRowBounds();
                g.text(font, "Swing Style", row.x(), layout.swingStyleBounds(HeldItemTransform.SwingStyle.VANILLA).y() + STYLE_TEXT_Y, Pixel.MUTED_TEXT, false);
                for (HeldItemTransform.SwingStyle style : HeldItemTransform.SwingStyle.values()) {
                    button(g, font, layout.swingStyleBounds(style), style == HeldItemTransform.SwingStyle.VANILLA ? "Vanilla" : "Item Only",
                        state.displayTransform().swingStyle == style, mouseX, mouseY, true, Pixel.Tone.NORMAL);
                }
            }

            button(g, font, layout.resetBounds(), state.target() == HeldItemEditorState.Target.GLOBAL ? "Reset" : "Use Global",
                false, mouseX, mouseY, state.canResetCurrentTarget(), Pixel.Tone.DANGER);
            historyButton(g, font, layout.undoBounds(), UNDO_ICON, canUndo, mouseX, mouseY);
            historyButton(g, font, layout.redoBounds(), REDO_ICON, canRedo, mouseX, mouseY);
            if (layout.undoBounds().contains(mouseX, mouseY)) tooltip(g, font, "Undo", mouseX, mouseY);
            else if (layout.redoBounds().contains(mouseX, mouseY)) tooltip(g, font, "Redo", mouseX, mouseY);
            button(g, font, layout.doneBounds(), "Done", false, mouseX, mouseY, true, Pixel.Tone.CONFIRM);
        }

        private static String textureTooltip(HeldItemEditorState state) {
            if (HeldItemTextures.isPaper(state.currentItem())) return "Unavailable for paper items";
            boolean global = state.target() == HeldItemEditorState.Target.GLOBAL;
            if (!global && state.currentItemId() == null) return "Requires a SkyBlock ID";
            if (global) return state.usesVanillaTexture() ? "Restore pack textures globally" : "Use vanilla textures globally";
            return state.usesVanillaTexture() ? "Restore pack texture for this item" : "Use vanilla texture for this item";
        }

        private static void slider(GuiGraphicsExtractor g, Font font, HeldItemEditorState state, Layout layout,
                                   TransformField field, int mouseX, int mouseY) {
            Rect row = layout.sliderRowBounds(field);
            Rect track = layout.sliderTrackBounds(field);
            float value = field.value(state.displayTransform());
            float progress = HeldItemTransform.clamp((value - field.min) / (field.max - field.min), 0f, 1f);
            g.text(font, field.label, row.x(), row.y() + SLIDER_TEXT_Y, Pixel.MUTED_TEXT, false);
            Pixel.slider(g, track, progress, row.contains(mouseX, mouseY));
            String valueText = field.formattedValue(value);
            g.text(font, valueText, row.x() + row.width() - font.width(valueText), row.y() + SLIDER_TEXT_Y, Pixel.TEXT, false);
        }

        private static void historyButton(GuiGraphicsExtractor g, Font font, Rect bounds, List<String> icon, boolean enabled, int mouseX, int mouseY) {
            Pixel.button(g, font, bounds, "", false, enabled && bounds.contains(mouseX, mouseY), enabled, Pixel.Tone.NORMAL);
            Pixel.icon(g, bounds, icon, 2, enabled);
        }

        private static void button(GuiGraphicsExtractor g, Font font, Rect bounds, String label, boolean selected,
                                   int mouseX, int mouseY, boolean enabled, Pixel.Tone tone) {
            Pixel.button(g, font, bounds, label, selected, enabled && bounds.contains(mouseX, mouseY), enabled, tone);
        }

        private static void tooltip(GuiGraphicsExtractor g, Font font, String text, int mouseX, int mouseY) {
            g.setTooltipForNextFrame(font, Component.literal(text), mouseX, mouseY);
        }

        private static FormattedCharSequence elide(Font font, Component text, int maxWidth) {
            if (font.width(text) <= maxWidth) return text.getVisualOrderText();
            FormattedText suffix = FormattedText.of("…", text.getStyle());
            FormattedText head = font.substrByWidth(text, Math.max(0, maxWidth - font.width(suffix)));
            return Language.getInstance().getVisualOrder(FormattedText.composite(head, suffix));
        }
    }

    /** Skysoft's pixel-style panel, buttons, sliders and icons. */
    private static final class Pixel {
        enum Tone { NORMAL, DANGER, CONFIRM }

        static final int TEXT = 0xFFFFFFFF;
        static final int MUTED_TEXT = 0xFF9AA4AE;
        private static final int HEADER = 0x801B2530;
        private static final int PANEL_BACKGROUND = 0xB0101010;
        private static final int PANEL_OUTLINE = 0x80505050;
        private static final int SLIDER_TRACK = 0xFF30363B;
        private static final int SLIDER_KNOB = 0xFFB9C2CA;
        private static final int ACCENT = 0xFF45A3FF;
        private static final int BORDER = 0xFF111315;
        private static final int DISABLED = 0xFF24272A;
        private static final int HIGHLIGHT = 0xFF6F7880;
        private static final int INSET = 0xFF1D2226;
        private static final int DISABLED_TEXT = 0xFF606870;

        /** normal, hovered, selected, accent. */
        private static int[] palette(Tone tone) {
            return switch (tone) {
                case NORMAL -> new int[]{0xFF3B4147, 0xFF4B5963, 0xFF286B98, 0xFF65C2FF};
                case DANGER -> new int[]{0xFF493638, 0xFF6C3C40, 0xFF7A343A, 0xFFFF7379};
                case CONFIRM -> new int[]{0xFF35483B, 0xFF45634D, 0xFF3D6B49, 0xFF70D98A};
            };
        }

        static void panel(GuiGraphicsExtractor g, Rect b, int headerHeight) {
            g.fill(b.x(), b.y(), b.x() + b.width(), b.y() + b.height(), PANEL_BACKGROUND);
            g.outline(b.x(), b.y(), b.width(), b.height(), PANEL_OUTLINE);
            g.fill(b.x() + 1, b.y() + 1, b.x() + b.width() - 1, b.y() + headerHeight, HEADER);
        }

        static void button(GuiGraphicsExtractor g, Font font, Rect b, String label, boolean selected, boolean hovered,
                           boolean enabled, Tone tone) {
            int[] p = palette(tone);
            int fill = !enabled ? DISABLED : selected ? p[2] : hovered ? p[1] : p[0];
            chamfered(g, b, BORDER);
            Rect inner = new Rect(b.x() + 1, b.y() + 1, b.width() - 2, b.height() - 2);
            chamfered(g, inner, fill);
            if (enabled) {
                int topLeft = selected ? INSET : HIGHLIGHT;
                int bottomRight = selected ? HIGHLIGHT : INSET;
                g.fill(inner.x() + 1, inner.y(), inner.x() + inner.width() - 1, inner.y() + 1, topLeft);
                g.fill(inner.x(), inner.y() + 1, inner.x() + 1, inner.y() + inner.height() - 1, topLeft);
                g.fill(inner.x() + 1, inner.y() + inner.height() - 1, inner.x() + inner.width() - 1, inner.y() + inner.height(), bottomRight);
                g.fill(inner.x() + inner.width() - 1, inner.y() + 1, inner.x() + inner.width(), inner.y() + inner.height() - 1, bottomRight);
                if (selected) {
                    g.fill(inner.x() + 1, inner.y() + inner.height() - 2, inner.x() + inner.width() - 1, inner.y() + inner.height() - 1, p[3]);
                }
            }
            int textX = b.x() + (b.width() - font.width(label) + 1) / 2;
            int textY = b.y() + (b.height() - font.lineHeight + 2) / 2;
            g.text(font, label, textX, textY, enabled ? TEXT : DISABLED_TEXT, false);
        }

        static void icon(GuiGraphicsExtractor g, Rect b, List<String> icon, int scale, boolean enabled) {
            int iconWidth = icon.stream().mapToInt(String::length).max().orElse(0) * scale;
            int iconHeight = icon.size() * scale;
            int startX = b.x() + (b.width() - iconWidth) / 2;
            int startY = b.y() + (b.height() - iconHeight) / 2;
            for (int row = 0; row < icon.size(); row++) {
                String pixels = icon.get(row);
                for (int col = 0; col < pixels.length(); col++) {
                    if (pixels.charAt(col) != 'X') continue;
                    int x = startX + col * scale;
                    int y = startY + row * scale;
                    g.fill(x, y, x + scale, y + scale, enabled ? TEXT : DISABLED_TEXT);
                }
            }
        }

        static void slider(GuiGraphicsExtractor g, Rect track, float progress, boolean hovered) {
            int fillWidth = Math.round(track.width() * HeldItemTransform.clamp(progress, 0f, 1f));
            g.fill(track.x(), track.y(), track.x() + track.width(), track.y() + track.height(), SLIDER_TRACK);
            g.fill(track.x(), track.y(), track.x() + fillWidth, track.y() + track.height(), ACCENT);
            int knobX = Math.max(track.x(), Math.min(track.x() + track.width(), track.x() + fillWidth));
            g.fill(knobX - 2, track.y() - 2, knobX + 2, track.y() + track.height() + 2, hovered ? TEXT : SLIDER_KNOB);
        }

        private static void chamfered(GuiGraphicsExtractor g, Rect b, int colour) {
            g.fill(b.x() + 1, b.y(), b.x() + b.width() - 1, b.y() + b.height(), colour);
            g.fill(b.x(), b.y() + 1, b.x() + b.width(), b.y() + b.height() - 1, colour);
        }
    }

    // ================================================================ opening animation

    /** A line grows across the middle, then the panel unfolds from it. */
    private static final class OpeningAnimation {
        private static final long DURATION_NANOS = 220_000_000L;
        private static final float LINE_PHASE_END = 0.2f;
        private long startedAt = Long.MIN_VALUE;

        void start() {
            if (startedAt == Long.MIN_VALUE) startedAt = System.nanoTime();
        }

        boolean isComplete() {
            return progress() >= 1f;
        }

        void render(GuiGraphicsExtractor g, Rect bounds, java.util.function.Consumer<Boolean> drawPanel) {
            float progress = progress();
            if (progress >= 1f) {
                drawPanel.accept(true);
                return;
            }
            if (progress < LINE_PHASE_END) {
                drawLine(g, bounds, easeOutCubic(progress / LINE_PHASE_END), 1f);
                return;
            }
            float unfold = easeOutCubic((progress - LINE_PHASE_END) / (1f - LINE_PHASE_END));
            int visibleHeight = Math.max(1, Math.round(bounds.height() * unfold));
            int visibleTop = bounds.y() + (bounds.height() - visibleHeight) / 2;
            g.enableScissor(bounds.x(), visibleTop, bounds.x() + bounds.width(), visibleTop + visibleHeight);
            try {
                drawPanel.accept(false);
            } finally {
                g.disableScissor();
            }
            drawLine(g, bounds, 1f, 1f - unfold);
        }

        private float progress() {
            if (startedAt == Long.MIN_VALUE) return 1f;
            return Math.min(1f, (System.nanoTime() - startedAt) / (float) DURATION_NANOS);
        }

        private static void drawLine(GuiGraphicsExtractor g, Rect bounds, float widthProgress, float opacity) {
            int lineWidth = Math.max(1, Math.round(bounds.width() * widthProgress));
            int lineX = bounds.x() + (bounds.width() - lineWidth) / 2;
            int lineY = bounds.y() + bounds.height() / 2;
            int alpha = Math.round(255 * opacity);
            g.fill(lineX, lineY, lineX + lineWidth, lineY + 1, (alpha << 24) | 0x5D6872);
        }
    }

    // ================================================================ "Currently disabled" overlay

    /** With the feature off, the editor is covered by a card with a switch that turns it on (then fades away). */
    private static final class DisabledOverlay {
        private static final long FADE_NANOS = 300_000_000L;
        private static final long TOGGLE_NANOS = 200_000_000L;
        private static final int CARD_WIDTH = 196, CARD_HEIGHT = 58, INSET = 12, TITLE_Y = 12, STATUS_Y = 32;
        private static final int TOGGLE_WIDTH = 48, TOGGLE_HEIGHT = 14, KNOB_WIDTH = 12;

        private enum Phase { HIDDEN, SHOWN, FADING }

        record Visuals(float opacity, float toggleProgress) {
            static final Visuals HIDDEN = new Visuals(0f, 1f);
            static final Visuals DISABLED = new Visuals(1f, 0f);
        }

        private Phase phase = Phase.HIDDEN;
        private long fadeStartedAt = Long.MIN_VALUE;
        private boolean initialized;

        void initialize(boolean enabled) {
            if (initialized) {
                synchronize(enabled);
                return;
            }
            phase = enabled ? Phase.HIDDEN : Phase.SHOWN;
            fadeStartedAt = Long.MIN_VALUE;
            initialized = true;
        }

        void beginEnableTransition() {
            if (phase == Phase.HIDDEN) return;
            phase = Phase.FADING;
            fadeStartedAt = System.nanoTime();
        }

        boolean isEditingBlocked(boolean enabled) {
            synchronize(enabled);
            if (phase == Phase.FADING && progress(FADE_NANOS) >= 1f) phase = Phase.HIDDEN;
            return phase != Phase.HIDDEN;
        }

        Visuals visuals(boolean enabled) {
            synchronize(enabled);
            if (phase == Phase.HIDDEN) return Visuals.HIDDEN;
            if (phase == Phase.SHOWN) return Visuals.DISABLED;
            float fade = progress(FADE_NANOS);
            if (fade >= 1f) {
                phase = Phase.HIDDEN;
                return Visuals.HIDDEN;
            }
            return new Visuals(1f - smoothStep(fade), smoothStep(progress(TOGGLE_NANOS)));
        }

        private void synchronize(boolean enabled) {
            if (!enabled) {
                phase = Phase.SHOWN;
                fadeStartedAt = Long.MIN_VALUE;
            } else if (phase == Phase.SHOWN) {
                beginEnableTransition();
            }
        }

        private float progress(long duration) {
            if (fadeStartedAt == Long.MIN_VALUE) return 1f;
            return Math.min(1f, (System.nanoTime() - fadeStartedAt) / (float) duration);
        }

        static Rect toggleBounds(int screenWidth, int screenHeight) {
            Rect card = card(screenWidth, screenHeight);
            return new Rect(card.x() + card.width() - INSET - TOGGLE_WIDTH, card.y() + (card.height() - TOGGLE_HEIGHT) / 2,
                TOGGLE_WIDTH, TOGGLE_HEIGHT);
        }

        static void render(GuiGraphicsExtractor g, Font font, int screenWidth, int screenHeight, Visuals visuals) {
            if (visuals.opacity() <= 0f) return;
            float o = visuals.opacity();
            Rect card = card(screenWidth, screenHeight);
            g.fill(0, 0, screenWidth, screenHeight, scaledAlpha(0xB0000000, o));
            g.fill(card.x(), card.y(), card.x() + card.width(), card.y() + card.height(), scaledAlpha(0xB0101010, o));
            g.outline(card.x(), card.y(), card.width(), card.height(), scaledAlpha(0x80505050, o));
            g.text(font, "Held Item", card.x() + INSET, card.y() + TITLE_Y, scaledAlpha(0xFFFFFFFF, o), false);
            g.text(font, "Currently disabled", card.x() + INSET, card.y() + STATUS_Y, scaledAlpha(0xFFFF5555, o), false);
            // The switch: a bar with a knob that slides right (and turns green) as it turns on.
            Rect t = toggleBounds(screenWidth, screenHeight);
            g.fill(t.x(), t.y() + 3, t.x() + t.width(), t.y() + t.height() - 3, scaledAlpha(0xFF30363B, o));
            int knobX = t.x() + Math.round(visuals.toggleProgress() * (t.width() - KNOB_WIDTH));
            int knob = visuals.toggleProgress() < 0.5f ? 0xFFB0B0B0 : 0xFF55FF55;
            g.fill(knobX, t.y(), knobX + KNOB_WIDTH, t.y() + t.height(), scaledAlpha(knob, o));
            g.outline(knobX, t.y(), KNOB_WIDTH, t.height(), scaledAlpha(0xFF111315, o));
        }

        private static Rect card(int screenWidth, int screenHeight) {
            return new Rect((screenWidth - CARD_WIDTH) / 2, (screenHeight - CARD_HEIGHT) / 2, CARD_WIDTH, CARD_HEIGHT);
        }
    }

    private static int scaledAlpha(int argb, float scale) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * scale);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    private static float smoothStep(float t) {
        return t * t * (3f - 2f * t);
    }
}
