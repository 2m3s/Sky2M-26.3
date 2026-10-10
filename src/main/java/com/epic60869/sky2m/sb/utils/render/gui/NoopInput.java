// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker), licensed under LGPL-3.0.
package com.epic60869.sky2m.sb.utils.render.gui;

import net.minecraft.client.input.InputWithModifiers;

public record NoopInput() implements InputWithModifiers {
	public static final InputWithModifiers INSTANCE = new NoopInput();

	@Override
	public int input() {
		return 0;
	}

	@Override
	public int modifiers() {
		return 0;
	}
}
