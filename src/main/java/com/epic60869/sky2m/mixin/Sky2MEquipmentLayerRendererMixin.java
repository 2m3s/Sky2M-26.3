package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Custom armor models. Ported from Skyblocker's EquipmentLayerRendererMixin (LGPL-3.0). */
@Mixin(EquipmentLayerRenderer.class)
public class Sky2MEquipmentLayerRendererMixin {

    @ModifyVariable(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V", at = @At("HEAD"), argsOnly = true)
    private ResourceKey<EquipmentAsset> sky2m$customArmorModel(ResourceKey<EquipmentAsset> assetKey, @Local(argsOnly = true) ItemStack stack) {
        if (Compat.isOnSkyblock()) {
            String uuid = Compat.uuid(stack);
            if (!uuid.isEmpty()) {
                Identifier identifier = CustomConfigManager.get().general.customArmorModel.get(uuid);
                return identifier == null ? assetKey : ResourceKey.create(EquipmentAssets.ROOT_ID, identifier);
            }
        }

        return assetKey;
    }
}
