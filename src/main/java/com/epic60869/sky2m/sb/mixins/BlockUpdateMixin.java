// Mixins supporting Sky2M's port of Skyblocker's dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.epic60869.sky2m.sb.events.WorldEvents;

/** Fires Skyblocker's block state update event for server block changes (used by device solvers). */
@Mixin(ClientLevel.class)
public abstract class BlockUpdateMixin {
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void sky2m$beforeBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci, @Share("old") LocalRef<BlockState> oldState) {
		oldState.set(((ClientLevel) (Object) this).getBlockState(pos));
	}

	@Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"))
	private void sky2m$afterBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci, @Share("old") LocalRef<BlockState> oldState) {
		WorldEvents.BLOCK_STATE_UPDATE.invoker().onBlockStateUpdate(pos, oldState.get(), state);
	}
}
