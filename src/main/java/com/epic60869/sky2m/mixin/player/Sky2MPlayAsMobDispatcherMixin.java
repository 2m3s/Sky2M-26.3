/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), mobskin/mixin/MobSkinDispatcherMixin. MIT
 * License, Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.mixin.player;

import com.epic60869.sky2m.features.misc.playasmob.PlayAsMobRender;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Misc > Play as Mob's swap point. {@code extractEntity} returns the state the level renderer draws, and the later
 * {@code submit} picks the renderer from the state's entity type, so returning a mob's state here draws you as that
 * mob. At RETURN, so every other player-state mixin has already run on the state that is copied from.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class Sky2MPlayAsMobDispatcherMixin {
    @Inject(method = "extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at = @At("RETURN"), cancellable = true, require = 0)
    private void sky2m$playAsMob(Entity entity, float partialTick, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState original = cir.getReturnValue();
        EntityRenderState swapped = PlayAsMobRender.swap((EntityRenderDispatcher) (Object) this, entity, original, partialTick);
        if (swapped != original) cir.setReturnValue(swapped);
    }
}
