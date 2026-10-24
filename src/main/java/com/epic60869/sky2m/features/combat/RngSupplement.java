package com.epic60869.sky2m.features.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Drops the NEU item list has no chances for, written by hand from the Hypixel SkyBlock wiki for the /s2 rng screen:
 * Garden pest rare drops (Overbloom, and Pet Luck for the Rat and Slug pets) and the Harvest Feast drops. Farming is
 * limited to those two groups; pest drops of 7% and more (other than the 15% in-season rare crops) are left out.
 * Sources: https://hypixelskyblock.minecraft.wiki/w/Pests (drop table and the pet formula
 * base x (1 + Overbloom / 100 + Pet Luck / 600)), https://hypixelskyblock.minecraft.wiki/w/Overbloom and
 * https://hypixelskyblock.minecraft.wiki/w/Rare_Crops (list of rare crops), https://hypixelskyblock.minecraft.wiki/w/Cactus_Flower
 * (0.006%, 1 in 18,000 crops, during the Harvest Feast). Seasonal rare crops are only listed here for the pests (15% in
 * season); the wiki's Rare Crops page gives no base chances for farming drops.
 */
final class RngSupplement {
    /** A hand-written drop: the formula decides which stats change it. */
    record Drop(String name, String source, double chance, RngCommand.Formula formula) {}

    private RngSupplement() {}

    private static void pest(List<Drop> out, String pest, String... items) {
        for (int i = 0; i < items.length; i += 2) {
            out.add(new Drop(items[i], "Pest: " + pest, Double.parseDouble(items[i + 1]) / 100.0, RngCommand.Formula.PEST));
        }
    }

    static List<Drop> drops() {
        List<Drop> out = new ArrayList<>();
        pest(out, "Fly", "Pretty Fly Vinyl", "5", "Beady Eyes", "3", "Enchanted Hay Bale", "0.75", "Cornucopia", "15");
        pest(out, "Cricket", "Cricket Choir Vinyl", "5", "Enchanted Golden Carrot", "0.75", "Chirping Stereo", "1", "Carrot Zest", "15");
        pest(out, "Locust", "Cicada Symphony Vinyl", "5", "Locust Larva", "4", "Enchanted Baked Potato", "0.75", "Deepfries", "15");
        pest(out, "Rat", "Rodent Revolution Vinyl", "5", "Polished Pumpkin", "0.75", "Aggourdian", "15");
        pest(out, "Mosquito", "Buzzin' Beats Vinyl", "5", "Clipped Wings", "2", "Enchanted Sugar Cane", "0.75", "Cane Knot", "15");
        pest(out, "Earthworm", "Earthworm Ensemble Vinyl", "5", "Bookworm's Favorite Book", "4", "Enchanted Melon", "0.75", "Melon Juice", "15");
        pest(out, "Mite", "DynaMITES Vinyl", "5", "Atmospheric Filter", "0.5", "Enchanted Cactus", "0.75", "Cactus Flower", "15");
        pest(out, "Moth", "Wings of Harmony Vinyl", "5", "Wriggling Larva", "1", "Enchanted Cookie", "0.75", "Designer Coffee Beans", "15");
        pest(out, "Slug", "Slow and Groovy Vinyl", "5", "Enchanted Red Mushroom Block", "0.375", "Enchanted Brown Mushroom Block", "0.375", "Feastfungus", "15");
        pest(out, "Beetle", "Not Just a Pest Vinyl", "5","Mutant Nether Wart", "0.75", "Botroot", "15");
        pest(out, "Dragonfly", "Imagine Dragonflies Vinyl", "5", "Vermin Vaporizer Chip", "2", "Compacted Sunflower", "0.75", "Salted Sunflower Seeds", "15");
        pest(out, "Firefly", "Firefly in the Hole Vinyl", "5", "Fire in a Bottle", "2", "Compacted Moonflower", "0.75", "Crystalized Moonlight", "15");
        pest(out, "Praying Mantis", "Pray For Me Vinyl", "5", "Mantid Claw", "2", "Compacted Wild Rose", "0.75", "Floral Gelatin", "15");
        pest(out, "Lunar Moth", "Dung Dye", "0.002");
        pest(out, "Field Mouse", "Squeaky Toy", "5", "Squeaky Mousemat", "1", "Dung Dye", "0.002");
        // Dung Dye on every other pest (all except the Field Mouse and Lunar Moth, which have their own line).
        for (String p : new String[] {"Fly", "Cricket", "Locust", "Rat", "Mosquito", "Earthworm", "Mite", "Moth", "Slug", "Beetle",
            "Dragonfly", "Firefly", "Praying Mantis"}) {
            pest(out, p, "Dung Dye", "0.0004");
        }
        out.add(new Drop("Rat Pet (Legendary)", "Pest: Rat", 0.004, RngCommand.Formula.PEST_PET));
        out.add(new Drop("Slug Pet (Epic)", "Pest: Slug", 0.01, RngCommand.Formula.PEST_PET));
        out.add(new Drop("Slug Pet (Legendary)", "Pest: Slug", 0.002, RngCommand.Formula.PEST_PET));
        // Harvest Feast drops from harvesting in-season crops (https://hypixelskyblock.minecraft.wiki/w/Harvest_Feast):
        // any eligible Rare Crop 0.006% (1 in 18,000) and Seasoning 0.04% (1 in 2,250). Overbloom applies to Rare Crops
        // (https://hypixelskyblock.minecraft.wiki/w/Overbloom); the wiki doesn't say whether it also applies to Seasoning,
        // which is a Rare Crop, so it is treated like the others.
        String[][] feast = {{"Cornucopia", "Wheat"}, {"Carrot Zest", "Carrot"}, {"Deepfries", "Potato"}, {"Aggourdian", "Pumpkin"},
            {"Cane Knot", "Sugar Cane"}, {"Melon Juice", "Melon Slice"}, {"Cactus Flower", "Cactus"},
            {"Designer Coffee Beans", "Cocoa Beans"}, {"Feastfungus", "Mushroom"}, {"Botroot", "Nether Wart"},
            {"Salted Sunflower Seeds", "Sunflower"}, {"Crystalized Moonlight", "Moonflower"}, {"Floral Gelatin", "Wild Rose"}};
        for (String[] f : feast) out.add(new Drop(f[0], "Harvest Feast: " + f[1], 0.00006, RngCommand.Formula.PEST));
        out.add(new Drop("Seasoning", "Harvest Feast: any in-season crop", 0.0004, RngCommand.Formula.PEST));
        return out;
    }
}
