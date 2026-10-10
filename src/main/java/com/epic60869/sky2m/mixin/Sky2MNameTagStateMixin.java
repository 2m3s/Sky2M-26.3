package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nicknames above heads: rewrites the name tag stored in the render state, which is what actually gets drawn, so the
 * nick shows whichever method filled it in (vanilla, a player renderer override, or another mod). Replacing is a no-op
 * when the real name isn't in the tag or it was already replaced.
 */
@Mixin(EntityRenderer.class)
public abstract class Sky2MNameTagStateMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void sky2m$nickRenderState(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (state.nameTag != null) state.nameTag = Sky2MNick.worldText(state.nameTag);
    }
}
