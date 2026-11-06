package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /s2 bestfloor: your chest profit per hour on each floor, best first, from your own Chest History and Run History
 * ({@link DungeonProfitHour}'s numbers). An hour is your recent run time plus your usual gap between runs. The RNG meter
 * isn't counted, as it isn't money until it drops; rare drops you've had are in the chest profit already.
 */
public final class BestFloor {
    /** How many of your newest runs on a floor make its run time. */
    private static final int RECENT_RUNS = 20;

    /** One floor. {@code runSeconds} and {@code perHour} are -1 with no finished run to time. */
    public record Row(String floor, int runs, double chestPerRun, double runSeconds, double perHour) {}

    private BestFloor() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("bestfloor").executes(c -> {
                    show();
                    return 1;
                })));
            }
        });
    }

    /** Every floor you have chests on, best profit per hour first, untimed floors last. Public for the game test. */
    public static List<Row> rank(JsonArray chests, JsonArray runs) {
        Set<String> floors = new LinkedHashSet<>();
        for (JsonElement e : chests) {
            JsonElement floor = e.getAsJsonObject().get("floor");
            if (floor == null || !floor.isJsonPrimitive()) continue;
            String name = floor.getAsString().toUpperCase(Locale.ROOT);
            if (!name.isEmpty() && !name.equals("?")) floors.add(name);
        }
        List<Row> out = new ArrayList<>();
        for (String floor : floors) {
            DungeonProfitHour.History history = DungeonProfitHour.history(floor, chests, runs);
            double run = DungeonProfitHour.recentRunSeconds(floor, runs, RECENT_RUNS);
            double perHour = run <= 0 ? -1 : DungeonProfitHour.estimate(run, Math.max(0, history.gapSeconds()), history.chestPerRun(), 0, 0, 0, 0).withoutMeter();
            out.add(new Row(floor, history.runs(), history.chestPerRun(), run, perHour));
        }
        out.sort(Comparator.comparing((Row r) -> r.runSeconds() <= 0).thenComparing(Comparator.comparingDouble(Row::perHour).reversed()));
        return out;
    }

    private static void show() {
        List<Row> rows = rank(DungeonProfitHour.read(DungeonProfitHour.chestFile()), DungeonProfitHour.runHistory());
        if (rows.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No chests saved yet, so there's nothing to compare. Chest Spending History and Run History fill this in.").withStyle(ChatFormatting.GRAY));
            return;
        }
        MutableComponent out = Component.literal("Best Floors").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(" (your chest profit per hour)").withStyle(ChatFormatting.GRAY));
        int place = 1;
        for (Row row : rows) {
            MutableComponent line = Component.literal("\n " + place++ + ". ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(row.floor() + ": ").withStyle(ChatFormatting.WHITE));
            if (row.runSeconds() <= 0) {
                line.append(Component.literal("no finished runs to time").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                line.append(Component.literal(signed(row.perHour()) + "/h").withStyle(row.perHour() >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            line.append(Component.literal(" (" + row.runs() + (row.runs() == 1 ? " run" : " runs") + ")").withStyle(ChatFormatting.DARK_GRAY));
            String detail = signed(row.chestPerRun()) + " a run in chests after their costs"
                + (row.runSeconds() > 0 ? "\n" + DungeonProfitHour.time(row.runSeconds()) + " a run, your last " + RECENT_RUNS + " at most" : "");
            line.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal(detail).withStyle(ChatFormatting.GRAY))));
            out.append(line);
        }
        out.append(Component.literal("\nFew runs make a floor swing a lot. No RNG meter in this: see /s2 hour.").withStyle(ChatFormatting.DARK_GRAY));
        Sky2MAlerts.chat(out);
    }

    private static String signed(double value) {
        return (value < 0 ? "-" : "+") + DungeonProfitHour.coins(Math.abs(value));
    }
}
