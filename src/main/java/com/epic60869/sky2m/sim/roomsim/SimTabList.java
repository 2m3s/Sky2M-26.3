/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.roomsim.mixin.SimPlayerInfoPacketAccessor;
import com.epic60869.sky2m.sim.util.FeatureGuard;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The Catacombs tab list, inside the sim, sent by the integrated server the way Hypixel sends it.
 *
 * <p>Hypixel's tab list is not a list of players. It is eighty fake profiles named so they sort into four
 * columns of twenty, each carrying a display name - "Secrets Found: 45.2%", "Crypts: 3", "Puzzles: (3)",
 * "[312] Name (Mage L)" - and the mod reads those display names in a dozen places. Until 2026-10-04 the sim had
 * no tab list at all, so Dungeon Info and Score Calc carried {@code SimState.isActive()} branches that read
 * {@link SimScore} directly. That is the opposite of what the sim is for: a client feature that behaves one way
 * in here and another on Hypixel is not being tested in here. So the sim now sends the same shape of packets
 * (player-info ADD/UPDATE_DISPLAY_NAME for the fake entries, plus a header/footer packet) and every reader
 * reads them exactly as it does on Hypixel.
 *
 * <p>Every line below is written to match the reader that consumes it, not from memory of Hypixel:
 * <ul>
 *   <li>{@code [100] Name (Mage L)} - {@code PartyTracker.TAB_REGEX} (and its copies in {@code Teammates},
 *       {@code ClassColors}, {@code PartyCommandsFeature}, {@code RunSummaryFeature.TAB_PARTY}) and
 *       {@code AutoUltFeature.TABLIST}</li>
 *   <li>{@code Secrets Found: N} and {@code Secrets Found: N.NN%} - {@code DungeonInfoFeature},
 *       {@code ScoreCalculatorFeature}, {@code RunSummaryFeature}</li>
 *   <li>{@code Crypts: N}, {@code Completed Rooms: N}, {@code Team Deaths: N}, {@code Puzzles: (N)} and the
 *       per-puzzle {@code Name: [✔|✖|✦] (player)} rows - {@code ScoreCalculatorFeature}, {@code RunSummaryFeature}</li>
 *   <li>{@code Dungeon: Catacombs} - {@code SkyblockArea}, {@code IslandDetector}, {@code ChatCommandsFeature}</li>
 * </ul>
 *
 * <p>The header and footer say what this world is (the sim's own identity, kept from {@link SimSidebar}) and
 * are sent as a {@link ClientboundTabListPacket} rather than written into the client's overlay.
 */
public final class SimTabList {

    private static final int COLUMNS = 4;
    private static final int ROWS = 20;
    private static final int SLOTS = COLUMNS * ROWS;

    /** Client ticks between unconditional re-publishes (a new connection gets the full list on the next one). */
    private static final int REPUBLISH_EVERY = 20;
    /** Client ticks between header/footer packets. */
    private static final int HEADER_EVERY = 40;

    /** A placeholder Skyblock level: the sim has no profile, and nothing reads the number. */
    private static final int SKYBLOCK_LEVEL = 100;
    /** The sim's only class ({@link SimClass}) at its in-sim level, in Hypixel's roman numerals. */
    private static final String CLASS_TEXT = "Mage L";

    private static final UUID[] IDS = new UUID[SLOTS];
    private static final GameProfile[] PROFILES = new GameProfile[SLOTS];

    static {
        for (int i = 0; i < SLOTS; i++) {
            // "!A-a" .. "!D-t": vanilla sorts the tab by profile name, so this is column-major order, and '!'
            // sorts ahead of every real player name.
            String name = "!" + (char) ('A' + i / ROWS) + "-" + (char) ('a' + i % ROWS);
            IDS[i] = UUID.nameUUIDFromBytes(("sky2m-simtab-" + i).getBytes(StandardCharsets.UTF_8));
            PROFILES[i] = new GameProfile(IDS[i], name);
        }
    }

    // ---- client thread ----
    private static List<String> lastComposed = List.of();
    private static int republishTicks;
    private static int headerTicks;
    private static final Set<String> VISITED = new HashSet<>();

    // ---- server thread ----
    /** Connections that have been sent the ADD_PLAYER for every slot. Weak, so a closed one just drops out. */
    private static final Set<Object> ADDED = Collections.newSetFromMap(new WeakHashMap<>());
    private static String[] sent = new String[SLOTS];

    private SimTabList() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimTabList.tick", SimTabList::tick));
    }

    /** A new map: forget which rooms he has been in, and publish on the next tick. */
    public static void reset() {
        VISITED.clear();
        lastComposed = List.of();
        republishTicks = 0;
        headerTicks = 0;
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        String room = SimState.currentRoomName();
        if (room != null && !room.isBlank()) {
            VISITED.add(room);
        }
        List<String> lines = compose(client);
        // A blessing just found goes into the footer at once, not up to two seconds later.
        int blessings = SimBlessings.version();
        boolean header = headerTicks-- <= 0 || blessings != lastBlessings;
        lastBlessings = blessings;
        if (header) {
            headerTicks = HEADER_EVERY;
        }
        boolean due = republishTicks-- <= 0;
        if (due) {
            republishTicks = REPUBLISH_EVERY;
        }
        if (!header && !due && lines.equals(lastComposed)) {
            return;
        }
        lastComposed = lines;
        server.execute(() -> publish(server, lines, header));
    }

    /** All eighty slots, column-major; an empty string is a blank slot. */
    static List<String> compose(Minecraft client) {
        String[] slot = new String[SLOTS];
        Arrays.fill(slot, "");
        String self = client.player == null ? "" : client.player.getGameProfile().name();

        // Column A: the party.
        int a = 0;
        slot[a++] = "Party (1)";
        slot[a] = "[" + SKYBLOCK_LEVEL + "] " + self + " (" + CLASS_TEXT + ")";

        // Column B: the team's numbers. Crypts sits in row 13 (slot 33), where Hypixel's tab has it and the
        // score calculator (Skyblocker's DungeonScore) reads it by position.
        int b = ROWS;
        slot[b++] = "Team Stats";
        slot[b++] = " Team Deaths: " + SimScore.deaths();
        slot[b] = " Secrets Found: " + SimScore.secretsFound();
        slot[ROWS + 13] = " Crypts: " + SimScore.cryptsBlown();

        // Column C: the dungeon and its puzzles, in Hypixel's rows: Completed Rooms at slot 43, Secrets Found % at 44,
        // Puzzles at 47 and one row per puzzle under it.
        int c = 2 * ROWS;
        slot[c++] = "Dungeon Stats";
        slot[c++] = " Dungeon: Catacombs";
        c++; // Hypixel's "Opened Rooms" row; the sim doesn't count opened rooms
        slot[c++] = " Completed Rooms: " + SimScore.roomsCleared();
        slot[c++] = " Secrets Found: " + percent(SimScore.secretsPercent()) + "%";
        slot[c++] = " Time: " + time(SimRun.elapsedMs());
        c++;
        List<String> puzzles = puzzleRows(self);
        slot[c++] = " Puzzles: (" + puzzles.size() + ")";
        for (String row : puzzles) {
            if (c >= 3 * ROWS) {
                break;
            }
            slot[c++] = row;
        }

        // Column D: what this world is.
        int d = 3 * ROWS;
        slot[d++] = "Info";
        slot[d] = " " + SimSidebar.INFO_NOT_HYPIXEL;
        return List.of(slot);
    }

    /**
     * One row per puzzle room on this floor, in placement order: {@code ???: [✦]} until he has stood in it, then
     * its name with ✔ solved, ✖ failed (and who failed it), or ✦ still open.
     */
    private static List<String> puzzleRows(String self) {
        List<String> rows = new ArrayList<>();
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            String key = SimRoomPuzzles.puzzleKey(placed.name());
            if (key == null) {
                continue;
            }
            if (!VISITED.contains(placed.name())) {
                rows.add(" ???: [✦]");
                continue;
            }
            String shown = displayName(placed.name());
            if (SimRoomState.isFailed(placed.name())) {
                rows.add(" " + shown + ": [✖] (" + self + ")");
            } else if (com.epic60869.sky2m.sim.roomsim.puzzles.SimPuzzles.isComplete(key)) {
                rows.add(" " + shown + ": [✔]");
            } else {
                rows.add(" " + shown + ": [✦]");
            }
        }
        return rows;
    }

    /** The two blaze rooms are one puzzle on Hypixel's tab list; every other room is listed under its own name. */
    private static String displayName(String roomName) {
        String lower = roomName.toLowerCase(Locale.ROOT);
        if (lower.equals("higher blaze") || lower.equals("lower blaze")) {
            return "Higher Or Lower";
        }
        return roomName;
    }

    /** Up to two decimals, trailing zeros dropped: 45.2, 100, 33.33. */
    private static String percent(double value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private static String time(long ms) {
        long total = Math.max(0, ms / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, s)
                : String.format(Locale.ROOT, "%02dm %02ds", m, s);
    }

    // ------------------------------------------------------------------ server thread

    private static void publish(MinecraftServer server, List<String> composed, boolean header) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        List<String> lines = withParty(server, composed);
        List<ClientboundPlayerInfoUpdatePacket.Entry> changed = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            String line = lines.get(i);
            if (!line.equals(sent[i])) {
                changed.add(entry(i, line));
            }
        }
        ClientboundPlayerInfoUpdatePacket update = changed.isEmpty() ? null
                : packet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), changed);
        ClientboundPlayerInfoUpdatePacket full = null;
        ClientboundTabListPacket headerPacket = null;
        for (ServerPlayer real : players) {
            if (real.connection == null) {
                continue;
            }
            if (!ADDED.contains(real.connection)) {
                if (full == null) {
                    List<ClientboundPlayerInfoUpdatePacket.Entry> all = new ArrayList<>(SLOTS);
                    for (int i = 0; i < SLOTS; i++) {
                        all.add(entry(i, lines.get(i)));
                    }
                    full = packet(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), all);
                }
                real.connection.send(full);
                ADDED.add(real.connection);
                header = true;
            } else if (update != null) {
                real.connection.send(update);
            }
            if (header) {
                if (headerPacket == null) {
                    headerPacket = headerFooter();
                }
                real.connection.send(headerPacket);
            }
        }
        sent = lines.toArray(new String[0]);
    }

    /**
     * Column A as Hypixel lays it out for a party: "Party (n)", then one member every fourth row - "[level] name
     * (Class L)", or "(DEAD)" for a ghost. Every client's DungeonPlayerManager, Leap Menu and map read it there.
     * Alone it is the one row it always was. Server thread.
     */
    private static List<String> withParty(MinecraftServer server, List<String> composed) {
        List<ServerPlayer> party = SimParty.players(server);
        // Alone too: the row then says the class he picked (it used to always say Mage), which Self Class, the class
        // cooldowns and every class-only option read.
        if (party.isEmpty()) {
            return composed;
        }
        String[] slot = composed.toArray(new String[0]);
        for (int i = 0; i < ROWS; i++) {
            slot[i] = "";
        }
        slot[0] = "Party (" + party.size() + ")";
        for (int i = 0; i < party.size() && 1 + i * 4 < ROWS; i++) {
            ServerPlayer p = party.get(i);
            String cls = SimDeath.isGhost(p) ? "DEAD"
                    : SimParty.dungeonClass(p).label + " " + roman(SimParty.classLevel(p));
            slot[1 + i * 4] = "[" + SKYBLOCK_LEVEL + "] " + p.getGameProfile().name() + " (" + cls + ")";
        }
        return List.of(slot);
    }

    /** A class level the way Hypixel's tab list writes it (XLII). */
    private static String roman(int n) {
        int[] values = {50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int left = Math.max(1, Math.min(50, n));
        for (int i = 0; i < values.length; i++) {
            while (left >= values[i]) {
                out.append(numerals[i]);
                left -= values[i];
            }
        }
        return out.toString();
    }

    /** The {@link SimBlessings} version the last header/footer packet carried. Client thread. */
    private static int lastBlessings = -1;

    /**
     * The header, and the footer as Hypixel writes it in a dungeon: a "Dungeon Buffs" block with one
     * "Blessing of Power XI" line per blessing the team holds (what Blessing Display reads), then the sim's note.
     */
    private static ClientboundTabListPacket headerFooter() {
        Component head = Component.literal(SimSidebar.INFO_TITLE).withStyle(ChatFormatting.GOLD);
        StringBuilder foot = new StringBuilder();
        for (String line : SimBlessings.footerLines()) {
            foot.append(line).append("\n");
        }
        if (foot.length() > 0) {
            foot.append("\n");
        }
        foot.append("§7").append(SimSidebar.INFO_NOT_HYPIXEL);
        return new ClientboundTabListPacket(head, Component.literal(foot.toString()));
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry entry(int slot, String text) {
        return new ClientboundPlayerInfoUpdatePacket.Entry(IDS[slot], PROFILES[slot], true, 0, GameType.SURVIVAL,
                Component.literal(text), false, 0, null);
    }

    /** Built with no players, then given the hand-made entries - see {@link SimPlayerInfoPacketAccessor}. */
    private static ClientboundPlayerInfoUpdatePacket packet(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions,
                                                            List<ClientboundPlayerInfoUpdatePacket.Entry> entries) {
        ClientboundPlayerInfoUpdatePacket p = new ClientboundPlayerInfoUpdatePacket(actions, List.<ServerPlayer>of());
        ((SimPlayerInfoPacketAccessor) (Object) p).killer560smod$setEntries(List.copyOf(entries));
        return p;
    }
}
