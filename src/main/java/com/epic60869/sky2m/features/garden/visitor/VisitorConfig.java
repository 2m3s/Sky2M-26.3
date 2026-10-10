package com.epic60869.sky2m.features.garden.visitor;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDraggableList;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

/** SkyHanni's garden visitor config (VisitorConfig and its accordions, LGPL-2.1). Move the HUDs in /s2 gui. */
public final class VisitorConfig {
    @Expose
    @ConfigOption(name = "Visitor Timer", desc = "")
    @Accordion
    public Timer timer = new Timer();

    @Expose
    @ConfigOption(name = "Visitor Shopping List", desc = "")
    @Accordion
    public ShoppingList shoppingList = new ShoppingList();

    @Expose
    @ConfigOption(name = "Visitor Inventory", desc = "")
    @Accordion
    public Inventory inventory = new Inventory();

    @Expose
    @ConfigOption(name = "Visitor Reward Warning", desc = "")
    @Accordion
    public RewardWarning rewardWarning = new RewardWarning();

    @Expose
    @ConfigOption(name = "Visitor Drops Statistics Counter", desc = "")
    @Accordion
    public DropsStatistics dropsStatistics = new DropsStatistics();

    @Expose
    @ConfigOption(name = "Charmed Visitors", desc = "")
    @Accordion
    public Charmed charmed = new Charmed();

    @Expose
    @ConfigOption(name = "Notification Chat", desc = "Show in chat when a new visitor is visiting your island.")
    @ConfigEditorBoolean
    public boolean notificationChat = false;

    @Expose
    @ConfigOption(name = "Compact Chat", desc = "Compact reward summary messages when you accept an offer.")
    @ConfigEditorBoolean
    public boolean compactRewardChat = false;

    @Expose
    @ConfigOption(name = "Notification Title", desc = "Show a title when a new visitor is visiting your island.")
    @ConfigEditorBoolean
    public boolean notificationTitle = false;

    @Expose
    @ConfigOption(name = "Highlight Status", desc = "Highlight the status for visitors with a text above or with color.")
    @ConfigEditorDropdown
    public HighlightMode highlightStatus = HighlightMode.DISABLED;

    public enum HighlightMode {
        COLOR("Color Only"),
        NAME("Name Only"),
        BOTH("Both"),
        DISABLED("Disabled");

        private final String displayName;

        HighlightMode(String displayName) {
            this.displayName = displayName;
        }

        public boolean color() {
            return this == COLOR || this == BOTH;
        }

