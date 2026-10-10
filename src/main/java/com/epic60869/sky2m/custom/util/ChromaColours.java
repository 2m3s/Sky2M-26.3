package com.epic60869.sky2m.custom.util;

import io.github.notenoughupdates.moulconfig.ChromaColour;

/**
 * Reads MoulConfig's colour strings ("speed:alpha:red:green:blue", as saved by @ConfigEditorColour on a String) without
 * ChromaColour.forLegacyString, which MoulConfig deprecated. Same result: a chroma speed above 0 makes the colour cycle.
 */
public final class ChromaColours {
    private static final int MIN_CHROMA_SECS = 1;
    private static final int MAX_CHROMA_SECS = 60;

    private ChromaColours() {}

    public static ChromaColour parse(String value) {
        int[] parts = new int[5];
        String[] split = value == null ? new String[0] : value.split(":");
        // Missing leading parts (e.g. "r:g:b") count as 0, like MoulConfig.
        for (int i = 0; i < split.length && i < 5; i++) {
            try {
                parts[i] = Integer.parseInt(split[split.length - 1 - i].trim());
            } catch (NumberFormatException ignored) {}
        }
        int b = parts[0], g = parts[1], r = parts[2], a = parts[3], speed = parts[4];
        int millis = speed > 0 ? (int) (((255 - speed) / 254f * (MAX_CHROMA_SECS - MIN_CHROMA_SECS) + MIN_CHROMA_SECS) * 1000) : 0;
        return ChromaColour.fromRGB(r, g, b, millis, a);
    }
}
