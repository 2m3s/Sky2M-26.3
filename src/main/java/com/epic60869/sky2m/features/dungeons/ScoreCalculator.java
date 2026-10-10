package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonScore;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.mayor.MayorUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon score, ported from NoammAddons' ScoreCalculation (https://github.com/Noamm9/NoammAddons, CC0-1.0).
 * Everything is read from the tab list, the sidebar and chat, so it works without Skyblocker's dungeon tracking
 * having started. Used by the Score Display HUD, the dungeon map's extra info and the 270/300 alerts.
 */
public final class ScoreCalculator {
    private static final Pattern SECRETS_FOUND = Pattern.compile("Secrets Found: (\\d+)$");
    private static final Pattern SECRETS_PERCENT = Pattern.compile("Secrets Found: ([\\d.]+)%");
    private static final Pattern CRYPTS = Pattern.compile("Crypts: (\\d+)");
    private static final Pattern COMPLETED_ROOMS = Pattern.compile("Completed Rooms: (\\d+)");
    private static final Pattern PUZZLE_COUNT = Pattern.compile("Puzzles: \\((\\d)\\)");
    private static final Pattern PUZZLE = Pattern.compile("^ ?(.+): \\[([✦✔✖])");
    private static final Pattern CLEARED = Pattern.compile("Cleared: (\\d+)%");
    private static final Pattern ELAPSED = Pattern.compile("Time Elapsed: (?:(\\d+)h )?(?:(\\d+)m )?(?:(\\d+)s)?");
    private static final Pattern DEATH = Pattern.compile("^ ☠ (?:You were|(\\w+)) (.+?)(?: and became a ghost)?\\.$");

    private static final Map<String, Double> REQUIRED_SECRETS = Map.ofEntries(
        Map.entry("E", 0.3), Map.entry("F1", 0.3), Map.entry("F2", 0.4), Map.entry("F3", 0.5), Map.entry("F4", 0.6),
        Map.entry("F5", 0.7), Map.entry("F6", 0.85), Map.entry("F7", 1.0), Map.entry("M1", 1.0), Map.entry("M2", 1.0),
        Map.entry("M3", 1.0), Map.entry("M4", 1.0), Map.entry("M5", 1.0), Map.entry("M6", 1.0), Map.entry("M7", 1.0));
    private static final Map<String, Integer> TIME_LIMIT = Map.ofEntries(
        Map.entry("E", 600), Map.entry("F1", 600), Map.entry("F2", 600), Map.entry("F3", 600), Map.entry("F4", 720),
        Map.entry("F5", 600), Map.entry("F6", 720), Map.entry("F7", 840), Map.entry("M1", 480), Map.entry("M2", 480),
        Map.entry("M3", 480), Map.entry("M4", 480), Map.entry("M5", 480), Map.entry("M6", 600), Map.entry("M7", 840));
    private static final Set<String> MIMIC_MESSAGES = Set.of(
        "mimic dead!", "mimic dead", "mimic killed!", "mimic killed", "$skytils-dungeon-score-mimic$", "child destroyed!",
        "mimic obliterated!", "mimic exorcised!", "mimic destroyed!", "mimic annhilated!", "breefing killed", "breefing dead");
    private static final Set<String> PRINCE_MESSAGES = Set.of(
        "prince dead", "prince dead!", "$skytils-dungeon-score-prince$", "prince killed", "prince slain", "prince killed!",
        "a prince falls. +1 bonus score");

    private static boolean started;
    private static boolean watcherCleared;
    private static boolean bloodDone;
    private static boolean had270;
    private static boolean had300;
    private static boolean mimicKilled;
    private static boolean princeKilled;
    private static int deathCount;
    private static int foundSecrets;
    private static int cryptsCount;
    private static double secretPercentage;
    private static int clearedPercentage;
    private static int completedRooms;
    private static int secondsElapsed;
    private static int maxPuzzles;
    private static int puzzlesDone;
    private static int score;
    private static int ticks;

    private ScoreCalculator() {}

