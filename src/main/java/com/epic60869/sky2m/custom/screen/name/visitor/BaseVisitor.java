// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker, v6.10.4+26.2), licensed under LGPL-3.0.
package com.epic60869.sky2m.custom.screen.name.visitor;

import java.util.Optional;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;

abstract class BaseVisitor implements FormattedText.StyledContentConsumer<Void> {
	protected int selStart;
	protected int selSize;

	BaseVisitor(int selectionStart, int selectionEnd) {
		this.selStart = Math.min(selectionStart, selectionEnd);
		this.selSize = Math.abs(selectionStart - selectionEnd);
	}

	@Override
	public final Optional<Void> accept(Style style, String asString) {
		visit(style, asString);
		return Optional.empty();
	}

	protected abstract void visit(Style style, String asString);
}
