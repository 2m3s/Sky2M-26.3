// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils;

import java.util.function.Supplier;

import net.minecraft.network.chat.MutableComponent;

import com.epic60869.sky2m.custom.util.Compat;

public interface Constants {
	Supplier<MutableComponent> PREFIX = Compat.PREFIX;
}
