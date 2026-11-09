package com.epic60869.sky2m.sim.roomsim.mixin;

import com.epic60869.sky2m.sim.roomsim.SimState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Water and lava stay where the rooms put them, as on Hypixel: no flowing, so breaking a block beside a pool (a
 * Dungeonbreaker, a Superboom) does not pour it into the room. Only while a sim is running.
 */
@Mixin(FlowingFluid.class)
public abstract class SimFluidMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void sky2m$noFlowInSim(ServerLevel level, BlockPos pos, BlockState state, FluidState fluid,
            CallbackInfo ci) {
        if (SimState.isActive()) {
            ci.cancel();
        }
    }
}
