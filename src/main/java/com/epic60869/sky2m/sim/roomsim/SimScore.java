/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModChat;

import java.util.Locale;

/**
 * The score side of a sim clear: crypts, the prince, bats, secrets and rooms.
 *
 * <p>killer560 (2026-09-28): "make sure it has crypt and prince logic and bat logic for calcuating 300 score in
 * the non individual room test." So a full-map run has to be able to answer "is this 300" the same way a real
 * one does, because a route that clears fast and misses 300 is not a route worth practising.
 *
 * <p><b>What is modelled and what is not.</b> Catacombs score is Skill + Explore + Time + Bonus. The parts a sim
 * can honestly reproduce are the ones that depend on what is IN the map and what the player did to it: crypts
 * blown, the mimic (prince) killed, bats killed, secrets found, rooms completed. Time is real because the run
 * clock is real. Skill's death penalty is real because deaths are real.
 *
 * <p>The formulas and per-floor tables (secret requirement, time limit) are the mod's own Score Calculator's,
 * which reads the sim's tab list and sidebar while the sim is active, so both say the same number. Puzzle fails
 * come from the sim's own puzzles ({@code SimPuzzles.reportFail}); the secret total is the room database's for the
 * rooms that were built, not Hypixel's own count.
 */
public final class SimScore {

    /**
     * Bonus scoring, taken from the wiki (checked 2026-09-28) rather than remembered.
     *
     * <p>ONE point per crypt, capped at five - not five points for reaching five, which is what this file had
     * first and is wrong in the way that matters: it made four crypts worth nothing when they are worth four,
     * so a run that was one crypt short of 300 would have read as five short.
     *
     * <p>The mimic is two, on Floor VI and above. Paul's EZPZ perk is ten more when the mayor is Paul, which is
     * a real part of whether a run makes 300 and is therefore a switch rather than something left out.
     */
    private static final int MAX_CRYPT_BONUS = 5;
    private static final int MIMIC_BONUS = 2;
    private static final int PAUL_EZPZ_BONUS = 10;

    /** Whether Paul's EZPZ is on for this run. */
    private static boolean paulEzpz;

    // Bumped from the render thread (SimMimic's chest open) and the server thread (secrets, bats, crypts, the
    // mimic), so every mutator is synchronized: two ++ landing together lost one.
    private static int cryptsBlown;
    private static boolean mimicKilled;
    private static int batsKilled;
    private static int secretsFound;
    private static int secretsTotal;
    private static int roomsCleared;
    private static int roomsTotal;
    private static int deaths;
    /** The first death of the run was by a player with a Legendary Spirit pet: it costs one point instead of two. */
    private static boolean spiritDeath;
    private static boolean princeKilled;
    private static int puzzleFails;
    private static boolean had270;
    private static boolean had300;
    /** Rooms stood in this run, for the rooms that clear by being visited (the Fairy room, rooms with no mobs). */
    private static final java.util.Set<String> VISITED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The share of a floor's secrets needed for full marks, per floor - the same table the mod's own Score
     * Calculator ({@code features.dungeons.ScoreCalculator}) uses, so the sim and the HUD agree. Master Mode and F7
     * ask for every secret; lower Normal floors for less.
     */
    private static final java.util.Map<String, Double> REQUIRED_SECRETS = java.util.Map.ofEntries(
            java.util.Map.entry("E", 0.3), java.util.Map.entry("F1", 0.3), java.util.Map.entry("F2", 0.4),
            java.util.Map.entry("F3", 0.5), java.util.Map.entry("F4", 0.6), java.util.Map.entry("F5", 0.7),
            java.util.Map.entry("F6", 0.85), java.util.Map.entry("F7", 1.0), java.util.Map.entry("M1", 1.0),
            java.util.Map.entry("M2", 1.0), java.util.Map.entry("M3", 1.0), java.util.Map.entry("M4", 1.0),
            java.util.Map.entry("M5", 1.0), java.util.Map.entry("M6", 1.0), java.util.Map.entry("M7", 1.0));

    /** Seconds before the speed score starts dropping, per floor - the Score Calculator's table again. */
    private static final java.util.Map<String, Integer> TIME_LIMIT = java.util.Map.ofEntries(
            java.util.Map.entry("E", 600), java.util.Map.entry("F1", 600), java.util.Map.entry("F2", 600),
            java.util.Map.entry("F3", 600), java.util.Map.entry("F4", 720), java.util.Map.entry("F5", 600),
            java.util.Map.entry("F6", 720), java.util.Map.entry("F7", 840), java.util.Map.entry("M1", 480),
            java.util.Map.entry("M2", 480), java.util.Map.entry("M3", 480), java.util.Map.entry("M4", 480),
            java.util.Map.entry("M5", 480), java.util.Map.entry("M6", 600), java.util.Map.entry("M7", 840));

