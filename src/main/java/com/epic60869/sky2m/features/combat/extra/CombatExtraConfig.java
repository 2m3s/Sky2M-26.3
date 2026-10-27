package com.epic60869.sky2m.features.combat.extra;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/** Settings of the Combat extras (boss indicator, Vampire Slayer, Ashfang, Ghost counter, End dragons, area bosses). */
public final class CombatExtraConfig {
    private CombatExtraConfig() {}

    /** Boss damage indicator, in the spirit of SkyHanni's damage indicator. */
    public static final class BossIndicator {
        @Expose
        @ConfigOption(name = "Enabled", desc = "A HUD for the boss you are fighting (a slayer boss or any \"☠\" boss nearby): its health and percentage, the damage per second dealt to it and how long it takes to die at that rate. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show DPS", desc = "Show the damage per second dealt to the boss, over the last few seconds.")
        @ConfigEditorBoolean
        public boolean showDps = true;

        @Expose
        @ConfigOption(name = "Show Time To Kill", desc = "Show how long the boss has left at the current DPS.")
        @ConfigEditorBoolean
        public boolean showTimeToKill = true;

        @Expose
        @ConfigOption(name = "Show Hits", desc = "Show the \"15 Hits\" line of a Voidgloom Seraph, or any other hit counter in the boss's nametag.")
        @ConfigEditorBoolean
        public boolean showHits = true;

        @Expose
        @ConfigOption(name = "Heal Messages", desc = "Say in chat and in the HUD when the boss heals, and by how much.")
        @ConfigEditorBoolean
        public boolean healMessages = false;

        @Expose
        @ConfigOption(name = "Heal Threshold", desc = "How much the boss has to heal before it is reported, in percent of its maximum health.")
        @ConfigEditorSlider(minValue = 1, maxValue = 25, minStep = 1)
        public int healThreshold = 3;

        @Expose
        @ConfigOption(name = "DPS Window", desc = "How many seconds the DPS is averaged over.")
        @ConfigEditorSlider(minValue = 2, maxValue = 30, minStep = 1)
        public int dpsWindow = 8;

        @Expose
        @ConfigOption(name = "Label Above Boss", desc = "Also draw the boss's health percentage above it in the world.")
        @ConfigEditorBoolean
        public boolean worldLabel = false;
    }

    /** Vampire Slayer helpers: Skyblocker's rift effigy waypoints and indicators, SkyHanni's ichor highlight. */
    public static final class VampireSlayer {
        @Expose
        @ConfigOption(name = "Effigy Waypoints", desc = "In the Stillgore Chateau, a waypoint with a beacon on every Blood Effigy you have not broken yet.")
        @ConfigEditorBoolean
        public boolean effigyWaypoints = false;

        @Expose
        @ConfigOption(name = "Compact Effigy Waypoints", desc = "Only the waypoint at the bottom of each effigy, instead of a column up to it.")
        @ConfigEditorBoolean
        public boolean compactEffigies = false;

        @Expose
        @ConfigOption(name = "Mania Indicator", desc = "A title while your Bloodfiend is using MANIA: green if you are standing on green terracotta (safe), red if not.")
        @ConfigEditorBoolean
        public boolean maniaIndicator = false;

        @Expose
        @ConfigOption(name = "Steak / Stake Indicator", desc = "A title when your Bloodfiend can be staked (its nametag shows the ҉ mark, or it is at 20% health or less).")
        @ConfigEditorBoolean
        public boolean stakeIndicator = false;

        @Expose
        @ConfigOption(name = "Twinclaws Indicator", desc = "A title shortly after the Bloodfiend's TWINCLAWS nametag appears, when to use Holy Ice.")
        @ConfigEditorBoolean
        public boolean twinclawsIndicator = false;

        @Expose
        @ConfigOption(name = "Twinclaws Delay", desc = "Ticks to wait after TWINCLAWS appears before the title.")
        @ConfigEditorSlider(minValue = 0, maxValue = 40, minStep = 1)
        public int twinclawsDelay = 6;

        @Expose
        @ConfigOption(name = "Highlight Blood Ichor / Killer Spring", desc = "Box the Blood Ichor and Killer Spring the Bloodfiend throws, within 15 blocks.")
        @ConfigEditorBoolean
        public boolean highlightIchor = false;
    }

    /** Ashfang helpers, ported from SkyHanni. */
    public static final class Ashfang {
        @Expose
        @ConfigOption(name = "Highlight Blazes", desc = "Box Ashfang's followers (dark grey), underlings (red) and acolytes (blue) while Ashfang is up.")
        @ConfigEditorBoolean
        public boolean highlightBlazes = false;

        @Expose
        @ConfigOption(name = "Blazing Souls", desc = "Box the Blazing Souls Ashfang drops, with a label coloured by distance.")
        @ConfigEditorBoolean
        public boolean blazingSouls = false;

        @Expose
        @ConfigOption(name = "Gravity Orbs", desc = "A circle around Ashfang's Gravity Orbs, so you can stay out of them.")
        @ConfigEditorBoolean
        public boolean gravityOrbs = false;