        public boolean showName() {
            return this == NAME || this == BOTH;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Expose
    @ConfigOption(name = "Hypixel Message", desc = "Hide the chat message from Hypixel that a new visitor has arrived at your garden.")
    @ConfigEditorBoolean
    public boolean hypixelArrivedMessage = false;

    @Expose
    @ConfigOption(name = "Hide Chat", desc = "Hide chat messages from the visitors in the garden. (Except those with long dialogues)")
    @ConfigEditorBoolean
    public boolean hideChat = false;

    @Expose
    @ConfigOption(name = "Accept Hotkey", desc = "Accept a visitor when you press this keybind while in the visitor GUI.\n§eUseful for getting Ephemeral Gratitudes during the Great Spook event.")
    @ConfigEditorKeybind(defaultKey = -1)
    public int acceptHotkey = -1;

    @Expose
    @ConfigOption(name = "Highlight Visitors in SkyBlock", desc = "Highlight visitors outside of the Garden.")
    @ConfigEditorBoolean
    public boolean highlightVisitors = false;

    @Expose
    @ConfigOption(name = "Block Interacting with Visitors", desc = "Prevent interacting with / unlocking Visitors to allow for Dedication Cycling.")
    @ConfigEditorDropdown
    public BlockBehaviour blockInteracting = BlockBehaviour.DONT;

    public enum BlockBehaviour {
        DONT("Don't"),
        ALWAYS("Always"),
        ONLY_ON_BINGO("Only on Bingo");

        private final String displayName;

        BlockBehaviour(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final class Timer {
        @Expose
        @ConfigOption(name = "Visitor Timer", desc = "Timer for when the next visitor will appear, and a number for how many visitors are already waiting.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Sixth Visitor Estimate", desc = "Estimate when the sixth visitor in the queue will arrive.\n§eMay be inaccurate with co-op members farming simultaneously.")
        @ConfigEditorBoolean
        public boolean sixthVisitorEnabled = true;

        @Expose
        @ConfigOption(name = "Sixth Visitor Warning", desc = "Notify when it is believed that the sixth visitor has arrived.\n§eMay be inaccurate with co-op members farming simultaneously.")
        @ConfigEditorBoolean
        public boolean sixthVisitorWarning = true;

        @Expose
        @ConfigOption(name = "New Visitor Ping", desc = "Ping you when you are less than 10 seconds away from getting a new visitor.\n§eUseful for getting Ephemeral Gratitudes during the Great Spook event.")
        @ConfigEditorBoolean
        public boolean newVisitorPing = false;
    }

    public static final class ShoppingList {
        @Expose
        @ConfigOption(name = "Enable", desc = "Show all items required for the visitors.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Only when Close", desc = "Only show the shopping list when close to the visitors.")
        @ConfigEditorBoolean
        public boolean onlyWhenClose = false;

        @Expose
        @ConfigOption(name = "Bazaar Alley", desc = "Show the Visitor Items List while inside the Bazaar Alley in the Hub.\n§eHelps in buying the correct amount when not having a §6Booster Cookie §ebuff active.")
        @ConfigEditorBoolean
        public boolean inBazaarAlley = true;

        @Expose
        @ConfigOption(name = "Farming Areas", desc = "Show the Visitor Shopping List while on the Farming Islands or inside the Farm in the Hub.\n§eHelps in farming the correct amount, especially when in the early game.")
        @ConfigEditorBoolean
        public boolean inFarmingAreas = false;

        @Expose
        @ConfigOption(name = "Show Price", desc = "Show the coin price in the shopping list.")
        @ConfigEditorBoolean
        public boolean showPrice = true;

        @Expose
        @ConfigOption(name = "Show Sack Count", desc = "Show the amount of this item that you already have in your sacks.\n§eOnly updates on sack change messages and when you look in your sacks.")
        @ConfigEditorBoolean
        public boolean showSackCount = true;

        @Expose
        @ConfigOption(name = "Show Super Craft", desc = "Show super craft button if there are enough materials to make in the sack.")
        @ConfigEditorBoolean
        public boolean showSuperCraft = false;

        @Expose
        @ConfigOption(name = "Item Preview", desc = "Show the base type for the required items next to new visitors.\n§cNote that some visitors may require any crop.")
        @ConfigEditorBoolean
        public boolean itemPreview = true;
    }

    public static final class Inventory {
        @Expose
        @ConfigOption(name = "Visitor Price", desc = "Show the Bazaar price of the items required for the visitors, like in NEU.")
        @ConfigEditorBoolean
        public boolean showPrice = false;

        @Expose
        @ConfigOption(name = "Amount and Time", desc = "Show the exact item amount (in base crops) when farmed manually. Especially useful for Ironman.")
        @ConfigEditorBoolean
        public boolean exactAmountAndTime = true;

        @Expose
        @ConfigOption(name = "Copper Price", desc = "Show the price per copper inside the visitor GUI.")
        @ConfigEditorBoolean
        public boolean copperPrice = true;

        @Expose
        @ConfigOption(name = "Copper Time", desc = "Show the time required per copper inside the visitor GUI.")
        @ConfigEditorBoolean
        public boolean copperTime = false;

        @Expose
        @ConfigOption(name = "Garden Exp Price", desc = "Show the price per garden experience inside the visitor GUI.")
        @ConfigEditorBoolean
        public boolean experiencePrice = false;
    }

    public static final class RewardWarning {
        @Expose
        @ConfigOption(name = "Notify in Chat", desc = "Send a chat message once you talk to a visitor with a reward.")
        @ConfigEditorBoolean
        public boolean notifyInChat = true;

        @Expose
        @ConfigOption(name = "Show over Name", desc = "Show the reward name above the visitor name.")
        @ConfigEditorBoolean
        public boolean showOverName = true;

        @Expose
        @ConfigOption(name = "Block Refusing Reward", desc = "Prevent refusing visitors with a reward.")
        @ConfigEditorBoolean
        public boolean preventRefusing = true;

        @Expose
        @ConfigOption(name = "Bypass Key", desc = "Hold this key to bypass the Prevent Refusing feature.")
        @ConfigEditorKeybind(defaultKey = InputConstants.KEY_LCONTROL)
        public int bypassKey = InputConstants.KEY_LCONTROL;

        @Expose
        @ConfigOption(name = "Items", desc = "Warn for these reward item visitor drops.")
        @ConfigEditorDraggableList
        public List<VisitorReward> drops = new ArrayList<>(List.of(
            VisitorReward.OVERGROWN_GRASS, VisitorReward.GREEN_BANDANA, VisitorReward.DEDICATION, VisitorReward.MUSIC_RUNE,
            VisitorReward.SPACE_HELMET, VisitorReward.CULTIVATING, VisitorReward.REPLENISH, VisitorReward.COPPER_DYE,
            VisitorReward.FARMING_EXP_BOOST_EPIC, VisitorReward.DYE_WILD_STRAWBERRY));

        @Expose
        @ConfigOption(name = "Coins per Copper", desc = "The price to use for the options below.\nRequires at least one of them to be on.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 50_000f, minStep = 250f)
        public int coinsPerCopperPrice = 6_000;

        @Expose
        @ConfigOption(name = "Block Refusing Copper", desc = "Prevent refusing visitors with a coins per copper lower than the set value.")
        @ConfigEditorBoolean
        public boolean preventRefusingCopper = false;

        @Expose
        @ConfigOption(name = "Block Accepting Copper", desc = "Prevent accepting visitors with a coins per copper higher than the set value.")
        @ConfigEditorBoolean
        public boolean preventAcceptingCopper = false;

        @Expose
        @ConfigOption(name = "Acceptable Coin Loss", desc = "The price to use for the below options.\nRequires one of the below options to be on.\nAbove options take precedence.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 500_000f, minStep = 1_000f)
        public int coinsLossThreshold = 150_000;

        @Expose
        @ConfigOption(name = "Block Refusing Low Loss", desc = "Prevent refusing a visitor with a net loss lower than a certain value.")
        @ConfigEditorBoolean
        public boolean preventRefusingLowLoss = false;

        @Expose
        @ConfigOption(name = "Block Accepting High Loss", desc = "Prevent accepting a visitor with a net loss higher than a certain value.")
        @ConfigEditorBoolean
        public boolean preventAcceptingHighLoss = false;

        @Expose
        @ConfigOption(name = "Block Refusing New Visitors", desc = "Prevents refusing a visitor you've never completed an offer with.\n§eDisabled while on bingo.")
        @ConfigEditorBoolean
        public boolean preventRefusingNew = true;

        @Expose
        @ConfigOption(name = "Transparency", desc = "How transparent the offer buttons should be when blocked.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 255f, minStep = 5f)
        public int transparency = 180;

        @Expose
        @ConfigOption(name = "Outline", desc = "Add a red/green line around the best offer buttons.")
        @ConfigEditorBoolean
        public boolean optionOutline = true;
    }

    public static final class DropsStatistics {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Tally statistics about visitors and the rewards you have received from them.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Text Format", desc = "Drag text to change the appearance of the overlay.")
        @ConfigEditorDraggableList
        public List<StatsEntry> textFormat = new ArrayList<>(List.of(
            StatsEntry.TITLE, StatsEntry.TOTAL_VISITORS, StatsEntry.VISITORS_BY_RARITY, StatsEntry.ACCEPTED, StatsEntry.DENIED,
            StatsEntry.SPACER_1, StatsEntry.COPPER, StatsEntry.FARMING_EXP, StatsEntry.COINS_SPENT, StatsEntry.OVERGROWN_GRASS,
            StatsEntry.GREEN_BANDANA, StatsEntry.DEDICATION_IV, StatsEntry.COPPER_DYE, StatsEntry.HYPERCHARGE_CHIP,
            StatsEntry.QUICKDRAW_CHIP, StatsEntry.FARMING_EXP_BOOST_EPIC, StatsEntry.UNFULFILLED_JERRYSEED, StatsEntry.VOTER_BADGE,
            StatsEntry.VOTER_BADGE_VIP, StatsEntry.VOTER_BADGE_ELITE, StatsEntry.VOTER_BADGE_SUPREME, StatsEntry.VISITORS_GRATITUDE,
            StatsEntry.FARMING_CONTEST_DISPLAY, StatsEntry.ASTRONAUT_PERSONALITY, StatsEntry.FAST_FOOD_BARN_SKIN,
            StatsEntry.JELLY_GREENHOUSE_SKIN));

        @Expose
        @ConfigOption(name = "Display Numbers First", desc = "Whether the number or drop name displays first.\n§eNote: Will not update the preview above!")
        @ConfigEditorBoolean
        public boolean displayNumbersFirst = true;

        @Expose
        @ConfigOption(name = "Only on Barn Plot", desc = "Only show the overlay while on the Barn plot.")
        @ConfigEditorBoolean
        public boolean onlyOnBarn = true;

        @ConfigOption(name = "Reset Statistics", desc = "Reset the Visitor Drops Statistics for this profile (SkyHanni's /shresetvisitordrops).")
        @ConfigEditorButton(buttonText = "RESET")
        public Runnable reset = GardenVisitors::resetDropStatistics;

        /** Generic non-reward entries come before the first VisitorReward entry. */
        public enum StatsEntry {
            TITLE("§e§lVisitor Statistics"),
            TOTAL_VISITORS("§e1,636 Total"),
            VISITORS_BY_RARITY("§a1,172§f-§9382§f-§681§f-§d2§f-§c1"),
            ACCEPTED("§21,382 Accepted"),
            DENIED("§c254 Denied"),
            SPACER_1(" "),
            COPPER("§c62,072 Copper"),
            FARMING_EXP("§33.2m Farming EXP"),
            COINS_SPENT("§647.2m Coins Spent"),
            SPACER_2(" "),
            GARDEN_EXP("§212,600 Garden EXP"),
            BITS("§b4.2k Bits"),
            MITHRIL_POWDER("§220k Mithril Powder"),
            GEMSTONE_POWDER("§d18k Gemstone Powder"),
            FLOWERING_BOUQUET("§b23 §9Flowering Bouquet"),
            OVERGROWN_GRASS("§b4 §9Overgrown Grass"),
            GREEN_BANDANA("§b2 §5Green Bandana"),
            DEDICATION_IV("§b1 §9Dedication IV"),
            MUSIC_RUNE_I("§b6 §b◆ Music Rune I"),
            SPACE_HELMET("§b1 §cSpace Helmet"),
            CULTIVATING_I("§b1 §9Cultivating I"),
            REPLENISH_I("§b1 §9Replenish I"),
            DELICATE("§b1 §9Delicate V"),
            COPPER_DYE("§b1 §8Copper Dye"),
            JUNGLE_KEY("§b1 §5Jungle Key"),
            FRUIT_BOWL("§b1 §9Fruit Bowl"),
            HARVEST_HARBINGER("§b1 §9Harvest Harbinger V"),
            HYPERCHARGE_CHIP("§b3 §9Hypercharge Chip"),
            QUICKDRAW_CHIP("§b7 §9Quickdraw Chip"),
            FARMING_EXP_BOOST_EPIC("§b2 §5Farming Exp Boost"),
            UNFULFILLED_JERRYSEED("§b3 §aUnfulfilled Jerryseed"),
            VOTER_BADGE("§b1 §fVoter's Badge"),
            VOTER_BADGE_VIP("§b1 §aVIP Voter's Badge"),
            VOTER_BADGE_ELITE("§b1 §9Elite Voter's Badge"),
            VOTER_BADGE_SUPREME("§b1 §5Supreme Voter's Badge"),
            DYE_WILD_STRAWBERRY("§b2 §dWild Strawberry Dye"),
            VELVET_TOP_HAT("§b5 §9Velvet Top Hat"),
            CASHMERE_JACKET("§b6 §9Cashmere Jacket"),
            SATIN_TROUSERS("§b4 §9Satin Trousers"),
            OXFORD_SHOES("§b7 §9Oxford Shoes"),
            VISITORS_GRATITUDE("§b7 §fVisitors' Gratitude"),
            FARMING_CONTEST_DISPLAY("§b3 §aFarming Contest Display"),
            ASTRONAUT_PERSONALITY("§b1 §fAstronaut Minion Skin"),
            FAST_FOOD_BARN_SKIN("§b2 §6Fast Food Barn Skin"),
            JELLY_GREENHOUSE_SKIN("§b2 §6Jelly Garden Greenhouse Skin");

            private final String displayName;

            StatsEntry(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }
    }

    public static final class Charmed {
        @Expose
        @ConfigOption(name = "Enable", desc = "Show a list of visitors you have charmed with the Gift Vinyl Set.")
        @ConfigEditorBoolean
        public boolean enabled = false;
    }
}
