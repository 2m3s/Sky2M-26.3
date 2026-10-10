// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.skyblock.waypoint;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import com.epic60869.sky2m.custom.RepoItems;

/** Fairy soul counts per island, read from NEU's constants/fairy_souls.json (Skyblocker's source). */
public final class FairySouls {
	private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();
	private static volatile int maxSouls;
	private static final CompletableFuture<Void> LOADED = new CompletableFuture<>();

	private FairySouls() {}

	public static void init() {
		RepoItems.runAsync(() -> {
			try {
				JsonObject root = JsonParser.parseString(RepoItems.neuRepoFile("constants/fairy_souls.json")).getAsJsonObject();
				for (var entry : root.entrySet()) {
					if (entry.getKey().equals("Max Souls")) maxSouls = entry.getValue().getAsInt();
					else if (entry.getValue().isJsonArray()) COUNTS.put(entry.getKey(), entry.getValue().getAsJsonArray().size());
				}
			} catch (Exception e) {
				System.err.println("[Sky2M] Failed to load fairy souls: " + e.getMessage());
			}
			LOADED.complete(null);
		});
	}

	public static void runAsyncAfterFairySoulsLoad(Runnable runnable) {
		LOADED.thenRun(runnable);
	}

	public static int getFairySoulsSize(@Nullable String location) {
		return location == null ? maxSouls : COUNTS.getOrDefault(location, 0);
	}
}
