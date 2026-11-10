// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.skyblock.tabhud.util;

import com.epic60869.sky2m.features.core.RepoPattern;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;

/** Reads the ordered tab list through Sky2M's tab widget manager. */
public final class PlayerListManager {
	private PlayerListManager() {}

	/** Like {@link #regexAt(int, Pattern)}, with the pattern's current (possibly Sky2M-REPO) regex. */
	public static @Nullable Matcher regexAt(int idx, RepoPattern p) {
		return regexAt(idx, p.get());
	}

	public static @Nullable Matcher regexAt(int idx, Pattern p) {
		String str = strAt(idx);
		if (str == null) return null;
		Matcher m = p.matcher(str);
		return m.matches() ? m : null;
	}

	public static @Nullable String strAt(int idx) {
		List<PlayerInfo> list = Sky2MTabWidgetManager.players();
		if (list.size() <= idx) return null;
		Component txt = com.epic60869.sky2m.custom.util.Compat.rawTabName(list.get(idx));
		if (txt == null) return null;
		String str = txt.getString().trim();
		return str.isEmpty() ? null : str;
	}
}
