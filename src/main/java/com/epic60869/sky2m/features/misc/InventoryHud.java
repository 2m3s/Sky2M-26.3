package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
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
 * key is held, or toggled with a key. Horizontal or vertical, normal or mini (half size), with a panel or slot
 * background.
 * <p>
 * Ported from Killer560's Mod (inventoryhud/), MIT License, Copyright (c) 2026 Killer560 (which follows
 * briansemrau/InventoryHUD's slot order and Inventory HUD+'s options). Drawn in Sky2M' HUD style.
 */
public final class InventoryHud {
    private static final int FIRST = 9, ROWS = 3, COLS = 9;
    private static boolean toggledOn = true;
    private static boolean keyWasDown;

    private InventoryHud() {}

    private static FeatureConfigs.InventoryHud config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.inventoryHud;
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
        return c != null && c.mini ? 1 : 3;
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
        int alpha = Math.round(c.opacity * 255);
        if (alpha <= 0) return;
        if (c.background != FeatureConfigs.InventoryHud.Background.NONE) {
            // Sky2M' HUD look: a dark translucent panel, with darker slots.
            graphics.fill(0, 0, w, h, (Math.round(alpha * 0.5f) << 24));
            if (c.background == FeatureConfigs.InventoryHud.Background.SLOTS) {
                int slot = (Math.round(alpha * 0.35f) << 24) | 0x202020;
                for (int row = 0; row < ROWS; row++) {
                    for (int col = 0; col < COLS; col++) {
                        int x = pad + (c.vertical ? row : col) * cell, y = pad + (c.vertical ? col : row) * cell;
                        graphics.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, slot);
                    }
                }
            }
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Inventory inventory = mc.player.getInventory();
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                ItemStack stack = inventory.getItem(FIRST + row * COLS + col);
                if (stack.isEmpty()) continue;
                int x = pad + (c.vertical ? row : col) * cell, y = pad + (c.vertical ? col : row) * cell;
                if (c.mini) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(x + 0.5f, y + 0.5f);
                    graphics.pose().scale(0.5f, 0.5f);
                    graphics.item(stack, 0, 0);
                    if (c.showCounts) graphics.itemDecorations(mc.font, stack, 0, 0);
                    graphics.pose().popMatrix();
                } else {
                    graphics.item(stack, x + 1, y + 1);
                    if (c.showCounts) graphics.itemDecorations(mc.font, stack, x + 1, y + 1);
                }
            }
        }
        // Items can't be drawn see-through, so lower opacity dims everything with a dark layer on top.
        if (alpha < 255) graphics.fill(0, 0, w, h, ((255 - alpha) * 3 / 4) << 24);
    }
}
