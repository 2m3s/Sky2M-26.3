package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerTabOverlay.class)
public class Sky2MPlayerTabOverlayMixin {
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void sky2m$replaceTabName(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        GameProfile profile = info.getProfile();
        if (profile == null) return;

        Component original = cir.getReturnValue();
        Component replacement = Sky2MNick.tabDisplayName(original, profile.id(), profile.name());
        // getString() intentionally ignores colour/style. A nickname can keep
        // the same text while changing its colour, so never use plain text as
        // the test for whether the replacement should be applied.
        if (replacement != null && replacement != original) {
            cir.setReturnValue(replacement);
        }
    }
}
