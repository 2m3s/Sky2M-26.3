// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.custom.util.ColorArgumentType;

public class CustomArmorDyeColors {
	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register(CustomArmorDyeColors::registerCommands);
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
		for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root)
				.then(ClientCommands.literal("custom")
						.then(ClientCommands.literal("dyeColor")
								.executes(context -> customizeDyeColor(context.getSource(), Integer.MIN_VALUE))
								.then(ClientCommands.argument("hexCode", ColorArgumentType.hex())
										.executes(context -> customizeDyeColor(context.getSource(), ColorArgumentType.getIntFromHex(context, "hexCode")))))));
	}

	@SuppressWarnings("SameReturnValue")
	private static int customizeDyeColor(FabricClientCommandSource source, int color) {
		ItemStack heldItem = source.getPlayer().getMainHandItem();

		if (Compat.isOnSkyblock() && heldItem != null) {
			if (heldItem.is(ItemTags.CAULDRON_CAN_REMOVE_DYE)) {
				String itemUuid = Compat.uuid(heldItem);

				if (!itemUuid.isEmpty()) {
					Object2IntOpenHashMap<String> customDyeColors = CustomConfigManager.get().general.customDyeColors;

					if (color == Integer.MIN_VALUE) {
						if (customDyeColors.containsKey(itemUuid)) {
							CustomConfigManager.update(config -> config.general.customDyeColors.removeInt(itemUuid));
							source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customDyeColors.removed")));
						} else {
							source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customDyeColors.neverHad")));
						}
					} else {
						CustomConfigManager.update(config -> config.general.customDyeColors.put(itemUuid, color));
						source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customDyeColors.added")));
					}
				} else {
					source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customDyeColors.noItemUuid")));
				}
			} else {
				source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customDyeColors.notDyeable")));
				return Command.SINGLE_SUCCESS;
			}
		} else {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customDyeColors.unableToSetColor")));
		}

		return Command.SINGLE_SUCCESS;
	}
}
