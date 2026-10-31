package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.fishing.FishingVisuals;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Fishing > Visuals > Lava Replacement (SkyHanni's MixinFluidStateModelSet, LGPL-2.1). */
@Mixin(FluidStateModelSet.class)
public abstract class Sky2MFluidModelMixin {
    @Inject(method = "bake", at = @At("RETURN"))
    private static void sky2m$bakeOpaqueWater(CallbackInfoReturnable<Map<Fluid, FluidModel>> cir) {
        FishingVisuals.onModelsBaked(cir.getReturnValue());
    }

    @ModifyReturnValue(method = "get", at = @At("RETURN"))
    private FluidModel sky2m$replaceLava(FluidModel original, FluidState state) {
        return FishingVisuals.replacement(state, original);
    }
}
