package com.epic60869.sky2m.sim.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Stands in for Killer560's chunk cache, which Sky2M doesn't have: a chunk counts only while it is loaded. */
public final class SimChunks {

    private SimChunks() {
    }

    public static boolean isLoaded(Level level, BlockPos pos) {
        return isLoaded(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** True only when the chunk is actually loaded (not just "has a chunk" which on 26.2 always returns true). */
    public static boolean isLoaded(Level level, int cx, int cz) {
        return level != null && level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null;
    }
}
