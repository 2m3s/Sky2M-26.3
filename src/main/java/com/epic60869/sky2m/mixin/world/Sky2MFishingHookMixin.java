package com.epic60869.sky2m.mixin.world;

import com.epic60869.sky2m.features.fishing.FishingVisuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fishing > Visuals > Fix Bobber (SkyOcean's FishingHookMixin): lava counts as a liquid for the bobber, and Hypixel's timer stand is not taken for the hooked entity. */
@Mixin(FishingHook.class)
public abstract class Sky2MFishingHookMixin extends Projectile {
    protected Sky2MFishingHookMixin(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean sky2m$lavaBobber(FluidState instance, TagKey<Fluid> tag, Operation<Boolean> original) {
        return original.call(instance, tag) || (FishingVisuals.fixBobber() && instance.is(FluidTags.LAVA));
    }

    @WrapOperation(method = "onSyncedDataUpdated", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntity(I)Lnet/minecraft/world/entity/Entity;"))
    private Entity sky2m$bobberHook(Level instance, int id, Operation<Entity> original) {
        Entity entity = original.call(instance, id);
        return FishingVisuals.fixBobber() && entity instanceof ArmorStand stand && stand.getId() == this.getId() + 1 ? null : entity;
    }
}
