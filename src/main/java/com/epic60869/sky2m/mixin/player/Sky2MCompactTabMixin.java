package com.epic60869.sky2m.mixin.player;

import com.epic60869.sky2m.features.qol.CompactTabList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compact Tab List: draws SkyHanni's compact tab list (CompactTabList) instead of the vanilla one. */
@Mixin(PlayerTabOverlay.class)
public class Sky2MCompactTabMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void sky2m$compact(GuiGraphicsExtractor graphics, int screenWidth, Scoreboard scoreboard, Objective objective, CallbackInfo ci) {
        if (!CompactTabList.enabled()) return;
        CompactTabList.renderHeld(graphics);
        ci.cancel();
    }
}
