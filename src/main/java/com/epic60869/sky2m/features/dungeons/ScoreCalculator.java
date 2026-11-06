package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
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

    /** Each Bat killed in a run is +1: Hypixel tells only the killer, and teammates' mods say so in party chat. */
    private static final Pattern BAT_PARTY = Pattern.compile("^Party > (.+): (?:Bat dead!?|Bat Killed!)$");
    private static final String BAT_HYPIXEL = "A Bat has been slain. +1 Bonus Score";

    private static boolean started;
    private static boolean watcherCleared;
    private static boolean bloodDone;
    private static boolean had270;
    private static boolean had300;
    private static boolean mimicKilled;
    private static boolean princeKilled;
    private static int batsKilled;
    /** Sticky: once at the boss, Blood and the boss room are no longer rooms still to come. */
    private static boolean reachedBoss;
    private static int deathCount;
    /** Who died first this run, and whether they have a Legendary Spirit pet (null while that isn't known yet). */
    private static String firstDeath;
    private static volatile Boolean firstDeathSpirit;
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
    private static int simTotalRooms;
    private static int simTotalSecrets;

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
            if (ticks % 100 == 0 && !simActive()) {
                // Teammates' Spirit pets looked up ahead of time, so a first death is scored at once (SpiritPetCheck).
                var players = com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager.getPlayers();
                if (players != null) {
                    List<String> names = new ArrayList<>();
                    for (var p : players) if (p != null) names.add(p.name());
                    SpiritPetCheck.prefetch(names);
                }
            }
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
        batsKilled = 0;
        reachedBoss = false;
        deathCount = 0;
        firstDeath = null;
        firstDeathSpirit = null;
        foundSecrets = 0;
        cryptsCount = 0;
        secretPercentage = 0;
        clearedPercentage = 0;
        completedRooms = 0;
        secondsElapsed = 0;
        maxPuzzles = 0;
        puzzlesDone = 0;
        score = 0;
        simTotalRooms = 0;
        simTotalSecrets = 0;
    }

    public static boolean started() { return started; }
    public static int score() { return score; }
    public static int foundSecrets() { return foundSecrets; }
    public static int crypts() { return cryptsCount; }
    public static int deaths() { return deathCount; }
    public static double secretPercentage() { return secretPercentage; }
    public static int secondsElapsed() { return secondsElapsed; }
    public static boolean mimicKilled() { return mimicKilled || (simActive() && com.epic60869.sky2m.sim.roomsim.SimScore.isMimicKilled()) || DungeonScore.wasMimicKilled() || teammateSaw(com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Flag.MIMIC_KILLED); }
    /** Like {@link #mimicKilled()}, but only when someone saw it die, not guessed from all the secrets being found. */
    public static boolean mimicSeenKilled() { return mimicKilled || DungeonScore.wasMimicSeenKilled() || teammateSaw(com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Flag.MIMIC_KILLED); }
    public static boolean princeKilled() { return princeKilled || DungeonScore.wasPrinceKilled() || teammateSaw(com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Flag.PRINCE_KILLED); }

    public static int bats() { return batsKilled; }
    /** A Bat you killed with no line from Hypixel ({@link WitherBladeBats}). */
    public static void countBat() { batsKilled++; }

    /**
     * Whether a chat line is a Bat kill to count. Your own party line is skipped: Hypixel's line already counted that
     * kill. Public for the game test.
     */
    public static boolean isBatKill(String text, String ownName) {
        if (text.equals(BAT_HYPIXEL)) return true;
        Matcher party = BAT_PARTY.matcher(text);
        if (!party.matches()) return false;
        for (String word : party.group(1).split(" ")) if (word.equalsIgnoreCase(ownName)) return false;
        return true;
    }

    /**
     * Rooms to score: the completed ones, plus Blood and the boss room while they're still to come. From the boss on
     * neither is added, so a skipped room costs what it costs on Hypixel. Public for the game test.
     */
    public static int effectiveRooms(int completed, boolean bloodDone, boolean inBoss) {
        return completed + (bloodDone || inBoss ? 0 : 1) + (inBoss ? 0 : 1);
    }

    /** A teammate's Sky2M (or another mod's party socket) said this happened (Party Dungeon Data). */
    private static boolean teammateSaw(com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Flag flag) {
        var fact = com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.flagFact(flag);
        return fact != null && fact.source() != com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Source.SELF;
    }

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

    /**
     * A death line. The first one of the run decides the Spirit pet rule: it costs 1 skill point instead of 2 when that
     * player has a Legendary Spirit pet in their pets menu (the wiki's Dungeon Score page; {@link SpiritPetCheck}).
     * {@code who} is null for "You were ...".
     */
    private static void onDeath(String who) {
        deathCount++;
        if (deathCount != 1) return;
        String name = who != null ? who : Minecraft.getInstance().getUser().getName();
        firstDeath = name;
        firstDeathSpirit = SpiritPetCheck.known(name);
        if (firstDeathSpirit == null) {
            SpiritPetCheck.lookup(name).thenAccept(has -> {
                if (name.equals(firstDeath)) {
                    firstDeathSpirit = has;
                    Minecraft.getInstance().execute(ScoreCalculator::recalculate);
                }
            });
        }
    }

    /**
     * Skill points the deaths cost: 2 each, 1 for the first when its player has a Legendary Spirit pet. Until that's
     * known ({@code firstSpirit} null) the first counts 2, so the score never reads higher than it is. Public for the
     * game test.
     */
    public static int deathPenalty(int deaths, Boolean firstSpirit) {
        return Math.max(0, deaths * 2 - (deaths > 0 && Boolean.TRUE.equals(firstSpirit) ? 1 : 0));
    }

    /** The run's first death and whether the Spirit pet rule applies to it, for the score HUD and the game test. */
    public static String firstDeath() { return firstDeath; }
    public static Boolean firstDeathSpirit() { return firstDeathSpirit; }
    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        if (text.equals("[NPC] Mort: Here, I found this map when I first entered the dungeon.")) started = true;
        else if (text.equals("[BOSS] The Watcher: You have proven yourself. You may pass.")) watcherCleared = true;
        else if (text.startsWith("[BOSS] ") && !text.startsWith("[BOSS] The Watcher:")) reachedBoss = true;
        else if (isBatKill(text, Minecraft.getInstance().getUser().getName())) batsKilled++;
        else if (text.startsWith(" ☠ ") && !text.contains("reconnected")) {
            Matcher death = DEATH.matcher(text);
            if (death.matches()) onDeath(death.group(1));
        }

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
        if (simActive()) feedSim();
        recalculate();
    }

    private static boolean simActive() {
        return com.epic60869.sky2m.sim.roomsim.SimState.isActive();
    }

    /** Dungeon Sim: its sidebar has no clock or Cleared line, so the run clock, deaths and room total come from the sim itself. */
    private static void feedSim() {
        if (!com.epic60869.sky2m.sim.roomsim.SimRun.isRunning()) return;
        started = true;
        secondsElapsed = (int) (com.epic60869.sky2m.sim.roomsim.SimRun.elapsedMs() / 1000L);
        deathCount = com.epic60869.sky2m.sim.roomsim.SimScore.deaths();
        simTotalRooms = com.epic60869.sky2m.sim.roomsim.SimScore.roomsTotal();
        simTotalSecrets = com.epic60869.sky2m.sim.roomsim.SimScore.secretsTotal();
        bloodDone = true;
    }

    /** Total secrets in the dungeon: the sim's count, or worked out from the found count and the tab list's percentage. 0 until known. */
    public static int totalSecrets() {
        if (simActive() && simTotalSecrets > 0) return simTotalSecrets;
        return secretPercentage > 0 ? (int) Math.round(foundSecrets * 100.0 / secretPercentage) : 0;
    }

    /** Secrets the floor asks for to fill the secrets part of the score (NoammAddons' required fraction of the total). */
    public static int secretsNeeded() {
        int total = totalSecrets();
        return total <= 0 ? 0 : (int) Math.ceil(total * REQUIRED_SECRETS.getOrDefault(floorKey(), 1.0));
    }

    /**
     * Secrets still enough for 300 score if the rest of the run is perfect, like Odin's neededSecretsAmount (BSD-3-Clause):
     * bonus score covers some of the 40 points secrets are worth, and each death's skill point needs one more. 0 until the
     * total is known.
     */
    public static int secretsForSPlus() {
        int total = totalSecrets();
        int penalty = simActive() ? com.epic60869.sky2m.sim.roomsim.SimScore.deathPenalty() : deathPenalty(deathCount, firstDeathSpirit);
        return total <= 0 ? 0 : secretsForSPlus(total, REQUIRED_SECRETS.getOrDefault(floorKey(), 1.0), bonusScore(), penalty);
    }

    /** Public for the game test. */
    public static int secretsForSPlus(int total, double required, int bonus, int deathPenalty) {
        int points = Math.clamp(40 - bonus + deathPenalty, 0, 40);
        return (int) Math.ceil(total * required * points / 40.0);
    }

    private static int bonusScore() {
        int bonus = Math.min(cryptsCount, 5);
        if (mimicKilled() && floorNumber() > 5) bonus += 2;
        if (princeKilled()) bonus += 1;
        bonus += batsKilled;
        if (isPaul()) bonus += 10;
        return bonus;
    }

    private static int totalRooms() {
        if (simActive() && simTotalRooms > 0) return simTotalRooms;
        return completedRooms > 0 && clearedPercentage > 0 ? (int) Math.floor(completedRooms / (clearedPercentage / 100.0) + 0.4) : 36;
    }

    /** Whether Paul's EZPZ (+10 score) is active: from Hypixel's election data, or the Force Paul setting. */
    public static boolean isPaul() {
        // The sim has no mayor: /simscore paul decides, the same switch the sim's own score reads.
        if (simActive()) return com.epic60869.sky2m.sim.roomsim.SimScore.isPaulEzpz();
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

        int bonus = bonusScore();

        int limit = TIME_LIMIT.getOrDefault(floor, 100);
        int speed = secondsElapsed <= limit ? 100 : Math.max(0, (int) (100 - speedDeduction((secondsElapsed - limit) * 100f / limit)));

        // Dungeon Sim: its own boss flag, and its death penalty (a Spirit pet's first death is -1, any other -2).
        if (!simActive() && DungeonManager.isInBoss()) reachedBoss = true;
        boolean inBoss = simActive() ? com.epic60869.sky2m.sim.roomsim.SimBoss.isActive() : reachedBoss;
        int effectiveRooms = effectiveRooms(completedRooms, bloodDone, inBoss);
        double required = REQUIRED_SECRETS.getOrDefault(floor, 1.0);
        int secretsScore = (int) Math.max(0, Math.min(40, Math.floor(secretPercentage / required / 100.0 * 40.0)));
        double roomRatio = effectiveRooms / (double) totalRooms();
        int roomScore = (int) Math.max(0, Math.min(60, roomRatio * 60.0));
        int skillRooms = (int) Math.max(0, Math.min(80, Math.floor(roomRatio * 80)));
        int puzzlePenalty = Math.max(0, maxPuzzles - puzzlesDone) * 10;
        int deathPenalty = simActive() ? com.epic60869.sky2m.sim.roomsim.SimScore.deathPenalty() : deathPenalty(deathCount, firstDeathSpirit);

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
        if (party && mc.getConnection() != null && !simActive()) mc.getConnection().sendCommand("pc [S2M] " + message);
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
