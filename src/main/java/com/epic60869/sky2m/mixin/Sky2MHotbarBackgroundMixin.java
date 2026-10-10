package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MItemBackgrounds;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rarity backgrounds behind hotbar items, as in Skyblocker's HudMixin. */
@Mixin(Hud.class)
public abstract class Sky2MHotbarBackgroundMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void sky2m$drawItemBackground(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker, Player player, ItemStack stack, int seed, CallbackInfo ci) {
        Sky2MItemBackgrounds.draw(graphics, stack, x, y);
    }
}
