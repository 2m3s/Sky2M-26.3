package com.epic60869.sky2m.features.dungeons;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A player's name colour as Hypixel draws it in the tab list, which is their rank's colour ([VIP] green, [MVP] aqua,
 * [MVP++] gold, no rank grey). Remembered once seen, so it's still known after they leave the tab list. Used by
 * Auto Requeue's Leave Title and Team Secrets so names aren't plain white.
 */
public final class PlayerNameColour {
    private static final Map<String, Integer> SEEN = new HashMap<>();
    /** Legacy colour codes, as Hypixel sometimes puts "§b" inside the text instead of styling it. */
    private static final String CODES = "0123456789abcdef";

    private PlayerNameColour() {}

    /** The name's colour (RGB) from the tab list now, else the last one seen, else null. */
    public static Integer of(String name) {
        Integer now = fromTab(name);
        String key = name.toLowerCase(Locale.ROOT);
        if (now != null) SEEN.put(key, now);
        return now != null ? now : SEEN.get(key);
    }

    private static Integer fromTab(String name) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return null;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null || !hasName(display.getString(), name)) continue;
            Integer colour = colourOf(display, name);
            if (colour != null) return colour;
        }
        return null;
    }

    /** The colour the name is drawn in within {@code line}, or null. */
    public static Integer colourOf(Component line, String name) {
        Optional<Integer> found = line.visit((style, text) -> {
            if (!hasName(text, name)) return Optional.empty();
            Integer legacy = legacyBefore(text, text.indexOf(name));
            if (legacy != null) return Optional.of(legacy);
            TextColor colour = style.getColor();
            return colour == null ? Optional.empty() : Optional.of(colour.getValue());
        }, Style.EMPTY);
        return found.orElse(null);
    }

    /** The last "§x" colour code before {@code at} in the text, as RGB, or null. */
    private static Integer legacyBefore(String text, int at) {
        for (int i = Math.min(at, text.length()) - 2; i >= 0; i--) {
            if (text.charAt(i) != '§' || CODES.indexOf(Character.toLowerCase(text.charAt(i + 1))) < 0) continue;
            char code = Character.toLowerCase(text.charAt(i + 1));
            for (net.minecraft.ChatFormatting f : net.minecraft.ChatFormatting.values()) {
                TextColor legacy = TextColor.fromLegacyFormat(f);
                if (legacy != null && f.toString().charAt(1) == code) return legacy.getValue();
            }
            return null;
        }
        return null;
    }

    /** Whether the name is in the text as a whole name ("Steve", not inside "Steve2"); "§bSteve" counts. */
    private static boolean hasName(String text, String name) {
        int i = text.indexOf(name);
        while (i >= 0) {
            boolean code = i >= 2 && text.charAt(i - 2) == '§';
            boolean before = i == 0 || code || !isNameChar(text.charAt(i - 1));
            int end = i + name.length();
            boolean after = end >= text.length() || !isNameChar(text.charAt(end));
            if (before && after) return true;
            i = text.indexOf(name, i + 1);
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
