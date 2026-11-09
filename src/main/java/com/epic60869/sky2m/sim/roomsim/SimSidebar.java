/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The Catacombs sidebar, inside the sim, laid out as Hypixel's dungeon sidebar is.
 *
 * <p>A REAL server scoreboard: a vanilla objective in the sidebar slot on the integrated server, titled "SKYBLOCK",
 * with every line a team prefix on a holder name that draws as nothing - exactly how Hypixel builds its sidebar, so
 * every client-side reader (the Custom Scoreboard, {@code Sky2MLocation}'s floor and location, the Score Calculator's
 * "Cleared"/"Time Elapsed", {@code DungeonScore}, {@code DungeonState}'s keys) reads it as it reads Hypixel's.
 *
 * <p>The objective is the server scoreboard's own (so a joining guest gets it like any objective), but the LINES are
 * sent to each player separately as team and score packets, because they differ per player: the party block lists
 * your teammates, not you, as Hypixel's does. Nothing per-player is kept in the server's scoreboard, so it is never
 * re-broadcast over anyone's lines.
 *
 * <p>Top to bottom: date and server, the SkyBlock date and time, "⏣ The Catacombs (M7)", then the run (Keys, Time
 * Elapsed, Cleared - or "Starting in:" during the countdown), the teammates, and a footer that names the sim, never a
 * Hypixel domain: the sim must never present itself as Hypixel.
 */
public final class SimSidebar {

    private static final String OBJECTIVE = "k560sim";

    /** Holder names from before the per-player sidebar, cleared out of the saved scoreboard once per session. */
    private static final String OLD_HOLDER = "k560l";
    private static final String OLD_TEAM = "k560t";

    /** This sidebar's team per line. Sent to each client only; never in the server's scoreboard. */
    private static final String TEAM = "s2msb";

    /** Hypixel shows at most fifteen lines. */
    private static final int MAX_LINES = 15;

    /** The footer: the sim's own name in Hypixel's footer spot, never Hypixel's domain. */
    public static final String FOOTER = "sky2m sim";

    /**
     * Line {@code i}'s holder: colour codes and a reset and nothing else, which draws as nothing - what Hypixel does.
     * Doubled so it can never collide with the old shared sidebar's single-code holders.
     */
    private static String holder(int i) {
        char c = "0123456789abcdef".charAt(i & 15);
        return "§" + c + "§" + c + "§r";
    }

    /** Pre-2026-11 holders, for the one-off cleanup of the saved scoreboard. */
    private static String oldHolder(int i) {
        return "§" + "0123456789abcdef".charAt(i & 15) + "§r";
    }

    /** The tab list header. (The sidebar no longer starts with it, or ends with a Discord invite.) */
    public static final String INFO_TITLE = "Dungeon Sim";

    /** Said plainly, so a screenshot of the sim is never mistaken for a Hypixel run. */
    public static final String INFO_NOT_HYPIXEL = "Local practice world - not Hypixel";

    private static final String[] MONTHS = {"Early Spring", "Spring", "Late Spring", "Early Summer", "Summer",
            "Late Summer", "Early Autumn", "Autumn", "Late Autumn", "Early Winter", "Winter", "Late Winter"};
    private static final long SKYBLOCK_EPOCH_MS = 1560275700000L;
    private static final long SB_HOUR_MS = 50_000L;
    private static final long SB_DAY_MS = SB_HOUR_MS * 24;
    private static final long SB_MONTH_MS = SB_DAY_MS * 31;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.US);

    /** What the shared part said last tick, so the per-player send runs only when something moved. */
    private static Shared lastShared;
    private static boolean built;
    private static int ticks;

    /** Per connection: the line each holder shows on that client (null: not shown), and which teams it has. */
    private static final Map<Object, Sent> SENT = new WeakHashMap<>();

    private static final class Sent {
        final String[] lines = new String[MAX_LINES];
        final boolean[] teams = new boolean[MAX_LINES];
    }

    /** A scratch board for building team packets; the packets copy what they need. Server thread only. */
    private static final Scoreboard SCRATCH = new Scoreboard();

    private SimSidebar() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimSidebar.tick", SimSidebar::tick));
    }

    /** Forgets everything, so the next sim session rebuilds rather than inheriting the last one's lines. */
    public static void reset() {
        built = false;
        lastShared = null;
    }

    /**
     * Takes the sim's header and footer back off the tab list. Called as the sim world unloads.
     *
     * <p>The header and footer themselves are sent by the integrated server as a tab-list packet, with the rest
     * of the Hypixel-shaped tab list, by {@link SimTabList}; this only makes sure they cannot follow him onto a
     * server that sends none of its own.
     */
    public static void clearTabInfo(Minecraft client) {
        if (client == null || client.gui == null) {
            return;
        }
        var tab = com.epic60869.sky2m.sim.compat.McCompat.tabList(client);
        tab.setHeader(null);
        tab.setFooter(null);
    }

    /** The lines every player shares, read on the host's client thread where the run's state lives. */
    private record Shared(List<String> top, List<String> run, boolean started) {
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        Shared shared = compose();
        // The teammates' health lines move on their own: twice a second, whatever else changed.
        boolean due = ++ticks % 10 == 0;
        if (shared.equals(lastShared) && !due) {
            return;
        }
        lastShared = shared;
        // On the server thread: the scoreboard and the connections belong to the server.
        server.execute(() -> apply(server, shared));
    }

    private static Shared compose() {
        List<String> top = new ArrayList<>();
        top.add("§7" + LocalDate.now().format(DATE) + " §8sim");
        top.add("");
        long sb = System.currentTimeMillis() - SKYBLOCK_EPOCH_MS;
        long inYear = Math.floorMod(sb, SB_MONTH_MS * 12);
        int month = (int) (inYear / SB_MONTH_MS);
        int day = (int) ((inYear % SB_MONTH_MS) / SB_DAY_MS) + 1;
        long inDay = inYear % SB_DAY_MS;
        int hour = (int) (inDay / SB_HOUR_MS);
        int minute = (int) ((inDay % SB_HOUR_MS) * 60 / SB_HOUR_MS) / 10 * 10;
        top.add(" " + MONTHS[month] + " " + day + suffix(day));
        boolean daytime = hour >= 6 && hour < 19;
        top.add(String.format(Locale.US, " §7%d:%02d%s %s", hour % 12 == 0 ? 12 : hour % 12, minute,
                hour < 12 ? "am" : "pm", daytime ? "§e☀" : "§b☽"));
        top.add(" §7⏣ §cThe Catacombs §7(" + SimState.floorLabel() + ")");
        top.add("");

        List<String> run = new ArrayList<>();
        boolean started = SimRun.hasStarted();
        boolean floor = SimState.isGeneratedFloor();
        if (floor && !started) {
            // Before the gate opens Hypixel shows only the countdown, once everyone is ready.
            if (SimRun.isArmed()) {
                int seconds = (SimRun.countdownTicks() + 19) / 20;
                run.add("Starting in: §a" + seconds / 60 + ":" + String.format(Locale.US, "%02d", seconds % 60));
            }
        } else {
            boolean blood = SimKeys.bloodKey();
            run.add("Keys: §c■ " + (blood ? "§a✓" : "§c✗") + " §8■ §a"
                    + Math.min(9, Math.max(0, SimKeys.witherKeys())) + "x");
            if (started) {
                run.add("Time Elapsed: §a" + elapsed(SimRun.elapsedMs()));
            }
            int total = SimScore.roomsTotal();
            int percent = total > 0 ? (int) Math.floor(100.0 * SimScore.roomsCleared() / total) : 0;
            String colour = percent >= 100 ? "§a" : percent >= 50 ? "§e" : "§c";
            run.add("Cleared: " + colour + percent + "% §8(" + SimScore.totalScore() + ")");
        }
        return new Shared(List.copyOf(top), List.copyOf(run), started || !floor);
    }

    /** Hypixel's "Time Elapsed": "23s", "01m 23s", "1h 01m 23s". */
    private static String elapsed(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) {
            return String.format(Locale.US, "%dh %02dm %02ds", h, m, sec);
        }
        if (m > 0) {
            return String.format(Locale.US, "%02dm %02ds", m, sec);
        }
        return String.format(Locale.US, "%02ds", sec);
    }

    private static String suffix(int day) {
        if (day % 100 >= 11 && day % 100 <= 13) {
            return "th";
        }
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    // ------------------------------------------------------------------ server thread

    private static void apply(MinecraftServer server, Shared shared) {
        Scoreboard board = server.getScoreboard();
        Objective objective = board.getObjective(OBJECTIVE);
        Component title = Component.literal("SKYBLOCK").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
        if (objective == null) {
            objective = board.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY, title,
                    ObjectiveCriteria.RenderType.INTEGER, true,
                    net.minecraft.network.chat.numbers.BlankFormat.INSTANCE);
        }
        if (!title.equals(objective.getDisplayName())) {
            objective.setDisplayName(title);
        }
        // No red score numbers down the right - they are only the line order. An objective saved with the world
        // before this existed has none.
        if (!(objective.numberFormat() instanceof net.minecraft.network.chat.numbers.BlankFormat)) {
            objective.setNumberFormat(net.minecraft.network.chat.numbers.BlankFormat.INSTANCE);
        }
        if (board.getDisplayObjective(DisplaySlot.SIDEBAR) != objective) {
            board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        }
        if (!built) {
            // The shared sidebar's teams and scores, saved with the world before the sidebar went per-player.
            for (int i = 0; i < 16; i++) {
                for (String old : new String[]{OLD_HOLDER + i, oldHolder(i)}) {
                    board.resetAllPlayerScores(ScoreHolder.forNameOnly(old));
                }
                for (String name : new String[]{OLD_HOLDER + i, OLD_TEAM + i}) {
                    PlayerTeam old = board.getPlayerTeam(name);
                    if (old != null) {
                        board.removePlayerTeam(old);
                    }
                }
            }
            built = true;
        }
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.connection != null) {
                send(viewer, linesFor(server, viewer, shared));
            }
        }
    }

    /** One player's sidebar: the shared lines with his teammates (not himself) in the party block. */
    private static List<String> linesFor(MinecraftServer server, ServerPlayer viewer, Shared shared) {
        List<String> lines = new ArrayList<>(shared.top());
        lines.addAll(shared.run());
        if (!shared.run().isEmpty()) {
            lines.add("");
        }
        List<String> party = new ArrayList<>();
        for (ServerPlayer p : SimParty.players(server)) {
            if (p == viewer) {
                continue;
            }
            String letter = SimParty.dungeonClass(p).label.substring(0, 1);
            String tail;
            if (!shared.started()) {
                tail = "§e[Lvl" + SimParty.classLevel(p) + "]";
            } else if (SimDeath.isGhost(p)) {
                tail = "§cDEAD";
            } else {
                double[] v = SimVitals.snapshot(p.getUUID());
                tail = v == null ? "§a0§c❤"
                        : (v[1] > 0 && v[0] < v[1] / 2 ? "§e" : "§a")
                                + SimDamage.commas(Math.max(0, Math.round(v[0]))) + "§c❤";
            }
            party.add("§e[" + letter + "] §b" + p.getGameProfile().name() + " " + tail);
        }
        if (party.isEmpty()) {
            party.add("§3§lSolo");
        }
        // Room for the footer and the blank above it; the blank goes first when it does not fit.
        int room = MAX_LINES - lines.size() - 2;
        if (party.size() > room) {
            room++;
        }
        lines.addAll(party.subList(0, Math.max(0, Math.min(party.size(), room))));
        if (lines.size() + 2 <= MAX_LINES) {
            lines.add("");
        }
        lines.add("§e" + FOOTER);
        return lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
    }

    /** Sends only what changed on this player's sidebar: team prefixes for text, scores for lines that came or went. */
    private static void send(ServerPlayer viewer, List<String> lines) {
        Sent sent = SENT.computeIfAbsent(viewer.connection, k -> new Sent());
        for (int i = 0; i < MAX_LINES; i++) {
            String want = i < lines.size() ? lines.get(i) : null;
            String had = sent.lines[i];
            if (Objects.equals(want, had)) {
                continue;
            }
            String holder = holder(i);
            PlayerTeam team = SCRATCH.getPlayerTeam(TEAM + i);
            if (team == null) {
                team = SCRATCH.addPlayerTeam(TEAM + i);
                SCRATCH.addPlayerToTeam(holder, team);
            }
            // A line that goes away is emptied as well as hidden: readers that scan every team's prefix
            // (Sky2MLocation.teamLines) would otherwise still see it.
            team.setPlayerPrefix(Component.literal(want == null ? "" : want));
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, !sent.teams[i]));
            sent.teams[i] = true;
            if (want != null && had == null) {
                // Highest at the top, which is how Hypixel orders it; fixed per line, so a line coming or going
                // never has to renumber the others.
                viewer.connection.send(new ClientboundSetScorePacket(holder, OBJECTIVE, MAX_LINES - i,
                        Optional.empty(), Optional.empty()));
            } else if (want == null) {
                viewer.connection.send(new ClientboundResetScorePacket(holder, OBJECTIVE));
            }
            sent.lines[i] = want;
        }
    }

    public static boolean isBuilt() {
        return built;
    }
}
