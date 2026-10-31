package com.epic60869.sky2m.features.mining.extras;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fossil excavation profit tracker, after SkyHanni's ExcavatorProfitTracker (hannibal002/SkyHanni, LGPL-2.1): every
 * "EXCAVATION COMPLETE" chat summary is added up, valued, and the Suspicious Scrap each excavation used is taken
 * off. Fossil Dust counts as a 500th of a scrap each, as in SkyHanni. Kept in {@code sky2m/fossil_excavation.json}.
 */
public final class ExcavatorProfit {
    private static final String START = "EXCAVATION COMPLETE";
    private static final String EMPTY = "You didn't find anything. Maybe next time!";
    private static final String SCRAP_ID = "SUSPICIOUS_SCRAP";
    private static final Gson GSON = new Gson();

    private static final class Data {
        long excavations, glacitePowder, fossilDust;
        Map<String, Long> items = new LinkedHashMap<>();
    }

    private static Data data = new Data();
    private static Path file;
    private static boolean inLoot;
    private static final List<ExtrasUtil.Loot> LOOT = new ArrayList<>();

    private ExcavatorProfit() {}

    private static MiningExtrasConfig.FossilExcavator config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.fossilExcavator;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("fossil_excavation.json");
        load();
        Sky2MChat.onChat(message -> onChat(message.text()));
        Sky2MLocation.onAreaChange(area -> inLoot = false);
        Sky2MHuds.setting("fossil_profit", () -> config() != null && config().profitTracker);
        Sky2MHuds.register("fossil_profit", "Fossil Excavation Profit",
            () -> config() != null && config().profitTracker && Sky2MLocation.inDwarvenMines()
                && Sky2MLocation.location().contains("Fossil Research Center"),
            ExcavatorProfit::lines,
            List.of(Component.literal("Fossil Excavation Profit").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("Tusk Fossil: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("3 (240k)").withStyle(ChatFormatting.WHITE)),
                Component.literal("Excavations: ").withStyle(ChatFormatting.GRAY).append(Component.literal("12").withStyle(ChatFormatting.WHITE)),
                Component.literal("Total profit: ").withStyle(ChatFormatting.GRAY).append(Component.literal("1.2M").withStyle(ChatFormatting.GOLD))),
            8, 150);
    }

    private static void onChat(String text) {
        MiningExtrasConfig.FossilExcavator c = config();
        if (c == null || !c.profitTracker || !Sky2MLocation.inDwarvenMines()) return;
        String trimmed = text.trim();
        if (trimmed.equals(EMPTY)) {
            data.excavations++;
            save();
            return;
        }
        if (trimmed.equals(START)) {
            inLoot = true;
            LOOT.clear();
            return;
        }
        if (!inLoot) return;
        if (ExtrasUtil.DIVIDER.matcher(text).matches()) {
            inLoot = false;
            data.excavations++;
            for (ExtrasUtil.Loot item : LOOT) add(item);
            save();
            return;
        }
        ExtrasUtil.Loot item = ExtrasUtil.parseLoot(text);
        if (item != null) LOOT.add(item);
    }

    private static void add(ExtrasUtil.Loot item) {
        switch (item.name()) {
            case "Glacite Powder" -> data.glacitePowder += item.amount();
            case "Fossil Dust" -> data.fossilDust += item.amount();
            default -> data.items.merge(item.name(), (long) item.amount(), Long::sum);
        }
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Fossil Excavation Profit").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        double profit = 0;
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>(data.items);
        for (var e : counts.entrySet()) {
            double price = ExtrasUtil.priceByName(e.getKey());
            double value = price < 0 ? 0 : price * e.getValue();
            profit += value;
            rows.add(Map.entry(e.getKey(), value));
        }
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (var row : rows) {
            if (++shown > 8) break;
            out.add(Component.literal(row.getKey() + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(counts.get(row.getKey()) + " (" + ExtrasUtil.coins(row.getValue()) + ")").withStyle(ChatFormatting.WHITE)));
        }
        double scrap = ExtrasUtil.costById(SCRAP_ID);
        if (data.fossilDust > 0 && scrap > 0) {
            double dust = scrap / 500 * data.fossilDust;
            profit += dust;
            out.add(Component.literal("Fossil Dust: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(String.format(Locale.US, "%,d", data.fossilDust) + " (" + ExtrasUtil.coins(dust) + ")").withStyle(ChatFormatting.WHITE)));
        }
        if (data.glacitePowder > 0) {
            out.add(Component.literal("Glacite Powder: ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.format(Locale.US, "%,d", data.glacitePowder)).withStyle(ChatFormatting.WHITE)));
        }
        double cost = scrap * data.excavations;
        profit -= cost;
        out.add(Component.literal("Excavations: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", data.excavations)).withStyle(ChatFormatting.WHITE)));
        if (cost > 0) {
            out.add(Component.literal("Scrap used: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("-" + ExtrasUtil.coins(cost)).withStyle(ChatFormatting.RED)));
        }
        out.add(Component.literal("Total profit: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(ExtrasUtil.coins(profit)).withStyle(profit < 0 ? ChatFormatting.RED : ChatFormatting.GOLD)));
        if (data.excavations > 0) {
            out.add(Component.literal("Per excavation: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ExtrasUtil.coins(profit / data.excavations)).withStyle(profit < 0 ? ChatFormatting.RED : ChatFormatting.GOLD)));
        }
        return out;
    }

    /** The Reset button. */
    public static void reset() {
        data = new Data();
        save();
        Sky2MAlerts.chat(Component.literal("Fossil excavation profit reset.").withStyle(ChatFormatting.YELLOW));
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Data read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (read != null) {
                if (read.items == null) read.items = new LinkedHashMap<>();
                data = read;
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
