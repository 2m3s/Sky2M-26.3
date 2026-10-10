package com.epic60869.sky2m;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Locale;
import java.util.Set;

/**
 * An item's estimated value, like SkyHanni's and NEU's Estimated Item Value: what the base item sells for plus the
 * price of everything applied to it (recombobulator, potato books, enchantments, master stars, gemstones, scrolls,
 * dyes, skins and other upgrades), each at its bazaar insta-buy price or lowest BIN.
 */
final class EstimatedItemValue {
    /** Gemstone slot types whose gem is stored separately as "<slot>_gem". */
    private static final Set<String> UNIVERSAL_SLOTS = Set.of("COMBAT", "OFFENSIVE", "DEFENSIVE", "UNIVERSAL", "MINING", "CHISEL");
    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};

    private EstimatedItemValue() {}

    /** The base price plus the applied upgrades, or 0 when there's no base price. */
    static double estimate(String id, String apiId, CompoundTag data) {
        double total = Sky2MPriceTooltip.costPrice(apiId);
        if (total <= 0) return 0;

        if (data.getIntOr("rarity_upgrades", 0) > 0) total += price("RECOMBOBULATOR_3000");

        int potatoBooks = data.getIntOr("hot_potato_count", 0);
        total += Math.min(potatoBooks, 10) * price("HOT_POTATO_BOOK");
        total += Math.max(0, potatoBooks - 10) * price("FUMING_POTATO_BOOK");

        // An enchanted book's own enchantment is already its base price.
        if (!id.equals("ENCHANTED_BOOK")) {
            CompoundTag enchants = data.getCompoundOrEmpty("enchantments");
            for (String enchant : enchants.keySet()) {
                total += price("ENCHANTMENT_" + enchant.toUpperCase(Locale.ROOT) + "_" + enchants.getIntOr(enchant, 1));
            }
        }

        // Stars past 5 are master stars.
        int stars = Math.max(data.getIntOr("upgrade_level", 0), data.getIntOr("dungeon_item_level", 0));
        for (int i = 5; i < Math.min(stars, 10); i++) total += price(MASTER_STARS[i - 5]);

        CompoundTag gems = data.getCompoundOrEmpty("gems");
        for (String slot : gems.keySet()) {
            if (slot.endsWith("_gem") || slot.equals("unlocked_slots")) continue;
            String quality = gems.getString(slot).orElseGet(() -> gems.getCompoundOrEmpty(slot).getStringOr("quality", ""));
            if (quality.isEmpty()) continue;
            String type = slot.substring(0, Math.max(0, slot.lastIndexOf('_')));
            if (UNIVERSAL_SLOTS.contains(type)) type = gems.getStringOr(slot + "_gem", "");
            if (!type.isEmpty()) total += price(quality + "_" + type + "_GEM");
        }

        if (data.getIntOr("art_of_war_count", 0) > 0) total += price("THE_ART_OF_WAR");
        if (data.getIntOr("artOfPeaceApplied", 0) > 0) total += price("THE_ART_OF_PEACE");
        total += data.getIntOr("wood_singularity_count", 0) * price("WOOD_SINGULARITY");
        total += data.getIntOr("farming_for_dummies_count", 0) * price("FARMING_FOR_DUMMIES");
        total += data.getIntOr("polarvoid", 0) * price("POLARVOID_BOOK");
        total += data.getIntOr("tuned_transmission", 0) * price("TRANSMISSION_TUNER");
        total += data.getIntOr("mana_disintegrator_count", 0) * price("MANA_DISINTEGRATOR");
        if (data.getIntOr("jalapeno_count", 0) > 0) total += price("JALAPENO_BOOK");
        if (data.getIntOr("ethermerge", 0) > 0) total += price("ETHERWARP_CONDUIT");
        if (data.contains("stats_book")) total += price("BOOK_OF_STATS");
        if (data.getIntOr("divan_powder_coating", 0) > 0) total += price("DIVAN_POWDER_COATING");

        for (String key : new String[]{"dye_item", "skin", "power_ability_scroll", "drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module"}) {
            String item = data.getStringOr(key, "");
            if (!item.isEmpty()) total += price(item.toUpperCase(Locale.ROOT));
        }
        String enrichment = data.getStringOr("talisman_enrichment", "");
        if (!enrichment.isEmpty()) total += price("TALISMAN_ENRICHMENT_" + enrichment.toUpperCase(Locale.ROOT));

        ListTag scrolls = data.getListOrEmpty("ability_scroll");
        for (int i = 0; i < scrolls.size(); i++) total += price(scrolls.getStringOr(i, ""));

        // A rune applied to an item (rune items themselves are priced by their rune).
        if (!id.equals("RUNE") && !id.equals("UNIQUE_RUNE")) {
            CompoundTag runes = data.getCompoundOrEmpty("runes");
            for (String rune : runes.keySet()) total += price(rune + "_RUNE_" + runes.getIntOr(rune, 1));
        }
        return total;
    }

    private static double price(String id) {
        return id.isEmpty() ? 0 : Sky2MPriceTooltip.costPrice(id);
    }
}
