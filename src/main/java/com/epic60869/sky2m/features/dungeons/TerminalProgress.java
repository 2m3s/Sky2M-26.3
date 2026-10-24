package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Floor 7 > Terminal Progress: who did each terminal, lever and device in P3, from Hypixel's
 * "Name activated a terminal! (3/7)" lines. A count lower than the last one starts the next section. The HUD shows
 * the current section from Goldor's first line until a few seconds after the core opens.
 */
public final class TerminalProgress {
    private static final Pattern DONE = Pattern.compile("^(\\w{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final long KEEP_AFTER_CORE_MS = 5_000;

    private record Done(String name, String type, int count, int total) {}

    private static final List<Done> section = new ArrayList<>();
    private static int sectionNumber;
    private static boolean active;
    private static long coreOpenedAt;

    private TerminalProgress() {}

    private static FeatureConfigs.TerminalProgress config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.terminalProgress;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("terminal_progress", "Terminal Progress", () -> config() != null && config().hud && Sky2MLocation.inDungeon(),
            TerminalProgress::lines,
            List.of(Component.literal("Section 2 ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD).append(Component.literal("3/7").withStyle(ChatFormatting.WHITE)),
                line(new Done("Steve", "terminal", 1, 7), true), line(new Done("Alex", "lever", 2, 7), true), line(new Done("Steve", "device", 3, 7), true)),
            10, 300);
        Sky2MHuds.setting("terminal_progress", () -> config() != null && config().hud);
    }

    private static void reset() {
        section.clear();
        sectionNumber = 0;
        active = false;
        coreOpenedAt = 0;
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        if (text.equals("[BOSS] Goldor: Who dares trespass into my domain?")) {
            reset();
            active = true;
            sectionNumber = 1;
            return;
        }
        if (text.equals("The Core entrance is opening!")) {
            coreOpenedAt = System.currentTimeMillis();
            return;
        }
        Matcher m = DONE.matcher(text);
        if (!m.matches()) return;
        Done done = new Done(m.group(1), m.group(3), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        active = true;
        if (sectionNumber == 0) sectionNumber = 1;
        if (!section.isEmpty() && done.count() <= section.getLast().count()) {
            section.clear();
            sectionNumber++;
        }
        section.add(done);
        FeatureConfigs.TerminalProgress c = config();
        if (c != null && c.title) {
            boolean finished = done.count() >= done.total();
            if (finished && c.phaseDone) {
                Sky2MAlerts.title(Component.empty(), Component.literal("Phase Done!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);
                return;
            }
            Sky2MAlerts.title(Component.empty(), Component.literal(done.name() + " " + done.count() + "/" + done.total())
                .withStyle(finished ? ChatFormatting.GREEN : ChatFormatting.GOLD), false);
        }
    }

    /** Hypixel's P3 subtitles, as NoammAddons' F7 Titles matches them (the name may have a rank). */
    private static final Pattern SUBTITLE = Pattern.compile("^.+ (?:activated|completed) a (?:terminal|lever|device)! \\(\\d/\\d\\)$");

    /** A subtitle in P3 (from {@link F7Extras}): true hides Hypixel's, when Sky2M shows its own title instead. */
    static boolean onSubtitle(String text) {
        FeatureConfigs.TerminalProgress c = config();
        if (c == null || !c.title) return false;
        if (c.gateTitles) {
            if (text.equals("The gate has been destroyed!")) {
                Sky2MAlerts.title(Component.empty(), Component.literal("Gate Destroyed!").withStyle(ChatFormatting.RED), false);
                return true;
            }
            if (text.equals("The gate will open in 5 seconds!")) {
                Sky2MAlerts.title(Component.empty(), Component.literal("GATE!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
                return true;
            }
        }
        return c.replaceTitles && SUBTITLE.matcher(text).matches();
    }

    private static Component line(Done d, boolean showType) {
        MutableComponent line = Component.literal(d.name()).withStyle(ChatFormatting.YELLOW);
        if (showType) line.append(Component.literal(" " + d.type()).withStyle(ChatFormatting.GRAY));
        return line.append(Component.literal(" " + d.count() + "/" + d.total()).withStyle(d.count() >= d.total() ? ChatFormatting.GREEN : ChatFormatting.WHITE));
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        FeatureConfigs.TerminalProgress c = config();
        if (c == null || !active || (coreOpenedAt > 0 && System.currentTimeMillis() - coreOpenedAt > KEEP_AFTER_CORE_MS)) return out;
        Done last = section.isEmpty() ? null : section.getLast();
        out.add(Component.literal("Section " + sectionNumber + " ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(last == null ? "0/?" : last.count() + "/" + last.total()).withStyle(ChatFormatting.WHITE)));
        for (Done d : section) out.add(line(d, c.showType));
        return out;
    }
}
