package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.custom.CustomAnimatedHelmetTextures;
import com.epic60869.sky2m.custom.CustomArmorTrims;
import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.CustomHelmetTextures;
import com.epic60869.sky2m.custom.util.Compat;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ported from Skyblocker's DataComponentHolderMixin (LGPL-3.0). */
@Mixin(DataComponentHolder.class)
public interface Sky2MDataComponentHolderMixin {

    @SuppressWarnings("unchecked")
    @ModifyReturnValue(method = "get", at = @At("RETURN"))
    private <T> T sky2m$customComponents(T original, DataComponentType<? extends T> dataComponentType) {
        // Check the type before reading the UUID: the UUID lookup itself reads CUSTOM_DATA through this method.
        if (dataComponentType != DataComponents.TRIM && dataComponentType != DataComponents.PROFILE
                && dataComponentType != DataComponents.ENCHANTMENT_GLINT_OVERRIDE && dataComponentType != DataComponents.ITEM_MODEL) {
            return original;
        }
        if (!((Object) this instanceof ItemStack stack) || !Compat.isOnSkyblock()) return original;

        String itemUuid = Compat.uuid(stack);
        if (itemUuid.isEmpty()) return original;
        CustomConfigManager.GeneralConfig general = CustomConfigManager.get().general;

        if (dataComponentType == DataComponents.TRIM) {
            CustomArmorTrims.ArmorTrimId trimKey = general.customArmorTrims.get(itemUuid);

            if (trimKey != null) {
                return (T) CustomArmorTrims.TRIMS_CACHE.getOrDefault(trimKey, (ArmorTrim) original);
            }
        } else if (dataComponentType == DataComponents.PROFILE && stack.is(Items.PLAYER_HEAD)) {
            // Normal head textures
            String tex = general.customHelmetTextures.get(itemUuid);

            if (tex != null) {
                return (T) CustomHelmetTextures.getProfile(tex);
            }

            // Animated heads
            String animatedTexId = general.customAnimatedHelmetTextures.get(itemUuid);
            ResolvableProfile frame = animatedTexId != null ? CustomAnimatedHelmetTextures.animateHeadTexture(animatedTexId) : null;

            if (frame != null) {
                return (T) frame;
            }
        } else if (dataComponentType == DataComponents.ENCHANTMENT_GLINT_OVERRIDE) {
            if (general.customGlint.containsKey(itemUuid)) {
                return (T) (Boolean) general.customGlint.getBoolean(itemUuid);
            }
        } else if (dataComponentType == DataComponents.ITEM_MODEL) {
            Identifier id = general.customItemModel.get(itemUuid);

            if (id != null) {
                return (T) id;
            }
        }
        return original;
    }
}
