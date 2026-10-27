package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon Run Summary and History, like Killer560's Mod's (MIT, https://github.com/Killer560-OG/killer560s-mod,
 * runsummary/): every finished run is saved to config/sky2m/run-history.json with its floor, times, splits, P3
 * terminal and device times, score, secrets, crypts, deaths, class and party, from what Sky2M already tracks (the
 * split timers, the score calculator and the dungeon party). Personal bests per floor (time and score) are marked and
 * announced, a summary can go to chat, and /s2 runs opens the history.
 */
public final class RunHistory {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("run-history.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern DEFEATED = Pattern.compile("^\\s*☠ Defeated (.+) in 0?([\\dhms ]+?)\\s*(\\(NEW RECORD!\\))?$");
    private static final Pattern TEAM_SCORE = Pattern.compile("^\\s*Team Score: ([\\d,]+) \\(([A-DS+]+)\\)");
    private static final Pattern DEVICE = Pattern.compile("^(\\w{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    /** Built on first use, not at start-up. */
    private static final class DateFormat {
        static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ROOT);
    }

    /** One saved run. Counts are -1 when they weren't known. */
    public static final class Run {
        public long startedAt, endedAt, totalMs;
        public String floor;
        public boolean completed;
        public String hypixelTime;
        public boolean hypixelRecord;
        public int score = -1, hypixelScore = -1;
        public String rank, hypixelRank;
        public int secrets = -1, crypts = -1, deaths = -1;
        public double secretPercent = -1;
        public String dungeonClass;
        public List<Split> splits = new ArrayList<>();
        public List<Device> devices = new ArrayList<>();
        public List<Member> party = new ArrayList<>();
        public boolean bestTime, bestScore;
        /** Who died, to what, where and when (Death Recap); null if it wasn't recorded. */
        public List<DeathRecap.DeathEntry> deathLog;

        /** Hypixel's score if it was seen, otherwise Sky2M's estimate. */
        public int effectiveScore() {
            return hypixelScore >= 0 ? hypixelScore : score;
        }

        public String effectiveRank() {
            return hypixelScore >= 0 && hypixelRank != null ? hypixelRank : rank;
        }

        public String date() {
            return DateFormat.DATE.format(Instant.ofEpochMilli(endedAt > 0 ? endedAt : startedAt).atZone(ZoneId.systemDefault()));
        }
    }

    public record Split(String name, long ms) {}

    /** A terminal, device or lever: who, what, its (n/m), and how far into P3 it was. */
    public record Device(String player, String kind, int index, int total, long msIntoPhase) {}

    public record Member(String uuid, String name, String dungeonClass) {}

    private static List<Run> runs;
    private static Run current;
    private static long goldorStart;
    private static int finishIn = -1;

    private RunHistory() {}

    static FeatureConfigs.RunHistory config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.timers.runHistory;
    }

    public static void init() {
        RouteReplay.init();
        Sky2MChat.onChat(message -> onChat(message.text()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (finishIn > 0 && --finishIn == 0) finish();
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("runs").executes(c -> {
                    Compat.queueOpenScreen(new RunHistoryScreen());
                    return 1;
                })));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ capture

    private static void onChat(String raw) {
        FeatureConfigs.RunHistory c = config();
        if (c == null || !c.enabled) return;
        String text = raw.trim();
        if (text.equals("Starting in 1 second.") && Sky2MLocation.inDungeon()) {
            current = new Run();
            current.startedAt = System.currentTimeMillis();
            current.floor = Sky2MLocation.dungeonFloor();
            RouteReplay.begin(current.floor, current.startedAt);
            java.util.concurrent.CompletableFuture.runAsync(RunHistory::runs); // read the saved runs off the client thread
            goldorStart = 0;
            finishIn = -1;
            return;
        }
        if (current == null) return;
        if (text.equals("[BOSS] Goldor: Who dares trespass into my domain?")) {
            goldorStart = System.currentTimeMillis();
            return;
        }
        Matcher m = DEVICE.matcher(text);
        if (m.matches()) {
            long into = goldorStart > 0 ? System.currentTimeMillis() - goldorStart : -1;
            Device d = new Device(m.group(1), m.group(3), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), into);
            if (current.devices.isEmpty() || !current.devices.getLast().equals(d)) current.devices.add(d);
            return;
        }
        m = DEFEATED.matcher(text);
        if (m.matches()) {
            current.completed = true;
            current.hypixelTime = m.group(2).trim();
            current.hypixelRecord = m.group(3) != null;
            current.endedAt = System.currentTimeMillis();
            // The score and extra stats follow; finish a few seconds later if they don't come.
            finishIn = 100;
            return;
        }
        m = TEAM_SCORE.matcher(text);
        if (m.find()) {
            current.hypixelScore = Integer.parseInt(m.group(1).replace(",", ""));
            current.hypixelRank = m.group(2);
            if (current.endedAt == 0) current.endedAt = System.currentTimeMillis();
            finishIn = 20;
            return;
        }
        if (text.equals("> EXTRA STATS <")) {
            if (current.endedAt == 0) current.endedAt = System.currentTimeMillis();
            if (finishIn < 0 || finishIn > 20) finishIn = 20;
        }
    }

