package com.epic60869.sky2m.sim.roomsim.mixin;

import com.epic60869.sky2m.sim.roomsim.SimState;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No durability in the sim, as on Hypixel: a player's rod, weapon or armour never wears down. Every
 * {@code hurtAndBreak} goes through {@code processDurabilityChange}, so turning the change to 0 there covers them all.
 * Only while a sim is running.
 */
@Mixin(ItemStack.class)
public abstract class SimDurabilityMixin {

    @Inject(method = "processDurabilityChange", at = @At("HEAD"), cancellable = true)
    private void sky2m$noWearInSim(int amount, ServerLevel level, ServerPlayer player,
            CallbackInfoReturnable<Integer> cir) {
        if (player != null && SimState.isActive()) {
            cir.setReturnValue(0);
        }
    }
}
