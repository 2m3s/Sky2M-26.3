package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.core.EntityGlow;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities highlighted by a Sky2M feature ({@link EntityGlow}) get the glowing outline. */
@Mixin(Minecraft.class)
public abstract class Sky2MEntityGlowMixin {
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void sky2m$entityGlow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (EntityGlow.colour(entity) != -1) cir.setReturnValue(true);
    }
}