    private static void finish() {
        Run run = current;
        current = null;
        finishIn = -1;
        if (run == null) return;
        if (run.endedAt == 0) run.endedAt = System.currentTimeMillis();
        run.totalMs = run.endedAt - run.startedAt;
        RouteReplay.end();
        for (Object[] split : DungeonFeatures.finishedSplits()) run.splits.add(new Split((String) split[0], (long) split[1]));
        if (ScoreCalculator.started()) {
            run.score = ScoreCalculator.score();
            run.rank = rank(run.score);
            run.secrets = ScoreCalculator.foundSecrets();
            run.secretPercent = ScoreCalculator.secretPercentage();
            run.crypts = ScoreCalculator.crypts();
            run.deaths = ScoreCalculator.deaths();
        }
        DungeonClass self = SelfClass.get();
        run.dungeonClass = self == null || self == DungeonClass.UNKNOWN ? null : self.displayName();
        DungeonPlayerManager.DungeonPlayer[] players = DungeonPlayerManager.getPlayers();
        if (players != null) {
            for (DungeonPlayerManager.DungeonPlayer p : players) {
                if (p == null) continue;
                DungeonClass dc = p.dungeonClass();
                run.party.add(new Member(p.uuid() == null ? null : p.uuid().toString(), p.name(), dc == null ? null : dc.displayName()));
            }
        }
        // Personal bests for the floor, among completed runs.
        List<Run> all = runs();
        if (run.completed) {
            boolean bestTime = true, bestScore = run.effectiveScore() >= 0;
            for (Run other : all) {
                if (!other.completed || !run.floor.equals(other.floor)) continue;
                if (other.totalMs > 0 && other.totalMs <= run.totalMs) bestTime = false;
                if (other.effectiveScore() >= run.effectiveScore()) bestScore = false;
            }
            run.bestTime = bestTime;
            run.bestScore = bestScore;
        }
        all.addFirst(run);
        FeatureConfigs.RunHistory c = config();
        int max = c == null ? 500 : c.maxRuns;
        while (all.size() > max) all.removeLast();
        save();
        if (c != null && c.chatSummary) summary(run);
        if (c != null && c.announceBests && (run.bestTime || run.bestScore)) {
            String what = run.bestTime && run.bestScore ? "time and score" : run.bestTime ? "time" : "score";
            Sky2MAlerts.chat(Component.literal("New " + run.floor + " personal best " + what + "!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        DungeonRecapExtras.onRunFinished(run);
    }

    private static void summary(Run run) {
        MutableComponent line = Component.literal(run.floor + " ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(run.completed ? "cleared in " : "ended after ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(time(run.totalMs)).withStyle(ChatFormatting.WHITE));
        if (run.effectiveScore() >= 0) {
            line.append(Component.literal("  Score: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(run.effectiveScore() + " (" + run.effectiveRank() + ")").withStyle(ChatFormatting.AQUA));
        }
        if (run.secrets >= 0) line.append(Component.literal("  Secrets: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(String.valueOf(run.secrets)).withStyle(ChatFormatting.WHITE));
        if (run.deaths >= 0) line.append(Component.literal("  Deaths: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(String.valueOf(run.deaths)).withStyle(ChatFormatting.RED));
        line.append(Component.literal("  [History]").withStyle(s -> s.withColor(ChatFormatting.YELLOW)
            .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/s2 runs"))));
        Sky2MAlerts.chat(line);
    }

    static String rank(int score) {
        if (score >= 300) return "S+";
        if (score >= 270) return "S";
        if (score >= 230) return "A";
        if (score >= 160) return "B";
        if (score >= 100) return "C";
        return "D";
    }

    /** "12m 34.56s". */
    static String time(long ms) {
        if (ms <= 0) return "-";
        long minutes = ms / 60000;
        double seconds = (ms % 60000) / 1000.0;
        return (minutes > 0 ? minutes + "m " : "") + String.format(Locale.ROOT, "%.2fs", seconds);
    }

    // ------------------------------------------------------------------------------------------------ storage

    static synchronized List<Run> runs() {
        if (runs == null) {
            try {
                runs = Files.exists(FILE) ? GSON.fromJson(Files.readString(FILE), new TypeToken<List<Run>>() {}.getType()) : null;
            } catch (Exception e) {
                System.err.println("[Sky2M] Run History couldn't read its runs: " + e.getMessage());
            }
            if (runs == null) runs = new ArrayList<>();
        }
        return runs;
    }

    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Sky2M run history save");
        t.setDaemon(true);
        return t;
    });

    /** Serialises the runs now and writes the file on a background thread, so ending a run doesn't hitch. */
    static synchronized void save() {
        final String json;
        try {
            json = GSON.toJson(runs());
        } catch (Exception e) {
            System.err.println("[Sky2M] Run History couldn't save its runs: " + e.getMessage());
            return;
        }
        WRITER.execute(() -> {
            try {
                Files.createDirectories(FILE.getParent());
                Files.writeString(FILE, json);
            } catch (Exception e) {
                System.err.println("[Sky2M] Run History couldn't save its runs: " + e.getMessage());
            }
        });
    }

    static synchronized void delete(Run run) {
        runs().remove(run);
        RouteReplay.delete(run.startedAt);
        save();
    }
}
