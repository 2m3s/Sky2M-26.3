package com.epic60869.sky2m.mixin.player;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Dungeons > Cross-Mod Bridge: reads {@code Minecraft.profileKeyPairManager} for NoammAddons' sign-in (it signs a
 * challenge with your chat-signing key). Read directly because No Chat Reports and similar mods hook the public getter
 * to hand back an empty key manager (Killer560's Mod and NoammAddons read it the same way).
 */
@Mixin(Minecraft.class)
public interface Sky2MProfileKeysAccessor {
    @Accessor("profileKeyPairManager")
    ProfileKeyPairManager sky2m$getProfileKeyPairManager();
}
