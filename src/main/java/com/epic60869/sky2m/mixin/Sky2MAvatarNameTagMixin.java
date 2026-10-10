package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MNick;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nicknames above players' heads, with their colour and font: players' name tags are filled in by AvatarRenderer, so
 * the nick is put into its render state once it's done (the same hook NoammAddons uses for its name tag badges).
 */
@Mixin(AvatarRenderer.class)
public abstract class Sky2MAvatarNameTagMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void sky2m$nickNameTag(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (state.nameTag == null || !(entity instanceof Player player)) return;
        // By UUID first, then by username (for Sky2M users whose nick is only known by name).
        state.nameTag = Sky2MNick.worldText(Sky2MNick.nameTag(state.nameTag, player.getUUID(), player.getGameProfile().name()));
    }
}
