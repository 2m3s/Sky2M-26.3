// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.Pair;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;


public class CustomArmorTrims {
	private static final Logger LOGGER = LoggerFactory.getLogger(CustomArmorTrims.class);
	public static final Object2ObjectOpenHashMap<ArmorTrimId, ArmorTrim> TRIMS_CACHE = new Object2ObjectOpenHashMap<>();
	private static boolean trimsInitialized = false;
	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((_, _, _) -> initializeTrimCache());
		ClientCommandRegistrationCallback.EVENT.register(CustomArmorTrims::registerCommand);
	}

	private static void initializeTrimCache() {
		Minecraft client = Minecraft.getInstance();
		if (trimsInitialized || (client == null && !false)) {
			return;
		}
		try {
			TRIMS_CACHE.clear();
			HolderLookup.Provider wrapperLookup = Compat.getRegistryWrapperLookup();
			for (Reference<TrimMaterial> material : wrapperLookup.lookupOrThrow(Registries.TRIM_MATERIAL).listElements().toList()) {
				for (Reference<TrimPattern> pattern : wrapperLookup.lookupOrThrow(Registries.TRIM_PATTERN).listElements().toList()) {
					ArmorTrim trim = new ArmorTrim(material, pattern);

					TRIMS_CACHE.put(new ArmorTrimId(material.key().identifier(), pattern.key().identifier()), trim);
				}
			}

			LOGGER.info("[Sky2M] Successfully cached all armor trims!");
			trimsInitialized = true;
		} catch (Exception e) {
			LOGGER.error("[Sky2M] Encountered an exception while caching armor trims", e);
		}
	}

	private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext registryAccess) {
		for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root)
				.then(ClientCommands.literal("custom")
						.then(ClientCommands.literal("armorTrim")
								.executes(context -> customizeTrim(context.getSource(), null, null))
								.then(ClientCommands.argument("material", IdentifierArgument.id())
										.suggests(getIdSuggestionProvider(Registries.TRIM_MATERIAL))
										.executes(context -> customizeTrim(context.getSource(), context.getArgument("material", Identifier.class), null))
										.then(ClientCommands.argument("pattern", IdentifierArgument.id())
												.suggests(getIdSuggestionProvider(Registries.TRIM_PATTERN))
												.executes(context -> customizeTrim(context.getSource(), context.getArgument("material", Identifier.class), context.getArgument("pattern", Identifier.class))))))));
	}

	private static <T> SuggestionProvider<FabricClientCommandSource> getIdSuggestionProvider(ResourceKey<? extends Registry<T>> registryKey) {
		return (context, builder) -> context.getSource().suggestRegistryElements(registryKey, SharedSuggestionProvider.ElementSuggestionType.ELEMENTS, builder, context, element -> true);
	}

	@SuppressWarnings("SameReturnValue")
	private static int customizeTrim(FabricClientCommandSource source, Identifier material, Identifier pattern) {
		ItemStack heldItem = source.getPlayer().getMainHandItem();

		if (Compat.isOnSkyblock() && heldItem != null) {
			if (heldItem.is(ItemTags.TRIMMABLE_ARMOR)) {
				String itemUuid = Compat.uuid(heldItem);

				if (!itemUuid.isEmpty()) {
					Object2ObjectOpenHashMap<String, ArmorTrimId> customArmorTrims = CustomConfigManager.get().general.customArmorTrims;

					if (material == null && pattern == null) {
						if (customArmorTrims.containsKey(itemUuid)) {
							CustomConfigManager.update(config -> config.general.customArmorTrims.remove(itemUuid));
							source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.removed")));
						} else {
							source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.neverHad")));
						}
					} else {
						// Ensure that the material & trim are valid
						ArmorTrimId trimId = new ArmorTrimId(material, pattern);
						if (TRIMS_CACHE.get(trimId) == null) {
							source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.invalidMaterialOrPattern")));

							return Command.SINGLE_SUCCESS;
						}

						CustomConfigManager.update(config -> config.general.customArmorTrims.put(itemUuid, trimId));
						source.sendFeedback(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.added")));
					}
				} else {
					source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.noItemUuid")));
				}
			} else {
				source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.notAnArmorPiece")));
				return Command.SINGLE_SUCCESS;
			}
		} else {
			source.sendError(Compat.PREFIX.get().append(Component.translatable("sky2m.customArmorTrims.unableToSetTrim")));
		}

		return Command.SINGLE_SUCCESS;
	}

	public record ArmorTrimId(Identifier material, Identifier pattern) implements Pair<Identifier, Identifier> {
		public static final Codec<ArmorTrimId> CODEC = RecordCodecBuilder.create(instance -> instance.group(
						Identifier.CODEC.fieldOf("material").forGetter(ArmorTrimId::material),
						Identifier.CODEC.fieldOf("pattern").forGetter(ArmorTrimId::pattern))
				.apply(instance, ArmorTrimId::new));

		@Override
		public Identifier left() {
			return material();
		}

		@Override
		public Identifier right() {
			return pattern();
		}
	}
}
