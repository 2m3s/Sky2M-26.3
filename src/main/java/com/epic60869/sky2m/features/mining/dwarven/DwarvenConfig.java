package com.epic60869.sky2m.features.mining.dwarven;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * Config for the Dwarven Mines solvers, Crystal Nucleus tracker, Glacite cold helpers, Precision Mining and Flowstate,
 * the HOTM/HOTF overlays, the mining events display and the Dwarven Mines carpet retexture (Mining category).
 */
public final class DwarvenConfig {
    private DwarvenConfig() {}

    public static final class Solvers {
        @Expose
        @ConfigOption(name = "Fetchur Solver", desc = "Replace Fetchur's riddle in chat with the item he wants.")
        @ConfigEditorBoolean
        public boolean fetchur = false;

        @Expose
        @ConfigOption(name = "Puzzler Solver", desc = "Mark the block you need to mine for the Puzzler's reward after he tells you the arrows.")
        @ConfigEditorBoolean
        public boolean puzzler = false;

        @Expose
        @ConfigOption(name = "Metal Detector Solver", desc = "In the Mines of Divan, mark where the treasure chest is while you hold the Metal Detector. Stand still in a few different spots until only one place is left.")
        @ConfigEditorBoolean
        public boolean metalDetector = false;

        @Expose
        @ConfigOption(name = "Metal Detector Sound", desc = "Play a ding when the Metal Detector solver finds a possible chest.")
        @ConfigEditorBoolean
        public boolean metalDetectorSound = true;

        @Expose
        @ConfigOption(name = "Stop When You Have All Tools", desc = "Turn the Metal Detector solver off while you carry all four Scavenged tools.")
        @ConfigEditorBoolean
        public boolean metalDetectorStopWhenAllTools = true;

        @Expose
        @ConfigOption(name = "Show Time Taken", desc = "After finding a treasure, show in chat how long it took since the last one.")
        @ConfigEditorBoolean
        public boolean metalDetectorTime = true;
    }

    public static final class Nucleus {
        @Expose
        @ConfigOption(name = "Run Tracker", desc = "HUD with your Crystal Nucleus runs and the loot of each Loot Bundle (items and amounts, kept between sessions). Move it in /s2 gui. /s2 resetnucleus clears it.")
        @ConfigEditorBoolean
        public boolean tracker = false;

        @Expose
        @ConfigOption(name = "Hide Loot Bundle Chat", desc = "Hide the Crystal Nucleus Loot Bundle message from chat and show one short line with the loot instead.")
        @ConfigEditorBoolean
        public boolean compactChat = false;
    }

    public static final class Glacite {
        @Expose
        @ConfigOption(name = "Cold Overlay", desc = "Frost on the edges of your screen that gets stronger the colder you are in the Glacite Tunnels and Mineshafts.")
        @ConfigEditorBoolean
        public boolean coldOverlay = false;

        @Expose
        @ConfigOption(name = "Overlay Starts At", desc = "The overlay starts at this much cold (0 to 100) and is strongest at 100.")
        @ConfigEditorSlider(minValue = 0, maxValue = 99, minStep = 1)
        public int coldThreshold = 50;

        @Expose
        @ConfigOption(name = "Overlay Strength", desc = "How opaque the overlay gets at most, in percent.")
        @ConfigEditorSlider(minValue = 5, maxValue = 100, minStep = 5)
        public int coldMaxAlpha = 60;

        @Expose
        @ConfigOption(name = "Cold Alert", desc = "A title once when your cold reaches the alert level.")
        @ConfigEditorBoolean
        public boolean coldAlert = false;

        @Expose
        @ConfigOption(name = "Cold Alert Level", desc = "Alert at this much cold (you freeze at 100).")
        @ConfigEditorSlider(minValue = 10, maxValue = 99, minStep = 1)
        public int coldAlertLevel = 70;

        @Expose
        @ConfigOption(name = "Ascension Rope Reminder", desc = "In a Mineshaft, remind you to use an Ascension Rope when your cold reaches the alert level and you carry one (it only shows a title, you use the rope yourself). If you have none, it says so.")
        @ConfigEditorBoolean
        public boolean ropeReminder = false;
    }

