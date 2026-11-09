package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Boss rooms for the Dungeon Sim: builds a floor's boss arena, puts the player in it, runs the fight and ends the
 * run when the boss dies.
 *
 * <p>Reached three ways: the Watcher's portal once Blood is cleared ({@link SimWatcher}), {@code /boss} for the
 * current floor's boss, and {@code /boss <name>} for any boss that is in the sim. {@code /boss leave} goes back.
 * F7/M7 can also be started at any phase: {@code /boss maxor|storm|goldor|necron|witherking} ({@link #NECRON_PHASES}:
 * {@code maxor} is the whole fight, what {@code /boss necron} used to be, and the Wither King is M7 only).
 *
 * <h2>Health</h2>
 * The sim's weapons hit for 10,000 and vanilla caps max health at 1,024, so a boss cannot be an ordinary mob
 * with a big health bar. Each fight keeps its own pool ({@link Health}), the boss entity is only the body, and
 * {@link #onDamage} turns every hit on that body into pool damage and refuses the vanilla hit. That is also what
 * Hypixel's bars look like: hundreds of thousands of health, not twenty.
 *
 * <h2>Chat</h2>
 * Boss and Watcher lines are sent as real system chat in Hypixel's exact wording where the mod already reads a
 * line (Boss Phases, splits, Blood Camp, the score's "watcher cleared"), so those features work in the sim the
 * same way they do on Hypixel - the sim produces what they read rather than teaching each of them about it.
 *
 * <p>All state is touched on the server thread only; the client-side callers hop over with
 * {@code server.execute}.
 */
public final class SimBoss {

    private static final Logger LOGGER = ModLog.get("sky2m-simboss");

    /** A boss that has an arena in the jar. The floor codes are the ones {@link SimState#floorLabel} holds. */
    enum Kind {
        BONZO("bonzo", "Bonzo", Set.of("F1", "M1"), SimBonzo::new),
        SCARF("scarf", "Scarf", Set.of("F2", "M2"), SimScarf::new),
        PROFESSOR("professor", "The Professor", Set.of("F3", "M3"), SimProfessor::new),
        THORN("thorn", "Thorn", Set.of("F4", "M4"), SimThorn::new),
        LIVID("livid", "Livid", Set.of("F5", "M5"), SimLivid::new),
        SADAN("sadan", "Sadan", Set.of("F6", "M6"), SimSadan::new),
        NECRON("necron", "Necron", Set.of("F7", "M7"), SimNecron::new);

        final String id;
        final String display;
        final Set<String> floors;
        final Function<SimBossArena, Fight> factory;

        Kind(String id, String display, Set<String> floors, Function<SimBossArena, Fight> factory) {
            this.id = id;
            this.display = display;
            this.floors = floors;
            this.factory = factory;
        }

        static Kind forFloor(String floor) {
            for (Kind k : values()) {
                if (floor != null && k.floors.contains(floor.toUpperCase(Locale.ROOT))) {
                    return k;
                }
            }
            return null;
        }
    }

    private enum Stage { IDLE, BUILDING, WAITING, FIGHTING, DONE }

    /** F7/M7's phases as {@code /boss} names them, P1 to P5. "necron" is P4; the whole fight is "maxor". */
    static final String[] NECRON_PHASES = {"maxor", "storm", "goldor", "necron", "witherking"};
    private static final String[] NECRON_PHASE_NAMES = {"P1 Maxor", "P2 Storm", "P3 Goldor", "P4 Necron",
            "P5 the Wither King"};

    private static Stage stage = Stage.IDLE;
    /** The F7/M7 phase the fight being built starts at, 1 (Maxor, the whole fight) to 5; 1 for every other boss. */
    private static int startPhase = 1;
    private static SimBossArena arena;

    /**
     * A position in the boss arena that is up now, at Hypixel's coordinates: the floors other than F7/M7 are pasted
     * far from where Hypixel has them, so a feature that knows a boss room's blocks by their Hypixel position reads
     * them through this. The position itself when no arena is up (or on a party guest, which has none).
     */
    public static BlockPos toHypixel(BlockPos world) {
        SimBossArena a = arena;
        return a == null || world == null ? world : a.toSource(world);
    }
    private static SimBossArena.PasteJob job;
    private static Fight fight;
    private static ServerBossEvent bar;
    private static long fightStartMs;
    private static int fightTicks;
    private static int lastProgressPct = -1;
    /** Where death sent him before the boss, put back by /boss leave. */
    private static BlockPos homeBefore;

    private SimBoss() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (SimState.isActive() && stage != Stage.IDLE) {
                try {
                    tick(server);
                } catch (RuntimeException e) {
                    // A broken fight must not take the server tick down with it; stop it and say so.
                    LOGGER.warn("Sim boss tick failed; stopping the fight", e);
                    stopNow(server);
                }
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SimBoss::onDamage);
        SimNecron.register();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var root = ClientCommands.literal("boss")
                    // Sim-only, like /start and /fly, so the name is never claimed on Hypixel.
                    .requires(src -> SimState.canAct(Minecraft.getInstance()))
                    .executes(ctx -> {
                        onServer(server -> enterForFloor(server));
                        return 1;
                    })
                    .then(ClientCommands.literal("leave").executes(ctx -> {
                        onServer(SimBoss::leave);
                        return 1;
                    }));
            for (Kind k : Kind.values()) {
                if (k == Kind.NECRON) {
                    // F7/M7 by phase instead: "necron" is P4 now, the whole fight is "maxor".
                    continue;
                }
                root.then(ClientCommands.literal(k.id).executes(ctx -> {
                    onServer(server -> enter(server, k));
                    return 1;
                }));
            }
            for (String name : NECRON_PHASES) {
                int phase = necronPhase(name);
                root.then(ClientCommands.literal(name).executes(ctx -> {
                    onServer(server -> enter(server, Kind.NECRON, phase));
                    return 1;
                }));
            }
            dispatcher.register(root);
        });
    }

    private static void onServer(java.util.function.Consumer<MinecraftServer> action) {
        Minecraft client = Minecraft.getInstance();
        MinecraftServer server = SimState.canAct(client) ? client.getSingleplayerServer() : null;
        if (server != null) {
            server.execute(() -> {
                try {
                    action.accept(server);
                } catch (RuntimeException e) {
                    LOGGER.warn("Sim boss command failed", e);
                }
            });
        }
    }

    /** Enters the current floor's boss. Server thread. */
    static void enterForFloor(MinecraftServer server) {
        String floor = SimState.floorLabel();
        Kind kind = Kind.forFloor(floor);
        if (kind == null) {
            tell(server, ModChat.text("No boss room for " + (floor == null || floor.isBlank() ? "this map" : floor)
                    + " in the sim yet. Pick one: "), ModChat.value("/boss bonzo|scarf|professor|thorn|livid|sadan|maxor"));
            return;
        }
        enter(server, kind);
    }

    /**
     * The F7/M7 phase a {@code /boss} name starts at: 1 Maxor to 5 the Wither King, from the phase names
     * ({@link #NECRON_PHASES}), "p1".."p5", or "wk" / "wither_king" / "wither king"; 0 for anything else.
     */
    static int necronPhase(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        for (int i = 0; i < NECRON_PHASES.length; i++) {
            if (n.equals(NECRON_PHASES[i]) || n.equals("p" + (i + 1))) {
                return i + 1;
            }
        }
        return n.equals("wk") ? 5 : 0;
    }

    /** Builds the boss's arena and moves every player into it. Server thread. */
    static void enter(MinecraftServer server, Kind kind) {
        enter(server, kind, 1);
    }

    /**
     * {@link #enter(MinecraftServer, Kind)}, starting F7/M7 at {@code phase} (1 Maxor to 5 the Wither King): the
     * fight then begins as the room is built, with everyone at that phase's landing. Server thread.
     */
    static void enter(MinecraftServer server, Kind kind, int phase) {
        if (kind != Kind.NECRON) {
            phase = 1;
        }
        String floor = SimState.floorLabel();
        if (phase == 5 && (floor == null || !floor.toUpperCase(Locale.ROOT).startsWith("M"))) {
            // Same F7/M7 choice as SimNecron's: the floor's mode. F7 ends at Necron, as on Hypixel.
            tell(server, ModChat.text("The Wither King is M7 only. Start a Master Mode run, or "),
                    ModChat.value("/boss necron"), ModChat.text(" for F7's last phase."));
            return;
        }
        if (stage == Stage.BUILDING) {
            tell(server, ModChat.text("The boss room is still being built."));
            return;
        }
        if (SimTerms.isDrill()) {
            // The drill stands on the F7 room's own ground (both use Hypixel's real P3 coordinates).
            tell(server, ModChat.text("Leave the terminal drill first: "), ModChat.value("/simterms leave"));
            return;
        }
        SimBossArena a = SimBossArena.get(kind.id);
        if (a == null) {
            tell(server, ModChat.text("The " + kind.display + " arena is missing from this build."));
            return;
        }
        // Already in a boss room (this boss or another): end it the way /boss leave does, players back home first.
        // The non-F7 arenas all paste at the same WORLD_X/WORLD_Z spot, so staying put would leave everyone inside
        // the box being rewritten, with their client still showing the old room's blocks (stuck "underground").
        BlockPos home = stage != Stage.IDLE ? homeBefore : null;
        stopNow(server);
        sendHome(server, home);
        // The floor follows the boss, keeping the mode: /boss storm on an F1 map is F7 (M7 on M1). The sidebar,
        // the boss's numbers and the party guests (SimParty's hello) all read it from here.
        boolean master = floor != null && floor.toUpperCase(Locale.ROOT).startsWith("M");
        SimState.setFloorLabel((master ? "M" : "F") + (kind.ordinal() + 1));
        arena = a;
        fight = kind.factory.apply(a);
        startPhase = phase;
        if (fight instanceof SimNecron necron) {
            necron.startAt(phase);
        }
        job = a.new PasteJob(server.overworld());
        stage = Stage.BUILDING;
        lastProgressPct = -1;
        tell(server, ModChat.text("Building " + kind.display + "'s room..."));
    }

    /** Ends any fight and puts players back where death would have sent them before. Server thread. */
    static void leave(MinecraftServer server) {
        if (stage == Stage.IDLE) {
            tell(server, ModChat.text("Not in a boss room."));
            return;
        }
        BlockPos home = homeBefore;
        stopNow(server);
        sendHome(server, home);
    }

    /** Every player to {@code home} (where death sent him before the boss), if there is one. Server thread. */
    private static void sendHome(MinecraftServer server, BlockPos home) {
        if (home != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.teleportTo(server.overworld(), home.getX() + 0.5, home.getY(), home.getZ() + 0.5,
                        Set.of(), p.getYRot(), p.getXRot(), false);
                p.fallDistance = 0;
            }
        }
    }

    /** True while a boss room is built or being fought in. */
    public static boolean isActive() {
        return stage != Stage.IDLE;
    }

    /** Forgets everything for a new map or leaving the sim. Safe from the client thread. */
    public static void reset(Minecraft client) {
        MinecraftServer server = client == null ? null : client.getSingleplayerServer();
        if (server != null && SimState.isActive()) {
            server.execute(() -> stopNow(server));
        } else {
            stage = Stage.IDLE;
            fight = null;
            job = null;
            arena = null;
            bar = null;
            homeBefore = null;
            startPhase = 1;
        }
    }

    private static void stopNow(MinecraftServer server) {
        if (fight != null) {
            fight.discard(server.overworld());
        }
        if (bar != null) {
            bar.removeAllPlayers();
        }
        if (homeBefore != null && stage != Stage.IDLE) {
            SimSurvival.setHome(homeBefore);
        }
        stage = Stage.IDLE;
        fight = null;
        job = null;
        arena = null;
        bar = null;
        homeBefore = null;
        startPhase = 1;
    }

    private static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (stage) {
            case BUILDING -> {
                job.step();
                int pct = (int) (job.progress() * 100);
                if (pct / 25 != lastProgressPct / 25 && pct < 100) {
                    lastProgressPct = pct;
                    for (ServerPlayer p : players(server)) {
                        p.sendSystemMessage(Component.literal("Building boss room " + pct + "%")
                                .withStyle(ChatFormatting.GRAY), true);
                    }
                }
                if (job.isDone()) {
                    job = null;
                    arrive(server);
                }
            }
            case WAITING -> {
                // The fight starts on stepping into the arena proper, not on arrival - Hypixel's dialogue
                // starts as you come in, and an entrance corridor to collect yourself in is part of the room.
                for (ServerPlayer p : players(server)) {
                    if (inRing(p)) {
                        beginFight(level);
                        break;
                    }
                }
            }
            case FIGHTING -> {
                fightTicks++;
                fight.tick(level, fightTicks);
                updateBar(server);
                if (fight.defeated()) {
                    finish(server);
                }
            }
            default -> {
            }
        }
    }

    private static void beginFight(ServerLevel level) {
        stage = Stage.FIGHTING;
        fightStartMs = System.currentTimeMillis();
        fightTicks = 0;
        fight.start(level);
        bar = new ServerBossEvent(UUID.randomUUID(), fight.barName(), fight.barColor(),
                BossEvent.BossBarOverlay.PROGRESS);
    }

    private static void arrive(MinecraftServer server) {
        BlockPos entry = arena.toWorld(arena.entry);
        // Death in the boss room goes back to its entrance, not the middle of whatever room he left.
        homeBefore = SimSurvival.home();
        SimSurvival.setHome(entry);
        if (startPhase > 1) {
            // A later F7/M7 phase: no entrance to walk in from. The fight starts now, moves everyone to the phase's
            // landing and sets death's home there, as the phase change before it would have.
            beginFight(server.overworld());
            tell(server, ModChat.text("Starting at " + NECRON_PHASE_NAMES[startPhase - 1] + ", "),
                    ModChat.value("/boss leave"), ModChat.text(" to go back."));
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.teleportTo(server.overworld(), entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5, Set.of(),
                    arena.entryYaw, 0f, false);
            p.fallDistance = 0;
        }
        stage = Stage.WAITING;
        tell(server, ModChat.text(arena.name + "'s room. Walk in to start the fight, "),
                ModChat.value("/boss leave"), ModChat.text(" to go back."));
    }

    private static void updateBar(MinecraftServer server) {
        if (bar == null) {
            return;
        }
        bar.setName(fight.barName());
        bar.setColor(fight.barColor());
        bar.setProgress(Math.max(0f, Math.min(1f, fight.barProgress())));
        for (ServerPlayer p : players(server)) {
            boolean in = arena.contains(p.getX(), p.getY(), p.getZ());
            if (in && !bar.getPlayers().contains(p)) {
                bar.addPlayer(p);
            } else if (!in && bar.getPlayers().contains(p)) {
                bar.removePlayer(p);
            }
        }
    }

    /** "The Catacombs - Floor VII", "Master Mode The Catacombs - Floor III" or "The Catacombs - Entrance", as Hypixel's end block names it. */
    static String endFloorName(String floor) {
        if (floor == null || floor.length() < 2) {
            return "The Catacombs - Entrance";
        }
        String[] roman = {"I", "II", "III", "IV", "V", "VI", "VII"};
        int n;
        try {
            n = Integer.parseInt(floor.substring(1));
        } catch (NumberFormatException e) {
            return "The Catacombs - Entrance";
        }
        String name = "The Catacombs - Floor " + (n >= 1 && n <= 7 ? roman[n - 1] : String.valueOf(n));
        return floor.startsWith("M") ? "Master Mode " + name : name;
    }

    private static void finish(MinecraftServer server) {
        stage = Stage.DONE;
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
        long bossMs = System.currentTimeMillis() - fightStartMs;
        boolean timed = SimRun.isRunning();
        SimRun.finish();
        // Hypixel's end-of-boss line, which Boss Phases (and Odin-style splits) close their last phase on.
        long secs = bossMs / 1000;
        Component defeated = Component.literal(String.format(Locale.US, "  ☠ Defeated %s in %02dm %02ds",
                fight.name(), secs / 60, secs % 60));
        // Hypixel's whole end-of-run block around it: the floor, "Team Score: N (S+)" and "> EXTRA STATS <". Run History,
        // the Croesus run list, Class Counters, Auto Requeue and the dungeon tracker's end-of-run reset all read these.
        int score = SimScore.totalScore();
        String rule = "▬".repeat(64);
        List<Component> block = List.of(
                Component.literal(rule).withStyle(ChatFormatting.GREEN),
                Component.literal("                         " + endFloorName(SimState.floorLabel())),
                Component.literal(String.format(Locale.US, "                    Team Score: %d (%s)", score, SimScore.grade(score))),
                defeated,
                Component.literal("                                 > EXTRA STATS <").withStyle(ChatFormatting.YELLOW),
                Component.literal(rule).withStyle(ChatFormatting.GREEN));
        for (ServerPlayer p : players(server)) {
            for (Component line : block) {
                p.sendSystemMessage(line);
            }
        }
        List<Component> parts = new ArrayList<>();
        parts.add(ModChat.text(fight.name() + " defeated in "));
        parts.add(ModChat.value(time(bossMs)));
        if (timed) {
            parts.add(ModChat.text(", run time "));
            parts.add(ModChat.value(SimRun.elapsedText()));
        }
        tell(server, parts.toArray(Component[]::new));
        LOGGER.info("Sim boss {} defeated in {} ms", fight.name(), bossMs);
    }

    /**
     * Turns a hit on a boss body into pool damage. Always refuses the vanilla hit for a boss body (so its
     * vanilla health never moves and it never dies on its own); everything else is left alone.
     */
    private static boolean onDamage(LivingEntity entity, DamageSource source, float amount) {
        Fight f = fight;
        if (f == null || stage != Stage.FIGHTING || !SimState.isActive() || !f.isBoss(entity)) {
            return true;
        }
        // The arena's own hazards (fire, the void) are not the player's damage.
        if (source.getEntity() == null && source.getDirectEntity() == null) {
            return false;
        }
        // A ghost deals nothing: his arrows, abilities and beams as well as the melee SimDeath already stops.
        if (SimDeath.isGhostAttack(source)) {
            return false;
        }
        // A player's hit is worth its Hypixel damage from his stats (SimDamage), not the weapon's vanilla number.
        SimDamage.Hit rolled = source.getEntity() instanceof net.minecraft.world.entity.player.Player attacker
                ? SimDamage.roll(attacker, source, entity) : null;
        if (rolled != null) {
            // The items' effects on the hit (SimItemEffects); a boss has no Defense factor here.
            rolled = SimItemEffects.onPlayerHit((net.minecraft.world.entity.player.Player) source.getEntity(), source,
                    entity, rolled, 1.0);
        }
        float dealt = rolled == null ? amount : (float) Math.min(Float.MAX_VALUE, rolled.total());
        if (entity.level() instanceof ServerLevel level && f.hit(level, entity, dealt)) {
            // The red flash and hurt sound a real hit gives, without the hit.
            level.broadcastDamageEvent(entity, source);
            SimDamage.showNumber(level, entity, rolled);
        }
        return false;
    }

    /**
     * Whether this entity is the running fight's boss body, and how full its health is, for the enchants that read
     * the target's health ({@link SimEnchants}: Prosecute, Execute, Giant Killer). The fraction is the boss bar's,
     * which for a fight with several bodies is the whole fight's. Null for anything that is not a boss body.
     */
    static Double bossHealthFraction(Entity entity) {
        Fight f = fight;
        if (f == null || stage != Stage.FIGHTING || entity == null || !f.isBoss(entity)) {
            return null;
        }
        return (double) Math.max(0f, Math.min(1f, f.barProgress()));
    }

    /** Real players on the sim server (fake-player minibosses are never in the player list). */
    static List<ServerPlayer> players(MinecraftServer server) {
        return server.getPlayerList().getPlayers();
    }

    /** Players standing in the arena's ring. */
    static List<ServerPlayer> playersInRing(ServerLevel level) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) {
            if (inRing(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean inRing(ServerPlayer p) {
        if (arena == null) {
            return false;
        }
        BlockPos c = arena.toWorld(arena.centre);
        double dx = p.getX() - (c.getX() + 0.5);
        double dz = p.getZ() - (c.getZ() + 0.5);
        return dx * dx + dz * dz <= (double) arena.radius * arena.radius && Math.abs(p.getY() - c.getY()) < 8;
    }

    /** A boss or Watcher line, as Hypixel prints it: red "[BOSS] Name", white text. */
    static void say(MinecraftServer server, String speaker, String text) {
        Component line = Component.literal("[BOSS] " + speaker).withStyle(ChatFormatting.RED)
                .append(Component.literal(": " + text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : players(server)) {
            p.sendSystemMessage(line);
        }
    }

    /** A [Sim] line to every player, sent from the server so it is safe off the client thread. */
    static void tell(MinecraftServer server, Component... parts) {
        Component line = ModChat.line("Sim", parts);
        for (ServerPlayer p : players(server)) {
            p.sendSystemMessage(line);
        }
    }

    static String time(long ms) {
        return String.format(Locale.US, "%d:%05.2f", ms / 60000, (ms % 60000) / 1000.0);
    }

    /** Short health, the way Hypixel writes it on a name tag: 250k, 1.2M. */
    static String shortHealth(double hp) {
        return SimHealth.shortHealth(hp);
    }

    /** A boss fight's health pool, kept apart from the body's vanilla health (see the class doc). */
    static final class Health {
        double max;
        double hp;
        boolean invulnerable;

        Health(double max) {
            this.max = max;
            this.hp = max;
        }

        /** @return whether the hit landed */
        boolean hit(float amount) {
            if (invulnerable || hp <= 0) {
                return false;
            }
            hp = Math.max(0, hp - amount);
            return true;
        }

        float fraction() {
            return max <= 0 ? 0f : (float) (hp / max);
        }
    }

    /** One boss fight. Every method runs on the server thread. */
    abstract static class Fight {

        final SimBossArena arena;

        Fight(SimBossArena arena) {
            this.arena = arena;
        }

        abstract String name();

        /** A player has stepped into the arena. */
        abstract void start(ServerLevel level);

        /** Once per server tick while fighting; {@code t} counts from 1 at the start. */
        abstract void tick(ServerLevel level, int t);

        /** Whether this entity is the boss's body (or a part that takes boss damage). */
        abstract boolean isBoss(Entity entity);

        /** A player's hit on the body. @return whether it did damage */
        abstract boolean hit(ServerLevel level, LivingEntity body, float amount);

        abstract boolean defeated();

        /** Removes everything this fight placed. */
        abstract void discard(ServerLevel level);

        abstract Component barName();

        abstract BossEvent.BossBarColor barColor();

        abstract float barProgress();
    }
}
