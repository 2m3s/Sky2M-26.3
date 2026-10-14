package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ability Timers, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, abilitytimers/): up to
 * five countdowns you name and time yourself, each started by its own key the moment you use the ability, shown in a
 * HUD. Nothing is detected for you (the timers are a stopwatch you start), so they're right for any ability. Keys only
 * count while no menu or chat is open, so typing a bound letter doesn't start a timer.
 */
public final class AbilityTimers {
    /** When each timer runs out (0: not started), and whether its key was down last tick. */
    private static final Map<FeatureConfigs.AbilityTimer, Long> ENDS = new IdentityHashMap<>();
    private static final Map<FeatureConfigs.AbilityTimer, Boolean> WAS_DOWN = new IdentityHashMap<>();
    /** Timers whose end has been announced. */
    private static final java.util.Set<FeatureConfigs.AbilityTimer> ANNOUNCED = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private AbilityTimers() {}

    private static FeatureConfigs.AbilityTimers config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.abilityTimers;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Sky2MHuds.register("ability_timers", "Ability Timers", () -> config() != null && config().enabled, AbilityTimers::lines,
            List.of(line(0xFFFFAA00, "Mask", "42.3s", ChatFormatting.WHITE), line(0xFF55FFFF, "Ragnarock Axe", "READY", ChatFormatting.GREEN)), 10, 200);
    }

    private static void tick() {
        FeatureConfigs.AbilityTimers c = config();
        if (c == null || !c.enabled) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        for (FeatureConfigs.AbilityTimer t : c.timers()) {
            Long end = ENDS.get(t);
            if (end != null && end > 0 && now >= end && ANNOUNCED.add(t) && c.readySound) {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 2f));
            }
            if (!t.enabled || t.key == -1) continue;
            // Keys only count with no menu or chat open, and the edge is remembered either way so closing one isn't a press.
            boolean down = mc.gui.screen() == null && t.key > 0 && InputConstants.isKeyDown(t.key);
            if (down && !WAS_DOWN.getOrDefault(t, false)) {
                ENDS.put(t, now + t.seconds * 1000L);
                ANNOUNCED.remove(t);
            }
            WAS_DOWN.put(t, down);
        }
    }

    private static List<Component> lines() {
        FeatureConfigs.AbilityTimers c = config();
        List<Component> out = new ArrayList<>();
        if (c == null || !c.enabled) return out;
        long now = System.currentTimeMillis();
        for (FeatureConfigs.AbilityTimer t : c.timers()) {
            if (!t.enabled) continue;
            long end = ENDS.getOrDefault(t, 0L);
            int colour = ChromaColours.parse(t.colour).getEffectiveColourRGB();
            if (end > now) {
                out.add(line(colour, t.name, String.format(Locale.ROOT, "%.1fs", (end - now) / 1000.0), ChatFormatting.WHITE));
            } else if (c.showReady && end > 0) {
                out.add(line(colour, t.name, "READY", ChatFormatting.GREEN));
            }
        }
        return out;
    }

    /** "■ Name: 12.3s", the square in the timer's colour. */
    private static Component line(int colour, String name, String value, ChatFormatting valueColour) {
        MutableComponent text = Component.literal("■ ").withStyle(s -> s.withColor(colour & 0xFFFFFF));
        return text.append(Component.literal(name + ": ").withStyle(ChatFormatting.GRAY)).append(Component.literal(value).withStyle(valueColour));
    }
}
