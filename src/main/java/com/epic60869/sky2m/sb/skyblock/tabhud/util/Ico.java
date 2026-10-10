// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.skyblock.tabhud.util;

import net.minecraft.world.item.Items;

import com.epic60869.sky2m.sb.utils.FlexibleItemStack;

public final class Ico {
	public static final FlexibleItemStack BARRIER = new FlexibleItemStack(Items.BARRIER);
	public static final FlexibleItemStack POTION = new FlexibleItemStack(Items.POTION);
	public static final FlexibleItemStack L_CHESTPLATE = new FlexibleItemStack(Items.LEATHER_CHESTPLATE);
	public static final FlexibleItemStack IRON_SWORD = new FlexibleItemStack(Items.IRON_SWORD);
	public static final FlexibleItemStack B_ROD = new FlexibleItemStack(Items.BLAZE_ROD);
	public static final FlexibleItemStack BOW = new FlexibleItemStack(Items.BOW);

	private Ico() {}
}
