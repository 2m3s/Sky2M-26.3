// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/StoragePageSlot.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import net.minecraft.client.Minecraft;

/** One storage page: 0-8 are the Ender Chest pages, 9-26 the backpacks. */
public record StoragePageSlot(int index) implements Comparable<StoragePageSlot> {
    public boolean isEnderChest() {
        return index < 9;
    }

    public boolean isBackPack() {
        return !isEnderChest();
    }

    public int slotIndexInOverviewPage() {
        return isEnderChest() ? index + 9 : index + 18;
    }

    public String defaultName() {
        return isEnderChest() ? "Ender Chest #" + (index + 1) : "Backpack #" + (index - 9 + 1);
    }

    public void navigateTo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (isBackPack()) mc.player.connection.sendCommand("backpack " + (index - 9 + 1));
        else mc.player.connection.sendCommand("enderchest " + (index + 1));
    }

    public static StoragePageSlot fromOverviewSlotIndex(int slot) {
        if (slot >= 9 && slot < 18) return new StoragePageSlot(slot - 9);
        if (slot >= 27 && slot < 45) return new StoragePageSlot(slot - 27 + 9);
        return null;
    }

    public static StoragePageSlot ofEnderChestPage(int slot) {
        return new StoragePageSlot(slot - 1);
    }

    public static StoragePageSlot ofBackPackPage(int slot) {
        return new StoragePageSlot(slot - 1 + 9);
    }

    @Override
    public int compareTo(StoragePageSlot other) {
        return index - other.index;
    }
}
