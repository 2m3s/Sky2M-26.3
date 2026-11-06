package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The terminal drill: Goldor's P3 (F7/M7 terminals, levers and devices) as a practice room in the Dungeon Sim.
 * {@code /simterms} builds it ({@link SimTermBuild}) and puts the player at the start of section 1;
 * {@code /simterms leave} goes back.
 *
 * <p>Each section has Hypixel's terminals at their real spots, two levers and a device ({@link SimDevices}):
 * S1 4 terminals + Simon Says, S2 5 terminals + the lights device, S3 4 terminals + Arrow Align, S4 4 terminals +
 * Sharp Shooter, so 7/8/7/7 to finish them. Only the current section counts: the next one's terminals, levers
 * and device wait until its gate is down - except Sharp Shooter (i4), which works from section 1 on, as in M7 runs
 * where the Berserk does it during S1: its "completed a device!" line then counts in the section it is done in,
 * and section 4 starts at 1/7 with no device line of its own. A terminal is an armour stand named "Inactive Terminal". When P3
 * starts each terminal is given one of the six types ({@link SimTermBoard#assign}: a section never gets the same
 * type twice, so Melody at most once), and a right click opens a fresh board of that type as a real server-side
 * chest menu ({@link SimTermMenu}), so Sky2M's terminal solvers work on it as on Hypixel. Closing one unsolved, or
 * walking away from it, throws that board away; the next open is a new board of the same type. Several players
 * can have one terminal open at once, as on Hypixel, each on their own board progressing independently: the first
 * to finish completes it (one "activated a terminal!" line, counted once) and the others' menus close.
 *
 * <h2>Chat</h2>
 * Everything the mod reads in P3 is sent as real system chat in Hypixel's wording: Goldor's "Who dares trespass
 * into my domain?", "Name activated a terminal! (n/7)", "activated a lever!", "completed a device!" (also as the
 * subtitle Hypixel shows), "The gate has been destroyed!" when a gate actually goes down ({@link #announceGate}:
 * at once in the drill, a couple of seconds after the section in the boss room) and "The Core entrance is
 * opening!" at the end. There is no "The gate will open in 5 seconds!" line: M7 runs never show one. So Terminal Progress, Terminal Timers, Boss Phases, the device solvers and F7 Extras all
 * run in the drill. At the end a [Sim] line gives each section's time and the total.
 *
 * <h2>Inside the real boss room</h2>
 * The F7/M7 boss fight ({@code SimNecron}) runs the same terminals, levers and devices in the real arena's P3
 * ({@link #startInArena}): the stands at the same spots, the arena's own eight levers and its own lights device,
 * and the arena's gates and core door opened through {@link Arena} instead of the drill's.
 *
 * <p>State is touched on the server thread only, except {@link #STANDS} (and the frames' set) which the client's
 * use/attack callbacks read.
 */
public final class SimTerms {

    private static final Logger LOGGER = ModLog.get("sky2m-simterms");
    /** On every entity the drill spawns, so a rebuild or leave can sweep them, also after a restart. */
    static final String TAG = "sky2m_simterms";
    /** What each section needs: terminals + 2 levers + its device (Hypixel's 7, 8, 7, 7). */
    private static final int[] TOTALS = {7, 8, 7, 7};
    private static final Random RANDOM = new Random();
    /**
     * Farther than this (squared, blocks) from the terminal's stand and its menu closes, as Hypixel closes a terminal
     * you leap or walk away from. APPROXIMATE: Hypixel's exact range isn't published.
     */
    private static final double MAX_USE_DIST_SQ = 6.0 * 6.0;

    private enum Stage { IDLE, BUILDING, RUNNING, DONE }

    private static Stage stage = Stage.IDLE;
    private static SimTermBuild.Job job;
    private static int lastProgressPct = -1;
    private static BlockPos homeBefore;
    private static int section;
    private static int count;
    /** Sharp Shooter (i4) has been counted; and whether that was before section 4 (which then starts at 1). */
    private static boolean i4Seen, i4Early;
    private static long phaseStartMs, sectionStartMs;
    private static final long[] splits = new long[4];

    /** One terminal: its stand and whether it is done. */
    static final class Terminal {
        final int section;
        final ArmorStand stand;
        /** Its command block: a right click on it opens the terminal too, as on Hypixel. */
        final BlockPos block;
        /** Its terminal type for this run (set once all the stands are up). */
        SimTermBoard.Type type = SimTermBoard.Type.PANES;
        boolean done;

        Terminal(int section, ArmorStand stand, BlockPos block) {
            this.section = section;
            this.stand = stand;
            this.block = block;
        }
    }

    /** The terminals' command blocks, for the use callback (the client's too). */
    static final Set<BlockPos> BLOCKS = ConcurrentHashMap.newKeySet();

    private static final class Lever {
        final int section;
        final BlockPos pos;
        /** The lever as built (off): put back with its POWERED set when someone flips it out of turn. */
        final BlockState off;
        boolean done;

        Lever(int section, BlockPos pos, BlockState off) {
            this.section = section;
            this.pos = pos;
            this.off = off;
        }
    }

    /** What the drill does with its own room that the real boss room does its own way (SimNecron's P3). */
    interface Arena {
        /** Takes the gate after this section (1-3) down. */
        void openGate(ServerLevel level, int section);

        /** Opens the core entrance (the last section is done). */
        void openCore(ServerLevel level);
    }

    /** Set while the terminals run inside the real F7 boss room rather than the drill. */
    private static Arena arena;

    private static final List<Terminal> TERMINALS = new ArrayList<>();
    private static final List<Lever> LEVERS = new ArrayList<>();
    private static List<SimDevices.Device> devices = List.of();
    /** The terminal stands by UUID, for the use and attack callbacks (the client's too). */
    static final Set<UUID> STANDS = ConcurrentHashMap.newKeySet();

    private SimTerms() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (stage == Stage.IDLE) {
                return;
            }
            if (!SimState.isActive()) {
                // Left the sim with the drill open: forget it (the world is gone or going).
                forget();
                return;
            }
            try {
                tick(server);
            } catch (RuntimeException e) {
                LOGGER.warn("Sim terminal drill tick failed; stopping it", e);
                stopNow(server);
            }
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (entity == null || !ours(entity)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide() && player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND && SimState.isActive()) {
                onUse(sp, (ServerLevel) level, entity);
            }
            // Consumed on both sides: no armour-stand swap, no vanilla frame turn, no item use behind it.
            return InteractionResult.SUCCESS;
        });
        // A LEFT click on a terminal opens it too, as on Hypixel: the client turns it into the right click above.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (entity == null || !ours(entity)) {
                return InteractionResult.PASS;
            }
            if (level.isClientSide() && STANDS.contains(entity.getUUID()) && SimState.isActive()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.gameMode != null && player == mc.player) {
                    mc.gameMode.interact(mc.player, entity, new net.minecraft.world.phys.EntityHitResult(entity,
                            entity.position()), InteractionHand.MAIN_HAND);
                }
            }
            return InteractionResult.FAIL;
        });
        // A right click on a terminal's command block opens it, as on Hypixel (not only its stand). Consumed on both
        // sides, so vanilla's command block screen never opens.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hit == null || !BLOCKS.contains(hit.getBlockPos())) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide() && player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND
                    && SimState.isActive() && !(sp.containerMenu instanceof SimTermMenu)) {
                for (Terminal t : TERMINALS) {
                    if (t.block.equals(hit.getBlockPos())) {
                        openTerminal(sp, t);
                        break;
                    }
                }
            }
            return InteractionResult.SUCCESS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommands.literal("simterms")
                        // Sim-only, like /boss, so the name is never claimed on Hypixel.
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            onServer(SimTerms::enter);
                            return 1;
                        })
                        .then(ClientCommands.literal("leave").executes(ctx -> {
                            onServer(SimTerms::leave);
                            return 1;
                        }))));
    }

    /**
     * A left click on a block while P3 runs: a terminal's command block, a lever or a device button works like a right
     * click (Hypixel lets you hit levers and terminals). Client thread, from {@code SimItems}' left-click hook, which
     * otherwise keeps every left click from mining. The right click it becomes is the server's to judge.
     *
     * @return whether the click was taken
     */
    static boolean leftClickBlock(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level,
                                  BlockPos pos, Direction direction) {
        if (stage == Stage.IDLE || !SimState.isActive()) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        boolean terminal = BLOCKS.contains(pos);
        boolean lever = state.is(Blocks.LEVER) || state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock;
        Minecraft mc = Minecraft.getInstance();
        if (!terminal && !lever || mc.gameMode == null || player != mc.player) {
            return false;
        }
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                Vec3.atCenterOf(pos), direction, pos, false));
        return true;
    }

    private static boolean ours(Entity entity) {
        return STANDS.contains(entity.getUUID()) || SimDevices.ArrowAlign.FRAMES.contains(entity.getUUID());
    }

    private static void onServer(java.util.function.Consumer<MinecraftServer> action) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = SimState.canAct(client) ? client.getSingleplayerServer() : null;
        if (server != null) {
            server.execute(() -> {
                try {
                    action.accept(server);
                } catch (RuntimeException e) {
                    LOGGER.warn("Sim terminal drill command failed", e);
                }
            });
        }
    }

    /** True while the drill is built or being played (or P3's terminals run in the boss room). */
    public static boolean isActive() {
        return stage != Stage.IDLE;
    }

    /** True while the drill itself (not the boss room's P3) is built or being played. */
    public static boolean isDrill() {
        return stage != Stage.IDLE && arena == null;
    }

    /** The section being done now (1-4), while terminals run. */
    static int section() {
        return section;
    }

    /** Whether every section is done (the core is open), while terminals run. */
    static boolean coreOpen() {
        return stage == Stage.DONE;
    }

    /**
     * Starts P3's terminals, levers and devices in the real F7 boss room, already built: stands at Hypixel's
     * spots, the arena's own levers ({@code levers}: {x, y, z, section}, put to off), the devices at their real
     * spots ({@link SimDevices#createForArena}). Nothing is built or teleported, and Goldor's lines are the
     * fight's. Server thread.
     */
    static void startInArena(ServerLevel level, Arena hooks, int[][] levers) {
        stopNow(level.getServer());
        arena = hooks;
        spawnTerminals(level);
        LEVERS.clear();
        for (int[] l : levers) {
            BlockPos pos = new BlockPos(l[0], l[1], l[2]);
            BlockState s = level.getBlockState(pos);
            BlockState off = s.is(Blocks.LEVER) ? s.setValue(BlockStateProperties.POWERED, false)
                    : SimTermBuild.lever(Direction.NORTH, false);
            level.setBlock(pos, off, Block.UPDATE_ALL);
            LEVERS.add(new Lever(l[3], pos, off));
        }
        devices = SimDevices.createForArena();
        for (SimDevices.Device d : devices) {
            d.reset(level);
        }
        section = 1;
        count = 0;
        i4Seen = false;
        i4Early = false;
        phaseStartMs = sectionStartMs = System.currentTimeMillis();
        java.util.Arrays.fill(splits, 0);
        stage = Stage.RUNNING;
        LOGGER.info("Sim P3 terminals started in the boss room");
    }

    /** Ends the boss room's terminals (the fight is over or left). No-op for the drill. Server thread. */
    static void stopInArena(MinecraftServer server) {
        if (arena != null) {
            stopNow(server);
        }
    }

    // ------------------------------------------------------------------------------------------------ enter / leave

    private static void enter(MinecraftServer server) {
        if (SimBoss.isActive()) {
            SimBoss.tell(server, ModChat.text("Leave the boss room first: "), ModChat.value("/boss leave"));
            return;
        }
        if (stage == Stage.BUILDING) {
            SimBoss.tell(server, ModChat.text("The terminal drill is still being built."));
            return;
        }
        // Again from inside the drill: start over, but keep the way home from before the first time.
        BlockPos home = stage == Stage.IDLE ? SimSurvival.home() : homeBefore;
        stopNow(server);
        homeBefore = home;
        ServerLevel level = server.overworld();
        sweep(level);
        job = new SimTermBuild.Job(level);
        lastProgressPct = -1;
        stage = Stage.BUILDING;
        SimBoss.tell(server, ModChat.text("Building the terminal drill..."));
    }

    private static void leave(MinecraftServer server) {
        if (stage == Stage.IDLE || arena != null) {
            SimBoss.tell(server, ModChat.text("Not in the terminal drill."));
            return;
        }
        BlockPos home = homeBefore;
        stopNow(server);
        if (home != null) {
            for (ServerPlayer p : SimBoss.players(server)) {
                p.teleportTo(server.overworld(), home.getX() + 0.5, home.getY(), home.getZ() + 0.5, Set.of(), p.getYRot(), p.getXRot(), false);
                p.fallDistance = 0;
            }
        }
    }

    /** Ends the drill: menus closed, its entities gone, home given back. The blocks stay until the next build. */
    private static void stopNow(MinecraftServer server) {
        for (ServerPlayer p : SimBoss.players(server)) {
            if (p.containerMenu instanceof SimTermMenu) {
                p.closeContainer();
            }
        }
        SimTermMenu.OPEN.clear();
        for (SimDevices.Device d : devices) {
            if (d instanceof SimDevices.ArrowAlign a) {
                a.discard();
            }
        }
        sweep(server.overworld());
        if (homeBefore != null && stage != Stage.IDLE) {
            SimSurvival.setHome(homeBefore);
        }
        forget();
    }

    private static void forget() {
        stage = Stage.IDLE;
        job = null;
        homeBefore = null;
        arena = null;
        TERMINALS.clear();
        LEVERS.clear();
        STANDS.clear();
        BLOCKS.clear();
        SimDevices.ArrowAlign.FRAMES.clear();
        SimTermMenu.OPEN.clear();
        devices = List.of();
    }

    /** Removes every entity the drill ever spawned that is loaded now. */
    private static void sweep(ServerLevel level) {
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e.entityTags().contains(TAG)) {
                doomed.add(e);
            }
        }
        for (Entity e : doomed) {
            e.discard();
        }
    }

    // ------------------------------------------------------------------------------------------------ the drill

    private static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (stage) {
            case BUILDING -> {
                job.step();
                int pct = (int) (job.progress() * 100);
                if (pct / 25 != lastProgressPct / 25 && pct < 100) {
                    lastProgressPct = pct;
                    for (ServerPlayer p : SimBoss.players(server)) {
                        p.sendSystemMessage(Component.literal("Building terminal drill " + pct + "%").withStyle(ChatFormatting.GRAY), true);
                    }
                }
                if (job.isDone()) {
                    job = null;
                    arrive(server, level);
                }
            }
            case RUNNING, DONE -> {
                for (Terminal t : TERMINALS) {
                    if (t.stand.isRemoved()) {
                        // Something else cleared the world (a floor build): the drill is gone with it.
                        SimBoss.tell(server, ModChat.text("The terminal drill was cleared."));
                        stopNow(server);
                        return;
                    }
                }
                tickMenus();
                if (stage == Stage.RUNNING) {
                    tickLevers(level);
                    for (SimDevices.Device d : devices) {
                        // Sharp Shooter (i4) also works before its section, as M7 Berserks do it in S1.
                        d.tick(level, stage == Stage.RUNNING && (d.section == section
                                || d instanceof SimDevices.SharpShooter && section < d.section));
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void arrive(MinecraftServer server, ServerLevel level) {
        spawnTerminals(level);
        LEVERS.clear();
        for (int i = 0; i < SimTermBuild.LEVERS.length; i++) {
            int[] l = SimTermBuild.LEVERS[i];
            LEVERS.add(new Lever(i / 2 + 1, new BlockPos(l[0], l[1], l[2]),
                    SimTermBuild.lever(Direction.from3DDataValue(l[3]), false)));
        }
        devices = SimDevices.create();
        for (SimDevices.Device d : devices) {
            d.reset(level);
        }
        BlockPos spawn = BlockPos.containing(SimTermBuild.SPAWN);
        SimSurvival.setHome(spawn);
        for (ServerPlayer p : SimBoss.players(server)) {
            p.teleportTo(level, SimTermBuild.SPAWN.x, SimTermBuild.SPAWN.y, SimTermBuild.SPAWN.z, Set.of(), SimTermBuild.SPAWN_YAW, 0f, false);
            p.fallDistance = 0;
        }
        section = 1;
        count = 0;
        i4Seen = false;
        i4Early = false;
        phaseStartMs = sectionStartMs = System.currentTimeMillis();
        java.util.Arrays.fill(splits, 0);
        stage = Stage.RUNNING;
        SimBoss.say(server, "Goldor", "Who dares trespass into my domain?");
        SimBoss.tell(server, ModChat.text("Terminal drill. Section 1: 4 terminals, 2 levers and Simon Says. "),
                ModChat.value("/simterms"), ModChat.text(" to start over, "), ModChat.value("/simterms leave"), ModChat.text(" to go back."));
        LOGGER.info("Sim terminal drill started");
    }

    /** Sweeps old drill entities and puts an "Inactive Terminal" stand at each of Hypixel's terminal spots. */
    private static void spawnTerminals(ServerLevel level) {
        sweep(level);
        TERMINALS.clear();
        STANDS.clear();
        BLOCKS.clear();
        for (int s = 0; s < SimTermBuild.TERMINALS.length; s++) {
            for (int i = 0; i < SimTermBuild.TERMINALS[s].length; i++) {
                int[] t = SimTermBuild.TERMINALS[s][i];
                int[] b = SimTermBuild.TERMINAL_BLOCKS[s][i];
                BlockPos block = new BlockPos(b[0], b[1], b[2]);
                if (!level.getBlockState(block).is(Blocks.COMMAND_BLOCK)) {
                    level.setBlock(block, SimTermBuild.terminalBlock(), Block.UPDATE_ALL);
                }
                ArmorStand stand = new ArmorStand(level, t[0] + 0.5, t[1], t[2] + 0.5);
                stand.setNoGravity(true);
                stand.setNoBasePlate(true);
                stand.setPermanentlyInvulnerable(true);
                stand.setCustomNameVisible(true);
                stand.addTag(TAG);
                Terminal terminal = new Terminal(s + 1, stand, block);
                BLOCKS.add(block);
                name(terminal);
                if (level.addFreshEntity(stand)) {
                    TERMINALS.add(terminal);
                    STANDS.add(stand.getUUID());
                }
            }
        }
        // Each section's types, drawn without repeats (see SimTermBoard.assign).
        int[] perSection = new int[SimTermBuild.TERMINALS.length];
        for (int s = 0; s < perSection.length; s++) {
            perSection[s] = SimTermBuild.TERMINALS[s].length;
        }
        List<List<SimTermBoard.Type>> types = SimTermBoard.assign(perSection, RANDOM);
        int[] next = new int[perSection.length];
        for (Terminal t : TERMINALS) {
            t.type = types.get(t.section - 1).get(next[t.section - 1]++);
        }
    }

    private static void name(Terminal t) {
        t.stand.setCustomName(t.done ? Component.literal("Terminal Active").withStyle(ChatFormatting.GREEN)
                : Component.literal("Inactive Terminal").withStyle(ChatFormatting.RED));
    }

    private static void onUse(ServerPlayer sp, ServerLevel level, Entity entity) {
        if (entity instanceof ItemFrame frame) {
            for (SimDevices.Device d : devices) {
                if (d instanceof SimDevices.ArrowAlign a) {
                    a.turn(level, sp, frame, stage == Stage.RUNNING && a.section == section);
                }
            }
            return;
        }
        if (sp.containerMenu instanceof SimTermMenu) {
            // The second packet of the same click (interact-at, then interact).
            return;
        }
        for (Terminal t : TERMINALS) {
            if (t.stand == entity) {
                openTerminal(sp, t);
                return;
            }
        }
    }

    private static void openTerminal(ServerPlayer sp, Terminal t) {
        if (t.done || stage != Stage.RUNNING) {
            return;
        }
        if (t.section != section) {
            sp.sendSystemMessage(Component.literal("This terminal isn't active yet!").withStyle(ChatFormatting.RED), true);
            return;
        }
        // Several players may have the same terminal open, as on Hypixel: each gets their own board of its type.
        SimTermBoard board = SimTermBoard.of(t.type, RANDOM);
        sp.openMenu(new SimpleMenuProvider((id, inventory, player) -> new SimTermMenu(id, inventory, board, t),
                Component.literal(board.title())));
    }

    /**
     * Melody's ticks; a solved terminal counted and closed (a tick after the click that solved it), along with every
     * other player's board on the same terminal; a terminal whose player has moved away closed unsolved.
     */
    private static void tickMenus() {
        for (SimTermMenu menu : new ArrayList<>(SimTermMenu.OPEN)) {
            if (!SimTermMenu.OPEN.contains(menu) || !(menu.player instanceof ServerPlayer sp)) {
                // Closed earlier this tick (someone else finished its terminal).
                continue;
            }
            if (!menu.board.solved() && sp.distanceToSqr(menu.terminal.stand.position()) > MAX_USE_DIST_SQ) {
                sp.closeContainer();
                continue;
            }
            menu.board.tick();
            if (menu.board.solved() && !menu.reported) {
                menu.reported = true;
                Terminal t = menu.terminal;
                // The line first, then the close, so the client still has the menu open when "activated a
                // terminal!" arrives (Team Melody and Terminal Timers tie the line to the open terminal).
                if (!t.done && stage == Stage.RUNNING && t.section == section) {
                    t.done = true;
                    name(t);
                    progress(sp.level(), sp, "terminal");
                }
                sp.closeContainer();
                // The first to finish completes it: everyone else's board on this terminal closes, uncounted.
                for (SimTermMenu other : othersOn(SimTermMenu.OPEN, menu, m -> m.terminal)) {
                    other.reported = true;
                    if (other.player instanceof ServerPlayer op) {
                        op.closeContainer();
                    }
                }
            }
        }
    }

    /** The open menus other than {@code solver} on the same terminal: the ones to close once it is completed. */
    static <M> List<M> othersOn(List<M> open, M solver, java.util.function.Function<M, ?> terminalOf) {
        List<M> out = new ArrayList<>();
        Object terminal = terminalOf.apply(solver);
        for (M m : open) {
            if (m != solver && terminalOf.apply(m) == terminal) {
                out.add(m);
            }
        }
        return out;
    }

    private static void tickLevers(ServerLevel level) {
        for (Lever l : LEVERS) {
            BlockState s = level.getBlockState(l.pos);
            boolean on = s.is(Blocks.LEVER) && s.getValue(BlockStateProperties.POWERED);
            if (on == l.done && s.is(Blocks.LEVER)) {
                continue;
            }
            if (on && !l.done && l.section == section) {
                l.done = true;
                progress(level, nearest(level, l.pos), "lever");
                continue;
            }
            // A lever of a later section (or a done one pushed back): put it back the way it was.
            if (on && !l.done) {
                ServerPlayer near = nearest(level, l.pos);
                if (near != null) {
                    near.sendSystemMessage(Component.literal("This lever isn't active yet!").withStyle(ChatFormatting.RED), true);
                }
            }
            level.setBlock(l.pos, l.off.setValue(BlockStateProperties.POWERED, l.done), Block.UPDATE_ALL);
        }
    }

    /** The player nearest a spot (the one who did it, in practice). */
    static ServerPlayer nearest(ServerLevel level, BlockPos pos) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            double d = p.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Hypixel's line as a gate goes down, in chat and as a subtitle (Terminal Progress's Gate Titles reads the
     * subtitle). Server thread.
     */
    static void announceGate(MinecraftServer server) {
        Component gate = Component.literal("The gate has been destroyed!").withStyle(ChatFormatting.RED);
        for (ServerPlayer p : SimBoss.players(server)) {
            p.sendSystemMessage(gate);
            p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 10));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
            p.connection.send(new ClientboundSetSubtitleTextPacket(gate));
        }
    }

    /**
     * Counts one terminal, lever or device for the current section, with Hypixel's line, and opens the gate (or
     * the core) when the section is complete. Server thread.
     */
    static void progress(ServerLevel level, ServerPlayer who, String kind) {
        if (stage != Stage.RUNNING) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (kind.equals("device") && !i4Seen) {
            for (SimDevices.Device d : devices) {
                if (d instanceof SimDevices.SharpShooter && d.done) {
                    // This is i4: counted in the section it is done in; done early, section 4 starts with it.
                    i4Seen = true;
                    i4Early = section < d.section;
                }
            }
        }
        int total = TOTALS[section - 1];
        count++;
        String name = who == null ? "Someone" : who.getGameProfile().name();
        String verb = kind.equals("device") ? "completed" : "activated";
        // Hypixel's colours: the name, green text, the count in red until it is full.
        MutableComponent line = Component.literal(name).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" " + verb + " a " + kind + "! (").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(String.valueOf(count)).withStyle(count >= total ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal("/" + total + ")").withStyle(ChatFormatting.GREEN));
        for (ServerPlayer p : SimBoss.players(server)) {
            p.sendSystemMessage(line);
            p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 10));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
            p.connection.send(new ClientboundSetSubtitleTextPacket(line));
        }
        if (count < total) {
            return;
        }
        long now = System.currentTimeMillis();
        splits[section - 1] = now - sectionStartMs;
        if (section < 4) {
            if (arena != null) {
                // The boss room opens the gate itself a little later, and says so then.
                arena.openGate(level, section);
            } else {
                SimTermBuild.openGate(level, section);
                announceGate(server);
            }
            section++;
            // Section 4 starts with Sharp Shooter if it was done early (it has no device line of its own then).
            count = section == 4 && i4Early ? 1 : 0;
            sectionStartMs = now;
            return;
        }
        if (arena != null) {
            arena.openCore(level);
        } else {
            SimTermBuild.openCore(level);
        }
        for (ServerPlayer p : SimBoss.players(server)) {
            p.sendSystemMessage(Component.literal("The Core entrance is opening!").withStyle(ChatFormatting.GREEN));
        }
        stage = Stage.DONE;
        List<Component> parts = new ArrayList<>();
        parts.add(ModChat.text("Terminals done in "));
        parts.add(ModChat.value(SimBoss.time(now - phaseStartMs)));
        parts.add(ModChat.text(" ("));
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                parts.add(ModChat.dim(" | "));
            }
            parts.add(ModChat.text("S" + (i + 1) + " "));
            parts.add(ModChat.value(SimBoss.time(splits[i])));
        }
        if (arena != null) {
            parts.add(ModChat.text(")."));
        } else {
            parts.add(ModChat.text("). "));
            parts.add(ModChat.value("/simterms"));
            parts.add(ModChat.text(" to go again."));
        }
        SimBoss.tell(server, parts.toArray(Component[]::new));
        LOGGER.info("Sim terminal drill done in {} ms", now - phaseStartMs);
    }
}
