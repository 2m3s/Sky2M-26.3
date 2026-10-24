package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.Sky2MConfig;
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
    private static final float[] BLUE = {0.2f, 0.3f, 1f};

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

        collector.submitOutlinedBox(new AABB(minX, 0, minZ, maxX, 256, maxZ), BLUE, 2f, true);
        double y = Math.floor(mc.player.getY());
        for (int level = -2; level <= 6; level += 2) {
            double yy = y + level;
            collector.submitLinesFromPoints(new Vec3[] {
                new Vec3(minX, yy, minZ), new Vec3(maxX, yy, minZ), new Vec3(maxX, yy, maxZ), new Vec3(minX, yy, maxZ), new Vec3(minX, yy, minZ)
            }, YELLOW, 1f, level == 0 ? 3f : 1.5f, true);
        }
    }

    private static int cell(double value) {
        return Math.max(0, Math.min(4, (int) Math.floor((value + 240) / PLOT_SIZE)));
    }
}
