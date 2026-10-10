package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.SkyBlockColors;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.ARGB;

/** Ported from Skyblocker's SkyblockItemRarity (LGPL-3.0). */
public enum Sky2MItemRarity {
    COMMON(TextColor.WHITE),
    UNCOMMON(TextColor.GREEN),
    RARE(SkyBlockColors.BLUE, TextColor.BLUE),
    EPIC(SkyBlockColors.DARK_PURPLE, TextColor.DARK_PURPLE),
    LEGENDARY(SkyBlockColors.GOLD, TextColor.GOLD),
    MYTHIC(TextColor.LIGHT_PURPLE),
    DIVINE(TextColor.AQUA),
    SPECIAL(TextColor.RED),
    VERY_SPECIAL(TextColor.RED),
    ULTIMATE(SkyBlockColors.DARK_RED, TextColor.DARK_RED),
    ADMIN(SkyBlockColors.DARK_RED, TextColor.DARK_RED),
    UNKNOWN(TextColor.DARK_GRAY);

    public final String name;
    public final int color;
    public final int legacyColor;

    Sky2MItemRarity(TextColor color, TextColor legacyColor) {
        this.name = this.name().replace("_", " ");
        this.color = color.getValue();
        this.legacyColor = legacyColor.getValue();
    }

    Sky2MItemRarity(TextColor color) {
        this(color, color);
    }

    public Sky2MItemRarity next() {
        Sky2MItemRarity[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    @Override
    public String toString() {
        return name;
    }

    public static Optional<Sky2MItemRarity> containsName(String name) {
        // Find last because "UNCOMMON" contains "COMMON" and "VERY SPECIAL" contains "SPECIAL"
        return Arrays.stream(values())
            .filter(rarity -> name.contains(rarity.toString()))
            .reduce((first, second) -> second);
    }

    public static Sky2MItemRarity fromColor(int color) {
        return Arrays.stream(values())
            .filter(rarity -> ARGB.opaque(rarity.color) == ARGB.opaque(color))
            .findFirst()
            .orElse(UNKNOWN);
    }
}
