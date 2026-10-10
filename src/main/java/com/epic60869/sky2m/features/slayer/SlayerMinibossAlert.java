package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayer miniboss alert: a title and a ding when one of your slayer minibosses spawns. Hypixel says so in chat
 * ("SLAYER MINI-BOSS Primordial Viscount has spawned!") only for your own, so other players' minibosses nearby
 * don't set it off.
 */
public final class SlayerMinibossAlert {
    private static final Pattern SPAWNED = Pattern.compile("^\\s*SLAYER MINI-BOSS (?<name>.+?) has spawned!\\s*$");

    private SlayerMinibossAlert() {}

    public static void init() {
        Sky2MChat.onChat(message -> {
            if (!enabled()) return;
            Matcher m = SPAWNED.matcher(message.text());
            if (!m.matches()) return;
            Sky2MAlerts.title(Component.literal("MINIBOSS!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.literal(m.group("name").trim()).withStyle(ChatFormatting.GOLD));
        });
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.slayers.minibossAlert;
    }
}
