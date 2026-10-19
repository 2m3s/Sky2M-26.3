package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MChat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayers > Boss Fight > Phase Timer: how long each phase of your boss took, read from its health nametag as
 * Slayer Features finds it. While the nametag says "15 Hits" (a Voidgloom's shield, and any other boss that shows
 * hits), the boss is in a hit phase; otherwise it's taking damage. The fight is timed from the nametag appearing to it
 * going, and the last fight stays in the HUD for a few seconds after.
 * <p>
 * While the nametag is gone mid-fight (a Tier 5 Tarantula between "Tarantula Broodfather" and "Conjoined Brood"),
 * that time is downtime, and the boss coming back (or its name changing) starts a new phase. For Tier 5 Tarantula
 * and Tier 5 Revenant these phases also show live in the Boss Phase Display and as a one-line kill summary.
 */
public final class SlayerPhaseTimer {
    private static final Pattern HITS = Pattern.compile("\\b(\\d+) Hits?\\b");
    /** The boss's name in its health line, to see Tarantula's phase 1 -> phase 2 name change. */
    private static final Pattern NAME = Pattern.compile("(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood"
        + "|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend)(?: ([IVX]+))?");
    private static final long KEEP_MS = 10_000;
    /** A shorter gap is the nametag flickering (out of range for a scan, entity resent), not downtime. */
    private static final long MIN_DOWNTIME_MS = 500;

    private enum Kind { HITS, DAMAGE, DOWNTIME }

    /** One timed stretch; {@code segment} counts the boss's real phases, split by downtime or a name change. */
    private record Phase(Kind kind, long millis, int segment) {}

    private static final List<Phase> phases = new ArrayList<>();
    private static boolean active, currentHits, killed;
    private static long fightStart, phaseStart, endedAt, gapStart;
    private static int segment, hitsLeft;
    private static String bossName = "";
    /** "T5 Tara" / "T5 Rev" while the fight is one of those, else null. */
    private static String t5Label;

    private SlayerPhaseTimer() {}

