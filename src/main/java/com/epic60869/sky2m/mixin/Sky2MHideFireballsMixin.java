package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.slayer.BlazeSlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Slayers > Blaze > Clear View: fireballs aren't drawn near an Inferno Demonlord. */
@Mixin(EntityRenderDispatcher.class)
public abstract class Sky2MHideFireballsMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void sky2m$hideFireballs(E entity, Frustum frustum, double x, double y, double z, float partialTick, CallbackInfoReturnable<Boolean> cir) {
        if (entity instanceof Fireball && BlazeSlayer.hideFireballs()) cir.setReturnValue(false);
        // Fishing > Hook & Bobber: Hypixel's timer stand over the bobber, while the Fishing Hook Display shows it.
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand && com.epic60869.sky2m.features.fishing.FishingHookTimer.hideStand(entity)) cir.setReturnValue(false);
    }
}
