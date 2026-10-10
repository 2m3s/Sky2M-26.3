package com.epic60869.sky2m.custom;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hypixel's item list still names materials the 1.8 way, some with a data value ("INK_SACK" with durability 10 is
 * lime dye, "RAW_FISH" 2 a clownfish). This turns them into today's item ids, so items are drawn as the right thing
 * instead of a barrier.
 */
public final class LegacyMaterials {
	private static final String[] COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
		"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
	private static final String[] DYES = {"ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye",
		"cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye",
		"orange_dye", "bone_meal"};
	private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

	/** Legacy name -> modern id, for materials without a data value that matters. */
	private static final Map<String, String> PLAIN = new HashMap<>();

	static {
		String[][] pairs = {
			{"empty_map", "map"}, {"carrot_stick", "carrot_on_a_stick"}, {"firework", "firework_rocket"},
			{"firework_charge", "firework_star"}, {"carrot_item", "carrot"}, {"potato_item", "potato"},
			{"exp_bottle", "experience_bottle"}, {"ender_stone", "end_stone"}, {"nether_stalk", "nether_wart"},
			{"water_lily", "lily_pad"}, {"yellow_flower", "dandelion"}, {"iron_plate", "heavy_weighted_pressure_plate"},
			{"gold_plate", "light_weighted_pressure_plate"}, {"stone_plate", "stone_pressure_plate"},
			{"wood_plate", "oak_pressure_plate"}, {"gold_record", "music_disc_13"}, {"green_record", "music_disc_cat"},
			{"record_3", "music_disc_blocks"}, {"record_4", "music_disc_chirp"}, {"record_5", "music_disc_far"},
			{"record_6", "music_disc_mall"}, {"record_7", "music_disc_mellohi"}, {"record_8", "music_disc_stal"},
			{"record_9", "music_disc_strad"}, {"record_10", "music_disc_ward"}, {"record_11", "music_disc_11"},
			{"record_12", "music_disc_wait"}, {"leash", "lead"}, {"melon_block", "melon"},
			{"speckled_melon", "glistering_melon_slice"}, {"redstone_comparator", "comparator"}, {"diode", "repeater"},
			{"gold_barding", "golden_horse_armor"}, {"iron_barding", "iron_horse_armor"},
			{"diamond_barding", "diamond_horse_armor"}, {"redstone_lamp_off", "redstone_lamp"},
			{"redstone_torch_on", "redstone_torch"}, {"mushroom_soup", "mushroom_stew"}, {"sulphur", "gunpowder"},
			{"boat", "oak_boat"}, {"watch", "clock"}, {"grilled_pork", "cooked_porkchop"}, {"pork", "porkchop"},
			{"raw_chicken", "chicken"}, {"raw_beef", "beef"}, {"trap_door", "oak_trapdoor"},
			{"flower_pot_item", "flower_pot"}, {"storage_minecart", "chest_minecart"},
			{"powered_minecart", "furnace_minecart"}, {"explosive_minecart", "tnt_minecart"},
			{"huge_mushroom_1", "brown_mushroom_block"}, {"huge_mushroom_2", "red_mushroom_block"},
			{"mycel", "mycelium"}, {"fence", "oak_fence"}, {"fence_gate", "oak_fence_gate"}, {"eye_of_ender", "ender_eye"},
			{"snow_ball", "snowball"}, {"web", "cobweb"}, {"seeds", "wheat_seeds"}, {"nether_brick_item", "nether_brick"},
			{"clay_brick", "brick"}, {"mob_spawner", "spawner"}, {"quartz_ore", "nether_quartz_ore"},
			{"spruce_wood_stairs", "spruce_stairs"}, {"birch_wood_stairs", "birch_stairs"},
			{"jungle_wood_stairs", "jungle_stairs"}, {"wood_stairs", "oak_stairs"}, {"smooth_stairs", "stone_brick_stairs"},
			{"spruce_door_item", "spruce_door"}, {"birch_door_item", "birch_door"}, {"jungle_door_item", "jungle_door"},
			{"acacia_door_item", "acacia_door"}, {"dark_oak_door_item", "dark_oak_door"}, {"wood_door", "oak_door"},
			{"cobble_wall", "cobblestone_wall"}, {"cauldron_item", "cauldron"}, {"piston_sticky_base", "sticky_piston"},
			{"piston_base", "piston"}, {"sign", "oak_sign"}, {"bed", "red_bed"}, {"iron_fence", "iron_bars"},
			{"thin_glass", "glass_pane"}, {"hard_clay", "terracotta"}, {"wood_button", "oak_button"},
			{"command", "command_block"}, {"rails", "rail"}, {"nether_fence", "nether_brick_fence"},
			{"book_and_quill", "writable_book"}, {"brewing_stand_item", "brewing_stand"},
			{"enchantment_table", "enchanting_table"}, {"workbench", "crafting_table"},
			{"ender_portal_frame", "end_portal_frame"}, {"grass", "grass_block"}, {"monster_egg", "pig_spawn_egg"},
			{"skull_item", "player_head"}, {"banner", "white_banner"},
		};
		for (String[] pair : pairs) PLAIN.put(pair[0], pair[1]);
	}

