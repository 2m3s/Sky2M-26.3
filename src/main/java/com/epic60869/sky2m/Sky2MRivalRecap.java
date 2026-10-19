package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Weekly Rival Recap (Misc > Collections): the first time you're on SkyBlock each week, who gained more last week on
 * each board you have a rival on.
 *
 * <p>The server's weekly boards (collectionweek / slayerweek) start again on Monday 00:00 UTC, so last week's gains
 * can't be asked for after that. Instead, whenever a rival board's weekly standings arrive (from this class, the
 * leaderboard screen's This Week view or anything else that asks), the latest values for you and your rivals on it
 * are kept for that week in config/sky2m/rival-weekly.json:
 * <pre>
 * {"version": 1, "recapped": "2026-10-12",
 *  "weeks": {"2026-10-12": {"collection|WHEAT": {"name": "Wheat", "at": ms, "size": 100, "floor": 1234,
 *                                                  "players": {"grimbly": {"name": "Grimbly", "value": 900000}}}}}}
 * </pre>
 * ("size" and "floor" are the list's length and lowest value, for a player who isn't on it). While you're on SkyBlock
 * with rivals, each board someone is your rival on gets its weekly standings asked for every {@link #FETCH_MS}, one
 * request at a time and never while another board request is out ({@link Sky2MLeaderboards#statBusy()}) or a screen is
 * open, like the Rival HUD. The recap is posted from the week that just ended; with no data for it, nothing is said.
 */
public final class Sky2MRivalRecap {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How often each rival board's weekly standings are asked for. */
    private static final long FETCH_MS = 30 * 60_000L;
    /** At most one request of ours this often, so several boards don't queue up behind each other. */
    private static final long REQUEST_GAP_MS = 15_000L;
    /**
     * Standings this soon after the reset may still be last week's (the server refreshes its boards every few minutes):
     * they aren't kept, so they can't land in the new week.
     */
    private static final long RESET_GRACE_MS = 20 * 60_000L;
    /** On SkyBlock this long before the recap, so it isn't lost among the join messages. */
    private static final long JOIN_DELAY_MS = 8_000L;
    private static final int KEEP_WEEKS = 4;

    private static JsonObject data;
    /** Per weekly board key: the standings ({@link Sky2MLeaderboards.StatResult#at()}) already kept. */
    private static final Map<String, Long> keptAt = new HashMap<>();
    /** Per board: when this class last asked for it (answered or not). */
    private static final Map<String, Long> askedAt = new HashMap<>();
    private static long lastRequest;
    private static long skyblockSince;
    private static int ticks;

    private Sky2MRivalRecap() {}

    private static boolean turnedOn() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.rivalRecap;
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(Sky2MRivalRecap::tick);
    }

    private static void tick(Minecraft mc) {
        if (!turnedOn() || mc.player == null || !Sky2MLocation.onSkyblock()) {
            skyblockSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (skyblockSince == 0) skyblockSince = now;
        // Once a second is plenty: standings come every few minutes at most.
        if (++ticks % 20 != 0) return;
        if (now - skyblockSince > JOIN_DELAY_MS) recapIfDue(now);
        keep(now);
        fetch(mc, now);
    }

    // ---------------------------------------------------------------- collecting

    /** The rival boards that have weekly boards ("collection|WHEAT", "slayer|zombie"). */
    private static List<String> weeklyBoards() {
        List<String> out = new ArrayList<>();
        for (String key : Sky2MRivals.ownBoards().keySet()) {
            if (key.startsWith("collection|") || key.startsWith("slayer|")) out.add(key);
        }
        return out;
    }

    /** Asks for the rival board whose weekly standings are oldest, when it's due. */
    private static void fetch(Minecraft mc, long now) {
        if (mc.gui.screen() != null || !SbcNet.online() || Sky2MLeaderboards.statBusy() || now - lastRequest < REQUEST_GAP_MS) return;
        if (now - weekStart(now) < RESET_GRACE_MS) return;
        String due = null;
        long oldest = Long.MAX_VALUE;
        for (String board : weeklyBoards()) {
            Sky2MLeaderboards.StatResult cached = Sky2MLeaderboards.cachedStat(weekKey(board));
            long seen = Math.max(cached == null ? 0 : cached.at(), askedAt.getOrDefault(board, 0L));
            if (now - seen >= FETCH_MS && seen < oldest) {
                oldest = seen;
                due = board;
            }
        }
        if (due == null) return;
        int bar = due.indexOf('|');
        if (Sky2MLeaderboards.requestStat(due.substring(0, bar) + "week", due.substring(bar + 1))) {
            askedAt.put(due, now);
            lastRequest = now;
        }
    }

    /** Keeps new weekly standings of every board you have a rival on (including rivals on all boards). */
    private static void keep(long now) {
        List<String> boards = new ArrayList<>(weeklyBoards());
        // Rivals on every board count wherever weekly standings turn up; the stat list says which boards there are.
        Sky2MLeaderboards.StatListData list = Sky2MLeaderboards.statList();
        if (list != null) {
            for (Sky2MLeaderboards.StatBoard b : list.collections()) addBoard(boards, "collection|" + b.id());
            for (Sky2MLeaderboards.StatBoard b : list.slayers()) addBoard(boards, "slayer|" + b.id());
        }
        boolean changed = false;
        for (String board : boards) {
            Sky2MLeaderboards.StatResult result = Sky2MLeaderboards.cachedStat(weekKey(board));
            if (result == null || !result.error().isEmpty() || keptAt.getOrDefault(board, 0L) == result.at()) continue;
            keptAt.put(board, result.at());
            if (result.at() - weekStart(result.at()) < RESET_GRACE_MS) continue;
            List<String> rivals = Sky2MRivals.rivalsOn(board);
            if (rivals.isEmpty()) continue;
            String self = Minecraft.getInstance().getUser().getName().toLowerCase(Locale.ROOT);
            JsonObject players = new JsonObject();
            long floor = Long.MAX_VALUE;
            for (Sky2MLeaderboards.StatEntry e : result.entries()) {
                floor = Math.min(floor, e.value());
                String lower = e.username().toLowerCase(Locale.ROOT);
                if (!lower.equals(self) && !rivals.contains(lower)) continue;
                JsonObject p = new JsonObject();
                p.addProperty("name", e.username());
                p.addProperty("value", e.value());
                players.add(lower, p);
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("name", Sky2MRivals.boardName(board));
            entry.addProperty("at", result.at());
            entry.addProperty("size", result.entries().size());
            entry.addProperty("floor", floor == Long.MAX_VALUE ? 0 : floor);
            entry.add("players", players);
            JsonObject weeks = object(data(), "weeks");
            object(weeks, week(result.at())).add(board, entry);
            changed = true;
        }
        if (changed) {
            prune();
            save();
        }
    }

    private static void addBoard(List<String> boards, String board) {
        if (!boards.contains(board) && Sky2MRivals.hasRivalsOn(board)) boards.add(board);
    }

    private static String weekKey(String board) {
        int bar = board.indexOf('|');
        return board.substring(0, bar) + "week" + board.substring(bar);
    }

    // ---------------------------------------------------------------- the recap

    /** Posts last week's recap once per week (the week is marked done even when there's nothing to say). */
    private static void recapIfDue(long now) {
        String thisWeek = week(now);
        JsonObject root = data();
        if (thisWeek.equals(string(root, "recapped"))) return;
        root.addProperty("recapped", thisWeek);
        save();
        String lastWeek = LocalDate.parse(thisWeek).minusWeeks(1).toString();
        JsonObject weeks = object(root, "weeks");
        if (!weeks.has(lastWeek) || !weeks.get(lastWeek).isJsonObject()) return;
        List<Component> lines = new ArrayList<>();
        String self = Minecraft.getInstance().getUser().getName().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, JsonElement> e : weeks.getAsJsonObject(lastWeek).entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            String board = e.getKey();
            JsonObject entry = e.getValue().getAsJsonObject();
            JsonObject players = object(entry, "players");
            // Rivals as they are now; one dropped since then isn't part of the recap.
            for (String rival : Sky2MRivals.rivalsOn(board)) {
                Component line = line(board, entry, players, self, rival);
                if (line != null) lines.add(line);
            }
        }
        if (lines.isEmpty()) return;
        say(Component.literal("Weekly Rival Recap (week of " + lastWeek + "):").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Component line : lines) say(line);
    }

    /**
     * "Wheat: you +1.2M vs Grimbly +900k — you won by 300k". Someone who wasn't on the list gained at most its lowest
     * value ("under 50k"); null when neither of you was on it.
     */
    private static Component line(String board, JsonObject entry, JsonObject players, String self, String rival) {
        JsonObject you = players.has(self) && players.get(self).isJsonObject() ? players.getAsJsonObject(self) : null;
        JsonObject them = players.has(rival) && players.get(rival).isJsonObject() ? players.getAsJsonObject(rival) : null;
        // Rivals on every board who weren't on this one: not a race there.
        if (them == null && Sky2MRivals.isRivalEverywhere(rival)) return null;
        if (you == null && them == null) return null;
        long floor = entry.has("floor") ? entry.get("floor").getAsLong() : 0;
        String name = them != null ? string(them, "name") : rival;
        String boardName = string(entry, "name").isEmpty() ? Sky2MRivals.boardName(board) : string(entry, "name");
        MutableComponent out = boardLink(board, boardName).append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("you ").withStyle(ChatFormatting.GREEN))
            .append(Component.literal(you == null ? "under +" + compact(floor) : "+" + compact(you.get("value").getAsLong())).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" vs ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(name + " ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(them == null ? "under +" + compact(floor) : "+" + compact(them.get("value").getAsLong())).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY));
        if (you != null && them != null) {
            long a = you.get("value").getAsLong(), b = them.get("value").getAsLong();
            out.append(a == b ? Component.literal("tied").withStyle(ChatFormatting.YELLOW)
                : a > b ? Component.literal("you won by " + compact(a - b)).withStyle(ChatFormatting.GREEN)
                : Component.literal(name + " won by " + compact(b - a)).withStyle(ChatFormatting.RED));
        } else if (you != null) {
            out.append(Component.literal("you won (" + name + " wasn't in the top " + entry.get("size").getAsInt() + ")").withStyle(ChatFormatting.GREEN));
        } else {
            out.append(Component.literal(name + " won (you weren't in the top " + entry.get("size").getAsInt() + ")").withStyle(ChatFormatting.RED));
        }
        return out;
    }

    /**
     * The board's name, clicking it shows that board's standings: /s2 leaderboard has no way to open the screen on a
     * board, so it's the chat lookup (all-time standings).
     */
    private static MutableComponent boardLink(String board, String boardName) {
        int bar = board.indexOf('|');
        String type = board.substring(0, bar), id = board.substring(bar + 1);
        String command = type.equals("slayer") ? "/s2 leaderboard slayer xp " + id : "/s2 leaderboard collection " + id;
        return Component.literal(boardName).withStyle(style -> style.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Show the " + boardName + " standings"))));
    }

    /** 1.2M, 900k, 2.5k, 950. */
    private static String compact(long n) {
        if (n >= 1_000_000_000L) return trim(String.format(Locale.ROOT, "%.1f", n / 1e9)) + "B";
        if (n >= 1_000_000L) return trim(String.format(Locale.ROOT, "%.1f", n / 1e6)) + "M";
        if (n >= 10_000L) return Math.round(n / 1e3) + "k";
        if (n >= 1_000L) return trim(String.format(Locale.ROOT, "%.1f", n / 1e3)) + "k";
        return String.valueOf(n);
    }

    private static String trim(String number) {
        return number.endsWith(".0") ? number.substring(0, number.length() - 2) : number;
    }

    // ---------------------------------------------------------------- weeks

    /** Monday 00:00 UTC of the week {@code ms} is in. */
    private static long weekStart(long ms) {
        return LocalDate.parse(week(ms)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** "2026-10-12": the Monday (UTC) of the week {@code ms} is in. */
    private static String week(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }

    /** Only the last few weeks are kept. */
    private static void prune() {
        JsonObject weeks = object(data(), "weeks");
        List<String> keys = new ArrayList<>(weeks.keySet());
        keys.sort(null);
        for (int i = 0; i < keys.size() - KEEP_WEEKS; i++) weeks.remove(keys.get(i));
    }

    // ---------------------------------------------------------------- storage

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("rival-weekly.json");
    }

    private static JsonObject data() {
        if (data == null) {
            data = new JsonObject();
            try {
                if (Files.exists(file())) {
                    JsonElement root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
                    if (root.isJsonObject()) data = root.getAsJsonObject();
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read rival-weekly.json: " + e.getMessage());
            }
            data.addProperty("version", 1);
        }
        return data;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(data()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save rival-weekly.json: " + e.getMessage());
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) parent.add(key, new JsonObject());
        return parent.getAsJsonObject(key);
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
        });
    }
}
