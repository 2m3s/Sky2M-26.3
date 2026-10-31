package com.epic60869.sky2m.features.misc.visual;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Misc > Etherwarp Overlay, ported from Odin's Etherwarp (https://github.com/odtheking/Odin,
 * features/impl/render/Etherwarp.kt, BSD-3-Clause): while you sneak with an etherwarp item (or hold an Etherwarp
 * Conduit), a box on the block you'd teleport to, in another colour when the teleport would fail. The ray walk is
 * Bloom's voxel traversal from Odin.
 */
public final class EtherwarpOverlay {
    public enum Style {
        FILLED_OUTLINE("Both"), OUTLINE("Outline"), FILLED("Filled");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record EtherPos(boolean succeeded, BlockPos pos) {}

    private static final int PASSABLE = 1;
    private static final int BLOCKS_FEET = 2;
    private static int[] blockFlags;

    private static ItemStack cachedItem;
    private static CompoundTag cachedData;

    private EtherwarpOverlay() {}

    private static FeatureConfigs.EtherwarpOverlay config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.etherwarpOverlay;
    }

    public static void init() {
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.EtherwarpOverlay c = config();
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (c == null || !c.enabled || player == null || mc.level == null || mc.gui.screen() != null) return;
            ItemStack held = player.getMainHandItem();
            if (cachedItem != held) {
                cachedItem = held;
                cachedData = etherwarpData(held);
            }
            if (cachedData == null) return;
            boolean conduit = cachedData.getStringOr("id", "").equals("ETHERWARP_CONDUIT");
            if (!player.isShiftKeyDown() && !conduit) return;
            double distance = 57.0 + cachedData.getIntOr("tuned_transmission", 0);
            EtherPos ether = etherPos(mc.level, player, c.useServerPosition ? player.oldPosition() : player.position(), distance);
            if (ether == null || ether.pos() == null || (!ether.succeeded() && !c.showFail)) return;
            int argb = colour(ether.succeeded() ? c.colour : c.failColour, ether.succeeded() ? 0xD9FFAA00 : 0xD9FF5555);
            float[] rgb = {(argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f};
            float alpha = (argb >>> 24) / 255f;
            AABB box = c.fullBlock ? new AABB(ether.pos()) : bounds(mc.level, ether.pos());
            if (c.style != Style.OUTLINE) collector.submitFilledBox(box, rgb, alpha * 0.4f, c.throughWalls);
            if (c.style != Style.FILLED) collector.submitOutlinedBox(box, rgb, alpha, 2f, c.throughWalls);
        });
    }

    /** The item's SkyBlock data if it can etherwarp (Odin's isEtherwarpItem), else null. */
    private static CompoundTag etherwarpData(ItemStack stack) {
        if (stack.isEmpty()) return null;
        // Read without copying: this runs every frame.
        CompoundTag data = Compat.customDataView(stack);
        if (data.getIntOr("ethermerge", 0) == 1 || data.getStringOr("id", "").equals("ETHERWARP_CONDUIT")) return data;
        return null;
    }

    private static AABB bounds(ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) return new AABB(pos);
        return shape.bounds().move(pos);
    }

    private static EtherPos etherPos(ClientLevel level, LocalPlayer player, Vec3 position, double distance) {
        double eyeHeight = player.getPose() == Pose.SWIMMING ? 0.4 : player.isCrouching() ? 1.27 : 1.62;
        Vec3 start = position.add(0, eyeHeight, 0);
        Vec3 end = player.getLookAngle().scale(distance).add(start);
        return traverse(level, start, end);
    }

    /** The first solid block from start to end, and whether you'd fit on top of it (Bloom's traversal, from Odin). */
    private static EtherPos traverse(ClientLevel level, Vec3 start, Vec3 end) {
        int[] flags = flags();
        double x0 = start.x, y0 = start.y, z0 = start.z;
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int endX = (int) Math.floor(end.x), endY = (int) Math.floor(end.y), endZ = (int) Math.floor(end.z);
        double dirX = end.x - x0, dirY = end.y - y0, dirZ = end.z - z0;
        int stepX = (int) Math.signum(dirX), stepY = (int) Math.signum(dirY), stepZ = (int) Math.signum(dirZ);
        double invX = dirX != 0 ? 1.0 / dirX : Double.MAX_VALUE;
        double invY = dirY != 0 ? 1.0 / dirY : Double.MAX_VALUE;
        double invZ = dirZ != 0 ? 1.0 / dirZ : Double.MAX_VALUE;
        double tDeltaX = Math.abs(invX * stepX), tDeltaY = Math.abs(invY * stepY), tDeltaZ = Math.abs(invZ * stepZ);
        double tMaxX = Math.abs((x + Math.max(stepX, 0) - x0) * invX);
        double tMaxY = Math.abs((y + Math.max(stepY, 0) - y0) * invY);
        double tMaxZ = Math.abs((z + Math.max(stepZ, 0) - z0) * invZ);

        for (int i = 0; i < 1000; i++) {
            BlockPos pos = new BlockPos(x, y, z);
            LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
            BlockState state = chunk.getBlockState(pos);
            int id = Block.getId(state);
            if ((flags[id] & PASSABLE) == 0) {
                double top = state.getCollisionShape(level, pos).max(Direction.Axis.Y);
                int baseY = y + Math.max(1, (int) Math.ceil(top));
                int feet = flags[Block.getId(chunk.getBlockState(new BlockPos(x, baseY, z)))];
                if ((feet & PASSABLE) == 0 || (feet & BLOCKS_FEET) != 0) return new EtherPos(false, pos);
                int head = flags[Block.getId(chunk.getBlockState(new BlockPos(x, baseY + 1, z)))];
                if ((head & PASSABLE) == 0 || (head & BLOCKS_FEET) != 0) return new EtherPos(false, pos);
                return new EtherPos(true, pos);
            }
            if (x == endX && y == endY && z == endZ) return null;
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
        return null;
    }

    /** Per block state: whether the ray passes through it and whether you can't stand in it (Odin's blockFlags). */
    private static int[] flags() {
        if (blockFlags != null) return blockFlags;
        int[] flags = new int[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            Block block = state.getBlock();
            boolean passable = block instanceof AirBlock || block instanceof FlowerBlock || block instanceof TallGrassBlock
                || block instanceof BushBlock || block instanceof TallFlowerBlock || block instanceof ShortDryGrassBlock
                || block instanceof TorchBlock || block instanceof RedstoneTorchBlock || block instanceof TripWireBlock
                || block instanceof TripWireHookBlock || block instanceof RailBlock || block instanceof FireBlock
                || block instanceof VineBlock || block instanceof LiquidBlock || block instanceof SaplingBlock
                || block instanceof CropBlock || block instanceof StemBlock || block instanceof SeagrassBlock
                || block instanceof TallSeagrassBlock || block instanceof SugarCaneBlock || block instanceof MushroomBlock
                || block instanceof NetherWartBlock || block instanceof RedstoneWireBlock || block instanceof ComparatorBlock
                || block instanceof RepeaterBlock || block instanceof SmallDripleafBlock || block instanceof BigDripleafStemBlock
                || block instanceof DoublePlantBlock || block instanceof LeverBlock || block instanceof SnowLayerBlock
                || block instanceof BubbleColumnBlock || block instanceof GrowingPlantBlock || block instanceof PistonHeadBlock
                || block instanceof DryVegetationBlock || block instanceof ButtonBlock || block instanceof LanternBlock
                || block instanceof SkullBlock || block instanceof WallSkullBlock || block instanceof LadderBlock
                || block instanceof FlowerPotBlock || block instanceof WebBlock || block instanceof NetherPortalBlock;
            boolean blocksFeet = block instanceof SkullBlock || block instanceof WallSkullBlock || block instanceof FlowerPotBlock
                || block instanceof LadderBlock || block instanceof VineBlock;
            flags[Block.getId(state)] = (passable ? PASSABLE : 0) | (blocksFeet ? BLOCKS_FEET : 0);
        }
        blockFlags = flags;
        return flags;
    }

    private static int colour(String value, int fallback) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
    }
}
