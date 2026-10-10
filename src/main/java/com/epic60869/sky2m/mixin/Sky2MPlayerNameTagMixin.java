package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Shows Sky2M nicknames above players' heads (the nametag uses the player's display name). */
@Mixin(Player.class)
public abstract class Sky2MPlayerNameTagMixin {
    @ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"))
    private Component sky2m$nickNameTag(Component original) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide()) return original;
        return Sky2MNick.nameTag(original, self.getUUID(), self.getGameProfile().name());
    }
}
