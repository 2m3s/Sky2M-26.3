package com.epic60869.sky2m.mixin.render;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.mining.dwarven.DwarvenMining;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Mining > Retextures > Red Dwarven Mines Carpets (SkyOcean's Red Carpets): the gray, light gray and light blue carpets use the red carpet's model in the Dwarven Mines. */
@Mixin(BlockStateModelSet.class)
public abstract class Sky2MCarpetRetextureMixin {
    @Shadow @Final private Map<BlockState, BlockStateModel> modelByState;

    @Inject(method = "get", at = @At("HEAD"), cancellable = true, require = 0)
    private void sky2m$redCarpets(BlockState state, CallbackInfoReturnable<BlockStateModel> cir) {
        if (!state.is(Blocks.CARPET.gray()) && !state.is(Blocks.CARPET.lightGray()) && !state.is(Blocks.CARPET.lightBlue())) return;
        if (!DwarvenMining.redCarpets() || !Sky2MLocation.inDwarvenMines()) return;
        BlockStateModel red = this.modelByState.get(Blocks.CARPET.red().defaultBlockState());
        if (red != null) cir.setReturnValue(red);
    }
}
