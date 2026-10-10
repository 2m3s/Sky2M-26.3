package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Player Size: scales your own player and/or other players, like Odin's Player Size. */
@Mixin(AvatarRenderer.class)
public abstract class Sky2MPlayerSizeMixin {
    @Inject(method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("TAIL"))
    private void sky2m$playerSize(AvatarRenderState state, PoseStack pose, CallbackInfo ci) {
        Sky2MConfig config = Sky2MConfig.current();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || mc.player == null || mc.level == null) return;
        Sky2MConfig.PlayerSize size = config.misc.playerSize;
        float x, y, z;
        if (state.id == mc.player.getId()) {
            if (!size.self) return;
            x = size.selfX;
            y = size.selfY;
            z = size.selfZ;
        } else {
            if (!size.others) return;
            // Real players only: Hypixel NPCs use version 2 UUIDs.
            Entity entity = mc.level.getEntity(state.id);
            if (!(entity instanceof Player player) || player.getUUID().version() != 4) return;
            x = size.othersX;
            y = size.othersY;
            z = size.othersZ;
        }
        if (y < 0) pose.translate(0f, y * 2, 0f);
        pose.scale(x, y, z);
    }
}
