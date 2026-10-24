package com.epic60869.sky2m.features.garden.helpers;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * Config of the Garden helpers: SkyHanni's composter overlay, Anita shop profit, DNA Analyzer and Phantomleaf
 * solvers, Stereo Harmony display, Atmospheric Filter display and farming weight display (SkyHanni is LGPL-2.1),
 * plus Skyblocker's Stereo Harmony helper (LGPL-3.0). Move the HUDs in /s2 gui.
 */
public final class HelperConfig {
    @Expose
    @ConfigOption(name = "Composter Overlay", desc = "")
    @Accordion
    public Composter composter = new Composter();

    @Expose
    @ConfigOption(name = "Stereo Harmony", desc = "")
    @Accordion
    public Stereo stereo = new Stereo();

    @Expose
    @ConfigOption(name = "Atmospheric Filter", desc = "")
    @Accordion
    public Filter filter = new Filter();

    @Expose
    @ConfigOption(name = "Greenhouse", desc = "")
    @Accordion
    public Greenhouse greenhouse = new Greenhouse();

    @Expose
    @ConfigOption(name = "Anita Shop", desc = "")
    @Accordion
    public Anita anita = new Anita();

    @Expose
    @ConfigOption(name = "Farming Weight", desc = "")
    @Accordion
    public Weight weight = new Weight();

    public static final class Composter {
        @Expose
        @ConfigOption(name = "Overlay", desc = "SkyHanni's Composter Overlay: while the Composter or Composter Upgrades menu is open, show the time per compost, what a compost costs with the cheapest Organic Matter and Fuel, how much to buy to fill it up, the profit per hour and, in the upgrades menu, what the upgrade you hover would change. Open Composter Upgrades once so your upgrade levels are known.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Round Down", desc = "Only count whole items when working out what fills the composter, and round 0.75 up.")
        @ConfigEditorBoolean
        public boolean roundDown = true;

        @Expose
        @ConfigOption(name = "Items Shown", desc = "How many of the cheapest Organic Matter and Fuel items to list.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        public int rows = 5;
    }

    public static final class Stereo {
        @Expose
        @ConfigOption(name = "Display", desc = "SkyHanni's Stereo Harmony Display: HUD with the vinyl your Vacuum is playing and which pest it attracts. Open the Stereo Harmony menu or hold the Vacuum once so it is known.")
        @ConfigEditorBoolean
        public boolean display = false;

        @Expose
        @ConfigOption(name = "Hide When None", desc = "Hide the display when no vinyl is playing.")
        @ConfigEditorBoolean
        public boolean hideWhenNone = true;

        @Expose
        @ConfigOption(name = "Vinyl Helper", desc = "Skyblocker's Stereo Harmony helper: in the Stereo Harmony menu, outline the vinyl that attracts the pest of the crop you are farming (the farming tool you hold): green to pick it, yellow when it is already playing.")
        @ConfigEditorBoolean
        public boolean helper = false;
    }

    public static final class Filter {
        @Expose
        @ConfigOption(name = "Display", desc = "SkyHanni's Atmospheric Filter Display: HUD with the current season and the perk the Atmospheric Filter gives for it.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Outside Garden", desc = "Show it everywhere on SkyBlock, not just in the Garden.")
        @ConfigEditorBoolean
        public boolean outsideGarden = false;

        @Expose
        @ConfigOption(name = "Only Buff", desc = "Only show the perk, not the season name.")
        @ConfigEditorBoolean
        public boolean onlyBuff = false;

        @Expose
        @ConfigOption(name = "Abbreviate Season", desc = "Show the first four letters of the season.")
        @ConfigEditorBoolean
        public boolean abbreviateSeason = false;

        @Expose
        @ConfigOption(name = "Abbreviate Perk", desc = "Show a short form of the perk.")
        @ConfigEditorBoolean
        public boolean abbreviatePerk = false;
    }

    public static final class Greenhouse {
        @Expose
        @ConfigOption(name = "DNA Analyzer Solver", desc = "SkyHanni's DNA Analyzer solver: in the DNA menu, outline the next two DNA pieces to swap. The solver finds the fewest swaps; the count shows in a HUD. Does not click for you.")
        @ConfigEditorBoolean
        public boolean dnaSolver = false;

        @Expose
        @ConfigOption(name = "Phantomleaf Solver", desc = "SkyHanni's Phantomleaf solver: after \"Phantomleaf: Poof! Try and find me!\", stand still and the beacon marks where it hides, worked out from the note block's volume. If several spots are possible, move a little to narrow it down.")
        @ConfigEditorBoolean
        public boolean phantomleaf = false;
    }

    public static final class Anita {
        @Expose
        @ConfigOption(name = "Extra Farming Fortune", desc = "SkyHanni's Anita Extra Farming Fortune: on the Extra Farming Fortune item in Anita's shop, show the Jacob's Tickets and Gold Medals needed to max it, and what the tickets cost.")
        @ConfigEditorBoolean
        public boolean extraFarmingFortune = false;

        @Expose
        @ConfigOption(name = "Medal Profit", desc = "SkyHanni's Anita Medal Profit: in Anita's shop, show the profit per Bronze Medal of every item you can buy (the sell price minus any extra items it costs, divided by the medals it costs). Silver counts as 2 bronze and Gold as 8.")
        @ConfigEditorBoolean
        public boolean medalProfit = false;
    }

    public static final class Weight {
        @Expose
        @ConfigOption(name = "Display", desc = "SkyHanni's farming weight display: HUD with your Elite (elitebot.dev) farming weight, kept up to date as you break crops and the weight you gain per hour, with the time to reach your goal. Gains between Elite's updates are an estimate from your Farming Fortune.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Goal Weight", desc = "The farming weight to count down to. 0 shows no goal.")
        @ConfigEditorSlider(minValue = 0, maxValue = 20000, minStep = 50)
        public int goal = 0;

        @Expose
        @ConfigOption(name = "Farming Fortune", desc = "Your Farming Fortune, for estimating drops between Elite's updates. Used when the tab list does not show it (open the Stats widget to have it read).")
        @ConfigEditorSlider(minValue = 0, maxValue = 3000, minStep = 10)
        public int farmingFortune = 1000;

        @Expose
        @ConfigOption(name = "Show Per Hour", desc = "Show the weight you gain per hour.")
        @ConfigEditorBoolean
        public boolean perHour = true;

        @Expose
        @ConfigOption(name = "Only While Farming", desc = "Only show it in the Garden.")
        @ConfigEditorBoolean
        public boolean gardenOnly = true;
    }
}
