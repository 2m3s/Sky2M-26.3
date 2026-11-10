package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayer time messages and personal bests, following SkyHanni's SlayerTimeMessages: how long your boss took to kill
 * (from its "Spawned by: you" nametag appearing to the moment it dies: its health hitting 0 or its nametag going,
 * confirmed by "NICE! SLAYER BOSS SLAIN!" or "YOU COCOONED YOUR SLAYER BOSS"), your personal best for that boss and
 * tier (saved per Minecraft account), and how long the whole quest took.
 */
public final class SlayerTimes {
    /** "☠ Revenant Horror V 12M❤" -> "Revenant Horror", "V". */
    private static final RepoPattern BOSS = RepoPattern.of("slayer.SlayerTimes.BOSS", "(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend)(?: ([IVX]+))?");
    private static final Gson GSON = new Gson();

    /** Minecraft username (lowercase) -> boss key ("Revenant Horror V") -> best kill time in ms. */
    private static final Map<String, Map<String, Long>> BESTS = new ConcurrentHashMap<>();
    private static Path file;
    private static Path lastBossFile;
    /** Your current or most recent boss key, kept after the kill and between game sessions (for the PB HUD). */
    private static volatile String lastBoss;

    private static long questStartedAt;
    private static long spawnedAt;
    /** When its health showed 0: certainly dead, unlike a nametag that went (Tier 5 Tarantula between phases). */
    private static long zeroAt;
    /** "☠ Revenant Horror V 0❤": the boss is dead even if its nametag lingers. */
    private static final RepoPattern ZERO_HEALTH = RepoPattern.of("slayer.SlayerTimes.ZERO_HEALTH", "(?<![\\d.,])0❤");
    private static String boss;
    /** Set after a kill until your boss's nametag is gone, so the lingering tag doesn't start a new timer. */
    private static boolean killed;
    /** When the sidebar first said "Slay the boss!" for this boss: its real spawn, if its nametag is only seen later. */
    private static long slayLineAt;
    /**
     * Whether the fight's start is known: the boss was seen alive (a health line above 0) or its spawn came from the
     * sidebar's slay line. Without either, it was only seen as it died and its time means nothing.
     */
    private static boolean startKnown;
    /** A slay line older than this is from an earlier boss, not this one. */
    private static final long SLAY_LINE_MAX_MS = 5 * 60_000;

    /**
     * The fastest kill the Sky2M leaderboard believes per boss key (the server's table, mod-server slayer-pbs.js): a
     * faster "kill" is a timing miss (the boss was only seen as it died), so it isn't kept as a personal best, which
     * would otherwise block every real time from ever being sent. Anything not listed: 1 s.
     */
    private static final Map<String, Long> MIN_KILL_MS = Map.ofEntries(
        Map.entry("Tarantula Broodfather III", 2_000L), Map.entry("Tarantula Broodfather IV", 2_000L),
        Map.entry("Tarantula Broodfather V", 5_000L), Map.entry("Voidgloom Seraph III", 2_000L),
        Map.entry("Voidgloom Seraph IV", 10_000L), Map.entry("Inferno Demonlord II", 2_000L),
        Map.entry("Inferno Demonlord III", 4_000L), Map.entry("Inferno Demonlord IV", 6_000L),
        Map.entry("Riftstalker Bloodfiend I", 10_000L), Map.entry("Riftstalker Bloodfiend II", 10_000L),
        Map.entry("Riftstalker Bloodfiend III", 15_000L), Map.entry("Riftstalker Bloodfiend IV", 15_000L),
        Map.entry("Riftstalker Bloodfiend V", 15_000L));

    /**
     * Whether a kill's time is thrown away: only one under the boss's minimum whose start isn't known (seen only as it
     * died). A boss seen alive, or timed from the sidebar's slay line, keeps its time however short: a one-shot Tier IV
     * Sven really does die in under a second (2026-11-04, 0.46 s). Public for the game test.
     */
    public static boolean rejected(long time, String boss, boolean startKnown) {
        return !startKnown && time < minKillMs(boss);
    }

    public static long minKillMs(String boss) {
        return Math.max(1_000L, MIN_KILL_MS.getOrDefault(boss, 1_000L));
    }

    /** The sidebar shows "Slay the boss!" ({@link SlayerBossSpawnAlert}, every tick it's there). */
    static void onSlayLine(long now) {
        if (slayLineAt == 0 && spawnedAt == 0) slayLineAt = now;
    }

