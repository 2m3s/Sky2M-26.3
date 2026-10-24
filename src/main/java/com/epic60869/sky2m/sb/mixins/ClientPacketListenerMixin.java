// Ported from Skyblocker's mixins (LGPL-3.0) for Sky2M's dungeon port.
package com.epic60869.sky2m.sb.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.item.ItemEntity;

import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonScore;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.TeleportMaze;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;

// Every hook here runs inside vanilla's packet handling, where an uncaught exception disconnects you with
// "Network Protocol Error". Each one catches its own failures (including class-loading errors, e.g. when the jar is
// replaced while the game is running) and logs them instead.
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
	private void sky2m$beforeTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci, @Share("playerBeforeTeleportBlockPos") LocalRef<BlockPos> beforeTeleport) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) beforeTeleport.set(minecraft.player.blockPosition().immutable());
	}

	@Inject(method = "handleMovePlayer", at = @At(value = "RETURN"))
	private void sky2m$onTeleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci, @Share("playerBeforeTeleportBlockPos") LocalRef<BlockPos> beforeTeleport) {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (beforeTeleport.get() != null) {
				TeleportMaze.INSTANCE.onTeleport(minecraft, beforeTeleport.get(), minecraft.player.blockPosition().immutable());
			}
			com.epic60869.sky2m.features.dungeons.OdinPuzzleSolvers.onTeleport(packet, beforeTeleport.get());
			com.epic60869.sky2m.features.misc.CustomSounds.onTeleportPacket();
		} catch (Exception | LinkageError e) {
			sky2m$hookFailed("teleport", e);
		}
	}

	@Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/item/ItemEntity;getItem()Lnet/minecraft/world/item/ItemStack;"))
	private void sky2m$onItemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci, @Local(name = "itemEntity") ItemEntity itemEntity) {
		try {
			DungeonManager.onItemPickup(itemEntity);
			com.epic60869.sky2m.features.dungeons.SecretChime.onItemPickup(itemEntity);
		} catch (Exception | LinkageError e) {
			sky2m$hookFailed("item pickup", e);
		}
	}

	@ModifyExpressionValue(method = "handleEntityEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundEntityEventPacket;getEntity(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;"))
	private Entity sky2m$onEntityDeath(Entity entity, @Local(name = "packet") ClientboundEntityEventPacket packet) {
		if (packet.getEventId() == EntityEvent.DEATH) {
			try {
				DungeonScore.handleEntityDeath(entity);
				com.epic60869.sky2m.features.combat.ZealotCounter.onEntityDeath(entity);
				com.epic60869.sky2m.features.misc.CustomSounds.onEntityDeath(entity);
				com.epic60869.sky2m.features.misc.CarryTracker.onEntityDeath(entity);
			} catch (Exception | LinkageError e) {
				sky2m$hookFailed("entity death", e);
			}
		}
		return entity;
	}

	// Skyblocker's map update hook, missing from the port: without it the dungeon map texture only refreshed when a room was identified.
	@Inject(method = "handleMapItemData", at = @At("RETURN"))
	private void sky2m$onMapItemData(net.minecraft.network.protocol.game.ClientboundMapItemDataPacket packet, CallbackInfo ci) {
		try {
			com.epic60869.sky2m.sb.skyblock.dungeon.DungeonMapTexture.onMapItemDataUpdate(packet.mapId(), packet.colorPatch().isPresent());
		} catch (Exception | LinkageError e) {
			sky2m$hookFailed("map data", e);
		}
	}

	@Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER), cancellable = true)
	private void sky2m$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
		try {
			com.epic60869.sky2m.features.dungeons.DungeonRoutes.onSound(packet.getSound().value(), packet.getX(), packet.getY(), packet.getZ());
			com.epic60869.sky2m.features.dungeons.SecretChime.onSound(packet.getSound().value(), packet.getX(), packet.getY(), packet.getZ(), packet.getVolume());
			com.epic60869.sky2m.features.dungeons.RagAxe.onSound(packet.getSound().value(), packet.getPitch());
			// Custom Sounds: your teleport and leap sounds swapped for your own.
			if (com.epic60869.sky2m.features.misc.CustomSounds.onSound(packet.getSound().value(), packet.getX(), packet.getY(), packet.getZ(), packet.getVolume(), packet.getPitch())) ci.cancel();
		} catch (Exception | LinkageError e) {
			sky2m$hookFailed("sound", e);
		}
	}

	@Unique
	private static void sky2m$hookFailed(String hook, Throwable e) {
		System.err.println("[Sky2M] Skipped a failing " + hook + " packet hook instead of disconnecting: " + e);
	}
}
