package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Death Tracker: each death of the run from Hypixel's " ☠ Name was killed by X and became a ghost." lines,
 * with what killed them and the score it cost. Every death costs 2 score, except that the run's first death costs 1
 * when that player has a Spirit pet (which the client can't see, so the first death shows both). With Count Across
 * Runs, each player's deaths in your runs are also kept in {@code sky2m/deaths.json}.
 */
public final class DeathTracker {
    private static final Pattern DEATH = Pattern.compile("^ ?☠ (You|\\w{1,16}) (.+?)(?: and became a ghost)?\\.$");
    private static final Gson GSON = new Gson();

    private record Death(String name, String cause) {}

    private static final List<Death> deaths = new ArrayList<>();
    private static Map<String, Integer> lifetime = new LinkedHashMap<>();
    private static Path file;

    private DeathTracker() {}

    private static FeatureConfigs.DeathTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.deathTracker;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("deaths.json");
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> deaths.clear());
        Sky2MLocation.onAreaChange(area -> deaths.clear());
        Sky2MChat.onChat(message -> onChat(message.text()));
        Sky2MHuds.register("death_tracker", "Death Tracker", () -> config() != null && config().hud && Sky2MLocation.inDungeon(),
            DeathTracker::lines,
            List.of(Component.literal("Deaths: 2 ").withStyle(ChatFormatting.RED).append(Component.literal("(-4 score)").withStyle(ChatFormatting.GRAY)),
                Component.literal("Steve ").withStyle(ChatFormatting.YELLOW).append(Component.literal("Withermancer").withStyle(ChatFormatting.GRAY)),
                Component.literal("Alex ").withStyle(ChatFormatting.YELLOW).append(Component.literal("Lost Adventurer").withStyle(ChatFormatting.GRAY))),
            10, 200);
        Sky2MHuds.setting("death_tracker", () -> config() != null && config().hud);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("deaths").executes(c -> {
                    Minecraft.getInstance().execute(DeathTracker::printLifetime);
                    return 1;
                })));
            }
        });
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        Matcher m = DEATH.matcher(text);
        if (!m.matches()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean self = m.group(1).equals("You");
        String name = self ? (mc.player == null ? "You" : mc.player.getGameProfile().name()) : m.group(1);
        Death death = new Death(name, cause(m.group(2)));
        deaths.add(death);
        if (self) Sky2MAchievements.add(Sky2MAchievements.Stat.DUNGEON_DEATHS, 1);
        FeatureConfigs.DeathTracker c = config();
        if (c == null) return;
        if (c.lifetime) {
            lifetime.merge(name.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
            save();
        }
        if (c.chat) {
            String cost = deaths.size() == 1 ? "-1 or -2 score" : "-2 score";
            Sky2MAlerts.chat(Component.literal(name).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" died: " + death.cause()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" (" + cost + ", " + deaths.size() + (deaths.size() == 1 ? " death" : " deaths") + " this run)").withStyle(ChatFormatting.RED)));
        }
    }

    /** "was killed by Withermancer" -> "Withermancer", "disconnected from the Dungeon" -> "Disconnected". */
    private static String cause(String raw) {
        String s = raw.replaceFirst("^(?:was|were) ", "");
        if (s.startsWith("killed by ")) return s.substring("killed by ".length());
        if (s.startsWith("disconnected")) return "Disconnected";
        return s.isEmpty() ? "Died" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        if (deaths.isEmpty()) return out;
        int min = deaths.size() * 2 - 1, max = deaths.size() * 2;
        out.add(Component.literal("Deaths: " + deaths.size() + " ").withStyle(ChatFormatting.RED)
            .append(Component.literal("(-" + (min == max ? String.valueOf(max) : min + "/" + max) + " score)").withStyle(ChatFormatting.GRAY)));
        FeatureConfigs.DeathTracker c = config();
        for (Death d : deaths) {
            MutableComponent line = Component.literal(d.name() + " ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(d.cause()).withStyle(ChatFormatting.GRAY));
            if (c != null && c.lifetime) {
                int total = lifetime.getOrDefault(d.name().toLowerCase(java.util.Locale.ROOT), 0);
                if (total > 1) line.append(Component.literal(" (" + total + " total)").withStyle(ChatFormatting.DARK_GRAY));
            }
            out.add(line);
        }
        return out;
    }

    private static void printLifetime() {
        if (lifetime.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No deaths saved yet. Turn on Count Across Runs in Dungeon > Death Tracker.").withStyle(ChatFormatting.GRAY));
            return;
        }
        Sky2MAlerts.chat(Component.literal("Most deaths in your runs:").withStyle(ChatFormatting.GOLD));
        lifetime.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10).forEach(e ->
            Sky2MAlerts.chat(Component.literal(" " + e.getKey() + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(String.valueOf(e.getValue())).withStyle(ChatFormatting.RED))));
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, Integer> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Integer>>() {}.getType());
            if (read != null) lifetime = read;
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(lifetime), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
