// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.render.title;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class Title {
	private final MutableComponent text;

	public Title(String translationKey, ChatFormatting formatting) {
		this(Component.translatable(translationKey).withStyle(formatting));
	}

	public Title(MutableComponent text) {
		this.text = text;
	}

	public MutableComponent getText() {
		return text;
	}
}
