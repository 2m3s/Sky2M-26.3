package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal Timers, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod,
 * splittimers/TerminalTimersFeature), a port of Odin's Terminal Times (BSD-3-Clause, TerminalTimes.kt):
 * <ul>
 *   <li>Splits: every "Name activated/completed a terminal/device/lever! (n/7)" line gets "(section | phase)" added:
 *   the time since this P3 section started and since Goldor's "Who dares trespass" line. When the core opens, the
 *   section times and total are posted. Sections end like Odin's: the gate blown with every terminal done, or the
 *   count going back down.</li>
 *   <li>Solve times: how long each terminal you complete was open, posted once your own "completed a terminal" line
 *   confirms it was solved (not just closed).</li>
 *   <li>A HUD of the current section, its time, the phase time and the sections already done.</li>
 * </ul>
 */
public final class TerminalTimers {
    private static final Pattern COMPLETE = Pattern.compile("^(\\w{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final Pattern[] TERMINAL_TITLES = {Pattern.compile("^Correct all the panes!$"), Pattern.compile("^Change all to same color!$"),
        Pattern.compile("^Click in order!$"), Pattern.compile("^What starts with: '\\w'\\?$"), Pattern.compile("^Select all the [\\w ]+ items!$"),
        Pattern.compile("^Click the button on time!$")};
    private static final String GATE = "The gate has been destroyed!";
    private static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final String CORE = "The Core entrance is opening!";
    private static final long MATCH_WINDOW_MS = 3000;

    private static int completed, total = 7;
    private static final List<Float> SECTIONS = new ArrayList<>();
    private static boolean gateBlown, inPhase;
    private static long sectionStart, phaseStart;
    private static String lastLine;
    private static long lastLineAt;

    // The terminal open now (its title and when it opened), and a closed one waiting for its chat line.
    private static Object openMenu;
    private static String openTitle;
    private static long openedAt;
    private static String pendingTitle;
    private static double pendingSeconds;
    private static long pendingAt, ownLineAt;
    private static Object lastLevel;

    private TerminalTimers() {}

