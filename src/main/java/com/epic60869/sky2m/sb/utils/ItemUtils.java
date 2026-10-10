// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils;

import java.util.List;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.custom.util.Compat;

public final class ItemUtils {
	private ItemUtils() {}

	public static List<ItemStack> getArmor(LivingEntity entity) {
		return Compat.getArmor(entity);
	}
}
