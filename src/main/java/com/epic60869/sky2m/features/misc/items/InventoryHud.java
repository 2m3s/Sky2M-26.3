package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.BindKeys;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory HUD: your 27 inventory slots (not the hotbar) as a HUD, moved and sized in /s2 gui, shown always, while a
 * key is held, or toggled with a key. Horizontal or vertical, normal or mini (half size).
 * <p>
 * Looks and behaves like Inventory HUD+ (dmitrylovin): an inventory panel and sunken slots (dark, or vanilla's
 * light gray) with a see-through background, and items that bounce when picked up. Written from scratch, since Inventory HUD+ is All Rights Reserved.
 */
public final class InventoryHud {
    private static final int FIRST = 9, ROWS = 3, COLS = 9;
    private static boolean toggledOn = true;
    private static boolean keyWasDown;

    private InventoryHud() {}

    private static FeatureConfigs.InventoryHud config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.gui.inventoryHud;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            FeatureConfigs.InventoryHud c = config();
            if (c == null || !c.enabled || c.show != FeatureConfigs.InventoryHud.Show.TOGGLE_KEY) {
                keyWasDown = false;
                return;
            }
            boolean down = BindKeys.down(c.key);
            // Only in game, so typing the key in chat or a sign doesn't flip it.
            if (down && !keyWasDown && mc.gui.screen() == null) toggledOn = !toggledOn;
            keyWasDown = down;
        });
        Sky2MHuds.registerCustom("inventory_hud", "Inventory HUD", () -> {
            FeatureConfigs.InventoryHud c = config();
            return c != null && c.enabled;
        }, new Sky2MHuds.CustomHud() {
            @Override public int width() { return size(config(), true); }
            @Override public int height() { return size(config(), false); }

            @Override
            public boolean visible() {
                FeatureConfigs.InventoryHud c = config();
                Minecraft mc = Minecraft.getInstance();
                if (c == null || mc.player == null) return false;
                if (c.hideInScreens && mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) return false;
                if (c.hideWhenEmpty && empty(mc.player.getInventory())) return false;
                return switch (c.show) {
                    case ALWAYS -> true;
                    case TOGGLE_KEY -> toggledOn;
                    case HOLD_KEY -> !(mc.gui.screen() instanceof ChatScreen) && BindKeys.down(c.key);
                };
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                FeatureConfigs.InventoryHud c = config();
                if (c != null) draw(graphics, c);
            }
        }, 4, 60);
    }

    private static int cell(FeatureConfigs.InventoryHud c) {
        return c != null && c.mini ? 9 : 18;
    }

    private static int padding(FeatureConfigs.InventoryHud c) {
        return c != null && c.mini ? 3 : 4;
    }

    private static int size(FeatureConfigs.InventoryHud c, boolean width) {
        boolean vertical = c != null && c.vertical;
        int cells = width == vertical ? ROWS : COLS;
        return cells * cell(c) + padding(c) * 2;
    }

    private static boolean empty(Inventory inventory) {
        for (int i = FIRST; i < FIRST + ROWS * COLS; i++) if (!inventory.getItem(i).isEmpty()) return false;
        return true;
    }

    private static void draw(GuiGraphicsExtractor graphics, FeatureConfigs.InventoryHud c) {
        int w = size(c, true), h = size(c, false);
        int cell = cell(c), pad = padding(c);
        int alpha = Math.round(c.opacity * 255) << 24;
        if (c.background != FeatureConfigs.InventoryHud.Background.NONE && alpha != 0) {
            boolean dark = c.style == FeatureConfigs.InventoryHud.Style.DARK;
            panel(graphics, w, h, c.mini ? 1 : 2, alpha, dark);
            if (c.background == FeatureConfigs.InventoryHud.Background.SLOTS) {
                for (int row = 0; row < ROWS; row++) {
                    for (int col = 0; col < COLS; col++) {
                        slot(graphics, pad + (c.vertical ? row : col) * cell, pad + (c.vertical ? col : row) * cell, cell, alpha, dark);
                    }
                }
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Inventory inventory = mc.player.getInventory();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                ItemStack stack = inventory.getItem(FIRST + row * COLS + col);
                if (stack.isEmpty()) continue;
                int x = pad + (c.vertical ? row : col) * cell + 1, y = pad + (c.vertical ? col : row) * cell + 1;
                graphics.pose().pushMatrix();
                if (c.mini) {
                    graphics.pose().translate(x - 0.5f, y - 0.5f);
                    graphics.pose().scale(0.5f, 0.5f);
                    x = y = 0;
                }
                item(graphics, stack, x, y, c.animation ? stack.getPopTime() - partial : 0);
                if (c.showCounts) graphics.itemDecorations(mc.font, stack, x, y);
                graphics.pose().popMatrix();
            }
        }
    }

    /** The hotbar's pickup bounce: squashed and stretched from the bottom middle while the pop time runs down. */
    private static void item(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y, float pop) {
        if (pop <= 0) {
            graphics.item(stack, x, y);
            return;
        }
        float scale = 1 + pop / 5;
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 8, y + 12);
        graphics.pose().scale(1 / scale, (scale + 1) / 2);
        graphics.pose().translate(-(x + 8), -(y + 12));
        graphics.item(stack, x, y);
        graphics.pose().popMatrix();
    }

    /**
     * The container panel: a black outline with cut corners, a lit top-left bevel, a shaded bottom-right one and the
     * middle. Vanilla's light gray, or a near-black dark version. The pieces don't overlap, so a see-through panel
     * shades evenly.
     */
    private static void panel(GuiGraphicsExtractor graphics, int w, int h, int bevel, int alpha, boolean dark) {
        int outline = alpha, light = alpha | (dark ? 0x2E2E2E : 0xFFFFFF), shadow = alpha | (dark ? 0x050505 : 0x555555),
            base = alpha | (dark ? 0x141414 : 0xC6C6C6);
        graphics.fill(1, 0, w - 1, 1, outline);
        graphics.fill(1, h - 1, w - 1, h, outline);
        graphics.fill(0, 1, 1, h - 1, outline);
        graphics.fill(w - 1, 1, w, h - 1, outline);
        int in = 1 + bevel;
        graphics.fill(1, 1, w - in, in, light);
        graphics.fill(1, in, in, h - in, light);
        graphics.fill(in, h - in, w - 1, h - 1, shadow);
        graphics.fill(w - in, in, w - 1, h - in, shadow);
        graphics.fill(w - in, 1, w - 1, in, base);
        graphics.fill(1, h - in, in, h - 1, base);
        graphics.fill(in, in, w - in, h - in, base);
    }

    /** An inventory slot, sunk into the panel: shaded top-left edge, lit bottom-right edge, darker inside. */
    private static void slot(GuiGraphicsExtractor graphics, int x, int y, int size, int alpha, boolean darkStyle) {
        int dark = alpha | (darkStyle ? 0x000000 : 0x373737), light = alpha | (darkStyle ? 0x2A2A2A : 0xFFFFFF),
            mid = alpha | (darkStyle ? 0x0A0A0A : 0x8B8B8B);
        int end = size - 1;
        graphics.fill(x, y, x + end, y + 1, dark);
        graphics.fill(x, y + 1, x + 1, y + end, dark);
        graphics.fill(x + 1, y + end, x + size, y + size, light);
        graphics.fill(x + end, y + 1, x + size, y + end, light);
        graphics.fill(x + end, y, x + size, y + 1, mid);
        graphics.fill(x, y + end, x + 1, y + size, mid);
        graphics.fill(x + 1, y + 1, x + end, y + end, mid);
    }
}
