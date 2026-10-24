package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MKeyMappings;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Radial menu, after Skyblocker's radial menus (https://github.com/SkyblockerMod/Skyblocker, LGPL-3.0) and built on
 * Sky2M's command buttons idea: hold the Radial Menu key to open a ring of up to 8 configurable commands, point the
 * mouse at one and release the key to run it. Releasing in the middle, or pressing Escape, runs nothing.
 */
public final class RadialMenu {
    private static KeyMapping key;
    /** Set once the wheel has opened for the current key press, so it only opens again after the key was let go. */
    private static boolean latch;

    private RadialMenu() {}

    public record Slice(String name, String command) {}

    public static void init() {
        key = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.sky2m.radial_menu", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), Sky2MKeyMappings.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(RadialMenu::tick);
    }

    private static QolConfigs.RadialMenu config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.gui.radialMenu;
    }

    private static void tick(Minecraft mc) {
        if (key == null) return;
        if (!key.isDown()) {
            latch = false;
            return;
        }
        QolConfigs.RadialMenu c = config();
        if (latch || c == null || !c.enabled || mc.gui.screen() != null || mc.player == null || !Sky2MLocation.onSkyblock()) return;
        List<Slice> slices = slices(c);
        if (slices.isEmpty()) return;
        latch = true;
        mc.gui.setScreen(new RadialScreen(slices, key));
    }

    private static List<Slice> slices(QolConfigs.RadialMenu c) {
        List<Slice> out = new ArrayList<>();
        for (String raw : new String[] {c.slice1, c.slice2, c.slice3, c.slice4, c.slice5, c.slice6, c.slice7, c.slice8}) {
            if (raw == null || raw.isBlank()) continue;
            int bar = raw.indexOf('|');
            String command = (bar < 0 ? raw : raw.substring(bar + 1)).trim();
            String name = bar < 0 ? command : raw.substring(0, bar).trim();
            if (command.isEmpty() || command.equals("/")) continue;
            out.add(new Slice(name.isEmpty() ? command : name, command));
        }
        return out;
    }

    private static final class RadialScreen extends Screen {
        private static final int RADIUS = 70;
        private static final int BOX_W = 84;
        private static final int BOX_H = 20;
        private static final int DEAD_ZONE = 18;

        private final List<Slice> slices;
        private final KeyMapping holdKey;
        private int hovered = -1;

        RadialScreen(List<Slice> slices, KeyMapping holdKey) {
            super(Component.literal("Radial Menu"));
            this.slices = slices;
            this.holdKey = holdKey;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        private int[] centre(int i) {
            double angle = Math.toRadians(-90 + i * 360.0 / slices.size());
            return new int[] {width / 2 + (int) Math.round(Math.cos(angle) * RADIUS), height / 2 + (int) Math.round(Math.sin(angle) * RADIUS)};
        }

        private int sliceAt(double mouseX, double mouseY) {
            double dx = mouseX - width / 2.0, dy = mouseY - height / 2.0;
            if (Math.hypot(dx, dy) < DEAD_ZONE) return -1;
            double degrees = Math.toDegrees(Math.atan2(dy, dx)) + 90;
            double step = 360.0 / slices.size();
            int index = (int) Math.round(((degrees % 360) + 360) % 360 / step);
            return index % slices.size();
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
            hovered = sliceAt(mouseX, mouseY);
            g.fill(0, 0, width, height, 0x60000000);
            int cx = width / 2, cy = height / 2;
            g.fill(cx - 3, cy - 3, cx + 3, cy + 3, 0xFFFFFFFF);
            for (int i = 0; i < slices.size(); i++) {
                int[] c = centre(i);
                int x = c[0] - BOX_W / 2, y = c[1] - BOX_H / 2;
                boolean on = i == hovered;
                g.fill(x, y, x + BOX_W, y + BOX_H, on ? 0xE0FFAA00 : 0xC0101010);
                g.outline(x, y, BOX_W, BOX_H, on ? 0xFFFFFFFF : 0xFF606060);
                g.centeredText(font, slices.get(i).name(), c[0], c[1] - 4, 0xFFFFFFFF);
            }
        }

        private void release() {
            int chosen = hovered;
            onClose();
            if (chosen < 0 || chosen >= slices.size() || minecraft.player == null) return;
            String command = slices.get(chosen).command();
            if (command.startsWith("/")) minecraft.player.connection.sendCommand(command.substring(1));
            else minecraft.player.connection.sendChat(command);
        }

        @Override
        public boolean keyReleased(KeyEvent event) {
            if (holdKey.matches(event)) {
                release();
                return true;
            }
            return super.keyReleased(event);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (holdKey.matchesMouse(event)) {
                release();
                return true;
            }
            return super.mouseReleased(event);
        }
    }
}
