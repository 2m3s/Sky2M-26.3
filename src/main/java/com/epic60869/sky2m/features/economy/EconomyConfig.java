package com.epic60869.sky2m.features.economy;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/** Misc > Economy: Bazaar extras, item value breakdown, trade value, bits, reforge and anvil helpers. */
public final class EconomyConfig {
    @Expose
    @ConfigOption(name = "Value Breakdown Tooltip", desc = "Hold Shift over a SkyBlock item to see what its Estimated Item Value is made of: base item, stars, recombobulator, enchantments, gemstones, scrolls and other upgrades (like SkyHanni's Estimated Item Value).")
    @ConfigEditorBoolean
    public boolean breakdownTooltip = false;

    @Expose
    @ConfigOption(name = "Value Breakdown Screen", desc = "Press the Value Breakdown key (J by default, change it in Controls) over an item in any menu to open a window listing its value, row by row.")
    @ConfigEditorBoolean
    public boolean breakdownScreen = false;

    @Expose
    @ConfigOption(name = "Bazaar Best Sell Method", desc = "On a Bazaar product page, add a line to the tooltip of the item showing whether an instant sell or a sell offer pays more per item, and the difference.")
    @ConfigEditorBoolean
    public boolean bazaarBestSell = false;

    @Expose
    @ConfigOption(name = "Bazaar Daily Limit", desc = "Count the coins you instant sell and sell offers that fill on the Bazaar each day (UTC), from the Bazaar chat messages, and show it in a HUD while you are in a Bazaar menu.")
    @ConfigEditorBoolean
    public boolean bazaarDailyLimit = false;

    @Expose
    @ConfigOption(name = "Bazaar Daily Limit Warning", desc = "Warn in chat when your counted daily Bazaar sales pass this many millions of coins (0 turns the warning off).")
    @ConfigEditorSlider(minValue = 0, maxValue = 5000, minStep = 50)
    public int bazaarDailyLimitWarnMillions = 0;

    @Expose
    @ConfigOption(name = "Trade Value", desc = "In a trade window, show the Estimated Item Value of everything you are offering and everything you are being offered, and the difference.")
    @ConfigEditorBoolean
    public boolean tradeValue = false;

    @Expose
    @ConfigOption(name = "Bits Gained Message", desc = "Show a chat line with the bits you gained when your bits go up, and your bits total.")
    @ConfigEditorBoolean
    public boolean bitsGained = false;

    @Expose
    @ConfigOption(name = "Bits To Coins", desc = "In the Community Shop, show how many coins each bit is worth for an item (its price on the Bazaar or lowest BIN divided by its bits cost), and what that makes of your bits.")
    @ConfigEditorBoolean
    public boolean bitsToCoins = false;

    @Expose
    @ConfigOption(name = "Bits Cookie Display", desc = "In the Community Shop, show the bits-to-coins value as the number of Booster Cookies your bits could buy.")
    @ConfigEditorBoolean
    public boolean bitsCookie = false;

    @Expose
    @ConfigOption(name = "Block Rare Reforge Changes", desc = "Stop clicks in the Reforge menu that would replace a reforge in the list below on the item in it. Hold Shift to go through anyway.")
    @ConfigEditorBoolean
    public boolean reforgeBlock = false;

    @Expose
    @ConfigOption(name = "Protected Reforges", desc = "Reforges Block Rare Reforge Changes protects, separated by commas.")
    @ConfigEditorText
    public String protectedReforges = "Fabled, Withered, Giant, Jaded, Necrotic, Ancient, Renowned, Spiritual, Submerged, Mythic, Dirty, Loving, Suspicious, Itchy, Bountiful, Moil, Toil, Blessed, Fierce";

    @Expose
    @ConfigOption(name = "Reforge Helper", desc = "In the Reforge menu, show the reforge the item in it currently has, in the item's tooltip.")
    @ConfigEditorBoolean
    public boolean reforgeHelper = false;

    @Expose
    @ConfigOption(name = "Anvil Combine Helper", desc = "In the Anvil, show the Estimated Item Value of the two items you put in and of the result, so you can see what combining loses or gains.")
    @ConfigEditorBoolean
    public boolean anvilHelper = false;
}
