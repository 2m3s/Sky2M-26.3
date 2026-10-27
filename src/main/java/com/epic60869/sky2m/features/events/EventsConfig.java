package com.epic60869.sky2m.features.events;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * The Events category: Diana and the mayor (see FeatureConfigs), Hoppity's Hunt, the Chocolate Factory, the Carnival, the Great Spook and the winter events.
 * Ported from SkyHanni's event features (https://github.com/hannibal002/SkyHanni, features/event/ and
 * features/inventory/chocolatefactory/, LGPL-2.1). HUD positions and scales are set in /s2 gui.
 */
public final class EventsConfig {
    @Expose
    @Category(name = "Diana", desc = "Rare Diana mobs and lootshare.")
    public com.epic60869.sky2m.features.FeatureConfigs.Diana diana = new com.epic60869.sky2m.features.FeatureConfigs.Diana();

    @Expose
    @Category(name = "Mayor & Election", desc = "The mayor, minister and election.")
    public com.epic60869.sky2m.features.FeatureConfigs.MayorDisplay mayor = new com.epic60869.sky2m.features.FeatureConfigs.MayorDisplay();

    @Expose
    @Category(name = "Hoppity's Hunt", desc = "Egg waypoints and unclaimed eggs.")
    public Hoppity hoppity = new Hoppity();

    @Expose
    @Category(name = "Chocolate Factory", desc = "Upgrades, Time Tower and Stray Rabbits.")
    public ChocolateFactory chocolateFactory = new ChocolateFactory();

    @Expose
    @Category(name = "Carnival", desc = "Tickets, goals and Zombie Shootout.")
    public Carnival carnival = new Carnival();

    @Expose
    @Category(name = "The Great Spook", desc = "Primal Fear, Trick or Treat, chests.")
    public Spook spook = new Spook();

    @Expose
    @Category(name = "Winter", desc = "Jyrre, Frozen Treasure and Reindrake.")
    public Winter winter = new Winter();

    public static final class Hoppity {
        @Expose
        @ConfigOption(name = "Egg Waypoints", desc = "While a Chocolate Egg is waiting to be found (after its \"has appeared\" message), shows every known egg location on the island you are on. The Egglocator still tells you which one it is. Locations you already collected a rabbit from are hidden.")
        @ConfigEditorBoolean
        public boolean eggWaypoints = false;

        @Expose
        @ConfigOption(name = "Hide Collected Locations", desc = "Hide the locations you already found an egg at. Off shows them in red instead.")
        @ConfigEditorBoolean
        public boolean hideCollected = true;

        @Expose
        @ConfigOption(name = "Line to Closest Egg", desc = "Draws a line from your crosshair to the closest egg location that is not collected yet.")
        @ConfigEditorBoolean
        public boolean lineToClosest = false;

        @Expose
        @ConfigOption(name = "Unclaimed Eggs", desc = "HUD listing the Chocolate Eggs that have appeared and the time they have left (a SkyBlock day). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean unclaimedEggs = false;
    }

