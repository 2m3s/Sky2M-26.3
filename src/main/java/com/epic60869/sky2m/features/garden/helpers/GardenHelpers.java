package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Entry point of the Garden helpers (SkyHanni, LGPL-2.1, https://github.com/hannibal002/SkyHanni; Skyblocker's
 * Stereo Harmony helper, LGPL-3.0): composter overlay, Stereo Harmony / Atmospheric Filter displays, DNA Analyzer and
 * Phantomleaf solvers, Anita shop profit and the farming weight display. Each lives in its own class; this wires the
 * menu drawing and the ticks. None of it clicks for you.
 */
public final class GardenHelpers {
    private GardenHelpers() {}

    public static void init() {
        ComposterOverlay.init();
        StereoAndFilter.init();
        GreenhouseHelpers.init();
        AnitaShop.init();
        FarmingWeightHud.init();

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> drawMenus(s, graphics));
        });
        ClientTickEvents.END_CLIENT_TICK.register(GardenHelpers::tick);
    }

    private static int tickCount;

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        Screen screen = mc.gui.screen();
        if (screen instanceof AbstractContainerScreen<?> container && tickCount % 5 == 0) {
            String title = HelperUtil.title(screen);
            ComposterOverlay.update(container, title);
            StereoAndFilter.updateMenu(container, title);
            GreenhouseHelpers.updateMenu(container, title);
            AnitaShop.update(container, title);
        }
        tickCount++;
    }

    private static void drawMenus(Screen screen, GuiGraphicsExtractor graphics) {
        if (!(screen instanceof AbstractContainerScreen<?> container)) return;
        String title = HelperUtil.title(screen);
        ComposterOverlay.draw(container, title, graphics);
        StereoAndFilter.drawMenu(container, title, graphics);
        GreenhouseHelpers.drawMenu(container, title, graphics);
        AnitaShop.draw(title, graphics);
    }

    /** Fills a 16x16 slot with a colour (ARGB). */
    static void fillSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot, int argb) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        int x = accessor.getX() + slot.x, y = accessor.getY() + slot.y;
        graphics.fill(x, y, x + 16, y + 16, argb);
    }

    /** Outlines a slot. */
    static void outlineSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot, int argb) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        graphics.outline(accessor.getX() + slot.x - 1, accessor.getY() + slot.y - 1, 18, 18, argb);
    }

    /** The slot the mouse is over, or null. */
    static Slot hovered(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).getFocusedSlot();
    }

    /** How many slots belong to the open container (not the player's inventory). */
    static int containerSize(AbstractContainerScreen<?> screen) {
        return Math.max(0, screen.getMenu().slots.size() - 36);
    }
}
