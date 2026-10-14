package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.Sky2MAchievements;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combat > Low Health & Mana: reads "1234/5000❤" and "300/1000✎ Mana" from the action bar and warns once when either
 * drops below its percentage, then again only after it has gone back above it.
 */
public final class LowStats {
    private static final Pattern HEALTH = Pattern.compile("([\\d,]+)/([\\d,]+)❤");
    private static final Pattern MANA = Pattern.compile("([\\d,]+)/([\\d,]+)✎");
    private static final long COOLDOWN_MS = 3_000;

    private static boolean healthLow, manaLow;
    private static long lastWarning;
    /** Health went under 5%: getting back above half counts a close call for the achievements. */
    private static boolean nearDeath;

    private LowStats() {}

    private static FeatureConfigs.LowStats config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.lowStats;
    }

    public static void init() {
        Sky2MChat.onActionBar(message -> onActionBar(message.text()));
        // Dying isn't a close call.
        Sky2MChat.onChat(message -> {
            if (message.text().matches("^ ?☠ You .*")) nearDeath = false;
        });
    }

    private static void onActionBar(String text) {
        FeatureConfigs.LowStats c = config();
        if (c == null || !Sky2MLocation.onSkyblock()) return;
        double health = fraction(HEALTH.matcher(text));
        if (health >= 0) {
            if (health < 0.05) nearDeath = true;
            else if (nearDeath && health > 0.5) {
                nearDeath = false;
                Sky2MAchievements.add(Sky2MAchievements.Stat.CLOSE_CALLS, 1);
            }
        }
        if (c.dungeonsOnly && !Sky2MLocation.inDungeon() && !Sky2MLocation.areaIs("Kuudra")) return;
        if (c.health && health >= 0) {
            boolean low = health * 100 < c.healthPercent;
            if (low && !healthLow) warn(Component.literal("LOW HEALTH!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), c);
            healthLow = low;
        }
        double mana = fraction(MANA.matcher(text));
        if (c.mana && mana >= 0) {
            boolean low = mana * 100 < c.manaPercent;
            if (low && !manaLow) warn(Component.literal("LOW MANA!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), c);
            manaLow = low;
        }
    }

    private static double fraction(Matcher m) {
        if (!m.find()) return -1;
        try {
            double now = Double.parseDouble(m.group(1).replace(",", "")), max = Double.parseDouble(m.group(2).replace(",", ""));
            return max <= 0 ? -1 : now / max;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void warn(Component title, FeatureConfigs.LowStats c) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < COOLDOWN_MS) return;
        lastWarning = now;
        Sky2MAlerts.title(title, null, false);
        if (c.sound) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f);
    }
}
