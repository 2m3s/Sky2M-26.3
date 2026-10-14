package com.epic60869.sky2m.custom.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.Command;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ResolvableProfile;

import com.epic60869.sky2m.Sky2MCustom;

/**
 * Stand-ins for the Skyblocker utilities used by the ported /skyblocker custom code
 * (Utils, ItemUtils, Constants, Scheduler, RegistryUtils, ...). Ported from Skyblocker (LGPL-3.0).
 */
public final class Compat {
	public static final String NAMESPACE = "sky2m";
	public static final String[] COMMAND_ROOTS = {"s2", "s2m"};
	public static final String UUID = "uuid";
	public static final int PLACEHOLDER_ID = -1;
	public static final NumberFormat DOUBLE_NUMBERS = net.minecraft.util.Util.make(NumberFormat.getInstance(Locale.US), nf -> nf.setMaximumFractionDigits(2));
	public static final Function<ChatFormatting, Component> FORMATTING_FORMATTER = formatting -> Component.literal(StringUtils.capitalize(formatting.name().replaceAll("_", " ")));
	public static final Supplier<MutableComponent> PREFIX = () -> Component.empty()
			.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
			.append(Component.literal("S2M").withStyle(ChatFormatting.AQUA))
			.append(Component.literal("] ").withStyle(ChatFormatting.GRAY));

	private static final HolderLookup.Provider LOOKUP = VanillaRegistries.createWorldLookup();
	private static @Nullable Screen queuedScreen;
	private static boolean queued;

