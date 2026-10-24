package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Text HUDs that are drawn on top of container screens (the HUD layer is not drawn behind them), placed with the same
 * /s2 gui position and scale as other HUDs. Same idea as the Hunting category's ScreenHud.
 */
final class ScreenHuds {
    private record Entry(String id, Supplier<List<Component>> lines) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean installed;

    private ScreenHuds() {}

    static void register(String id, Supplier<List<Component>> lines) {
        ENTRIES.add(new Entry(id, lines));
        if (installed) return;
        installed = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> render(graphics));
        });
    }

    private static void render(GuiGraphicsExtractor graphics) {
        for (Entry entry : ENTRIES) {
            List<Component> lines;
            try {
                lines = entry.lines().get();
            } catch (Exception e) {
                continue;
            }
            if (lines == null || lines.isEmpty()) continue;
            int[] at = Sky2MHuds.screenPosition(entry.id(), lines);
            Sky2MHuds.Placement p = Sky2MHuds.placement(entry.id());
            Sky2MHuds.render(graphics, lines, at[0], at[1], p.scale, p.background);
        }
    }
}
