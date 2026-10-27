package com.epic60869.sky2m.mixin.world;

import com.epic60869.sky2m.config.Sky2MConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Misc > Random > Hide Block Break Particles, like SkyHanni's: no burst of block bits when a block breaks (yours or
 * anyone's), and no chips flying off the block you're mining.
 */
@Mixin(ClientLevel.class)
public abstract class Sky2MHideBlockBreakParticlesMixin {
    private static boolean sky2m$hide() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.random.hideBlockBreakParticles;
    }

    @Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideDestroyParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (sky2m$hide()) ci.cancel();
    }

    @Inject(method = "addBreakingBlockEffects", at = @At("HEAD"), cancellable = true)
    private void sky2m$hideBreakingParticles(BlockPos pos, Direction direction, boolean flag, CallbackInfo ci) {
        if (sky2m$hide()) ci.cancel();
    }
}