        @Expose
        @ConfigOption(name = "Freeze Cooldown", desc = "A HUD that counts down the 3 seconds a Cryogenic Blast keeps you frozen.")
        @ConfigEditorBoolean
        public boolean freezeCooldown = false;

        @Expose
        @ConfigOption(name = "Next Reset Cooldown", desc = "A HUD with the time until Ashfang's next reset (46.1 seconds after it spawned).")
        @ConfigEditorBoolean
        public boolean nextReset = false;
    }

    /** Ghost counter and profit tracker, from SkyHanni's GhostTracker. */
    public static final class GhostCounter {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In the Mist (Dwarven Mines), a HUD with your Ghost kills, ghosts since your last Sorrow, best kill combo, average magic find and drops with their value. Kills are read from the Bestiary tab widget: enable it once with /widget. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show Drops", desc = "List every drop (Sorrow, Plasma, Volta, Ghostly Boots, bags of coins) with its count and value.")
        @ConfigEditorBoolean
        public boolean showDrops = true;

        @Expose
        @ConfigOption(name = "Show Profit", desc = "Show the total profit and the profit per hour.")
        @ConfigEditorBoolean
        public boolean showProfit = true;

        @ConfigOption(name = "Reset Counts", desc = "Set every Ghost count back to 0.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = () -> GhostTracker.reset();
    }

    /** Dragon fight helpers in the End, from SkyHanni. */
    public static final class Dragons {
        @Expose
        @ConfigOption(name = "Profit Tracker", desc = "In Dragon's Nest, a HUD with the dragons you killed (with your eyes), their loot, the Summoning Eyes you used and the profit. Dragon loot is counted when it comes into your inventory.")
        @ConfigEditorBoolean
        public boolean profitTracker = false;

        @Expose
        @ConfigOption(name = "Count Leeched Dragons", desc = "Also count dragons you killed without placing an eye.")
        @ConfigEditorBoolean
        public boolean countLeeched = false;

        @Expose
        @ConfigOption(name = "Weight Display", desc = "During a dragon fight, a HUD with your current dragon weight from your eyes, place and damage. Needs the Dragon tab widget (/widget).")
        @ConfigEditorBoolean
        public boolean weightDisplay = false;

        @Expose
        @ConfigOption(name = "Weight In Chat", desc = "After a dragon dies, say your final weight in chat.")
        @ConfigEditorBoolean
        public boolean weightChat = false;

        @Expose
        @ConfigOption(name = "Superior Dragon Notification", desc = "A title when a Superior Dragon spawns.")
        @ConfigEditorBoolean
        public boolean superiorNotify = false;

        @ConfigOption(name = "Reset Dragon Tracker", desc = "Set the dragon profit tracker back to 0.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable resetTracker = () -> DragonTracker.reset();
    }

    /** SkyHanni's Ender Node tracker. */
    public static final class EnderNodes {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In the End, a HUD with the Ender Nodes you mined, their loot, Endermite Nests, Enderman pets and the coins made. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Only With Pickaxe", desc = "Only show it while you hold a pickaxe or drill.")
        @ConfigEditorBoolean
        public boolean onlyPickaxe = false;

        @ConfigOption(name = "Reset Counts", desc = "Set the Ender Node tracker back to 0.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = () -> EnderNodeTracker.reset();
    }

    /** Area boss highlight and spawn timer (Corleone, Arachne's Keeper, Zealot Bruiser). */
    public static final class AreaBosses {
        @Expose
        @ConfigOption(name = "Highlight", desc = "Box Boss Corleone, Arachne's Keepers and Zealot Bruisers near you, with a label.")
        @ConfigEditorBoolean
        public boolean highlight = false;

        @Expose
        @ConfigOption(name = "Spawn Alert", desc = "A title when one of these bosses appears near you.")
        @ConfigEditorBoolean
        public boolean spawnAlert = false;

        @Expose
        @ConfigOption(name = "Timer HUD", desc = "A HUD with the time since each boss last died near you, and the time left until it respawns when a respawn time is set below.")
        @ConfigEditorBoolean
        public boolean timer = false;

        @Expose
        @ConfigOption(name = "Corleone Respawn (s)", desc = "Seconds after a Boss Corleone dies until it respawns, 0 to only count up.")
        @ConfigEditorSlider(minValue = 0, maxValue = 1800, minStep = 5)
        public int corleoneRespawn = 0;

        @Expose
        @ConfigOption(name = "Keeper Respawn (s)", desc = "Seconds after an Arachne's Keeper dies until it respawns, 0 to only count up.")
        @ConfigEditorSlider(minValue = 0, maxValue = 1800, minStep = 5)
        public int keeperRespawn = 0;

        @Expose
        @ConfigOption(name = "Bruiser Respawn (s)", desc = "Seconds after a Zealot Bruiser dies until it respawns, 0 to only count up.")
        @ConfigEditorSlider(minValue = 0, maxValue = 1800, minStep = 5)
        public int bruiserRespawn = 0;
    }
}
