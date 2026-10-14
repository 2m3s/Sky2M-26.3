/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560; see THIRD_PARTY_NOTICES.md.
 */
package com.epic60869.sky2m.sim.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;

/**
 * The sim's own etherwarp and teleport rays, for its singleplayer copies of the abilities: QUOI's voxel walk as
 * Killer560's {@code TeleportUtils} has it, cut down to what the sim's server side uses (no aiming or route planning).
 */
public final class SimRaycast {

    public static final int PASSABLE = 1;
    public static final int BLOCKS_FEET = 2;

    private static volatile int[] blockFlags;

    private SimRaycast() {
    }

    public record RaycastResult(boolean succeeded, BlockPos pos, BlockState state) {
        public static final RaycastResult NONE = new RaycastResult(false, null, null);
    }

    /** The modern sneaking eye height, which Hypixel's etherwarp casts from. */
    public static final double SNEAK_EYE = 1.27;

    public static double eyeHeight(boolean sneak) {
        return sneak ? SNEAK_EYE : 1.62;
    }

    public static Vec3 getLook(float yaw, float pitch) {
        double f2 = -Math.cos(-pitch * 0.017453292f);
        return new Vec3(Math.sin(-yaw * 0.017453292f - 3.1415927f) * f2, Math.sin(-pitch * 0.017453292f),
                Math.cos(-yaw * 0.017453292f - 3.1415927f) * f2);
    }

    private static int[] flags() {
        int[] f = blockFlags;
        if (f == null) {
            synchronized (SimRaycast.class) {
                if (blockFlags == null) {
                    blockFlags = buildFlags();
                }
                f = blockFlags;
            }
        }
        return f;
    }

    private static int[] buildFlags() {
        int[] out = new int[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            Block block = state.getBlock();
            boolean passable = block instanceof AirBlock
                    || block instanceof FlowerBlock || block instanceof TallGrassBlock || block instanceof BushBlock
                    || block instanceof TallFlowerBlock || block instanceof ShortDryGrassBlock
                    || block instanceof TorchBlock || block instanceof RedstoneTorchBlock
                    || block instanceof TripWireBlock || block instanceof TripWireHookBlock
                    || block instanceof RailBlock || block instanceof FireBlock || block instanceof VineBlock
                    || block instanceof LiquidBlock || block instanceof SaplingBlock
                    || block instanceof CropBlock || block instanceof StemBlock
                    || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock
                    || block instanceof SugarCaneBlock || block instanceof MushroomBlock || block instanceof NetherWartBlock
                    || block instanceof RedstoneWireBlock || block instanceof ComparatorBlock || block instanceof RepeaterBlock
                    || block instanceof SmallDripleafBlock || block instanceof BigDripleafStemBlock
                    || block instanceof DoublePlantBlock || block instanceof LeverBlock || block instanceof SnowLayerBlock
                    || block instanceof BubbleColumnBlock || block instanceof GrowingPlantBlock
                    || block instanceof PistonHeadBlock || block instanceof DryVegetationBlock
                    || block instanceof ButtonBlock || block instanceof LanternBlock
                    || block instanceof SkullBlock || block instanceof WallSkullBlock
                    || block instanceof LadderBlock || block instanceof FlowerPotBlock || block instanceof WebBlock
                    || block instanceof NetherPortalBlock || block instanceof CandleBlock;
            boolean blocksFeet = block instanceof SkullBlock || block instanceof WallSkullBlock
                    || block instanceof FlowerPotBlock || block instanceof LadderBlock;
            int id = Block.getId(state);
            if (id >= 0 && id < out.length) {
                out[id] = (passable ? PASSABLE : 0) | (blocksFeet ? BLOCKS_FEET : 0);
            }
        }
        return out;
    }

    static int flagsOf(BlockState state) {
        int[] f = flags();
        int id = Block.getId(state);
        return id >= 0 && id < f.length ? f[id] : 0;
    }


