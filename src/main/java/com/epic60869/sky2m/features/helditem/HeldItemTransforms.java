// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/HeldItemTransforms.kt and
// HeldItemCustomization.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public final class HeldItemTransforms {
    private static Object newestCustomData;
    private static String newestItemId;
    private static Object previousCustomData;
    private static String previousItemId;

    private HeldItemTransforms() {}

    /** Maps aren't customised (their first-person rendering is special). */
    public static boolean isEligible(ItemStack stack) {
        return stack.getItem() != Items.MAP && stack.getItem() != Items.FILLED_MAP;
    }

    /** Misc > Held Item (null when the config isn't loaded). */
    public static HeldItemConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.heldItem;
    }

    public static void apply(ItemStack stack, PoseStack pose) {
        if (!isEligible(stack)) return;
        HeldItemConfig config = config();
        if (config == null || !config.enabled) return;
        HeldItemTransform transform = effectiveTransform(stack);
        if (!transform.hasRenderChanges()) return;
        applyPosition(pose, transform, RenderSystem.getModelViewStack());
        applyRotation(pose, transform);
        if (transform.scale != 1f) pose.scale(transform.scale, transform.scale, transform.scale);
    }

    public static ItemStack currentItem() {
        var player = Minecraft.getInstance().player;
        if (player == null) return ItemStack.EMPTY;
        return player.getMainHandItem().isEmpty() ? player.getOffhandItem() : player.getMainHandItem();
    }

    /** The SkyBlock id of a stack (cached for the last two stacks, since this runs every frame), or null. */
    public static String itemId(ItemStack stack) {
        Object customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;
        if (customData == newestCustomData) return newestItemId;
        if (customData == previousCustomData) return previousItemId;
        String id = Compat.neuName(stack);
        String itemId = id.isEmpty() ? null : id;
        previousCustomData = newestCustomData;
        previousItemId = newestItemId;
        newestCustomData = customData;
        newestItemId = itemId;
        return itemId;
    }

    public static HeldItemTransform effectiveTransform(ItemStack stack) {
        HeldItemTransform preview = HeldItemEditorScreen.previewTransform(stack);
        if (preview != null) return preview;
        HeldItemConfig config = config();
        return config.itemTransforms.isEmpty() ? config.global : config.transformFor(itemId(stack));
    }

    /** Moves the item in screen space, so X and Y follow the mouse in the editor however the item is turned. */
    static void applyPosition(PoseStack pose, HeldItemTransform transform, Matrix4fc viewTransform) {
        float referenceDepth = referenceDepth(transform.z);
        Matrix4f screenTransform = new Matrix4f()
            .m20(-transform.x / referenceDepth)
            .m21(-transform.y / referenceDepth)
            .translate(0f, 0f, transform.z);
        Matrix4f cameraSpaceTransform = new Matrix4f(viewTransform).invert().mul(screenTransform).mul(viewTransform);
        pose.last().pose().mulLocal(cameraSpaceTransform);
    }

    static void applyRotation(PoseStack pose, HeldItemTransform transform) {
        if (transform.rotationX != 0f) pose.rotate(Axis.XP.rotationDegrees(transform.rotationX));
        if (transform.rotationY != 0f) pose.rotate(Axis.YP.rotationDegrees(transform.rotationY));
        if (transform.rotationZ != 0f) pose.rotate(Axis.ZP.rotationDegrees(transform.rotationZ));
    }

    // HeldItemPositionMath
    private static final float DEFAULT_ITEM_DEPTH = 0.72f;
    private static final float MIN_ITEM_DEPTH = 0.1f;
    private static final double HALF_HUD_FOV_RADIANS = Math.toRadians(Camera.BASE_HUD_FOV / 2.0);

    static float referenceDepth(float depthOffset) {
        return Math.max(MIN_ITEM_DEPTH, DEFAULT_ITEM_DEPTH - depthOffset);
    }

    static float unitsPerPixel(int guiHeight, float depthOffset) {
        return (float) (2.0 * referenceDepth(depthOffset) * Math.tan(HALF_HUD_FOV_RADIANS) / guiHeight);
    }
}