	private static final MethodHandle GUI_RENDER_STATE;
	private static final MethodHandle SCISSOR_STACK;
	private static final MethodHandle SCISSOR_PEEK;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			// Minecraft 26.x is unobfuscated, so these private members can be reached by name.
			Field renderState = GuiGraphicsExtractor.class.getDeclaredField("guiRenderState");
			renderState.setAccessible(true);
			GUI_RENDER_STATE = lookup.unreflectGetter(renderState);
			Field scissorStack = GuiGraphicsExtractor.class.getDeclaredField("scissorStack");
			scissorStack.setAccessible(true);
			SCISSOR_STACK = lookup.unreflectGetter(scissorStack);
			Method peek = scissorStack.getType().getDeclaredMethod("peek");
			peek.setAccessible(true);
			SCISSOR_PEEK = lookup.unreflect(peek);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private Compat() {}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!queued) return;
			queued = false;
			Screen screen = queuedScreen;
			queuedScreen = null;
			client.gui.setScreen(screen);
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}

	/** The tab list name Hypixel sent, without Sky2M's nickname replacement; for reading the tab list. */
	public static net.minecraft.network.chat.Component rawTabName(net.minecraft.client.multiplayer.PlayerInfo info) {
		return ((com.epic60869.sky2m.mixin.Sky2MPlayerInfoAccessor) info).sky2m$rawTabListDisplayName();
	}

	public static boolean isOnSkyblock() {
		return Sky2MCustom.isHypixel(Minecraft.getInstance()) && com.epic60869.sky2m.features.core.Sky2MLocation.skyblockOnlyAllows();
	}

	public static String uuid(ItemStack stack) {
		return Sky2MCustom.uuid(stack);
	}

	/** Set while {@link #realName} runs, so the /s2 custom rename mixin returns Hypixel's own name. */
	public static boolean bypassCustomNames;

	/**
	 * The item's name without /s2 custom renames. Features that recognise items by name use this,
	 * so renaming e.g. your Last Breath doesn't break them.
	 */
	public static Component realName(ItemStack stack) {
		boolean old = bypassCustomNames;
		bypassCustomNames = true;
		try {
			return stack.getHoverName();
		} finally {
			bypassCustomNames = old;
		}
	}

	/**
	 * SkyBlock item ids by custom data instance. Custom data is immutable, so an id read once stays right, and the
	 * weak identity keys drop entries when the item's data goes away. Saves copying the whole tag (enchantments,
	 * gems, attributes...) on every call from the per-tick inventory scans.
	 */
	private static final java.util.concurrent.ConcurrentMap<CustomData, String> NEU_NAMES =
		new com.google.common.collect.MapMaker().weakKeys().makeMap();

	public static String neuName(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return "";
		// Read without copying: a new item in a menu copied its whole tag just to read the id.
		return NEU_NAMES.computeIfAbsent(data, d -> ((com.epic60869.sky2m.mixin.Sky2MCustomDataAccessor) (Object) d).sky2m$getTag().getStringOr("id", ""));
	}

	public static CompoundTag getCustomData(ItemStack stack) {
		return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
	}

	/** Shared empty tag for {@link #customDataView}; read-only by contract, so never written into. */
	private static final CompoundTag EMPTY_VIEW = new CompoundTag();

	/**
	 * The item's custom data without copying it, for reading only (don't change it). {@link #getCustomData} copies the
	 * whole tag, which per-tick scans over every slot did many times a second.
	 */
	public static CompoundTag customDataView(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? EMPTY_VIEW : ((com.epic60869.sky2m.mixin.Sky2MCustomDataAccessor) (Object) data).sky2m$getTag();
	}

	public static List<ItemStack> getArmor(LivingEntity entity) {
		return EquipmentSlotGroup.ARMOR.slots().stream()
				.filter(es -> es.getType() == EquipmentSlot.Type.HUMANOID_ARMOR)
				.map(entity::getItemBySlot)
				.toList();
	}

	/**
	 * Skyblocker reads the equipment set (necklace, cloak, belt, gloves) from its SkyBlock inventory screen.
	 * Sky2M does not track equipment, so only the hand, armour and inventory are offered.
	 */
	public static List<ItemStack> getCurrentEquipmentSet() {
		return List.of();
	}

	public static PropertyMap propertyMapWithTexture(String textureValue) {
		return ExtraCodecs.PROPERTY_MAP.parse(JsonOps.INSTANCE, JsonParser.parseString("[{\"name\":\"textures\",\"value\":\"" + textureValue + "\"}]")).getOrThrow();
	}

	public static String getHeadTexture(ItemStack stack) {
		if (!stack.is(Items.PLAYER_HEAD)) return "";

		ResolvableProfile profile = stack.get(DataComponents.PROFILE);
		if (profile == null) return "";

		return profile.partialProfile().properties().get("textures").stream()
				.filter(Objects::nonNull)
				.map(Property::value)
				.findFirst()
				.orElse("");
	}

	public static ItemStack createSkull(String texture) {
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		try {
			GameProfile profile = new GameProfile(java.util.UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "custom", propertyMapWithTexture(texture));
			stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
		} catch (Exception ignored) {}
		return stack;
	}

	public static ItemStack barrier() {
		return new ItemStack(Items.BARRIER);
	}

	public static <T> T cycle(List<T> list, int currentIndex) {
		return list.get((currentIndex + 1) % list.size());
	}

	public static <K, V> Codec<Object2ObjectMap<K, V>> object2ObjectMapCodec(Codec<K> keyCodec, Codec<V> valueCodec) {
		return Codec.unboundedMap(keyCodec, valueCodec).xmap(Object2ObjectOpenHashMap::new, Function.identity());
	}

	public static HolderLookup.Provider getRegistryWrapperLookup() {
		Minecraft client = Minecraft.getInstance();
		return client != null && client.getConnection() != null && client.getConnection().registryAccess() != null ? client.getConnection().registryAccess() : LOOKUP;
	}

	/** Opens the screen on the next tick so the closing chat screen does not replace it. */
	public static int queueOpenScreen(@Nullable Screen screen) {
		queuedScreen = screen;
		queued = true;
		return Command.SINGLE_SUCCESS;
	}

	public static Command<FabricClientCommandSource> queueOpenScreenCommand(Supplier<Screen> screenSupplier) {
		return _ -> queueOpenScreen(screenSupplier.get());
	}

	public static GuiRenderState guiRenderState(GuiGraphicsExtractor graphics) {
		try {
			return (GuiRenderState) GUI_RENDER_STATE.invoke(graphics);
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}

	public static @Nullable ScreenRectangle scissor(GuiGraphicsExtractor graphics) {
		try {
			return (ScreenRectangle) SCISSOR_PEEK.invoke(SCISSOR_STACK.invoke(graphics));
		} catch (Throwable e) {
			throw new IllegalStateException(e);
		}
	}
}
