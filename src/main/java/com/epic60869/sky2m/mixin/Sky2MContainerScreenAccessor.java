package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface Sky2MContainerScreenAccessor {
    @Accessor("leftPos")
    int sky2m$getLeftPos();

    @Accessor("topPos")
    int sky2m$getTopPos();

    @Accessor("hoveredSlot")
    Slot sky2m$getHoveredSlot();

    @Accessor("imageWidth")
    int sky2m$getImageWidth();

    @Accessor("imageHeight")
    int sky2m$getImageHeight();
}
