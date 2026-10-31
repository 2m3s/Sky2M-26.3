package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trophy fish progress: counts each trophy fish you catch by tier (Bronze, Silver, Gold, Diamond), read from Hypixel's
 * "TROPHY FISH! You caught a Blobfish BRONZE." message. It only counts catches made while the mod runs, so the numbers
 * start at 0 and can differ from Odger's menu. Saved in config/sky2m/trophy-fish.json.
 */
public final class TrophyFishTracker {
    private static final Pattern CATCH = Pattern.compile("TROPHY FISH! You caught an? (.+?) (BRONZE|SILVER|GOLD|DIAMOND)\\.?\\s*$");
    private static final String[] TIERS = {"BRONZE", "SILVER", "GOLD", "DIAMOND"};
    private static final ChatFormatting[] COLOURS = {ChatFormatting.GOLD, ChatFormatting.GRAY, ChatFormatting.YELLOW, ChatFormatting.AQUA};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Fish name -> counts per tier, in the order of {@link #TIERS}. */
    private static final Map<String, int[]> COUNTS = new TreeMap<>();
    private static Path file;
    private static String lastCatch = "";

    private TrophyFishTracker() {}

    public static final class Config {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Count the trophy fish you catch by tier from the catch message. Counts only start when you turn this on, so they can differ from Odger's menu. /s2 trophyfish lists them.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "HUD", desc = "A HUD with your total Bronze, Silver, Gold and Diamond catches and the last fish with its count (needs Enabled). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Chat Message", desc = "After a catch, say in chat how many of that fish and tier you have caught (needs Enabled).")
        @ConfigEditorBoolean
        public boolean chat = false;
    }

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.fishing.trophyFish;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("trophy-fish.json");
        load();
        Sky2MChat.onChat(message -> {
            Config c = config();
            if (c == null || !c.enabled) return;
            Matcher m = CATCH.matcher(message.text().trim());
            if (m.find()) caught(c, m.group(1), m.group(2));
        });
        Sky2MHuds.register("trophy_fish", "Trophy Fish", () -> {
                Config c = config();
                return c != null && c.enabled && c.hud;
            }, TrophyFishTracker::hudLines,
            List.of(Component.literal("Trophy Fish: ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("B 120  S 40  G 9  D 1").withStyle(ChatFormatting.WHITE)),
                Component.literal("Last: Blobfish (Gold x3)").withStyle(ChatFormatting.GRAY)), 8, 330);
        Sky2MHuds.setting("trophy_fish", () -> config() != null && config().enabled && config().hud);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("trophyfish").executes(c -> {
                    list();
                    return 1;
                })));
            }
        });
    }

    private static void caught(Config c, String fish, String tier) {
        int index = 0;
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(tier)) index = i;
        int count = ++COUNTS.computeIfAbsent(fish, k -> new int[TIERS.length])[index];
        lastCatch = fish + " (" + capitalise(tier) + " x" + count + ")";
        save();
        if (c.chat) {
            Sky2MAlerts.chat(Component.literal(fish + " ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(capitalise(tier)).withStyle(COLOURS[index]))
                .append(Component.literal(" caught " + count + " time" + (count == 1 ? "" : "s")).withStyle(ChatFormatting.GRAY)));
        }
    }

    private static String capitalise(String tier) {
        return tier.charAt(0) + tier.substring(1).toLowerCase(Locale.ROOT);
    }

    private static int[] totals() {
        int[] total = new int[TIERS.length];
        for (int[] counts : COUNTS.values()) for (int i = 0; i < total.length; i++) total[i] += counts[i];
        return total;
    }

    private static List<Component> hudLines() {
        if (COUNTS.isEmpty()) return List.of();
        int[] t = totals();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Trophy Fish: ").withStyle(ChatFormatting.AQUA)
            .append(Component.literal("B " + t[0] + "  S " + t[1] + "  G " + t[2] + "  D " + t[3]).withStyle(ChatFormatting.WHITE)));
        if (!lastCatch.isEmpty()) out.add(Component.literal("Last: " + lastCatch).withStyle(ChatFormatting.GRAY));
        return out;
    }

    private static void list() {
        if (COUNTS.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No trophy fish counted yet. Turn on Fishing > Trophy Fish and catch some.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Sky2MAlerts.chat(Component.literal("Trophy fish caught since tracking started:").withStyle(ChatFormatting.AQUA));
        for (Map.Entry<String, int[]> e : COUNTS.entrySet()) {
            MutableComponent line = Component.literal(e.getKey() + ": ").withStyle(ChatFormatting.WHITE);
            for (int i = 0; i < TIERS.length; i++) {
                line.append(Component.literal(TIERS[i].charAt(0) + " " + e.getValue()[i] + "  ").withStyle(COLOURS[i]));
            }
            Sky2MAlerts.chat(line);
        }
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, int[]> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, int[]>>() {}.getType());
            if (loaded == null) return;
            loaded.forEach((fish, counts) -> {
                if (counts == null) return;
                int[] fixed = new int[TIERS.length];
                System.arraycopy(counts, 0, fixed, 0, Math.min(counts.length, fixed.length));
                COUNTS.put(fish, fixed);
            });
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read trophy-fish.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(COUNTS), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save trophy-fish.json: " + e.getMessage());
        }
    }
}
