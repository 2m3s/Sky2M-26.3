package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Misc > Visual Tweaks > Hide Lightning, Hide Dead Entities, Hide Far Entities. */
@Mixin(EntityRenderDispatcher.class)
public abstract class Sky2MVisualEntityMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void sky2m$hideEntities(E entity, Frustum frustum, double x, double y, double z, float partialTick, CallbackInfoReturnable<Boolean> cir) {
        if (VisualTweaks.hideEntity(entity)) cir.setReturnValue(false);
    }
}
