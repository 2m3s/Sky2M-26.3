package com.epic60869.sky2m.features.misc.crosshair;

import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.FeatureConfigs.Crosshair;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Crosshair codes: Killer560's Mod's share code (KXH1-..., also Sky2M' own, so codes work in both), Valorant's
 * profile codes and CS2's share codes. Valorant and CS2 sizes are their 1080p pixel sizes, so imports use Screen Pixels.
 * <p>
 * The KXH1 layout is Killer560's Mod's (crosshair/CustomCrosshairConfig), MIT License, Copyright (c) 2026 Killer560.
 */
public final class CrosshairCodes {
    public static final String KXH1 = "KXH1-";

    private static final int F_DOT = 1, F_TOP = 1 << 1, F_BOTTOM = 1 << 2, F_LEFT = 1 << 3, F_RIGHT = 1 << 4,
        F_SEP_DOT = 1 << 5, F_CHROMA = 1 << 6, F_INVERT = 1 << 7, F_OUTLINE = 1 << 8, F_MOVE = 1 << 9,
        F_SPRINT = 1 << 10, F_JUMP = 1 << 11, F_RECOIL = 1 << 12, F_ENTITY = 1 << 13, F_BLOCK = 1 << 14,
        F_ATTACK = 1 << 15, F_HIDE_MENUS = 1 << 16, F_THIRD = 1 << 17;

    private CrosshairCodes() {}

    /** What a code was, for the message after importing. Null if it wasn't a crosshair code. */
    public static String apply(Crosshair c, String code) {
        String s = code == null ? "" : code.trim();
        if (s.regionMatches(true, 0, KXH1, 0, KXH1.length())) return applyKxh1(c, s) ? "Sky2M" : null;
        if (s.regionMatches(true, 0, "CSGO-", 0, 5)) return applyCs2(c, s) ? "CS2" : null;
        if (s.matches("^\\d+;.*") && s.contains(";")) return applyValorant(c, s) ? "Valorant" : null;
        return null;
    }

    // -------------------------------------------------------------------------------------------- KXH1

    public static String export(Crosshair c) {
        ByteBuffer b = ByteBuffer.allocate(50);
        // Killer560's Mod has no Image style: it gets a cross.
        b.put((byte) (c.style == Crosshair.Style.IMAGE ? 0 : c.style.ordinal()));
        b.put((byte) c.sizeMode.ordinal());
        int f = 0;
        f |= c.dot ? F_DOT : 0;
        f |= c.armTop ? F_TOP : 0;
        f |= c.armBottom ? F_BOTTOM : 0;
        f |= c.armLeft ? F_LEFT : 0;
        f |= c.armRight ? F_RIGHT : 0;
        f |= c.separateDotColour ? F_SEP_DOT : 0;
        f |= chromaSpeed(c.colour) > 0 ? F_CHROMA : 0;
        f |= c.invert ? F_INVERT : 0;
        f |= c.outline ? F_OUTLINE : 0;
        f |= c.spreadMoving ? F_MOVE : 0;
        f |= c.spreadSprinting ? F_SPRINT : 0;
        f |= c.spreadJumping ? F_JUMP : 0;
        f |= c.recoil ? F_RECOIL : 0;
        f |= c.colourOnEntity ? F_ENTITY : 0;
        f |= c.colourOnBlock ? F_BLOCK : 0;
        f |= c.attackIndicator ? F_ATTACK : 0;
        f |= c.thirdPerson ? F_THIRD : 0;
        b.putInt(f);
        // The tenth size is Killer560's chroma speed; Sky2M' chroma speed lives in the colour, so 1 (their default).
        for (float v : new float[]{c.length, c.thickness, c.gap, c.dotSize, c.circleRadius, c.circleThickness, c.rotation,
            c.scale, c.outlineThickness, 1f, c.spreadAmount, c.recoilAmount}) {
            b.putShort((short) Math.round(v * 20f));
        }
        b.putInt(argb(c.colour));
        b.putInt(argb(c.outlineColour));
        b.putInt(argb(c.dotColour));
        b.putInt(argb(c.entityColour));
        b.putInt(argb(c.blockColour));
        return KXH1 + Base64.getUrlEncoder().withoutPadding().encodeToString(b.array());
    }

    private static boolean applyKxh1(Crosshair c, String code) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(code.substring(KXH1.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (bytes.length < 50) return false;
        ByteBuffer b = ByteBuffer.wrap(bytes);
        int style = b.get() & 0xFF;
        int mode = b.get() & 0xFF;
        int f = b.getInt();
        float[] v = new float[12];
        for (int i = 0; i < v.length; i++) v[i] = b.getShort() / 20f;
        int[] colours = new int[5];
        for (int i = 0; i < colours.length; i++) colours[i] = b.getInt();

        Crosshair.Style[] styles = Crosshair.Style.values();
        c.style = style < styles.length ? styles[style] : Crosshair.Style.CROSS;
        c.sizeMode = mode < Crosshair.SizeMode.values().length ? Crosshair.SizeMode.values()[mode] : Crosshair.SizeMode.GUI;
        c.dot = (f & F_DOT) != 0;
        c.armTop = (f & F_TOP) != 0;
        c.armBottom = (f & F_BOTTOM) != 0;
        c.armLeft = (f & F_LEFT) != 0;
        c.armRight = (f & F_RIGHT) != 0;
        c.separateDotColour = (f & F_SEP_DOT) != 0;
        c.invert = (f & F_INVERT) != 0;
        c.outline = (f & F_OUTLINE) != 0;
        c.spreadMoving = (f & F_MOVE) != 0;
        c.spreadSprinting = (f & F_SPRINT) != 0;
        c.spreadJumping = (f & F_JUMP) != 0;
        c.recoil = (f & F_RECOIL) != 0;
        c.colourOnEntity = (f & F_ENTITY) != 0;
        c.colourOnBlock = (f & F_BLOCK) != 0;
        c.attackIndicator = (f & F_ATTACK) != 0;
        c.thirdPerson = (f & F_THIRD) != 0;
        c.length = clamp(v[0], 0, 40);
        c.thickness = clamp(v[1], 0.5f, 10);
        c.gap = clamp(v[2], -10, 20);
        c.dotSize = clamp(v[3], 0.5f, 10);
        c.circleRadius = clamp(v[4], 1, 50);
        c.circleThickness = clamp(v[5], 0.5f, 10);
        c.rotation = clamp(v[6], 0, 359);
        c.scale = clamp(v[7], 0.25f, 4);
        c.outlineThickness = clamp(v[8], 0.5f, 5);
        c.spreadAmount = clamp(v[10], 0, 20);
        c.recoilAmount = clamp(v[11], 0, 20);
        c.colour = colour(colours[0], (f & F_CHROMA) != 0);
        c.outlineColour = colour(colours[1], false);
        c.dotColour = colour(colours[2], false);
        c.entityColour = colour(colours[3], false);
        c.blockColour = colour(colours[4], false);
        return true;
    }

    // -------------------------------------------------------------------------------------------- Valorant

    /** Valorant's preset colours, by the "c" value (8 is custom, from "u"). */
    private static final int[] VALORANT_COLOURS = {0xFFFFFF, 0x00FF00, 0x7FFF00, 0xDFFF00, 0xFFFF00, 0x00FFFF, 0xFF00FF, 0xFF0000};

    /**
     * "0;P;c;5;h;0;0l;4;0o;2;0a;1;0f;0;1b;0": key/value pairs, with P (primary), A (aim down sights) and S (sniper)
     * starting each section. The primary crosshair is used, falling back to its defaults for anything left out.
     */
    private static boolean applyValorant(Crosshair c, String code) {
        String[] parts = code.split(";");
        Map<String, String> primary = new HashMap<>();
        String section = "P";
        int i = 1; // parts[0] is the code's version
        while (i < parts.length) {
            String key = parts[i];
            if (key.equals("P") || key.equals("A") || key.equals("S")) {
                section = key;
                i++;
                continue;
            }
            if (i + 1 >= parts.length) break;
            if (section.equals("P")) primary.put(key, parts[i + 1]);
            i += 2;
        }
        if (primary.isEmpty() && !code.contains(";P;")) return false;
        try {
            int colourIndex = (int) num(primary, "c", 0);
            int rgb = colourIndex >= 0 && colourIndex < VALORANT_COLOURS.length ? VALORANT_COLOURS[colourIndex] : 0xFFFFFF;
            int alpha = 255;
            if (colourIndex == 8 && primary.containsKey("u")) {
                long rgba = Long.parseLong(primary.get("u"), 16);
                rgb = (int) (rgba >>> 8) & 0xFFFFFF;
                alpha = (int) (rgba & 0xFF);
            }
            boolean innerShown = num(primary, "0b", 1) != 0;
            boolean outerShown = num(primary, "1b", 1) != 0;
            // One set of lines here: the inner lines, or the outer ones if only those show.
            String lines = innerShown || !outerShown ? "0" : "1";
            boolean shown = innerShown || outerShown;
            float opacity = num(primary, lines + "a", lines.equals("0") ? 0.8f : 0.35f);
            c.style = Crosshair.Style.CROSS;
            c.sizeMode = Crosshair.SizeMode.PIXELS;
            c.scale = 1f;
            c.rotation = 0f;
            c.length = shown ? clamp(num(primary, lines + "l", lines.equals("0") ? 6 : 2), 0, 40) : 0;
            c.thickness = clamp(num(primary, lines + "t", 2), 0.5f, 10);
            c.gap = clamp(num(primary, lines + "o", lines.equals("0") ? 3 : 10), -10, 20);
            c.armTop = c.armBottom = c.armLeft = c.armRight = true;
            c.colour = colour(((int) (opacity * alpha) & 0xFF) << 24 | rgb, false);
            boolean dot = num(primary, "d", 0) != 0;
            c.dot = dot;
            c.dotSize = clamp(num(primary, "z", 2), 0.5f, 10);
            c.separateDotColour = dot;
            c.dotColour = colour(((int) (num(primary, "a", 1) * alpha) & 0xFF) << 24 | rgb, false);
            c.outline = num(primary, "h", 1) != 0;
            c.outlineThickness = clamp(num(primary, "t", 1), 0.5f, 5);
            c.outlineColour = colour(((int) (num(primary, "o", 0.5f) * 255) & 0xFF) << 24, false);
            // Movement error opens it while moving; firing error (on by default) is closest to the swing kick.
            c.spreadMoving = num(primary, lines + "m", lines.equals("0") ? 0 : 1) != 0;
            c.recoil = num(primary, lines + "f", 1) != 0;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static float num(Map<String, String> map, String key, float fallback) {
        String value = map.get(key);
        return value == null ? fallback : Float.parseFloat(value);
    }

    // -------------------------------------------------------------------------------------------- CS2

    private static final String CS_DICTIONARY = "ABCDEFGHJKLMNOPQRSTUVWXYZabcdefhijkmnopqrstuvwxyz23456789";
    /** CS2's preset colours by cl_crosshaircolor (5 is custom, from the code's RGB). */
    private static final int[] CS_COLOURS = {0xFA3232, 0x32FA32, 0xFAFA32, 0x3232FA, 0x32FAFA};

    /**
     * "CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx": 18 bytes as a base-57 number, read from the last character. Byte 0 is a
     * checksum of the rest, which is checked, so a code from something else is refused instead of applied wrongly.
     */
    private static boolean applyCs2(Crosshair c, String code) {
        String chars = code.substring(5).replace("-", "");
        if (chars.length() != 25) return false;
        BigInteger big = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(CS_DICTIONARY.length());
        for (int i = chars.length() - 1; i >= 0; i--) {
            int digit = CS_DICTIONARY.indexOf(chars.charAt(i));
            if (digit < 0) return false;
            big = big.multiply(base).add(BigInteger.valueOf(digit));
        }
        byte[] raw = big.toByteArray();
        byte[] bytes = new byte[18];
        // toByteArray may have a sign byte in front or be shorter: right-align into 18 bytes.
        int copy = Math.min(raw.length, 18);
        System.arraycopy(raw, raw.length - copy, bytes, 18 - copy, copy);
        int sum = 0;
        for (int i = 1; i < 18; i++) sum += bytes[i] & 0xFF;
        if ((sum & 0xFF) != (bytes[0] & 0xFF)) return false;

        float gap = bytes[2] / 10f; // signed
        float outline = (bytes[3] & 0xFF) / 2f;
        int r = bytes[4] & 0xFF, g = bytes[5] & 0xFF, b = bytes[6] & 0xFF, a = bytes[7] & 0xFF;
        int colourIndex = bytes[10] & 7;
        boolean outlineOn = (bytes[10] & 8) != 0;
        float thickness = (bytes[12] & 0xFF) / 10f;
        int flags = (bytes[13] & 0xFF) >> 4;
        boolean dot = (flags & 1) != 0;
        boolean alphaOn = (flags & 4) != 0;
        boolean tStyle = (flags & 8) != 0;
        int style = (bytes[13] & 0xF) >> 1;
        float size = ((bytes[15] & 0x1F) << 8 | (bytes[14] & 0xFF)) / 10f;

        int rgb = colourIndex < CS_COLOURS.length ? CS_COLOURS[colourIndex] : (r << 16 | g << 8 | b);
        int alpha = alphaOn ? a : 255;
        // CS2's units are about two screen pixels at 1080p, and its gap is measured from a few pixels out.
        c.style = tStyle ? Crosshair.Style.T_SHAPE : Crosshair.Style.CROSS;
        c.sizeMode = Crosshair.SizeMode.PIXELS;
        c.scale = 1f;
        c.rotation = 0f;
        c.length = clamp(size * 2f, 0, 40);
        c.thickness = clamp(Math.max(1f, thickness * 2f), 0.5f, 10);
        c.gap = clamp(gap + 4f, -10, 20);
        c.armTop = c.armBottom = c.armLeft = c.armRight = true;
        c.colour = colour(alpha << 24 | rgb, false);
        c.dot = dot;
        c.separateDotColour = false;
        c.dotSize = c.thickness;
        c.outline = outlineOn;
        c.outlineThickness = clamp(Math.max(0.5f, outline), 0.5f, 5);
        c.outlineColour = colour(0xFF000000, false);
        // Styles 2 and 3 are CS2's dynamic crosshairs: they open up when you move.
        c.spreadMoving = style == 2 || style == 3;
        c.spreadSprinting = false;
        c.spreadJumping = false;
        return true;
    }

    // -------------------------------------------------------------------------------------------- colours

    public static int argb(String colour) {
        return ChromaColours.parse(colour).getEffectiveColourWithTimeOffsetRGB(0);
    }

    private static int chromaSpeed(String colour) {
        String[] parts = colour == null ? new String[0] : colour.split(":");
        try {
            return parts.length == 5 ? Integer.parseInt(parts[0].trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A MoulConfig colour string ("speed:alpha:red:green:blue"). */
    public static String colour(int argb, boolean chroma) {
        return String.format(Locale.ROOT, "%d:%d:%d:%d:%d", chroma ? 200 : 0, argb >>> 24, (argb >> 16) & 0xFF,
            (argb >> 8) & 0xFF, argb & 0xFF);
    }

    private static float clamp(float v, float min, float max) {
        return !Float.isFinite(v) ? min : Math.max(min, Math.min(max, v));
    }
}
