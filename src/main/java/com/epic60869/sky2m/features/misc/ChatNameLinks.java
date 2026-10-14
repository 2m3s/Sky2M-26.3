package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat > Clickable Names: the sender's name on player chat messages (all, party, guild, officer, co-op and private)
 * opens their profile in Sky2M's profile viewer. The message is rebuilt piece by piece with the same styles, and only
 * the name's own characters get the new click and hover.
 */
public final class ChatNameLinks {
    /** "Party > [123] ☘ [MVP+] Name [Guild]: hi": the sender, whatever comes in front of it. */
    private static final Pattern SENDER = Pattern.compile(
        "^(?:(?:Party|Guild|Officer|Co-op) > |From |To )?(?:\\[\\d+] )?(?:[^\\w\\s\\[] )?(?:\\[[A-Z+]+] )?(\\w{3,16})(?: \\[[^]]+])?(?: [^\\w\\s:])?: ");

    private ChatNameLinks() {}

    public static void init() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : link(message));
    }

    private static Component link(Component message) {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || !c.chat.clickableNames) return message;
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
        if (!m.find()) return message;
        String name = m.group(1);
        int start = at[m.start(1)], end = at[m.end(1) - 1] + 1;
        Style link = Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/s2 pv " + name))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open " + name + "'s profile").withStyle(ChatFormatting.YELLOW)));
        MutableComponent out = Component.empty();
        int[] pos = {0};
        message.visit((style, text) -> {
            int from = pos[0], to = from + text.length();
            pos[0] = to;
            if (to <= start || from >= end) {
                out.append(Component.literal(text).withStyle(style));
                return Optional.empty();
            }
            int a = Math.max(start, from) - from, b = Math.min(end, to) - from;
            // Hypixel colours names with § codes in the text, so each cut piece carries the codes in force before it.
            if (a > 0) out.append(Component.literal(text.substring(0, a)).withStyle(style));
            out.append(Component.literal(codesBefore(text, a) + text.substring(a, b)).withStyle(style.withClickEvent(link.getClickEvent()).withHoverEvent(link.getHoverEvent())));
            if (b < text.length()) out.append(Component.literal(codesBefore(text, b) + text.substring(b)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
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
