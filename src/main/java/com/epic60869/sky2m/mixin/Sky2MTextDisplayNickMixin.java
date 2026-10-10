package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nicknames in text display entities, which Hypixel can use for name lines above heads. */
@Mixin(Display.TextDisplay.class)
public abstract class Sky2MTextDisplayNickMixin {
    @ModifyReturnValue(method = "getText", at = @At("RETURN"))
    private Component sky2m$nickText(Component original) {
        Display.TextDisplay self = (Display.TextDisplay) (Object) this;
        return self.level().isClientSide() ? Sky2MNick.worldText(original) : original;
    }
}
