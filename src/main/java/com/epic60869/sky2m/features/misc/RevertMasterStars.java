package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Map;
import java.util.Optional;

/**
 * Master Stars in item names the old way: Hypixel now writes "✪✪✪✪✪➋" (five stars and a numbered pip), where it used
 * to colour that many of the stars red. This colours the first N stars red and drops the pip. Only the name shown is
 * changed. The idea is Killer560's Mod's (revertmasterstars/, from QUOI's RevertMasterStars); written anew for Sky2M.
 */
public final class RevertMasterStars {
    private static final String STARS = "✪✪✪✪✪";
    private static final char FIRST_PIP = '➊';
    private static final char LAST_PIP = '➎';

    /** Results by the name that went in. Item names are asked for constantly, and Hypixel keeps the same component. */
    private static final Map<Component, Component> CACHE = new com.google.common.collect.MapMaker().weakKeys().makeMap();

    private RevertMasterStars() {}

    public static Component apply(Component name) {
        if (name == null) return null;
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.revertMasterStars) return name;
        Component cached = CACHE.get(name);
        if (cached != null) return cached;
        Component result = revert(name);
        CACHE.put(name, result);
        return result;
    }

    private static Component revert(Component name) {
        String text = name.getString();
        int stars = find(text);
        if (stars < 0) return name;
        int pip = stars + STARS.length();
        int redEnd = stars + (text.charAt(pip) - FIRST_PIP + 1);
        MutableComponent out = Component.empty();
        int[] offset = {0};
        name.visit((style, value) -> {
            int start = offset[0];
            offset[0] += value.length();
            StringBuilder run = new StringBuilder();
            Style runStyle = null;
            for (int i = 0; i < value.length(); i++) {
                int at = start + i;
                if (at == pip) continue;
                Style s = at >= stars && at < redEnd ? style.withColor(ChatFormatting.RED) : style;
                if (runStyle != null && !s.equals(runStyle)) {
                    out.append(Component.literal(run.toString()).withStyle(runStyle));
                    run.setLength(0);
                }
                runStyle = s;
                run.append(value.charAt(i));
            }
            if (runStyle != null && !run.isEmpty()) out.append(Component.literal(run.toString()).withStyle(runStyle));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Where "✪✪✪✪✪" followed by a master star pip starts, or -1. */
    private static int find(String text) {
        for (int from = 0; ; ) {
            int at = text.indexOf(STARS, from);
            if (at < 0) return -1;
            int pip = at + STARS.length();
            if (pip < text.length() && text.charAt(pip) >= FIRST_PIP && text.charAt(pip) <= LAST_PIP) return at;
            from = at + 1;
        }
    }
}
