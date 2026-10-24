package com.epic60869.sky2m.features.combat.kuudra;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * Combat > Kuudra. Ported from Odin's Nether module (BSD-3-Clause, https://github.com/odtheking/Odin): supply, pile and
 * pre-spot helpers, fresh tools, build helper, Kuudra info, chest profit and perk menu cleaner. HUDs are moved in /s2 gui.
 */
public final class KuudraConfig {
    // ---- Supplies
    @Expose
    @ConfigOption(name = "Supply Pick-Up Waypoints", desc = "In the supply phase, a waypoint at each supply crate you can pick up.")
    @ConfigEditorBoolean
    public boolean supplyPickUp = false;

    @Expose
    @ConfigOption(name = "Supply Drop-Off Waypoints", desc = "In the supply phase, a \"Place Here!\" waypoint at each drop-off spot that still needs a supply. Green is the spot a party member called missing, red the rest.")
    @ConfigEditorBoolean
    public boolean supplyDropOff = false;

    @Expose
    @ConfigOption(name = "Supply Spot Labels", desc = "Name each supply pick-up spot (Triangle, X, Equals, Slash, Shop, xCannon, Square) in the world.")
    @ConfigEditorBoolean
    public boolean supplySpotLabels = false;

    @Expose
    @ConfigOption(name = "Supply Time In Chat", desc = "After each supply you or a teammate recover, a line with how long into the supply phase it took.")
    @ConfigEditorBoolean
    public boolean supplyTimes = false;

    @Expose
    @ConfigOption(name = "Pre-Spot Alert", desc = "Works out your pre spot when Elle sends you to the platform, and at the start tells you (on screen, not in party chat) if your pre or its second crate is missing.")
    @ConfigEditorBoolean
    public boolean preSpotAlert = false;

    @Expose
    @ConfigOption(name = "Crate Priority", desc = "When you or a party member call a missing crate (\"No Triangle!\"), show which crate to go for from your pre spot.")
    @ConfigEditorBoolean
    public boolean cratePriority = false;

    @Expose
    @ConfigOption(name = "Advanced Crate Priority", desc = "Harder crate priority in some situations (pull Square and X Cannon at once).")
    @ConfigEditorBoolean
    public boolean advancedCratePriority = false;

    // ---- Build
    @Expose
    @ConfigOption(name = "Unfinished Pile Waypoints", desc = "In the build phase, a waypoint over each pile that isn't finished, named with its progress.")
    @ConfigEditorBoolean
    public boolean pileWaypoints = false;

    @Expose
    @ConfigOption(name = "Hide Default Pile Tag", desc = "Hide Hypixel's own tag over unfinished piles while the waypoints show.")
    @ConfigEditorBoolean
    public boolean hidePileTag = true;

    @Expose
    @ConfigOption(name = "Build Helper HUD", desc = "HUD with the ballista's build percentage, how many players are building and how many have Fresh Tools. Move it in /s2 gui.")
    @ConfigEditorBoolean
    public boolean buildHud = false;

    @Expose
    @ConfigOption(name = "Build Progress On Ballista", desc = "Draw the build percentage and builders over the ballista in the world.")
    @ConfigEditorBoolean
    public boolean buildOnBallista = false;

    @Expose
    @ConfigOption(name = "Stun Alert Percent", desc = "Tier 3 and up: show \"Go to stun\" once the ballista reaches this build percentage (0 turns it off).")
    @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 1)
    public int stunPercent = 0;

    @Expose
    @ConfigOption(name = "Fresh Tools Timer", desc = "HUD counting down the 10 seconds of Fresh Tools for you and the party members who say FRESH in party chat. Move it in /s2 gui.")
    @ConfigEditorBoolean
    public boolean freshTimer = false;

    // ---- Kuudra
    @Expose
    @ConfigOption(name = "Kuudra Hitbox", desc = "Outline Kuudra through walls.")
    @ConfigEditorBoolean
    public boolean hitbox = false;

    @Expose
    @ConfigOption(name = "Kuudra Health In World", desc = "Kuudra's health in front of him, scaled to 240M in tier 5's last stretch.")
    @ConfigEditorBoolean
    public boolean healthInWorld = false;

    @Expose
    @ConfigOption(name = "Kuudra Health HUD", desc = "Kuudra's health in a HUD you can move in /s2 gui.")
    @ConfigEditorBoolean
    public boolean healthHud = false;

    @Expose
    @ConfigOption(name = "Use Scaled Health", desc = "In tier 5, once Kuudra is under 25,000 health, show the 240M scale instead of the raw 100,000 one.")
    @ConfigEditorBoolean
    public boolean scaledHealth = true;

    // ---- Splits
    @Expose
    @ConfigOption(name = "Phase Splits HUD", desc = "HUD with how long each phase of the run has taken (Supplies, Build, Fuel & Stun, Boss) next to your personal bests. Move it in /s2 gui.")
    @ConfigEditorBoolean
    public boolean splitsHud = false;

    @Expose
    @ConfigOption(name = "Phase Splits In Chat", desc = "Tell you each phase's time in chat as it ends, and the full run (with personal bests) at the end.")
    @ConfigEditorBoolean
    public boolean splitsChat = false;

    // ---- Chests
    @Expose
    @ConfigOption(name = "Chest Profit", desc = "In the Free and Paid chest menus and Vesuvius's run chests: every item's price, the key cost and the profit, beside the menu. Prices: bazaar instant sell, else lowest BIN.")
    @ConfigEditorBoolean
    public boolean chestProfit = false;

    @Expose
    @ConfigOption(name = "Kuudra Pet Essence Bonus", desc = "Extra Crimson Essence percent from your Kuudra pet, counted in Chest Profit.")
    @ConfigEditorSlider(minValue = 0, maxValue = 20, minStep = 1)
    public int essenceBonusPercent = 0;

    @Expose
    @ConfigOption(name = "Salvage Armour Prices", desc = "Value armour and staffs in Chest Profit as the Crimson Essence you get by salvaging them, not their item price.")
    @ConfigEditorBoolean
    public boolean salvagePrices = false;

    // ---- Perk menu
    @Expose
    @ConfigOption(name = "Perk Menu Cleaner", desc = "Hide perks nobody uses (Steady Hands, Bomberman, Mining Frenzy, Elle's Lava Rod, Elle's Pickaxe, Auto Revive) in the Kuudra Perk Menu and stop clicks on them.")
    @ConfigEditorBoolean
    public boolean perkCleaner = false;

    @Expose
    @ConfigOption(name = "Keep Human Cannonball", desc = "With the Perk Menu Cleaner on, keep showing the Human Cannonball (stun) perk.")
    @ConfigEditorBoolean
    public boolean keepStunPerk = false;
}
