// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), features/helditem/HeldItemTextureOverrides.kt and
// HeldItemUpdateFix.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Showing an item's vanilla texture instead of Hypixel's pack model, and the Held Item Update Fix. */
public final class HeldItemTextures {
    private static final String HYPIXEL_MODEL_NAMESPACE = "hypixel_skyblock";
    private static final Set<String> MISSING_VANILLA_MODEL = new HashSet<>();

    private HeldItemTextures() {}

    // ---------------------------------------------------------------- texture overrides

    /** The stack to render: a copy with the vanilla model when that's turned on for it. */
    public static ItemStack renderStack(ItemStack stack) {
        if (!HeldItemTransforms.isEligible(stack)) return stack;
        HeldItemConfig config = HeldItemTransforms.config();
        if (config == null || !config.enabled || (!config.usesVanillaTexture(null) && config.itemTextureModes.isEmpty())) return stack;
        return vanillaStackIfConfigured(stack);
    }

    static ItemStack previewStack(ItemStack stack) {
        return HeldItemTransforms.isEligible(stack) ? vanillaStackIfConfigured(stack) : stack;
    }

    static boolean hasPackTexture(ItemStack stack) {
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null) return false;
        Identifier vanillaModel = stack.getPrototype().get(DataComponents.ITEM_MODEL);
        return !model.equals(vanillaModel) && HYPIXEL_MODEL_NAMESPACE.equals(model.getNamespace());
    }

    static boolean usesVanillaTexture(ItemStack stack) {
        if (!canUseVanillaTexture(stack)) return false;
        Boolean preview = HeldItemEditorScreen.previewUsesVanillaTexture(stack);
        if (preview != null) return preview;
        return HeldItemTransforms.config().usesVanillaTexture(HeldItemTransforms.itemId(stack));
    }

    static boolean canUseVanillaTexture(ItemStack stack) {
        return HeldItemTransforms.isEligible(stack) && hasPackTexture(stack) && !isPaper(stack);
    }

    static boolean isPaper(ItemStack stack) {
        return stack.getItem() == Items.PAPER;
    }

    private static ItemStack vanillaStackIfConfigured(ItemStack stack) {
        if (!usesVanillaTexture(stack)) return stack;
        Identifier vanillaModel = stack.getPrototype().get(DataComponents.ITEM_MODEL);
        if (vanillaModel == null) {
            String id = Objects.requireNonNullElse(HeldItemTransforms.itemId(stack), stack.getItem().toString());
            if (MISSING_VANILLA_MODEL.add(id)) System.err.println("[Sky2M] Cannot restore the vanilla item model for " + id);
            return stack;
        }
        ItemStack copy = stack.copy();
        copy.set(DataComponents.ITEM_MODEL, vanillaModel);
        return copy;
    }

    // ---------------------------------------------------------------- Held Item Update Fix

    /**
     * Hypixel resends the held item when its lore or data changes (cooldowns, counters). With the fix on, such an
     * update of the same item (same SkyBlock id and uuid) doesn't count as a new item.
     */
    public static boolean shouldPreserveUpdate(ItemStack previous, ItemStack current) {
        HeldItemConfig config = HeldItemTransforms.config();
        return config != null && config.settings.updateFix && sameItemUpdated(previous, current);
    }

    static boolean sameItemUpdated(ItemStack previous, ItemStack current) {
        if (previous.getItem() != current.getItem() || previous.getCount() != current.getCount()) return false;
        String previousId = skyBlockTag(previous, "id");
        String currentId = skyBlockTag(current, "id");
        if (previousId == null || currentId == null || !previousId.equals(currentId)) return false;
        if (!Objects.equals(previous.get(DataComponents.CUSTOM_DATA), current.get(DataComponents.CUSTOM_DATA))) {
            String previousUuid = skyBlockTag(previous, "uuid");
            if (previousUuid == null || !previousUuid.equals(skyBlockTag(current, "uuid"))) return false;
        }
        return ItemStack.matchesIgnoringComponents(previous, current, type ->
            type.ignoreSwapAnimation() || type == DataComponents.LORE || type == DataComponents.CUSTOM_DATA);
    }

    private static String skyBlockTag(ItemStack stack, String key) {
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        String value = data.copyTag().getStringOr(key, "");
        return value.isEmpty() ? null : value;
    }
}
