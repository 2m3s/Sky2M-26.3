package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.misc.visual.HitColour;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Visual > Hit Colour: hands the entity overlay (hurt flash) texture to {@link HitColour} so it can recolour it. */
@Mixin(OverlayTexture.class)
public abstract class Sky2MHitColourTextureMixin {
    @Shadow @Final private DynamicTexture texture;

    @Inject(method = "<init>()V", at = @At("TAIL"))
    private void sky2m$hitColour(CallbackInfo ci) {
        HitColour.init(texture);
    }
}
