package com.epic60869.sky2m.features.dungeons.mapstyle;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * One dungeon map look. A renderer draws from the neutral {@link MapView} only (it is built from the Hypixel map item
 * and the room scanner in a run, and from the Dungeon Sim's published floor in the sim), so a style works in both.
 *
 * <p>The caller has already translated to the map's top-left and applied the Dungeon Map scale; draw in the
 * {@link #width()} x {@link #height()} box starting at 0, 0. To add a style: add a value to {@link DungeonMapStyle},
 * implement this, and register it in {@link DungeonMapStyles}.
 */
public interface MapStyleRenderer {
    String id();

    /** Unscaled box width in GUI pixels (the HUD editor box and the position clamp use it). */
    int width();

    /** Unscaled box height in GUI pixels, including anything drawn under the map. */
    int height();

    /** @param preview true in the HUD editor / when no run is going (view may be empty). */
    void render(GuiGraphicsExtractor g, MapView view, boolean preview);
}
