// Trimmed from Skyblocker (LGPL-3.0) to the sections Sky2M's port uses.
package com.epic60869.sky2m.sb.config.configs;

import java.awt.Color;

import com.epic60869.sky2m.sb.utils.waypoint.Waypoint;

public class UIAndVisualsConfig {
	public Waypoints waypoints = new Waypoints();

	public static class Waypoints {
		public boolean enableWaypoints = true;

		public Waypoint.Type waypointType = Waypoint.Type.WAYPOINT;

		public boolean renderLine = true;

		public Color lineColor = new Color(0, 255, 0, 255);

		public float lineWidth = 5f;

		public boolean allowSkippingWaypoints = true;

		public boolean allowGoingBackwards = true;

		public float waypointActivationRadius = 2f;

		public boolean enableChatWaypoints = true;
	}
}
