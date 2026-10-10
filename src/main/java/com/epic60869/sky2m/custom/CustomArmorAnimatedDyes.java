// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import com.epic60869.sky2m.custom.util.Compat;
import java.util.List;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.jetbrains.annotations.VisibleForTesting;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.custom.util.OkLabColor;
import com.epic60869.sky2m.custom.util.ColorArgumentType;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class CustomArmorAnimatedDyes {
	private static final Object2ObjectOpenHashMap<AnimatedDye, AnimatedDyeStateTracker> STATE_TRACKER_MAP = new Object2ObjectOpenHashMap<>();
	private static final float DEFAULT_DELAY = 0;
	private static int frames;
	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register(CustomArmorAnimatedDyes::registerCommands);
		LevelExtractionEvents.END_EXTRACTION.register(_ -> ++frames);
		// have the animation restart on world change because why not?
		ClientPlayConnectionEvents.JOIN.register((_, _, _) -> cleanTrackers());
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
		for (String root : Compat.COMMAND_ROOTS) dispatcher.register(literal(root)
				.then(literal("custom")
						.then(literal("animatedDye")
								.executes(context -> customizeAnimatedDye(context.getSource(), Integer.MIN_VALUE, Integer.MIN_VALUE, 0, false, 0))
								.then(argument("hex1", ColorArgumentType.hex())
										.then(argument("hex2", ColorArgumentType.hex())
												.then(argument("duration", FloatArgumentType.floatArg(0.1f, 10f))
														.then(argument("cycleBack", BoolArgumentType.bool())
																.executes(context -> customizeAnimatedDye(context.getSource(), ColorArgumentType.getIntFromHex(context, "hex1"), ColorArgumentType.getIntFromHex(context, "hex2"), FloatArgumentType.getFloat(context, "duration"), BoolArgumentType.getBool(context, "cycleBack"), DEFAULT_DELAY))
																.then(argument("delay", FloatArgumentType.floatArg(0))
																		.executes(context -> customizeAnimatedDye(context.getSource(), ColorArgumentType.getIntFromHex(context, "hex1"), ColorArgumentType.getIntFromHex(context, "hex2"), FloatArgumentType.getFloat(context, "duration"), BoolArgumentType.getBool(context, "cycleBack"), FloatArgumentType.getFloat(context, "delay")))))))))));
	}

	private static int customizeAnimatedDye(FabricClientCommandSource source, int color1, int color2, float duration, boolean cycleBack, float delay) {
		ItemStack heldItem = source.getPlayer().getMainHandItem();

		if (Compat.isOnSkyblock() && heldItem != null && !heldItem.isEmpty()) {
			if (heldItem.is(ItemTags.CAULDRON_CAN_REMOVE_DYE)) {
				String itemUuid = Compat.uuid(heldItem);

				if (!itemUuid.isEmpty()) {
					Object2ObjectOpenHashMap<String, AnimatedDye> customAnimatedDyes = CustomConfigManager.get().general.customAnimatedDyes;

					if (color1 == Integer.MIN_VALUE && color2 == Integer.MIN_VALUE) {
						if (customAnimatedDyes.containsKey(itemUuid)) {
							CustomConfigManager.update(config -> config.general.customAnimatedDyes.remove(itemUuid));
							source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customAnimatedDyes.removed")));
						} else {
							source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customAnimatedDyes.neverHad")));
						}
					} else {
						AnimatedDye animatedDye = new AnimatedDye(List.of(new Keyframe(color1, 0), new Keyframe(color2, 1)), cycleBack, delay, duration);

						CustomConfigManager.update(config -> config.general.customAnimatedDyes.put(itemUuid, animatedDye));
						source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customAnimatedDyes.added")));
					}
				} else {
					source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customAnimatedDyes.noItemUuid")));
				}
			} else {
				source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customAnimatedDyes.notDyeable")));
			}
		} else {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customAnimatedDyes.unableToSetDye")));
		}

		return Command.SINGLE_SUCCESS;
	}

	public static int animateColorTransition(AnimatedDye animatedDye) {
		AnimatedDyeStateTracker trackedState = STATE_TRACKER_MAP.computeIfAbsent(animatedDye, AnimatedDyeStateTracker::new);

		if (trackedState.lastRecordedFrame == frames) {
			return trackedState.lastColor;
		}

		trackedState.lastRecordedFrame = frames;

		return trackedState.interpolate(animatedDye, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaTicks());
	}

	@VisibleForTesting
	static class AnimatedDyeStateTracker {
		private float progress = 0;
		private boolean onBackCycle = false;
		private int lastColor = 0;
		private int lastRecordedFrame = 0;

		@VisibleForTesting
		AnimatedDyeStateTracker(AnimatedDye animatedDye) {
			if (animatedDye.delay() > 0) {
				if (animatedDye.cycleBack()) {
					onBackCycle = true;
					progress = animatedDye.delay() / animatedDye.duration();
				} else {
					progress = 1 - animatedDye.delay() / animatedDye.duration();
				}
				progress = clamp(progress);
			}
		}

		@VisibleForTesting
		int interpolate(AnimatedDye animatedDye, float deltaTicks) {
			update(animatedDye, deltaTicks);

			int keyframe = 0;
			// keyframe cannot be the last keyframe, or else keyframe + 1 will be out of bounds, so we check for less than size - 2
			while (keyframe < animatedDye.keyframes.size() - 2 && animatedDye.keyframes.get(keyframe + 1).time < progress) keyframe++;

			Keyframe current = onBackCycle ? animatedDye.keyframes.get(keyframe + 1) : animatedDye.keyframes.get(keyframe);
			Keyframe next = onBackCycle ? animatedDye.keyframes.get(keyframe) : animatedDye.keyframes.get(keyframe + 1);

			float colorProgress = (progress - current.time) / (next.time - current.time);
			colorProgress = clamp(colorProgress);

			return lastColor = OkLabColor.interpolate(current.color, next.color, colorProgress);
		}

		private void update(AnimatedDye animatedDye, float deltaTicks) {
			float v = deltaTicks * 0.05f / animatedDye.duration;
			if (onBackCycle) {
				progress -= v;
				if (progress <= 0f) {
					onBackCycle = false;
					progress = Math.abs(progress);
				}
			} else {
				progress += v;
				if (progress >= 1f) {
					if (animatedDye.cycleBack) {
						onBackCycle = true;
						progress = 2f - progress;
					} else {
						progress %= 1f;
					}
				}
			}

			// Sanity clamp because I got some pretty weird errors with progress being greater than 1
			progress = clamp(progress);
		}

		private static float clamp(float progress) {
			return Math.clamp(progress, 0, 1);
		}
	}

	public static void cleanTrackers() {
		STATE_TRACKER_MAP.clear();
	}

	public record Keyframe(int color, float time) {}
	public record AnimatedDye(List<Keyframe> keyframes, boolean cycleBack, float delay, float duration) {}
}
