package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.ItemCooldowns;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item cooldowns (the cooldown bar/shade) and enchanted book labels over the item's slot, drawn with its count and durability. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class Sky2MItemCooldownMixin {
    @Inject(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V", at = @At("TAIL"))
    private void sky2m$cooldown(Font font, ItemStack stack, int x, int y, String text, CallbackInfo ci) {
        ItemCooldowns.drawOverlay((GuiGraphicsExtractor) (Object) this, stack, x, y);
        com.epic60869.sky2m.features.misc.EnchantedBookLabels.drawOverlay((GuiGraphicsExtractor) (Object) this, stack, x, y);
    }
}
