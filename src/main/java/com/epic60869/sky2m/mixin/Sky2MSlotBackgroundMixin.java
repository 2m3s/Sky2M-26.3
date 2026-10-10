package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MItemBackgrounds;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks when an inventory screen is being drawn. The rarity background itself is drawn from
 * {@link Sky2MItemDrawBackgroundMixin} at each item draw, so it also works when another mod
 * (e.g. Skysoft's custom inventory) replaces vanilla's slot rendering and draws items itself.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MSlotBackgroundMixin {
    @Inject(method = "extractContents", at = @At("HEAD"))
    private void sky2m$beginContainer(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        Sky2MItemBackgrounds.beginContainer((AbstractContainerScreen<?>) (Object) this);
    }

    /** The equipped loadout's, active pet's, bestiary, Bazaar order, shard and infested plot highlights, behind the slot's item. */
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void sky2m$slotBackground(GuiGraphicsExtractor graphics, net.minecraft.world.inventory.Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        com.epic60869.sky2m.features.misc.LoadoutHighlight.renderSlot(graphics, slot);
        com.epic60869.sky2m.features.misc.ActivePetHighlight.renderSlot(graphics, slot);
        com.epic60869.sky2m.features.combat.BestiaryOverlay.renderSlot(graphics, slot);
        com.epic60869.sky2m.features.dungeons.DungeonChestProfit.renderSlotBackground(graphics, slot);
        com.epic60869.sky2m.features.portfolio.BazaarNotifications.renderSlot(graphics, slot);
        com.epic60869.sky2m.features.misc.ShardLevelUpHighlight.renderSlot(graphics, slot);
        com.epic60869.sky2m.features.garden.PestFinder.renderSlot(graphics, slot);
    }

    /** /s2 protect's star and slot labels (chest profit, pet level), drawn with the slot so tooltips cover them. */
    @Inject(method = "extractSlot", at = @At("TAIL"))
    private void sky2m$protectedSlot(GuiGraphicsExtractor graphics, net.minecraft.world.inventory.Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        com.epic60869.sky2m.features.misc.SlotLocking.renderSlot(graphics, slot);
        com.epic60869.sky2m.features.dungeons.DungeonChestProfit.renderSlotLabel(graphics, slot);
        com.epic60869.sky2m.features.misc.ActivePetHighlight.renderSlotLabel(graphics, slot);
    }

    @Inject(method = "extractContents", at = @At("RETURN"))
    private void sky2m$endContainer(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        Sky2MItemBackgrounds.endContainer();
    }
}
