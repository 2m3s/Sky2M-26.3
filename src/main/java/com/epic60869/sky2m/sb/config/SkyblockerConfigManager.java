// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.config;

import java.util.function.Consumer;

/**
 * Holds the Skyblocker config sections used by the ported dungeon/experiment code.
 * Sky2M copies its own toggles into it (see Sky2MDungeons); values are not saved here.
 */
public final class SkyblockerConfigManager {
	private static final SkyblockerConfig CONFIG = new SkyblockerConfig();

	private SkyblockerConfigManager() {}

	public static SkyblockerConfig get() {
		return CONFIG;
	}

	public static void update(Consumer<SkyblockerConfig> action) {
		action.accept(CONFIG);
	}

	/** Sky2M: same as {@link #update}; the waypoint options save themselves (see Waypoints). */
	public static void updateOnly(Consumer<SkyblockerConfig> action) {
		action.accept(CONFIG);
		com.epic60869.sky2m.sb.skyblock.waypoint.Waypoints.saveOptions();
	}
}
