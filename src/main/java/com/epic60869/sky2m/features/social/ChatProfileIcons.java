package com.epic60869.sky2m.features.social;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profile mode icons (Ironman, Stranded, Bingo) next to chat names, in the spirit of SkyOcean's chat icons. Sky2M has
 * no profile API for other players, so the icon comes from the tab list: Hypixel shows it after a player's name there
 * (and in some chats, which are left alone), and it is remembered for the session so party, guild and private
 * messages from someone who has since left your island still get it.
 */
public final class ChatProfileIcons {
    private static final String ICONS = "♲Ⓑ☀";
    /** Same sender shape as Clickable Names; the icon groups catch an icon Hypixel already put there. */
    private static final Pattern SENDER = Pattern.compile(
        "^(?:(?:Party|Guild|Officer|Co-op) > |From |To )?(?:\\[\\d+] )?(?:[^\\w\\s\\[] )?(?:\\[[A-Z+]+] )?(\\w{3,16})(?<icon1> [" + ICONS + "])?(?: \\[[^]]+])?(?<icon2> [" + ICONS + "])?: ");

    /** Lower-case name -> the icon as it looks in the tab list. */
    private static final Map<String, Component> KNOWN = new ConcurrentHashMap<>();
    private static int ticks;

    private ChatProfileIcons() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> KNOWN.clear());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 40 != 0) return;
            SocialConfig c = SocialConfig.current();
            if (c == null || !c.profileIcons || mc.getConnection() == null) return;
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                Component display = info.getTabListDisplayName();
                if (display == null) continue;
                String name = info.getProfile().name().toLowerCase(java.util.Locale.ROOT);
                Component icon = iconIn(display);
                // Seen in the tab list without an icon: they are on a normal profile now.
                if (icon == null) KNOWN.remove(name);
                else KNOWN.put(name, icon);
            }
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : addIcon(message));
    }

    /** The first profile icon in a tab list name, with the colour it has there. */
    private static Component iconIn(Component display) {
        Component[] found = {null};
        display.visit((style, text) -> {
            for (int i = 0; i < text.length() && found[0] == null; i++) {
                if (ICONS.indexOf(text.charAt(i)) >= 0) {
                    found[0] = Component.literal(codesBefore(text, i) + text.charAt(i)).withStyle(style);
                }
            }
            if (found[0] != null) return Optional.of(Boolean.TRUE);
            return Optional.<Boolean>empty();
        }, Style.EMPTY);
        return found[0];
    }

    private static Component addIcon(Component message) {
        SocialConfig c = SocialConfig.current();
        if (c == null || !c.profileIcons || KNOWN.isEmpty()) return message;
        String raw = message.getString();
        // Matched without § codes, mapped back to where each character is in the raw text.
        StringBuilder plain = new StringBuilder();
        int[] at = new int[raw.length() + 1];
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '§' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            at[plain.length()] = i;
            plain.append(raw.charAt(i));
        }
        Matcher m = SENDER.matcher(plain);
        if (!m.find() || m.group("icon1") != null || m.group("icon2") != null) return message;
        Component icon = KNOWN.get(m.group(1).toLowerCase(java.util.Locale.ROOT));
        if (icon == null) return message;
        // Insert " <icon>" straight after the last character of the name.
        int end = at[m.end(1) - 1] + 1;
        MutableComponent out = Component.empty();
        boolean[] done = {false};
        int[] pos = {0};
        message.visit((style, text) -> {
            int from = pos[0], to = from + text.length();
            pos[0] = to;
            if (done[0] || end <= from || end > to) {
                out.append(Component.literal(text).withStyle(style));
                return Optional.empty();
            }
            done[0] = true;
            int b = end - from;
            out.append(Component.literal(text.substring(0, b)).withStyle(style));
            out.append(Component.literal(" ")).append(icon.copy());
            if (b < text.length()) out.append(Component.literal(codesBefore(text, b) + text.substring(b)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return done[0] ? out : message;
    }

    /** The § colour and format codes still in force at {@code index}: the last colour (or reset) and any formats after it. */
    private static String codesBefore(String text, int index) {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i + 1 < index; i++) {
            if (text.charAt(i) != '§') continue;
            char code = Character.toLowerCase(text.charAt(++i));
            if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r') codes.setLength(0);
            if (code != 'r') codes.append('§').append(code);
        }
        return codes.toString();
    }
}
