package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.sbc.Flags;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dungeons > Run Recap > Run Summary: builds a summary of the run that just ended from its Run History record
 * (floor, time, score, secrets, crypts, deaths, splits, party) and the reward chests opened for it (Chest History
 * profit). {@link RunSummaryScreen} draws it. Nothing goes to party or guild chat: with "Post Run Summary to /s2c" on,
 * the finished run is posted to Sky2M chat as one message with a clickable [View] that anyone in S2C can click, which
 * fetches the summary from the Sky2M server (runSummaryPost / runSummaryGet) and opens the screen.
 */
public final class RunSummary {
    private static final Pattern ID = Pattern.compile("[a-z0-9]{4,12}");
    /** Summaries fetched from the server this session, by id. */
    private static final Map<String, Remote> CACHE = new LinkedHashMap<>();
    /** Runs (by start time) already posted to S2C this session. */
    private static final Set<Long> POSTED = new HashSet<>();
    private static long lastPosting;
    private static String pendingId;
    private static RunSummaryScreen pendingScreen;

    private RunSummary() {}

    private record Remote(RunHistory.Run run, Double profit, String owner) {}

    static void onRunFinished(RunHistory.Run run) {
        DungeonRecapExtras.Config c = DungeonRecapExtras.config();
        if (c == null || !c.runSummary.enabled || !c.runSummary.postToS2c || !run.completed) return;
        post(run, true);
    }

    private static RunHistory.Run current() {
        List<RunHistory.Run> runs = RunHistory.runs();
        return runs.isEmpty() ? null : runs.getFirst();
    }

    /** /s2 runsummary: your latest run in the GUI. */
    static void open() {
        RunHistory.Run run = current();
        if (run == null) {
            Sky2MAlerts.chat(Component.literal("No finished run yet. Turn on Run History (Dungeon > Timers and Alerts) and finish a run.").withStyle(ChatFormatting.GRAY));
            return;
        }
        Compat.queueOpenScreen(new RunSummaryScreen(run, hasChests(run) ? profit(run) : null, null, true));
    }

    static boolean posted(RunHistory.Run run) {
        return POSTED.contains(run.startedAt);
    }

    // ------------------------------------------------------------------------------------------------ posting

    /** Posts the summary to S2C as one message with a [View] button (the server writes the line). */
    static void post(RunHistory.Run run, boolean auto) {
        if (!Flags.isEnabled("chat.runSummary")) {
            if (!auto) Flags.check("chat.runSummary");
            return;
        }
        if (auto && !SbcNet.online()) return;
        if (!POSTED.add(run.startedAt)) {
            if (!auto) Sbc.error("This run summary was already posted to /s2c.");
            return;
        }
        JsonObject packet = Sbc.packet("runSummaryPost");
        packet.add("summary", toJson(run));
        lastPosting = run.startedAt;
        if (!SbcNet.sendAuthed(packet)) POSTED.remove(run.startedAt);
    }

    private static JsonObject toJson(RunHistory.Run run) {
        JsonObject o = new JsonObject();
        o.addProperty("floor", run.floor == null ? "?" : run.floor);
        o.addProperty("completed", run.completed);
        o.addProperty("totalMs", run.totalMs);
        o.addProperty("endedAt", run.endedAt > 0 ? run.endedAt : run.startedAt);
        o.addProperty("score", run.effectiveScore());
        o.addProperty("rank", run.effectiveRank() == null ? "" : run.effectiveRank());
        o.addProperty("secrets", run.secrets);
        o.addProperty("secretPercent", Math.max(0, run.secretPercent));
        o.addProperty("crypts", run.crypts);
        o.addProperty("deaths", run.deathLog != null ? run.deathLog.size() : Math.max(0, run.deaths));
        o.addProperty("bestTime", run.bestTime);
        o.addProperty("bestScore", run.bestScore);
        if (hasChests(run)) o.addProperty("profit", Math.round(profit(run)));
        JsonArray splits = new JsonArray();
        for (RunHistory.Split s : run.splits) {
            JsonObject e = new JsonObject();
            e.addProperty("n", s.name());
            e.addProperty("ms", s.ms());
            splits.add(e);
        }
        o.add("splits", splits);
        JsonArray party = new JsonArray();
        for (RunHistory.Member m : run.party) {
            JsonObject e = new JsonObject();
            e.addProperty("n", m.name());
            e.addProperty("c", m.dungeonClass() == null ? "" : m.dungeonClass());
            party.add(e);
        }
        o.add("party", party);
        JsonArray deaths = new JsonArray();
        if (run.deathLog != null) {
            for (DeathRecap.DeathEntry d : run.deathLog) {
                JsonObject e = new JsonObject();
                e.addProperty("n", d.name());
                e.addProperty("c", d.cause());
                e.addProperty("r", d.room() == null ? "" : d.room());
                e.addProperty("ms", d.ms());
                deaths.add(e);
            }
        }
        o.add("deathLog", deaths);
        return o;
    }

    // ------------------------------------------------------------------------------------------------ viewing

    /** /s2 runsummary view <id>: the [View] button on a run summary in S2C. Opens the screen and fetches the data. */
    static void view(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        if (!ID.matcher(key).matches()) return;
        Remote cached = CACHE.get(key);
        if (cached != null) {
            Compat.queueOpenScreen(new RunSummaryScreen(cached.run(), cached.profit(), cached.owner(), false));
            return;
        }
        if (!Flags.check("chat.runSummary")) return;
        RunSummaryScreen screen = new RunSummaryScreen();
        JsonObject packet = Sbc.packet("runSummaryGet");
        packet.addProperty("id", key);
        pendingId = key;
        pendingScreen = screen;
        Compat.queueOpenScreen(screen);
        if (!SbcNet.sendQuietly(packet)) screen.fail("Not connected to the Sky2M server right now.");
    }

