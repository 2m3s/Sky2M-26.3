package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dungeons > Run Recap > Boss Phase Notes: a short, editable note for each boss phase, shown in chat and a HUD when
 * {@link BossPhases} moves to that phase. Notes are stored per floor (F1-F7, used for the M floors too) as
 * "Phase: note | Phase: note". A phase matches by its exact name ("Terminals 2") or by its name without the number
 * ("Terminals").
 */
final class BossNotes {
    private static String phase, note;
    private static long shownAt;

    private BossNotes() {}

    private static DungeonRecapExtras.NotesConfig config() {
        DungeonRecapExtras.Config c = DungeonRecapExtras.config();
        return c == null ? null : c.bossNotes;
    }

    static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> phase = null);
        Sky2MLocation.onAreaChange(area -> phase = null);
        Sky2MHuds.register("boss_notes", "Boss Phase Notes", () -> {
                DungeonRecapExtras.NotesConfig c = config();
                return c != null && c.enabled && c.hud && Sky2MLocation.inDungeon() && phase != null
                    && System.currentTimeMillis() - shownAt < c.hudSeconds * 1000L;
            }, BossNotes::lines,
            List.of(Component.literal("Storm").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("Break the pillars, watch for the").withStyle(ChatFormatting.WHITE),
                Component.literal("lightning (PY) and stay out of it.").withStyle(ChatFormatting.WHITE)),
            10, 320);
        Sky2MHuds.setting("boss_notes", () -> {
            DungeonRecapExtras.NotesConfig c = config();
            return c != null && c.enabled && c.hud;
        });
    }

    /** From BossPhases when the fight moves to {@code name} on {@code floor} ("F7", "M3"). */
    static void onPhase(String floor, String name) {
        DungeonRecapExtras.NotesConfig c = config();
        if (c == null || !c.enabled || floor == null || floor.length() != 2) return;
        String found = find(floor.charAt(1), name);
        if (found == null) return;
        phase = name;
        note = found;
        shownAt = System.currentTimeMillis();
        if (c.chatTips) {
            Sky2MAlerts.chat(Component.literal(name + ": ").withStyle(ChatFormatting.GOLD).append(Component.literal(found).withStyle(ChatFormatting.GRAY)));
        }
    }

    private static String find(char digit, String phaseName) {
        DungeonRecapExtras.NotesConfig c = config();
        String text = switch (digit) {
            case '1' -> c.f1;
            case '2' -> c.f2;
            case '3' -> c.f3;
            case '4' -> c.f4;
            case '5' -> c.f5;
            case '6' -> c.f6;
            case '7' -> c.f7;
            default -> null;
        };
        if (text == null) return null;
        String exact = phaseName.toLowerCase(Locale.ROOT);
        String base = exact.replaceFirst("\\s+\\d+$", "");
        String fallback = null;
        for (String part : text.split("\\|")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String key = part.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(colon + 1).trim();
            if (value.isEmpty()) continue;
            if (key.equals(exact)) return value;
            if (key.equals(base)) fallback = value;
        }
        return fallback;
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        if (phase == null || note == null) return out;
        out.add(Component.literal(phase).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        StringBuilder line = new StringBuilder();
        for (String word : note.split(" ")) {
            if (line.length() + word.length() + 1 > 42 && !line.isEmpty()) {
                out.add(Component.literal(line.toString()).withStyle(ChatFormatting.WHITE));
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(Component.literal(line.toString()).withStyle(ChatFormatting.WHITE));
        return out;
    }
}
