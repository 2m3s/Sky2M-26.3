package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.mapstyle.MapView;
import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Odin's Puzzle HUD (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/dungeon/PuzzleHud.kt and the tab
 * regex in utils/skyblock/dungeon/DungeonListener.kt): the run's puzzles from the tab list, each remaining, done or
 * failed, with who did it. The tab list decides the state; the "PUZZLE SOLVED!" and "PUZZLE FAIL!" chat lines only fill
 * in the name when the tab line has none, and also feed {@link ArchitectDraftAlert}.
 */
public final class PuzzleHud {
    public enum Status {
        REMAINING("✦", "§6"), DONE("✔", "§a"), FAILED("✖", "§c");

        final String icon;
        final String colour;

        Status(String icon, String colour) {
            this.icon = icon;
            this.colour = colour;
        }
    }

    /** A puzzle's tab line: its name ("???" until it's found), state, and player when the line names one. */
    public record Entry(String name, Status status, String player) {}

    /** A puzzle chat line: solved or failed, by whom, and which puzzle when the line says so (else null). */
    public record ChatLine(boolean solved, String player, String puzzle) {}

    private record Pending(ChatLine line, long at) {}

    private static final class Visited {
        final int slot;
        String room;

        Visited(int slot) {
            this.slot = slot;
        }

        List<String> players() {
            return firstIn(ENTERED.getOrDefault(slot, Set.of()));
        }
    }

    private static final Pattern COUNT = Pattern.compile("Puzzles: \\((\\d+)\\)");
    private static final Pattern TAB_LINE = Pattern.compile("^ ?(.+?): \\[([✦✔✖])] ?(?:\\(([^)]*)\\))?$");
    /** An emblem like the fish in "PUZZLE SOLVED! 🐟 2m3s tied Tic Tac Toe! Good job!" comes before the name. */
    private static final Pattern CHAT_LINE = Pattern.compile("^PUZZLE (SOLVED|FAIL)! (?:\\[[^]]*] ?)?(?:[^\\w\\s\\[]+ )?(\\w{1,16}) (.+)$");
    private static final Pattern QUIZ_FAIL = Pattern.compile("^\\[STATUE] Oruo the Omniscient: (?:\\[[^]]*] ?)?(?:[^\\w\\s\\[]+ )?(\\w{1,16}) chose the wrong answer!.*$");
    private static final long PENDING_MS = 30_000;

    private static List<Entry> puzzles = List.of();
    /** Who did each puzzle, from chat, for tab lines that don't name them. */
    private static final Map<String, String> WHO = new HashMap<>();
    /** Who has stood in each room slot on the map. By slot, not puzzle: the map can take a while to mark a room as one, and you may have left by then. */
    private static final Map<Integer, Set<String>> ENTERED = new HashMap<>();
    /** The puzzle rooms the map knows of, by slot, with their name once it gives it. */
    private static final Map<Integer, Visited> PUZZLE_ROOMS = new LinkedHashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static int count;
    private static int ticks;
    private static Level lastLevel;

