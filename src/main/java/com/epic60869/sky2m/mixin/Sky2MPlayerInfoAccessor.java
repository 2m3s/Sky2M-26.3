package com.epic60869.sky2m.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerInfo.class)
public interface Sky2MPlayerInfoAccessor {
    @Accessor("tabListDisplayName")
    Component sky2m$rawTabListDisplayName();
}