    private static FeatureConfigs.SlayerPhaseTimer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.phaseTimer;
    }

    private static FeatureConfigs.Slayer hudConfig() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.huds;
    }

    static boolean needsBoss() {
        FeatureConfigs.SlayerPhaseTimer c = config();
        FeatureConfigs.Slayer h = hudConfig();
        return c != null && c.enabled || h != null && (h.t5PhaseTimes || h.t5PhaseSummary);
    }

    public static void init() {
        Sky2MHuds.register("slayer_phase_timer", "Slayer Phase Timer", () -> config() != null && config().enabled,
            SlayerPhaseTimer::lines,
            List.of(line(Kind.HITS, 1, 3_200), line(Kind.DAMAGE, 1, 5_400), line(Kind.DOWNTIME, 0, 2_100), line(Kind.HITS, 2, 2_100)), 8, 240);
        Sky2MHuds.setting("slayer_phase_timer", () -> config() != null && config().enabled);
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            if (text.equals("SLAYER QUEST STARTED!")) {
                reset();
            } else if (text.equals("NICE! SLAYER BOSS SLAIN!") || text.startsWith("YOU COCOONED YOUR SLAYER BOSS")) {
                if (active) {
                    finish(System.currentTimeMillis());
                    killed = true;
                }
            } else if (text.equals("SLAYER QUEST FAILED!")) {
                reset();
            }
        });
    }

    /** The boss's health line every other tick (null when you have no boss). */
    static void onBoss(String text) {
        long now = System.currentTimeMillis();
        if (text == null) {
            // The health nametag can disappear during Tarantula's Conjoined Brood transition.
            // A missing tag alone never ends a fight; wait for Hypixel's boss-slain/cocoon message.
            if (killed) killed = false;
            else if (active && gapStart == 0) gapStart = now;
            return;
        }
        if (killed) return;
        boolean hits = HITS.matcher(text).find();
        hitsLeft = hits ? hitsIn(text) : 0;
        Matcher nameMatch = NAME.matcher(text);
        boolean named = nameMatch.find();
        String name = named ? nameMatch.group(1) : bossName;
        if (!active) {
            active = true;
            phases.clear();
            fightStart = phaseStart = now;
            currentHits = hits;
            endedAt = 0;
            gapStart = 0;
            segment = 0;
            bossName = name;
            t5Label = named ? t5Label(nameMatch) : null;
            return;
        }
        if (t5Label == null && named) t5Label = t5Label(nameMatch);
        boolean renamed = !name.equals(bossName);
        if (gapStart > 0) {
            long gap = now - gapStart;
            if (gap >= MIN_DOWNTIME_MS || renamed) {
                // The boss was gone: its phase ended when the nametag went, and a new one starts now.
                phases.add(new Phase(kind(currentHits), gapStart - phaseStart, segment));
                phases.add(new Phase(Kind.DOWNTIME, gap, segment));
                segment++;
                phaseStart = now;
                currentHits = hits;
                gapStart = 0;
                bossName = name;
                return;
            }
            gapStart = 0;
        }
        if (renamed) {
            phases.add(new Phase(kind(currentHits), now - phaseStart, segment++));
            phaseStart = now;
            currentHits = hits;
            bossName = name;
            return;
        }
        if (hits != currentHits) {
            phases.add(new Phase(kind(currentHits), now - phaseStart, segment));
            FeatureConfigs.SlayerPhaseTimer c = config();
            if (c != null && c.enabled && c.phaseAlerts) {
                Sky2MAlerts.title(Component.literal(hits ? "Hit Phase" : "Damage Phase").withStyle(hits ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.RED),
                    Component.literal((currentHits ? "Hits" : "Damage") + " took " + seconds(now - phaseStart)).withStyle(ChatFormatting.GRAY));
            }
            phaseStart = now;
            currentHits = hits;
        }
    }

    /** "T5 Tara" for "Tarantula Broodfather V" or "Conjoined Brood" (phase 2 has no tier), "T5 Rev" for "Atoned Horror". */
    private static String t5Label(Matcher m) {
        String name = m.group(1);
        String tier = m.group(2);
        if (name.equals("Conjoined Brood") || name.equals("Tarantula Broodfather") && "V".equals(tier)) return "T5 Tara";
        if (name.equals("Atoned Horror") || name.equals("Revenant Horror") && "V".equals(tier)) return "T5 Rev";
        return null;
    }

    private static int hitsIn(String text) {
        Matcher m = HITS.matcher(text);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Kind kind(boolean hits) {
        return hits ? Kind.HITS : Kind.DAMAGE;
    }

    private static void finish(long now) {
        if (!active) return;
        active = false;
        // Killed while its nametag was gone: the fight ended when the nametag went, and that gap isn't downtime.
        long end = gapStart > 0 ? gapStart : now;
        gapStart = 0;
        phases.add(new Phase(kind(currentHits), Math.max(0, end - phaseStart), segment));
        endedAt = end;
        if (end - fightStart < 1_000) return;
        FeatureConfigs.Slayer h = hudConfig();
        if (t5Label != null && h != null && h.t5PhaseSummary) {
            Sky2MAlerts.chat(t5Summary(end));
            return;
        }
        FeatureConfigs.SlayerPhaseTimer c = config();
        if (c == null || !c.enabled || !c.chat) return;
        MutableComponent msg = Component.literal("Boss phases: ").withStyle(ChatFormatting.GOLD);
        int hitN = 0, dmgN = 0;
        for (int i = 0; i < phases.size(); i++) {
            Phase p = phases.get(i);
            if (i > 0) msg.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            String label = switch (p.kind()) {
                case HITS -> "Hits " + ++hitN;
                case DAMAGE -> "Damage " + ++dmgN;
                case DOWNTIME -> "Downtime";
            };
            msg.append(Component.literal(label + " ").withStyle(colour(p.kind())))
                .append(Component.literal(seconds(p.millis())).withStyle(ChatFormatting.WHITE));
        }
        msg.append(Component.literal(" (total " + seconds(end - fightStart) + ")").withStyle(ChatFormatting.GRAY));
        Sky2MAlerts.chat(msg);
    }

    /** "T5 Tara: 41.2s (phase 1 18.1s · downtime 3.4s · phase 2 19.7s)". */
    private static Component t5Summary(long end) {
        MutableComponent msg = Component.literal(t5Label + ": ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(seconds(end - fightStart)).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" (").withStyle(ChatFormatting.GRAY));
        boolean first = true;
        for (long[] part : segments()) {
            if (!first) msg.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            first = false;
            boolean downtime = part[0] < 0;
            msg.append(Component.literal(downtime ? "downtime " : "phase " + part[0] + " ").withStyle(downtime ? ChatFormatting.DARK_AQUA : ChatFormatting.YELLOW))
                .append(Component.literal(seconds(part[1])).withStyle(ChatFormatting.WHITE));
        }
        return msg.append(Component.literal(")").withStyle(ChatFormatting.GRAY));
    }

    /** Completed phases grouped per real boss phase: {number, ms}, with downtime as {-1, ms}. */
    private static List<long[]> segments() {
        List<long[]> out = new ArrayList<>();
        long[] current = null;
        for (Phase p : phases) {
            if (p.kind() == Kind.DOWNTIME) {
                out.add(new long[]{-1, p.millis()});
                current = null;
                continue;
            }
            if (current == null || current[0] != p.segment() + 1) {
                current = new long[]{p.segment() + 1, 0};
                out.add(current);
            }
            current[1] += p.millis();
        }
        return out;
    }

    private static void reset() {
        active = false;
        phases.clear();
        fightStart = 0;
        phaseStart = 0;
        endedAt = 0;
        gapStart = 0;
        segment = 0;
        hitsLeft = 0;
        bossName = "";
        t5Label = null;
        currentHits = false;
        killed = false;
    }

    /**
     * Lines for the Boss Phase Display during (and for a few seconds after) a Tier 5 Tarantula or Revenant: hits left
     * while it shows hits, each completed phase and the downtime between them, and the current phase's time so far.
     */
    static List<Component> t5Lines() {
        List<Component> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (t5Label == null || !active && (endedAt == 0 || now - endedAt > KEEP_MS)) return out;
        if (active && currentHits && gapStart == 0 && hitsLeft > 0) {
            out.add(Component.literal("Hits left: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(String.valueOf(hitsLeft)).withStyle(ChatFormatting.WHITE)));
        }
        List<long[]> parts = segments();
        // The current phase continues the last completed stretch of the same boss phase (a hits/damage switch).
        long currentSoFar = 0;
        if (active && !parts.isEmpty() && parts.getLast()[0] == segment + 1) currentSoFar = parts.removeLast()[1];
        for (long[] part : parts) {
            out.add(part[0] < 0 ? segmentLine("Downtime", part[1], ChatFormatting.DARK_AQUA)
                : segmentLine("Phase " + part[0], part[1], ChatFormatting.YELLOW));
        }
        if (active) {
            if (gapStart > 0 && now - gapStart >= MIN_DOWNTIME_MS) {
                out.add(segmentLine("Phase " + (segment + 1), currentSoFar + gapStart - phaseStart, ChatFormatting.YELLOW));
                out.add(segmentLine("Downtime", now - gapStart, ChatFormatting.DARK_AQUA).append(Component.literal(" ...").withStyle(ChatFormatting.GRAY)));
            } else {
                out.add(segmentLine("Phase " + (segment + 1), currentSoFar + now - phaseStart, ChatFormatting.GREEN)
                    .append(Component.literal(" (now)").withStyle(ChatFormatting.GRAY)));
            }
        } else {
            out.add(segmentLine("Total", endedAt - fightStart, ChatFormatting.GRAY));
        }
        return out;
    }

    private static MutableComponent segmentLine(String label, long millis, ChatFormatting colour) {
        return Component.literal(label + ": ").withStyle(colour).append(Component.literal(seconds(millis)).withStyle(ChatFormatting.WHITE));
    }

    private static String seconds(long millis) {
        return String.format(Locale.US, "%.1fs", millis / 1000.0);
    }

    private static ChatFormatting colour(Kind kind) {
        return switch (kind) {
            case HITS -> ChatFormatting.LIGHT_PURPLE;
            case DAMAGE -> ChatFormatting.RED;
            case DOWNTIME -> ChatFormatting.DARK_AQUA;
        };
    }

    private static Component line(Kind kind, int n, long millis) {
        String label = switch (kind) {
            case HITS -> "Hit Phase " + n;
            case DAMAGE -> "Damage Phase " + n;
            case DOWNTIME -> "Downtime";
        };
        return Component.literal(label + ": ").withStyle(colour(kind))
            .append(Component.literal(seconds(millis)).withStyle(ChatFormatting.WHITE));
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (!active && (endedAt == 0 || now - endedAt > KEEP_MS)) return out;
        int hitN = 0, dmgN = 0;
        for (Phase p : phases) {
            int n = p.kind() == Kind.HITS ? ++hitN : p.kind() == Kind.DAMAGE ? ++dmgN : 0;
            out.add(line(p.kind(), n, p.millis()));
        }
        if (active) {
            if (gapStart > 0 && now - gapStart >= MIN_DOWNTIME_MS) {
                out.add(line(kind(currentHits), currentHits ? ++hitN : ++dmgN, gapStart - phaseStart));
                out.add(line(Kind.DOWNTIME, 0, now - gapStart));
            } else {
                out.add(line(kind(currentHits), currentHits ? ++hitN : ++dmgN, now - phaseStart));
            }
        }
        out.add(Component.literal("Total: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(seconds((active ? now : endedAt) - fightStart)).withStyle(ChatFormatting.WHITE)));
        return out;
    }
}
