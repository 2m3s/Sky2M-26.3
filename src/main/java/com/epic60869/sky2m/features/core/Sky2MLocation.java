package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.Sky2MTabWidgetManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the player is in SkyBlock, read from the sidebar scoreboard and the tab list.
 * Updated twice a second; listeners are told when the tab-list area changes.
 */
public final class Sky2MLocation {
    private static final Pattern FLOOR = Pattern.compile("Catacombs \\((?<floor>[FM]\\d|E)\\)");
    /** Compiled once: String.replaceAll compiled these for every scoreboard line and team, twice a second. */
    private static final Pattern PADDING_EMOJI = Pattern.compile("[\\x{10000}-\\x{10FFFF}]");
    private static final Pattern NOT_ASCII = Pattern.compile("[^\\x20-\\x7E]");
    /**
     * "[MVP+] Name entered The Catacombs, Floor VII!" (MM before The Catacombs in Master Mode), between two lines of
     * dashes, when the party is sent into a run (Odin's DungeonQueue reads the same message).
     */
    private static final Pattern ENTERED = Pattern.compile("entered (?<master>MM )?The Catacombs, (?:Floor (?<floor>[IVX]+)|(?<entrance>Entrance))!");
    /** The Floor 7 bosses; when one talks you're on floor 7 (F7 unless the run's start message said MM). */
    private static final Pattern F7_BOSS = Pattern.compile("^\\[BOSS] (?:Maxor|Storm|Goldor|Necron|Wither King):");
    private static final Pattern GLACITE = Pattern.compile("Glacite Tunnels|Dwarven Base Camp|Great Glacite Lake|Fossil Research Center");
    private static final List<Consumer<String>> AREA_LISTENERS = new CopyOnWriteArrayList<>();

    private static List<String> scoreboard = List.of();
    private static List<String> teamLines = List.of();
    private static String scoreboardTitle = "";
    private static String area = "";
    private static String location = "";
    private static String floor = "";
    /** The floor from the run's start message, used when the sidebar can't be read. */
    private static String chatFloor = "";
    private static boolean onSkyblock;
    private static int ticks;

