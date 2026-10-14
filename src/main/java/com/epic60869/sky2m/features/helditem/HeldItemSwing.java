// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/HeldItemSwing.kt,
// HeldItemSwingVisuals.kt and HeldItemSwingPreview.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;

/** Swing speed, and the "Item Only" swing style (the item swings, the arm stays still). */
public final class HeldItemSwing {
    private static final int MIN_VISIBLE_DURATION = 2;
    private static final float SWING_X_DEGREES = -80f;
    private static final float SWING_Y_DEGREES = -20f;
    private static final float SWING_Z_DEGREES = -20f;

    private record ItemOnlySwing(ItemStack stack, float attack, HumanoidArm arm, boolean[] vanillaSwingReplaced) {}

    private static ItemOnlySwing itemOnlySwing;

    private HeldItemSwing() {}

    /** How long your own swing takes, in ticks. */
    public static int duration(LivingEntity entity, int vanillaDuration) {
        if (entity != Minecraft.getInstance().player) return vanillaDuration;
        InteractionHand hand = entity.getCurrentSwing() != null ? entity.getCurrentSwing().hand() : InteractionHand.MAIN_HAND;
        ItemStack stack = entity.getItemInHand(hand);
        if (!HeldItemTransforms.isEligible(stack)) return vanillaDuration;
        HeldItemConfig config = HeldItemTransforms.config();
        if (config == null || !config.enabled) return vanillaDuration;
        HeldItemTransform transform = HeldItemTransforms.effectiveTransform(stack);
        return adjustedDuration(vanillaDuration, stack.getAttackAnimation().duration(), transform.swingSpeed,
            config.settings.ignoresMiningEffects);
    }

    static int adjustedDuration(int vanillaDuration, int baseDuration, float speed, boolean ignoresMiningEffects) {
        if (speed <= 0f) return vanillaDuration;
        if (speed == 1f && !ignoresMiningEffects) return vanillaDuration;
        int duration = ignoresMiningEffects ? baseDuration : vanillaDuration;
        return Math.max(MIN_VISIBLE_DURATION, Math.round(duration / speed));
    }

    /** Swing In Place: the swing's slide across the screen (vanilla's translate before the swing turn) is skipped. */
    public static boolean skipSwingSlide() {
        HeldItemConfig config = HeldItemTransforms.config();
        return config != null && config.settings.swingInPlace;
    }

    /** Draws one first-person arm, remembering whether its item should use the Item Only swing. */
    public static void renderWithSwing(ItemStack stack, float attack, HumanoidArm arm, Runnable render) {
        ItemOnlySwing previous = itemOnlySwing;
        itemOnlySwing = null;
        try {
            try {
                begin(stack, attack, arm);
            } catch (Throwable ignored) {
                itemOnlySwing = null;
            }
            render.run();
        } finally {
            itemOnlySwing = previous;
        }
    }

    private static void begin(ItemStack stack, float attack, HumanoidArm arm) {
        if (!HeldItemTransforms.isEligible(stack)) return;
        HeldItemConfig config = HeldItemTransforms.config();
        if (config == null || !config.enabled) return;
        HeldItemTransform transform = HeldItemTransforms.effectiveTransform(stack);
        if (transform.swingStyle == HeldItemTransform.SwingStyle.ITEM_ONLY
            && stack.getAttackAnimation().type() == SwingAnimationType.WHACK) {
            itemOnlySwing = new ItemOnlySwing(stack, attack, arm, new boolean[1]);
        }
    }

    /** Vanilla's arm swing: skipped (true) when the Item Only swing replaces it. */
    public static boolean replaceVanillaSwing() {
        if (itemOnlySwing == null) return false;
        itemOnlySwing.vanillaSwingReplaced()[0] = true;
        return true;
    }

    /** The Item Only swing, applied to the item instead of the arm. */
    public static void apply(ItemStack stack, PoseStack pose) {
        ItemOnlySwing swing = itemOnlySwing;
        if (swing == null || swing.stack() != stack || !swing.vanillaSwingReplaced()[0]) return;
        applyItemOnlySwing(pose, swing.attack(), swing.arm());
    }

    static void applyItemOnlySwing(PoseStack pose, float attack, HumanoidArm arm) {
        if (attack <= 0f) return;
        float direction = arm == HumanoidArm.RIGHT ? 1f : -1f;
        float arc = (float) Math.sin(Math.sqrt(attack) * Math.PI);
        float twist = (float) Math.sin(attack * attack * Math.PI);
        pose.rotate(Axis.YP.rotationDegrees(direction * twist * SWING_Y_DEGREES));
        pose.rotate(Axis.ZP.rotationDegrees(direction * arc * SWING_Z_DEGREES));
        pose.rotate(Axis.XP.rotationDegrees(arc * SWING_X_DEGREES));
    }

    /** The editor's "Preview swing" button. */
    static void preview() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
    }
}
