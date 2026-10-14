package com.epic60869.sky2m.sim.roomsim.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The sim sends the held slot before a use, as vanilla would (see SimAbilities.sendHeldSlotFirst). */
@Mixin(MultiPlayerGameMode.class)
public interface SimGameModeInvoker {

    @Invoker("ensureHasSentCarriedItem")
    void sky2m$ensureHasSentCarriedItem();
}
