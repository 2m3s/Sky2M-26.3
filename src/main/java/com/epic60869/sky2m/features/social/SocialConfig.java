package com.epic60869.sky2m.features.social;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * Chat > Social Tools: party shortcut commands (/rp, /pa, /pback, /pkick), waypoints from coordinates in any chat,
 * and profile mode icons on chat names.
 */
public final class SocialConfig {
    @Expose
    @ConfigOption(name = "Party Shortcuts", desc = "Adds /rp (reparty: disband and re-invite everyone), /pa (accept the last party invite), /pback (give the party back to the previous leader) and /pkick <name> <reason> (says the reason in party chat, then kicks).")
    @ConfigEditorBoolean
    public boolean partyShortcuts = false;

    @Expose
    @ConfigOption(name = "Chat Waypoints", desc = "Coordinates in any chat message (x: 1, y: 2, z: 3, as sent by /sendcoords, Patcher or SkyHanni) get a clickable [Waypoint] button that makes a waypoint with the sender's name.")
    @ConfigEditorBoolean
    public boolean chatWaypoints = false;

    @Expose
    @ConfigOption(name = "Auto Chat Waypoints", desc = "Make the waypoint straight away when someone shares coordinates in chat, without clicking [Waypoint]. Party chat is left to Diana > Party Coord Waypoints while that is on.")
    @ConfigEditorBoolean
    public boolean autoChatWaypoints = false;

    @Expose
    @ConfigOption(name = "Profile Icons In Chat", desc = "Show a player's profile icon (Ironman, Stranded or Bingo) next to their name in chat messages that don't already show it, when the tab list showed it for them.")
    @ConfigEditorBoolean
    public boolean profileIcons = true;

    public static SocialConfig current() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.social;
    }
}
