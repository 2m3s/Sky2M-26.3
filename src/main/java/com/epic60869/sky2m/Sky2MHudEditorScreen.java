package com.epic60869.sky2m;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

public final class Sky2MHudEditorScreen extends Screen {
    /** GLFW key code for the keypad minus key. */
    private static final int KEYPAD_MINUS = 333;
    private final Screen parent;
    private final List<EditableHud> elements = new ArrayList<>();
    private EditableHud selected;
    private int dragOffsetX;
    private int dragOffsetY;

    public Sky2MHudEditorScreen(Screen parent) {
        super(Component.literal("Sky2M Position Editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width - 90, 16, 70, 24).build());
        rebuildElements();
    }

    private void rebuildElements() {
        elements.clear();
        // Only HUDs whose feature is turned on; turn one on in /s2 to place it.
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null && config.farming.rng.enabled) {
            elements.add(new EditableHud("RNG HUD", Sky2MRngHud::x, Sky2MRngHud::y,
                Sky2MRngHud::setPosition, Sky2MRngHud::width, Sky2MRngHud::height,
                Sky2MRngHud::renderPreview, "rng"));
        }
        if (config != null && config.mining.commissions.enabled) {
            elements.add(new EditableHud("Mining Commissions", Sky2MCommissionHud::x, Sky2MCommissionHud::y,
                Sky2MCommissionHud::setPosition, Sky2MCommissionHud::width, Sky2MCommissionHud::height,
                Sky2MCommissionHud::renderPreview, "commissions"));
        }

        if (config != null && config.misc.pets.display.enabled) {
            elements.add(new EditableHud("Pet Display", () -> config.misc.pets.display.x, () -> config.misc.pets.display.y,
                (x, y) -> { config.misc.pets.display.x = Math.max(0, x); config.misc.pets.display.y = Math.max(0, y); },
                Sky2MNopoFeatures::petHudWidth, Sky2MNopoFeatures::petHudHeight, Sky2MNopoFeatures::renderPetHudPreview, "pet"));
        }
        if (config != null && config.dungeons.map.enabled) {
            var map = config.dungeons.map;
            elements.add(new EditableHud("Dungeon Map", () -> map.x, () -> map.y,
                (x, y) -> { map.x = Math.max(0, x); map.y = Math.max(0, y); },
                () -> Math.round(128 * map.scale), () -> Math.round(128 * map.scale),
                (g, x, y) -> {
                    int size = Math.round(128 * map.scale);
                    g.fill(x, y, x + size, y + size, 0x80202020);
                    g.centeredText(font, Component.literal("Dungeon Map"), x + size / 2, y + size / 2 - 4, 0xFFFFFFFF);
                }, "dmap"));
        }

        for (var hud : com.epic60869.sky2m.features.core.Sky2MHuds.elements()) {
            // Only HUDs whose feature is turned on; turn one on in /s2 to place it.
            if (!com.epic60869.sky2m.features.core.Sky2MHuds.turnedOn(hud)) continue;
            var placement = com.epic60869.sky2m.features.core.Sky2MHuds.placement(hud.id());
            elements.add(new EditableHud(hud.name(), () -> placement.x, () -> placement.y,
                (x, y) -> { placement.x = Math.max(0, x); placement.y = Math.max(0, y); },
                () -> com.epic60869.sky2m.features.core.Sky2MHuds.editorWidth(hud),
                () -> com.epic60869.sky2m.features.core.Sky2MHuds.editorHeight(hud),
                (g, x, y) -> {
                    if (hud.custom() != null) com.epic60869.sky2m.features.core.Sky2MHuds.renderCustom(g, hud, true);
                    else com.epic60869.sky2m.features.core.Sky2MHuds.render(g, com.epic60869.sky2m.features.core.Sky2MHuds.editorLines(hud), x, y, placement.scale, placement.background);
                }, "hud:" + hud.id()));
        }
        // Move every HUD to where it is drawn on this screen (GUI scale / window size may have changed since it
        // was placed), pulling anything off screen back into view so it can be grabbed.
        for (EditableHud e : elements) e.setClamped(com.epic60869.sky2m.features.core.Sky2MHuds.mapX(e.x(), e.width()), com.epic60869.sky2m.features.core.Sky2MHuds.mapY(e.y(), e.height()));
        com.epic60869.sky2m.features.core.Sky2MHuds.setReferenceToScreen();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0x00000000);
        g.fill(width / 2 - 1, 0, width / 2 + 1, height, 0x18FFFFFF);
        g.fill(0, height / 2 - 1, width, height / 2 + 1, 0x18FFFFFF);

        g.text(font, Component.literal("Sky2M Position Editor"), 18, 18, 0xFFFFFFFF, true);
        g.text(font, Component.literal("Drag to move • Scroll to resize • Right-click opens its settings • Middle-click toggles background • Arrow keys move"), 18, 36, 0xFFB8BEC9, false);
        if (elements.isEmpty()) {
            g.centeredText(font, Component.literal("No HUDs are turned on. Turn one on in /s2 to place it here."), width / 2, height / 2 - 20, 0xFFB8BEC9);
        }

        EditableHud hovered = null;
        for (int i = elements.size() - 1; i >= 0; i--) {
            EditableHud e = elements.get(i);
            int x = e.x(), y = e.y(), w = Math.max(1, e.width()), h = Math.max(1, e.height());
            boolean hover = inside(mouseX, mouseY, x, y, w, h);
            if (hover && hovered == null) hovered = e;

            // The box is exactly the HUD's drawn bounds, so what you see is what snaps to the edges.
            int border = e == selected ? 0xFFE8EAED : (hover ? 0xFF8F98A6 : 0x664B515B);
            int fill = e == selected ? 0x28FFFFFF : (hover ? 0x18FFFFFF : 0x10000000);
            g.fill(x, y, x + w, y + h, fill);
            e.preview().render(g, x, y);
            g.fill(x, y, x + w, y + 1, border);
            g.fill(x, y + h - 1, x + w, y + h, border);
            g.fill(x, y, x + 1, y + h, border);
            g.fill(x + w - 1, y, x + w, y + h, border);

            if (e == selected || hover) {
                int labelY = y >= 12 ? y - 11 : y + h + 2;
                int labelX = Math.min(x, width - font.width(e.name()) - 2);
                g.text(font, Component.literal(e.name()), labelX, labelY, 0xFFFFFFFF, true);
            }
        }

        if (hovered != null) {
            int tx = Math.min(mouseX + 12, width - 235);
            int ty = Math.min(mouseY + 12, height - 96);
            g.fill(tx, ty, tx + 225, ty + 94, 0xF0101115);
            g.text(font, Component.literal(hovered.name()), tx + 8, ty + 8, 0xFFFFFFFF, true);
            g.text(font, Component.literal("x: " + hovered.x() + ", y: " + hovered.y()), tx + 8, ty + 23, 0xFFD0D5DC, false);
            g.text(font, Component.literal("Left-click + drag to move"), tx + 8, ty + 37, 0xFF9EA5B1, false);
            g.text(font, Component.literal("Scroll to resize"), tx + 8, ty + 51, 0xFF9EA5B1, false);
            g.text(font, Component.literal("Right-click to open its settings"), tx + 8, ty + 65, 0xFF9EA5B1, false);
            g.text(font, Component.literal("Middle-click: background " + (hovered.background() ? "ON" : "OFF")), tx + 8, ty + 79, 0xFF9EA5B1, false);
        } else if (selected == null) {
            g.text(font, Component.literal("Sky2M Position Editor"), 18, height - 42, 0xFF9097A3, true);
            g.text(font, Component.literal("Select a HUD element to edit its position and scale."), 18, height - 26, 0xFF707783, false);
        }

        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        boolean right = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;
        boolean middle = event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_MIDDLE;
        if (right || middle) {
            EditableHud hovered = findHovered(mx, my);
            if (hovered != null) {
                // Like SkyHanni's GUI editor: right-click jumps to the HUD's settings. Middle-click (or shift and
                // right-click) toggles its background.
                if (middle || event.hasShiftDown()) {
                    hovered.toggleBackground();
                    save();
                } else {
                    save();
                    Sky2MConfig.openGui(hovered.settingsSearch());
                }
                return true;
            }
        }
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);

