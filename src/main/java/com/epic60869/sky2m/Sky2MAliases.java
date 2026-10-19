package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Command aliases: /s2 alias add <name> <command> makes /<name> run <command> ("/s2 alias add m7 joininstance
 * master_catacombs_floor_seven", then /m7). Anything typed after the alias is added to the end, or put where {args}
 * is. /s2 alias remove <name>, /s2 alias list. They can run server and Sky2M commands, and work straight away.
 * Kept in config/sky2m/aliases.json.
 */
public final class Sky2MAliases {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, String> aliases;

    /**
     * Built in: /f1-/f7 and /m1-/m7 join that Catacombs floor, /e the Entrance, /k1-/k5 that Kuudra tier (Basic, Hot,
     * Burning, Fiery, Infernal). Your own alias with the same name takes their place.
     */
    static final Map<String, String> BUILT_IN = builtIn();

    private static Map<String, String> builtIn() {
        Map<String, String> out = new LinkedHashMap<>();
        String[] numbers = {"one", "two", "three", "four", "five", "six", "seven"};
        out.put("e", "joininstance catacombs_entrance");
        for (int i = 0; i < 7; i++) out.put("f" + (i + 1), "joininstance catacombs_floor_" + numbers[i]);
        for (int i = 0; i < 7; i++) out.put("m" + (i + 1), "joininstance master_catacombs_floor_" + numbers[i]);
        String[] tiers = {"normal", "hot", "burning", "fiery", "infernal"};
        for (int i = 0; i < 5; i++) out.put("k" + (i + 1), "joininstance kuudra_" + tiers[i]);
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The command an alias runs: yours first, then the built-in ones. */
    private static String target(String name) {
        String own;
        synchronized (Sky2MAliases.class) {
            own = aliases().get(name);
        }
        return own != null ? own : BUILT_IN.get(name);
    }
    /** An alias running another alias: stop after a few, so two aliases can't call each other forever. */
    private static int depth;

    private Sky2MAliases() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("alias")
                    .executes(c -> {
                        list();
                        return 1;
                    })
                    .then(ClientCommands.literal("list").executes(c -> {
                        list();
                        return 1;
                    }))
                    .then(ClientCommands.literal("add").then(ClientCommands.argument("name", StringArgumentType.word())
                        .then(ClientCommands.argument("command", StringArgumentType.greedyString()).executes(c -> {
                            add(StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "command"));
                            return 1;
                        }))))
                    .then(ClientCommands.literal("remove").then(ClientCommands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(aliases().keySet(), b))
                        .executes(c -> {
                            remove(StringArgumentType.getString(c, "name"));
                            return 1;
                        })))));
            }
        });
        // Typed commands: an alias is swapped for its command before it's sent.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String[] parts = command.trim().split(" ", 2);
            String target = target(parts[0].toLowerCase(Locale.ROOT));
            if (target == null || depth > 3) return true;
            String args = parts.length > 1 ? parts[1].trim() : "";
            String full = target.contains("{args}") ? target.replace("{args}", args).trim() : (args.isEmpty() ? target : target + " " + args);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.getConnection() == null) return;
                depth++;
                try {
                    mc.getConnection().sendCommand(full.startsWith("/") ? full.substring(1) : full);
                } finally {
                    depth--;
                }
            });
            return false;
        });
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("aliases.json");
    }

    private static synchronized Map<String, String> aliases() {
        if (aliases == null) {
            aliases = new LinkedHashMap<>();
            try {
                if (Files.exists(file())) {
                    Map<String, String> loaded = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
                    if (loaded != null) loaded.forEach((k, v) -> { if (k != null && v != null) aliases.put(k.toLowerCase(Locale.ROOT), v); });
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't read aliases.json: " + e.getMessage());
            }
        }
        return aliases;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(aliases()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save aliases.json: " + e.getMessage());
        }
    }

    private static void add(String name, String command) {
        String key = name.toLowerCase(Locale.ROOT).replaceFirst("^/", "");
        if (!key.matches("[a-z0-9_\\-]{1,24}")) {
            say(Component.literal("Alias names are letters, numbers, - and _ (up to 24).").withStyle(ChatFormatting.RED));
            return;
        }
        if (java.util.Arrays.asList(Compat.COMMAND_ROOTS).contains(key)) {
            say(Component.literal("/" + key + " is Sky2M's own command; pick another name.").withStyle(ChatFormatting.RED));
            return;
        }
        String target = command.trim().replaceFirst("^/", "");
        synchronized (Sky2MAliases.class) {
            aliases().put(key, target);
        }
        save();
        say(Component.literal("/" + key + " now runs /" + target + ". Anything after it is added on (or put where {args} is).").withStyle(ChatFormatting.GREEN));
    }

    private static void remove(String name) {
        String removed;
        synchronized (Sky2MAliases.class) {
            removed = aliases().remove(name.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }
        if (removed != null) save();
        say(Component.literal(removed != null ? "Removed /" + name + "." : "There's no alias /" + name + ".").withStyle(ChatFormatting.GRAY));
    }

    private static void list() {
        Map<String, String> all;
        synchronized (Sky2MAliases.class) {
            all = new LinkedHashMap<>(aliases());
        }
        say(Component.literal("Built in: /f1-/f7 and /m1-/m7 (Catacombs floors), /e (Entrance), /k1-/k5 (Kuudra Basic, Hot, Burning, Fiery, Infernal).")
            .withStyle(ChatFormatting.GRAY));
        if (all.isEmpty()) {
            say(Component.literal("No aliases of your own yet: /s2 alias add <name> <command>").withStyle(ChatFormatting.GRAY));
            return;
        }
        say(Component.literal("Your aliases:").withStyle(ChatFormatting.GOLD));
        for (Map.Entry<String, String> e : all.entrySet()) {
            say(Component.literal("/" + e.getKey()).withStyle(s -> s.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.SuggestCommand("/" + e.getKey()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to type it"))))
                .append(Component.literal(" → /" + e.getValue()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" [x]").withStyle(s -> s.withColor(ChatFormatting.RED)
                    .withClickEvent(new ClickEvent.RunCommand("/s2 alias remove " + e.getKey()))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Remove this alias"))))));
        }
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
    }
}
