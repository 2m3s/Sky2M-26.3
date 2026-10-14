package com.epic60869.sky2m.features.profileviewer.networth;

import java.util.List;
import java.util.Map;

/**
 * A pet's networth, ported from SkyHelper-Networth (MIT, https://github.com/Altpapier/SkyHelper-Networth, v2.11.1):
 * calculators/PetNetworthCalculator.js, helpers/PetNetworthHelper.js, handlers PetItem, SoulboundPetSkin and PetCandy,
 * and constants/pets.js. A pet is worth its level 1 / 100 (/ 200) prices, in between by its experience.
 */
final class SkyHelperPet {
    private static final Map<String, Integer> SPECIAL_LEVELS = Map.of("GOLDEN_DRAGON", 200, "JADE_DRAGON", 200, "ROSE_DRAGON", 200);
    private static final List<String> SOULBOUND_PETS = List.of("GRANDMA_WOLF", "KUUDRA", "BINGO");
    private static final List<String> BLOCKED_CANDY_REDUCE_PETS = List.of("ENDER_DRAGON", "GOLDEN_DRAGON", "SCATHA", "JADE_DRAGON", "ROSE_DRAGON");
    private static final Map<String, Integer> RARITY_OFFSET = Map.of("COMMON", 0, "UNCOMMON", 6, "RARE", 11, "EPIC", 16, "LEGENDARY", 20,
        "MYTHIC", 20, "SPECIAL", 16, "VERY_SPECIAL", 20);
    private static final double PET_CANDY = 0.65, SOULBOUND_PET_SKINS = 0.8, PET_ITEM = 1;
    private static final int[] LEVELS = buildLevels();

    private static int[] buildLevels() {
        int[] head = {100, 110, 120, 130, 145, 160, 175, 190, 210, 230, 250, 275, 300, 330, 360, 400, 440, 490, 540, 600, 660, 730, 800, 880,
            960, 1050, 1150, 1260, 1380, 1510, 1650, 1800, 1960, 2130, 2310, 2500, 2700, 2920, 3160, 3420, 3700, 4000, 4350, 4750, 5200, 5700,
            6300, 7000, 7800, 8700, 9700, 10800, 12000, 13300, 14700, 16200, 17800, 19500, 21300, 23200, 25200, 27400, 29800, 32400, 35200,
            38200, 41400, 44800, 48400, 52200, 56200, 60400, 64800, 69400, 74200, 79200, 84700, 90700, 97200, 104200, 111700, 119700, 128200,
            137200, 146700, 156700, 167700, 179700, 192700, 206700, 221700, 237700, 254700, 272700, 291700, 311700, 333700, 357700, 383700,
            411700, 441700, 476700, 516700, 561700, 611700, 666700, 726700, 791700, 861700, 936700, 1016700, 1101700, 1191700, 1286700,
            1386700, 1496700, 1616700, 1746700, 1886700};
        // The table then repeats 1,886,700 to 219 entries in all.
        int[] all = new int[219];
        for (int i = 0; i < all.length; i++) all[i] = i < head.length ? head[i] : 1886700;
        return all;
    }

    private static double xpToLevel100(String rarity) {
        Integer offset = rarity == null ? null : RARITY_OFFSET.get(rarity);
        if (offset == null) return Double.NaN;
        double total = 0;
        for (int i = offset; i < Math.min(LEVELS.length, offset + 99); i++) total += LEVELS[i];
        return total;
    }

    private final String type, tier, heldItem, skin;
    private final double exp, candyUsed;
    private int level;
    private double xpMax, xpMaxTo100;

    private SkyHelperPet(String type, String tier, double exp, String heldItem, String skin, double candyUsed) {
        this.type = type;
        this.tier = tier;
        this.exp = exp;
        this.heldItem = heldItem;
        this.skin = skin;
        this.candyUsed = candyUsed;
    }

