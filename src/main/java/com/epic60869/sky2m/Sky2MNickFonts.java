package com.epic60869.sky2m;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Nickname fonts (the font is chosen on shadowisabot.com).
 *
 * Letter fonts (Small Caps, Script, Bubble, Enchanting Runes, ...) swap the letters for their Unicode look-alikes, so they are part of
 * the nickname text itself and every Sky2M user sees them. Style fonts (Bold, Italic, ...) and Minecraft's own
 * fonts (Illager runes, Uniform) are applied as text style and synced as the "font" field.
 *
 * {@link #plain} turns look-alike letters back into normal ones, so the nickname filter can't be dodged with them.
 */
public final class Sky2MNickFonts {
    public enum NickFont {
        DEFAULT("Default"),
        BOLD("Bold"),
        ITALIC("Italic"),
        BOLD_ITALIC("Bold Italic"),
        UNDERLINED("Underlined"),
        SMALL_CAPS("Small Caps"),
        FULL_WIDTH("Full Width"),
        BUBBLE("Bubble"),
        SCRIPT("Script"),
        FRAKTUR("Fraktur"),
        DOUBLE_STRUCK("Double Struck"),
        MONOSPACE("Monospace"),
        SANS_BOLD("Sans Bold"),
        ENCHANTING("Enchanting Runes"),
        ILLAGER("Illager Runes"),
        UNIFORM("Uniform");

        public final String label;

        NickFont(String label) {
            this.label = label;
        }

        public NickFont next() {
            NickFont[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    // Minecraft's rune fonts only have letters (and the illager one digits), so Sky2M's copies fall back to the
    // normal font for everything else; otherwise a name like "2m3s" comes out with missing characters.
    /**
     * The enchanting table alphabet as the Unicode look-alikes people use for it (a few letters take more than one
     * character). As letters rather than a font, it shows everywhere, including tab lists drawn by other mods.
     */
    private static final String[] GALACTIC = {
        "\u1511", "\u0296", "\u14F5", "\u21B8", "\u14B7", "\u2393", "\u22A3", "\u2351", "\u254E", "\u22EE",
        "\uA58C", "\uA58E", "\u14B2", "\u30EA", "\uD835\uDE79", "!\u00A1", "\u1451", "\u2237", "\u14ED", "\u2138 \u0323",
        "\u268D", "\u234A", "\u2234", " \u0307/", "||", "\u2A05"};
    private static final FontDescription ILLAGER_ALT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("sky2m", "illager"));
    private static final FontDescription UNIFORM_FONT = new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

    private Sky2MNickFonts() {}

    /** The font saved under {@code name} (its label or enum name), or DEFAULT. */
    public static NickFont parse(String name) {
        if (name == null || name.isBlank()) return NickFont.DEFAULT;
        for (NickFont font : NickFont.values()) {
            if (font.label.equalsIgnoreCase(name.trim()) || font.name().equalsIgnoreCase(name.trim())) return font;
        }
        return NickFont.DEFAULT;
    }

    /** Swaps letters and digits for the font's look-alikes; other characters (and already-converted ones) stay. */
    public static String letters(String text, NickFont font) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (font == NickFont.ENCHANTING && ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z'))) {
                out.append(GALACTIC[Character.toLowerCase(cp) - 'a']);
            } else {
                out.appendCodePoint(map(cp, font));
            }
        });
        return out.toString();
    }

    /** Applies the font's style (bold, italic, Minecraft font) on top of {@code style}. */
    public static Style style(Style style, NickFont font) {
        return switch (font) {
            case BOLD -> style.withBold(true);
            case ITALIC -> style.withItalic(true);
            case BOLD_ITALIC -> style.withBold(true).withItalic(true);
            case UNDERLINED -> style.withUnderlined(true);
            case ILLAGER -> style.withFont(ILLAGER_ALT);
            case UNIFORM -> style.withFont(UNIFORM_FONT);
            default -> style;
        };
    }

    /** Whether the font changes the letters themselves (so it travels inside the nickname text). */
    public static boolean isLetterFont(NickFont font) {
        return switch (font) {
            case SMALL_CAPS, FULL_WIDTH, BUBBLE, SCRIPT, FRAKTUR, DOUBLE_STRUCK, MONOSPACE, SANS_BOLD, ENCHANTING -> true;
            default -> false;
        };
    }

    /** Look-alike letters back to plain ones: "𝓯𝓲𝓼𝓱" and "ꜰɪꜱʜ" become "fish". Used by the nickname filter. */
    public static String plain(String text) {
        if (text == null) return "";
        // Enchanting table letters first (longest first), before NFKC turns some of them into other letters.
        java.util.List<String> glyphs = java.util.Arrays.asList(GALACTIC);
        String[] order = GALACTIC.clone();
        java.util.Arrays.sort(order, (a, b) -> b.length() - a.length());
        for (String glyph : order) text = text.replace(glyph, String.valueOf((char) ('a' + glyphs.indexOf(glyph))));
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            int small = SMALL_CAPS.indexOf(cp);
            if (small >= 0 && cp != 'x') out.append((char) ('a' + small));
            else out.appendCodePoint(cp);
        });
        // NFKC folds the mathematical, full-width and circled letters to ASCII.
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static int map(int cp, NickFont font) {
        boolean upper = cp >= 'A' && cp <= 'Z';
        boolean lower = cp >= 'a' && cp <= 'z';
        boolean digit = cp >= '0' && cp <= '9';
        if (!upper && !lower && !digit) return cp;
        int i = upper ? cp - 'A' : lower ? cp - 'a' : cp - '0';
        return switch (font) {
            case SMALL_CAPS -> digit ? cp : SMALL_CAPS.charAt(i);
            case FULL_WIDTH -> cp + 0xFEE0;
            case BUBBLE -> upper ? 0x24B6 + i : lower ? 0x24D0 + i : (i == 0 ? 0x24EA : 0x2460 + i - 1);
            case SCRIPT -> upper ? 0x1D4D0 + i : lower ? 0x1D4EA + i : cp;            // bold script (no gaps)
            case FRAKTUR -> upper ? 0x1D56C + i : lower ? 0x1D586 + i : cp;           // bold fraktur (no gaps)
            case DOUBLE_STRUCK -> upper ? doubleStruckUpper(i) : lower ? 0x1D552 + i : 0x1D7D8 + i;
            case MONOSPACE -> upper ? 0x1D670 + i : lower ? 0x1D68A + i : 0x1D7F6 + i;
            case SANS_BOLD -> upper ? 0x1D5D4 + i : lower ? 0x1D5EE + i : 0x1D7EC + i;
            default -> cp;
        };
    }

    /** Double-struck capitals C, H, N, P, Q, R and Z live in the Letterlike Symbols block. */
    private static int doubleStruckUpper(int i) {
        return switch ((char) ('A' + i)) {
            case 'C' -> 0x2102;
            case 'H' -> 0x210D;
            case 'N' -> 0x2115;
            case 'P' -> 0x2119;
            case 'Q' -> 0x211A;
            case 'R' -> 0x211D;
            case 'Z' -> 0x2124;
            default -> 0x1D538 + i;
        };
    }
}
