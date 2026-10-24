package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.garden.Crops;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;

import java.util.Locale;

/**
 * SkyHanni's CropType (https://github.com/hannibal002/SkyHanni, LGPL-2.1): the crops of the Garden with their base
 * drops, the optimal mousemat angles and optimal speeds from its defaults, and the bazaar item their price is taken from.
 */
public enum GardenCrop {
    WHEAT("Wheat", "WHEAT", 1.0, 90f, 0f, 93, "HAY_BLOCK", 9),
    CARROT("Carrot", "CARROT_ITEM", 3.0, -90f, 2.8f, 93, "ENCHANTED_CARROT", 160),
    POTATO("Potato", "POTATO_ITEM", 3.0, -90f, 2.8f, 93, "ENCHANTED_POTATO", 160),
    NETHER_WART("Nether Wart", "NETHER_STALK", 2.5, 90f, 0f, 93, "ENCHANTED_NETHER_STALK", 160),
    PUMPKIN("Pumpkin", "PUMPKIN", 1.0, 90f, -58.5f, 155, "ENCHANTED_PUMPKIN", 160),
    MELON("Melon", "MELON", 5.0, 90f, -58.5f, 155, "ENCHANTED_MELON", 160),
    COCOA_BEANS("Cocoa Beans", "INK_SACK:3", 3.0, 180f, -45f, 155, "ENCHANTED_COCOA", 160),
    SUGAR_CANE("Sugar Cane", "SUGAR_CANE", 2.0, -135f, 0f, 328, "ENCHANTED_SUGAR", 160),
    CACTUS("Cactus", "CACTUS", 2.0, -90f, 0f, 464, "ENCHANTED_CACTUS_GREEN", 160),
    MUSHROOM("Mushroom", "MUSHROOM_COLLECTION", 1.0, 116.5f, 0f, 233, "ENCHANTED_RED_MUSHROOM", 160),
    SUNFLOWER("Sunflower", "DOUBLE_PLANT", 2.0, 45f, 0f, 328, "DOUBLE_PLANT", 1),
    MOONFLOWER("Moonflower", "MOONFLOWER", 2.0, 45f, 0f, 328, "MOONFLOWER", 1),
    WILD_ROSE("Wild Rose", "WILD_ROSE", 2.0, 45f, 0f, 328, "WILD_ROSE", 1);

    public final String displayName;
    /** The Hypixel collection id the rest of Sky2M uses (see {@link Crops}). */
    public final String collection;
    public final double baseDrops;
    public final float yaw;
    public final float pitch;
    public final int speed;
    public final String priceItem;
    public final int priceDivisor;

    GardenCrop(String displayName, String collection, double baseDrops, float yaw, float pitch, int speed, String priceItem, int priceDivisor) {
        this.displayName = displayName;
        this.collection = collection;
        this.baseDrops = baseDrops;
        this.yaw = yaw;
        this.pitch = pitch;
        this.speed = speed;
        this.priceItem = priceItem;
        this.priceDivisor = priceDivisor;
    }

    public static GardenCrop byCollection(String collection) {
        if (collection == null) return null;
        for (GardenCrop crop : values()) if (crop.collection.equals(collection)) return crop;
        return null;
    }

    /** The crop a block is, or null. */
    public static GardenCrop ofBlock(Block block, long gameTime) {
        return byCollection(Crops.collectionOf(block, gameTime));
    }

    /** The crop of the farming tool in your hand, or null. */
    public static GardenCrop held() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        return byCollection(Crops.toolCollection(Compat.neuName(mc.player.getMainHandItem()), mc.level.getGameTime()));
    }

    /** A name as the tab list, scoreboard or menus write it ("Melon Slice", "Wild Rose"), or null. */
    public static GardenCrop byName(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) return null;
        if (n.equals("seeds")) return WHEAT;
        if (n.contains("mushroom")) return MUSHROOM;
        if (n.startsWith("melon")) return MELON;
        for (GardenCrop crop : values()) {
            if (crop.displayName.toLowerCase(Locale.ROOT).equals(n)) return crop;
        }
        return null;
    }

    /** SkyHanni's milestone data key (the keys of Garden.json's crop_milestones). */
    public String milestoneKey() {
        return name();
    }
}
