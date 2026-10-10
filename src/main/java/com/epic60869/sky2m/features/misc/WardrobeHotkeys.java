package com.epic60869.sky2m.features.misc;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MConfig;
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

        if (WARDROBE_TITLE.matcher(title).matches() && config.misc.wardrobeHotkeys.enabled) {
            int[] keys = {
                config.misc.wardrobeHotkeys.slot1Key,
                config.misc.wardrobeHotkeys.slot2Key,
                config.misc.wardrobeHotkeys.slot3Key,
                config.misc.wardrobeHotkeys.slot4Key,
                config.misc.wardrobeHotkeys.slot5Key,
                config.misc.wardrobeHotkeys.slot6Key,
                config.misc.wardrobeHotkeys.slot7Key,
                config.misc.wardrobeHotkeys.slot8Key,
                config.misc.wardrobeHotkeys.slot9Key
            };
            for (int i = 0; i < keys.length; i++) {
                if (key != keys[i] || key == InputConstants.UNKNOWN.getValue()) continue;
                clickSlot(screen, 36 + i);
                return true;
            }
        }

        if (LOADOUT_TITLE.matcher(title).matches() && config.misc.wardrobeHotkeys.loadoutEnabled) {
            int[] keys = {
                config.misc.wardrobeHotkeys.loadoutSlot1Key,
                config.misc.wardrobeHotkeys.loadoutSlot2Key,
                config.misc.wardrobeHotkeys.loadoutSlot3Key,
                config.misc.wardrobeHotkeys.loadoutSlot4Key,
                config.misc.wardrobeHotkeys.loadoutSlot5Key,
                config.misc.wardrobeHotkeys.loadoutSlot6Key,
                config.misc.wardrobeHotkeys.loadoutSlot7Key,
                config.misc.wardrobeHotkeys.loadoutSlot8Key,
                config.misc.wardrobeHotkeys.loadoutSlot9Key,
                config.misc.wardrobeHotkeys.loadoutSlot10Key,
                config.misc.wardrobeHotkeys.loadoutSlot11Key,
                config.misc.wardrobeHotkeys.loadoutSlot12Key
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