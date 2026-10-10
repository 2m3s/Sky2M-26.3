package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nicknames (with their colour and font) in every tab list: vanilla's and the ones other mods draw (SkyHanni,
 * Skyblocker), which read the name straight from here. Sky2M's own tab parsing reads the raw name through
 * {@link com.epic60869.sky2m.custom.util.Compat#rawTabName}.
 */
@Mixin(PlayerInfo.class)
public abstract class Sky2MPlayerInfoMixin {
    @Shadow
    public abstract GameProfile getProfile();

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void sky2m$nickInTab(CallbackInfoReturnable<Component> cir) {
        Component original = cir.getReturnValue();
        GameProfile profile = getProfile();
        if (original == null || profile == null) return;
        Component replacement = Sky2MNick.tabDisplayName(original, profile.id(), profile.name());
        if (replacement != null && replacement != original) cir.setReturnValue(replacement);
    }
}
