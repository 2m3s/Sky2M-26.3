package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.Sky2MConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * The HUD theme: one place that turns the colours Sky2M's HUDs ask for into the colours drawn, for high contrast and
 * the three kinds of colour blindness. {@link #argb} adapts one colour, {@link #background} the HUD background and
 * {@link #lines} a HUD's styled text; with the Default theme every one of them returns its input untouched.
 */
public final class Sky2MTheme {
    public enum HudTheme {
        DEFAULT("Default"), HIGH_CONTRAST("High contrast"), DEUTERANOPIA("Deuteranopia"),
        PROTANOPIA("Protanopia"), TRITANOPIA("Tritanopia");

        private final String label;

        HudTheme(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // Okabe-Ito colours, which stay apart under every kind of colour blindness.
    private static final int ORANGE = 0xE69F00;
    private static final int SKY_BLUE = 0x56B4E9;
    private static final int YELLOW = 0xF0E442;
    private static final int VERMILLION = 0xFF7043;
    private static final int PINK = 0xCC79A7;
    private static final int TEAL = 0x00C8A0;

    private Sky2MTheme() {}

    public static HudTheme theme() {
        Sky2MConfig config = Sky2MConfig.current();
        HudTheme theme = config == null ? null : config.gui.hudTheme;
        return theme == null ? HudTheme.DEFAULT : theme;
    }

    /** The colour to draw for {@code argb} (alpha is kept). */
    public static int argb(int argb) {
        HudTheme theme = theme();
        if (theme == HudTheme.DEFAULT) return argb;
        return (argb & 0xFF000000) | (rgb(argb & 0xFFFFFF, theme) & 0xFFFFFF);
    }

    /** The HUD background: darker and more opaque in High contrast. */
    public static int background(int argb) {
        return theme() == HudTheme.HIGH_CONTRAST ? 0xC8000000 : argb;
    }

    /** {@code lines} with their colours adapted; the same list when the theme is Default. */
    public static List<Component> lines(List<Component> lines) {
        HudTheme theme = theme();
        if (theme == HudTheme.DEFAULT) return lines;
        List<Component> out = new ArrayList<>(lines.size());
        for (Component line : lines) out.add(recolor(line, theme));
        return out;
    }

    private static Component recolor(Component line, HudTheme theme) {
        MutableComponent out = Component.empty();
        line.visit((style, text) -> {
            if (!text.isEmpty()) {
                TextColor color = style.getColor();
                out.append(Component.literal(text).withStyle(color == null ? style
                    : style.withColor(TextColor.fromRgb(rgb(color.getValue(), theme)))));
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return out;
    }

    private static int rgb(int rgb, HudTheme theme) {
        float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, null);
        float hue = hsb[0] * 360f;
        boolean coloured = hsb[1] > 0.35f && hsb[2] > 0.3f;
        switch (theme) {
            case HIGH_CONTRAST -> {
                // Lift the brightness and saturation so text reads on the darker background.
                return java.awt.Color.HSBtoRGB(hsb[0], Math.min(1f, hsb[1] * 1.15f), 1f) & 0xFFFFFF;
            }
            case DEUTERANOPIA, PROTANOPIA -> {
                if (!coloured) return rgb;
                if (hue < 20 || hue >= 340) return theme == HudTheme.PROTANOPIA ? YELLOW : ORANGE; // red
                if (hue < 70) return YELLOW;                                                     // orange, yellow
                if (hue < 170) return SKY_BLUE;                                                  // green
                return rgb;
            }
            case TRITANOPIA -> {
                if (!coloured) return rgb;
                if (hue >= 40 && hue < 70) return PINK;                                          // yellow
                if (hue >= 70 && hue < 170) return TEAL;                                         // green
                if (hue >= 190 && hue < 260) return VERMILLION;                                  // blue
                return rgb;
            }
            default -> {
                return rgb;
            }
        }
    }
}