	private LegacyMaterials() {}

	/** Today's id for a 1.8 material and data value ("ink_sack", 10 -> "lime_dye"), or the material itself. */
	public static String modern(String material, int data) {
		String m = material.toLowerCase(Locale.ROOT);
		int colour = Math.floorMod(data, 16);
		String plain = PLAIN.get(m);
		if (plain != null) return m.equals("banner") ? COLOURS[15 - colour] + "_banner" : plain;
		// Tools and armour: GOLD_SWORD -> golden_sword, WOOD_AXE -> wooden_axe, IRON_SPADE -> iron_shovel.
		if (m.matches("(gold|wood|stone|iron|diamond)_(sword|axe|pickaxe|spade|hoe|helmet|chestplate|leggings|boots)")) {
			String[] parts = m.split("_");
			String tier = switch (parts[0]) {
				case "gold" -> "golden";
				case "wood" -> "wooden";
				default -> parts[0];
			};
			return tier + "_" + (parts[1].equals("spade") ? "shovel" : parts[1]);
		}
		return switch (m) {
			case "ink_sack" -> DYES[colour];
			case "raw_fish" -> switch (data) {
				case 1 -> "salmon";
				case 2 -> "tropical_fish";
				case 3 -> "pufferfish";
				default -> "cod";
			};
			case "cooked_fish" -> data == 1 ? "cooked_salmon" : "cooked_cod";
			case "wool" -> COLOURS[colour] + "_wool";
			case "carpet" -> COLOURS[colour] + "_carpet";
			case "stained_clay" -> COLOURS[colour] + "_terracotta";
			case "stained_glass" -> COLOURS[colour] + "_stained_glass";
			case "stained_glass_pane" -> COLOURS[colour] + "_stained_glass_pane";
			case "red_rose" -> switch (data) {
				case 1 -> "blue_orchid";
				case 2 -> "allium";
				case 3 -> "azure_bluet";
				case 4 -> "red_tulip";
				case 5 -> "orange_tulip";
				case 6 -> "white_tulip";
				case 7 -> "pink_tulip";
				case 8 -> "oxeye_daisy";
				default -> "poppy";
			};
			case "double_plant" -> switch (data) {
				case 1 -> "lilac";
				case 2 -> "tall_grass";
				case 3 -> "large_fern";
				case 4 -> "rose_bush";
				case 5 -> "peony";
				default -> "sunflower";
			};
			case "long_grass" -> data == 2 ? "fern" : "short_grass";
			case "log" -> WOODS[Math.min(data & 3, 3)] + "_log";
			case "log_2" -> WOODS[4 + Math.min(data & 1, 1)] + "_log";
			case "leaves" -> WOODS[Math.min(data & 3, 3)] + "_leaves";
			case "leaves_2" -> WOODS[4 + Math.min(data & 1, 1)] + "_leaves";
			case "wood" -> WOODS[Math.min(data, 5)] + "_planks";
			case "sapling" -> WOODS[Math.min(data, 5)] + "_sapling";
			case "wood_step" -> WOODS[Math.min(data & 7, 5)] + "_slab";
			case "step" -> switch (data & 7) {
				case 1 -> "sandstone_slab";
				case 3 -> "cobblestone_slab";
				case 4 -> "brick_slab";
				case 5 -> "stone_brick_slab";
				case 6 -> "nether_brick_slab";
				case 7 -> "quartz_slab";
				default -> "smooth_stone_slab";
			};
			case "smooth_brick" -> switch (data) {
				case 1 -> "mossy_stone_bricks";
				case 2 -> "cracked_stone_bricks";
				case 3 -> "chiseled_stone_bricks";
				default -> "stone_bricks";
			};
			default -> m;
		};
	}
}
