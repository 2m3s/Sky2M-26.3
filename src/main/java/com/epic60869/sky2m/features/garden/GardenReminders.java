package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sim.util.TabText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composter reminder (SkyHanni's composter notifications: the Garden tab list Composter widget). The Safari ticket
 * reminder moved to {@link com.epic60869.sky2m.features.foraging.SafariTicketReminder}.
 */
public final class GardenReminders {
    private static final Pattern ORGANIC = Pattern.compile("^\\s*Organic Matter:\\s*(?<amount>[\\d.,]+[kKmM]?)\\s*/");
    private static final Pattern FUEL = Pattern.compile("^\\s*Fuel:\\s*(?<amount>[\\d.,]+[kKmM]?)\\s*/");
    private static final Pattern TIME_LEFT = Pattern.compile("^\\s*Time Left:\\s*(?<time>.+?)\\s*$");

    private static final long COMPOSTER_COOLDOWN_MS = 5 * 60_000L;

    private static int ticks;
    private static long lastComposterWarning;
    private static boolean wasOnSkyblock;

    private GardenReminders() {}

    private static FeatureConfigs.Garden config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.garden;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return;
        boolean onSkyblock = Sky2MLocation.onSkyblock();
        if (onSkyblock && !wasOnSkyblock) ticks = 0;
        wasOnSkyblock = onSkyblock;
        if (!onSkyblock) return;
        if (++ticks % 40 != 0) return;
        FeatureConfigs.Garden c = config();
        if (c == null) return;
        if (c.composterReminder && Sky2MLocation.inGarden()) checkComposter(mc, c);
    }

    // ---------------------------------------------------------------------------------------------- Composter

    private static void checkComposter(Minecraft mc, FeatureConfigs.Garden c) {
        boolean widget = false;
        double organic = -1;
        double fuel = -1;
        String timeLeft = null;
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String line = TabText.plain(info);
            if (line == null) continue;
            if (line.trim().startsWith("Composter:")) {
                widget = true;
                continue;
            }
            Matcher m = ORGANIC.matcher(line);
            if (m.find()) {
                organic = parse(m.group("amount"));
                continue;
            }
            m = FUEL.matcher(line);
            if (m.find()) {
                fuel = parse(m.group("amount"));
                continue;
            }
            m = TIME_LEFT.matcher(line);
            if (m.find()) timeLeft = m.group("time");
        }
        // Without the widget (or its numbers) nothing is known: don't guess.
        if (!widget || organic < 0 || fuel < 0) return;

        boolean inactive = timeLeft != null && timeLeft.toLowerCase(Locale.ROOT).startsWith("inactive");
        String reason = null;
        if (organic < 1 && fuel < 1) reason = "out of Organic Matter and Fuel";
        else if (organic < 1) reason = "out of Organic Matter";
        else if (fuel < 1) reason = "out of Fuel";
        else if (inactive) reason = "inactive";
        else if (c.composterLowAmount > 0 && (organic < c.composterLowAmount || fuel < c.composterLowAmount)) {
            reason = organic < c.composterLowAmount ? "low on Organic Matter" : "low on Fuel";
        }
        if (reason == null) return;

        long now = System.currentTimeMillis();
        if (now - lastComposterWarning < COMPOSTER_COOLDOWN_MS) return;
        lastComposterWarning = now;
        Sky2MAlerts.title(Component.literal("§cComposter " + reason + "!"), Component.literal("§eRefill your composter"));
        Sky2MAlerts.chat(Component.literal("§cYour composter is " + reason + "! §eRefill it."));
    }

    /** "12.5k" -> 12500, "1,200" -> 1200, "2m" -> 2000000. */
    private static double parse(String text) {
        String s = text.replace(",", "").toLowerCase(Locale.ROOT);
        double mult = 1;
        if (s.endsWith("k")) {
            mult = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
