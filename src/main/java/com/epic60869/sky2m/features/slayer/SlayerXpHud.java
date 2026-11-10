package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayer XP HUD: this session's bosses per hour and Slayer XP per hour, and the XP to your next slayer level with about
 * how many bosses and how long that is at this pace.
 *
 * <ul>
 * <li>Kills are Hypixel's "SLAYER QUEST COMPLETE!". The hours are active time only: each quest from "SLAYER QUEST
 *     STARTED!" to its completion, plus the gap to the next quest when it's under {@link #MAX_GAP_MS} (turning in and
 *     starting again), so a break doesn't drag the rate down.</li>
 * <li>The XP to the next level is Hypixel's own line after a quest, "Zombie Slayer LVL 7 - Next LVL in 12,345 XP!".</li>
 * <li>The XP a boss gives is the drop between two of those lines on the same level; until there are two, the tier's
 *     base XP ({@link #tierXp}: 5, 25, 100, 500, 1,500 for tiers I to V).</li>
 * </ul>
 */
public final class SlayerXpHud {
    /** "Zombie Slayer LVL 7 - Next LVL in 12,345 XP!" / "Wolf Slayer LVL 9 - LVL MAXED OUT!". */
    static final RepoPattern LEVEL = RepoPattern.of("slayer.SlayerXpHud.LEVEL", "(Zombie|Spider|Wolf|Enderman|Blaze|Vampire) Slayer LVL (\\d+) - (?:Next LVL in ([\\d,]+) XP!|LVL MAXED OUT!)");
    /** Gaps between quests longer than this were a break, not part of the grind. */
    static final long MAX_GAP_MS = 3 * 60_000L;

    private static int kills;
    private static long activeMs;
    private static long questStartedAt;
    private static long lastKillAt;
    private static double sessionXp;
    /** The last level line: which slayer, its level and the XP left (-1 at max level). */
    private static String slayer;
    private static int level;
    private static long xpLeft = -2;
    /** The XP one boss gave, learned from two level lines; 0 until then. */
    private static long xpPerBoss;
    /** The tier's base XP of the boss just killed, for a level line that can't be compared yet. */
    private static int pendingTierXp;

    private SlayerXpHud() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.slayers.xpHud;
    }

    public static void init() {
        Sky2MChat.onChat(message -> onLine(message.text().trim(), System.currentTimeMillis()));
        Sky2MHuds.setting("slayer_xp", SlayerXpHud::enabled);
        Sky2MHuds.register("slayer_xp", "Slayer XP", () -> enabled() && Sky2MLocation.onSkyblock() && hasData(),
            SlayerXpHud::lines,
            List.of(Component.literal("§c§lSlayer XP"),
                Component.literal("§7Bosses/h: §e14.2 §8(12 this session)"),
                Component.literal("§7XP/h: §b21,300"),
                Component.literal("§7Zombie LVL 7: §b12,345 XP §7to LVL 8"),
                Component.literal("§7≈ §e9 bosses §8· §e38m")),
            8, 330);
    }

    /** One chat line, at {@code now}. Public for the game test. */
    public static void onLine(String text, long now) {
        if (text.equals("SLAYER QUEST STARTED!")) {
            if (lastKillAt > 0 && now - lastKillAt <= MAX_GAP_MS) activeMs += now - lastKillAt;
            questStartedAt = now;
        } else if (text.equals("SLAYER QUEST COMPLETE!")) {
            kills++;
            if (questStartedAt > 0) activeMs += now - questStartedAt;
            questStartedAt = 0;
            lastKillAt = now;
            pendingTierXp = tierXp(SlayerTimes.lastBoss());
        } else {
            Matcher m = LEVEL.matcher(text);
            if (m.find()) onLevelLine(m.group(1), Integer.parseInt(m.group(2)),
                m.group(3) == null ? -1 : Long.parseLong(m.group(3).replace(",", "")));
        }
    }

    private static void onLevelLine(String which, int lvl, long left) {
        long gained = 0;
        if (which.equals(slayer) && lvl == level && xpLeft >= 0 && left >= 0 && left < xpLeft) {
            gained = xpLeft - left;
            xpPerBoss = gained;
        } else if (pendingTierXp > 0) {
            gained = pendingTierXp;
        }
        if (!which.equals(slayer)) xpPerBoss = 0;
        sessionXp += gained;
        pendingTierXp = 0;
        slayer = which;
        level = lvl;
        xpLeft = left;
    }

    /** A boss's base Slayer XP by its tier, from a name like "Revenant Horror V"; 0 when there's no tier. */
    static int tierXp(String boss) {
        if (boss == null) return 0;
        String[] parts = boss.trim().split("\\s+");
        return switch (parts[parts.length - 1]) {
            case "I" -> 5;
            case "II" -> 25;
            case "III" -> 100;
            case "IV" -> 500;
            case "V" -> 1500;
            default -> 0;
        };
    }

    private static boolean hasData() {
        return kills > 0 || xpLeft != -2;
    }

    /** Bosses per hour this session, 0 before the first. */
    public static double bossesPerHour() {
        return activeMs <= 0 ? 0 : kills / (activeMs / 3_600_000d);
    }

    /** Slayer XP per hour this session. */
    public static double xpPerHour() {
        return activeMs <= 0 ? 0 : sessionXp / (activeMs / 3_600_000d);
    }

    /** For the game test: {kills, activeMs, sessionXp, xpLeft, xpPerBoss}. */
    public static double[] state() {
        return new double[]{kills, activeMs, sessionXp, xpLeft, xpPerBoss};
    }

    /** For the game test: back to a fresh session. */
    public static void reset() {
        kills = 0;
        activeMs = 0;
        questStartedAt = 0;
        lastKillAt = 0;
        sessionXp = 0;
        slayer = null;
        level = 0;
        xpLeft = -2;
        xpPerBoss = 0;
        pendingTierXp = 0;
    }

    public static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("§c§lSlayer XP"));
        double perHour = bossesPerHour();
        out.add(Component.literal("§7Bosses/h: §e" + (kills == 0 ? "-" : String.format(Locale.US, "%.1f", perHour))
            + " §8(" + kills + " this session)"));
        out.add(Component.literal("§7XP/h: §b" + (sessionXp <= 0 ? "-" : String.format(Locale.US, "%,d", Math.round(xpPerHour())))));
        if (slayer != null) {
            if (xpLeft < 0) {
                out.add(Component.literal("§7" + slayer + " LVL " + level + ": §aMaxed"));
            } else {
                out.add(Component.literal("§7" + slayer + " LVL " + level + ": §b" + String.format(Locale.US, "%,d", xpLeft)
                    + " XP §7to LVL " + (level + 1)));
                long perBoss = xpPerBoss > 0 ? xpPerBoss : tierXp(SlayerTimes.lastBoss());
                if (perBoss > 0) {
                    long bosses = (xpLeft + perBoss - 1) / perBoss;
                    String time = perHour > 0 ? " §8· §e" + duration(bosses / perHour * 3600) : "";
                    out.add(Component.literal("§7≈ §e" + String.format(Locale.US, "%,d", bosses) + " bosses" + time));
                }
            }
        }
        return out;
    }

    /** "38m", "2h 5m", "3d 4h". */
    static String duration(double seconds) {
        long minutes = Math.max(1, Math.round(seconds / 60));
        long d = minutes / 1440, h = minutes % 1440 / 60, m = minutes % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