    /** runSummary, runSummaryMissing, runSummaryPosted and runSummaryError from the server, on the game thread. */
    public static void handle(String type, JsonObject packet) {
        switch (type) {
            case "runSummary" -> {
                String id = Sbc.str(packet, "id");
                Remote remote = parse(Sbc.obj(packet, "summary"), Sbc.str(packet, "owner"));
                if (remote == null) {
                    if (id.equals(pendingId) && pendingScreen != null) pendingScreen.fail("That run summary couldn't be read.");
                    return;
                }
                if (CACHE.size() >= 30) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(id, remote);
                if (id.equals(pendingId) && pendingScreen != null) {
                    pendingScreen.show(remote.run(), remote.profit(), remote.owner());
                    pendingId = null;
                    pendingScreen = null;
                }
            }
            case "runSummaryMissing" -> {
                if (Sbc.str(packet, "id").equals(pendingId) && pendingScreen != null) {
                    pendingScreen.fail("This run summary has expired.");
                    pendingId = null;
                    pendingScreen = null;
                }
            }
            case "runSummaryError" -> {
                POSTED.remove(lastPosting);
                String message = Sbc.str(packet, "message");
                Sbc.error(message.isBlank() ? "Couldn't post the run summary (" + Sbc.str(packet, "code") + ")." : message);
            }
            default -> {}
        }
    }

    private static Remote parse(JsonObject s, String owner) {
        if (s == null || s.size() == 0) return null;
        RunHistory.Run run = new RunHistory.Run();
        run.floor = Sbc.str(s, "floor");
        run.completed = Sbc.bool(s, "completed");
        run.totalMs = Sbc.lng(s, "totalMs", 0);
        run.endedAt = Sbc.lng(s, "endedAt", 0);
        run.startedAt = run.endedAt;
        run.score = (int) Sbc.lng(s, "score", -1);
        String rank = Sbc.str(s, "rank");
        run.rank = rank.isEmpty() ? null : rank;
        run.secrets = (int) Sbc.lng(s, "secrets", -1);
        run.secretPercent = s.has("secretPercent") && s.get("secretPercent").isJsonPrimitive() ? s.get("secretPercent").getAsDouble() : -1;
        run.crypts = (int) Sbc.lng(s, "crypts", -1);
        run.deaths = (int) Sbc.lng(s, "deaths", 0);
        run.bestTime = Sbc.bool(s, "bestTime");
        run.bestScore = Sbc.bool(s, "bestScore");
        for (JsonElement e : Sbc.arr(s, "splits")) {
            if (e.isJsonObject()) run.splits.add(new RunHistory.Split(Sbc.str(e.getAsJsonObject(), "n"), Sbc.lng(e.getAsJsonObject(), "ms", 0)));
        }
        for (JsonElement e : Sbc.arr(s, "party")) {
            if (!e.isJsonObject()) continue;
            String cls = Sbc.str(e.getAsJsonObject(), "c");
            run.party.add(new RunHistory.Member(null, Sbc.str(e.getAsJsonObject(), "n"), cls.isEmpty() ? null : cls));
        }
        java.util.ArrayList<DeathRecap.DeathEntry> deaths = new java.util.ArrayList<>();
        for (JsonElement e : Sbc.arr(s, "deathLog")) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            deaths.add(new DeathRecap.DeathEntry(Sbc.str(d, "n"), Sbc.str(d, "c"), Sbc.str(d, "r"), Sbc.lng(d, "ms", -1)));
        }
        run.deathLog = deaths.isEmpty() ? null : deaths;
        Double profit = s.has("profit") && s.get("profit").isJsonPrimitive() ? s.get("profit").getAsDouble() : null;
        return new Remote(run, profit, owner.isBlank() ? null : owner);
    }

    // ------------------------------------------------------------------------------------------------ local data

    /** Coins from the reward chests opened for this run: Chest History entries after it started and before the next run. */
    static double profit(RunHistory.Run run) {
        List<RunHistory.Run> runs = RunHistory.runs();
        int i = runs.indexOf(run);
        long until = i > 0 ? runs.get(i - 1).startedAt : Long.MAX_VALUE;
        double sum = 0;
        for (ChestHistory.Chest chest : ChestHistory.chests()) {
            if (chest.time < run.startedAt || chest.time >= until) continue;
            if (chest.floor != null && run.floor != null && !chest.floor.equals(run.floor)) continue;
            sum += chest.profit;
        }
        return sum;
    }

    static boolean hasChests(RunHistory.Run run) {
        List<RunHistory.Run> runs = RunHistory.runs();
        int i = runs.indexOf(run);
        long until = i > 0 ? runs.get(i - 1).startedAt : Long.MAX_VALUE;
        for (ChestHistory.Chest chest : ChestHistory.chests()) {
            if (chest.time >= run.startedAt && chest.time < until && (chest.floor == null || run.floor == null || chest.floor.equals(run.floor))) return true;
        }
        return false;
    }

    static String coins(double v) {
        double a = Math.abs(v);
        String s = a >= 1_000_000 ? String.format(Locale.ROOT, "%.2fM", a / 1_000_000) : a >= 1_000 ? String.format(Locale.ROOT, "%.1fK", a / 1_000) : String.format(Locale.ROOT, "%.0f", a);
        return (v < 0 ? "-" : "+") + s;
    }
}
