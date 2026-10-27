package com.epic60869.sky2m.features.misc.items;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ContainerInput;

import java.util.regex.Pattern;

/** Configurable hotkeys for armor sets and loadouts on their open pages. */
public final class WardrobeHotkeys {
    private static final Pattern WARDROBE_TITLE = Pattern.compile(".*\\(\\d+/\\d+\\) Armor Sets.*");
    private static final Pattern LOADOUT_TITLE = Pattern.compile(".*\\(\\d+/\\d+\\) Loadouts.*");
    private static final int[] LOADOUT_MENU_SLOTS = {14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43};

    private WardrobeHotkeys() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ContainerScreen container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !press(container, event.key()));
        });
    }

    private static boolean press(ContainerScreen screen, int key) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !Compat.isOnSkyblock()) return false;
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        if (title == null) return false;

        if (WARDROBE_TITLE.matcher(title).matches() && config.inventory.wardrobeHotkeys.enabled) {
            int[] keys = {
                config.inventory.wardrobeHotkeys.slot1Key,
                config.inventory.wardrobeHotkeys.slot2Key,
                config.inventory.wardrobeHotkeys.slot3Key,
                config.inventory.wardrobeHotkeys.slot4Key,
                config.inventory.wardrobeHotkeys.slot5Key,
                config.inventory.wardrobeHotkeys.slot6Key,
                config.inventory.wardrobeHotkeys.slot7Key,
                config.inventory.wardrobeHotkeys.slot8Key,
                config.inventory.wardrobeHotkeys.slot9Key
            };
            for (int i = 0; i < keys.length; i++) {
                if (key != keys[i] || key == InputConstants.UNKNOWN.getValue()) continue;
                clickSlot(screen, 36 + i);
                return true;
            }
        }

        if (LOADOUT_TITLE.matcher(title).matches() && config.inventory.wardrobeHotkeys.loadoutEnabled) {
            int[] keys = {
                config.inventory.wardrobeHotkeys.loadoutSlot1Key,
                config.inventory.wardrobeHotkeys.loadoutSlot2Key,
                config.inventory.wardrobeHotkeys.loadoutSlot3Key,
                config.inventory.wardrobeHotkeys.loadoutSlot4Key,
                config.inventory.wardrobeHotkeys.loadoutSlot5Key,
                config.inventory.wardrobeHotkeys.loadoutSlot6Key,
                config.inventory.wardrobeHotkeys.loadoutSlot7Key,
                config.inventory.wardrobeHotkeys.loadoutSlot8Key,
                config.inventory.wardrobeHotkeys.loadoutSlot9Key,
                config.inventory.wardrobeHotkeys.loadoutSlot10Key,
                config.inventory.wardrobeHotkeys.loadoutSlot11Key,
                config.inventory.wardrobeHotkeys.loadoutSlot12Key
            };
            for (int i = 0; i < keys.length; i++) {
                if (key != keys[i] || key == InputConstants.UNKNOWN.getValue()) continue;
                clickSlot(screen, LOADOUT_MENU_SLOTS[i]);
                return true;
            }
        }
        return false;
    }

    private static void clickSlot(ContainerScreen screen, int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || slot >= screen.getMenu().slots.size()) return;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }
}