    public static final class ChocolateFactory {
        @Expose
        @ConfigOption(name = "Stats Display", desc = "While the Chocolate Factory is open, a HUD with your chocolate, production per second, minute, hour and day, multiplier, barn, Time Tower, leaderboard position, time to prestige and time until the best upgrade. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean stats = true;

        @Expose
        @ConfigOption(name = "Upgrade Advice", desc = "In the Chocolate Factory, highlights the best upgrade (cheapest per extra chocolate per second) and the best one you can afford now, and lists them in a HUD with how long they take to pay for themselves. Move it in /s2 gui.\n§7Only rabbits and Coach Jackrabbit are compared; the Time Tower is not counted.")
        @ConfigEditorBoolean
        public boolean upgradeAdvice = false;

        @Expose
        @ConfigOption(name = "Time Tower Display", desc = "Shows the Time Tower's charges and status as last seen in the Chocolate Factory.")
        @ConfigEditorBoolean
        public boolean timeTower = false;

        @Expose
        @ConfigOption(name = "Time Tower Full Warning", desc = "Warns now and then when the Time Tower was full the last time you looked, so charges are not wasted. Click the message to open /cf.")
        @ConfigEditorBoolean
        public boolean timeTowerWarning = false;

        @Expose
        @ConfigOption(name = "Stray Rabbit Timer", desc = "After you find an egg, shows how long the Stray Rabbit stays up while the Chocolate Factory is open (30 seconds).")
        @ConfigEditorBoolean
        public boolean strayTimer = false;

        @Expose
        @ConfigOption(name = "Stray Timer Ding", desc = "Plays a sound every second when the Stray Rabbit timer is below this many seconds. 0 turns it off.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 15f, minStep = 1f)
        public int strayDing = 0;
    }

    public static final class Carnival {
        @Expose
        @ConfigOption(name = "Ticket Reminder", desc = "While the Chivalrous Carnival perk is active, reminds you every 5 minutes to claim your daily Carnival Tickets. Click the message to warp to the Carnival.")
        @ConfigEditorBoolean
        public boolean ticketReminder = false;

        @Expose
        @ConfigOption(name = "Show Goals", desc = "At the Carnival, shows the goals you have not completed yet. Open the Carnival Goals menu once so they are known. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean goals = false;

        @Expose
        @ConfigOption(name = "Zombie Shootout", desc = "")
        @Accordion
        public ZombieShootout zombieShootout = new ZombieShootout();
    }

    public static final class ZombieShootout {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Turns the Zombie Shootout helpers on (they only work during a game).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Coloured Hitboxes", desc = "Outlines the zombies in the colour of their helmet: brown for leather, grey for iron, gold and cyan for diamond.")
        @ConfigEditorBoolean
        public boolean hitboxes = true;

        @Expose
        @ConfigOption(name = "Zombie Timer", desc = "Shows how long each zombie is still there above it.")
        @ConfigEditorBoolean
        public boolean timer = true;

        @Expose
        @ConfigOption(name = "Highest Value Only", desc = "Only highlight (and time) the zombies of the best type on the field.")
        @ConfigEditorBoolean
        public boolean highestOnly = false;
    }

    public static final class Spook {
        @Expose
        @ConfigOption(name = "Primal Fear Timer", desc = "Shows when the next Primal Fear can spawn. Needs the Stats widget with Fear selected in the tab list to account for your Fear stat. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean primalFearTimer = false;

        @Expose
        @ConfigOption(name = "Primal Fear Notify", desc = "Plays a sound when the next Primal Fear can spawn.")
        @ConfigEditorBoolean
        public boolean primalFearNotify = false;

        @Expose
        @ConfigOption(name = "Math Fear Answer", desc = "Shows the answer to the \"QUICK MATHS!\" Primal Fear in chat. You still type it yourself.")
        @ConfigEditorBoolean
        public boolean mathAnswer = false;

        @Expose
        @ConfigOption(name = "Chest Alert", desc = "Shows a title and plays a sound when a Trick or Treat Chest or Party Chest appears.")
        @ConfigEditorBoolean
        public boolean chestAlert = false;

        @Expose
        @ConfigOption(name = "Chest Alert Sound", desc = "Play a sound with the Chest Alert.")
        @ConfigEditorBoolean
        public boolean chestSound = true;
    }

    public static final class Winter {
        @Expose
        @ConfigOption(name = "Refined Jyrre Timer", desc = "Shows how long your Refined Bottle of Jyrre boost has left. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean jyrreTimer = false;

        @Expose
        @ConfigOption(name = "Show When Inactive", desc = "Keep showing the Jyrre timer as inactive when the boost has run out.")
        @ConfigEditorBoolean
        public boolean jyrreInactive = false;

        @Expose
        @ConfigOption(name = "Reindrake Warp", desc = "In Jerry's Workshop, when a Reindrake is summoned, shows a message you can click to warp to the Winter Island spawn.")
        @ConfigEditorBoolean
        public boolean reindrakeWarp = false;

        @Expose
        @ConfigOption(name = "Frozen Treasure Tracker", desc = "Counts the Frozen Treasures you mine in Jerry's Workshop and the ice they are worth. Move it in /s2 gui. Reset it with /s2 resetfrozentreasure.")
        @ConfigEditorBoolean
        public boolean frozenTreasure = false;

        @Expose
        @ConfigOption(name = "Only in Glacial Cave", desc = "Only show the Frozen Treasure Tracker in the Glacial Cave.")
        @ConfigEditorBoolean
        public boolean frozenOnlyCave = false;

        @Expose
        @ConfigOption(name = "Highlight Jerries", desc = "Highlights the Jerries you found from the Jerrypocalypse perk, in the colour of the Jerry.")
        @ConfigEditorBoolean
        public boolean highlightJerries = false;

        @Expose
        @ConfigOption(name = "Line to Jerries", desc = "Draws a line to the Jerries you found from the Jerrypocalypse perk.")
        @ConfigEditorBoolean
        public boolean lineJerries = false;

        @Expose
        @ConfigOption(name = "Unique Gift Counter", desc = "In December, while holding a gift, shows how many unique players you have gifted out of 600. Open the Generow menu to sync it. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean uniqueGiftCounter = false;

        @Expose
        @ConfigOption(name = "Frozen Treasure Highlighter", desc = "Highlights the ice blocks hiding Frozen Treasures in the Glacial Cave.")
        @ConfigEditorBoolean
        public boolean frozenHighlighter = false;

        @Expose
        @ConfigOption(name = "Gift Profit Tracker", desc = "Tracks the rewards and coins from the gifts you give. Move it in /s2 gui. Reset it with /s2 resetgifttracker.")
        @ConfigEditorBoolean
        public boolean giftTracker = false;

        @Expose
        @ConfigOption(name = "Only While Gifting", desc = "Only show the Gift Profit Tracker while holding a gift or just after a gift reward.")
        @ConfigEditorBoolean
        public boolean giftTrackerOnlyGifting = true;

        @Expose
        @ConfigOption(name = "Unique Gifting Opportunities", desc = "In December, highlights players you have not gifted yet in green. Remembers the players you gift.")
        @ConfigEditorBoolean
        public boolean giftingOpportunities = false;

        @Expose
        @ConfigOption(name = "Only While Holding a Gift", desc = "Only highlight players you could still gift while holding a gift.")
        @ConfigEditorBoolean
        public boolean giftingOnlyHolding = true;

        @Expose
        @ConfigOption(name = "Gift Clean Display", desc = "Hides the \"From:\" and \"To:\" names above placed gifts.")
        @ConfigEditorBoolean
        public boolean giftClean = false;

        @Expose
        @ConfigOption(name = "New Year Cake Reminder", desc = "Reminds you to claim the free New Year Cake during the New Year event, with a click to open the baker menu.")
        @ConfigEditorBoolean
        public boolean newYearCakeReminder = false;
    }
}
