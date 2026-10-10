package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MDateCalculator;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Calendar dates in the menu's own tooltip too (see Sky2MDateCalculator.addDates). */
@Mixin(AbstractContainerScreen.class)
public abstract class Sky2MContainerTooltipMixin {
    @ModifyReturnValue(method = "getTooltipFromContainerItem", at = @At("RETURN"))
    private List<Component> sky2m$calendarDates(List<Component> lines, ItemStack stack) {
        try {
            return Sky2MDateCalculator.addDates(stack, lines);
        } catch (Throwable t) {
            return lines;
        }
    }
}
