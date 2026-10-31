package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Visual Tweaks > Hide Entity Fire: the render state says the entity is not burning. */
@Mixin(EntityRenderer.class)
public abstract class Sky2MVisualEntityFireMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void sky2m$noEntityFire(Entity entity, EntityRenderState state, float partial, CallbackInfo ci) {
        if (state.displayFireAnimation && VisualTweaks.hideEntityFire()) state.displayFireAnimation = false;
    }
}