    /**
     * The same walk over a given level. The dungeon sim's server-side etherwarp ({@code roomsim.SimAbilities})
     * resolves a use packet with this against the integrated server's own level, so the sim's server and the
     * planner share one model of where a hop lands - the planner's model of Hypixel's server.
     */
    public static RaycastResult traverseVoxels(Level level, double x0, double y0, double z0, double x1, double y1, double z1,
                                               boolean etherwarp) {
        if (level == null) {
            return RaycastResult.NONE;
        }
        double x = Math.floor(x0);
        double y = Math.floor(y0);
        double z = Math.floor(z0);
        double endX = Math.floor(x1);
        double endY = Math.floor(y1);
        double endZ = Math.floor(z1);
        double dirX = x1 - x0;
        double dirY = y1 - y0;
        double dirZ = z1 - z0;
        int stepX = (int) Math.signum(dirX);
        int stepY = (int) Math.signum(dirY);
        int stepZ = (int) Math.signum(dirZ);
        double invDirX = dirX != 0.0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invDirY = dirY != 0.0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invDirZ = dirZ != 0.0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invDirX * stepX);
        double tDeltaY = Math.abs(invDirY * stepY);
        double tDeltaZ = Math.abs(invDirZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invDirX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invDirY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invDirZ);

        BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
        int lastChunkX = Integer.MIN_VALUE;
        int lastChunkZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;

        for (int iter = 0; iter < 1000; iter++) {
            mut.set(x, y, z);
            int cx = ((int) x) >> 4;
            int cz = ((int) z) >> 4;
            if (cx != lastChunkX || cz != lastChunkZ) {
                chunk = level.getChunk(cx, cz);
                lastChunkX = cx;
                lastChunkZ = cz;
            }
            if (chunk == null) {
                return RaycastResult.NONE;
            }
            BlockState state = chunk.getBlockState(mut);
            int id = Block.getId(state);
            boolean passable = (flagsOf(state) & PASSABLE) != 0;
            if ((etherwarp && !passable) || (!etherwarp && id != 0)) {
                BlockPos hitPos = mut.immutable();
                if (!etherwarp && passable) {
                    return new RaycastResult(false, hitPos, state);
                }
                double collisionTop = state.getCollisionShape(level, hitPos).max(Direction.Axis.Y);
                double clearanceBaseY = hitPos.getY() + Math.max(1.0, Math.ceil(collisionTop));
                mut.set(x, clearanceBaseY, z);
                int feet = flagsOf(chunk.getBlockState(mut));
                if ((feet & PASSABLE) == 0 || (feet & BLOCKS_FEET) != 0) {
                    return new RaycastResult(false, hitPos, state);
                }
                mut.set(x, clearanceBaseY + 1, z);
                int head = flagsOf(chunk.getBlockState(mut));
                if ((head & PASSABLE) == 0 || (head & BLOCKS_FEET) != 0) {
                    return new RaycastResult(false, hitPos, state);
                }
                return new RaycastResult(true, hitPos, state);
            }
            if (x == endX && y == endY && z == endZ) {
                return RaycastResult.NONE;
            }
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                tMaxX += tDeltaX;
                x += stepX;
            } else if (tMaxY <= tMaxZ) {
                tMaxY += tDeltaY;
                y += stepY;
            } else {
                tMaxZ += tDeltaZ;
                z += stepZ;
            }
        }
        return RaycastResult.NONE;
    }


    /** Where the feet land on an etherwarp onto {@code target}: the top of its collision box (at least a full block). */
    public static double standYOn(Level level, BlockPos target) {
        var shape = level.getBlockState(target).getCollisionShape(level, target);
        double top = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        return target.getY() + Math.max(1.0, Math.ceil(top));
    }

    private static final Set<UUID> WITHER_ESSENCE_PROFILE_IDS = Set.of(
            UUID.fromString("2865274b-3097-394e-8149-ec629c72d850"),
            UUID.fromString("e0f3e929-869e-3dca-9504-54c666ee6f23")
    );

    public static boolean isWitherEssence(BlockGetter level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof SkullBlockEntity skull)) {
            return false;
        }
        net.minecraft.world.item.component.ResolvableProfile owner = skull.getOwnerProfile();
        if (owner == null) {
            return false;
        }
        com.mojang.authlib.GameProfile partial = owner.partialProfile();
        return partial != null && partial.id() != null && WITHER_ESSENCE_PROFILE_IDS.contains(partial.id());
    }
}
