package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.gizmos.Gizmos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws Sky2M's world renderers once per frame, into the frame's main-thread gizmos. */
@Mixin(LevelExtractor.class)
public abstract class Sky2MLevelExtractorMixin {
    @Inject(method = "extractGizmos", at = @At("HEAD"))
    private void sky2m$renderFrame(CallbackInfo ci) {
        try (Gizmos.TemporaryCollection _ = ((LevelExtractor) (Object) this).collectPerFrameMainThreadGizmos()) {
            Sky2MWorldRender.renderFrame();
        }
    }
}
