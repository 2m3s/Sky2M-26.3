// Ported from Firmament (https://github.com/FirmamentMC/Firmament), features/inventory/storageoverlay/VirtualInventory.kt.
// SPDX-FileCopyrightText: Linnea Gräf <nea@nea.moe>, Firmament Contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package com.epic60869.sky2m.features.misc.storage;

import com.epic60869.sky2m.Sky2MStorageSearch;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The items of one saved storage page (without Hypixel's top row), rows of 9. Stored as compressed NBT; the items are
 * read back lazily, once a world is loaded to read them against (like Firmament's LazyItemStack).
 */
public final class VirtualInventory {
    private final int rows;
    private String blob;
    private List<ItemStack> stacks;

    public VirtualInventory(List<ItemStack> stacks) {
        List<ItemStack> copy = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) copy.add(stack == null ? ItemStack.EMPTY : stack.copy());
        this.stacks = Collections.unmodifiableList(copy);
        this.rows = copy.size() / 9;
        this.blob = Sky2MStorageSearch.encodeItems(copy);
    }

    private VirtualInventory(int rows, String blob) {
        this.rows = rows;
        this.blob = blob;
    }

    public static VirtualInventory fromSaved(int rows, String blob) {
        return new VirtualInventory(rows, blob);
    }

    public int rows() {
        return rows;
    }

    /** The items, rows * 9 of them (empty stacks until they can be read). */
    public List<ItemStack> stacks() {
        if (stacks == null) {
            List<ItemStack> decoded = blob == null ? null : Sky2MStorageSearch.decodeItems(blob);
            if (decoded == null) return Collections.nCopies(rows * 9, ItemStack.EMPTY);
            List<ItemStack> fixed = new ArrayList<>(decoded);
            while (fixed.size() < rows * 9) fixed.add(ItemStack.EMPTY);
            stacks = Collections.unmodifiableList(fixed.subList(0, rows * 9));
        }
        return stacks;
    }

    /** What gets saved; encoded when the page was captured (a world is always loaded then). */
    public String blob() {
        if (blob == null && stacks != null) blob = Sky2MStorageSearch.encodeItems(stacks);
        return blob;
    }

    /** Whether this holds the same items as {@code other} (so an unchanged page isn't saved again). */
    public boolean sameAs(List<ItemStack> other) {
        List<ItemStack> mine = stacks();
        if (mine.size() != other.size()) return false;
        for (int i = 0; i < mine.size(); i++) {
            if (!ItemStack.matches(mine.get(i), other.get(i))) return false;
        }
        return true;
    }
}
