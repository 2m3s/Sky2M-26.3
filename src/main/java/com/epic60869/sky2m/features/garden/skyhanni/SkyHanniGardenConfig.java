package com.epic60869.sky2m.features.garden.skyhanni;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * SkyHanni's farming features for the Garden (https://github.com/hannibal002/SkyHanni, LGPL-2.1): the farming HUD
 * (crop milestones, crops per minute, coins per hour, Garden level), Jacob's Contest, the true Farming Fortune
 * display, optimal angles and speeds, plot borders, pest traps, the pest profit tracker and the spray display.
 * Move the HUDs in /s2 gui.
 */
public final class SkyHanniGardenConfig {
    @Expose
    @ConfigOption(name = "Farming HUD", desc = "")
    @Accordion
    public FarmingHud farmingHud = new FarmingHud();

    @Expose
    @ConfigOption(name = "Jacob's Contest", desc = "")
    @Accordion
    public Jacob jacob = new Jacob();

    @Expose
    @ConfigOption(name = "Farming Fortune", desc = "")
    @Accordion
    public Fortune fortune = new Fortune();

    @Expose
    @ConfigOption(name = "Optimal Angles and Speed", desc = "")
    @Accordion
    public Optimal optimal = new Optimal();

    @Expose
    @ConfigOption(name = "Plot Borders", desc = "")
    @Accordion
    public Plots plots = new Plots();

    @Expose
    @ConfigOption(name = "Pest Traps, Profit and Spray", desc = "")
    @Accordion
    public PestExtras pests = new PestExtras();

    public static final class FarmingHud {
        @Expose
        @ConfigOption(name = "Crop Milestone HUD", desc = "Crop Milestone display for the crop in your hand: milestone tier and progress (read from the Crop Milestones tab widget), crops per minute, blocks per second and the time to the next tier.\n§eTurn on the Crop Milestones widget in /widget.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Crops Per Minute", desc = "Show the crops per second, minute and hour you farm (from your Farming Fortune, the crop's base drops and your blocks per second).")
        @ConfigEditorBoolean
        public boolean showCropsPerMinute = true;

        @Expose
        @ConfigOption(name = "Coins Per Hour", desc = "Show the coins per hour the crops you farm are worth, from the Bazaar price of their enchanted form (an estimate).")
        @ConfigEditorBoolean
        public boolean showCoinsPerHour = true;

        @Expose
        @ConfigOption(name = "Show ETA", desc = "Show the time until the next crop milestone tier.")
        @ConfigEditorBoolean
        public boolean showEta = true;

        @Expose
        @ConfigOption(name = "Reset After (seconds)", desc = "Reset the speed after you stopped breaking crops for this long.")
        @ConfigEditorSlider(minValue = 3, maxValue = 30, minStep = 1)
        public float resetSeconds = 8;

        @Expose
        @ConfigOption(name = "Warn Near Milestone", desc = "Show a title when the next crop milestone tier is less than 6 seconds away.")
        @ConfigEditorBoolean
        public boolean warnClose = false;

        @Expose
        @ConfigOption(name = "Garden Level HUD", desc = "Garden Level display: your Garden level and experience. Open the Garden Desk (or the SkyBlock Menu) once to read it; visitor rewards update it.")
        @ConfigEditorBoolean
        public boolean gardenLevel = false;
    }

    public static final class Jacob {
        @Expose
        @ConfigOption(name = "Contest Timer HUD", desc = "The Jacob's Contest tab widget (time left and the crops) as a HUD in the Garden.")
        @ConfigEditorBoolean
        public boolean timer = false;

        @Expose
        @ConfigOption(name = "Contest Notification", desc = "A title and chat message when a Jacob's Farming Contest starts, with its crop.")
        @ConfigEditorBoolean
        public boolean notification = false;

        @Expose
        @ConfigOption(name = "Medal Cut-offs HUD", desc = "During a contest, the crops each medal needs (the average of your last contests of that crop, read from the Your Contests menu), with the Farming Fortune that takes at 19.9 blocks per second. Open the Your Contests menu in Jacob's menu to teach it.")
        @ConfigEditorBoolean
        public boolean medals = false;

        @Expose
        @ConfigOption(name = "Contest Stats Summary", desc = "Jacob contest summary: when a contest ends, say how many blocks you broke and your average blocks per second in chat.")
        @ConfigEditorBoolean
        public boolean summary = false;

        @Expose
        @ConfigOption(name = "Hide Zero Crop Stats", desc = "Don't print the summary for a contest you broke no crops in.")
        @ConfigEditorBoolean
        public boolean hideZeroStats = true;

        @Expose
        @ConfigOption(name = "Zorro's Cape Protection", desc = "Block claiming Jacob's Contest rewards (and Bulk Claim) unless Zorro's Cape is equipped, so the Farming Fortune from the cape counts. Open your Stats & Equipment menu once so Sky2M knows your cloak.")
        @ConfigEditorBoolean
        public boolean zorroProtection = false;
    }

