// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.ComponentArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.custom.screen.CustomizeScreen;

public class CustomItemNames {
	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register(CustomItemNames::registerCommands);
	}

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
		for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root)
				.then(ClientCommands.literal("custom")
						.then(ClientCommands.literal("renameItem")
								.executes(context -> openScreen(context.getSource()))
								.then(ClientCommands.argument("textComponent", ComponentArgument.textComponent(registryAccess))
										.executes(context -> renameItem(context.getSource(), context.getArgument("textComponent", Component.class))))
								// greedy string will only consume the arg if the text component parsing fails.
								.then(ClientCommands.argument("basicText", StringArgumentType.greedyString())
										.executes(context -> renameItem(context.getSource(), Component.nullToEmpty(context.getArgument("basicText", String.class))))))));
	}

	private static int openScreen(FabricClientCommandSource source) {
		if (!Compat.isOnSkyblock()) {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customItemNames.notOnSkyblock")));
			return 0;
		}
		ItemStack handStack = source.getPlayer().getMainHandItem();
		if (handStack.isEmpty()) {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customItemNames.noItem")));
			return 0;
		}
		if (Compat.uuid(handStack).isEmpty()) {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customItemNames.noItemUuid")));
			return 0;
		}
		Compat.queueOpenScreen(new CustomizeScreen(null, true));
		return Command.SINGLE_SUCCESS;
	}

	@SuppressWarnings("SameReturnValue")
	private static int renameItem(FabricClientCommandSource source, Component text) {
		if (Compat.isOnSkyblock()) {
			String itemUuid = Compat.uuid(source.getPlayer().getMainHandItem());

			if (!itemUuid.isEmpty()) {
				CustomConfigManager.update(config -> {
					Object2ObjectOpenHashMap<String, Component> customItemNames = config.general.customItemNames;
					//If the text is provided then set the item's custom name to it

					//Set italic to false if it hasn't been changed (or was already false)
					Style currentStyle = text.getStyle();
					((MutableComponent) text).setStyle(currentStyle.withItalic(currentStyle.isItalic()));

					customItemNames.put(itemUuid, text);
				});
				source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customItemNames.added")));

			} else {
				source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customItemNames.noItemUuid")));
			}
		} else {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customItemNames.notOnSkyblock")));
		}

		return Command.SINGLE_SUCCESS;
	}
}
