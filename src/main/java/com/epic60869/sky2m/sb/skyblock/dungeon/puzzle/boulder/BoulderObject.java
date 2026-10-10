package com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.boulder;

import net.minecraft.core.BlockPos;

public record BoulderObject(int x, int y, int z, String type) {
	public BlockPos get3DPosition() {
		return new BlockPos(x, y, z);
	}
}
