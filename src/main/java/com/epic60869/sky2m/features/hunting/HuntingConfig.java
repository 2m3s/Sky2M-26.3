package com.epic60869.sky2m.features.hunting;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorInfoText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * The Hunting category, laid out like SkyHanni's HuntingConfig (https://github.com/hannibal002/SkyHanni,
 * config/features/hunting/, LGPL-2.1), including the 9.1 beta's Critter Capsules and Safari Checklist. HUD positions
 * and scales are set in /s2 gui.
 */
public final class HuntingConfig {
    @Expose
    @Category(name = "Safari", desc = "The Critter Safari.")
    public Safari safari = new Safari();

    @Expose
    @ConfigOption(name = "Shard Tracker", desc = "")
    @Accordion
    public ShardTracker shardTracker = new ShardTracker();

    @Expose
    @ConfigOption(name = "Hunting Profit Tracker", desc = "")
    @Accordion
    public ProfitTracker huntingProfitTracker = new ProfitTracker();

    @Expose
    @Category(name = "Galatea Mob Highlights", desc = "Galatea mob highlights.")
    public MobHighlights mobHighlight = new MobHighlights();

    @Expose
    @ConfigOption(name = "Lasso Display", desc = "Displays your lasso progress on screen. Move it in /s2 gui.")
    @ConfigEditorBoolean
    public boolean lassoDisplay = true;

    @Expose
    @ConfigOption(name = "Fusion Keybinds", desc = "")
    @Accordion
    public FusionKeybinds fusionKeybinds = new FusionKeybinds();

    @Expose
    @ConfigOption(name = "Prevent Huntrap Misclick", desc = "Prevents clicking on empty traps in the Hunting Toolkit.")
    @ConfigEditorBoolean
    public boolean huntrapMisclick = false;

    @Expose
    @ConfigOption(name = "Shulker Finder", desc = "Shows a route from your position to the nearest possible spawn point for a Shulker (Hideonleaf on Galatea, Hideonsun in Torrhus Canyon) for easy hunting. Press the key below to start it.")
    @ConfigEditorBoolean
    public boolean shulkerFinder = false;

    @Expose
    @ConfigOption(name = "Show next Shulker", desc = "Press this key to show the route to the next Shulker.")
    @ConfigEditorKeybind(defaultKey = -1)
    public int nextShulkerKeybind = -1;

    @Expose
    @ConfigOption(name = "Fusion Display", desc = "Displays the shard you are fusing and how many you have, in the Fusion Box. Move it in /s2 gui.")
    @ConfigEditorBoolean
    public boolean fusionDisplay = true;

    public static final class Safari {
        @Expose
        @ConfigOption(name = "Names in Center", desc = "Shows the names of the 4 areas while in the center of the Critter Safari.")
        @ConfigEditorBoolean
        public boolean namesInCenter = false;

        @Expose
        @ConfigOption(name = "Hideyho Finder", desc = "Helps you find where Hideyho is hiding: once he says \"No peeking!\", a route leads you past every spot he can hide.")
        @ConfigEditorBoolean
        public boolean hideyhoFinder = true;

        @Expose
        @ConfigOption(name = "Safari Checklist", desc = "Displays the Critter Safari unique shards caught during the current run.")
        @Accordion
        public Checklist checklist = new Checklist();

        @Expose
        @ConfigOption(name = "Critter Capsules", desc = "")
        @Accordion
        public CritterCapsules critterCapsules = new CritterCapsules();
    }

    public static final class Checklist {
        @Expose
        @ConfigOption(name = "Shard Checklist", desc = "Displays the Critter Safari unique shards caught during the current run. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Checklist Display", desc = "Select which Critter Safari shard checklist biomes to show.\n§eOnly Current §7shows a one-line summary for the other biomes.")
        @ConfigEditorDropdown
        public ChecklistDisplay runDisplay = ChecklistDisplay.ONLY_CURRENT;

        @Expose
        @ConfigOption(name = "Hide Caught Shards", desc = "Hide shards already caught during the current Critter Safari run.")
        @ConfigEditorBoolean
        public boolean hideCaught = true;

        @Expose
        @ConfigOption(name = "Show Shard Icons", desc = "Display an item icon next to each shard in the checklist.")
        @ConfigEditorBoolean
        public boolean showIcons = true;

        @Expose
        @ConfigOption(name = "Checklist Style", desc = "§eUnique Critters§7: a \"Unique Critters\" title, \"Cavern:\" headers and a green ✔ or red ✖ before each critter.\n§eSkyHanni§7: SkyHanni's \"Cavern - 3/9\" headers and \"- (✔) Name\" rows.")
        @ConfigEditorDropdown
        public ChecklistStyle style = ChecklistStyle.UNIQUE_CRITTERS;

        public enum ChecklistStyle {
            UNIQUE_CRITTERS("Uniques"), SKYHANNI("SkyHanni");

            private final String label;

