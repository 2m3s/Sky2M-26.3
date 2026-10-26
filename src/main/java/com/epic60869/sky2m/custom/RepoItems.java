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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private static final Map<String, RepoItem> ITEMS = new LinkedHashMap<>();
	/** Items that take essence to star (dungeon items), from Hypixel's {@code upgrade_costs}. */
	private static volatile java.util.Set<String> starable = java.util.Set.of();
	/** Item id -> its per-star {@code upgrade_costs} (essence and items), from Hypixel's item list. */
	private static volatile Map<String, JsonArray> starCosts = Map.of();
	private static final List<Runnable> AFTER_ITEMS_LOADED = new ArrayList<>();
	private static volatile boolean itemsLoaded;

	private record RepoItem(String id, String name, Item item, @Nullable String texture, @Nullable String itemModel, @Nullable String tier) {}

	/** A SkyBlock player head, without creating an item stack. */
	public record Head(String id, String name, String texture) {}

	/** Repo loads run here (not the shared common pool), at low priority so they don't compete with the first frames. */
	private static final java.util.concurrent.ExecutorService REPO_POOL = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
		Thread thread = new Thread(r, "Sky2M repo load");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	/** Item icon downloads (many small images) get their own threads so they can't hold up the data loads. */
	private static final java.util.concurrent.ExecutorService ICON_POOL = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
		Thread thread = new Thread(r, "Sky2M icon load");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY + 1);
		return thread;
	});

	private RepoItems() {}

	/** Like {@link #runAsync}, for item icon downloads. */
	public static void runIconAsync(Runnable runnable) {
		CompletableFuture.runAsync(runnable, ICON_POOL).exceptionally(e -> {
			LOGGER.error("[Sky2M] Icon task failed", e);
			return null;
		});
	}

	public static void init() {
		// The item list is downloaded once, by PriceService, and shared with the price features.
		com.epic60869.sky2m.PriceService.onItems(RepoItems::loadItems);
	}

	public static void runAsync(Runnable runnable) {
		CompletableFuture.runAsync(runnable, REPO_POOL).exceptionally(e -> {
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

	/**
	 * Like {@link #idByName}, ignoring symbols: the repo's gemstone names start with a resource pack glyph
	 * (" Rough Ruby Gemstone") that Hypixel's sack messages don't have.
	 */
	public static @Nullable String idByLooseName(String name) {
		String exact = idByName(name);
		if (exact != null) return exact;
		String wanted = loose(name);
		if (wanted.isEmpty()) return null;
		synchronized (ITEMS) {
			for (RepoItem item : ITEMS.values()) {
				if (item.name() != null && loose(item.name()).equals(wanted)) return item.id();
			}
		}
		return null;
	}

	private static String loose(String name) {
		String plain = net.minecraft.ChatFormatting.stripFormatting(name);
		return plain == null ? "" : plain.replaceAll("[^\\p{L}\\p{N}' -]", "").replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
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

	/**
	 * What each star costs, star by star ({@code [{"type":"ESSENCE","essence_type":"CRIMSON","amount":30}, ...]}), or null
	 * when the item has no stars of its own (a fishing rod, say).
	 */
	public static @Nullable JsonArray starCosts(String id) {
		return starCosts.get(id);
	}

	/** Whether the item can be starred with essence (a dungeon item). */
	public static boolean isStarable(String id) {
		return starable.contains(id);
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
		if (item != null) return createStack(item);
		ItemStack book = enchantedBook(id);
		return book != null ? book : Compat.barrier();
	}

	private static final java.util.regex.Pattern BOOK_ID = java.util.regex.Pattern.compile("^ENCHANTMENT_(.+)_(\\d+)$");

	/**
	 * Price ids name enchanted books "ENCHANTMENT_ULTIMATE_CHIMERA_1" (the profit trackers and /s2 session store drops
	 * that way), while the repo keys them "ULTIMATE_CHIMERA;1": look that up, else draw a plain enchanted book named
	 * after the enchant, so a book never shows as a barrier. Null when {@code id} isn't a book.
	 */
	private static ItemStack enchantedBook(String id) {
		if (id == null) return null;
		java.util.regex.Matcher m = BOOK_ID.matcher(id);
		if (!m.matches() && !id.equals("ENCHANTED_BOOK")) return null;
		if (m.matches()) {
			RepoItem item;
			synchronized (ITEMS) {
				item = ITEMS.get(m.group(1) + ";" + m.group(2));
			}
			if (item != null) return createStack(item);
		}
		ItemStack stack = new ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK);
		stack.set(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		if (m.matches()) {
			StringBuilder name = new StringBuilder();
			for (String word : m.group(1).replaceFirst("^ULTIMATE_", "").split("_")) {
				if (word.isEmpty()) continue;
				if (!name.isEmpty()) name.append(' ');
				name.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
			}
			stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				net.minecraft.network.chat.Component.literal(name + " " + m.group(2)));
		}
		return stack;
	}

	private static ItemStack createStack(RepoItem repoItem) {
		Identifier model = repoItem.itemModel() == null ? null : Identifier.tryParse(repoItem.itemModel());
		// Newer items have no vanilla material, only Hypixel's model: draw them as paper rather than a barrier.
		Item base = repoItem.item() == net.minecraft.world.item.Items.BARRIER && model != null ? net.minecraft.world.item.Items.PAPER : repoItem.item();
		ItemStack stack = repoItem.texture() != null ? Compat.createSkull(repoItem.texture()) : new ItemStack(base);
		// Newer items are paper with Hypixel's model on top; use the model when it's loaded (Hypixel's pack, or vanilla).
		if (model != null && repoItem.texture() == null && hasItemModel(model)) stack.set(DataComponents.ITEM_MODEL, model);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(repoItem.name()));
		CompoundTag tag = new CompoundTag();
		tag.putString("id", repoItem.id());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	private static void loadItems(Map<String, JsonObject> items) {
		try {
			Map<String, RepoItem> loaded = new LinkedHashMap<>();
			java.util.Set<String> loadedStarable = new java.util.HashSet<>();
			Map<String, JsonArray> loadedStarCosts = new java.util.HashMap<>();
			for (JsonObject item : items.values()) {
				if (!item.has("id")) continue;
				String id = item.get("id").getAsString();
				String name = item.has("name") ? item.get("name").getAsString() : id;
				String material = item.has("material") ? item.get("material").getAsString() : "";
				int durability = item.has("durability") ? item.get("durability").getAsInt() : 0;
				String texture = skinTexture(item.get("skin"));
				String itemModel = item.has("item_model") ? item.get("item_model").getAsString() : null;
				String tier = item.has("tier") ? item.get("tier").getAsString() : null;
				loaded.put(id, new RepoItem(id, name, material(material, durability), texture, itemModel, tier));
				if (item.has("upgrade_costs")) {
					loadedStarable.add(id);
					if (item.get("upgrade_costs").isJsonArray()) loadedStarCosts.put(id, item.getAsJsonArray("upgrade_costs"));
				}
			}
			starable = loadedStarable;
			starCosts = loadedStarCosts;
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

	/** Repo files are cached on disk (config/sky2m/repo-cache) and only re-checked (by ETag) after this long. */
	private static final long CACHE_FRESH_MS = 12L * 60 * 60 * 1000;
	private static final java.nio.file.Path CACHE_DIR = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("repo-cache");
	/** At most this many repo requests run at once, so a launch doesn't fire dozens of downloads together. */
	private static final java.util.concurrent.Semaphore FETCH_SLOTS = new java.util.concurrent.Semaphore(3);

	private static String cacheName(String url) {
		try {
			byte[] hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 10; i++) sb.append(String.format("%02x", hash[i]));
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(url.hashCode());
		}
	}

	private static String fetch(String url) throws IOException, InterruptedException {
		String name = cacheName(url);
		java.nio.file.Path body = CACHE_DIR.resolve(name + ".json");
		java.nio.file.Path tagFile = CACHE_DIR.resolve(name + ".etag");
		boolean cached = java.nio.file.Files.isRegularFile(body);
		if (cached) {
			try {
				long age = System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(body).toMillis();
				if (age >= 0 && age < CACHE_FRESH_MS) return java.nio.file.Files.readString(body);
			} catch (Exception ignored) {
				cached = false;
			}
		}
		FETCH_SLOTS.acquire();
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(15))
					.header("User-Agent", "Sky2M/1.0")
					.GET();
			if (cached) {
				try {
					if (java.nio.file.Files.isRegularFile(tagFile)) builder.header("If-None-Match", java.nio.file.Files.readString(tagFile).trim());
				} catch (Exception ignored) {}
			}
			HttpResponse<String> response;
			try {
				response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			} catch (IOException e) {
				if (cached) return java.nio.file.Files.readString(body); // offline: the saved copy still works
				throw e;
			}
			if (response.statusCode() == 304 && cached) {
				try {
					java.nio.file.Files.setLastModifiedTime(body, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
				} catch (Exception ignored) {}
				return java.nio.file.Files.readString(body);
			}
			if (response.statusCode() != 200) {
				if (cached) return java.nio.file.Files.readString(body);
				throw new IOException("HTTP " + response.statusCode() + " for " + url);
			}
			try {
				java.nio.file.Files.createDirectories(CACHE_DIR);
				java.nio.file.Files.writeString(body, response.body());
				java.util.Optional<String> tag = response.headers().firstValue("ETag");
				if (tag.isPresent()) java.nio.file.Files.writeString(tagFile, tag.get());
				else java.nio.file.Files.deleteIfExists(tagFile);
			} catch (Exception ignored) {}
			return response.body();
		} finally {
			FETCH_SLOTS.release();
		}
	}
}
