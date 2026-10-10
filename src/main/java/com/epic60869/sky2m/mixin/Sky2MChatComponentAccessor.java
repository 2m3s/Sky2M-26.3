package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChatComponent.class)
public interface Sky2MChatComponentAccessor {
    @Accessor("trimmedMessages")
    List<GuiMessage.Line> sky2m$trimmedMessages();

    @Accessor("chatScrollbarPos")
    int sky2m$chatScrollbarPos();

    @Accessor("allMessages")
    List<GuiMessage> sky2m$allMessages();

    @Invoker("refreshTrimmedMessages")
    void sky2m$refreshTrimmedMessages();
}