    private Sky2MLocation() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 == 0) update(mc);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            Matcher m = ENTERED.matcher(text);
            if (m.find()) {
                String number = m.group("entrance") != null ? "" : String.valueOf(roman(m.group("floor")));
                chatFloor = m.group("entrance") != null ? "E" : (m.group("master") != null ? "M" : "F") + number;
                return;
            }
            if (F7_BOSS.matcher(text).find() && !chatFloor.endsWith("7")) chatFloor = text.startsWith("[BOSS] Wither King") ? "M7" : "F7";
        });
    }

    private static int roman(String numeral) {
        int total = 0;
        for (int i = 0; i < numeral.length(); i++) {
            int value = switch (numeral.charAt(i)) { case 'X' -> 10; case 'V' -> 5; default -> 1; };
            int next = i + 1 < numeral.length() ? switch (numeral.charAt(i + 1)) { case 'X' -> 10; case 'V' -> 5; default -> 1; } : 0;
            total += value < next ? -value : value;
        }
        return total;
    }

    /** Called when the tab-list area changes (e.g. "Garden", "Catacombs", "Crystal Hollows"). */
    public static void onAreaChange(Consumer<String> listener) {
        AREA_LISTENERS.add(listener);
    }

    private static void reset() {
        scoreboard = List.of();
        teamLines = List.of();
        scoreboardTitle = "";
        location = "";
        floor = "";
        onSkyblock = false;
        setArea("");
    }

    private static void update(Minecraft mc) {
        if (mc.level == null) {
            if (onSkyblock || !area.isEmpty()) reset();
            return;
        }

        Scoreboard board = mc.level.getScoreboard();
        // Scoreboard mods can take the sidebar out of its display slot, or put their own objective there, to draw
        // their own; the server's SkyBlock objective is still in the scoreboard, so it's found by its title first.
        Objective objective = findSkyblockObjective(board);
        if (objective == null) objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) objective = biggestObjective(board);
        List<String> lines = new ArrayList<>();
        if (objective != null) {
            scoreboardTitle = strip(objective.getDisplayName().getString());
            List<PlayerScoreEntry> entries = new ArrayList<>(board.listPlayerScores(objective));
            entries.removeIf(PlayerScoreEntry::isHidden);
            entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner));
            for (PlayerScoreEntry entry : entries) {
                PlayerTeam team = board.getPlayersTeam(entry.owner());
                // Hypixel pads lines with emoji (outside the basic plane) between the team prefix and suffix.
                lines.add(PADDING_EMOJI.matcher(strip(PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString())).replaceAll("").trim());
            }
        } else {
            scoreboardTitle = "";
        }
        scoreboard = List.copyOf(lines);
        // Every sidebar line is a team's prefix + suffix. Reading the teams directly works even when a scoreboard
        // mod hides or replaces the sidebar objective, like NoammAddons does.
        List<String> teams = new ArrayList<>();
        for (PlayerTeam team : board.getPlayerTeams()) {
            String text = PADDING_EMOJI.matcher(strip(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString())).replaceAll("").trim();
            if (!text.isEmpty()) teams.add(text);
        }
        teamLines = List.copyOf(teams);
        onSkyblock = scoreboardTitle.contains("SKYBLOCK") || scoreboardTitle.contains("SKIBLOCK");
        if (onSkyblock) lastOnSkyblockAt = System.currentTimeMillis();

        String newLocation = "";
        String newFloor = "";
        for (String line : teams) {
            if (line.indexOf('(') < 0) continue; // the floor is "(F7)"; most teams are just players in the tab list
            Matcher m = FLOOR.matcher(NOT_ASCII.matcher(line).replaceAll(""));
            if (m.find() && !line.contains("Queue")) newFloor = m.group("floor");
        }
        for (String line : lines) {
            int symbol = Math.max(line.indexOf('⏣'), line.indexOf('ф'));
            if (symbol >= 0) newLocation = line.substring(symbol + 1).trim();
            // Hypixel's padding emoji can land inside "(F7)" (it sits between the team prefix and suffix, and some are
            // in the basic plane, like ⚽), so only printable ASCII is kept for the floor, as Skyblocker's \D* allows.
            Matcher m = FLOOR.matcher(NOT_ASCII.matcher(line).replaceAll(""));
            if (m.find() && !line.contains("Queue")) newFloor = m.group("floor");
        }
        location = newLocation;

        String newArea = "";
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = com.epic60869.sky2m.custom.util.Compat.rawTabName(info);
            if (name == null) continue;
            String text = strip(name.getString()).trim();
            if (text.startsWith("Area: ")) { newArea = text.substring(6).trim(); break; }
            if (text.startsWith("Dungeon: ")) { newArea = text.substring(9).trim(); break; }
        }
        // The sidebar's floor, or (when it can't be read) the one from the run's start message or the F7 bosses.
        floor = !newFloor.isEmpty() ? newFloor : newArea.equals("Catacombs") || newArea.isEmpty() ? chatFloor : "";
        setArea(newArea);
    }

    /** The server's SkyBlock sidebar objective (titled SKYBLOCK), wherever it is displayed. */
    private static Objective findSkyblockObjective(Scoreboard board) {
        for (Objective candidate : board.getObjectives()) {
            String title = strip(candidate.getDisplayName().getString());
            if (title.contains("SKYBLOCK") || title.contains("SKIBLOCK")) return candidate;
        }
        return null;
    }

    /** The objective with the most lines, when none is titled SKYBLOCK. */
    private static Objective biggestObjective(Scoreboard board) {
        Objective best = null;
        int bestSize = 0;
        for (Objective candidate : board.getObjectives()) {
            int size = board.listPlayerScores(candidate).size();
            if (size > bestSize) {
                best = candidate;
                bestSize = size;
            }
        }
        return best;
    }

    private static void setArea(String newArea) {
        if (newArea.equals(area)) return;
        // The start message comes before the server switch into the run, so the floor it gave is kept until you
        // reach an area that isn't the dungeon (the Dungeon Hub after the run, or anywhere else).
        if (!newArea.isEmpty() && !newArea.equals("Catacombs")) chatFloor = "";
        area = newArea;
        for (Consumer<String> listener : AREA_LISTENERS) {
            try {
                listener.accept(newArea);
            } catch (Exception ignored) {}
        }
    }

    public static String strip(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? "" : stripped;
    }

    public static boolean onSkyblock() { return onSkyblock; }

    private static long lastOnSkyblockAt;

    /**
     * SkyBlock Only (General): with it on, Sky2M pauses outside SkyBlock. Compat.isOnSkyblock, the HUDs and the
     * world renderers all ask this, so features don't each need a check. Singleplayer is allowed, and so are a few
     * seconds after leaving SkyBlock, so switching servers (the scoreboard reloads) doesn't make things flicker.
     * From Killer560's Mod's Skyblock Only (MIT).
     */
    public static boolean skyblockOnlyAllows() {
        com.epic60869.sky2m.Sky2MConfig config = com.epic60869.sky2m.Sky2MConfig.current();
        if (config == null || !config.general.skyblockOnly || onSkyblock) return true;
        if (Minecraft.getInstance().hasSingleplayerServer()) return true;
        return System.currentTimeMillis() - lastOnSkyblockAt < 10_000L;
    }
    public static String area() { return area; }
    public static String location() { return location; }
    public static List<String> scoreboard() { return scoreboard; }
    public static String scoreboardTitle() { return scoreboardTitle; }
    /** The sidebar lines read from the scoreboard teams (prefix + suffix), found even when the sidebar is hidden. */
    public static List<String> teamLines() { return teamLines; }
    /** Floor such as "F7" or "M7", or "" outside dungeons. */
    public static String dungeonFloor() { return floor; }

    public static boolean inDungeon() {
        return area.equals("Catacombs") || !floor.isEmpty();
    }

    public static boolean inGarden() { return area.equals("Garden"); }
    public static boolean inCrystalHollows() { return area.equals("Crystal Hollows"); }
    public static boolean inDwarvenMines() { return area.equals("Dwarven Mines"); }
    public static boolean inMineshaft() { return area.equals("Mineshaft"); }
    public static boolean inGlaciteTunnels() { return inDwarvenMines() && GLACITE.matcher(location).find(); }
    public static boolean inMiningIsland() { return inDwarvenMines() || inCrystalHollows() || inMineshaft(); }

    public static boolean areaIs(String name) {
        return area.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT));
    }

    /** First scoreboard line that starts with {@code prefix}, without the prefix, or null. */
    public static String scoreboardValue(String prefix) {
        for (String line : scoreboard) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return null;
    }
}
