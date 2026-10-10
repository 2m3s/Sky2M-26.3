// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.logging.LogUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;


public class SkyblockItemModels {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static void init() {
		RepoItems.runAsync(() -> {
			ItemModelsData data = loadItemModels();
			if (data == null) return;
			// Run this on the main thread
			CompletableFuture.runAsync(() -> updateItemModels(data), Minecraft.getInstance());
		});
	}

	/** Sky2M reads NEU's constants/resource_pack.json directly instead of through the NEU repo library. */
	private record ItemModelsData(List<String> getRemoved, Map<String, String> getRenamed) {}

	private static ItemModelsData loadItemModels() {
		try {
			JsonObject itemModels = JsonParser.parseString(RepoItems.neuRepoFile("constants/resource_pack.json")).getAsJsonObject().getAsJsonObject("item_models");
			List<String> removed = itemModels.getAsJsonArray("removed").asList().stream().map(e -> e.getAsString()).toList();
			Map<String, String> renamed = new java.util.HashMap<>();
			itemModels.getAsJsonObject("renamed").entrySet().forEach(e -> renamed.put(e.getKey(), e.getValue().getAsString()));
			return new ItemModelsData(removed, renamed);
		} catch (Exception e) {
			LOGGER.error("[Sky2M Skyblock Item Models] Failed to load item model data", e);
			return null;
		}
	}

	private static void updateItemModels(ItemModelsData itemModelsData) {
		CustomConfigManager.update(config -> {
			try {
				Iterator<Map.Entry<String, Identifier>> iterator = config.general.customItemModel.entrySet().iterator();

				while (iterator.hasNext()) {
					Map.Entry<String, Identifier> entry = iterator.next();
					Identifier itemModel = entry.getValue();
					String itemModelString = itemModel.toString();

					if (itemModel.getNamespace().equals("hypixel_skyblock")) {
						// Remove item models that no longer exist
						if (itemModelsData.getRemoved().contains(itemModelString)) {
							iterator.remove();
						}

						// Skip updating model name if possible
						if (!itemModelsData.getRenamed().containsKey(itemModelString)) {
							continue;
						}

						// Update old item models recursively with some protection against circular map references
						String currentModel = itemModelsData.getRenamed().get(itemModelString);
						Set<String> visited = new HashSet<>();
						visited.add(itemModelString);

						String nextModel = itemModelsData.getRenamed().get(currentModel);

						while (nextModel != null) {
							if (visited.contains(nextModel)) {
								// Circular reference so break loop
								break;
							}

							entry.setValue(Identifier.parse(nextModel));
							visited.add(nextModel);

							currentModel = nextModel;
							nextModel = itemModelsData.getRenamed().get(currentModel);
						}
					}
				}
			} catch (Exception e) {
				LOGGER.error("[Sky2M Skyblock Item Models] Failed to update item models", e);
			}
		});
	}
}
