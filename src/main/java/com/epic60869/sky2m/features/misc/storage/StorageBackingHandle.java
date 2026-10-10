// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StorageBackingHandle.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A handle representing the state of the "server side" screens. */
public sealed interface StorageBackingHandle {

    sealed interface HasBackingScreen extends StorageBackingHandle {
        ChestMenu handler();
    }

    /** The main storage overview is open. Clicking on a slot will open that page. This page is accessible via /storage. */
    record Overview(ChestMenu handler) implements HasBackingScreen {}

    /**
     * An individual storage page is open. This may be a backpack or an enderchest page. This page is accessible via
     * the {@link Overview} or via /ec &lt;index + 1&gt; for enderchest pages.
     */
    record Page(ChestMenu handler, StoragePageSlot storagePageSlot) implements HasBackingScreen {}

    Pattern ENDER_CHEST_NAME = Pattern.compile("^Ender Chest (?:✦ )?\\(([1-9])/[1-9]\\)$");
    Pattern BACK_PACK_NAME = Pattern.compile("^.+Backpack (?:✦ )?\\(Slot #([0-9]+)\\)$");

    /**
     * Parse a screen into a {@link StorageBackingHandle}. If this returns null it means that the screen is not
     * representable as a {@link StorageBackingHandle}, meaning another screen is open, for example the enderchest icon
     * selection screen.
     */
    static StorageBackingHandle fromScreen(Screen screen) {
        if (!(screen instanceof ContainerScreen container)) return null;
        String title = ChatFormatting.stripFormatting(container.getTitle().getString());
        if (title == null) return null;
        if (title.equals("Storage")) return new Overview(container.getMenu());
        Matcher ender = ENDER_CHEST_NAME.matcher(title);
        if (ender.matches()) return new Page(container.getMenu(), StoragePageSlot.ofEnderChestPage(Integer.parseInt(ender.group(1))));
        Matcher backpack = BACK_PACK_NAME.matcher(title);
        if (backpack.matches()) return new Page(container.getMenu(), StoragePageSlot.ofBackPackPage(Integer.parseInt(backpack.group(1))));
        return null;
    }
}
