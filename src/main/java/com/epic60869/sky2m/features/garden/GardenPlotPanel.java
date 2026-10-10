package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import com.epic60869.sky2m.mixin.Sky2MScreenWidgetsInvoker;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * A map of your 25 Garden plots to the right of your inventory, like Skyblocker's Garden Plots widget: click a plot to
 * /plottp there. Plots with pests are red with their pest count, locked ones grey, and the one you're in outlined.
 * Plot names, locks and pests come from {@link PestFinder}.
 */
public final class GardenPlotPanel extends AbstractWidget {
    private static final int CELL = 20;
    private static final int TITLE = 12;
    private static final int BUTTON = 14;
    private static final int PADDING = 4;
    private static final int WIDTH = CELL * 5 + PADDING * 2;
    private static final int HEIGHT = TITLE + CELL * 5 + PADDING * 2 + BUTTON + 2;

    private final InventoryScreen screen;

    private GardenPlotPanel(InventoryScreen screen) {
        super(0, 0, WIDTH, HEIGHT, Component.literal("Garden Plots"));
        this.screen = screen;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen inventory) || client.player == null) return;
            FeatureConfigs.PestFinder c = PestFinder.config();
            if (c == null || !c.plotPanel || !Sky2MLocation.inGarden()) return;
            ((Sky2MScreenWidgetsInvoker) screen).sky2m$addWidget(new GardenPlotPanel(inventory));
        });
    }

    private void updatePosition() {
        Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
        setX(access.sky2m$getLeftPos() + access.sky2m$getImageWidth() + 4);
        setY(access.sky2m$getTopPos());
    }

    /** The plot under the mouse, or null. */
    private PestFinder.Plot plotAt(double mouseX, double mouseY) {
        int gx = (int) Math.floor((mouseX - getX() - PADDING) / CELL);
        int gy = (int) Math.floor((mouseY - getY() - PADDING - TITLE) / CELL);
        if (gx < 0 || gx >= 5 || gy < 0 || gy >= 5) return null;
        return PestFinder.PLOTS.get(gy * 5 + gx);
    }

    private boolean overInfestedButton(double mouseX, double mouseY) {
        int top = getY() + PADDING + TITLE + CELL * 5 + 2;
        return mouseX >= getX() + PADDING && mouseX < getX() + WIDTH - PADDING && mouseY >= top && mouseY < top + BUTTON;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        updatePosition();
        // No room to the right (a small window, or the recipe book pushed the inventory over).
        if (getX() + WIDTH > screen.width) {
            visible = false;
            return;
        }
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        g.fill(x, y, x + WIDTH, y + HEIGHT, 0xD0101010);
        g.outline(x, y, WIDTH, HEIGHT, 0xFF404040);
        g.text(font, "Plots", x + PADDING, y + PADDING, 0xFFFFFFFF, true);

        PestFinder.Plot current = PestFinder.getCurrentPlot();
        PestFinder.Plot hovered = isHovered() ? plotAt(mouseX, mouseY) : null;
        for (int i = 0; i < PestFinder.PLOTS.size(); i++) {
            PestFinder.Plot plot = PestFinder.PLOTS.get(i);
            int cx = x + PADDING + (i % 5) * CELL;
            int cy = y + PADDING + TITLE + (i / 5) * CELL;
            var data = plot.data();
            boolean infested = data.pests > 0 || data.isPestCountInaccurate;
            int colour;
            if (plot.isBarn()) colour = 0xFF6B4A2B;
            else if (data.locked) colour = 0xFF2A2A2A;
            else if (infested) colour = 0xFFAA2222;
            else colour = 0xFF2E5E2E;
            if (plot == hovered && !data.locked) colour = brighten(colour);
            g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, colour);
            if (plot == current) g.outline(cx, cy, CELL, CELL, 0xFFFFFFFF);

            String label = plot.isBarn() ? "B" : plot.name();
            float scale = Math.min(1f, (CELL - 4) / (float) Math.max(1, font.width(label)));
            g.pose().pushMatrix();
            g.pose().translate(cx + CELL / 2f, cy + (infested ? 3 : 6));
            g.pose().scale(scale, scale);
            g.text(font, label, -font.width(label) / 2, 0, data.locked ? 0xFF808080 : 0xFFFFFFFF, true);
            g.pose().popMatrix();
            if (infested && !plot.isBarn()) {
                String pests = data.isPestCountInaccurate ? "?" : "x" + data.pests;
                g.pose().pushMatrix();
                g.pose().translate(cx + CELL / 2f, cy + 11);
                g.pose().scale(0.75f, 0.75f);
                g.text(font, pests, -font.width(pests) / 2, 0, 0xFFFFDD55, true);
                g.pose().popMatrix();
            }
        }

        // Warp to the nearest plot with pests (/s2tpinfested).
        boolean anyInfested = !PestFinder.getInfestedPlots().isEmpty();
        int by = y + PADDING + TITLE + CELL * 5 + 2;
        boolean overButton = isHovered() && overInfestedButton(mouseX, mouseY);
        g.fill(x + PADDING, by, x + WIDTH - PADDING, by + BUTTON, !anyInfested ? 0xFF2A2A2A : overButton ? 0xFFCC4444 : 0xFF993333);
        String text = anyInfested ? "TP to Pests" : "No Pests";
        g.text(font, text, x + WIDTH / 2 - font.width(text) / 2, by + 3, anyInfested ? 0xFFFFFFFF : 0xFF808080, true);

        if (hovered != null) g.setComponentTooltipForNextFrame(font, tooltip(hovered, hovered == current), mouseX, mouseY);
        if (hovered != null && !hovered.data().locked || overButton && anyInfested) handleCursor(g);
    }

    private static List<Component> tooltip(PestFinder.Plot plot, boolean current) {
        var data = plot.data();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(plot.isBarn() ? "The Barn" : "Plot - " + plot.name()).withStyle(ChatFormatting.GREEN));
        if (data.locked) {
            lines.add(Component.literal("Locked").withStyle(ChatFormatting.RED));
            return lines;
        }
        if (data.isPestCountInaccurate) lines.add(Component.literal("Has pests (count unknown)").withStyle(ChatFormatting.RED));
        else if (data.pests > 0) lines.add(Component.literal("Pests: " + data.pests).withStyle(ChatFormatting.RED));
        if (data.uncleared) lines.add(Component.literal("Not cleaned up").withStyle(ChatFormatting.GRAY));
        if (current) lines.add(Component.literal("You're here").withStyle(ChatFormatting.AQUA));
        lines.add(Component.literal("Click to teleport!").withStyle(ChatFormatting.YELLOW));
        return lines;
    }

    private static int brighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 40);
        int b = Math.min(255, (argb & 0xFF) + 40);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    @Override
    public void onClick(MouseButtonEvent click, boolean doubled) {
        if (overInfestedButton(click.x(), click.y())) {
            if (!PestFinder.getInfestedPlots().isEmpty()) PestFinder.teleportNearestInfestedPlot();
            return;
        }
        PestFinder.Plot plot = plotAt(click.x(), click.y());
        if (plot == null || plot.data().locked) return;
        PestFinder.sendCommand("plottp " + plot.tpName());
    }

    /** Not reached with Tab, like the inventory buttons. */
    @Override
    public ComponentPath nextFocusPath(FocusNavigationEvent navigation) {
        return null;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
