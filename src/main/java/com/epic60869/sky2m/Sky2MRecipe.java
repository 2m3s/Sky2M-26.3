package com.epic60869.sky2m;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SkyOcean-inspired /s2 recipe command.
 *
 * Syntax: /s2 recipe <item> <amount>
 *
 * Item names and SkyBlock IDs are loaded from Hypixel's public SkyBlock
 * resource API and cached in memory.
 */
public final class Sky2MRecipe {
    private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
    private static final long CACHE_MS = 15 * 60 * 1000L;
    private static final Map<String, HypixelItem> ITEMS = new LinkedHashMap<>();
    private static volatile boolean loading;
    private static volatile long loadedAt;

    private Sky2MRecipe() {}

    public static void init() {
        refreshAsync();
    }

    public static void register(
        com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> root
    ) {
        root.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("recipe")
            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument(
                "recipe",
                com.mojang.brigadier.arguments.StringArgumentType.greedyString()
            ).suggests(Sky2MRecipe::suggest)
            .executes(context -> execute(
                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "recipe")
            )))
        );
    }

    private static CompletableFuture<Suggestions> suggest(
        com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context,
        SuggestionsBuilder builder
    ) {
        refreshAsync();

        String input = builder.getRemaining().toLowerCase(Locale.ROOT).trim();
        String query = input;
        if (input.contains(" ")) {
            String last = input.substring(input.lastIndexOf(' ') + 1);
            if (last.matches("\\d+")) {
                query = input.substring(0, input.lastIndexOf(' ')).trim();
            }
        }

        for (HypixelItem item : ITEMS.values()) {
            if (query.isEmpty()
                || item.name.toLowerCase(Locale.ROOT).contains(query)
                || item.id.toLowerCase(Locale.ROOT).contains(query)) {
                builder.suggest(item.name);
                builder.suggest(item.id);
            }
        }
        return builder.buildFuture();
    }

    private static int execute(String input) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        input = input == null ? "" : input.trim();
        int amount = 1;
        String itemInput = input;

        int split = input.lastIndexOf(' ');
        if (split > 0) {
            String possibleAmount = input.substring(split + 1).trim();
            try {
                int parsed = Integer.parseInt(possibleAmount);
                if (parsed > 0) {
                    amount = parsed;
                    itemInput = input.substring(0, split).trim();
                }
            } catch (NumberFormatException ignored) {}
        }

        HypixelItem item = find(itemInput);
        if (item == null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal(
                "§c[S2M] Unknown Hypixel item: §f" + itemInput
                    + " §7— use autocomplete or wait for the item list to load."
            ));
            refreshAsync();
            return 0;
        }

        mc.player.connection.sendCommand("viewrecipe " + item.id);
        mc.gui.hud.getChat().addClientSystemMessage(Component.literal(
            "§d[S2M] §fRecipe: §a" + amount + "x §f" + item.name
                + " §7(" + item.id + ")"
        ));
        return 1;
    }

    private static HypixelItem find(String input) {
        String normalized = normalize(input);
        HypixelItem exact = ITEMS.get(normalized);
        if (exact != null) return exact;

        for (HypixelItem item : ITEMS.values()) {
            if (normalize(item.name).equals(normalized)
                || normalize(item.id).equals(normalized)) return item;
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    private static void refreshAsync() {
        if (loading || System.currentTimeMillis() - loadedAt < CACHE_MS) return;
        loading = true;

        Thread.startVirtualThread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI.create(ITEMS_URL).toURL().openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "Sky2M/1.0");

                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonArray items = root.getAsJsonArray("items");
                    Map<String, HypixelItem> loaded = new LinkedHashMap<>();

                    if (items != null) {
                        for (JsonElement element : items) {
                            if (!element.isJsonObject()) continue;
                            JsonObject object = element.getAsJsonObject();
                            if (!object.has("id") || !object.has("name")) continue;
                            String id = object.get("id").getAsString();
                            String name = object.get("name").getAsString();
                            if (id.isBlank() || name.isBlank()) continue;
                            loaded.put(normalize(id), new HypixelItem(id, name));
                        }
                    }

                    if (!loaded.isEmpty()) {
                        ITEMS.clear();
                        loaded.values().stream()
                            .sorted(Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT)))
                            .forEach(item -> ITEMS.put(normalize(item.id), item));
                        loadedAt = System.currentTimeMillis();
                        System.out.println("[Sky2M] Loaded " + ITEMS.size()
                            + " Hypixel SkyBlock items for /s2 recipe.");
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Failed to load Hypixel item list: " + e.getMessage());
            } finally {
                loading = false;
            }
        });
    }

    private record HypixelItem(String id, String name) {}
}
