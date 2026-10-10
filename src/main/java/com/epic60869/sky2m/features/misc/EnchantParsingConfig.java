package com.epic60869.sky2m.features.misc;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.minecraft.ChatFormatting;

/** SkyHanni's EnchantParsingConfig and AdvancedEnchantmentColors (LGPL-2.1). */
public final class EnchantParsingConfig {
    @Expose
    @ConfigOption(name = "Enable", desc = "Colour the enchants in item tooltips by how good their level is: perfect (max level, chroma by default), great, good and poor, with ultimate enchants bold. Turn this off if you want to use enchant parsing from other mods.")
    @ConfigEditorBoolean
    public boolean colorParsing = false;

    @Expose
    @ConfigOption(name = "Format", desc = "The way the enchants are formatted in the tooltip.")
    @ConfigEditorDropdown
    public EnchantFormat format = EnchantFormat.NORMAL;

    public enum EnchantFormat {
        NORMAL("Normal"),
        COMPRESSED("Compressed"),
        STACKED("Stacked");

        private final String displayName;

        EnchantFormat(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Expose
    @ConfigOption(name = "Ultimate Enchantment Color", desc = "The color the Ultimate enchantment will be. (Will always be bold)")
    @ConfigEditorDropdown
    public EnchantColour ultimateEnchantColor = EnchantColour.LIGHT_PURPLE;

    @Expose
    @ConfigOption(name = "Perfect Enchantment Color", desc = "The color an enchantment will be at max level.")
    @ConfigEditorDropdown
    public EnchantColour perfectEnchantColor = EnchantColour.CHROMA;

    @Expose
    @ConfigOption(name = "Perfect Enchantment Bold", desc = "Enchantments at max level will be bold.")
    @ConfigEditorBoolean
    public boolean boldPerfectEnchant = false;

    @Expose
    @ConfigOption(name = "Great Enchantment Color", desc = "The color an enchantment will be at a great level.")
    @ConfigEditorDropdown
    public EnchantColour greatEnchantColor = EnchantColour.GOLD;

    @Expose
    @ConfigOption(name = "Good Enchantment Color", desc = "The color an enchantment will be at a good level.")
    @ConfigEditorDropdown
    public EnchantColour goodEnchantColor = EnchantColour.BLUE;

    @Expose
    @ConfigOption(name = "Poor Enchantment Color", desc = "The color an enchantment will be at a poor level.")
    @ConfigEditorDropdown
    public EnchantColour poorEnchantColor = EnchantColour.GRAY;

    @Expose
    @ConfigOption(name = "Advanced Enchantment Colors", desc = "")
    @Accordion
    public Advanced advancedEnchantColors = new Advanced();

    @Expose
    @ConfigOption(name = "Hide Enchant Description", desc = "Hide the enchant description after each enchant if available.")
    @ConfigEditorBoolean
    public boolean hideEnchantDescriptions = false;

    @Expose
    @ConfigOption(name = "Stacking Enchant Progress", desc = "Shows the stacking enchant progress at the bottom of the lore.\n§eRequires Enchant Parsing to be enabled.")
    @ConfigEditorBoolean
    public boolean stackingEnchantProgress = true;

    public static final class Advanced {
        @Expose
        @ConfigOption(name = "Use Advanced Ultimate Color?", desc = "Enable this to override the color selected for Ultimate enchantments from above.")
        @ConfigEditorBoolean
        public boolean useAdvancedUltimateColor = false;

        @Expose
        @ConfigOption(name = "Advanced Ultimate Color", desc = "Select a custom color to use for Ultimate enchantments.")
        @ConfigEditorColour
        public String advancedUltimateColor = "0:255:255:255:85";

        @Expose
        @ConfigOption(name = "Use Advanced Perfect Color?", desc = "Enable this to override the color selected for perfect level enchantments from above.")
        @ConfigEditorBoolean
        public boolean useAdvancedPerfectColor = false;

        @Expose
        @ConfigOption(name = "Advanced Perfect Color", desc = "Select a custom color to use for perfect enchantments.")
        @ConfigEditorColour
        public String advancedPerfectColor = "0:255:255:255:85";

        @Expose
        @ConfigOption(name = "Use Advanced Great Color?", desc = "Enable this to override the color selected for great level enchantments from above.")
        @ConfigEditorBoolean
        public boolean useAdvancedGreatColor = false;

        @Expose
        @ConfigOption(name = "Advanced Great Color", desc = "Select a custom color to use for great enchantments.")
        @ConfigEditorColour
        public String advancedGreatColor = "0:255:255:255:85";

        @Expose
        @ConfigOption(name = "Use Advanced Good Color?", desc = "Enable this to override the color selected for good level enchantments from above.")
        @ConfigEditorBoolean
        public boolean useAdvancedGoodColor = false;

        @Expose
        @ConfigOption(name = "Advanced Good Color", desc = "Select a custom color to use for good enchantments.")
        @ConfigEditorColour
        public String advancedGoodColor = "0:255:255:255:85";

        @Expose
        @ConfigOption(name = "Use Advanced Poor Color?", desc = "Enable this to override the color selected for poor level enchantments from above.")
        @ConfigEditorBoolean
        public boolean useAdvancedPoorColor = false;

        @Expose
        @ConfigOption(name = "Advanced Poor Color", desc = "Select a custom color to use for poor enchantments.")
        @ConfigEditorColour
        public String advancedPoorColor = "0:255:255:255:85";
    }

    /** SkyHanni's LorenzColor choices: the chat colours plus Chroma (a moving rainbow). */
    public enum EnchantColour {
        BLACK("§0Black", ChatFormatting.BLACK),
        DARK_BLUE("§1Dark Blue", ChatFormatting.DARK_BLUE),
        DARK_GREEN("§2Dark Green", ChatFormatting.DARK_GREEN),
        DARK_AQUA("§3Dark Aqua", ChatFormatting.DARK_AQUA),
        DARK_RED("§4Dark Red", ChatFormatting.DARK_RED),
        DARK_PURPLE("§5Dark Purple", ChatFormatting.DARK_PURPLE),
        GOLD("§6Gold", ChatFormatting.GOLD),
        GRAY("§7Gray", ChatFormatting.GRAY),
        DARK_GRAY("§8Dark Gray", ChatFormatting.DARK_GRAY),
        BLUE("§9Blue", ChatFormatting.BLUE),
        GREEN("§aGreen", ChatFormatting.GREEN),
        AQUA("§bAqua", ChatFormatting.AQUA),
        RED("§cRed", ChatFormatting.RED),
        LIGHT_PURPLE("§dPink", ChatFormatting.LIGHT_PURPLE),
        YELLOW("§eYellow", ChatFormatting.YELLOW),
        WHITE("§fWhite", ChatFormatting.WHITE),
        CHROMA("§bC§ah§er§co§dm§9a", null);

        private final String displayName;
        public final ChatFormatting formatting;

        EnchantColour(String displayName, ChatFormatting formatting) {
            this.displayName = displayName;
            this.formatting = formatting;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
