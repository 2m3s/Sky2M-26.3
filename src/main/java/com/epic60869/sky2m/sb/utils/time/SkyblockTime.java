// Sky2M stand-in for a Skyblocker class used by the ported dungeon code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.time;

import com.epic60869.sky2m.Sky2MSkyblockTime;

public final class SkyblockTime {
	/** Current SkyBlock year, computed from the SkyBlock epoch. */
	public static final Year skyblockYear = new Year();

	private SkyblockTime() {}

	public static final class Year {
		public int get() {
			long elapsed = System.currentTimeMillis() - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli();
			return (int) (elapsed / Sky2MSkyblockTime.YEAR_MILLIS) + 1;
		}
	}
}
