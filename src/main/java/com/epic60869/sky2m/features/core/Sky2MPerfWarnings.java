package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Performance Warnings: tells you in chat when one HUD or in-world renderer averages over 1 ms a frame for three of
 * {@link Sky2MPerf}'s 5-second windows in a row. It reads the numbers the always-on profiler already keeps when a
 * window closes, so the check is a short loop every 5 seconds and adds no timing of its own. Three windows, so a
 * single hitch (loading a world, opening a big menu) doesn't warn. Once per feature per session.
 */
public final class Sky2MPerfWarnings {
    private static final double LIMIT_MS = 1.0;
    private static final int WINDOWS_IN_A_ROW = 3;

    /** "KIND|name" -> windows in a row over the limit. */
    private static final Map<String, Integer> STREAKS = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();

    private Sky2MPerfWarnings() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("perf")
                    .then(ClientCommands.literal("mute").then(ClientCommands.argument("feature", StringArgumentType.greedyString())
                        .executes(c -> mute(StringArgumentType.getString(c, "feature")))))));
            }
        });
    }

    /** Called by {@link Sky2MPerf} on the render thread each time a window closes. */
    static void onWindow(List<Sky2MPerf.Stat> stats, long frames) {
        Minecraft mc = Minecraft.getInstance();
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.general.performanceWarnings || mc.player == null || frames <= 0) {
            STREAKS.clear();
            return;
        }
        Set<String> over = new HashSet<>();
        for (Sky2MPerf.Stat s : stats) {
            if (s.kind() == Sky2MPerf.Kind.CHAT) continue;
            double perFrameMs = s.nanos() / 1e6 / frames;
            if (perFrameMs <= LIMIT_MS) continue;
            String key = s.kind() + "|" + s.name();
            over.add(key);
            int streak = STREAKS.merge(key, 1, Integer::sum);
            if (streak >= WINDOWS_IN_A_ROW && !WARNED.contains(key) && !config.general.perfWarningMuted.contains(s.name())) {
                WARNED.add(key);
                warn(s, perFrameMs, frames);
            }
        }
        STREAKS.keySet().retainAll(over);
    }

    private static void warn(Sky2MPerf.Stat s, double perFrameMs, long frames) {
        double fps = frames / Math.max(0.1, Sky2MPerf.seconds());
        double share = 100 * perFrameMs * fps / 1000;
        String kind = s.kind() == Sky2MPerf.Kind.HUD ? "HUD" : "in-world renderer";
        MutableComponent message = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("Performance: ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(s.name()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(String.format(Locale.US, " (%s) takes %.2f ms a frame, %.1f%% of each frame at %.0f FPS. ",
                kind, perFrameMs, share, fps)).withStyle(ChatFormatting.YELLOW));
        // A HUD's name finds its settings (like clicking it in /s2 perf); a renderer's is a class name, so open /s2 perf.
        if (s.kind() == Sky2MPerf.Kind.HUD) {
            message.append(button("[Open settings]", ChatFormatting.GREEN, "s2 settings " + s.name(), "Find this HUD's settings"));
        } else {
            message.append(button("[Open /s2 perf]", ChatFormatting.GREEN, "s2 perf", "See what each Sky2M feature costs"));
        }
        message.append(Component.literal(" "))
            .append(button("[Don't warn again]", ChatFormatting.GRAY, "s2 perf mute " + s.name(), "Never warn about " + s.name() + " again"));
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
    }

    private static int mute(String feature) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null) return 0;
        String name = feature.trim();
        if (!config.general.perfWarningMuted.contains(name)) config.general.perfWarningMuted.add(name);
        Sky2MConfig.saveCurrent(config);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("You won't be warned about " + name + " again.").withStyle(ChatFormatting.GRAY)));
        });
        return 1;
    }

    private static MutableComponent button(String text, ChatFormatting colour, String command, String hover) {
        return Component.literal(text).withStyle(style -> style.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
