package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps your highest pristine proc, overall and per gemstone, and announces a new PB.
 * The message pattern is SkyHanni's (mining.pristine).
 */
public final class PristineRecord {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern PRISTINE = Pattern.compile("^PRISTINE! You found . Flawed (?<gemstone>\\w+) Gemstone x(?<amount>\\d+)!$");

    /** "Overall" and each gemstone -> highest amount. */
    private static Map<String, Integer> pbs = new HashMap<>();
    private static Path file;

    private PristineRecord() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m-pristine-pbs.json");
        load();
        Sky2MChat.onChat(message -> {
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.mining.features.pristineRecord) return;
            Matcher m = PRISTINE.matcher(message.text());
            if (m.matches()) onPristine(m.group("gemstone"), Integer.parseInt(m.group("amount")));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("pristine")
                    .executes(c -> show())
                    .then(ClientCommands.literal("reset").executes(c -> {
                        pbs.clear();
                        save();
                        return say("Pristine records reset.", ChatFormatting.YELLOW);
                    }))));
            }
        });
    }

    private static void onPristine(String gemstone, int amount) {
        int overall = pbs.getOrDefault("Overall", 0);
        int gem = pbs.getOrDefault(gemstone, 0);
        if (amount > gem) pbs.put(gemstone, amount);
        if (amount > overall) {
            pbs.put("Overall", amount);
            Sky2MAlerts.title(Component.literal("NEW PRISTINE PB!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                Component.literal(gemstone + " x" + amount).withStyle(ChatFormatting.WHITE));
            say("New pristine PB: " + gemstone + " x" + amount + (overall > 0 ? " (old PB x" + overall + ")" : "") + "!", ChatFormatting.LIGHT_PURPLE);
        } else if (amount > gem) {
            say("New " + gemstone + " pristine PB: x" + amount + (gem > 0 ? " (old x" + gem + ")" : "") + ". Overall PB: x" + overall + ".", ChatFormatting.LIGHT_PURPLE);
        }
        if (amount > gem || amount > overall) save();
    }

    private static int show() {
        if (pbs.isEmpty()) return say("No pristine records yet.", ChatFormatting.YELLOW);
        say("Pristine PB: x" + pbs.getOrDefault("Overall", 0), ChatFormatting.LIGHT_PURPLE);
        pbs.entrySet().stream().filter(e -> !e.getKey().equals("Overall")).sorted(Map.Entry.comparingByKey())
            .forEach(e -> say("  " + e.getKey() + ": x" + e.getValue(), ChatFormatting.GRAY));
        return 1;
    }

    private static void load() {
        try {
            if (Files.exists(file)) {
                Map<String, Integer> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Integer>>() {}.getType());
                if (loaded != null) pbs = new HashMap<>(loaded);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read pristine PBs: " + e);
        }
    }

    private static void save() {
        try {
            Files.writeString(file, GSON.toJson(pbs), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save pristine PBs: " + e);
        }
    }

    private static int say(String text, ChatFormatting colour) {
        Sky2MAlerts.chat(Component.literal(text).withStyle(colour));
        return 1;
    }
}
