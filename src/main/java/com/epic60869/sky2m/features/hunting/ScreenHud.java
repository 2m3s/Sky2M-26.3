package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Text HUDs that are also drawn over open menus (like SkyHanni's GuiOnTop and chest overlay renders), at their /s2 gui
 * position, where their lines can be clicked. While a menu is open the normal HUD should show nothing (see
 * {@link #inMenu()}), so the HUD isn't drawn twice (once blurred behind the menu).
 */
final class ScreenHud {
    private record Entry(String id, Supplier<List<Component>> lines, IntConsumer onClick) {}

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean installed;

    private ScreenHud() {}

    /** {@code onClick} gets the clicked line's index (or is null for HUDs that can't be clicked). */
    static void register(String id, Supplier<List<Component>> lines, IntConsumer onClick) {
        ENTRIES.add(new Entry(id, lines, onClick));
        if (installed) return;
        installed = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> render(graphics));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event.x(), event.y()));
        });
    }

    static boolean inMenu() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>;
    }

    private static List<Component> safeLines(Entry entry) {
        try {
            List<Component> lines = entry.lines().get();
            return lines == null ? List.of() : lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void render(GuiGraphicsExtractor graphics) {
        for (Entry entry : ENTRIES) {
            List<Component> lines = safeLines(entry);
            if (lines.isEmpty()) continue;
            int[] at = Sky2MHuds.screenPosition(entry.id(), lines);
            Sky2MHuds.Placement p = Sky2MHuds.placement(entry.id());
            Sky2MHuds.render(graphics, lines, at[0], at[1], p.scale, p.background);
        }
    }

    private static boolean click(double x, double y) {
        for (Entry entry : ENTRIES) {
            if (entry.onClick() == null) continue;
            List<Component> lines = safeLines(entry);
            if (lines.isEmpty()) continue;
            int[] at = Sky2MHuds.screenPosition(entry.id(), lines);
            float scale = Sky2MHuds.placement(entry.id()).scale;
            if (x < at[0] || x > at[0] + Sky2MHuds.width(lines, scale) || y < at[1] || y > at[1] + Sky2MHuds.height(lines, scale)) continue;
            int line = (int) ((y - at[1] - Sky2MHuds.PADDING * scale) / (Sky2MHuds.LINE_HEIGHT * scale));
            if (line >= 0 && line < lines.size()) entry.onClick().accept(line);
            return true;
        }
        return false;
    }
}
