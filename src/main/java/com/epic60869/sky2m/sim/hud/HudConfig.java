package com.epic60869.sky2m.sim.hud;

/** The sim's HUD elements sit at their default spots and scales (Sky2M's own HUD editor moves Sky2M's HUDs). */
public final class HudConfig {

    private static final HudConfig INSTANCE = new HudConfig();

    private HudConfig() {
    }

    public static HudConfig getInstance() {
        return INSTANCE;
    }

    public boolean hasPosition(String id) {
        return false;
    }

    public int[] getPosition(String id, int x, int y) {
        return new int[]{x, y};
    }

    public float getScale(String id, float fallback) {
        return fallback;
    }

    public float getEffectiveGlobalScale() {
        return 1.0f;
    }
}
