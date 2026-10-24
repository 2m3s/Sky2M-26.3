package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.qol.VisualTweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Misc > Visual Tweaks > Disconnect Fix. Ported from NoFrills (WhatYouThing/NoFrills, ClientPacketListenerMixin). */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MDisconnectFixMixin {
    @WrapOperation(method = "handleSetEntityData", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"))
    private void sky2m$guardEntityData(SynchedEntityData data, List<SynchedEntityData.DataValue<?>> values, Operation<Void> original) {
        if (!VisualTweaks.disconnectFix()) {
            original.call(data, values);
            return;
        }
        try {
            original.call(data, values);
        } catch (Exception e) {
            System.err.println("[Sky2M] Disconnect Fix skipped a corrupted entity data packet: " + e);
        }
    }
}
