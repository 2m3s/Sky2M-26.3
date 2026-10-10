package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.components.Checkbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Checkbox.class)
public interface Sky2MCheckboxAccessor {
    @Accessor
    void setSelected(boolean checked);
}
