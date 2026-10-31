package com.epic60869.sky2m.features.combat.kuudra;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Kuudra phase splits: how long Supplies, Build, Fuel &amp; Stun and the Boss phase took, shown in a HUD and in chat, with
 * the best time ever for each phase and for the whole run (per tier) saved in sky2m-kuudra.json. A run only counts when
 * every phase was seen and Kuudra died.
 */
final class KuudraSplits {
    private static final String[] NAMES = {"Supplies", "Build", "Fuel & Stun", "Boss"};
    private static final Gson GSON = new Gson();

    /** Start time of each phase 1-4 and of the end (index 4), 0 when it didn't happen. */
    private static final long[] marks = new long[5];
    private static int markedTier;
    private static Path file;
    /** Tier ("T5") -> best times (index 0-3 phases, 4 total) in ms, 0 = none. */
    private static final java.util.Map<String, long[]> best = new java.util.HashMap<>();

    private KuudraSplits() {}

    private static KuudraConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.crimsonIsle.kuudra;
    }

    static void init(Path configDir) {
        file = configDir.resolve("sky2m-kuudra.json");
        load();
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
        KuudraState.onPhase(KuudraSplits::onPhase);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("kuudra_splits", () -> config() != null && config().splitsHud);
        com.epic60869.sky2m.features.core.Sky2MHuds.register("kuudra_splits", "Kuudra Splits",
            () -> config() != null && config().splitsHud && KuudraState.inKuudra && marks[0] > 0,
            KuudraSplits::lines,
            List.of(sample("Supplies: ", "0:38.4", " PB 0:35.0"), sample("Build: ", "0:21.0", " PB 0:19.6"),
                sample("Fuel & Stun: ", "0:42.7", " PB 0:40.1"), sample("Boss: ", "0:15.2", " PB 0:12.0")),
            8, 150);
    }

    private static MutableComponent sample(String name, String time, String pb) {
        return Component.literal(name).withStyle(ChatFormatting.GRAY)
            .append(Component.literal(time).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(pb).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void onPhase(int phase) {
        if (phase == 1) {
            java.util.Arrays.fill(marks, 0);
            marks[0] = System.currentTimeMillis();
            markedTier = KuudraState.tier;
            return;
        }
        if (marks[0] == 0 || phase > 5) return;
        marks[phase - 1] = System.currentTimeMillis();
        KuudraConfig c = config();
        if (phase <= 4 && c != null && c.splitsChat) {
            Sky2MAlerts.chat(Component.literal(NAMES[phase - 2 < 0 ? 0 : phase - 2] + " took ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(format(marks[phase - 1] - marks[phase - 2])).withStyle(ChatFormatting.AQUA)));
        }
        if (phase == 5) finish(c);
    }

    private static void finish(KuudraConfig c) {
        for (int i = 0; i < 5; i++) if (marks[i] == 0) return;
        long[] times = new long[5];
        for (int i = 0; i < 4; i++) times[i] = marks[i + 1] - marks[i];
        times[4] = marks[4] - marks[0];
        String key = markedTier > 0 ? "T" + markedTier : "T?";
        long[] pbs = best.computeIfAbsent(key, k -> new long[5]);
        List<Component> summary = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            boolean record = pbs[i] == 0 || times[i] < pbs[i];
            String name = i == 4 ? "Total" : NAMES[i];
            MutableComponent line = Component.literal(name + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(format(times[i])).withStyle(ChatFormatting.AQUA));
            if (record) line.append(Component.literal(pbs[i] == 0 ? " (first time)" : " NEW BEST!").withStyle(ChatFormatting.GOLD));
            else line.append(Component.literal(" (best " + format(pbs[i]) + ")").withStyle(ChatFormatting.DARK_GRAY));
            summary.add(line);
            if (record) pbs[i] = times[i];
        }
        save();
        if (c != null && c.splitsChat) {
            Sky2MAlerts.chat(Component.literal("Kuudra " + key + " run").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            for (Component line : summary) Sky2MAlerts.chat(line);
        }
    }

    private static List<Component> lines() {
        long now = System.currentTimeMillis();
        long[] pbs = best.get(markedTier > 0 ? "T" + markedTier : "T?");
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (marks[i] == 0) break;
            boolean running = marks[i + 1] == 0;
            long time = (running ? now : marks[i + 1]) - marks[i];
            MutableComponent line = Component.literal(NAMES[i] + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(format(time)).withStyle(running ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
            if (pbs != null && pbs[i] > 0) line.append(Component.literal("  PB " + format(pbs[i])).withStyle(ChatFormatting.DARK_GRAY));
            out.add(line);
        }
        return out;
    }

    static String format(long ms) {
        long tenths = ms / 100;
        return String.format(Locale.US, "%d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10);
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null || !root.has("best")) return;
            for (var entry : root.getAsJsonObject("best").entrySet()) {
                long[] times = new long[5];
                var array = entry.getValue().getAsJsonArray();
                for (int i = 0; i < 5 && i < array.size(); i++) times[i] = array.get(i).getAsLong();
                best.put(entry.getKey(), times);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load Kuudra splits: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject bests = new JsonObject();
            for (var entry : best.entrySet()) {
                var array = new com.google.gson.JsonArray();
                for (long time : entry.getValue()) array.add(time);
                bests.add(entry.getKey(), array);
            }
            root.add("best", bests);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save Kuudra splits: " + e.getMessage());
        }
    }
}
