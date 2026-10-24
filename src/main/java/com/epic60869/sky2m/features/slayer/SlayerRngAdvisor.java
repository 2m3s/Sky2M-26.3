package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RNG meter advisor: of the slayer RNG meters Sky2M has read (the drop selected in each slayer's RNG Meter menu and the
 * "Stored XP" line after each boss, see {@link SlayerRngMeterDisplay}), shows the one closest to its drop. Nothing new
 * is read: a meter that was never opened in Maddox's menu or never had a boss killed is not listed. Dungeon RNG meters
 * are not covered because the mod does not store them.
 */
public final class SlayerRngAdvisor {
    private SlayerRngAdvisor() {}

    public static final class Config {
        @Expose
        @ConfigOption(name = "Enabled", desc = "A HUD naming the slayer RNG meter closest to dropping its selected item, out of the meters Sky2M has read (open each slayer's RNG Meter menu in Maddox once, then kill a boss). /s2 rngadvice lists them all. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;
    }

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.rngAdvisor;
    }

    public static void init() {
        Sky2MHuds.register("slayer_rng_advisor", "RNG Meter Advisor", () -> {
                Config c = config();
                return c != null && c.enabled && Sky2MLocation.onSkyblock();
            }, SlayerRngAdvisor::hudLines,
            List.of(Component.literal("Closest RNG meter: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("Scythe Blade").withStyle(ChatFormatting.LIGHT_PURPLE)),
                Component.literal("Revenant Horror, 63.2% (~491 bosses left)").withStyle(ChatFormatting.YELLOW)), 8, 300);
        Sky2MHuds.setting("slayer_rng_advisor", () -> config() != null && config().enabled);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("rngadvice").executes(c -> {
                    explain();
                    return 1;
                })));
            }
        });
    }

    /** Meters with a selected drop and a known needed amount and progress, closest to done first. */
    private static List<SlayerRngMeterDisplay.MeterInfo> ranked() {
        List<SlayerRngMeterDisplay.MeterInfo> out = new ArrayList<>();
        for (SlayerRngMeterDisplay.MeterInfo m : SlayerRngMeterDisplay.meters()) {
            if (!m.item().isEmpty() && m.required() > 0 && m.current() >= 0) out.add(m);
        }
        out.sort((a, b) -> Double.compare(share(b), share(a)));
        return out;
    }

    private static double share(SlayerRngMeterDisplay.MeterInfo m) {
        return Math.min(1, m.current() / (double) m.required());
    }

    private static String detail(SlayerRngMeterDisplay.MeterInfo m) {
        String text = String.format(Locale.US, "%s, %.1f%%", m.slayer(), share(m) * 100);
        if (m.gainPerBoss() > 0) {
            long missing = Math.max(0, m.required() - m.current());
            text += String.format(Locale.US, " (~%,d bosses left)", Math.max(1, (missing + m.gainPerBoss() - 1) / m.gainPerBoss()));
        }
        return text;
    }

    private static List<Component> hudLines() {
        List<SlayerRngMeterDisplay.MeterInfo> list = ranked();
        if (list.isEmpty()) return List.of();
        SlayerRngMeterDisplay.MeterInfo best = list.getFirst();
        return List.of(Component.literal("Closest RNG meter: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(best.item()).withStyle(ChatFormatting.LIGHT_PURPLE)),
            Component.literal(detail(best)).withStyle(ChatFormatting.YELLOW));
    }

    private static void explain() {
        Sky2MAlerts.chat(Component.literal("RNG meter advisor reads the slayer RNG meters Sky2M already knows: the selected drop and its needed XP from Maddox's RNG Meter menu, and your progress from the \"Stored XP\" line after each boss. Dungeon meters are not stored.").withStyle(ChatFormatting.GRAY));
        List<SlayerRngMeterDisplay.MeterInfo> list = ranked();
        if (list.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No meter is known yet: open a slayer's RNG Meter menu in Maddox and kill a boss.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        for (SlayerRngMeterDisplay.MeterInfo m : list) {
            Sky2MAlerts.chat(Component.literal(m.item() + ": ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(detail(m)).withStyle(ChatFormatting.YELLOW)));
        }
    }
}