    private SimScore() {
    }

    /** Clears everything, for a new run. */
    public static synchronized void reset(int mapSecretTotal, int mapRoomTotal) {
        cryptsBlown = 0;
        mimicKilled = false;
        batsKilled = 0;
        secretsFound = 0;
        FOUND_BY_ROOM.clear();
        deaths = 0;
        spiritDeath = false;
        princeKilled = false;
        puzzleFails = 0;
        had270 = false;
        had300 = false;
        VISITED.clear();
        roomsCleared = 0;
        secretsTotal = mapSecretTotal;
        roomsTotal = mapRoomTotal;
        paulEzpz = false;
    }

    public static synchronized void cryptBlown() {
        cryptsBlown++;
    }

    /** The mimic - "prince" - killed. Worth two bonus points and nothing else. */
    public static synchronized void mimicKilled() {
        mimicKilled = true;
    }

    /**
     * A bat killed.
     *
     * <p>Bats count as SECRETS, not as their own score component - which is the whole reason they matter to a
     * 300 run and the reason they are tracked separately here: a player who cannot tell a bat secret from a
     * chest secret cannot tell why their secret count is short.
     */
    public static synchronized void batKilled() {
        batsKilled++;
        secretsFound++;
        SimBreakerState.secretFound();
    }

    public static synchronized void secretFound() {
        secretsFound++;
        SimBreakerState.secretFound();
    }

    /**
     * A secret found AT {@code at}: counted for the run, and for the room standing there, which is the count
     * Hypixel's action bar shows ("x/y Secrets") while you are in that room - {@link SimActionBar} sends it, and
     * Auto Routes' {@code await:<n>} and the live map read it, as they do on Hypixel. Without the per-room count
     * the sim sent no such line at all, so an {@code await} node waited forever in the sim (2026-10-05).
     */
    public static synchronized void secretFound(net.minecraft.core.BlockPos at) {
        secretsFound++;
        // Every secret gives the Dungeonbreaker 2 charges back (killer560, 2026-10-06) - see SimBreakerState.
        SimBreakerState.secretFound();
        String room = roomAt(at);
        if (room != null) {
            FOUND_BY_ROOM.merge(room, 1, Integer::sum);
        }
    }

    /** Secrets found so far in the named room this run. */
    public static int foundInRoom(String room) {
        return room == null ? 0 : FOUND_BY_ROOM.getOrDefault(room, 0);
    }

    /** The placed room covering a world position, by the same tile rounding {@code DungeonLayout.roomAtWorld} uses. */
    public static String roomAt(net.minecraft.core.BlockPos at) {
        if (at == null) {
            return null;
        }
        int grid = com.epic60869.sky2m.sim.livemap.DungeonLayout.GRID;
        int gx = Math.max(0, Math.min(grid - 1, (int) Math.round((at.getX()
                - com.epic60869.sky2m.sim.livemap.LiveMapFeature.START_X) / 32.0) * 2));
        int gz = Math.max(0, Math.min(grid - 1, (int) Math.round((at.getZ()
                - com.epic60869.sky2m.sim.livemap.LiveMapFeature.START_Z) / 32.0) * 2));
        return SimRoomIndex.nameAtCell(gz * grid + gx);
    }

    private static final java.util.Map<String, Integer> FOUND_BY_ROOM = new java.util.concurrent.ConcurrentHashMap<>();

    public static synchronized void roomCleared() {
        roomsCleared++;
    }

    public static synchronized void died() {
        died(false);
    }

    /**
     * A death of the run. {@code spirit}: it was the first death, by a player with a Legendary Spirit pet, so it costs one skill
     * point instead of two (Hypixel's rule; {@code SimClassKit.hasSpiritForScore}).
     */
    public static synchronized void died(boolean spirit) {
        if (spirit && deaths == 0) {
            spiritDeath = true;
        }
        deaths++;
    }

    /** "A Prince falls. +1 Bonus Score": a crypt prince killed. */
    public static synchronized void princeKilled() {
        princeKilled = true;
    }

    public static boolean isPrinceKilled() {
        return princeKilled;
    }

    /** A puzzle failed ({@code SimPuzzles.reportFail}). The skill penalty itself comes from the puzzle not being done. */
    public static synchronized void puzzleFailed() {
        puzzleFails++;
    }

    public static int puzzleFails() {
        return puzzleFails;
    }

