package com.epic60869.sky2m.features.dungeons.mapstyle;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.sim.livemap.MapViewSource;
import com.epic60869.sky2m.sim.roomsim.SimState;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry and dispatcher of the Dungeon Map styles (Dungeons > Dungeon Map > Map Style). The HUD hook
 * ({@code sb.skyblock.dungeon.DungeonMap}), the HUD editor box and the Dungeon Sim all go through here, so the sim
 * draws exactly the selected style and there is no separate sim map.
 *
 * <p>To add a style: a {@link DungeonMapStyle} value, a {@link MapStyleRenderer}, one {@code register} line below.
 */
public final class DungeonMapStyles {
    private static final Map<DungeonMapStyle, MapStyleRenderer> RENDERERS = new EnumMap<>(DungeonMapStyle.class);

    static {
        register(DungeonMapStyle.SKYBLOCKER, new SkyblockerMapStyle());
        register(DungeonMapStyle.STELLA, new StellaMapStyle());
        register(DungeonMapStyle.DEVONIAN, new DevonianMapStyle());
    }

    private DungeonMapStyles() {}

    public static void register(DungeonMapStyle style, MapStyleRenderer renderer) {
        RENDERERS.put(style, renderer);
    }

    private static FeatureConfigs.DungeonMap config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.map;
    }

    public static DungeonMapStyle selected() {
        FeatureConfigs.DungeonMap map = config();
        return map == null || map.style == null ? DungeonMapStyle.SKYBLOCKER : map.style;
    }

    public static MapStyleRenderer renderer() {
        return RENDERERS.getOrDefault(selected(), RENDERERS.get(DungeonMapStyle.SKYBLOCKER));
    }

    /**
     * Whether the room scanner / live map data must run for the Dungeon Map: Skyblocker's style reads the map item
     * directly in a run, every other style draws from the {@link MapView}.
     */
    public static boolean needsScan() {
        FeatureConfigs.DungeonMap map = config();
        return map != null && map.enabled && selected() != DungeonMapStyle.SKYBLOCKER;
    }

    /** The Dungeon Sim always draws the map; a run draws it when Dungeons > Dungeon Map is on. */
    public static boolean inSim() {
        return SimState.isActive();
    }

    public static int width() {
        return renderer().width();
    }

    public static int height() {
        return renderer().height();
    }

    /** Draws the selected style with its top-left at x, y; scale is the Dungeon Map scale. */
    public static void render(GuiGraphicsExtractor graphics, int x, int y, float scale) {
        MapStyleRenderer renderer = renderer();
        // Skyblocker's style in a run draws the real map item itself and needs no view.
        MapView view = selected() == DungeonMapStyle.SKYBLOCKER && !inSim() ? MapView.empty() : MapViewSource.current();
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        try {
            renderer.render(graphics, view, false);
        } finally {
            graphics.pose().popMatrix();
        }
    }
}