    private static FeatureConfigs.TerminalTimers config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.timers.terminalTimers;
    }

    public static void init() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : onMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(TerminalTimers::tick);
        Sky2MHuds.register("terminal_timers", "Terminal Timers", () -> config() != null && config().enabled && config().hud, TerminalTimers::lines,
            List.of(title("Terminal Timers"), line("Section 3", "8.42s"), line("Phase", "31.07s"), Component.literal("12.10s | 10.55s").withStyle(ChatFormatting.GRAY)), 10, 240);
    }

    private static void reset(boolean full) {
        long now = System.currentTimeMillis();
        if (full) {
            SECTIONS.clear();
            phaseStart = now;
        } else {
            SECTIONS.add(since(sectionStart));
        }
        completed = 0;
        total = 7;
        sectionStart = now;
        gateBlown = false;
    }

    private static Component onMessage(Component message) {
        FeatureConfigs.TerminalTimers c = config();
        if (c == null || !c.enabled) return message;
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null) return message;
        plain = plain.trim();
        Matcher m = COMPLETE.matcher(plain);
        if (m.matches()) {
            Minecraft mc = Minecraft.getInstance();
            if (m.group(3).equals("terminal") && mc.player != null && m.group(1).equals(mc.player.getGameProfile().name())) {
                ownLineAt = System.currentTimeMillis();
                postSolve();
            }
            return c.splits ? split(message, plain, m) : message;
        }
        if (plain.equals(GOLDOR)) {
            reset(true);
            inPhase = true;
        } else if (plain.equals(GATE)) {
            if (completed == total) reset(false);
            else gateBlown = true;
        } else if (plain.equals(CORE) && inPhase) {
            reset(false);
            inPhase = false;
            if (c.splits) {
                MutableComponent times = Component.literal("Terminal times: ").withStyle(ChatFormatting.GRAY);
                for (int i = 0; i < SECTIONS.size(); i++) {
                    if (i > 0) times.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
                    times.append(Component.literal(fmt(SECTIONS.get(i))).withStyle(ChatFormatting.GOLD));
                }
                times.append(Component.literal(", Total: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(fmt(since(phaseStart))).withStyle(ChatFormatting.GOLD));
                Sky2MAlerts.chat(times);
            }
        }
        return message;
    }

    /** The line with "(section | phase)" added, and Odin's section bookkeeping. */
    private static Component split(Component message, String plain, Matcher m) {
        long now = System.currentTimeMillis();
        // The same line offered twice (another mod adding its own copy) is only timed once.
        if (plain.equals(lastLine) && now - lastLineAt < 250) return message;
        lastLine = plain;
        lastLineAt = now;
        if (!inPhase) {
            // Joined mid-phase: start the timers here.
            reset(true);
            inPhase = true;
        }
        int current = Integer.parseInt(m.group(4)), max = Integer.parseInt(m.group(5));
        MutableComponent out = message.copy().append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(fmt(since(sectionStart))).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(fmt(since(phaseStart))).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
        if ((current == max && gateBlown) || current < completed) {
            reset(false);
        } else {
            completed = current;
            total = max;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ solve times

    private static void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            inPhase = false;
            SECTIONS.clear();
        }
        FeatureConfigs.TerminalTimers c = config();
        if (c == null || !c.enabled) return;
        Screen screen = mc.gui.screen();
        Object menu = screen instanceof AbstractContainerScreen<?> container ? container.getMenu() : null;
        if (menu == openMenu) return;
        // The terminal closed (or another menu replaced it): wait for its "completed a terminal" line.
        if (openMenu != null && openTitle != null) {
            pendingTitle = openTitle;
            pendingSeconds = (System.currentTimeMillis() - openedAt) / 1000.0;
            pendingAt = System.currentTimeMillis();
            postSolve();
        }
        openMenu = menu;
        openTitle = null;
        if (menu != null && Sky2MLocation.inDungeon()) {
            String title = Sky2MLocation.strip(screen.getTitle().getString()).trim();
            for (Pattern p : TERMINAL_TITLES) {
                if (p.matcher(title).matches()) {
                    openTitle = title;
                    openedAt = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    /** Posts the solve time once both the close and your own completion line have arrived, close together. */
    private static void postSolve() {
        if (pendingTitle == null || ownLineAt == 0) return;
        if (Math.abs(pendingAt - ownLineAt) > MATCH_WINDOW_MS) {
            // Only the older one is stale: keep the newer waiting for its partner.
            if (pendingAt < ownLineAt) pendingTitle = null;
            else ownLineAt = 0;
            return;
        }
        FeatureConfigs.TerminalTimers c = config();
        if (c != null && c.solveTimes) {
            Sky2MAlerts.chat(Component.literal(pendingTitle).withStyle(ChatFormatting.GREEN)
                .append(Component.literal(" solved in ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.ROOT, "%.2fs", pendingSeconds)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("!").withStyle(ChatFormatting.GRAY)));
        }
        pendingTitle = null;
        ownLineAt = 0;
    }

    // ------------------------------------------------------------------------------------------------ HUD

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        if (!inPhase) return out;
        out.add(title("Terminal Timers"));
        out.add(line("Section " + (SECTIONS.size() + 1) + " (" + completed + "/" + total + ")", fmt(since(sectionStart))));
        out.add(line("Phase", fmt(since(phaseStart))));
        if (!SECTIONS.isEmpty()) {
            StringBuilder done = new StringBuilder();
            for (int i = 0; i < SECTIONS.size(); i++) done.append(i > 0 ? " | " : "").append(fmt(SECTIONS.get(i)));
            out.add(Component.literal(done.toString()).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private static Component title(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static Component line(String label, String value) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static float since(long start) {
        return start <= 0 ? 0f : (System.currentTimeMillis() - start) / 1000f;
    }

    private static String fmt(float seconds) {
        return String.format(Locale.ROOT, "%.2fs", seconds);
    }
}