    /** Skill points the deaths cost: two each, one for a first death with a Spirit pet. */
    public static int deathPenalty() {
        return Math.max(0, deaths * 2 - (spiritDeath ? 1 : 0));
    }

    /** Explore: rooms completed and secrets found, as a percentage pair. */
    /** Secrets found so far this run - read by the sim's sidebar. */
    public static int secretsFound() {
        return secretsFound;
    }

    /** Secrets the generated map contains, or 0 before a map has been built. */
    public static int secretsTotal() {
        return secretsTotal;
    }

    /**
     * The rest of the run's counters, for the HUDs that normally read them off Hypixel's TAB LIST.
     *
     * <p>A singleplayer sim has no tab list - the integrated server lists one player and none of Hypixel's
     * "Secrets Found"/"Crypts"/"Completed Rooms" display names exist - so the Dungeon Info HUD and the Score
     * Calculator found nothing all run and reported "?" ({@code No tab-list 'Secrets Found' line matched} in
     * his log). They read these instead while the sim is active. Same numbers this class already scores with,
     * so the HUD and the sim's own score screen cannot disagree.
     */
    public static int roomsCleared() {
        return roomsCleared;
    }

    public static int roomsTotal() {
        return roomsTotal;
    }

    public static int deaths() {
        return deaths;
    }

    /** Secrets found as a percentage of the floor's total, or 0 before a map has been built. */
    public static double secretsPercent() {
        return secretsTotal <= 0 ? 0.0 : 100.0 * secretsFound / secretsTotal;
    }

    // ------------------------------------------------------------------ Hypixel's score
    //
    // The same formulas, inputs and tables as the mod's own Score Calculator (features.dungeons.ScoreCalculator,
    // after NoammAddons), which reads the sim's tab list and sidebar while the sim is active - so /simscore, the
    // sidebar and the Score HUD say the same number.

    /** "E", "F1".."M7": the floor the score tables are read for. */
    private static String floorKey() {
        String f = SimState.floorLabel();
        return f == null || f.isEmpty() ? "F7" : f.toUpperCase(Locale.ROOT);
    }

    private static int floorNumber() {
        String f = floorKey();
        char last = f.charAt(f.length() - 1);
        return Character.isDigit(last) ? last - '0' : 0;
    }

    /** The share of the floor's secrets that fills the secrets part of the score. */
    public static double secretRequirement() {
        return REQUIRED_SECRETS.getOrDefault(floorKey(), 1.0);
    }

    /** Secrets needed for the full 40: the requirement of the total, rounded up. */
    public static int secretsNeeded() {
        return secretsTotal <= 0 ? 0 : (int) Math.ceil(secretsTotal * secretRequirement());
    }

    public static int timeLimitSeconds() {
        return TIME_LIMIT.getOrDefault(floorKey(), 600);
    }

    /** Rooms the score counts: cleared ones, plus the boss room Hypixel counts until you are in it. */
    private static double roomRatio() {
        if (roomsTotal <= 0) {
            return 0;
        }
        int effective = roomsCleared + (SimBoss.isActive() ? 0 : 1);
        return effective / (double) roomsTotal;
    }

    /** The tab list's "Secrets Found: x%", to its two decimals - the number the Score Calculator reads. */
    private static double shownSecretPercent() {
        return Math.round(secretsPercent() * 100.0) / 100.0;
    }

    /** Explore, secrets half: up to 40, for the floor's requirement of the secrets. */
    public static int secretScore() {
        return (int) Math.max(0, Math.min(40, Math.floor(shownSecretPercent() / secretRequirement() / 100.0 * 40.0)));
    }

    /** Explore, rooms half: up to 60. */
    public static int roomScore() {
        return (int) Math.max(0, Math.min(60, roomRatio() * 60.0));
    }

    /** Explore: rooms (60) and secrets (40). */
    public static int exploreScore() {
        return roomsTotal <= 0 ? 0 : secretScore() + roomScore();
    }

