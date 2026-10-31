package com.epic60869.sky2m.features.qol;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/** Settings of the status bars, tab list extras, more slot labels and the radial menu (Misc). */
public final class QolConfigs {
    private QolConfigs() {}

    public static final class StatusBars {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show your health, mana, defense, XP and speed as bars on movable HUDs. Each bar is moved in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Hide Action Bar Stats", desc = "Remove the health, defense and mana from the action bar while the bars are shown. Other action bar text stays.")
        @ConfigEditorBoolean
        public boolean hideActionBar = true;

        @Expose
        @ConfigOption(name = "Health Bar", desc = "Show the health bar.")
        @ConfigEditorBoolean
        public boolean health = true;

        @Expose
        @ConfigOption(name = "Mana Bar", desc = "Show the mana bar (overflow mana adds to it).")
        @ConfigEditorBoolean
        public boolean mana = true;

        @Expose
        @ConfigOption(name = "Defense Bar", desc = "Show the defense bar. It fills with the damage you take less of (defense / (defense + 100)).")
        @ConfigEditorBoolean
        public boolean defense = true;

        @Expose
        @ConfigOption(name = "XP Bar", desc = "Show the XP level bar (your SkyBlock level progress).")
        @ConfigEditorBoolean
        public boolean xp = false;

        @Expose
        @ConfigOption(name = "Speed Bar", desc = "Show the speed bar (full at the 400% cap).")
        @ConfigEditorBoolean
        public boolean speed = false;

        @Expose
        @ConfigOption(name = "Bar Width", desc = "How wide each bar is, in pixels.")
        @ConfigEditorSlider(minValue = 40, maxValue = 200, minStep = 5)
        public int width = 90;

        @Expose
        @ConfigOption(name = "Show Numbers", desc = "Write the value on each bar.")
        @ConfigEditorBoolean
        public boolean numbers = true;
    }

    public static final class TabListExtras {
        @Expose
        @ConfigOption(name = "Compact Tab List", desc = "Compact the tab list: Hypixel's sections side by side without the blank filler lines, each column's title once, and the footer as an \"Other\" column.")
        @ConfigEditorBoolean
        public boolean compact = false;

        @Expose
        @ConfigOption(name = "Toggle Tab", desc = "Compact Tab List: press Tab to open or close the tab list, instead of holding it.")
        @ConfigEditorBoolean
        public boolean toggleTab = false;

        @Expose
        @ConfigOption(name = "Hide Hypixel Adverts", desc = "Compact Tab List: hide the Hypixel server and store lines above and below the tab list.")
        @ConfigEditorBoolean
        public boolean hideAdverts = false;

        @Expose
        @ConfigOption(name = "Hide Fire Sales", desc = "Compact Tab List: hide the Fire Sales section.")
        @ConfigEditorBoolean
        public boolean hideFiresales = false;

        @Expose
        @ConfigOption(name = "Hide Tab Background", desc = "Compact Tab List: hide the dark background behind the whole tab list (the columns get their own darker one).")
        @ConfigEditorBoolean
        public boolean hideTabBackground = false;

        @Expose
        @ConfigOption(name = "Hide Player Heads", desc = "Compact Tab List: hide the heads beside player names.")
        @ConfigEditorBoolean
        public boolean hidePlayerIcons = false;

        @Expose
        @ConfigOption(name = "Tab Widgets HUD", desc = "Show tab list widgets (e.g. Pet, Commissions, Pickaxe Ability) on a HUD without holding tab. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean widgetsHud = false;

        @Expose
        @ConfigOption(name = "Widgets to Show", desc = "Widget names separated by commas, as the tab list names them: Pet, Commissions, Pickaxe Ability, Event, Forge, Powder, Skills.")
        @ConfigEditorText
        public String widgets = "Pet, Commissions";
    }

    public static final class SlotLabels {
        @Expose
        @ConfigOption(name = "Pet Level", desc = "Show the pet's level on pets in menus.")
        @ConfigEditorBoolean
        public boolean petLevel = false;

        @Expose
        @ConfigOption(name = "Attribute Shard Level", desc = "Show the level on attribute shards.")
        @ConfigEditorBoolean
        public boolean attributeLevel = false;

        @Expose
        @ConfigOption(name = "Minion Tier", desc = "Show the tier on minions.")
        @ConfigEditorBoolean
        public boolean minionTier = false;

        @Expose
        @ConfigOption(name = "Collection Level", desc = "Show the collection tier on items in the collection menus.")
        @ConfigEditorBoolean
        public boolean collectionLevel = false;

        @Expose
        @ConfigOption(name = "Bestiary Level", desc = "Show the bestiary level on mobs in the bestiary menus.")
        @ConfigEditorBoolean
        public boolean bestiaryLevel = false;
    }

    public static final class RadialMenu {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Hold the Radial Menu key (Options > Controls > Sky2M) to open a wheel of commands, move the mouse to a slice and release the key to run it.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Slice 1", desc = "Name | command. Empty to leave the slice out.")
        @ConfigEditorText
        public String slice1 = "SkyBlock Menu | /sbmenu";

        @Expose
        @ConfigOption(name = "Slice 2", desc = "Name | command.")
        @ConfigEditorText
        public String slice2 = "Hub | /hub";

        @Expose
        @ConfigOption(name = "Slice 3", desc = "Name | command.")
        @ConfigEditorText
        public String slice3 = "Island | /is";

        @Expose
        @ConfigOption(name = "Slice 4", desc = "Name | command.")
        @ConfigEditorText
        public String slice4 = "Pets | /pets";

        @Expose
        @ConfigOption(name = "Slice 5", desc = "Name | command.")
        @ConfigEditorText
        public String slice5 = "Wardrobe | /wardrobe";

        @Expose
        @ConfigOption(name = "Slice 6", desc = "Name | command.")
        @ConfigEditorText
        public String slice6 = "Storage | /storage";

        @Expose
        @ConfigOption(name = "Slice 7", desc = "Name | command.")
        @ConfigEditorText
        public String slice7 = "Ender Chest | /ec";

        @Expose
        @ConfigOption(name = "Slice 8", desc = "Name | command.")
        @ConfigEditorText
        public String slice8 = "Bazaar | /bz";
    }
}
