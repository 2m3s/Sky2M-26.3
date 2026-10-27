package com.epic60869.sky2m.features.dungeons.bridge;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;

/** The Cross-Mod Bridge's switches, read from Sky2M's settings (Dungeons > Cross-Mod Bridge). */
public final class BridgeConfig {
    private static final BridgeConfig INSTANCE = new BridgeConfig();

    private BridgeConfig() {}

    public static BridgeConfig getInstance() {
        return INSTANCE;
    }

    private static FeatureConfigs.CrossModBridge c() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.dungeons.crossModBridge;
    }

    public boolean isEnabled() {
        FeatureConfigs.CrossModBridge c = c();
        return c != null && c.enabled;
    }

    public boolean isDevonian() {
        FeatureConfigs.CrossModBridge c = c();
        return c != null && c.devonian;
    }

    public boolean isNoamm() {
        FeatureConfigs.CrossModBridge c = c();
        return c != null && c.noamm;
    }

    public boolean isOdin() {
        FeatureConfigs.CrossModBridge c = c();
        return c != null && c.odin;
    }
}
