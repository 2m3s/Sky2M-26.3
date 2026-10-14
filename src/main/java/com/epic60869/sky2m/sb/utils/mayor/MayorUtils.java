// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.mayor;

import java.util.Set;

public final class MayorUtils {
	private MayorUtils() {}

	/** The mayor's and minister's perk names, from Sky2M's election data (Mayors > Mayor & Election). */
	public static Set<String> getActivePerks() {
		return com.epic60869.sky2m.features.misc.MayorDisplay.activePerks();
	}
}
