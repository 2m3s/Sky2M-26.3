package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * The terminal drill's room ({@link SimTerms}): Goldor's P3 ring, hand-built in code.
 *
 * <h2>At Hypixel's own coordinates</h2>
 * The drill is NOT moved far away like the boss arenas ({@link SimBossArena}): the mod's P3 features only react at
 * the F7 boss room's fixed coordinates - Simon Says' buttons at x 110, Arrow Align's frames at x -2, the Sharp
 * Shooter targets at z 50 ({@code OdinDevices}), the terminal stands and gates ({@code F7Extras}), the P3 sections
 * and phase-by-height. Building it anywhere else would leave every solver blind. That spot is free in the sim
 * world: the dungeon grid sits at -185..+6 on both axes and the ring starts at z 29, so the two never share a
 * block, and the floor build's clear never reaches it (it only sweeps the grid's own chunks). Nothing here adds
 * {@link SimAltitude}'s offset for the same reason: the coordinates are the point.
 *
 * <h2>The layout (approximated)</h2>
 * Hypixel's P3 is a tall, cluttered ring around the core; this is a flat one with the same footprint: a floor at
 * y 107, stone brick walls to y 135, the four sections as NoammAddons boxes them (S1 east, S2 north, S3 west, S4
 * south), and a wall across the ring between each pair with the gate at Hypixel's box, cracked stone bricks so
 * F7Extras' gate highlight finds it. The gate is taken down to the floor so the next section can be walked into
 * (Hypixel's gate sits higher, over a drop). Terminals stand at their real spots on pillars with a ladder; the
 * devices get platforms at their real heights. The S4-to-S1 wall never opens, and the core room behind the inner
 * wall of S4 opens at the end.
 */
final class SimTermBuild {

    static final int MIN_X = -4, MAX_X = 112, MIN_Z = 29, MAX_Z = 144;
    static final int FLOOR_Y = 107, TOP_Y = 135;
    /** Where the drill starts: the south end of section 1, facing along it. */
    static final Vec3 SPAWN = new Vec3(102.5, 108, 36.5);
    static final float SPAWN_YAW = 0f;
    /** The whole build, for sweeping its entities. */
    static final AABB AREA = new AABB(MIN_X, FLOOR_Y - 1, MIN_Z, MAX_X + 1, TOP_Y + 2, MAX_Z + 1);

    /** Hypixel's terminal spots by section (NoammAddons' Terminal ESP, as F7Extras keeps them). */
    static final int[][][] TERMINALS = {
        {{110, 113, 73}, {110, 119, 79}, {90, 112, 92}, {90, 122, 101}},
        {{68, 109, 122}, {59, 119, 123}, {47, 109, 122}, {39, 108, 142}, {40, 124, 123}},
        {{-2, 109, 112}, {-2, 119, 93}, {18, 123, 93}, {-2, 109, 77}},
        {{41, 109, 30}, {44, 121, 30}, {67, 109, 30}, {72, 114, 47}},
    };

    /**
     * Each terminal's command block (same order as {@link #TERMINALS}): on Hypixel the terminal is a command block
     * with its "Inactive Terminal" stand beside it, and a right click on either opens it. Read from the F7 world
     * save ({@code necron.json}, 14 command blocks one block from a stand); the save stops at x 0, so section 3's
     * three west ones are put on the west wall (x -3) the way section 1's are on the east wall (x 111).
     */
    static final int[][][] TERMINAL_BLOCKS = {
        {{111, 113, 73}, {111, 119, 79}, {89, 112, 92}, {89, 122, 101}},
        {{68, 109, 121}, {59, 120, 122}, {47, 109, 121}, {39, 108, 143}, {40, 124, 122}},
        {{-3, 109, 112}, {-3, 119, 93}, {19, 123, 93}, {-3, 109, 77}},
        {{41, 109, 29}, {44, 121, 29}, {67, 109, 29}, {72, 115, 48}},
    };

    /** A terminal's command block, as Hypixel's (facing down). */
    static BlockState terminalBlock() {
        return Blocks.COMMAND_BLOCK.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.DOWN);
    }

    /**
     * Two levers per section, {x, y, z, facing}. Hypixel's levers sit up on the structure; these are on the outer
     * wall at waist height, an approximation (nothing in the mod reads where a lever is).
     */
    static final int[][] LEVERS = {
        {111, 110, 60, 4}, {111, 110, 110, 4},
        {80, 110, 143, 2}, {25, 110, 143, 2},
        {-3, 110, 130, 5}, {-3, 110, 62, 5},
        {30, 110, 30, 3}, {85, 110, 30, 3},
    };

    /** The gates after sections 1-3, {x0, x1, z0, z1}, from y 108 to 134 (F7Extras' boxes, taken down to the floor). */
    private static final int[][] GATES = {{95, 106, 122, 124}, {17, 19, 127, 138}, {1, 14, 49, 51}};
    private static final int GATE_TOP = 134;
    /** The core entrance: a door in the inner wall of section 4. */
    private static final int CORE_X0 = 52, CORE_X1 = 56, CORE_TOP = 112;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState();
    private static final BlockState LIGHT = Blocks.SEA_LANTERN.defaultBlockState();
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState GATE = Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
    private static final BlockState CORE_DOOR = Blocks.GOLD_BLOCK.defaultBlockState();
    private static final BlockState PILLAR = Blocks.QUARTZ_BLOCK.defaultBlockState();

    /** The fixed structures over the plain ring: pillars, ladders, platforms, the devices' walls. */
    private static final Map<Long, BlockState> SPECIALS = new HashMap<>();

    static {
        for (int s = 0; s < TERMINALS.length; s++) {
            for (int[] t : TERMINALS[s]) {
                Direction toward = towardCorridor(s, t[0], t[2]);
                if (t[1] > FLOOR_Y + 1) {
                    column(t[0], t[2], FLOOR_Y + 1, t[1] - 1, PILLAR);
                    ladder(t[0] + toward.getStepX(), t[2] + toward.getStepZ(), t[1] - 1, toward);
                }
            }
        }
        for (int[] l : LEVERS) {
            put(l[0], l[1], l[2], lever(Direction.from3DDataValue(l[3]), false));
        }
        // S1, Simon Says: the obsidian wall at x 111 (its 4x4 lanterns at y 120-123, z 92-95) and a platform at
        // y 119 to press the buttons at x 110 from.
        for (int y = 119; y <= 124; y++) {
            for (int z = 89; z <= 97; z++) {
                put(111, y, z, Blocks.OBSIDIAN.defaultBlockState());
            }
        }
        platform(104, 109, 88, 98, 119);
        column(104, 93, FLOOR_Y + 1, 118, PILLAR);
        ladder(103, 93, 119, Direction.WEST);
        // S2, the lights device: twelve redstone lamps on the north wall, spaced so a lever lights only its own.
        for (int i = 0; i < 12; i++) {
            put(SimDevices.Lights.lampX(i), SimDevices.Lights.lampY(i), SimDevices.Lights.LAMP_Z, Blocks.REDSTONE_LAMP.defaultBlockState());
        }
        // S3, Arrow Align: the backing for the 5x5 frames at x -2 (y 120-124, z 75-79) and a platform at y 119.
        for (int y = 120; y <= 124; y++) {
            for (int z = 75; z <= 79; z++) {
                put(-3, y, z, Blocks.QUARTZ_BLOCK.defaultBlockState());
            }
        }
        platform(-2, 2, 72, 82, 119);
        column(2, 77, FLOOR_Y + 1, 118, PILLAR);
        ladder(3, 77, 119, Direction.EAST);
        // S4, Sharp Shooter: the nine targets on the inner wall at z 50 and a platform at y 125 to shoot from.
        for (BlockPos p : SimDevices.SharpShooter.TARGETS) {
            put(p.getX(), p.getY(), p.getZ(), SimDevices.SharpShooter.UNLIT);
        }
        platform(61, 69, 33, 37, 125);
        column(65, 33, FLOOR_Y + 1, 124, PILLAR);
        ladder(65, 32, 125, Direction.NORTH);
    }

    private SimTermBuild() {
    }

    /** The way from a terminal's spot into the middle of its section's corridor, for its ladder. */
    private static Direction towardCorridor(int section, int x, int z) {
        return switch (section) {
            case 0 -> x > 100 ? Direction.WEST : Direction.EAST;
            case 1 -> z > 132 ? Direction.NORTH : Direction.SOUTH;
            case 2 -> x > 8 ? Direction.WEST : Direction.EAST;
            default -> z > 40 ? Direction.NORTH : Direction.SOUTH;
        };
    }

    private static void put(int x, int y, int z, BlockState state) {
        SPECIALS.put(BlockPos.asLong(x, y, z), state);
    }

    private static void column(int x, int z, int y0, int y1, BlockState state) {
        for (int y = y0; y <= y1; y++) {
            put(x, y, z, state);
        }
    }

    /** A ladder from the floor up to {@code top}, on the block behind it (the side opposite {@code facing}). */
    private static void ladder(int x, int z, int top, Direction facing) {
        BlockState l = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, facing);
        for (int y = FLOOR_Y + 1; y <= top; y++) {
            put(x, y, z, l);
        }
    }

    private static void platform(int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                put(x, y, z, FLOOR);
            }
        }
    }

    static BlockState lever(Direction facing, boolean powered) {
        return Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.POWERED, powered);
    }

    static BlockState button(Direction facing) {
        return Blocks.STONE_BUTTON.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.POWERED, false);
    }

    /** What belongs at a cell of the freshly built ring (gates and core door closed). */
    static BlockState at(int x, int y, int z) {
        BlockState special = SPECIALS.get(BlockPos.asLong(x, y, z));
        if (special != null) {
            return special;
        }
        if (y < FLOOR_Y || y > TOP_Y) {
            return AIR;
        }
        // The core room, behind the door in the inner wall of section 4.
        if (x >= CORE_X0 - 1 && x <= CORE_X1 + 1 && z >= 51 && z <= 58) {
            if (y == FLOOR_Y) {
                return FLOOR;
            }
            if (z == 51) {
                return x >= CORE_X0 && x <= CORE_X1 && y <= CORE_TOP ? CORE_DOOR : WALL;
            }
            return x == CORE_X0 - 1 || x == CORE_X1 + 1 || z == 58 ? WALL : AIR;
        }
        boolean hollow = x >= 20 && x <= 89 && z >= 51 && z <= 121;
        boolean hollowEdge = hollow && (x == 20 || x == 89 || z == 51 || z == 121);
        if (hollow && !hollowEdge) {
            return AIR;
        }
        if (y == FLOOR_Y) {
            return Math.floorMod(x, 7) == 3 && Math.floorMod(z, 7) == 3 ? LIGHT : FLOOR;
        }
        if (hollowEdge || x == MIN_X || x == MAX_X || z == MIN_Z || z == MAX_Z) {
            return WALL;
        }
        // The walls across the ring, each with its section's gate.
        if (x >= 90 && z >= 122 && z <= 124) {
            return gate(0, x, y, z);
        }
        if (x >= 17 && x <= 19 && z >= 122) {
            return gate(1, x, y, z);
        }
        if (x <= 19 && z >= 49 && z <= 51) {
            return gate(2, x, y, z);
        }
        if (x >= 92 && x <= 93 && z <= 50) {
            return WALL;
        }
        return AIR;
    }

    private static BlockState gate(int i, int x, int y, int z) {
        int[] g = GATES[i];
        return x >= g[0] && x <= g[1] && z >= g[2] && z <= g[3] && y <= GATE_TOP ? GATE : WALL;
    }

    /** Takes a section's gate down. Server thread. */
    static void openGate(ServerLevel level, int section) {
        int[] g = GATES[section - 1];
        for (int x = g[0]; x <= g[1]; x++) {
            for (int z = g[2]; z <= g[3]; z++) {
                for (int y = FLOOR_Y + 1; y <= GATE_TOP; y++) {
                    level.setBlock(new BlockPos(x, y, z), AIR, Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Opens the core entrance. Server thread. */
    static void openCore(ServerLevel level) {
        for (int x = CORE_X0; x <= CORE_X1; x++) {
            for (int y = FLOOR_Y + 1; y <= CORE_TOP; y++) {
                level.setBlock(new BlockPos(x, y, 51), AIR, Block.UPDATE_ALL);
            }
        }
    }

    /**
     * Builds the ring a slice per server tick, every cell of its box (air included), so it also wipes whatever the
     * last drill left: opened gates, pressed buttons, lit targets. Through setBlock rather than the room paste's
     * section writer: these chunks can be within the client's view of the dungeon grid, and setBlock is what tells
     * it about every change.
     */
    static final class Job {
        private static final int CELLS_PER_TICK = 60_000;
        private static final int SIZE_X = MAX_X - MIN_X + 1, SIZE_Z = MAX_Z - MIN_Z + 1, SIZE_Y = TOP_Y - FLOOR_Y + 2;
        private static final int TOTAL = SIZE_X * SIZE_Y * SIZE_Z;

        private final ServerLevel level;
        private int cell;

        Job(ServerLevel level) {
            this.level = level;
        }

        boolean isDone() {
            return cell >= TOTAL;
        }

        float progress() {
            return (float) cell / TOTAL;
        }

        void step() {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int end = Math.min(TOTAL, cell + CELLS_PER_TICK);
            for (; cell < end; cell++) {
                int z = MIN_Z + cell % SIZE_Z;
                int x = MIN_X + (cell / SIZE_Z) % SIZE_X;
                int y = FLOOR_Y + cell / (SIZE_Z * SIZE_X);
                m.set(x, y, z);
                BlockState want = at(x, y, z);
                if (level.getBlockState(m) != want) {
                    level.setBlock(m, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }
}