    /** Puzzles on the floor (the tab list's "Puzzles: (n)"). */
    public static int puzzleCount() {
        int n = 0;
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            if (SimRoomPuzzles.puzzleKey(placed.name()) != null) {
                n++;
            }
        }
        return n;
    }

    /** Puzzles solved (a tick on the tab list). */
    public static int puzzlesDone() {
        int n = 0;
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            String key = SimRoomPuzzles.puzzleKey(placed.name());
            if (key != null && !SimRoomState.isFailed(placed.name())
                    && com.epic60869.sky2m.sim.roomsim.puzzles.SimPuzzles.isComplete(key)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Skill: 20, plus up to 80 for the rooms cleared, less 10 per puzzle not solved (failed or not done) and the
     * death penalty ({@link #deathPenalty}); between 20 and 100.
     */
    public static int skillScore() {
        int skillRooms = (int) Math.max(0, Math.min(80, Math.floor(roomRatio() * 80)));
        int puzzlePenalty = Math.max(0, puzzleCount() - puzzlesDone()) * 10;
        return Math.max(20, Math.min(100, 20 + skillRooms - puzzlePenalty - deathPenalty()));
    }

    /** Speed: 100 inside the floor's time limit, then less the further over it the run is. */
    public static int speedScore() {
        int seconds = (int) (SimRun.elapsedMs() / 1000L);
        int limit = timeLimitSeconds();
        if (seconds <= limit) {
            return 100;
        }
        return Math.max(0, (int) (100 - speedDeduction((seconds - limit) * 100f / limit)));
    }

    private static float speedDeduction(float percentage) {
        float over = percentage;
        float deduction = 0;
        float[][] steps = {{20, 2}, {20, 3.5f}, {10, 4}, {10, 5}};
        for (float[] step : steps) {
            if (over <= 0) {
                return deduction;
            }
            deduction += Math.min(over, step[0]) / step[1];
            over -= step[0];
        }
        if (over > 0) {
            deduction += over / 6f;
        }
        return deduction;
    }

    /** Bonus: one per crypt to five, two for the mimic on floors 6 and 7, one for the prince, ten for Paul. */
    public static int bonusScore() {
        int bonus = Math.min(cryptsBlown, MAX_CRYPT_BONUS);
        if (mimicKilled && floorNumber() > 5) {
            bonus += MIMIC_BONUS;
        }
        if (princeKilled) {
            bonus += 1;
        }
        if (paulEzpz) {
            bonus += PAUL_EZPZ_BONUS;
        }
        return bonus;
    }

    /** The whole score: skill + explore + speed + bonus. */
    public static int totalScore() {
        return skillScore() + exploreScore() + speedScore() + bonusScore();
    }

    /** Hypixel's grade for a score. */
    public static String grade(int score) {
        if (score >= 300) {
            return "S+";
        }
        if (score >= 270) {
            return "S";
        }
        if (score >= 230) {
            return "A";
        }
        if (score >= 160) {
            return "B";
        }
        if (score >= 100) {
            return "C";
        }
        return "D";
    }

    // ------------------------------------------------------------------ client tick: rooms and milestones

    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                com.epic60869.sky2m.sim.util.FeatureGuard.end("SimScore.tick", SimScore::clientTick));
    }

    private static int tickCount;

    private static void clientTick(net.minecraft.client.Minecraft client) {
        if (!SimState.canAct(client) || ++tickCount % 10 != 0) {
            return;
        }
        String here = SimState.currentRoomName();
        if (here != null && !here.isBlank()) {
            VISITED.add(here);
        }
        if (SimState.isGeneratedFloor()) {
            clearMoblessRooms();
        }
        if (SimRun.isRunning() && SimState.isGeneratedFloor() && roomsTotal > 0) {
            int score = totalScore();
            if (score >= 270 && !had270) {
                had270 = true;
                milestone(270, score);
            }
            if (score >= 300 && !had300) {
                had300 = true;
                milestone(300, score);
            }
        }
    }

    private static void milestone(int at, int score) {
        String grade = at >= 300 ? "S+" : "S";
        ModChat.send("Sim", ModChat.text((at >= 300 ? "§c§l" : "§e§l") + grade + "§r§7 - "
                        + at + " score reached in "), ModChat.value(SimRun.elapsedText()),
                ModChat.dim(" (" + score + " now)"));
    }

    /**
     * Rooms that clear without a starred mob, the way Hypixel clears them: the Entrance once the run starts, the
     * Fairy room and a room with no starred mobs once you are in it, a puzzle once it is solved, Blood once the
     * Watcher is done. The rooms with starred mobs clear in {@code SimMobs} when the last one dies.
     */
    private static void clearMoblessRooms() {
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            String name = placed.name();
            if (name == null || SimRoomState.isCleared(name) || SimRoomState.isFailed(name)) {
                continue;
            }
            String type = SimFloorGen.typeOf(name).toUpperCase(Locale.ROOT);
            boolean clear = switch (type) {
                case "ENTRANCE" -> SimRun.hasStarted();
                case "BLOOD" -> SimWatcher.isCleared();
                case "PUZZLE" -> {
                    String key = SimRoomPuzzles.puzzleKey(name);
                    yield key != null && com.epic60869.sky2m.sim.roomsim.puzzles.SimPuzzles.isComplete(key);
                }
                default -> VISITED.contains(name) && !SimMobs.roomHasStarred(name);
            };
            if (clear && SimRoomState.markCleared(name)) {
                roomCleared();
            }
        }
    }

    /** Paul's EZPZ, which is worth ten and decides plenty of 300 runs on its own. */
    public static void setPaulEzpz(boolean on) {
        paulEzpz = on;
    }

    public static boolean isPaulEzpz() {
        return paulEzpz;
    }

    public static int cryptsBlown() {
        return cryptsBlown;
    }

    public static int batsKilled() {
        return batsKilled;
    }

    public static boolean isMimicKilled() {
        return mimicKilled;
    }

    /**
     * What is still missing for the bonus, in words.
     *
     * <p>The useful output of a score model is not the number, it is the sentence that tells you which thing to
     * go and do. "289" is a result; "two more crypts and the mimic" is a plan.
     */
    public static String whatIsMissing() {
        StringBuilder sb = new StringBuilder();
        if (cryptsBlown < MAX_CRYPT_BONUS) {
            sb.append(MAX_CRYPT_BONUS - cryptsBlown).append(" more crypt(s)");
        }
        if (!mimicKilled && floorNumber() > 5) {
            sb.append(sb.isEmpty() ? "" : ", ").append("the mimic");
        }
        if (secretsTotal > 0 && secretsFound < secretsNeeded()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(secretsNeeded() - secretsFound).append(" more secret(s)");
        }
        int unsolved = puzzleCount() - puzzlesDone();
        if (unsolved > 0) {
            sb.append(sb.isEmpty() ? "" : ", ").append(unsolved).append(" puzzle(s)");
        }
        if (roomsTotal > 0 && roomsCleared < roomsTotal) {
            sb.append(sb.isEmpty() ? "" : ", ").append(roomsTotal - roomsCleared).append(" more room(s)");
        }
        return sb.isEmpty() ? "nothing - only deaths and time stand between you and 300" : sb.toString();
    }

    /** One line for chat or a HUD. */
    public static String summary() {
        int total = totalScore();
        return String.format(Locale.US,
                "skill %d  explore %d  speed %d  bonus %d  = %d (%s)   crypts %d/%d  mimic %s  deaths %d  secrets %d/%d",
                skillScore(), exploreScore(), speedScore(), bonusScore(), total, grade(total),
                cryptsBlown, MAX_CRYPT_BONUS, mimicKilled ? "yes" : "no", deaths, secretsFound, secretsTotal);
    }

    /** {@code /simscore}: the four parts, what went into each, and what is still missing. */
    public static void announce() {
        int total = totalScore();
        ModChat.send("Sim", ModChat.text("Score "), ModChat.value(total + " (" + grade(total) + ")"),
                ModChat.dim(" on " + floorKey()));
        ModChat.send("Sim", ModChat.text("Skill "), ModChat.value(String.valueOf(skillScore())),
                ModChat.dim("  deaths " + deaths + " (-" + deathPenalty() + ")  puzzles " + puzzlesDone() + "/"
                        + puzzleCount() + (puzzleFails > 0 ? ", " + puzzleFails + " failed" : "")));
        ModChat.send("Sim", ModChat.text("Explore "), ModChat.value(String.valueOf(exploreScore())),
                ModChat.dim(String.format(Locale.US, "  rooms %d/%d (%d)  secrets %d/%d = %.1f%%, %d%% needed (%d)",
                        roomsCleared, roomsTotal, roomScore(), secretsFound, secretsTotal, secretsPercent(),
                        Math.round(secretRequirement() * 100), secretScore())));
        int limit = timeLimitSeconds();
        ModChat.send("Sim", ModChat.text("Speed "), ModChat.value(String.valueOf(speedScore())),
                ModChat.dim(String.format(Locale.US, "  %s, limit %dm %02ds", SimRun.elapsedText(), limit / 60,
                        limit % 60)));
        ModChat.send("Sim", ModChat.text("Bonus "), ModChat.value(String.valueOf(bonusScore())),
                ModChat.dim("  crypts " + Math.min(cryptsBlown, MAX_CRYPT_BONUS) + "/" + MAX_CRYPT_BONUS
                        + (floorNumber() > 5 ? "  mimic " + (mimicKilled ? "yes" : "no") : "")
                        + "  prince " + (princeKilled ? "yes" : "no") + (paulEzpz ? "  Paul +10" : "")));
        ModChat.send("Sim", ModChat.dim("missing: " + whatIsMissing()));
    }
}
