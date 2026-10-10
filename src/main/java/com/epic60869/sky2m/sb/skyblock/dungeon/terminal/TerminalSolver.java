package com.epic60869.sky2m.sb.skyblock.dungeon.terminal;

import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;

public sealed interface TerminalSolver permits ColorTerminal, LightsOnTerminal, OrderTerminal, SameColorTerminal, StartsWithTerminal {
	default boolean shouldBlockIncorrectClicks() {
		return SkyblockerConfigManager.get().dungeons.terminals.blockIncorrectClicks;
	}
}