        EditableHud hovered = findHovered(mx, my);
        if (hovered == null) {
            selected = null;
            return super.mouseClicked(event, doubleClick);
        }

        selected = hovered;
        dragOffsetX = mx - hovered.x();
        dragOffsetY = my - hovered.y();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (selected == null || event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return super.mouseDragged(event, dx, dy);

        selected.setClamped((int) event.x() - dragOffsetX, (int) event.y() - dragOffsetY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) save();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        EditableHud hovered = selected != null ? selected : findHovered((int) mouseX, (int) mouseY);
        if (hovered != null && scrollY != 0) {
            hovered.changeScale(scrollY > 0 ? 0.1f : -0.1f);
            save();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (selected == null) return super.keyPressed(event);
        int keyCode = event.key();
        int d = event.hasShiftDown() ? 10 : 1;
        switch (keyCode) {
            case InputConstants.KEY_LEFT -> selected.move(-d, 0);
            case InputConstants.KEY_RIGHT -> selected.move(d, 0);
            case InputConstants.KEY_UP -> selected.move(0, -d);
            case InputConstants.KEY_DOWN -> selected.move(0, d);
            case InputConstants.KEY_MINUS, KEYPAD_MINUS -> selected.changeScale(-0.1f);
            case InputConstants.KEY_EQUALS, InputConstants.KEY_ADD -> selected.changeScale(0.1f);
            default -> { return super.keyPressed(event); }
        }
        save();
        return true;
    }

    private EditableHud findHovered(int mx, int my) {
        for (int i = elements.size() - 1; i >= 0; i--) {
            EditableHud e = elements.get(i);
            if (inside(mx, my, e.x(), e.y(), e.width(), e.height())) return e;
        }
        return null;
    }

    private static boolean inside(int x, int y, int l, int t, int w, int h) {
        return x >= l && x <= l + w && y >= t && y <= t + h;
    }

    private void save() {
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null) Sky2MConfig.saveCurrent(config);
        com.epic60869.sky2m.features.core.Sky2MHuds.save();
    }

