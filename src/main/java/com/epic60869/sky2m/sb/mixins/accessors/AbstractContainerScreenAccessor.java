// Mixins supporting Sky2M's port of Skyblocker's dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.mixins.accessors;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int getX();

	@Accessor("topPos")
	int getY();

	@Accessor
	int getImageWidth();

	@Accessor
	int getImageHeight();

	@Accessor("hoveredSlot")
	Slot getFocusedSlot();
}
