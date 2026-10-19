package com.epic60869.sky2m.features.skills;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Set;

/**
 * Mining, foraging and fishing next to {@link com.epic60869.sky2m.features.garden.Crops}: which collection a block you
 * break (or an item you fish up) counts for. Ids are the Hypixel collection ids from
 * api.hypixel.net/v2/resources/skyblock/collections, the same keys the Sky2M boards use.
 *
 * <p>Blocks are matched by registry path ("gray_wool"), so every colour of gemstone glass is one rule. Some blocks
 * count for a different collection on the mining islands: stone is Hard Stone there and Cobblestone on the Hub, and in
 * the Glacite Tunnels and Mineshafts cobblestone and clay are Tungsten, packed ice is Glacite, terracotta is Umber.
 */
public final class Gathering {
    private Gathering() {}

    /** Blocks that count for one collection wherever you break them. */
    private static final Map<String, String> MINING = Map.ofEntries(
        Map.entry("coal_ore", "COAL"), Map.entry("deepslate_coal_ore", "COAL"), Map.entry("coal_block", "COAL"),
        Map.entry("iron_ore", "IRON_INGOT"), Map.entry("deepslate_iron_ore", "IRON_INGOT"), Map.entry("iron_block", "IRON_INGOT"),
        Map.entry("gold_ore", "GOLD_INGOT"), Map.entry("deepslate_gold_ore", "GOLD_INGOT"), Map.entry("gold_block", "GOLD_INGOT"),
        Map.entry("diamond_ore", "DIAMOND"), Map.entry("deepslate_diamond_ore", "DIAMOND"), Map.entry("diamond_block", "DIAMOND"),
        Map.entry("emerald_ore", "EMERALD"), Map.entry("deepslate_emerald_ore", "EMERALD"), Map.entry("emerald_block", "EMERALD"),
        Map.entry("redstone_ore", "REDSTONE"), Map.entry("deepslate_redstone_ore", "REDSTONE"), Map.entry("redstone_block", "REDSTONE"),
        Map.entry("lapis_ore", "INK_SACK:4"), Map.entry("deepslate_lapis_ore", "INK_SACK:4"), Map.entry("lapis_block", "INK_SACK:4"),
        Map.entry("nether_quartz_ore", "QUARTZ"),
        Map.entry("obsidian", "OBSIDIAN"),
        Map.entry("glowstone", "GLOWSTONE_DUST"),
        Map.entry("gravel", "GRAVEL"),
        Map.entry("ice", "ICE"),
        Map.entry("netherrack", "NETHERRACK"),
        Map.entry("sand", "SAND"),
        Map.entry("red_sand", "SAND:1"),
        Map.entry("end_stone", "ENDER_STONE"),
        Map.entry("mycelium", "MYCEL"),
        Map.entry("sponge", "SULPHUR_ORE"),
        // Mithril: the grey and light blue wool, cyan terracotta and prismarine of the Dwarven Mines.
        Map.entry("gray_wool", "MITHRIL_ORE"), Map.entry("light_blue_wool", "MITHRIL_ORE"),
        Map.entry("cyan_terracotta", "MITHRIL_ORE"), Map.entry("prismarine", "MITHRIL_ORE"),
        Map.entry("prismarine_bricks", "MITHRIL_ORE"), Map.entry("dark_prismarine", "MITHRIL_ORE"));

    /** Logs and wood by tree (Galatea's Mangrove included; its Fig and other logs aren't plain blocks we can tell). */
    private static final Map<String, String> FORAGING = Map.ofEntries(
        Map.entry("oak", "LOG"), Map.entry("spruce", "LOG:1"), Map.entry("birch", "LOG:2"), Map.entry("jungle", "LOG:3"),
        Map.entry("acacia", "LOG_2"), Map.entry("dark_oak", "LOG_2:1"), Map.entry("mangrove", "MANGROVE_LOG"));

    /** Fishing collections; the item you fish up has the collection's own id. */
    private static final Set<String> FISHING = Set.of(
        "RAW_FISH", "RAW_FISH:1", "RAW_FISH:2", "RAW_FISH:3", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS", "CLAY_BALL",
        "WATER_LILY", "INK_SACK", "SPONGE", "LOTUS", "MAGMA_FISH");

    /** The mining collection a broken block counts for ("MITHRIL_ORE", "GEMSTONE_COLLECTION"), or null. */
    public static String miningCollection(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.endsWith("_stained_glass") || path.endsWith("_stained_glass_pane")) return "GEMSTONE_COLLECTION";
        boolean glacite = Sky2MLocation.inGlaciteTunnels() || Sky2MLocation.inMineshaft();
        if (glacite) {
            switch (path) {
                case "cobblestone", "cobblestone_stairs", "cobblestone_slab", "clay" -> { return "TUNGSTEN"; }
                case "packed_ice" -> { return "GLACITE"; }
                case "terracotta", "brown_terracotta", "smooth_red_sandstone" -> { return "UMBER"; }
                default -> {}
            }
        }
        if (path.equals("stone") || path.equals("infested_stone")) {
            return Sky2MLocation.inMiningIsland() ? "HARD_STONE" : "COBBLESTONE";
        }
        if (path.equals("cobblestone")) return "COBBLESTONE";
        return MINING.get(path);
    }

    /** The log collection a broken log or wood block counts for ("LOG:2"), or null. Stripped ones aren't trees. */
    public static String foragingCollection(Block block) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (path.startsWith("stripped_")) return null;
        String tree = path.endsWith("_log") ? path.substring(0, path.length() - 4)
            : path.endsWith("_wood") ? path.substring(0, path.length() - 5) : null;
        return tree == null ? null : FORAGING.get(tree);
    }

    /** Whether an item id (or collection id) is a fishing collection. */
    public static boolean isFishingCollection(String id) {
        return id != null && FISHING.contains(id);
    }
}
