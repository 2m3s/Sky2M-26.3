package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combat > Armor Stack Display, after SkyHanni's: the stacks of Crimson (Dominus ᝐ), Terror (Hydra Strike ⁑),
 * Aurora (Arcane Vision ҉) and Fervor (Ѫ) armor that Hypixel shows in the action bar, in a HUD with the time until the
 * next stack decays. A stack is lost after 4 seconds without gaining one, so the timer restarts whenever the count
 * changes.
 */
public final class ArmorStacks {
    private static final Pattern STACKS = Pattern.compile("(\\d+)([ᝐ⁑҉Ѫ])");
    private static final long DECAY_MS = 4_000;
    /** The action bar is resent about every second; after this long without the counter, the stacks are gone. */
    private static final long GONE_MS = 3_000;

    private static int stacks;
    private static char symbol;
    private static long changedAt;
    private static long seenAt;

    private ArmorStacks() {}

    private static FeatureConfigs.Combat config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat;
    }

    public static void init() {
        Sky2MChat.onActionBar(message -> onActionBar(message.text()));
        Sky2MHuds.setting("armor_stacks", () -> config() != null && config().armorStackDisplay);
        Sky2MHuds.register("armor_stacks", "Armor Stack Display",
            () -> config() != null && config().armorStackDisplay && Sky2MLocation.onSkyblock() && active(),
            ArmorStacks::lines,
            List.of(line(7, 'ᝐ', 2.4)),
            8, 148);
    }

    static void onActionBar(String text) {
        Matcher m = STACKS.matcher(text);
        if (!m.find()) return;
        int now = Integer.parseInt(m.group(1));
        long time = System.currentTimeMillis();
        if (now != stacks || m.group(2).charAt(0) != symbol) changedAt = time;
        stacks = now;
        symbol = m.group(2).charAt(0);
        seenAt = time;
    }

    private static boolean active() {
        return stacks > 0 && System.currentTimeMillis() - seenAt < GONE_MS;
    }

    private static List<Component> lines() {
        double left = Math.max(0, DECAY_MS - (System.currentTimeMillis() - changedAt)) / 1000.0;
        return List.of(line(stacks, symbol, left));
    }

    private static Component line(int count, char sym, double decaySeconds) {
        return Component.literal(name(sym) + ": ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(count + String.valueOf(sym)).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(String.format(Locale.ROOT, " (%.1fs)", decaySeconds))
                .withStyle(decaySeconds < 1 ? ChatFormatting.RED : ChatFormatting.YELLOW));
    }

    private static String name(char sym) {
        return switch (sym) {
            case '⁑' -> "Hydra Strike";
            case '҉' -> "Arcane Vision";
            case 'Ѫ' -> "Fervor";
            default -> "Dominus";
        };
    }
}
