package com.epic60869.sky2m.features.garden.visitor;

import net.minecraft.ChatFormatting;

/** SkyHanni's VisitorReward (LGPL-2.1): visitor rewards worth warning about and counting. */
public enum VisitorReward {
    FLOWERING_BOUQUET("FLOWERING_BOUQUET", "§9Flowering Bouquet"),
    OVERGROWN_GRASS("OVERGROWN_GRASS", "§9Overgrown Grass"),
    GREEN_BANDANA("GREEN_BANDANA", "§9Green Bandana"),
    DEDICATION("DEDICATION;4", "§9Dedication IV"),
    MUSIC_RUNE("MUSIC_RUNE;1", "§9Music Rune"),
    SPACE_HELMET("DCTR_SPACE_HELM", "§cSpace Helmet"),
    CULTIVATING("CULTIVATING;1", "§9Cultivating I"),
    REPLENISH("REPLENISH;1", "§9Replenish I"),
    DELICATE("DELICATE;5", "§9Delicate V"),
    COPPER_DYE("DYE_COPPER", "§8Copper Dye"),
    JUNGLE_KEY("JUNGLE_KEY", "§5Jungle Key"),
    FRUIT_BOWL("FRUIT_BOWL", "§9Fruit Bowl"),
    HARVEST_HARBINGER("POTION_HARVEST_HARBINGER;5", "§9Harvest Harbinger V"),
    HYPERCHARGE_CHIP("HYPERCHARGE_GARDEN_CHIP", "§9Hypercharge Chip"),
    QUICKDRAW_CHIP("QUICKDRAW_GARDEN_CHIP", "§9Quickdraw Chip"),
    FARMING_EXP_BOOST_EPIC("PET_ITEM_FARMING_SKILL_BOOST_EPIC", "§5Farming Exp Boost"),
    UNFULFILLED_JERRYSEED("UNFULFILLED_JERRYSEED", "§aUnfulfilled Jerryseed"),
    VOTER_BADGE("VOTER_BADGE", "§fVoter's Badge"),
    VOTER_BADGE_VIP("VOTER_BADGE_VIP", "§aVIP Voter's Badge"),
    VOTER_BADGE_ELITE("VOTER_BADGE_ELITE", "§9Elite Voter's Badge"),
    VOTER_BADGE_SUPREME("VOTER_BADGE_SUPREME", "§5Supreme Voter's Badge"),
    DYE_WILD_STRAWBERRY("DYE_WILD_STRAWBERRY", "§dWild Strawberry Dye"),
    VELVET_TOP_HAT("VELVET_TOP_HAT", "§9Velvet Top Hat"),
    CASHMERE_JACKET("CASHMERE_JACKET", "§9Cashmere Jacket"),
    SATIN_TROUSERS("SATIN_TROUSERS", "§9Satin Trousers"),
    OXFORD_SHOES("OXFORD_SHOES", "§9Oxford Shoes"),
    CARNIVAL_TICKET("CARNIVAL_TICKET", "§aCarnival Ticket"),
    VISITORS_GRATITUDE("VISITORS_GRATITUDE", "§fVisitors' Gratitude"),
    FARMING_CONTEST_DISPLAY("FARMING_CONTEST_DISPLAY", "§aFarming Contest Display"),
    ASTRONAUT_PERSONALITY("ASTRONAUT_PERSONALITY", "§fAstronaut Minion Skin"),
    FAST_FOOD_BARN_SKIN("FAST_FOOD_BARN_SKIN", "§6Fast Food Barn Skin"),
    JELLY_GREENHOUSE_SKIN("JELLY_GREENHOUSE_SKIN", "§6Jelly Garden Greenhouse Skin");

    /** The NEU id ("DEDICATION;4"). */
    public final String internalName;
    public final String displayName;

    VisitorReward(String internalName, String displayName) {
        this.internalName = internalName;
        this.displayName = displayName;
    }

    public static VisitorReward byInternalName(String internalName) {
        for (VisitorReward reward : values()) if (reward.internalName.equals(internalName)) return reward;
        return null;
    }

    /** The reward whose name a tooltip line shows ("Dedication IV", "Music Rune I"...), or null. */
    public static VisitorReward byName(String name) {
        String clean = ChatFormatting.stripFormatting(name).replace("◆ ", "").trim();
        for (VisitorReward reward : values()) {
            String rewardName = ChatFormatting.stripFormatting(reward.displayName);
            if (clean.equalsIgnoreCase(rewardName) || (reward == MUSIC_RUNE && clean.startsWith("Music Rune"))) return reward;
        }
        return null;
    }

    /** The statistics line for this reward (the names don't all match, as in SkyHanni's toStatsTextEntryOrNull). */
    public VisitorConfig.DropsStatistics.StatsEntry statsEntry() {
        return switch (this) {
            case DEDICATION -> VisitorConfig.DropsStatistics.StatsEntry.DEDICATION_IV;
            case MUSIC_RUNE -> VisitorConfig.DropsStatistics.StatsEntry.MUSIC_RUNE_I;
            case CULTIVATING -> VisitorConfig.DropsStatistics.StatsEntry.CULTIVATING_I;
            case REPLENISH -> VisitorConfig.DropsStatistics.StatsEntry.REPLENISH_I;
            default -> {
                try {
                    yield VisitorConfig.DropsStatistics.StatsEntry.valueOf(name());
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
