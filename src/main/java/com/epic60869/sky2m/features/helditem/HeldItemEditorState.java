// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/HeldItemEditorState.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/** What the editor is editing (the global settings or the held item's) and the edits themselves. */
final class HeldItemEditorState {
    enum Target { GLOBAL, ITEM }

    private static final float DEPTH_PER_PIXEL = 0.004f;

    private final HeldItemConfig config;
    private final Consumer<Target> targetSelection;
    private Target preferredTarget;
    private Target target;

    HeldItemEditorState(HeldItemConfig config, Target initialTarget, Consumer<Target> targetSelection) {
        this.config = config;
        this.preferredTarget = initialTarget;
        this.target = initialTarget;
        this.targetSelection = targetSelection;
    }

    Target target() {
        return target;
    }

    /** "This Item" needs an item with a SkyBlock id; without one the editor shows the global settings. */
    void ensureTargetAvailable() {
        target = preferredTarget == Target.ITEM && currentItemId() == null ? Target.GLOBAL : preferredTarget;
    }

    void selectTarget(Target selected) {
        if (selected == Target.ITEM && currentItemId() == null) return;
        preferredTarget = selected;
        target = selected;
        targetSelection.accept(selected);
    }

    ItemStack currentItem() {
        return HeldItemTransforms.currentItem();
    }

    String currentItemId() {
        return HeldItemTransforms.itemId(currentItem());
    }

    HeldItemTransform displayTransform() {
        return target == Target.ITEM ? config.transformFor(currentItemId()) : config.global;
    }

    boolean canResetCurrentTarget() {
        return target == Target.GLOBAL ? config.hasGlobalCustomization() : config.hasItemCustomization(currentItemId());
    }

    boolean isTextureToggleVisible() {
        return HeldItemTextures.hasPackTexture(currentItem());
    }

    boolean canToggleTexture() {
        return HeldItemTextures.canUseVanillaTexture(currentItem()) && (target == Target.GLOBAL || currentItemId() != null);
    }

    boolean usesVanillaTexture() {
        return config.usesVanillaTexture(target == Target.GLOBAL ? null : currentItemId());
    }

    ItemStack previewItem() {
        return HeldItemTextures.previewStack(currentItem());
    }

    boolean toggleTexture() {
        if (!canToggleTexture()) return false;
        if (target == Target.GLOBAL) return config.toggleGlobalTexture();
        String id = currentItemId();
        return id != null && config.toggleItemTexture(id);
    }

    void moveItem(int deltaX, int deltaY, float unitsPerPixel) {
        HeldItemTransform t = editableTransform();
        if (t == null) return;
        setFieldValue(t, TransformField.X, t.x + deltaX * unitsPerPixel);
        setFieldValue(t, TransformField.Y, t.y - deltaY * unitsPerPixel);
    }

    void moveItemDepth(int deltaX) {
        HeldItemTransform t = editableTransform();
        if (t != null) setFieldValue(t, TransformField.Z, t.z + deltaX * DEPTH_PER_PIXEL);
    }

    void updateSlider(TransformField field, int mouseX, HeldItemEditorScreen.Rect track) {
        float progress = HeldItemTransform.clamp((mouseX - track.x()) / (float) track.width(), 0f, 1f);
        setField(field, field.min + (field.max - field.min) * progress);
    }

    void changeFieldBy(TransformField field, float amount) {
        setField(field, field.value(displayTransform()) + amount);
    }

    void selectSwingStyle(HeldItemTransform.SwingStyle style) {
        HeldItemTransform t = editableTransform();
        if (t != null) t.swingStyle = style;
    }

    boolean resetCurrentTarget() {
        if (target == Target.GLOBAL) return config.resetGlobalCustomization();
        String id = currentItemId();
        return id != null && config.removeItemCustomization(id);
    }

    HeldItemEditorHistory.Key historyKey() {
        if (target == Target.GLOBAL) return HeldItemEditorHistory.Key.GLOBAL;
        String id = currentItemId();
        return id == null ? null : HeldItemEditorHistory.Key.item(id);
    }

    private void setField(TransformField field, float value) {
        HeldItemTransform t = editableTransform();
        if (t != null) setFieldValue(t, field, value);
    }

    private static void setFieldValue(HeldItemTransform t, TransformField field, float value) {
        field.setValue(t, HeldItemTransform.clamp(value, field.min, field.max));
    }

    private HeldItemTransform editableTransform() {
        if (target == Target.GLOBAL) return config.global;
        String id = currentItemId();
        return id == null ? null : config.customize(id);
    }
}
