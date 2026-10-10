package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MPlayerTabOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Odin's Blessing Display (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/dungeon/BlessingDisplay.kt
 * and the Blessing enum in utils/skyblock/dungeon): the dungeon's blessings and their levels, read from the tab list
 * footer ("Blessing of Power XIV").
 */
public final class BlessingDisplay {
    private static final Blessing[] BLESSINGS = {
        new Blessing("Power", "Blessing of Power (X{0,3}(?:IX|IV|V?I{0,3}))", c -> c.power, c -> c.powerColour),
        new Blessing("Time", "Blessing of Time (V)", c -> c.time, c -> c.timeColour),
        new Blessing("Stone", "Blessing of Stone (X{0,3}(?:IX|IV|V?I{0,3}))", c -> c.stone, c -> c.stoneColour),
        new Blessing("Life", "Blessing of Life (X{0,3}(?:IX|IV|V?I{0,3}))", c -> c.life, c -> c.lifeColour),
        new Blessing("Wisdom", "Blessing of Wisdom (X{0,3}(?:IX|IV|V?I{0,3}))", c -> c.wisdom, c -> c.wisdomColour),
    };

    private BlessingDisplay() {}

    private static FeatureConfigs.BlessingDisplay config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.blessings;
    }

    public static void init() {
        Sky2MHuds.setting("blessings", () -> config() != null && config().enabled);
        Sky2MHuds.register("blessings", "Blessing Display",
            () -> config() != null && config().enabled && Sky2MLocation.inDungeon(),
            () -> lines(false),
            lines(true),
            20, 300);
    }

    private static List<Component> lines(boolean preview) {
        List<Component> lines = new ArrayList<>();
        FeatureConfigs.BlessingDisplay config = config();
        if (config == null) return lines;
        String footer = preview ? "" : footer();
        for (Blessing blessing : BLESSINGS) {
            if (!blessing.shown.apply(config)) continue;
            int level = preview ? 19 : blessing.level(footer);
            if (level <= 0) continue;
            int colour = com.epic60869.sky2m.custom.util.ChromaColours.parse(blessing.colour.apply(config)).getEffectiveColourRGB() & 0xFFFFFF;
            lines.add(Component.literal(blessing.name + ": ").withColor(colour).append(Component.literal("§a" + level)));
        }
        return lines;
    }

    private static String footer() {
        Component footer = ((Sky2MPlayerTabOverlayAccessor) Minecraft.getInstance().gui.hud.getTabList()).sky2m$getFooter();
        return footer == null ? "" : Sky2MLocation.strip(footer.getString());
    }

    private static int roman(String text) {
        int total = 0, previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int value = switch (text.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    private record Blessing(String name, Pattern pattern, Function<FeatureConfigs.BlessingDisplay, Boolean> shown,
                            Function<FeatureConfigs.BlessingDisplay, String> colour) {
        Blessing(String name, String regex, Function<FeatureConfigs.BlessingDisplay, Boolean> shown,
                 Function<FeatureConfigs.BlessingDisplay, String> colour) {
            this(name, Pattern.compile(regex), shown, colour);
        }

        int level(String footer) {
            Matcher m = pattern.matcher(footer);
            return m.find() ? roman(m.group(1)) : 0;
        }
    }
}
