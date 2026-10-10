// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import com.epic60869.sky2m.custom.util.Compat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.slf4j.Logger;

import net.minecraft.world.item.component.ResolvableProfile;


/**
 * Caches generated ProfileComponents for custom player head textures.
 */
public class CustomHelmetTextures {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final List<NamedTexture> TEXTURES = new ArrayList<>();
	public static final Object2ObjectOpenHashMap<String, ResolvableProfile> PROFILE_CACHE = new Object2ObjectOpenHashMap<>();
	private static final Pattern LEVEL_PATTERN = Pattern.compile("\\[Lvl[^\\]]*\\]");
	public static void init() {
		RepoItems.runAfterItemsLoaded(CustomHelmetTextures::loadTextures);
	}

	private static void loadTextures() {
		try {
			if (!RepoItems.itemsLoaded()) return;

			TEXTURES.clear();
			ObjectSet<String> seen = new ObjectOpenHashSet<>();
			// Sky2M reads head textures from the item data directly: item stacks cannot be
			// created until Minecraft binds item components, which happens after this runs.
			for (RepoItems.Head head : RepoItems.heads()) {
				if (head.texture().isEmpty() || !seen.add(head.texture())) continue;
				TEXTURES.add(new NamedTexture(cleanName(head.name()), head.texture(), head.id()));
			}

			TEXTURES.sort(Comparator.comparing(NamedTexture::internalName));
			LOGGER.info("[Sky2M] Loaded and sorted {} helmet textures from repo", TEXTURES.size());
		} catch (Exception e) {
			LOGGER.error("[Sky2M] Failed to load helmet textures from repo", e);
		}
	}

	private static String cleanName(String name) {
		return LEVEL_PATTERN.matcher(name).replaceAll("").trim();
	}

	public static List<NamedTexture> getTextures() {
		return TEXTURES;
	}

	private static final Object2IntOpenHashMap<String> ATTEMPTS = new Object2IntOpenHashMap<>();
	private static final Object2LongOpenHashMap<String> LAST_RETRY = new Object2LongOpenHashMap<>();
	private static final long RETRY_MS = 5000;
	private static final int MAX_ATTEMPTS = 5;

	public static ResolvableProfile getProfile(String texture) {
		ResolvableProfile profile = PROFILE_CACHE.computeIfAbsent(texture, (String t) -> create(t, 0));
		// Minecraft remembers a skin that failed to load for 5 minutes. If this one failed,
		// make a new profile (a new cache key) after a few seconds so it is fetched again.
		if (skinFailed(profile) && ATTEMPTS.getInt(texture) < MAX_ATTEMPTS
				&& System.currentTimeMillis() - LAST_RETRY.getLong(texture) > RETRY_MS) {
			int attempt = ATTEMPTS.getInt(texture) + 1;
			ATTEMPTS.put(texture, attempt);
			LAST_RETRY.put(texture, System.currentTimeMillis());
			profile = create(texture, attempt);
			PROFILE_CACHE.put(texture, profile);
		}
		return profile;
	}

	private static ResolvableProfile create(String texture, int attempt) {
		String key = attempt == 0 ? texture : texture + "#" + attempt;
		return ResolvableProfile.createResolved(new GameProfile(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
				"custom", Compat.propertyMapWithTexture(texture)));
	}

	/** Whether the skin for this profile finished loading without a texture. */
	public static boolean skinFailed(ResolvableProfile profile) {
		try {
			var result = net.minecraft.client.Minecraft.getInstance().playerSkinRenderCache().lookup(profile).getNow(null);
			return result != null && result.isEmpty();
		} catch (Exception e) {
			return false;
		}
	}

	/** Whether the skin for this profile has finished loading. */
	public static boolean skinReady(ResolvableProfile profile) {
		try {
			var result = net.minecraft.client.Minecraft.getInstance().playerSkinRenderCache().lookup(profile).getNow(null);
			return result != null && result.isPresent();
		} catch (Exception e) {
			return false;
		}
	}

	public record NamedTexture(String name, String texture, String internalName) {}
}
