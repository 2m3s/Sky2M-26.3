package com.epic60869.sky2m.sim.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Stands in for Killer560's chunk cache, which Sky2M doesn't have: a chunk counts only while it is loaded. */
public final class SimChunks {

    private SimChunks() {
    }

    public static boolean isLoaded(Level level, BlockPos pos) {
        return level != null && level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
