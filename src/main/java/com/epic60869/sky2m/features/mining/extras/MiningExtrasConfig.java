package com.epic60869.sky2m.features.mining.extras;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/** Config for the Glacite Mineshaft helpers, the powder tracker and the fossil excavator helpers (Mining category). */
public final class MiningExtrasConfig {
    private MiningExtrasConfig() {}

    public static final class GlaciteMineshaft {
        @Expose
        @ConfigOption(name = "Corpse Finder", desc = "Mark Lapis, Tungsten, Umber and Vanguard corpses with a waypoint once you have seen them (not through walls), and turn the waypoint green when you loot them. Based on SkyHanni's Corpse Locator.")
        @ConfigEditorBoolean
        public boolean corpseFinder = false;

        @Expose
        @ConfigOption(name = "Corpse Profit", desc = "After looting a corpse, a chat message with its profit: the loot's value minus the key it used (Resourceful saves count). Hover for the items. Based on SkyHanni's Profit per Corpse.")
        @ConfigEditorBoolean
        public boolean corpseProfit = false;

        @Expose
        @ConfigOption(name = "Entrance Waypoint", desc = "Mark the mineshaft entrance (where you arrived) so you can find the way out.")
        @ConfigEditorBoolean
        public boolean entranceWaypoint = false;

        @Expose
        @ConfigOption(name = "Ladder Waypoint", desc = "Mark the bottom of the ladder shaft, which is at a fixed spot from the entrance.")
        @ConfigEditorBoolean
        public boolean ladderWaypoint = false;

        @Expose
        @ConfigOption(name = "Cave-In Timer", desc = "HUD with the 60 second countdown until the mineshaft entrance caves in. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean caveInTimer = false;

        @Expose
        @ConfigOption(name = "Pity HUD", desc = "HUD with your Glacite Mineshaft pity counter (from the tab list's Pity widget), shown in the Dwarven Mines and Glacite Tunnels. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean pityHud = false;

        @Expose
        @ConfigOption(name = "Pity In Chat", desc = "When a mineshaft spawns, show in chat what your pity counter was.")
        @ConfigEditorBoolean
        public boolean pityChat = false;
    }

    public static final class MineshaftStats {
        @Expose
        @ConfigOption(name = "HUD", desc = "HUD with the time you have been in the current Glacite Mineshaft and the blocks you broke in it, with ore blocks (named *_ore) counted separately. Tungsten, Umber, Glacite and gemstones only count in the block total. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Summary In Chat", desc = "When you leave a mineshaft, say in chat how long you were in it and how many blocks you broke.")
        @ConfigEditorBoolean
        public boolean chat = false;
    }

    public static final class PowderTracker {
        @Expose
        @ConfigOption(name = "Enabled", desc = "HUD with the Mithril, Gemstone and Glacite powder you gained this session and per hour, read from the sidebar. Only gains count, spending does not. Pauses when you stop gaining powder. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Only Show Gained Types", desc = "Hide the powder types you have not gained any of this session.")
        @ConfigEditorBoolean
        public boolean onlyGained = true;

        @Expose
        @ConfigOption(name = "Show Per Hour", desc = "Show the powder per hour next to each type.")
        @ConfigEditorBoolean
        public boolean perHour = true;

        @ConfigOption(name = "Reset Powder Tracker", desc = "Set the session's powder gains back to 0.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = com.epic60869.sky2m.features.mining.extras.PowderTrackerHud::reset;
    }

    public static final class FossilExcavator {
        @Expose
        @ConfigOption(name = "Solver", desc = "In the Fossil Excavator, highlight the best tile to chisel next and show its chance and which fossils are still possible. Based on SkyHanni's Fossil Excavator Solver.")
        @ConfigEditorBoolean
        public boolean solver = false;

        @Expose
        @ConfigOption(name = "Show Chance", desc = "Show the chance of the highlighted tile being part of the fossil on it.")
        @ConfigEditorBoolean
        public boolean showChance = true;

        @Expose
        @ConfigOption(name = "Profit Tracker", desc = "HUD with the loot of your fossil excavations in the Fossil Research Center, their value, and the profit after the Suspicious Scrap each one used. Based on SkyHanni's Excavator Profit Tracker. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean profitTracker = false;

        @ConfigOption(name = "Reset Excavation Profit", desc = "Set the excavation profit tracker back to 0.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = com.epic60869.sky2m.features.mining.extras.ExcavatorProfit::reset;
    }
}