    @Override
    public void onClose() {
        save();
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }

    @FunctionalInterface
    private interface Preview { void render(GuiGraphicsExtractor g, int x, int y); }

    @FunctionalInterface
    private interface PositionSetter { void set(int x, int y); }

    private final class EditableHud {
        private final String name, type;
        private final IntSupplier x, y, width, height;
        private final PositionSetter setter;
        private final Preview preview;

        EditableHud(String name, IntSupplier x, IntSupplier y, PositionSetter setter,
                    IntSupplier width, IntSupplier height, Preview preview, String type) {
            this.name = name; this.x = x; this.y = y; this.setter = setter;
            this.width = width; this.height = height; this.preview = preview; this.type = type;
        }

        String name() { return name; }
        int x() { return x.getAsInt(); }
        int y() { return y.getAsInt(); }
        int width() { return width.getAsInt(); }
        int height() { return height.getAsInt(); }
        Preview preview() { return preview; }

        void setPosition(int x, int y) { setter.set(x, y); }

        /** Keeps the whole element on screen; it may touch but not cross any edge. */
        void setClamped(int x, int y) {
            int maxX = Math.max(0, Sky2MHudEditorScreen.this.width - width());
            int maxY = Math.max(0, Sky2MHudEditorScreen.this.height - height());
            setPosition(Math.max(0, Math.min(maxX, x)), Math.max(0, Math.min(maxY, y)));
        }

        void move(int dx, int dy) { setClamped(x() + dx, y() + dy); }

        void changeScale(float d) {
            Sky2MConfig c = Sky2MConfig.current();
            if (c == null) return;
            switch (type) {
                case "rng" -> c.farming.rng.scale = clamp(c.farming.rng.scale + d);
                case "commissions" -> c.mining.commissions.scale = clamp(c.mining.commissions.scale + d);
                case "pet" -> c.misc.pets.display.scale = clamp(c.misc.pets.display.scale + d);
                case "dmap" -> c.dungeons.map.scale = clamp(c.dungeons.map.scale + d);
                default -> {
                    if (type.startsWith("hud:")) com.epic60869.sky2m.features.core.Sky2MHuds.changeScale(type.substring(4), d);
                }
            }
            // Growing an element near an edge must not push it off screen.
            setClamped(x(), y());
        }

        boolean background() {
            Sky2MConfig c = Sky2MConfig.current();
            if (c == null) return true;
            return switch (type) {
                case "rng" -> c.farming.rng.background;
                case "commissions" -> c.mining.commissions.background;
                case "pet" -> c.misc.pets.display.background;
                case "dmap" -> c.dungeons.map.background;
                default -> !type.startsWith("hud:") || com.epic60869.sky2m.features.core.Sky2MHuds.placement(type.substring(4)).background;
            };
        }

        /** What to search the settings for to find this HUD's options. */
        String settingsSearch() {
            return switch (type) {
                case "rng" -> "Farming RNG HUD";
                case "commissions" -> "Mining Commissions";
                case "pet" -> "Pets Display";
                case "dmap" -> "Dungeon Map";
                default -> type.startsWith("hud:") ? com.epic60869.sky2m.features.core.Sky2MHuds.settingsSearch(type.substring(4), name()) : name();
            };
        }

        void toggleBackground() {
            Sky2MConfig c = Sky2MConfig.current();
            if (c == null) return;
            switch (type) {
                case "rng" -> c.farming.rng.background = !c.farming.rng.background;
                case "commissions" -> c.mining.commissions.background = !c.mining.commissions.background;
                case "pet" -> c.misc.pets.display.background = !c.misc.pets.display.background;
                case "dmap" -> c.dungeons.map.background = !c.dungeons.map.background;
                default -> {
                    if (type.startsWith("hud:")) com.epic60869.sky2m.features.core.Sky2MHuds.toggleBackground(type.substring(4));
                }
            }
        }

        private float clamp(float v) { return Math.max(0.5f, Math.min(3.0f, Math.round(v * 10f) / 10f)); }
    }

    @FunctionalInterface
    private interface IntSupplier { int getAsInt(); }
}
