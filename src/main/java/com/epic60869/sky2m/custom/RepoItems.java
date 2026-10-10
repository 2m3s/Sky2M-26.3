package com.epic60869.sky2m.custom;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import com.epic60869.sky2m.custom.util.Compat;

/**
 * Sky2M's replacement for Skyblocker's NEU repo and item repository. Single repo files are
 * fetched on demand, and the SkyBlock item list comes from Hypixel's public items resource.
 */
public final class RepoItems {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String NEU_REPO = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/";
	private static final String SKYHANNI_REPO = "https://raw.githubusercontent.com/hannibal002/SkyHanni-REPO/main/";
	private static final String HYPIXEL_ITEMS = "https://api.hypixel.net/v2/resources/skyblock/items";
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Map<String, RepoItem> ITEMS = new LinkedHashMap<>();
	private static final List<Runnable> AFTER_ITEMS_LOADED = new ArrayList<>();
	private static volatile boolean itemsLoaded;

	private record RepoItem(String id, String name, Item item, @Nullable String texture, @Nullable String itemModel, @Nullable String tier) {}

	/** A SkyBlock player head, without creating an item stack. */
	public record Head(String id, String name, String texture) {}

	private RepoItems() {}

	public static void init() {
		runAsync(RepoItems::loadItems);
	}

	public static void runAsync(Runnable runnable) {
		CompletableFuture.runAsync(runnable).exceptionally(e -> {
			LOGGER.error("[Sky2M] Repo task failed", e);
			return null;
		});
	}

	public static String neuRepoFile(String path) throws IOException, InterruptedException {
		return fetch(NEU_REPO + path);
	}

	/** A file from SkyHanni's repo (e.g. "constants/Garden.json"). Blocking. */
	public static String skyHanniRepoFile(String path) throws IOException, InterruptedException {
		return fetch(SKYHANNI_REPO + path);
	}

	public static boolean itemsLoaded() {
		return itemsLoaded;
	}

	/** Runs once the item list has loaded and the client has finished starting. */
	public static void runAfterItemsLoaded(Runnable runnable) {
		synchronized (AFTER_ITEMS_LOADED) {
			if (!itemsLoaded()) {
				AFTER_ITEMS_LOADED.add(runnable);
				return;
			}
		}
		runAsync(runnable);
	}

	private static void runPendingCallbacks() {
		List<Runnable> callbacks;
		synchronized (AFTER_ITEMS_LOADED) {
			if (!itemsLoaded()) return;
			callbacks = List.copyOf(AFTER_ITEMS_LOADED);
			AFTER_ITEMS_LOADED.clear();
		}
		callbacks.forEach(RepoItems::runAsync);
	}

	public static List<Head> heads() {
		synchronized (ITEMS) {
			return ITEMS.values().stream()
					.filter(item -> item.item() == Items.PLAYER_HEAD && item.texture() != null)
					.map(item -> new Head(item.id(), item.name(), item.texture()))
					.toList();
		}
	}

	/** Every SkyBlock item id; empty until the item list has loaded. */
	public static List<String> allIds() {
		synchronized (ITEMS) {
			return new ArrayList<>(ITEMS.keySet());
		}
	}

	/** Every item's name with its colour codes, for suggestions; empty until the item list has loaded. */
	public static List<String> allNames() {
		synchronized (ITEMS) {
			List<String> names = new ArrayList<>();
			for (RepoItem item : ITEMS.values()) if (item.name() != null && !item.name().isBlank()) names.add(item.name());
			return names;
		}
	}

	/** The item id for a plain item name (no colour codes, any case), e.g. "Enchanted Diamond"; null if unknown. */
	public static @Nullable String idByName(String name) {
		String wanted = net.minecraft.ChatFormatting.stripFormatting(name).trim();
		synchronized (ITEMS) {
			for (RepoItem item : ITEMS.values()) {
				if (item.name() != null && net.minecraft.ChatFormatting.stripFormatting(item.name()).trim().equalsIgnoreCase(wanted)) return item.id();
			}
		}
		return null;
	}

	/** Every item id with this plain name (some share one: "Saddle", or a pet item's five rarities). */
	public static List<String> idsByName(String name) {
		String wanted = net.minecraft.ChatFormatting.stripFormatting(name).trim();
		List<String> ids = new ArrayList<>();
		synchronized (ITEMS) {
			for (RepoItem item : ITEMS.values()) {
				if (item.name() != null && net.minecraft.ChatFormatting.stripFormatting(item.name()).trim().equalsIgnoreCase(wanted)) ids.add(item.id());
			}
		}
		return ids;
	}

	/**
	 * Hypixel's item model for the item ("hypixel_skyblock:item/uncategorized/summoning_eye"), drawn by Hypixel's
	 * resource pack; null if it has none. Without that pack loaded the item looks like its plain material (paper).
	 */
	public static @Nullable String itemModel(String id) {
		synchronized (ITEMS) {
			RepoItem item = ITEMS.get(id);
			return item == null ? null : item.itemModel();
		}
	}

