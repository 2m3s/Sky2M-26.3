package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.profileviewer.ProfileApi;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Catacombs Calculator (/s2 catacalc [level] [floor or XP]): runs of a floor until a Catacombs level, from
 * your Catacombs XP on your profile. Each floor's XP per run is what your last run of it gave you (Hypixel's
 * "+300,000 Catacombs Experience" run summary line, so your bonuses are already in it), else the floor's base S+ XP
 * with the XP Bonus setting. Levels past 50 take another 200M each, like the profile viewer.
 */
public final class CataCalculator {
    private static final Pattern RUN_XP = Pattern.compile("^\\+([\\d,]+(?:\\.\\d+)?) Catacombs Experience");
    private static final Pattern FLOOR = Pattern.compile("(?i)^(?:e|[fm][1-7])$");
    private static final Pattern NUMBER = Pattern.compile("(?i)^([\\d.,]+)([kmb]?)$");
    /** Catacombs XP for an S+ run of each floor, before bonuses. */
    private static final Map<String, Double> BASE_XP = Map.ofEntries(
        Map.entry("E", 50.0), Map.entry("F1", 80.0), Map.entry("F2", 160.0), Map.entry("F3", 400.0), Map.entry("F4", 1420.0),
        Map.entry("F5", 2000.0), Map.entry("F6", 4000.0), Map.entry("F7", 20000.0), Map.entry("M1", 10000.0),
        Map.entry("M2", 15000.0), Map.entry("M3", 36500.0), Map.entry("M4", 48500.0), Map.entry("M5", 70000.0),
        Map.entry("M6", 100000.0), Map.entry("M7", 300000.0));
    private static final Gson GSON = new Gson();

    private static final class Saved {
        Map<String, Double> lastXp = new LinkedHashMap<>();
        String lastFloor = "";
    }

    private static Saved saved = new Saved();
    private static Path file;

    private CataCalculator() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("cata-xp.json");
        load();
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("catacalc")
                    .executes(c -> run(50, ""))
                    .then(ClientCommands.argument("level", IntegerArgumentType.integer(1, 100))
                        .executes(c -> run(IntegerArgumentType.getInteger(c, "level"), ""))
                        .then(ClientCommands.argument("floor", StringArgumentType.word())
                            .executes(c -> run(IntegerArgumentType.getInteger(c, "level"), StringArgumentType.getString(c, "floor")))))));
            }
        });
    }

    private static int run(int level, String arg) {
        Minecraft.getInstance().execute(() -> calculate(level, arg));
        return 1;
    }

    /** "E", "F1".."F7" or "M1".."M7" for the dungeon you're in, or "" outside one. */
    private static String currentFloor() {
        String floor = Sky2MLocation.dungeonFloor();
        if (floor.isEmpty()) return "";
        return floor.equals("E0") || floor.equals("F0") ? "E" : floor.toUpperCase(Locale.ROOT);
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        Matcher m = RUN_XP.matcher(text);
        String floor = currentFloor();
        if (!m.find() || floor.isEmpty()) return;
        try {
            saved.lastXp.put(floor, Double.parseDouble(m.group(1).replace(",", "")));
            saved.lastFloor = floor;
            save();
        } catch (NumberFormatException ignored) {}
    }

    public static void calculate(int target, String arg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String floor;
        double perRun;
        String source;
        Matcher number = NUMBER.matcher(arg.trim());
        if (!arg.isBlank() && !FLOOR.matcher(arg).matches() && number.matches()) {
            perRun = parse(number.group(1), number.group(2));
            floor = "";
            source = "per run";
        } else {
            floor = !arg.isBlank() ? arg.toUpperCase(Locale.ROOT) : !currentFloor().isEmpty() ? currentFloor() : !saved.lastFloor.isEmpty() ? saved.lastFloor : "M7";
            Double last = saved.lastXp.get(floor);
            if (last != null && last > 0) {
                perRun = last;
                source = "from your last " + floor + " run";
            } else {
                Sky2MConfig c = Sky2MConfig.current();
                int bonus = c == null ? 0 : c.dungeons.cataCalculator.bonusPercent;
                perRun = BASE_XP.getOrDefault(floor, 0.0) * (1 + bonus / 100.0);
                source = floor + "'s base S+ XP" + (bonus > 0 ? " +" + bonus + "%" : "");
            }
        }
        if (perRun <= 0) {
            say(Component.literal("Unknown floor \"" + arg + "\". Use E, F1-F7, M1-M7 or an XP amount.").withStyle(ChatFormatting.RED));
            return;
        }
        String name = mc.player.getGameProfile().name();
        double xpPerRun = perRun;
        String runFloor = floor, from = source;
        say(Component.literal("Loading your Catacombs XP...").withStyle(ChatFormatting.GRAY));
        ProfileApi.player(name).whenComplete((player, error) -> mc.execute(() -> {
            if (error != null || player == null) {
                say(Component.literal("Couldn't load your profile: " + ProfileApi.reason(error)).withStyle(ChatFormatting.RED));
                return;
            }
            double xp = cataXp(player.profiles().getFirst().member());
            double need = xpFor(target) - xp;
            String level = String.format(Locale.US, "%.2f", PartyFinderStats.level(xp));
            if (need <= 0) {
                say(Component.literal("You're already Catacombs " + level + ", past " + target + ".").withStyle(ChatFormatting.GREEN));
                return;
            }
            long runs = (long) Math.ceil(need / xpPerRun);
            say(Component.literal("Catacombs " + level + " → " + target + ": ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(CombatFeatures.formatCoins(need) + " XP to go").withStyle(ChatFormatting.WHITE)));
            say(Component.literal(String.format(Locale.US, "%,d", runs) + (runFloor.isEmpty() ? " runs" : " " + runFloor + " runs")).withStyle(ChatFormatting.AQUA)
                .append(Component.literal(" at " + CombatFeatures.formatCoins(xpPerRun) + " XP each (" + from + ").").withStyle(ChatFormatting.GRAY)));
        }));
    }

    private static double parse(String digits, String suffix) {
        try {
            double v = Double.parseDouble(digits.replace(",", ""));
            return switch (suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> v * 1_000;
                case "m" -> v * 1_000_000;
                case "b" -> v * 1_000_000_000;
                default -> v;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Total Catacombs XP to reach {@code level}. */
    private static double xpFor(int level) {
        double total = 0;
        for (int i = 0; i < level; i++) total += i < PartyFinderStats.CATA_XP.length ? PartyFinderStats.CATA_XP[i] : 200_000_000L;
        return total;
    }

    private static double cataXp(JsonObject member) {
        JsonElement e = member;
        for (String key : new String[]{"dungeons", "dungeon_types", "catacombs", "experience"}) {
            if (e == null || !e.isJsonObject()) return 0;
            e = e.getAsJsonObject().get(key);
        }
        return e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
    }

    private static void say(Component text) {
        Sky2MAlerts.chat(text);
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Saved read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
            if (read != null) {
                if (read.lastXp == null) read.lastXp = new LinkedHashMap<>();
                if (read.lastFloor == null) read.lastFloor = "";
                saved = read;
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
