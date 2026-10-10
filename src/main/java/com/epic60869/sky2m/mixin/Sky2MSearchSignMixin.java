package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.features.misc.SearchOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Bazaar and Auction House search: Hypixel's search sign opens Sky2M's search box instead, and the sign is sent
 * with what you picked. Hooked before the sign screen exists, because closing a sign screen sends the sign.
 */
@Mixin(LocalPlayer.class)
public abstract class Sky2MSearchSignMixin {
    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    private void sky2m$searchOverlay(SignBlockEntity sign, SignTextSlot slot, CallbackInfo ci) {
        List<Component> messages = sign.getText(slot).getMessages(false);
        String[] lines = messages.stream().map(Component::getString).toArray(String[]::new);
        boolean opened = SearchOverlay.open(lines, query -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSignUpdatePacket(sign.getBlockPos(),
                    List.of(query, lines.length > 1 ? lines[1] : "", lines.length > 2 ? lines[2] : "", lines.length > 3 ? lines[3] : ""), slot));
            }
            mc.gui.setScreen(null);
        });
        if (opened) ci.cancel();
    }
}
