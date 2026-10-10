// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

public final class SkyblockerMod {
	public static final String NAMESPACE = "sky2m";
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final Gson GSON_COMPACT = new GsonBuilder().create();
	public static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("sky2m");
	public static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

	private SkyblockerMod() {}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(NAMESPACE, path);
	}
}