    public static final class Precision {
        @Expose
        @ConfigOption(name = "Precision Mining Highlight", desc = "Highlight the Precision Mining spot on the block you are mining (green when you aim at it).")
        @ConfigEditorBoolean
        public boolean highlight = false;

        @Expose
        @ConfigOption(name = "Flowstate HUD", desc = "While you mine with Flowstate, a HUD with your block streak, the speed it gives, the time left and your personal best. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean flowstate = false;

        @Expose
        @ConfigOption(name = "Flowstate Personal Best Message", desc = "A chat message when you beat your Flowstate streak record.")
        @ConfigEditorBoolean
        public boolean flowstatePbMessage = true;
    }

    public static final class Hotx {
        @Expose
        @ConfigOption(name = "HOTM Level As Stack Size", desc = "In the Heart of the Mountain menu, show each perk's level on its icon (not for unlocked-at-zero or maxed perks).")
        @ConfigEditorBoolean
        public boolean hotmLevel = false;

        @Expose
        @ConfigOption(name = "HOTM Powder Cost Tooltip", desc = "In the Heart of the Mountain menu, add the powder cost of the next level and the total powder left to max the perk to the tooltip.")
        @ConfigEditorBoolean
        public boolean hotmTooltip = false;

        @Expose
        @ConfigOption(name = "HOTM Highlight Perks", desc = "In the Heart of the Mountain menu, highlight enabled perks green and disabled perks red.")
        @ConfigEditorBoolean
        public boolean hotmHighlight = false;

        @Expose
        @ConfigOption(name = "HOTF Level As Stack Size", desc = "In the Heart of the Forest menu, show each perk's level on its icon.")
        @ConfigEditorBoolean
        public boolean hotfLevel = false;
    }

    public enum EventIslands {
        ALL("All"),
        DWARVEN("Dwarven Mines"),
        CRYSTAL("Hollows"),
        CURRENT("This Island");

        private final String displayName;

        EventIslands(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final class Events {
        @Expose
        @ConfigOption(name = "Mining Events HUD", desc = "HUD with the mining events (2x Powder, Goblin Raid, Better Together, Gone with the Wind, Raffle, Mithril Gourmand) running on other servers and when they end, from a community API (it only reads, Sky2M sends nothing). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Islands", desc = "Which islands the HUD lists.")
        @ConfigEditorDropdown
        public EventIslands islands = EventIslands.ALL;

        @Expose
        @ConfigOption(name = "Show Outside Mining", desc = "Also show the HUD on islands that are not mining islands.")
        @ConfigEditorBoolean
        public boolean outsideMining = false;

        @Expose
        @ConfigOption(name = "Event Start Alert", desc = "A title when a mining event starts on the server you are on.")
        @ConfigEditorBoolean
        public boolean startAlert = false;

        @Expose
        @ConfigOption(name = "Golden Goblin Alert", desc = "A title when a Golden Goblin spawns.")
        @ConfigEditorBoolean
        public boolean goldenGoblin = false;

        @Expose
        @ConfigOption(name = "Diamond Goblin Alert", desc = "A title when a Diamond Goblin spawns.")
        @ConfigEditorBoolean
        public boolean diamondGoblin = false;

        @Expose
        @ConfigOption(name = "Suspicious Scrap Alert", desc = "A title when you find a Suspicious Scrap.")
        @ConfigEditorBoolean
        public boolean scrap = false;

        @Expose
        @ConfigOption(name = "Glacite Mineshaft Alert", desc = "A title when you find a Glacite Mineshaft portal.")
        @ConfigEditorBoolean
        public boolean mineshaft = false;
    }

    public static final class Retexture {
        @Expose
        @ConfigOption(name = "Red Dwarven Mines Carpets", desc = "Show the gray, light gray and light blue carpets of the Dwarven Mines as red carpets, which is easier on the eyes. Takes effect on chunks as they load: re-enter the Dwarven Mines or press F3+A.")
        @ConfigEditorBoolean
        public boolean redCarpets = false;
    }
}