    private static FeatureConfigs.Score config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.score;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        Sky2MChat.onChat(message -> onChat(message.text()));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 != 0 || !Sky2MLocation.inDungeon()) return;
            update();
        });
    }

    public static void reset() {
        started = false;
        watcherCleared = false;
        bloodDone = false;
        had270 = false;
        had300 = false;
        mimicKilled = false;
        princeKilled = false;
        deathCount = 0;
        foundSecrets = 0;
        cryptsCount = 0;
        secretPercentage = 0;
        clearedPercentage = 0;
        completedRooms = 0;
        secondsElapsed = 0;
        maxPuzzles = 0;
        puzzlesDone = 0;
        score = 0;
    }

    public static boolean started() { return started; }
    public static int score() { return score; }
    public static int foundSecrets() { return foundSecrets; }
    public static int crypts() { return cryptsCount; }
    public static int deaths() { return deathCount; }
    public static double secretPercentage() { return secretPercentage; }
    public static int secondsElapsed() { return secondsElapsed; }
    public static boolean mimicKilled() { return mimicKilled || DungeonScore.wasMimicKilled(); }
    public static boolean princeKilled() { return princeKilled || DungeonScore.wasPrinceKilled(); }

    public static int floorNumber() {
        String floor = Sky2MLocation.dungeonFloor();
        if (floor.isEmpty() || !Character.isDigit(floor.charAt(floor.length() - 1))) return 0;
        return floor.charAt(floor.length() - 1) - '0';
    }

    /** "E", "F1".."F7" or "M1".."M7", as used by the score tables. */
    private static String floorKey() {
        String floor = Sky2MLocation.dungeonFloor();
        return floor.equals("E0") || floor.equals("F0") ? "E" : floor;
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        if (text.equals("[NPC] Mort: Here, I found this map when I first entered the dungeon.")) started = true;
        else if (text.equals("[BOSS] The Watcher: You have proven yourself. You may pass.")) watcherCleared = true;
        else if (text.startsWith(" ☠ ") && !text.contains("reconnected") && DEATH.matcher(text).matches()) deathCount++;

        String lower = text.toLowerCase(Locale.ROOT);
        if (!princeKilled && PRINCE_MESSAGES.stream().anyMatch(lower::contains)) princeKilled = true;
        if (floorNumber() > 5 && !DungeonManager.isInBoss() && MIMIC_MESSAGES.stream().anyMatch(lower::contains)) mimicKilled = true;
    }

    private static void update() {
        int puzzlesSeen = 0;
        int done = 0;
        boolean inPuzzles = false;
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = com.epic60869.sky2m.custom.util.Compat.rawTabName(info);
            if (name == null) continue;
            String line = Sky2MLocation.strip(name.getString());
            Matcher m;
            if ((m = CRYPTS.matcher(line)).find()) cryptsCount = Integer.parseInt(m.group(1));
            else if ((m = COMPLETED_ROOMS.matcher(line)).find()) completedRooms = Integer.parseInt(m.group(1));
            else if ((m = SECRETS_PERCENT.matcher(line)).find()) secretPercentage = Double.parseDouble(m.group(1));
            else if ((m = SECRETS_FOUND.matcher(line.trim())).find()) foundSecrets = Integer.parseInt(m.group(1));
            else if ((m = PUZZLE_COUNT.matcher(line)).find()) {
                maxPuzzles = Integer.parseInt(m.group(1));
                inPuzzles = true;
                continue;
            }
            if (inPuzzles) {
                Matcher p = PUZZLE.matcher(line);
                if (p.find()) {
                    puzzlesSeen++;
                    if (p.group(2).equals("✔")) done++;
                } else if (line.isBlank() || puzzlesSeen >= maxPuzzles) {
                    inPuzzles = false;
                }
            }
        }
        puzzlesDone = done;

        // The sidebar lines, and the same lines read from the scoreboard teams (works when a scoreboard mod hides the sidebar).
        List<String> sidebar = new ArrayList<>(Sky2MLocation.scoreboard());
        sidebar.addAll(Sky2MLocation.teamLines());
        for (String line : sidebar) {
            Matcher m;
            if ((m = CLEARED.matcher(line)).find()) {
                int cleared = Integer.parseInt(m.group(1));
                if (cleared != clearedPercentage && watcherCleared) bloodDone = true;
                clearedPercentage = cleared;
            } else if (line.contains("Time Elapsed:") && (m = ELAPSED.matcher(line)).find()) {
                int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
                int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                int s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                secondsElapsed = h * 3600 + min * 60 + s;
                if (secondsElapsed > 0) started = true;
            }
        }
        recalculate();
    }

    private static int totalRooms() {
        return completedRooms > 0 && clearedPercentage > 0 ? (int) Math.floor(completedRooms / (clearedPercentage / 100.0) + 0.4) : 36;
    }

    private static boolean isPaul() {
        FeatureConfigs.Score config = config();
        if (config != null && config.forcePaul) return true;
        try {
            return MayorUtils.getActivePerks().contains("EZPZ");
        } catch (Exception e) {
            return false;
        }
    }

    private static void recalculate() {
        if (!started) return;
        String floor = floorKey();
        if (floor.isEmpty()) return;

        int bonus = Math.min(cryptsCount, 5);
        if (mimicKilled() && floorNumber() > 5) bonus += 2;
        if (princeKilled()) bonus += 1;
        if (isPaul()) bonus += 10;

        int limit = TIME_LIMIT.getOrDefault(floor, 100);
        int speed = secondsElapsed <= limit ? 100 : Math.max(0, (int) (100 - speedDeduction((secondsElapsed - limit) * 100f / limit)));

        boolean inBoss = DungeonManager.isInBoss();
        int effectiveRooms = completedRooms + (bloodDone ? 0 : 1) + (inBoss ? 0 : 1);
        double required = REQUIRED_SECRETS.getOrDefault(floor, 1.0);
        int secretsScore = (int) Math.max(0, Math.min(40, Math.floor(secretPercentage / required / 100.0 * 40.0)));
        double roomRatio = effectiveRooms / (double) totalRooms();
        int roomScore = (int) Math.max(0, Math.min(60, roomRatio * 60.0));
        int skillRooms = (int) Math.max(0, Math.min(80, Math.floor(roomRatio * 80)));
        int puzzlePenalty = Math.max(0, maxPuzzles - puzzlesDone) * 10;
        int deathPenalty = Math.max(0, deathCount * 2 - 1);

        score = secretsScore + roomScore + Math.max(20, Math.min(100, 20 + skillRooms - puzzlePenalty - deathPenalty)) + bonus + speed;

        // Like NoammAddons, each milestone the score passes gets its own message (both when it jumps straight past 300).
        if (score >= 270 && !had270) {
            had270 = true;
            milestone(270);
        }
        if (score >= 300 && !had300) {
            had300 = true;
            milestone(300);
        }
    }

    private static float speedDeduction(float percentage) {
        float over = percentage;
        float deduction = 0;
        float[][] steps = {{20, 2}, {20, 3.5f}, {10, 4}, {10, 5}};
        for (float[] step : steps) {
            if (over <= 0) return deduction;
            deduction += Math.min(over, step[0]) / step[1];
            over -= step[0];
        }
        if (over > 0) deduction += over / 6f;
        return deduction;
    }

    private static void milestone(int milestone) {
        FeatureConfigs.Score config = config();
        if (config == null) return;
        boolean alert = milestone == 300 ? config.alert300 : config.alert270;
        boolean party = milestone == 300 ? config.party300 : config.party270;
        String message = (milestone == 300 ? config.message300 : config.message270).replace("[score]", String.valueOf(milestone));
        Minecraft mc = Minecraft.getInstance();
        if (party && mc.getConnection() != null) mc.getConnection().sendCommand("pc [S2M] " + message);
        // NoammAddons' chat line: "270 score reached in 5m 12s || M7."
        if (config.timeMessage) {
            String floor = Sky2MLocation.dungeonFloor();
            String floorColour = floor.startsWith("M") ? "§c" : "§a";
            Sky2MAlerts.chat(Component.literal("§e" + milestone + "§a score reached in §6" + formatTime(secondsElapsed) + " §f|| " + floorColour + (floor.isEmpty() ? "?" : floor) + "."));
        }
        if (!alert) return;
        Sky2MAlerts.title(Component.literal((milestone == 300 ? "§c" : "§e") + message), Component.empty());
        var sound = SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 0f);
        mc.getSoundManager().play(sound);
    }

    public static String formatTime(int seconds) {
        if (seconds <= 0) return "0s";
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0 || sb.isEmpty()) sb.append(s).append('s');
        return sb.toString().trim();
    }

    /** §c below 270, §e below 300, §a at 300+. */
    public static String colorizeScore(int score) {
        return (score < 270 ? "§c" : score < 300 ? "§e" : "§a") + score;
    }

    /** Colour code by how close value is to max, like NoammAddons' colorCodeByPercent. */
    public static String colorByPercent(int value, int max, boolean reversed) {
        float percentage = Math.max(0, Math.min(value, Math.max(1, max))) * 100f / Math.max(1, max);
        if (percentage > 75) return reversed ? "§c" : "§a";
        if (percentage > 50) return reversed ? "§6" : "§e";
        if (percentage > 25) return reversed ? "§e" : "§6";
        return reversed ? "§a" : "§c";
    }
}
