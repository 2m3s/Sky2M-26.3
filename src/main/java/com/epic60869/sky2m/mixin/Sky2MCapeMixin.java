package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.sbc.SbcCosmetics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sky2M capes: players in the cosmetics table wear their cape (downloaded once and cached). */
@Mixin(AbstractClientPlayer.class)
public abstract class Sky2MCapeMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void sky2m$cape(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin skin = cir.getReturnValue();
        if (skin == null) return;
        Identifier cape = SbcCosmetics.capeTexture(((AbstractClientPlayer) (Object) this).getUUID());
        if (cape == null) return;
        ClientAsset.Texture texture = new ClientAsset.ResourceTexture(cape, cape);
        cir.setReturnValue(new PlayerSkin(skin.body(), texture, skin.elytra(), skin.model(), skin.secure()));
    }
}
