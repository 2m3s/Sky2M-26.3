package com.epic60869.sky2m.features.misc.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;

/**
 * Colours written as "&c" in text you can type: Minecraft won't let you type or paste "§" in chat. Copy Chat writes them
 * (Left Ctrl + the Copy Message Key) and Sky2M chat shows them. So normal text isn't changed, "&" only counts before
 * something it can colour, and not inside a word or link ("Q&A", "?x=1&a=2") or before a space ("Q&a session").
 */
public final class ChatColourCodes {
    private static final String CODES = "0123456789abcdefklmnor";
    private static final String COLOUR_CODES = "0123456789abcdef";
    private static final int[] COLOUR_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private ChatColourCodes() {}

    /** Whether a code starts at {@code i} of {@code text}. */
    private static boolean isCode(String text, int i) {
        if (text.charAt(i) != '&' || i + 2 >= text.length() || CODES.indexOf(text.charAt(i + 1)) < 0) return false;
        char next = text.charAt(i + 2);
        if (Character.isWhitespace(next)) return false;
        // In a link, "&a=2" is part of the address.
        int word = i;
        while (word > 0 && !Character.isWhitespace(text.charAt(word - 1))) word--;
        if (text.substring(word, i).contains("://")) return false;
        // What comes before is the text, not an earlier code: the "6" of "&6&lhi" doesn't make this one inside a word.
        int before = i;
        while (before >= 2 && text.charAt(before - 2) == '&' && CODES.indexOf(text.charAt(before - 1)) >= 0) before -= 2;
        return !(before > 0 && Character.isLetterOrDigit(text.charAt(before - 1)) && Character.isLetterOrDigit(next));
    }

    /** {@code text} with its codes as styles. Public for the game test. */
    public static MutableComponent parse(String text) {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < text.length(); i++) {
            if (!isCode(text, i)) {
                run.append(text.charAt(i));
                continue;
            }
            if (!run.isEmpty()) {
                out.append(Component.literal(run.toString()).setStyle(style));
                run.setLength(0);
            }
            char letter = text.charAt(++i);
            ChatFormatting code = ChatFormatting.getByCode(letter);
            if (code == null || code == ChatFormatting.RESET) style = Style.EMPTY;
            else if (COLOUR_CODES.indexOf(letter) >= 0) style = Style.EMPTY.withColor(code);
            else style = style.applyFormat(code);
        }
        if (!run.isEmpty()) out.append(Component.literal(run.toString()).setStyle(style));
        return out;
    }

    /** {@code text} as it reads without its codes. Public for the game test. */
    public static String strip(String text) {
        return parse(text).getString();
    }

    /**
     * {@code text} with codes for its styles, one style per char. A colour that isn't one of the 16 becomes the closest that
     * is. Spaces don't start a code, and a change inside a word waits for the word's end, as {@link #isCode} wouldn't read it.
     * Public for the game test.
     */
    public static String encode(String text, List<Style> styles) {
        StringBuilder out = new StringBuilder();
        char colour = 'r';
        String formats = "";
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            Style style = styles.get(i);
            char wantColour = colourCode(style.getColor());
            String wantFormats = formats(style);
            boolean inWord = i > 0 && Character.isLetterOrDigit(text.charAt(i - 1)) && Character.isLetterOrDigit(ch);
            if (!Character.isWhitespace(ch) && !inWord && (wantColour != colour || !wantFormats.equals(formats))) {
                boolean reset = wantColour != colour || formats.chars().anyMatch(f -> wantFormats.indexOf(f) < 0);
                if (reset) out.append('&').append(wantColour);
                for (char f : wantFormats.toCharArray()) {
                    if (reset || formats.indexOf(f) < 0) out.append('&').append(f);
                }
                colour = wantColour;
                formats = wantFormats;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String formats(Style style) {
        StringBuilder out = new StringBuilder();
        if (style.isObfuscated()) out.append('k');
        if (style.isBold()) out.append('l');
        if (style.isStrikethrough()) out.append('m');
        if (style.isUnderlined()) out.append('n');
        if (style.isItalic()) out.append('o');
        return out.toString();
    }

    /** The code of the closest of the 16 colours, "r" with no colour. */
    private static char colourCode(TextColor colour) {
        if (colour == null) return 'r';
        int rgb = colour.getValue();
        char best = 'f';
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < COLOUR_RGB.length; i++) {
            int other = COLOUR_RGB[i];
            int r = (rgb >> 16 & 255) - (other >> 16 & 255), g = (rgb >> 8 & 255) - (other >> 8 & 255), b = (rgb & 255) - (other & 255);
            int distance = r * r + g * g + b * b;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = COLOUR_CODES.charAt(i);
            }
        }
        return best;
    }
}
