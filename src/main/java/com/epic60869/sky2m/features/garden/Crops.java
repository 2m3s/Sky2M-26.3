package com.epic60869.sky2m.features.garden;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The one list of farming crops: which blocks are crops, which collection (Hypixel collection id, as the Sky2M boards
 * and the collections resource use) a crop block or a farming tool is for. The Farming profit tracker and the Rival
 * HUD both read it, so they can't drift apart.
 */
public final class Crops {
    private Crops() {}

    /** Whether breaking this block is farming (the Farming profit tracker only counts these). */
    public static boolean isCrop(Block block) {
        return block == Blocks.WHEAT || block == Blocks.CARROTS || block == Blocks.POTATOES || block == Blocks.NETHER_WART
            || block == Blocks.PUMPKIN || block == Blocks.CARVED_PUMPKIN || block == Blocks.MELON || block == Blocks.COCOA
            || block == Blocks.SUGAR_CANE || block == Blocks.CACTUS || block == Blocks.RED_MUSHROOM
            || block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM_BLOCK || block == Blocks.BROWN_MUSHROOM_BLOCK
            || block == Blocks.SUNFLOWER || block == Blocks.ROSE_BUSH;
    }

    /** A crop block's collection ("WHEAT", "INK_SACK:3"), or null. */
    public static String collectionOf(Block block, long gameTime) {
        if (block == Blocks.WHEAT) return "WHEAT";
        if (block == Blocks.CARROTS) return "CARROT_ITEM";
        if (block == Blocks.POTATOES) return "POTATO_ITEM";
        if (block == Blocks.NETHER_WART) return "NETHER_STALK";
        if (block == Blocks.PUMPKIN || block == Blocks.CARVED_PUMPKIN) return "PUMPKIN";
        if (block == Blocks.MELON) return "MELON";
        if (block == Blocks.COCOA) return "INK_SACK:3";
        if (block == Blocks.SUGAR_CANE) return "SUGAR_CANE";
        if (block == Blocks.CACTUS) return "CACTUS";
        if (block == Blocks.RED_MUSHROOM || block == Blocks.BROWN_MUSHROOM
            || block == Blocks.RED_MUSHROOM_BLOCK || block == Blocks.BROWN_MUSHROOM_BLOCK) return "MUSHROOM_COLLECTION";
        if (block == Blocks.ROSE_BUSH) return "WILD_ROSE";
        if (block == Blocks.SUNFLOWER) return sunflower(gameTime);
        return null;
    }

    /** The Garden's sunflowers are Moonflowers at night: "MOONFLOWER" then, "DOUBLE_PLANT" (Sunflower) by day. */
    public static String sunflower(long gameTime) {
        return gameTime % 24_000L >= 12_000L ? "MOONFLOWER" : "DOUBLE_PLANT";
    }

    /** The crop a farming tool is for (Sky2MMouseLock's tool list), or null for other items and the generic ones. */
    public static String toolCollection(String id, long gameTime) {
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("THEORETICAL_HOE_")) {
            String crop = id.substring("THEORETICAL_HOE_".length()).replaceFirst("_\\d+$", "");
            return switch (crop) {
                case "WHEAT" -> "WHEAT";
                case "CARROT" -> "CARROT_ITEM";
                case "POTATO" -> "POTATO_ITEM";
                case "CANE" -> "SUGAR_CANE";
                case "WARTS" -> "NETHER_STALK";
                case "WILD_ROSE" -> "WILD_ROSE";
                case "SUNFLOWER" -> sunflower(gameTime);
                default -> null;
            };
        }
        if (id.startsWith("FUNGI_CUTTER")) return "MUSHROOM_COLLECTION";
        if (id.startsWith("CACTUS_KNIFE")) return "CACTUS";
        if (id.startsWith("MELON_DICER")) return "MELON";
        if (id.startsWith("PUMPKIN_DICER")) return "PUMPKIN";
        if (id.startsWith("COCO_CHOPPER")) return "INK_SACK:3";
        return null;
    }
}
