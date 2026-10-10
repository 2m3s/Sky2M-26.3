// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import com.epic60869.sky2m.custom.util.Compat;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.slf4j.Logger;

import net.minecraft.util.ExtraCodecs;


public class RepoDyeColors {
	private static final Logger LOGGER = LogUtils.getLogger();

	public static final Map<String, Integer> STATIC_DYES = Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());
	public static final Map<String, List<Integer>> ANIMATED_DYES = Object2ObjectMaps.synchronize(new Object2ObjectOpenHashMap<>());
	public static void init() {
		RepoItems.runAsync(RepoDyeColors::loadDyes);
	}

	public static void loadDyes() {
		STATIC_DYES.clear();
		ANIMATED_DYES.clear();

		try {
			Dyes dyes = Dyes.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(RepoItems.neuRepoFile("constants/dyes.json"))).getOrThrow();
			STATIC_DYES.putAll(dyes.staticDyes);
			ANIMATED_DYES.putAll(dyes.animatedDyes);
			LOGGER.info("[Sky2M] Successfully loaded {} static dyes and {} animated dyes from repo.", STATIC_DYES.size(), ANIMATED_DYES.size());
		} catch (Exception ex) {
			LOGGER.info("[Sky2M] Failed to load dyes from repo", ex);
		}
	}

	private record Dyes(Object2ObjectMap<String, List<Integer>> animatedDyes, Map<String, Integer> staticDyes) {
		private static final Codec<Dyes> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Compat.object2ObjectMapCodec(Codec.STRING, ExtraCodecs.STRING_RGB_COLOR.listOf()).fieldOf("animated").forGetter(Dyes::animatedDyes),
				Codec.unboundedMap(Codec.STRING, ExtraCodecs.STRING_RGB_COLOR).fieldOf("static").forGetter(Dyes::staticDyes)
		).apply(instance, Dyes::new));
	}
}