            ChecklistStyle(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum ChecklistDisplay {
            ALL("All"), CURRENT_ON_TOP("Current First"), ONLY_CURRENT("Only Current");

            private final String label;

            ChecklistDisplay(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }
    }

    public static final class CritterCapsules {
        @Expose
        @ConfigOption(name = "Hide Flying Capsules", desc = "Hide thrown Critter Capsules while they are in the air. Masterful Critter Capsules are only hidden while they are close to you.")
        @ConfigEditorDropdown
        public FlyingMode flyingMode = FlyingMode.NEVER;

        @Expose
        @ConfigOption(name = "Close Distance", desc = "How close a flying capsule has to be to get hidden. Only used in the When Close mode.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 6f, minStep = 0.5f)
        public float closeDistance = 2f;

        @Expose
        @ConfigOption(name = "Hide Capsules on Ground", desc = "Hide Critter Capsules lying on the ground. Capsules you do not pick up return to your inventory on their own after a few seconds. Masterful Critter Capsules are never hidden.")
        @ConfigEditorBoolean
        public boolean hideOnGround = false;

        public enum FlyingMode {
            NEVER("Never"), WHEN_CLOSE("When Close"), ALWAYS("Always");

            private final String label;

            FlyingMode(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }
    }

    public static final class ShardTracker {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Track your current shards: how many you have in your Hunting Box and how many you need to max each one (or the amount a SkyShards recipe needs). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Select Shard Key", desc = "Press this key in your Hunting Box (or the Attribute Menu or Fusion Box) to track the hovered shard.")
        @ConfigEditorKeybind(defaultKey = -1)
        public int selectShardKeybind = -1;

        @ConfigOption(name = "SkyShards", desc = "Click this button to import a recipe from SkyShards (copy it there first).\nYou can also use §e/s2 importskyshards§7.")
        @ConfigEditorButton(buttonText = "Import")
        public Runnable skyShardsImport = ShardTracker_::importFromSkyShards;

        @ConfigOption(name = "SkyShards", desc = "Click this button to open SkyShards in the browser.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable openSkyShards = () -> com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create("https://skyshards.com/"));
    }

    /** Indirection so the config class loads without the tracker. */
    private static final class ShardTracker_ {
        static void importFromSkyShards() {
            ShardTrackerDisplay.importFromSkyShards();
        }
    }

    public static final class ProfitTracker {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Count all shards you get while hunting, with their value. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show When Pickup", desc = "Show the hunting tracker for a couple of seconds after hunting something.\n§eIgnored if Always Show is enabled.")
        @ConfigEditorBoolean
        public boolean showWhenPickup = true;

        @Expose
        @ConfigOption(name = "Always Show", desc = "Always show the tracker, regardless of what you are holding.\n§eIf enabled, ignore the Show When Pickup setting.")
        @ConfigEditorBoolean
        public boolean alwaysShow = false;

        @Expose
        @ConfigOption(name = "Tracker Settings", desc = "")
        @Accordion
        public TrackerSettings perTrackerConfig = new TrackerSettings();
    }

    public static final class TrackerSettings {
        @Expose
        @ConfigOption(name = "Display Mode", desc = "Show this session's shards, or every shard since you turned the tracker on.")
        @ConfigEditorDropdown
        public DisplayMode displayMode = DisplayMode.TOTAL;

        @Expose
        @ConfigOption(name = "Hide Cheap Items", desc = "Hide shards worth less than this many coins in total (0 shows every shard).")
        @ConfigEditorSlider(minValue = 0, maxValue = 500_000, minStep = 5_000)
        public float hideCheapBelow = 0;

        @Expose
        @ConfigOption(name = "Shown Lines", desc = "How many shard lines to show at most (the most valuable first).")
        @ConfigEditorSlider(minValue = 1, maxValue = 40, minStep = 1)
        public int maxLines = 15;

        @ConfigOption(name = "Reset", desc = "Reset what's shown now (the session or the total). Same as §e/s2 resethuntingtracker§7.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = () -> HuntingProfitTracker.reset();

        public enum DisplayMode {
            TOTAL("Total"), SESSION("This Session");

            private final String label;

            DisplayMode(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }
    }

    public static final class MobHighlights {
        @Expose
        @ConfigOption(name = "Birries Highlight", desc = "")
        @Accordion
        public Highlight birries = new Highlight("0:255:0:255:0");

        @Expose
        @ConfigOption(name = "Hideonleaf Highlight", desc = "")
        @Accordion
        public Highlight hideonleaf = new Highlight("0:255:255:0:255");

        @Expose
        @ConfigOption(name = "Hideonsun Highlight", desc = "")
        @Accordion
        public Highlight hideonsun = new Highlight("0:255:255:0:255");

        @Expose
        @ConfigOption(name = "Invisibug Highlight", desc = "")
        @Accordion
        public Highlight invisibug = new Highlight("0:255:0:255:255");
    }

    public static final class Highlight {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Highlight this mob when it's nearby.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Color", desc = "Color for the highlight.")
        @ConfigEditorColour
        public String color;

        public Highlight() {
            this("0:255:0:255:0");
        }

        Highlight(String color) {
            this.color = color;
        }
    }

    public static final class FusionKeybinds {
        @ConfigOption(name = "§cNotice", desc = "The keybinds below must be different, and won't work if you hold both at the same time.")
        @ConfigEditorInfoText
        public String notice = "";

        @Expose
        @ConfigOption(name = "Repeat Fusion Keybind", desc = "Keybind to repeat the previous fusion, in the Fusion Box.")
        @ConfigEditorKeybind(defaultKey = -1)
        public int repeatFusionKeybind = -1;

        @Expose
        @ConfigOption(name = "Confirm Fusion Keybind", desc = "Keybind to confirm the current fusion, in Confirm Fusion.")
        @ConfigEditorKeybind(defaultKey = -1)
        public int confirmFusionKeybind = -1;
    }
}
