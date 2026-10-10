package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.dungeons.BloodCamp;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Blood Camp > Watcher Bar: adds the blood mobs left to the Watcher's boss bar name. */
@Mixin(BossHealthOverlay.class)
public abstract class Sky2MBossBarMixin {
    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"))
    private Component sky2m$watcherBar(Component name, @Local LerpingBossEvent event) {
        return BloodCamp.watcherBarName(name, event.getProgress());
    }
}
