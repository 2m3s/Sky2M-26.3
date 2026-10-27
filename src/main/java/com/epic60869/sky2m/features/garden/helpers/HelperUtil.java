package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.config.Sky2MConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small helpers shared by the Garden helpers. */
final class HelperUtil {
    private HelperUtil() {}

    static HelperConfig cfg() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.farming == null ? null : c.farming.gardenHelpers;
    }

    /** The lore lines of an item with the colour codes stripped. */
    static List<String> lore(ItemStack stack) {
        ItemLore lore = stack == null ? null : stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Component line : lore.lines()) out.add(strip(line.getString()));
        return out;
    }

    static String name(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : strip(stack.getHoverName().getString());
    }

    static String strip(String text) {
        String s = ChatFormatting.stripFormatting(text);
        return s == null ? "" : s.trim();
    }

    static String title(Screen screen) {
        return screen == null ? "" : strip(screen.getTitle().getString());
    }

    /** 1234 -> "1.2k", 5_600_000 -> "5.6m". */
    static String shortNumber(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000) return String.format(Locale.US, "%.2fb", value / 1_000_000_000);
        if (abs >= 1_000_000) return String.format(Locale.US, "%.2fm", value / 1_000_000);
        if (abs >= 10_000) return String.format(Locale.US, "%.1fk", value / 1_000);
        if (abs >= 1_000) return String.format(Locale.US, "%.2fk", value / 1_000);
        return abs >= 100 ? String.format(Locale.US, "%.0f", value) : String.format(Locale.US, "%.1f", value);
    }

    /** "12.5k" -> 12500, "1,200" -> 1200; -1 if it isn't a number. */
    static double parseAmount(String text) {
        String s = text.replace(",", "").toLowerCase(Locale.ROOT).trim();
        double mult = 1;
        if (s.endsWith("k")) {
            mult = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "IV" -> 4; 0 for anything else. */
    static int roman(String text) {
        int total = 0, prev = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int v = switch (text.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                default -> -1;
            };
            if (v < 0) return 0;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    /** "1h 5m", "3m 20s", "45s". */
    static String duration(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        long d = s / 86400, h = s % 86400 / 3600, m = s % 3600 / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }
}
