package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.misc.visual.HitColour;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Misc > Visual > Hit Colour: entities filtered out (players/mobs/only your hits) sample the still-red overlay row
 * instead of the recoloured one, so they keep the vanilla red flash.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class Sky2MHitColourRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void sky2m$hitColourFilter(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
        state.setData(HitColour.KEEP_VANILLA, state.hasRedOverlay && HitColour.keepVanilla(entity) ? Boolean.TRUE : null);
    }

    @Inject(method = "getOverlayCoords(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)I", at = @At("RETURN"), cancellable = true)
    private static void sky2m$hitColourCoords(LivingEntityRenderState state, float whiteOverlayProgress, CallbackInfoReturnable<Integer> cir) {
        if (state.hasRedOverlay && Boolean.TRUE.equals(state.getData(HitColour.KEEP_VANILLA))) {
            cir.setReturnValue(OverlayTexture.pack(OverlayTexture.u(whiteOverlayProgress), HitColour.VANILLA_RED_V));
        }
    }
}