    private PuzzleHud() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.puzzleHud;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            Config c = config();
            if (c == null || !c.enabled || !Sky2MLocation.inDungeon()) return;
            ChatLine line = parseChat(message.text());
            if (line != null) PENDING.add(new Pending(line, System.currentTimeMillis()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level != lastLevel) {
                lastLevel = mc.level;
                reset();
            }
            Config c = config();
            ticks++;
            if (c == null || !c.enabled || !Sky2MLocation.inDungeon()) return;
            if (c.showEntered && ticks % 2 == 0) watchRooms(com.epic60869.sky2m.sim.livemap.MapViewSource.current());
            if (ticks % 10 == 0) readTab();
        });
        Sky2MHuds.setting("puzzle_hud", () -> config() != null && config().enabled);
        Sky2MHuds.register("puzzle_hud", "Puzzle HUD",
            () -> config() != null && config().enabled && Sky2MLocation.inDungeon() && !DungeonManager.isInBoss() && !puzzles.isEmpty(),
            PuzzleHud::lines,
            List.of(Component.literal(header(3)),
                Component.literal(row(new Entry("Ice Path", Status.FAILED, "Steve"), true)),
                Component.literal(row(new Entry("Boulder", Status.DONE, "Alex"), true)),
                Component.literal(row(new Entry("Water Board", Status.REMAINING, null), true, List.of("Steve"))),
                Component.literal(row(new Entry("???", Status.REMAINING, null), true))),
            10, 340);
    }

    private static void reset() {
        puzzles = List.of();
        WHO.clear();
        ENTERED.clear();
        PUZZLE_ROOMS.clear();
        PENDING.clear();
        count = 0;
    }

    /** Notes whose head is in which room slot, so a puzzle someone walked into and left unsolved shows who. */
    private static void watchRooms(MapView view) {
        if (!view.valid() || view.inBoss()) return;
        int step = view.roomSize() + view.gap();
        for (MapView.Head head : view.heads()) {
            if (inGap(head.x(), view.startX(), view) || inGap(head.y(), view.startY(), view)) continue;
            int col = Math.floorDiv((int) Math.floor(head.x()) - view.startX(), step);
            int row = Math.floorDiv((int) Math.floor(head.y()) - view.startY(), step);
            ENTERED.computeIfAbsent(col * 100 + row, s -> new LinkedHashSet<>()).add(head.name());
        }
        for (MapView.Room room : view.rooms()) {
            if (room.type() != MapView.RoomType.PUZZLE || room.tiles().isEmpty()) continue;
            int[] tile = room.tiles().get(0);
            Visited visited = PUZZLE_ROOMS.computeIfAbsent(tile[0] * 100 + tile[1], Visited::new);
            if (room.name() != null) visited.room = room.name();
        }
    }

    /** A head in the doorway after a room hasn't gone in. */
    private static boolean inGap(float pixel, int start, MapView view) {
        return Math.floorMod((int) Math.floor(pixel) - start, view.roomSize() + view.gap()) >= view.roomSize();
    }

    /** Whether the tab list's puzzle name is this room on the map. Both Blaze rooms are "Higher Or Lower" in the tab. Public for the game test. */
    public static boolean samePuzzle(String tabName, String roomName) {
        if (tabName == null || roomName == null) return false;
        String room = roomName.toLowerCase(Locale.ROOT);
        return tabName.equalsIgnoreCase(room.contains("blaze") ? "Higher Or Lower" : roomName);
    }

    /** The first of those who went in, who are kept in the order they did. Public for the game test. */
    public static List<String> firstIn(Set<String> entered) {
        return entered.isEmpty() ? List.of() : List.of(entered.iterator().next());
    }

    /** Who went first into each listed puzzle, in the list's order. A "???" line gets the next entered room the tab hasn't named. */
    private static List<List<String>> enteredLists(List<Entry> entries) {
        List<Visited> left = new ArrayList<>(PUZZLE_ROOMS.values());
        List<List<String>> out = new ArrayList<>();
        for (Entry entry : entries) {
            Visited match = null;
            for (Visited visited : left) {
                if (samePuzzle(entry.name(), visited.room)) {
                    match = visited;
                    break;
                }
            }
            if (match != null) left.remove(match);
            out.add(match == null ? null : match.players());
        }
        for (int i = 0; i < entries.size() && !left.isEmpty(); i++) {
            if (out.get(i) == null && entries.get(i).name().equals("???")) out.set(i, left.removeFirst().players());
        }
        return out;
    }

    /** A puzzle line from the tab list's Puzzles section, or null. Public for the game test. */
    public static Entry parseTabLine(String line) {
        Matcher m = TAB_LINE.matcher(line == null ? "" : line.stripTrailing());
        if (!m.matches()) return null;
        Status status = switch (m.group(2)) {
            case "✔" -> Status.DONE;
            case "✖" -> Status.FAILED;
            default -> Status.REMAINING;
        };
        String who = m.group(3) == null ? "" : m.group(3).trim();
        // The name is the last word, in case an emblem ever comes with it.
        if (!who.isEmpty()) who = who.substring(who.lastIndexOf(' ') + 1);
        return new Entry(m.group(1).trim(), status, who.isEmpty() ? null : who);
    }

    /** A "PUZZLE SOLVED!", "PUZZLE FAIL!" or Oruo's wrong answer line, or null. Public for the game test. */
    public static ChatLine parseChat(String text) {
        if (text == null) return null;
        Matcher quiz = QUIZ_FAIL.matcher(text);
        if (quiz.matches()) return new ChatLine(false, quiz.group(1), "Quiz");
        Matcher m = CHAT_LINE.matcher(text);
        if (!m.matches()) return null;
        String rest = m.group(3);
        String puzzle = null;
        if (rest.contains("Tic Tac Toe")) puzzle = "Tic Tac Toe";
        else if (rest.contains("fooled by")) puzzle = "Three Weirdos";
        else if (rest.contains("Blaze")) puzzle = "Higher Or Lower";
        return new ChatLine(m.group(1).equals("SOLVED"), m.group(2), puzzle);
    }

    private static void readTab() {
        List<Entry> found = new ArrayList<>();
        boolean inPuzzles = false;
        int total = count;
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = com.epic60869.sky2m.custom.util.Compat.rawTabName(info);
            if (name == null) continue;
            String line = Sky2MLocation.strip(name.getString());
            Matcher m = COUNT.matcher(line);
            if (m.find()) {
                total = Integer.parseInt(m.group(1));
                inPuzzles = true;
                continue;
            }
            if (!inPuzzles) continue;
            Entry entry = parseTabLine(line);
            if (entry != null) found.add(entry);
            else if (line.isBlank() || found.size() >= total) inPuzzles = false;
        }
        count = total;
        puzzles = withWho(found);
    }

    /** Fills in who did each puzzle from chat when the tab doesn't say, and forgets it when a Draft puts a puzzle back. */
    private static List<Entry> withWho(List<Entry> found) {
        long now = System.currentTimeMillis();
        PENDING.removeIf(p -> now - p.at() > PENDING_MS);
        List<Entry> out = new ArrayList<>(found.size());
        for (Entry entry : found) {
            if (entry.status() == Status.REMAINING) {
                WHO.remove(entry.name());
                out.add(entry);
                continue;
            }
            if (entry.player() != null) {
                out.add(entry);
                continue;
            }
            String who = WHO.get(entry.name());
            if (who == null) {
                who = takePending(entry);
                if (who != null) WHO.put(entry.name(), who);
            }
            out.add(new Entry(entry.name(), entry.status(), who));
        }
        return out;
    }

    /** The oldest chat line for this puzzle, else the oldest of the same result that doesn't name a puzzle. */
    private static String takePending(Entry entry) {
        boolean solved = entry.status() == Status.DONE;
        for (int pass = 0; pass < 2; pass++) {
            for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
                ChatLine line = it.next().line();
                if (line.solved() != solved) continue;
                boolean match = pass == 0 ? entry.name().equals(line.puzzle()) : line.puzzle() == null;
                if (!match) continue;
                it.remove();
                return line.player();
            }
        }
        return null;
    }

    private static List<Component> lines() {
        Config c = config();
        List<Component> out = new ArrayList<>();
        if (c == null || puzzles.isEmpty()) return out;
        out.add(Component.literal(header(Math.max(count, puzzles.size()))));
        List<List<String>> entered = c.showEntered ? enteredLists(puzzles) : null;
        for (int i = 0; i < puzzles.size(); i++) {
            out.add(Component.literal(row(puzzles.get(i), c.showNames, entered == null ? null : entered.get(i))));
        }
        return out;
    }

    private static String header(int total) {
        return "§5Puzzles §8(§3" + total + "§8)";
    }

    private static String row(Entry entry, boolean showName) {
        return row(entry, showName, null);
    }

    /** "Water Board: [✦] in: Steve", with who went in first when anyone has. Public for the game test. */
    public static String row(Entry entry, boolean showName, List<String> entered) {
        String who = showName && entry.player() != null ? " §7(§6" + entry.player() + "§7)" : "";
        String in = entered == null || entered.isEmpty() ? "" : " §8in: §7" + String.join(", ", entered);
        return "§e" + entry.name() + "§8: [" + entry.status().colour + entry.status().icon + "§8]" + who + in;
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In a dungeon, until the boss, a HUD with the run's puzzles: remaining, done or failed. From the tab list. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Show Player Names", desc = "Also show who solved or failed each puzzle.")
        @ConfigEditorBoolean
        public boolean showNames = true;

        @Expose
        @ConfigOption(name = "Show Who Entered", desc = "Also show who went into each puzzle room first, so a puzzle someone walked into and skipped shows who. From the heads on the dungeon map.")
        @ConfigEditorBoolean
        public boolean showEntered = true;

        @Expose
        @ConfigOption(name = "Puzzle Bests", desc = "Keep your fastest time for each puzzle, from walking into its room to its green checkmark or a PUZZLE SOLVED! line naming you, and say the time in chat each time. /s2 puzzles lists them. A green checkmark only counts as a best with you in the room and no teammate's head in it on the map.")
        @ConfigEditorBoolean
        public boolean puzzleBests = true;
    }
}
