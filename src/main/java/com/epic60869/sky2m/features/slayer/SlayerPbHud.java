package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MGlobalChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The slayer PB leaderboard HUD: the Sky2M kill time standings ({@link SlayerLeaderboard}) for your current or
 * last slayer boss (or a pinned one), your own place, and while your boss is alive a live timer against your
 * personal best and the #1 time. Standings are asked for when the shown boss changes, every minute while the HUD
 * shows, and after a new personal best, never more than once every 10 seconds; when SBC is offline it keeps showing
 * the last standings it got.
 */
public final class SlayerPbHud {
    private static final long REFRESH_MS = 60_000L;
    private static final long MIN_GAP_MS = 10_000L;
    private static final Set<String> SLAYER_ISLANDS = Set.of("Hub", "Spider's Den", "The Park", "The End", "Crimson Isle", "The Rift");

    /** When the HUD shows (the "PB Leaderboard: Show" dropdown). */
    public enum Show {
        ALWAYS("Always"),
        QUEST("In Quest"),
        ISLANDS("On Islands");

        private final String displayName;

        Show(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static long lastSentAt;
    /** The boss the last request was for: a different boss to show means asking again. */
    private static String lastRequested;
    /** A new personal best on the shown boss: ask again as soon as the 10 second gap allows. */
    private static boolean refreshSoon;
    private static int ticks;

    private SlayerPbHud() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 == 0) maybeFetch();
        });
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("slayer_pb_leaderboard", () -> config() != null && config().enabled);
        Sky2MHuds.register("slayer_pb_leaderboard", "Slayer PB Leaderboard", SlayerPbHud::visible, SlayerPbHud::lines,
            List.of(
                Component.literal("Revenant Horror V PBs").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("1. Steve 21.35s").withStyle(ChatFormatting.GOLD),
                Component.literal("2. Alex 24.10s").withStyle(ChatFormatting.WHITE),
                Component.literal("3. You 26.02s").withStyle(ChatFormatting.GREEN),
                Component.literal("Now: 18.40s").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("  PB -7.62s").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("  #1 -2.95s").withStyle(ChatFormatting.GREEN))),
            8, 260);
    }

    private static Sky2MConfig.PbLeaderboardHud config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.pbLeaderboardHud;
    }

    private static boolean visible() {
        Sky2MConfig.PbLeaderboardHud config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || !config.enabled || mc.level == null || mc.gui.hud.isHidden() || !Sky2MLocation.onSkyblock()) return false;
        return switch (config.show) {
            case ALWAYS -> true;
            case QUEST -> SlayerTimes.bossSpawnedAt() > 0 || onSlayerQuest();
            case ISLANDS -> SLAYER_ISLANDS.contains(Sky2MLocation.area());
        };
    }

    /** The scoreboard shows "Slayer Quest" from starting a quest until it's done (also after rejoining mid-quest). */
    private static boolean onSlayerQuest() {
        return SlayerFeatures.onSlayerQuest();
    }

    /** The pinned boss, else your current or last slayer boss; null if there's none yet. */
    private static String shownBoss() {
        Sky2MConfig.PbLeaderboardHud config = config();
        String pinned = config == null || config.boss == null ? "" : config.boss.trim();
        if (!pinned.isEmpty() && !pinned.equalsIgnoreCase("auto")) {
            String boss = SlayerLeaderboard.canonical(pinned);
            if (boss != null) return boss;
        }
        return SlayerLeaderboard.canonical(SlayerTimes.lastBoss());
    }

    /** {@link SlayerLeaderboard} got a better time on the board for {@code boss}. */
    static void onImproved(String boss) {
        if (boss != null && boss.equalsIgnoreCase(shownBoss())) refreshSoon = true;
    }

    private static void maybeFetch() {
        if (!visible()) return;
        String boss = shownBoss();
        if (boss == null) return;
        long now = System.currentTimeMillis();
        boolean due = !boss.equals(lastRequested) || refreshSoon || now - lastSentAt >= REFRESH_MS;
        if (!due || now - lastSentAt < MIN_GAP_MS) return;
        lastRequested = boss;
        if (SlayerLeaderboard.fetch(boss)) {
            lastSentAt = now;
            refreshSoon = false;
        } else {
            // Offline (send() starts connecting): try again after the minimum gap rather than a whole minute.
            lastSentAt = now - REFRESH_MS + MIN_GAP_MS;
        }
    }

    private static List<Component> lines() {
        String boss = shownBoss();
        List<Component> lines = new ArrayList<>();
        if (boss == null) {
            lines.add(Component.literal("Slayer PBs").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            lines.add(Component.literal("Kill a slayer boss to see its leaderboard").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        SlayerLeaderboard.Standings standings = SlayerLeaderboard.cached(boss);
        Long mine = SlayerTimes.personalBests().get(boss);
        MutableComponent title = Component.literal(boss + " PBs").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (Sky2MGlobalChat.currentConnection() == null) {
            title.append(Component.literal(" (offline)").withStyle(style -> style.withColor(ChatFormatting.GRAY).withBold(false)));
        }
        lines.add(title);

        long best = 0;
        if (standings != null) {
            String self = Minecraft.getInstance().getUser().getName();
            Sky2MConfig.PbLeaderboardHud config = config();
            int rows = Math.min(config == null ? 5 : config.rows, standings.entries().size());
            for (int i = 0; i < rows; i++) {
                SlayerLeaderboard.Entry entry = standings.entries().get(i);
                ChatFormatting colour = entry.username().equalsIgnoreCase(self) ? ChatFormatting.GREEN
                    : i == 0 ? ChatFormatting.GOLD : i == 1 ? ChatFormatting.WHITE : i == 2 ? ChatFormatting.RED : ChatFormatting.GRAY;
                lines.add(Component.literal((i + 1) + ". " + entry.username() + " " + SlayerTimes.format(entry.timeMs())).withStyle(colour));
            }
            if (!standings.entries().isEmpty()) best = standings.entries().get(0).timeMs();
            if (standings.entries().isEmpty()) {
                lines.add(Component.literal("No times yet").withStyle(ChatFormatting.GRAY));
            }
            if (standings.youRank() > rows) {
                lines.add(Component.literal("You: #" + standings.youRank() + " " + SlayerTimes.format(standings.youTimeMs())).withStyle(ChatFormatting.GREEN));
            } else if (standings.youRank() == 0 && mine != null) {
                lines.add(Component.literal("You: " + SlayerTimes.format(mine) + " (not on the board yet)").withStyle(ChatFormatting.GREEN));
            }
        } else {
            lines.add(mine != null
                ? Component.literal("You: " + SlayerTimes.format(mine)).withStyle(ChatFormatting.GREEN)
                : Component.literal("No time yet").withStyle(ChatFormatting.GRAY));
        }

        // The fight in progress, against your PB and the #1 time.
        long elapsed = SlayerTimes.bossElapsed();
        if (elapsed >= 0 && boss.equals(SlayerLeaderboard.canonical(SlayerTimes.lastBoss()))) {
            MutableComponent live = Component.literal("Now: " + SlayerTimes.format(elapsed)).withStyle(ChatFormatting.AQUA);
            if (mine != null) live.append(gap("  PB ", elapsed, mine));
            if (best > 0) live.append(gap("  #1 ", elapsed, best));
            lines.add(live);
        }
        return lines;
    }

    /** "  PB -1.20s": green while you're ahead of {@code target}, red once you're behind it. */
    private static Component gap(String label, long elapsed, long target) {
        long diff = elapsed - target;
        String sign = diff < 0 ? "-" : "+";
        return Component.literal(label + sign + SlayerTimes.format(Math.abs(diff)))
            .withStyle(diff < 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
    }
}
