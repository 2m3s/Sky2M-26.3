package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiLineEditBox.class)
public interface Sky2MMultiLineEditBoxAccessor {
    @Accessor("textField")
    MultilineTextField sky2m$textField();
}
