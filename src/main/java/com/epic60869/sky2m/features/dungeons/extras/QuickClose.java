package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.DungeonChestProfit;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;

/**
 * NoFrills' Quick Close (https://github.com/WhatYouThing/NoFrills, BSD-3-Clause): in a dungeon chest (a secret chest or
 * a reward chest), pressing one of your movement keys closes it. The key press is consumed, so it doesn't type or click.
 */
final class QuickClose {
    private QuickClose() {}

    static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ContainerScreen container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                DungeonExtras.Config c = DungeonExtras.config();
                if (c == null || !c.quickClose.enabled || !Compat.isOnSkyblock() || !Sky2MLocation.inDungeon()) return true;
                String title = ChatFormatting.stripFormatting(container.getTitle().getString()).trim();
                if (!title.endsWith("Chest") && !DungeonChestProfit.isChest(title)) return true;
                Minecraft mc = Minecraft.getInstance();
                for (KeyMapping key : new KeyMapping[] {mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight}) {
                    if (key.matches(event)) {
                        container.onClose();
                        return false;
                    }
                }
                return true;
            });
        });
    }
}
