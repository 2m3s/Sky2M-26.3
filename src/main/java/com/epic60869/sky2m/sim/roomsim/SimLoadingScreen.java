/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.gui.profit.ProfitPanels;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.epic60869.sky2m.sim.compat.McCompat;

/**
 * The sim's own loading screen, held up until the map is actually built.
 *
 * <p>killer560 (2026-09-28): "no room ever loaded. Make sure the room loads before I am actually put into the
 * workd. And make it a custom loading screen."
 *
 * <p>The first half was a real bug. Picking a room from the main menu called into the builder while there was
 * still no world, which took a branch that opened an empty sim and printed "run /simbuild room again once you
 * are in" - so the room was never placed and the message asking him to finish the job himself scrolled past in
 * chat. Building is now queued and runs the moment the world exists.
 *
 * <p>The second half is what makes that honest. A big room is hundreds of thousands of blocks, and the world
 * arrives before any of them are placed, so without this he would be standing in an empty flat world watching a
 * dungeon appear around him. This screen covers that gap: it goes up as the world opens and comes down when the
 * build reports finished, so the first thing he sees is the finished room.
 *
 * <p>It cannot be dismissed with Escape on purpose. Escaping out of it would drop him into exactly the
 * half-built world it exists to hide, which is the confusing state, not a useful escape hatch.
 */
public final class SimLoadingScreen extends Screen {

    private String what;
    private String detail = "";

    public SimLoadingScreen(String what) {
        super(Component.literal("Dungeon Sim"));
        this.what = what == null ? "" : what;
    }

    /** Updates the line under the title - the room being placed, or the block count as it lands. */
    public void progress(String line) {
        this.detail = line == null ? "" : line;
    }

    public void setWhat(String line) {
        this.what = line == null ? "" : line;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        // Pausing would stop the integrated server, which is the thread doing the building.
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // Opaque under the frame's dimmed backdrop: the world behind is the one being built.
        g.fill(0, 0, this.width, this.height, 0xFF101010);

        int panelW = Math.min(this.width - 40, 420);
        int panelH = 110;
        int panelX = (this.width - panelW) / 2;
        int panelY = (this.height - panelH) / 2;
        // The sim's shared frame (ProfitPanels.frame), like its other screens.
        ProfitPanels.frame(g, this.font, this.width, this.height, panelX, panelY, panelW, panelH, 26, "Loading");

        if (!what.isEmpty()) {
            g.centeredText(this.font, this.font.plainSubstrByWidth(what, panelW - 20), panelX + panelW / 2,
                    panelY + 36, ProfitPanels.TEXT);
        }
        if (!detail.isEmpty()) {
            g.centeredText(this.font, this.font.plainSubstrByWidth(detail, panelW - 20), panelX + panelW / 2,
                    panelY + 50, ProfitPanels.DIM);
        }

        // A real bar. killer560 (2026-09-28): "instead of the moving bar at the bottom [...] make an acttual
        // progress bar." The earlier sweep was there because I thought the builder could not say how far along
        // it was without counting the work twice - it can, because every job knows the size of its own box
        // before it starts. Measured in positions VISITED rather than blocks written: a room is mostly
        // uncaptured air that gets skipped, so a write-based bar would stall on sparse rooms and race through
        // dense ones, which is worse than no bar because it looks like it has hung.
        int barX = panelX + 20;
        int barW = panelW - 40;
        int barY = panelY + panelH - 30;
        g.fill(barX, barY, barX + barW, barY + 6, ProfitPanels.INNER_BG);
        float progress = SimBuildQueue.progress();
        int filled = Math.max(0, Math.min(barW, Math.round(barW * progress)));
        if (filled > 0) {
            g.fill(barX, barY, barX + filled, barY + 6, ProfitPanels.ACCENT);
        }
        String pct = Math.round(progress * 100) + "%";
        g.centeredText(this.font, pct, panelX + panelW / 2, barY + 10, ProfitPanels.DIM);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Puts this screen up, replacing whatever is there. */
    public static SimLoadingScreen show(Minecraft client, String what) {
        SimLoadingScreen screen = new SimLoadingScreen(what);
        McCompat.setScreen(client, screen);
        return screen;
    }

    /** Takes it down, but only if it is still the screen showing - never closes something else. */
    public static void dismiss(Minecraft client) {
        if (McCompat.screen(client) instanceof SimLoadingScreen) {
            McCompat.setScreen(client, null);
        }
    }
}
