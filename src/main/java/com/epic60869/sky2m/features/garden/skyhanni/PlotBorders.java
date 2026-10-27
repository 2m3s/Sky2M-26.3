package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * SkyHanni's Garden Plot Borders (https://github.com/hannibal002/SkyHanni, LGPL-2.1: GardenPlotBorders,
 * SprayFeatures): a key toggles the border of the plot you are in (or the closest one), and with the option on it
 * shows while you hold a Sprayonator. The plots are the 5x5 grid of 96x96 blocks around the Garden's middle.
 */
public final class PlotBorders {
    private static final double PLOT_SIZE = 96.0;
    private static final float[] YELLOW = {1f, 1f, 0.2f};

    private static boolean show;
    private static boolean keyWasDown;

    private PlotBorders() {}

    private static SkyHanniGardenConfig.Plots config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.skyhanniGarden.plots;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            SkyHanniGardenConfig.Plots c = config();
            if (c == null || mc.player == null || !Sky2MLocation.inGarden()) {
                keyWasDown = false;
                return;
            }
            boolean down = c.bordersKey > 0 && mc.gui.screen() == null && com.epic60869.sky2m.custom.util.Input.keyDown(c.bordersKey);
            if (down && !keyWasDown) show = !show;
            keyWasDown = down;
        });
        Sky2MWorldRender.register(PlotBorders::render);
    }

    private static boolean sprayonatorInHand(Minecraft mc) {
        String id = Compat.neuName(mc.player.getMainHandItem());
        return id != null && id.contains("SPRAYONATOR");
    }

    private static void render(PrimitiveCollector collector) {
        SkyHanniGardenConfig.Plots c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || mc.player == null || !Sky2MLocation.inGarden()) return;
        boolean wanted = show || (c.sprayonatorBorders && sprayonatorInHand(mc));
        if (!wanted) return;

        // The grid cell (0..4) of the player, clamped so the closest plot shows outside the Garden's edge too.
        int cx = cell(mc.player.getX());
        int cz = cell(mc.player.getZ());
        double minX = -240 + cx * PLOT_SIZE;
        double minZ = -240 + cz * PLOT_SIZE;
        double maxX = minX + PLOT_SIZE;
        double maxZ = minZ + PLOT_SIZE;

        drawWall(collector, minX, minZ, maxX, maxZ, Math.floor(mc.player.getY()), YELLOW);
    }

    /** How tall the border walls are, from the ground up. */
    private static final double WALL_HEIGHT = 3;
    private static final double WALL_THICKNESS = 0.06;

    /**
     * A plot border: a see-through wall {@link #WALL_HEIGHT} blocks tall along the plot's edge, from the ground at
     * {@code y}, with a solid line along the bottom and a fainter one along the top. Drawn through blocks so it reads
     * across the crops. Also used by Pest Plot Borders.
     */
    public static void drawWall(PrimitiveCollector collector, double minX, double minZ, double maxX, double maxZ, double y, float[] colour) {
        double top = y + WALL_HEIGHT;
        double t = WALL_THICKNESS;
        collector.submitFilledBox(new AABB(minX, y, minZ - t, maxX, top, minZ + t), colour, 0.18f, true);
        collector.submitFilledBox(new AABB(minX, y, maxZ - t, maxX, top, maxZ + t), colour, 0.18f, true);
        collector.submitFilledBox(new AABB(minX - t, y, minZ, minX + t, top, maxZ), colour, 0.18f, true);
        collector.submitFilledBox(new AABB(maxX - t, y, minZ, maxX + t, top, maxZ), colour, 0.18f, true);
        collector.submitLinesFromPoints(ring(minX, minZ, maxX, maxZ, y), colour, 1f, 3f, true);
        collector.submitLinesFromPoints(ring(minX, minZ, maxX, maxZ, top), colour, 0.6f, 1.5f, true);
    }

    private static Vec3[] ring(double minX, double minZ, double maxX, double maxZ, double y) {
        return new Vec3[] {new Vec3(minX, y, minZ), new Vec3(maxX, y, minZ), new Vec3(maxX, y, maxZ), new Vec3(minX, y, maxZ), new Vec3(minX, y, minZ)};
    }

    private static int cell(double value) {
        return Math.max(0, Math.min(4, (int) Math.floor((value + 240) / PLOT_SIZE)));
    }
}
