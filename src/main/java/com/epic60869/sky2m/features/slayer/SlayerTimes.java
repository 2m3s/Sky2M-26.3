package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
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
    private static final Pattern BOSS = Pattern.compile("(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood"
        + "|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend)(?: ([IVX]+))?");
    private static final Gson GSON = new Gson();

    /** Minecraft username (lowercase) -> boss key ("Revenant Horror V") -> best kill time in ms. */
    private static final Map<String, Map<String, Long>> BESTS = new ConcurrentHashMap<>();
    private static Path file;
    private static Path lastBossFile;
    /** Your current or most recent boss key, kept after the kill and between game sessions (for the PB HUD). */
    private static volatile String lastBoss;

    private static long questStartedAt;
    private static long spawnedAt;
    /** The last scan your boss was seen alive, and when it died (0 while alive): see {@link #onBoss}. */
    private static long lastSeenAt;
    private static long diedAt;
    /** When its health showed 0: certainly dead, unlike a nametag that went (Tier 5 Tarantula between phases). */
    private static long zeroAt;
    /** "☠ Revenant Horror V 0❤": the boss is dead even if its nametag lingers. */
    private static final Pattern ZERO_HEALTH = Pattern.compile("(?<![\\d.,])0❤");
    private static String boss;
    /** Set after a kill until your boss's nametag is gone, so the lingering tag doesn't start a new timer. */
    private static boolean killed;

    private SlayerTimes() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("slayer-personal-bests.json");
        lastBossFile = configDir.resolve("sky2m").resolve("slayer-last-boss.txt");
        load();
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
        return config != null && (config.timeToKill || config.personalBests) || SlayerBossProfit.enabled();
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
            killed = false;
            if (spawnedAt > 0 && diedAt == 0 && lastSeenAt > 0) diedAt = lastSeenAt;
            return;
        }
        if (killed) return;
        if (spawnedAt == 0) {
            spawnedAt = now;
            SlayerBossProfit.onSpawn();
        }
        if (healthLine.isEmpty() || ZERO_HEALTH.matcher(healthLine).find()) {
            if (diedAt == 0 && lastSeenAt > 0) diedAt = healthLine.isEmpty() ? lastSeenAt : now;
            if (zeroAt == 0 && !healthLine.isEmpty()) zeroAt = now;
        } else {
            // Alive: back in range, or Tier 5 Tarantula's second phase after the first one went.
            lastSeenAt = now;
            diedAt = 0;
            zeroAt = 0;
        }
        Matcher m = BOSS.matcher(healthLine);
        if (m.find()) {
            boss = key(m.group(2) == null ? m.group(1) : m.group(1) + " " + m.group(2));
            if (!boss.equals(lastBoss)) {
                lastBoss = boss;
                saveLastBoss();
            }
        }
    }

    /**
     * The boss key a nametag name is saved under. A Tier 5 Tarantula is one boss in two phases, "Tarantula
     * Broodfather V" and then "Conjoined Brood" (no tier in its nametag), so both count as "Tarantula Broodfather V",
     * as in SkyHanni. Otherwise the key would change mid-fight and the PB HUD would switch boards.
     */
    static String key(String name) {
        return name.equalsIgnoreCase("Conjoined Brood") ? "Tarantula Broodfather V" : name;
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
        lastSeenAt = 0;
        diedAt = 0;
        zeroAt = 0;
        boss = null;
    }

    private static void onKill() {
        if (spawnedAt == 0 || boss == null) return;
        long time = (diedAt > 0 ? diedAt : System.currentTimeMillis()) - spawnedAt;
        String name = boss;
        SlayerBossProfit.onKill(name);
        spawnedAt = 0;
        lastSeenAt = 0;
        diedAt = 0;
        zeroAt = 0;
        boss = null;
        killed = true;
        Sky2MConfig.PersonalBest config = config();
        if (config == null) return;

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