    /** A pet from {@code pets_data.pets} or an item's {@code petInfo}; null without a type, tier and experience. */
    static SkyHelperPet of(Map<String, Object> pet) {
        Object type = pet.get("type"), tier = pet.get("tier"), exp = pet.get("exp");
        if (type == null || tier == null || exp == null) return null;
        String held = SkyHelperItem.truthy(pet.get("heldItem")) ? SkyHelperItem.str(pet.get("heldItem")) : null;
        String skin = SkyHelperItem.truthy(pet.get("skin")) ? SkyHelperItem.str(pet.get("skin")) : null;
        double candy = SkyHelperItem.num(pet.get("candyUsed"));
        return new SkyHelperPet(SkyHelperItem.str(type), SkyHelperItem.str(tier), SkyHelperItem.num(exp), held, skin, Double.isNaN(candy) ? 0 : candy);
    }

    private String tierBoostedName() {
        int t = SkyHelperItem.TIERS.indexOf(tier);
        int boosted = "PET_ITEM_TIER_BOOST".equals(heldItem) && !"PHOENIX".equals(type) ? t + 1 : t;
        return boosted >= 0 && boosted < SkyHelperItem.TIERS.size() ? SkyHelperItem.TIERS.get(boosted) : null;
    }

    private void computeLevel() {
        int max = SPECIAL_LEVELS.getOrDefault(type, 100);
        String rarity = "BINGO".equals(type) ? "COMMON" : tierBoostedName();
        Integer offset = rarity == null ? null : RARITY_OFFSET.get(rarity);
        // LEVELS.slice(offset, offset + max - 1); past its end (or with no offset) JavaScript adds undefined, giving NaN.
        int from = offset == null ? 0 : offset, to = offset == null ? 0 : Math.min(LEVELS.length, offset + max - 1);
        int lvl = 1;
        double total = 0;
        for (int i = 0; i < max; i++) {
            total += from + i < to ? LEVELS[from + i] : Double.NaN;
            if (total > exp) break;
            lvl++;
        }
        level = Math.min(lvl, max);
        xpMax = 0;
        for (int i = from; i < to; i++) xpMax += LEVELS[i];
        xpMaxTo100 = xpToLevel100(tierBoostedName());
    }

    double networth(Map<String, Double> prices) {
        computeLevel();
        String basePetId = tier + "_" + type;
        double lvl1 = SkyHelperItem.price(prices, "LVL_1_" + basePetId);
        double lvl100 = SkyHelperItem.price(prices, "LVL_100_" + basePetId);
        double lvl200 = SkyHelperItem.price(prices, "LVL_200_" + basePetId);
        if (skin != null) {
            String petId = basePetId + "_SKINNED_" + skin;
            lvl1 = Math.max(SkyHelperItem.price(prices, "LVL_1_" + petId), lvl1);
            lvl100 = Math.max(SkyHelperItem.price(prices, "LVL_100_" + petId), lvl100);
            lvl200 = Math.max(SkyHelperItem.price(prices, "LVL_200_" + petId), lvl200);
        }
        double basePrice = level <= 100 ? lvl100 : (lvl200 != 0 ? lvl200 : lvl100);
        if (level < 100 && xpMax != 0) {
            double formula = (lvl100 - lvl1) / xpMaxTo100;
            if (formula != 0 && !Double.isNaN(formula)) basePrice = formula * exp + lvl1;
        }
        if (level > 100 && level < 200) {
            double formula = (lvl200 - lvl100) / 100;
            if (formula != 0 && !Double.isNaN(formula)) basePrice = formula * (level - 100) + lvl100;
        }

        double price = 0;
        if (heldItem != null) price += SkyHelperItem.price(prices, heldItem) * PET_ITEM;
        if (skin != null && SOULBOUND_PETS.contains(type) && SkyHelperItem.has(prices, "PET_SKIN_" + skin)) {
            price += SkyHelperItem.price(prices, "PET_SKIN_" + skin) * SOULBOUND_PET_SKINS;
        }
        // Pet candy (last): candied pets are worth a little less.
        if (candyUsed > 0 && !BLOCKED_CANDY_REDUCE_PETS.contains(type) && exp - candyUsed * 1_000_000 < xpMax) {
            double reduce = Math.min(basePrice * (1 - PET_CANDY), level == 100 ? 5_000_000 : 2_500_000);
            price -= reduce;
        }
        double total = price + basePrice;
        return Double.isNaN(total) ? 0 : total;
    }
}