	public static @Nullable String displayName(String id) {
		synchronized (ITEMS) {
			RepoItem item = ITEMS.get(id);
			return item == null ? null : item.name();
		}
	}

	/** The item's rarity as Hypixel's item list gives it ("LEGENDARY", "EPIC", ...), or null if unknown. */
	public static @Nullable String tier(String id) {
		synchronized (ITEMS) {
			RepoItem item = ITEMS.get(id);
			return item == null ? null : item.tier();
		}
	}

	/** The colour of a rarity ("LEGENDARY" is gold), white when unknown. */
	public static net.minecraft.ChatFormatting tierColour(@Nullable String tier) {
		if (tier == null) return net.minecraft.ChatFormatting.WHITE;
		return switch (tier) {
			case "UNCOMMON" -> net.minecraft.ChatFormatting.GREEN;
			case "RARE" -> net.minecraft.ChatFormatting.BLUE;
			case "EPIC" -> net.minecraft.ChatFormatting.DARK_PURPLE;
			case "LEGENDARY" -> net.minecraft.ChatFormatting.GOLD;
			case "MYTHIC" -> net.minecraft.ChatFormatting.LIGHT_PURPLE;
			case "DIVINE" -> net.minecraft.ChatFormatting.AQUA;
			case "SPECIAL", "VERY_SPECIAL" -> net.minecraft.ChatFormatting.RED;
			case "ULTIMATE" -> net.minecraft.ChatFormatting.DARK_RED;
			default -> net.minecraft.ChatFormatting.WHITE;
		};
	}

	public static ItemStack itemStack(String id) {
		RepoItem item;
		synchronized (ITEMS) {
			item = ITEMS.get(id);
		}
		return item == null ? Compat.barrier() : createStack(item);
	}

	private static ItemStack createStack(RepoItem repoItem) {
		ItemStack stack = repoItem.texture() != null ? Compat.createSkull(repoItem.texture()) : new ItemStack(repoItem.item());
		// Newer items are paper with Hypixel's model on top; use the model when it's loaded (Hypixel's pack, or vanilla).
		Identifier model = repoItem.itemModel() == null ? null : Identifier.tryParse(repoItem.itemModel());
		if (model != null && repoItem.texture() == null && hasItemModel(model)) stack.set(DataComponents.ITEM_MODEL, model);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(repoItem.name()));
		CompoundTag tag = new CompoundTag();
		tag.putString("id", repoItem.id());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	private static void loadItems() {
		try {
			JsonObject root = JsonParser.parseString(fetch(HYPIXEL_ITEMS)).getAsJsonObject();
			Map<String, RepoItem> loaded = new LinkedHashMap<>();
			for (JsonElement element : root.getAsJsonArray("items")) {
				JsonObject item = element.getAsJsonObject();
				if (!item.has("id")) continue;
				String id = item.get("id").getAsString();
				String name = item.has("name") ? item.get("name").getAsString() : id;
				String material = item.has("material") ? item.get("material").getAsString() : "";
				int durability = item.has("durability") ? item.get("durability").getAsInt() : 0;
				String texture = skinTexture(item.get("skin"));
				String itemModel = item.has("item_model") ? item.get("item_model").getAsString() : null;
				String tier = item.has("tier") ? item.get("tier").getAsString() : null;
				loaded.put(id, new RepoItem(id, name, material(material, durability), texture, itemModel, tier));
			}
			synchronized (ITEMS) {
				ITEMS.clear();
				ITEMS.putAll(loaded);
			}
			itemsLoaded = true;
			LOGGER.info("[Sky2M] Loaded {} SkyBlock items", loaded.size());
			runPendingCallbacks();
		} catch (Exception e) {
			LOGGER.error("[Sky2M] Failed to load SkyBlock items", e);
		}
	}

	/** Hypixel sends {@code skin} either as the texture string or as {@code {"value": ..., "signature": ...}}. */
	private static @Nullable String skinTexture(@Nullable JsonElement skin) {
		if (skin == null || skin.isJsonNull()) return null;
		if (skin.isJsonPrimitive()) return skin.getAsString();
		if (skin.isJsonObject() && skin.getAsJsonObject().has("value")) return skin.getAsJsonObject().get("value").getAsString();
		return null;
	}

	/** Whether the client has this item model (Hypixel's only exist while its resource pack is loaded). */
	public static boolean hasItemModel(Identifier model) {
		try {
			var models = net.minecraft.client.Minecraft.getInstance().getModelManager();
			return models.getItemModel(model) != models.getItemModel(Identifier.fromNamespaceAndPath("sky2m", "no_such_item_model"));
		} catch (Exception e) {
			return false;
		}
	}

	private static Item material(String material, int durability) {
		if (material.equalsIgnoreCase("SKULL_ITEM")) return Items.PLAYER_HEAD;
		Identifier id = Identifier.tryParse(LegacyMaterials.modern(material, durability));
		return id == null ? Items.BARRIER : BuiltInRegistries.ITEM.getOptional(id).orElse(Items.BARRIER);
	}

	private static String fetch(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "Sky2M/1.0")
				.GET().build();
		HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + url);
		return response.body();
	}
}
