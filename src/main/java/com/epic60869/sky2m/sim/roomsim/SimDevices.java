package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The four P3 devices of the terminal drill ({@link SimTerms}), one per section, built where Hypixel has them and
 * driven the way the mod's device solvers ({@code OdinDevices}) watch them: by block changes at fixed spots and
 * by item frame rotations. Each one only works while its section is the current one; finishing it calls
 * {@link SimTerms#progress} for Hypixel's "completed a device!" line.
 *
 * <p>Buttons and levers are watched by polling their block state every tick rather than through a use-block
 * callback: the sim's own ability callback runs first and may take a click made with an ability item in hand,
 * while vanilla still presses the button underneath. Polling sees every press however it happened.
 */
final class SimDevices {

    private static final Random RANDOM = new Random();

    private SimDevices() {
    }

    /** One device. Every method runs on the server thread. */
    abstract static class Device {
        final int section;
        boolean done;

        Device(int section) {
            this.section = section;
        }

        /** Puts the device back to its starting state (after the room is built). */
        abstract void reset(ServerLevel level);

        /** Once per server tick; {@code active} while its section is the current one. */
        abstract void tick(ServerLevel level, boolean active);

        void complete(ServerLevel level, BlockPos near) {
            done = true;
            SimTerms.progress(level, SimTerms.nearest(level, near), "device");
        }
    }

    static List<Device> create() {
        return List.of(new Simon(), new Lights(false), new ArrowAlign(), new SharpShooter());
    }

    /**
     * The devices for P3 in the real F7 boss room ({@link SimTerms#startInArena}): the same Simon Says, Arrow Align
     * and Sharp Shooter (the room has them at the very spots the drill builds them), and the room's own lights
     * device instead of the drill's lamps.
     */
    static List<Device> createForArena() {
        return List.of(new Simon(), new Lights(true), new ArrowAlign(), new SharpShooter());
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    private static void actionBar(ServerPlayer p, String text) {
        if (p != null) {
            p.sendSystemMessage(Component.literal(text).withStyle(net.minecraft.ChatFormatting.RED), true);
        }
    }

    // ------------------------------------------------------------------------------------------------ Simon Says

    /**
     * S1, Simon Says, on OdinDevices' layout: a 4x4 of obsidian at x 111 (y 120-123, z 92-95) that flashes sea
     * lantern, its buttons at x 110 in front, and the start button at (110, 121, 91). Pressing start flashes one
     * lantern; the buttons appear; press them in the order they flashed. Each round flashes the whole sequence
     * again one longer, with the buttons taken away while it plays (which is also when Odin forgets the last
     * round). Four rounds since Hypixel's 2026 dungeon update. A wrong button takes the buttons away and the
     * device starts again from the start button (Hypixel's penalty, approximated).
     */
    static final class Simon extends Device {
        private static final int ROUNDS = 4;
        private static final BlockPos START = new BlockPos(110, 121, 91);
        private static final int FLASH_TICKS = 8, GAP_TICKS = 4, ROUND_PAUSE_TICKS = 10;

        private enum State { IDLE, FLASHING, INPUT, PAUSE }

        private State state = State.IDLE;
        private final List<Integer> sequence = new ArrayList<>();
        private final boolean[] wasPowered = new boolean[16];
        private boolean startWasPowered;
        private int timer, pressed;

        Simon() {
            super(1);
        }

        private static BlockPos button(int i) {
            return new BlockPos(110, 120 + i / 4, 92 + i % 4);
        }

        private static BlockPos lantern(int i) {
            return button(i).east();
        }

        @Override
        void reset(ServerLevel level) {
            done = false;
            state = State.IDLE;
            sequence.clear();
            clearButtons(level);
            for (int i = 0; i < 16; i++) {
                set(level, lantern(i), Blocks.OBSIDIAN.defaultBlockState());
            }
            set(level, START, SimTermBuild.button(Direction.WEST));
        }

        private void clearButtons(ServerLevel level) {
            for (int i = 0; i < 16; i++) {
                set(level, button(i), Blocks.AIR.defaultBlockState());
                wasPowered[i] = false;
            }
        }

        private static boolean powered(ServerLevel level, BlockPos pos) {
            BlockState s = level.getBlockState(pos);
            return s.is(Blocks.STONE_BUTTON) && s.getValue(BlockStateProperties.POWERED);
        }

        private void nextRound(ServerLevel level) {
            int next;
            do {
                next = RANDOM.nextInt(16);
            } while (sequence.contains(next));
            sequence.add(next);
            clearButtons(level);
            state = State.FLASHING;
            timer = 0;
        }

        @Override
        void tick(ServerLevel level, boolean active) {
            boolean start = powered(level, START);
            if (start && !startWasPowered && !done && state == State.IDLE) {
                if (active) {
                    nextRound(level);
                } else {
                    actionBar(SimTerms.nearest(level, START), "This device isn't active yet!");
                }
            }
            startWasPowered = start;
            switch (state) {
                case FLASHING -> {
                    int step = timer / (FLASH_TICKS + GAP_TICKS);
                    int phase = timer % (FLASH_TICKS + GAP_TICKS);
                    if (step >= sequence.size()) {
                        for (int i = 0; i < 16; i++) {
                            set(level, button(i), SimTermBuild.button(Direction.WEST));
                        }
                        pressed = 0;
                        state = State.INPUT;
                        return;
                    }
                    BlockPos l = lantern(sequence.get(step));
                    if (phase == 0) {
                        set(level, l, Blocks.SEA_LANTERN.defaultBlockState());
                    } else if (phase == FLASH_TICKS) {
                        set(level, l, Blocks.OBSIDIAN.defaultBlockState());
                    }
                    timer++;
                }
                case INPUT -> {
                    for (int i = 0; i < 16; i++) {
                        boolean now = powered(level, button(i));
                        if (now && !wasPowered[i]) {
                            wasPowered[i] = true;
                            if (sequence.get(pressed) != i) {
                                clearButtons(level);
                                sequence.clear();
                                state = State.IDLE;
                                actionBar(SimTerms.nearest(level, START), "Wrong button! Press the start button to try again.");
                                return;
                            }
                            if (++pressed >= sequence.size()) {
                                if (sequence.size() >= ROUNDS) {
                                    clearButtons(level);
                                    state = State.IDLE;
                                    complete(level, START);
                                    return;
                                }
                                state = State.PAUSE;
                                timer = 0;
                            }
                        }
                        wasPowered[i] = now;
                    }
                }
                case PAUSE -> {
                    if (++timer >= ROUND_PAUSE_TICKS) {
                        nextRound(level);
                    }
                }
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ lights

    /**
     * S2's device, approximated: twelve redstone lamps on the north wall, each with its lever, some already lit.
     * Flip levers until every lamp is lit. Hypixel's S2 device is its own puzzle at its own spot; nothing in the
     * mod reads it, so this keeps only the idea (levers that have to end up all on).
     *
     * <p>In the real boss room ({@code arena}) it is that room's own lights board instead: twenty levers at
     * x 58-62, y 133-136 on the z 142 wall, each in front of its redstone lamp (which the lever lights itself).
     */
    static final class Lights extends Device {
        static final int LAMP_Z = 143;
        private final boolean arena;
        private final int n;
        private final boolean[] start;

        Lights(boolean arena) {
            super(2);
            this.arena = arena;
            this.n = arena ? 20 : 12;
            this.start = new boolean[n];
        }

        static int lampX(int i) {
            return 58 + 2 * (i % 4);
        }

        static int lampY(int i) {
            return 109 + 2 * (i / 4);
        }

        private BlockPos lever(int i) {
            return arena ? new BlockPos(58 + i % 5, 133 + i / 5, LAMP_Z - 1) : new BlockPos(lampX(i), lampY(i), LAMP_Z - 1);
        }

        @Override
        void reset(ServerLevel level) {
            done = false;
            int on = 0;
            for (int i = 0; i < n; i++) {
                // The arena's board starts mostly lit, so it is a few flips like the drill's, not twenty.
                start[i] = arena ? RANDOM.nextInt(4) != 0 : RANDOM.nextInt(3) == 0;
                if (start[i]) {
                    on++;
                }
            }
            if (on == n) {
                start[RANDOM.nextInt(n)] = false;
            }
            for (int i = 0; i < n; i++) {
                set(level, lever(i), SimTermBuild.lever(Direction.NORTH, start[i]));
            }
        }

        @Override
        void tick(ServerLevel level, boolean active) {
            boolean all = true;
            for (int i = 0; i < n; i++) {
                BlockState s = level.getBlockState(lever(i));
                boolean on = s.is(Blocks.LEVER) && s.getValue(BlockStateProperties.POWERED);
                // Before its section (or once done) the levers stay as they are: a flip is put back.
                boolean locked = done ? true : !active ? start[i] : on;
                if (!s.is(Blocks.LEVER) || on != locked) {
                    if (!done && !active && on != start[i]) {
                        actionBar(SimTerms.nearest(level, lever(i)), "This device isn't active yet!");
                    }
                    set(level, lever(i), SimTermBuild.lever(Direction.NORTH, locked));
                    on = locked;
                }
                all &= on;
            }
            if (active && !done && all) {
                complete(level, lever(5));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Arrow Align

    /**
     * S3, Arrow Align: arrows in item frames on a 5x5 at x -2 (y 120-124, z 75-79), facing east, laid out as one of
     * Odin's nine known solutions (the frames that have no arrow there are left out, as Hypixel does) and turned
     * to random rotations. A right click turns a frame one step (vanilla's rotation, done by the sim so the frame
     * cannot be emptied or broken); done when every arrow matches the solution.
     */
    static final class ArrowAlign extends Device {
        /** Odin's ArrowAlign solutions (OdinDevices), index = (y - 120) + (z - 75) * 5, -1 = no frame. */
        private static final int[][] SOLUTIONS = {
            {7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1},
            {-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1},
            {7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3},
            {5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1},
            {5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
            {7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
            {-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1},
            {-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1},
            {-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1}
        };

        /** The frames by UUID, for the use and attack callbacks (read from the client thread too). */
        static final Set<UUID> FRAMES = ConcurrentHashMap.newKeySet();
        private final List<ItemFrame> frames = new ArrayList<>();
        private final List<Integer> wanted = new ArrayList<>();
        private long lastTurnTick = -1;
        private UUID lastTurned;

        ArrowAlign() {
            super(3);
        }

        @Override
        void reset(ServerLevel level) {
            done = false;
            discard();
            int[] solution = SOLUTIONS[RANDOM.nextInt(SOLUTIONS.length)];
            boolean anyWrong = false;
            for (int i = 0; i < 25; i++) {
                if (solution[i] < 0) {
                    continue;
                }
                BlockPos pos = new BlockPos(-2, 120 + i % 5, 75 + i / 5);
                ItemFrame frame = new ItemFrame(level, pos, Direction.EAST);
                frame.setItem(new ItemStack(Items.ARROW), false);
                int rotation = RANDOM.nextInt(8);
                anyWrong |= rotation != solution[i];
                frame.setRotation(rotation);
                frame.setPermanentlyInvulnerable(true);
                frame.addTag(SimTerms.TAG);
                if (level.addFreshEntity(frame)) {
                    frames.add(frame);
                    wanted.add(solution[i]);
                    FRAMES.add(frame.getUUID());
                }
            }
            if (!anyWrong && !frames.isEmpty()) {
                frames.getFirst().setRotation((wanted.getFirst() + 2) % 8);
            }
        }

        void discard() {
            for (ItemFrame f : frames) {
                f.discard();
            }
            frames.clear();
            wanted.clear();
            FRAMES.clear();
        }

        /** A right click on one of the frames. */
        void turn(ServerLevel level, ServerPlayer player, ItemFrame frame, boolean active) {
            if (done) {
                return;
            }
            if (!active) {
                actionBar(player, "This device isn't active yet!");
                return;
            }
            // interact-at and interact can both arrive for one click: one turn per frame per tick.
            long tick = level.getGameTime();
            if (tick == lastTurnTick && frame.getUUID().equals(lastTurned)) {
                return;
            }
            lastTurnTick = tick;
            lastTurned = frame.getUUID();
            frame.setRotation((frame.getRotation() + 1) % 8);
        }

        @Override
        void tick(ServerLevel level, boolean active) {
            if (done || !active || frames.isEmpty()) {
                return;
            }
            for (int i = 0; i < frames.size(); i++) {
                if (frames.get(i).getRotation() != wanted.get(i)) {
                    return;
                }
            }
            complete(level, new BlockPos(0, 120, 77));
        }
    }

    // ------------------------------------------------------------------------------------------------ Sharp Shooter

    /**
     * S4, Sharp Shooter (the i4 device): nine blue terracotta targets at OdinDevices' spots (x 64/66/68, y
     * 126/128/130, z 50). One lights up as an emerald block; shoot it and it goes back to blue, and a target that
     * hasn't been hit lights next. Done when all nine have been hit. Hits are found by looking for arrows at a
     * lit target's face each tick, so any bow (or the sim's Terminator) counts.
     */
    static final class SharpShooter extends Device {
        static final List<BlockPos> TARGETS = List.of(
                new BlockPos(68, 130, 50), new BlockPos(66, 130, 50), new BlockPos(64, 130, 50),
                new BlockPos(68, 128, 50), new BlockPos(66, 128, 50), new BlockPos(64, 128, 50),
                new BlockPos(68, 126, 50), new BlockPos(66, 126, 50), new BlockPos(64, 126, 50));
        static final BlockState UNLIT = Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE).defaultBlockState();
        private static final int NEXT_DELAY_TICKS = 10;
        private static final AABB FACE = new AABB(63, 125, 48.8, 70, 132, 50.6);

        private final Set<Integer> hit = new HashSet<>();
        private int lit = -1;
        private int delay;

        SharpShooter() {
            super(4);
        }

        @Override
        void reset(ServerLevel level) {
            done = false;
            hit.clear();
            lit = -1;
            delay = NEXT_DELAY_TICKS;
            for (BlockPos p : TARGETS) {
                set(level, p, UNLIT);
            }
        }

        @Override
        void tick(ServerLevel level, boolean active) {
            if (done || !active) {
                return;
            }
            if (lit < 0) {
                if (--delay > 0) {
                    return;
                }
                List<Integer> left = new ArrayList<>();
                for (int i = 0; i < TARGETS.size(); i++) {
                    if (!hit.contains(i)) {
                        left.add(i);
                    }
                }
                lit = left.get(RANDOM.nextInt(left.size()));
                set(level, TARGETS.get(lit), Blocks.EMERALD_BLOCK.defaultBlockState());
            }
            for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, FACE)) {
                BlockPos t = TARGETS.get(lit);
                double x = arrow.getX(), y = arrow.getY(), z = arrow.getZ();
                if (x >= t.getX() - 0.1 && x <= t.getX() + 1.1 && y >= t.getY() - 0.1 && y <= t.getY() + 1.1 && z >= 49.3) {
                    arrow.discard();
                    hit.add(lit);
                    set(level, t, UNLIT);
                    lit = -1;
                    delay = NEXT_DELAY_TICKS;
                    if (hit.size() >= TARGETS.size()) {
                        complete(level, new BlockPos(65, 126, 35));
                    }
                    return;
                }
            }
        }
    }
}
