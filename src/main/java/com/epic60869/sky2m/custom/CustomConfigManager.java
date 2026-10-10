package com.epic60869.sky2m.custom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.slf4j.Logger;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

/**
 * Holds the customization maps that Skyblocker keeps in {@code config.general}, with the same
 * {@link #update}/{@link #updateOnly} semantics, persisted to {@code config/sky2m-custom.json}.
 */
public final class CustomConfigManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "sky2m-custom.json";

	private static final CustomConfig CONFIG = new CustomConfig();
	private static Path configDir;

	private CustomConfigManager() {}

	public static final class CustomConfig {
		public final GeneralConfig general = new GeneralConfig();
	}

	public static final class GeneralConfig {
		public Object2ObjectOpenHashMap<String, Component> customItemNames = new Object2ObjectOpenHashMap<>();
		public Object2IntOpenHashMap<String> customDyeColors = new Object2IntOpenHashMap<>();
		public Object2ObjectOpenHashMap<String, CustomArmorTrims.ArmorTrimId> customArmorTrims = new Object2ObjectOpenHashMap<>();
		public Object2ObjectOpenHashMap<String, CustomArmorAnimatedDyes.AnimatedDye> customAnimatedDyes = new Object2ObjectOpenHashMap<>();
		public Object2ObjectOpenHashMap<String, String> customHelmetTextures = new Object2ObjectOpenHashMap<>();
		public Object2BooleanOpenHashMap<String> customGlint = new Object2BooleanOpenHashMap<>();
		public Object2ObjectOpenHashMap<String, Identifier> customItemModel = new Object2ObjectOpenHashMap<>();
		public Object2ObjectOpenHashMap<String, Identifier> customArmorModel = new Object2ObjectOpenHashMap<>();
		public Object2ObjectOpenHashMap<String, String> customAnimatedHelmetTextures = new Object2ObjectOpenHashMap<>();
	}

	public static CustomConfig get() {
		return CONFIG;
	}

	/** Executes the given {@code action} to update fields in the config, then saves the changes. */
	public static void update(Consumer<CustomConfig> action) {
		action.accept(CONFIG);
		save();
	}

	/** Executes the given {@code action} to update fields in the config, without saving the changes. */
	public static void updateOnly(Consumer<CustomConfig> action) {
		action.accept(CONFIG);
	}

	public static void init(Path dir) {
		configDir = dir;
		load();
	}

	private static void load() {
		if (configDir == null) return;
		Path file = configDir.resolve(FILE_NAME);
		if (!Files.exists(file)) return;

		GeneralConfig general = CONFIG.general;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();

			// Current (Skyblocker-compatible) keys, with fallbacks for the previous Sky2M format.
			forEach(root, "customItemNames", "itemNames", (k, v) -> {
				Component name = parseName(v);
				if (name != null) general.customItemNames.put(k, name);
			});
			forEach(root, "customDyeColors", "dyeColors", (k, v) -> general.customDyeColors.put(k, v.getAsInt()));
			forEach(root, "customArmorTrims", "armorTrims", (k, v) -> {
				JsonObject o = v.getAsJsonObject();
				general.customArmorTrims.put(k, new CustomArmorTrims.ArmorTrimId(
						Identifier.parse(o.get("material").getAsString()), Identifier.parse(o.get("pattern").getAsString())));
			});
			forEach(root, "customAnimatedDyes", "animatedDyes", (k, v) -> {
				CustomArmorAnimatedDyes.AnimatedDye dye = parseAnimatedDye(v.getAsJsonObject());
				if (dye != null) general.customAnimatedDyes.put(k, dye);
			});
			forEach(root, "customHelmetTextures", "helmetSkins", (k, v) -> general.customHelmetTextures.put(k, v.getAsString()));
			forEach(root, "customGlint", "itemGlints", (k, v) -> general.customGlint.put(k, v.getAsBoolean()));
			forEach(root, "customItemModel", "itemModels", (k, v) -> general.customItemModel.put(k, Identifier.parse(v.getAsString())));
			forEach(root, "customArmorModel", null, (k, v) -> general.customArmorModel.put(k, Identifier.parse(v.getAsString())));
			forEach(root, "customAnimatedHelmetTextures", null, (k, v) -> general.customAnimatedHelmetTextures.put(k, v.getAsString()));
		} catch (Exception e) {
			LOGGER.error("[Sky2M] Failed to load custom item config", e);
		}
	}

	private static void forEach(JsonObject root, String key, String legacyKey, java.util.function.BiConsumer<String, JsonElement> action) {
		JsonElement element = root.has(key) ? root.get(key) : legacyKey != null ? root.get(legacyKey) : null;
		if (element == null || !element.isJsonObject()) return;
		for (var entry : element.getAsJsonObject().entrySet()) {
			try {
				action.accept(entry.getKey(), entry.getValue());
			} catch (Exception e) {
				LOGGER.warn("[Sky2M] Skipping invalid custom item entry {}", entry.getKey(), e);
			}
		}
	}

	private static Component parseName(JsonElement value) {
		// Old Sky2M names were stored as plain strings (sometimes holding component JSON).
		if (value.isJsonPrimitive()) {
			String text = value.getAsString();
			if (text.trim().startsWith("{")) {
				Component parsed = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)).result().orElse(null);
				if (parsed != null) return parsed;
			}
			return Component.literal(text);
		}
		return ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, value).result().orElse(null);
	}

	private static CustomArmorAnimatedDyes.AnimatedDye parseAnimatedDye(JsonObject o) {
		List<CustomArmorAnimatedDyes.Keyframe> frames = new ArrayList<>();
		if (o.has("keyframes")) {
			o.getAsJsonArray("keyframes").forEach(f -> frames.add(new CustomArmorAnimatedDyes.Keyframe(
					f.getAsJsonObject().get("color").getAsInt(), f.getAsJsonObject().get("time").getAsFloat())));
		}
		if (frames.size() < 2) return null;
		return new CustomArmorAnimatedDyes.AnimatedDye(List.copyOf(frames),
				o.get("cycleBack").getAsBoolean(), o.get("delay").getAsFloat(), o.get("duration").getAsFloat());
	}

	private static void save() {
		if (configDir == null) return;
		GeneralConfig general = CONFIG.general;
		JsonObject root = new JsonObject();

		JsonObject names = new JsonObject();
		general.customItemNames.forEach((k, v) -> ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, v).result().ifPresent(json -> names.add(k, json)));
		root.add("customItemNames", names);

		JsonObject dyes = new JsonObject();
		general.customDyeColors.object2IntEntrySet().forEach(e -> dyes.addProperty(e.getKey(), e.getIntValue()));
		root.add("customDyeColors", dyes);

		JsonObject trims = new JsonObject();
		general.customArmorTrims.forEach((k, v) -> {
			JsonObject o = new JsonObject();
			o.addProperty("material", v.material().toString());
			o.addProperty("pattern", v.pattern().toString());
			trims.add(k, o);
		});
		root.add("customArmorTrims", trims);

		JsonObject animated = new JsonObject();
		general.customAnimatedDyes.forEach((k, v) -> {
			JsonObject o = new JsonObject();
			com.google.gson.JsonArray frames = new com.google.gson.JsonArray();
			v.keyframes().forEach(frame -> {
				JsonObject f = new JsonObject();
				f.addProperty("color", frame.color());
				f.addProperty("time", frame.time());
				frames.add(f);
			});
			o.add("keyframes", frames);
			o.addProperty("cycleBack", v.cycleBack());
			o.addProperty("delay", v.delay());
			o.addProperty("duration", v.duration());
			animated.add(k, o);
		});
		root.add("customAnimatedDyes", animated);

		JsonObject helmets = new JsonObject();
		general.customHelmetTextures.forEach(helmets::addProperty);
		root.add("customHelmetTextures", helmets);

		JsonObject glints = new JsonObject();
		general.customGlint.object2BooleanEntrySet().forEach(e -> glints.addProperty(e.getKey(), e.getBooleanValue()));
		root.add("customGlint", glints);

		JsonObject itemModels = new JsonObject();
		general.customItemModel.forEach((k, v) -> itemModels.addProperty(k, v.toString()));
		root.add("customItemModel", itemModels);

		JsonObject armorModels = new JsonObject();
		general.customArmorModel.forEach((k, v) -> armorModels.addProperty(k, v.toString()));
		root.add("customArmorModel", armorModels);

		JsonObject animatedHelmets = new JsonObject();
		general.customAnimatedHelmetTextures.forEach(animatedHelmets::addProperty);
		root.add("customAnimatedHelmetTextures", animatedHelmets);

		try {
			Files.createDirectories(configDir);
			Files.writeString(configDir.resolve(FILE_NAME), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("[Sky2M] Failed to save custom item config", e);
		}
	}
}