    public static final class Fortune {
        @Expose
        @ConfigOption(name = "True Farming Fortune HUD", desc = "True Farming Fortune: the universal plus crop fortune from the tab list Stats widget, the pest fortune bonus and the loss from pests.\n§eTurn on the Stats widget with the Farming Fortune and Latest Crop Fortune lines in /widget.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Breakdown", desc = "Also show the universal and the crop part of the fortune.")
        @ConfigEditorBoolean
        public boolean breakdown = true;

        @Expose
        @ConfigOption(name = "Pest Bonus", desc = "Show the pest fortune bonus (from Phillip) and its time left.")
        @ConfigEditorBoolean
        public boolean pestBonus = true;

        @Expose
        @ConfigOption(name = "Pest Bonus Expired Warning", desc = "A title and chat message when the pest fortune bonus has expired.")
        @ConfigEditorBoolean
        public boolean pestBonusWarning = false;
    }

    public static final class Optimal {
        @Expose
        @ConfigOption(name = "Angle HUD", desc = "Show the optimal yaw and pitch of the crop in your hand next to your current ones (green when you're within half a degree).")
        @ConfigEditorBoolean
        public boolean angleHud = false;

        @Expose
        @ConfigOption(name = "Speed HUD", desc = "Show the optimal speed for the crop in your hand next to your current speed.")
        @ConfigEditorBoolean
        public boolean speedHud = false;

        @Expose
        @ConfigOption(name = "Wrong Speed Warning", desc = "A title when your speed is not the optimal speed while you farm (at most every 20 seconds).")
        @ConfigEditorBoolean
        public boolean speedWarning = false;

        @Expose
        @ConfigOption(name = "Only Warn With Rancher's", desc = "Only warn when wearing Rancher's Boots.")
        @ConfigEditorBoolean
        public boolean onlyRanchers = false;

        @Expose
        @ConfigOption(name = "Sign Overlay", desc = "List every crop's optimal angles on the Squeaky Mousemat sign and every crop's optimal speed on the Rancher's Boots sign.")
        @ConfigEditorBoolean
        public boolean signOverlay = true;
    }

    public static final class Plots {
        @Expose
        @ConfigOption(name = "Plot Borders Key", desc = "Press this key to show or hide the borders of the plot you're in (or the closest one).")
        @ConfigEditorKeybind(defaultKey = -1)
        public int bordersKey = -1;

        @Expose
        @ConfigOption(name = "Borders With Sprayonator", desc = "Show the border of your plot while you hold a Sprayonator.")
        @ConfigEditorBoolean
        public boolean sprayonatorBorders = false;
    }

    public static final class PestExtras {
        @Expose
        @ConfigOption(name = "Pest Trap HUD", desc = "Show how many pest traps you placed and which are full or out of bait (from the Pest Traps, Full Traps and No Bait tab widgets).")
        @ConfigEditorBoolean
        public boolean trapHud = false;

        @Expose
        @ConfigOption(name = "Pest Trap Warnings", desc = "Warn when a pest trap is full, has no bait or when you have not placed all of your traps.")
        @ConfigEditorBoolean
        public boolean trapWarnings = false;

        @Expose
        @ConfigOption(name = "Warn For Full Traps", desc = "Include full traps in the warning.")
        @ConfigEditorBoolean
        public boolean warnFull = true;

        @Expose
        @ConfigOption(name = "Warn For No Bait", desc = "Include traps without bait in the warning.")
        @ConfigEditorBoolean
        public boolean warnNoBait = true;

        @Expose
        @ConfigOption(name = "Warn For Unplaced Traps", desc = "Include traps you have not placed in the warning.")
        @ConfigEditorBoolean
        public boolean warnUnplaced = false;

        @Expose
        @ConfigOption(name = "Warning Message", desc = "How the warning is shown.")
        @ConfigEditorDropdown
        public Display warningDisplay = Display.TITLE;

        public enum Display {
            CHAT("Chat"),
            TITLE("Title"),
            BOTH("Both");

            private final String displayName;

            Display(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }

        @Expose
        @ConfigOption(name = "Warning Sound", desc = "The sound for a warning, like block.note_block.pling. Empty for none.")
        @ConfigEditorText
        public String warningSound = "block.note_block.pling";

        @Expose
        @ConfigOption(name = "Warning Interval (seconds)", desc = "How often the warning repeats.")
        @ConfigEditorSlider(minValue = 10, maxValue = 300, minStep = 5)
        public float warningInterval = 30;

        @Expose
        @ConfigOption(name = "Pest Profit Tracker", desc = "The items you get for killing pests this session, their rare drops and the profit per hour. /s2 pestprofit reset clears it.")
        @ConfigEditorBoolean
        public boolean profitTracker = false;

        @Expose
        @ConfigOption(name = "Spray Display", desc = "Show the spray (Compost, Dung, ...) active on the plot you're in and when it runs out, from the Garden tab list.")
        @ConfigEditorBoolean
        public boolean sprayDisplay = false;

        @Expose
        @ConfigOption(name = "Show Not Sprayed", desc = "Say \"Not sprayed!\" when the plot you're in has no spray.")
        @ConfigEditorBoolean
        public boolean showNotSprayed = false;
    }
}
