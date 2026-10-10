package com.epic60869.sky2m.mixin;

import com.epic60869.sky2m.Sky2MConfig;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Misc > Random > Hide Explosions: drops explosion particles from explosion and particle packets. */
@Mixin(ClientPacketListener.class)
public abstract class Sky2MHideExplosionsMixin {
    private static boolean sky2m$hide() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.random.hideExplosions;
    }

    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean sky2m$explosionParticle(ClientLevel level, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
        return !sky2m$hide();
    }

    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;trackExplosionEffects(Lnet/minecraft/world/phys/Vec3;FILnet/minecraft/util/random/WeightedList;)V"))
    private boolean sky2m$explosionBlockParticles(ClientLevel level, Vec3 center, float radius, int blockCount, WeightedList<?> particles) {
        return !sky2m$hide();
    }

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
    private void sky2m$explosionParticlePacket(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        if (!sky2m$hide()) return;
        var type = packet.particle().getType();
        if (type == ParticleTypes.EXPLOSION || type == ParticleTypes.EXPLOSION_EMITTER) ci.cancel();
    }
}
