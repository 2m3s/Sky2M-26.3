package com.epic60869.sky2m.features.sbc;

import net.fabricmc.loader.api.FabricLoader;

/** The installed Sky2M and Minecraft versions, as sent to the mod server. */
public final class SbcInfo {
    private SbcInfo() {}

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer("sky2m")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("dev");
    }

    public static String mcVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
    }
}
