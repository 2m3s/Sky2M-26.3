package com.epic60869.sky2m.sim.util;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Where the Dungeon Sim keeps its files: config/sky2m/sim/, under the name each one always had. */
public final class ModPaths {

    private ModPaths() {
    }

    public static Path root() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("sim");
    }

    public static Path config(String name) {
        Path target = root().resolve(name.startsWith("sky2m-") ? name.substring("sky2m-".length()) : name);
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
        } catch (Exception ignored) {
            // the caller's own read or write reports it
        }
        return target;
    }
}
