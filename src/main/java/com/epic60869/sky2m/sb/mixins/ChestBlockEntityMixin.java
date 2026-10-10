// Ported from Skyblocker's mixins (LGPL-3.0) for Sky2M's dungeon port.
package com.epic60869.sky2m.sb.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;

@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityMixin extends RandomizableContainerBlockEntity {
	protected ChestBlockEntityMixin(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
		super(blockEntityType, blockPos, blockState);
	}

	@Inject(at = @At("HEAD"), method = "triggerEvent(II)Z")
	public void sky2m$onSyncedBlockEvent(int type, int data, CallbackInfoReturnable<Boolean> cir) {
		if (type != 1 || data <= 0) return; // should open lid
		DungeonManager.onChestOpened(worldPosition);
	}
}
