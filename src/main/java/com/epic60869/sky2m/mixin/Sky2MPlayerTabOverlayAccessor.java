package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Comparator;

@Mixin(PlayerTabOverlay.class)
public interface Sky2MPlayerTabOverlayAccessor {
    @Accessor("PLAYER_COMPARATOR")
    static Comparator<PlayerInfo> getOrdering() {
        throw new AssertionError();
    }

    @Accessor("footer")
    Component sky2m$getFooter();
}