    private SlayerTimes() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("slayer-personal-bests.json");
        lastBossFile = configDir.resolve("sky2m").resolve("slayer-last-boss.txt");
        load();
        SlayerSessionTimes.init();
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
    }

    private static Sky2MConfig.PersonalBest config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.personalBest;
    }

    /** Whether the boss scan in {@link SlayerFeatures} has to run for these messages. */
    static boolean enabled() {
        Sky2MConfig.PersonalBest config = config();
        return config != null && (config.timeToKill || config.personalBests) || SlayerBossProfit.enabled()
            || SlayerBossSpawnAlert.enabled();
    }

    /**
     * Your boss's health line this scan (every 2 ticks), "" when its nametag is there without one, or null when your
     * boss isn't around (from {@link SlayerFeatures}). The boss dies at the last scan it was seen alive: its health
     * hits 0 or its nametag goes. The kill only counts once Hypixel confirms it ("NICE! SLAYER BOSS SLAIN!" or a
     * cocoon), but it is timed to that moment, not to the chat message.
     */
    static void onBoss(String healthLine) {
        long now = System.currentTimeMillis();
        if (healthLine == null) {
            // A missing nametag can be a Tarantula phase transition, not a kill. Only kill chat
            // (or an observed 0-health line) confirms the end of the fight.
            killed = false;
            return;
        }
        if (killed) return;
        boolean spawned = spawnedAt == 0;
        if (spawned) {
            // The sidebar says the boss spawned before you saw its nametag (it spawned out of view): time from then.
            spawnedAt = slayLineAt > 0 && now - slayLineAt < SLAY_LINE_MAX_MS ? slayLineAt : now;
            startKnown = spawnedAt == slayLineAt;
            SlayerBossProfit.onSpawn();
        }
        if (healthLine.isEmpty() || ZERO_HEALTH.matcher(healthLine).find()) {
            if (zeroAt == 0 && !healthLine.isEmpty()) zeroAt = now;
        } else {
            // Alive: back in range, or Tier 5 Tarantula's second phase after the first one went.
            zeroAt = 0;
            startKnown = true;
        }
        Matcher m = BOSS.matcher(healthLine);
        if (m.find()) {
            String tier = m.group(2) != null ? m.group(2) : sidebarTier(m.group(1));
            boss = key(tier == null ? m.group(1) : m.group(1) + " " + tier);
            if (!boss.equals(lastBoss)) {
                lastBoss = boss;
                saveLastBoss();
            }
        }
        // After the name is read, so the alert can say which boss and tier it is.
        if (spawned) SlayerBossSpawnAlert.onNametagSpawn(boss);
    }

    /**
     * The boss key a nametag name is saved under. A Tier 5 Tarantula is one boss in two phases, "Tarantula
     * Broodfather V" and then "Conjoined Brood" (no tier in its nametag), so both count as "Tarantula Broodfather V",
     * as in SkyHanni. Otherwise the key would change mid-fight and the PB HUD would switch boards. Tier 5 Revenant's
     * nametag is "Atoned Horror", so it counts as "Revenant Horror V" (older saves are merged into it on load).
     */
    static String key(String name) {
        String base = name.trim().replaceFirst("(?i)\\s+[IVX]+$", "");
        if (base.equalsIgnoreCase("Conjoined Brood")) return "Tarantula Broodfather V";
        if (base.equalsIgnoreCase("Atoned Horror")) return "Revenant Horror V";
        return name;
    }

    /** Your current boss key, or if none is alive, the last one you fought; null if you haven't fought one yet. */
    public static String lastBoss() {
        return lastBoss;
    }

    /** When your current boss spawned (System.currentTimeMillis), or 0 when none is being timed. */
    public static long bossSpawnedAt() {
        return spawnedAt;
    }

    /**
     * How long the current fight has lasted; -1 when none is being timed. It stops when the boss's health shows 0,
     * but keeps running while the boss is only out of sight, so the live timer doesn't freeze while a Tier 5
     * Tarantula swaps phases.
     */
    public static long bossElapsed() {
        if (spawnedAt == 0) return -1;
        return (zeroAt > 0 ? zeroAt : System.currentTimeMillis()) - spawnedAt;
    }

    private static void onChat(String text) {
        switch (text) {
            case "SLAYER QUEST STARTED!" -> {
                reset();
                questStartedAt = System.currentTimeMillis();
            }
            case "NICE! SLAYER BOSS SLAIN!" -> onKill();
            case "SLAYER QUEST COMPLETE!" -> {
                onKill();
                Sky2MConfig.PersonalBest config = config();
                if (config != null && config.questComplete && questStartedAt > 0) {
                    String took = format(System.currentTimeMillis() - questStartedAt);
                    Sky2MAlerts.chat(config.compactTimes
                        ? Component.literal("Quest took ").withStyle(ChatFormatting.YELLOW).append(aqua(took)).append(yellow(" in total."))
                        : Component.literal("Slayer quest took ").withStyle(ChatFormatting.YELLOW).append(aqua(took)).append(yellow(" to complete.")));
                }
                questStartedAt = 0;
            }
            case "SLAYER QUEST FAILED!" -> reset();
            default -> {
                // A cocooned boss is dead when it's cocooned: "NICE! SLAYER BOSS SLAIN!" only comes once it hatches.
                if (text.startsWith("YOU COCOONED YOUR SLAYER BOSS")) onKill();
            }
        }
    }

    private static void reset() {
        questStartedAt = 0;
        spawnedAt = 0;
        slayLineAt = 0;
        zeroAt = 0;
        startKnown = false;
        boss = null;
    }

    /** The tier of {@code name}'s quest from the sidebar ("Tarantula Broodfather V"), for a tag read without its tier. */
    private static String sidebarTier(String name) {
        for (String line : com.epic60869.sky2m.features.core.Sky2MLocation.scoreboard()) {
            Matcher m = BOSS.matcher(line);
            if (m.find() && m.group(1).equals(name) && m.group(2) != null) return m.group(2);
        }
        return null;
    }

    private static void onKill() {
        if (spawnedAt == 0 || boss == null) return;
        long time = (zeroAt > 0 ? zeroAt : System.currentTimeMillis()) - spawnedAt;
        String name = boss;
        boolean known = startKnown;
        startKnown = false;
        SlayerBossProfit.onKill(name);
        spawnedAt = 0;
        zeroAt = 0;
        slayLineAt = 0;
        boss = null;
        killed = true;
        if (!rejected(time, name, known)) SlayerSessionTimes.add(name, time, System.currentTimeMillis());
        Sky2MConfig.PersonalBest config = config();
        if (config == null) return;
        if (rejected(time, name, known)) {
            // Seen only as it died: not a real time, so it can't become a PB that no real kill could beat.
            if (config.timeToKill || config.personalBests) {
                Sky2MAlerts.chat(Component.literal(name + " kill timed at " + format(time) + ", too fast to be real (the boss was "
                    + "only seen as it died), so it wasn't counted.").withStyle(ChatFormatting.GRAY));
            }
            return;
        }

        Map<String, Long> bests = BESTS.computeIfAbsent(player(), p -> new ConcurrentHashMap<>());
        Long previous = bests.get(name);
        boolean newBest = previous == null || time < previous;
        if (newBest) {
            bests.put(name, time);
            save();
            SlayerLeaderboard.onPersonalBest(name, time);
        }

        boolean compact = config.compactTimes;
        String took = format(time);
        if (config.timeToKill) {
            // One line: the time, then the personal best in brackets.
            MutableComponent line = compact
                ? red(name).append(yellow(" took ")).append(aqua(took))
                : yellow("It took ").append(aqua(took)).append(yellow(" to kill ")).append(red(name));
            if (config.personalBests) {
                if (newBest) {
                    line.append(yellow(" ("))
                        .append(Component.literal(compact ? "NEW PB!" : "NEW PERSONAL BEST!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                        .append(Component.literal(previous == null ? "" : " Previous: " + format(previous)).withStyle(ChatFormatting.GRAY))
                        .append(yellow(")"));
                } else {
                    line.append(yellow(" (PB: ")).append(Component.literal(format(previous)).withStyle(ChatFormatting.GOLD)).append(yellow(")"));
                }
            }
            Sky2MAlerts.chat(line);
            return;
        }
        if (!config.personalBests) return;
        MutableComponent bold = Component.literal(compact ? "NEW PB! " : "NEW PERSONAL BEST! ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
        if (newBest && previous == null) {
            Sky2MAlerts.chat(compact
                ? bold.append(red(name)).append(yellow(" in ")).append(green(took))
                : bold.append(green(took)).append(yellow(" for ")).append(red(name)));
        } else if (newBest) {
            String old = format(previous);
            Sky2MAlerts.chat(compact
                ? bold.append(red(name)).append(yellow(" in ")).append(Component.literal(old).withStyle(ChatFormatting.RED))
                    .append(yellow(" -> ")).append(green(took))
                : bold.append(green(took)).append(Component.literal(" (Previous " + old + ")").withStyle(ChatFormatting.GRAY))
                    .append(yellow(" for ")).append(red(name)));
        } else {
            Sky2MAlerts.chat(red(name).append(yellow(compact ? " PB: " : " Personal best: "))
                .append(Component.literal(format(previous)).withStyle(ChatFormatting.GOLD)));
        }
    }

    /** Your personal bests: boss key ("Revenant Horror V") -> best kill time in ms. */
    public static Map<String, Long> personalBests() {
        return Map.copyOf(BESTS.getOrDefault(player(), Map.of()));
    }

    private static String player() {
        return Minecraft.getInstance().getUser().getName().toLowerCase(Locale.ROOT);
    }

    /** "34.52s", "1m 3.45s". */
    public static String format(long millis) {
        long minutes = millis / 60_000;
        double seconds = (millis % 60_000) / 1000.0;
        return minutes > 0 ? String.format(Locale.US, "%dm %.2fs", minutes, seconds) : String.format(Locale.US, "%.2fs", seconds);
    }

    private static MutableComponent yellow(String text) { return Component.literal(text).withStyle(ChatFormatting.YELLOW); }
    private static MutableComponent aqua(String text) { return Component.literal(text).withStyle(ChatFormatting.AQUA); }
    private static MutableComponent green(String text) { return Component.literal(text).withStyle(ChatFormatting.GREEN); }
    private static MutableComponent red(String text) { return Component.literal(text).withStyle(ChatFormatting.RED); }

    // ---------------------------------------------------------------- saving

    private static void load() {
        try {
            if (Files.exists(lastBossFile)) {
                String boss = Files.readString(lastBossFile, StandardCharsets.UTF_8).trim();
                if (!boss.isEmpty()) lastBoss = key(boss);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read slayer-last-boss.txt: " + e.getMessage());
        }
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var player : root.entrySet()) {
                Map<String, Long> bests = new ConcurrentHashMap<>();
                for (var best : player.getValue().getAsJsonObject().entrySet()) {
                    // Older saves kept Tier 5 Tarantula's second phase as its own boss: keep the faster of the two.
                    bests.merge(key(best.getKey()), best.getValue().getAsLong(), Math::min);
                }
                BESTS.put(player.getKey(), bests);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read slayer-personal-bests.json: " + e.getMessage());
        }
        resetTarantulaOnce();
        dropImpossible();
    }

    /** Saved PBs under this are timing misses whatever the boss: no kill is that fast. */
    static final long IMPOSSIBLE_MS = 100;

    /**
     * PBs saved before the fastest-kill check that no kill could make: a 0.03s Sven made every real Sven time "not a
     * PB", so none was ever sent to the leaderboard. They're dropped so real kills count again. Only times under
     * {@link #IMPOSSIBLE_MS}: a real one-shot can be under the boss's minimum ({@link #rejected}), and dropping
     * everything under it wiped a real 0.46s Sven PB at every start.
     */
    private static void dropImpossible() {
        boolean removed = false;
        for (Map<String, Long> bests : BESTS.values()) {
            removed |= bests.entrySet().removeIf(e -> e.getValue() < IMPOSSIBLE_MS);
        }
        if (removed) save();
    }

    /**
     * Tarantula PBs saved before the phase fix could be a single phase (a "3.1s" kill), so they are cleared once and
     * set again by real kills.
     */
    private static void resetTarantulaOnce() {
        Path marker = file.resolveSibling("slayer-tarantula-reset-1.txt");
        if (Files.exists(marker)) return;
        boolean removed = false;
        for (Map<String, Long> bests : BESTS.values()) {
            removed |= bests.keySet().removeIf(boss -> boss.startsWith("Tarantula Broodfather") || boss.startsWith("Conjoined Brood"));
        }
        if (removed) save();
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "Tarantula personal bests were reset once after the phase timing fix.", StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save the Tarantula reset marker: " + e.getMessage());
        }
    }

    private static void saveLastBoss() {
        String boss = lastBoss;
        try {
            Files.createDirectories(lastBossFile.getParent());
            Files.writeString(lastBossFile, boss == null ? "" : boss, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save slayer-last-boss.txt: " + e.getMessage());
        }
    }

    private static void save() {
        Map<String, Map<String, Long>> copy = new HashMap<>(BESTS);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(copy), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save slayer-personal-bests.json: " + e.getMessage());
        }
    }
}
