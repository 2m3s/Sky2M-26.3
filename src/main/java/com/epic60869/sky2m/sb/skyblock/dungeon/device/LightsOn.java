package com.epic60869.sky2m.sb.skyblock.dungeon.device;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import com.epic60869.sky2m.sb.annotations.Init;
import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonBoss;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.ColorUtils;
import com.epic60869.sky2m.sb.utils.Utils;
import com.epic60869.sky2m.sb.utils.render.LevelRenderExtractionCallback;
import com.epic60869.sky2m.sb.utils.render.RenderHelper;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;

public class LightsOn {
	private static final Minecraft CLIENT = Minecraft.getInstance();
	private static final BlockPos TOP_LEFT = new BlockPos(62, 136, 142);
	private static final BlockPos TOP_RIGHT = new BlockPos(58, 136, 142);
	private static final BlockPos MIDDLE_TOP = new BlockPos(60, 135, 142);
	private static final BlockPos MIDDLE_BOTTOM = new BlockPos(60, 134, 142);
	private static final BlockPos BOTTOM_LEFT = new BlockPos(62, 133, 142);
	private static final BlockPos BOTTOM_RIGHT = new BlockPos(58, 133, 142);
	private static final BlockPos[] LEVERS = { TOP_LEFT, TOP_RIGHT, MIDDLE_TOP, MIDDLE_BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT };
	private static final float[] RED = ColorUtils.getFloatComponents(DyeColor.RED);
	/**
	 * Higher than typical to ensure it stands out from redstone lamps that are off.
	 */
	private static final float ALPHA = 0.75f;

	@Init
	public static void init() {
		LevelRenderExtractionCallback.EVENT.register(LightsOn::extractRendering);
	}

	private static void extractRendering(PrimitiveCollector collector) {
		if (!shouldProcess()) return;

		for (BlockPos lever : LEVERS) {
			ClientLevel world = CLIENT.level;
			BlockState state = world.getBlockState(lever);

			if (state.getBlock().equals(Blocks.LEVER) && state.hasProperty(BlockStateProperties.POWERED) && !state.getValue(BlockStateProperties.POWERED)) {
				AABB box = RenderHelper.getBlockBoundingBox(world, state, lever);

				if (box != null) {
					collector.submitFilledBox(box, RED, ALPHA, false);
				}
			}
		}
	}

	private static boolean shouldProcess() {
		return SkyblockerConfigManager.get().dungeons.devices.solveLightsOn && Utils.isInDungeons() && DungeonManager.isInBoss()
				&& DungeonManager.getBoss() == DungeonBoss.MAXOR;
	}
}
