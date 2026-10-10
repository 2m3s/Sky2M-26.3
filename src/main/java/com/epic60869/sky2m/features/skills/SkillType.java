package com.epic60869.sky2m.features.skills;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.Locale;
import java.util.function.Supplier;

/** SkyHanni's SkillType (LGPL-2.1): every skill with its icon and level cap. */
public enum SkillType {
    COMBAT("Combat", () -> Items.GOLDEN_SWORD, 60),
    FARMING("Farming", () -> Items.GOLDEN_HOE, 60),
    FISHING("Fishing", () -> Items.FISHING_ROD, 50),
    MINING("Mining", () -> Items.GOLDEN_PICKAXE, 60),
    FORAGING("Foraging", () -> Items.GOLDEN_AXE, 57),
    ENCHANTING("Enchanting", Blocks.ENCHANTING_TABLE::asItem, 60),
    ALCHEMY("Alchemy", () -> Items.BREWING_STAND, 50),
    CARPENTRY("Carpentry", Blocks.CRAFTING_TABLE::asItem, 50),
    TAMING("Taming", () -> Items.POLAR_BEAR_SPAWN_EGG, 60),
    HUNTING("Hunting", () -> Items.LEAD, 50);

    public final String displayName;
    public final int maxLevel;
    public final String lowercaseName;
    private final Supplier<Item> icon;
    private ItemStack item;

    SkillType(String displayName, Supplier<Item> icon, int maxLevel) {
        this.displayName = displayName;
        this.icon = icon;
        this.maxLevel = maxLevel;
        this.lowercaseName = displayName.toLowerCase(Locale.ROOT);
    }

    public ItemStack item() {
        if (item == null) {
            item = new ItemStack(icon.get());
            item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(displayName));
        }
        return item;
    }

    /** Shown in the config's draggable list, as in SkyHanni ("§bCombat"). */
    @Override
    public String toString() {
        return "§b" + displayName;
    }

    public static SkillType getByNameOrNull(String name) {
        for (SkillType type : values()) if (type.displayName.equalsIgnoreCase(name)) return type;
        return null;
    }
}
