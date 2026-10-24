package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.Sky2MCalculator;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Great Spook: the Primal Fear timer, the answer to the math Primal Fear, and the Trick or Treat and Party Chest
 * alert. Ported from SkyHanni's TheGreatSpook and SpookyChestAlert (https://github.com/hannibal002/SkyHanni,
 * features/event/spook/, LGPL-2.1). The answers are only shown; nothing is sent for you, and the Public Speaking solver
 * is not ported.
 */
public final class SpookFeatures {
    private static final String TIMER_HUD = "events_spook_fear";
    private static final long FEAR_COOLDOWN_MS = 6 * 60 * 1000L;
    /** The Great Spook is long over after this long without a Primal Fear. */
    private static final long STALE_MS = 60 * 60 * 1000L;
    private static final Pattern SPAWN = Pattern.compile("^FEAR\\. A Primal Fear has been summoned!");
    private static final Pattern MATH = Pattern.compile("^QUICK MATHS! Solve: (.+)$");
    private static final Pattern CHEST = Pattern.compile("^(SPOOKY|PARTY)! A (Trick or Treat Chest|Party Chest) has appeared!");
    private static final Pattern FEAR = Pattern.compile("Fear:\\D*(\\d+)");

    private static long lastSpawn;
    private static boolean notified;
    private static int fear = -1;
    private static int ticks;

    private SpookFeatures() {}

    private static EventsConfig.Spook config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.spook;
    }

    static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(SpookFeatures::tick);
        Sky2MHuds.register(TIMER_HUD, "Primal Fear Timer", () -> {
            EventsConfig.Spook c = config();
            return c != null && c.primalFearTimer && Sky2MLocation.onSkyblock() && lastSpawn > 0 && System.currentTimeMillis() - lastSpawn < STALE_MS;
        }, SpookFeatures::timerLines, List.of(
            Component.literal("Next fear in: ").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal("3m 12s").withStyle(ChatFormatting.AQUA))), 10, 140);
        Sky2MHuds.setSettingsSearch(TIMER_HUD, "Primal Fear Timer");
        Sky2MHuds.setting(TIMER_HUD, () -> config() != null && config().primalFearTimer);
    }

    private static void onChat(String text) {
        EventsConfig.Spook c = config();
        if (c == null) return;
        if (SPAWN.matcher(text).find()) {
            lastSpawn = System.currentTimeMillis();
            notified = false;
            return;
        }
        Matcher m = CHEST.matcher(text);
        if (c.chestAlert && m.find()) {
            boolean party = m.group(1).equals("PARTY");
            Component title = Component.literal(m.group(2).toUpperCase() + "!").withStyle(party ? ChatFormatting.RED : ChatFormatting.GOLD, ChatFormatting.BOLD);
            Sky2MAlerts.title(title, null, c.chestSound);
            return;
        }
        m = MATH.matcher(text);
        if (c.mathAnswer && m.find()) {
            String expression = m.group(1).trim().replace('x', '*').replace('X', '*').replace('×', '*').replace('÷', '/');
            try {
                String answer = Sky2MCalculator.calculate(expression).replace(",", "");
                Minecraft.getInstance().execute(() -> EventsFeatures.say(Component.literal("Primal Fear answer: ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(answer).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))));
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> EventsFeatures.say(Component.literal("Could not solve " + expression).withStyle(ChatFormatting.RED)));
            }
        }
    }

    private static void tick(Minecraft mc) {
        EventsConfig.Spook c = config();
        if (c == null || mc.player == null || lastSpawn == 0) return;
        if (!c.primalFearTimer && !c.primalFearNotify) return;
        if (++ticks % 40 == 0) fear = readFear();
        if (c.primalFearNotify && !notified && remaining() <= 0 && System.currentTimeMillis() - lastSpawn < STALE_MS) {
            notified = true;
            Sky2MAlerts.ding();
        }
    }

    private static int readFear() {
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = Compat.rawTabName(info);
            if (name == null) continue;
            Matcher m = FEAR.matcher(Sky2MLocation.strip(name.getString()));
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /** Milliseconds until the next Primal Fear can spawn; each point of Fear takes 3 seconds off. */
    private static long remaining() {
        return lastSpawn + FEAR_COOLDOWN_MS - Math.max(0, fear) * 3000L - System.currentTimeMillis();
    }

    private static List<Component> timerLines() {
        long left = remaining();
        if (left > 0) {
            return List.of(Component.literal("Next fear in: ").withStyle(ChatFormatting.DARK_PURPLE)
                .append(Component.literal(EventsFeatures.duration(left)).withStyle(ChatFormatting.AQUA)));
        }
        return List.of(Component.literal("Primal Fear Ready!").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD));
    }
}
