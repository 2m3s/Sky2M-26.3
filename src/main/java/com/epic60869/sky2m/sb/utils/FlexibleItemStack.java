// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Creates its stack lazily, since item stacks cannot be built before item components are bound. */
public final class FlexibleItemStack {
	private final Item item;
	private ItemStack stack;

	public FlexibleItemStack(Item item) {
		this.item = item;
	}

	public ItemStack getStack() {
		if (stack == null) stack = new ItemStack(item);
		return stack;
	}

	public ItemStack getStackOrThrow() {
		return getStack();
	}
}
