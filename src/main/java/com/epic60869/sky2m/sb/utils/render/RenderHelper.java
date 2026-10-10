// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RenderHelper {
	private RenderHelper() {}

	public static Camera getCamera() {
		return Minecraft.getInstance().gameRenderer.mainCamera();
	}

	public static void assertOnRenderThread(String message) {
		if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			throw new IllegalStateException(message);
		}
	}

	public static void runOnRenderThread(Runnable runnable) {
		Minecraft.getInstance().execute(runnable);
	}

	public static AABB getBlockBoundingBox(BlockGetter level, BlockPos pos) {
		return getBlockBoundingBox(level, level.getBlockState(pos), pos);
	}

	public static AABB getBlockBoundingBox(BlockGetter level, BlockState state, BlockPos pos) {
		VoxelShape shape = state.getShape(level, pos);
		return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
	}
}
