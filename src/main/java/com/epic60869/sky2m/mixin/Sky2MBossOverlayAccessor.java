package com.epic60869.sky2m.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** The boss bars on screen (WitherDragons starts the relic timer when the Wither King's appears). */
@Mixin(BossHealthOverlay.class)
public interface Sky2MBossOverlayAccessor {
    @Accessor("events")
    Map<UUID, LerpingBossEvent> sky2m$getEvents();
}
