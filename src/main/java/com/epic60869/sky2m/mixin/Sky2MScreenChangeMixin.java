// Ported from Firmament (https://github.com/FirmamentMC/Firmament), mixins/ScreenChangeEventPatch.java
// (Minecraft.setScreen there; the screen lives on Gui in 26.2).
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.storage.StorageOverlay;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the storage overlay attach to storage menus and stay open while Hypixel swaps pages. */
@Mixin(Gui.class)
public abstract class Sky2MScreenChangeMixin {
    @Shadow private Screen screen;

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void sky2m$onScreenChange(Screen newScreen, CallbackInfo ci, @Local(argsOnly = true) LocalRef<Screen> screenRef) {
        Screen override = StorageOverlay.onScreenChange(this.screen, newScreen);
        if (override != null) screenRef.set(override);
    }
}
