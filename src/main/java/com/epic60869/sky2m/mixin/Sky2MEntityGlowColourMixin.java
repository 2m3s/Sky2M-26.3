package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.core.EntityGlow;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The outline of an entity highlighted by a Sky2M feature is drawn in that feature's colour. */
@Mixin(Entity.class)
public abstract class Sky2MEntityGlowColourMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void sky2m$entityGlowColour(CallbackInfoReturnable<Integer> cir) {
        int colour = EntityGlow.colour((Entity) (Object) this);
        if (colour != -1) cir.setReturnValue(colour);
    }
}
