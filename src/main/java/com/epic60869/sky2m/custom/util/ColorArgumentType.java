// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom.util;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * Utility class that provides static methods for abstracting away the actual argument type classes.
 */
public final class ColorArgumentType {
	private ColorArgumentType() {} // Prevent instantiation

	public static RgbColorArgumentType rgb() {
		return new RgbColorArgumentType();
	}

	public static HexColorArgumentType hex() {
		return new HexColorArgumentType();
	}

	public static int getIntFromHex(CommandContext<FabricClientCommandSource> context, String name) {
		return HexColorArgumentType.getInt(context, name);
	}

	public static int getIntFromRgb(CommandContext<FabricClientCommandSource> context, String name) {
		return RgbColorArgumentType.getInt(context, name);
	}
}
