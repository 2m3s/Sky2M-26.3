package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientTextTooltip.class)
public interface Sky2MClientTextTooltipAccessor {
    @Accessor("text")
    FormattedCharSequence sky2m$getText();
}
