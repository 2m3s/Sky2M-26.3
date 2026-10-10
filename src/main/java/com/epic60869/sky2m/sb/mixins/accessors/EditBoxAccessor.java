// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker), licensed under LGPL-3.0.
package com.epic60869.sky2m.sb.mixins.accessors;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.gui.components.EditBox;

@Mixin(EditBox.class)
public interface EditBoxAccessor {
	@Accessor
	int getHighlightPos();

	@Accessor
	int getMaxLength();

	@Invoker
	void invokeOnValueChange(String value);
}
