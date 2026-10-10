package com.epic60869.sky2m;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * SkyOcean-style recipe command.
 *
 * Uses the public Hypixel item list for autocomplete and the public
 * NotEnoughUpdates repository for recipe layouts. The recipe is rendered
 * locally instead of sending /viewrecipe to Hypixel, avoiding server GUI
 * crashes and allowing the requested amount to be shown.
 */
public final class Sky2MRecipeCommand {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private static final List<ItemEntry> ITEMS = new ArrayList<>();

    private record ItemEntry(String id, String name) {}

    private Sky2MRecipeCommand() {}

    public static void init() {
        CompletableFuture.runAsync(Sky2MRecipeCommand::loadItems);
    }

    private static void loadItems() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hypixel.net/v2/resources/skyblock/items"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("User-Agent", "Sky2M/1.0")
                .GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("items");
            if (items == null) return;

            List<ItemEntry> next = new ArrayList<>();
            for (JsonElement element : items) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (!item.has("id") || !item.has("name")) continue;
                String id = item.get("id").getAsString().trim();
                String name = item.get("name").getAsString().trim();
                if (!id.isEmpty() && !name.isEmpty()) next.add(new ItemEntry(id, name));
            }
            next.sort(Comparator.comparing(ItemEntry::name, String.CASE_INSENSITIVE_ORDER));
            synchronized (ITEMS) {
                ITEMS.clear();
                ITEMS.addAll(next);
            }
            System.out.println("[Sky2M] Loaded " + next.size() + " Hypixel items for recipe autocomplete.");
        } catch (Exception e) {
            System.err.println("[Sky2M] Recipe item list load failed: " + e.getMessage());
        }
    }

    /** /s2 recipe, following SkyOcean's /skyocean recipe: item (with optional amount), amount, clear. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return literal("recipe")
            .then(literal("clear").executes(context -> {
                Sky2MCraftHelper.clear();
                message("Cleared current recipe!", 0xFFFFFF);
                return 1;
            }))
            .then(literal("amount").then(argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                .executes(context -> {
                    if (!Sky2MCraftHelper.active()) {
                        message("No recipe selected. Use /s2 recipe <item>.", 0xFF5555);
                        return 0;
                    }
                    Sky2MCraftHelper.setAmount(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "amount"));
                    return 1;
                })))
            .then(argument("item", StringArgumentType.greedyString())
                .suggests(Sky2MRecipeCommand::suggestItems)
                .executes(Sky2MRecipeCommand::run));
    }

    private static CompletableFuture<Suggestions> suggestItems(
        CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        String input = builder.getRemaining().toLowerCase(Locale.ROOT);
        int shown = 0;
        synchronized (ITEMS) {
            for (ItemEntry item : ITEMS) {
                if (item.name().toLowerCase(Locale.ROOT).contains(input)) {
                    builder.suggest(item.name());
                    if (++shown >= 200) break;
                }
            }
        }
        return builder.buildFuture();
    }

    private static int run(CommandContext<FabricClientCommandSource> context) {
        String raw = StringArgumentType.getString(context, "item").trim();
        // "<item>" or "<item> <amount>", as in SkyOcean.
        String id = resolveId(raw);
        int amount = 1;
        if (id == null || !knownId(id)) {
            int split = raw.lastIndexOf(' ');
            if (split > 0) {
                try {
                    amount = Math.max(1, Integer.parseInt(raw.substring(split + 1)));
                    String byName = resolveId(raw.substring(0, split).trim());
                    if (byName != null) id = byName;
                } catch (NumberFormatException ignored) {}
            }
        }
        if (id == null) {
            message("Unknown SkyBlock item: " + raw, 0xFF5555);
            return 0;
        }
        Sky2MCraftHelper.select(id, amount, true);
        return 1;
    }

    private static boolean knownId(String id) {
        synchronized (ITEMS) {
            for (ItemEntry item : ITEMS) if (item.id().equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    private static void message(String text, int colour) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.hud.getChat().addClientSystemMessage(com.epic60869.sky2m.custom.util.Compat.PREFIX.get()
                .append(Component.literal(text).withColor(colour)));
        }
    }

    private static String resolveId(String input) {
        String normalized = input.toLowerCase(Locale.ROOT).replace(' ', '_');
        synchronized (ITEMS) {
            for (ItemEntry item : ITEMS) if (item.id().equalsIgnoreCase(input)) return item.id();
            for (ItemEntry item : ITEMS) if (item.name().equalsIgnoreCase(input)) return item.id();
            for (ItemEntry item : ITEMS) if (item.id().equalsIgnoreCase(normalized)) return item.id();
        }
        return input.matches("[A-Za-z0-9_:.\\-]+") ? input.toUpperCase(Locale.ROOT) : null;
    }

    /** Every SkyBlock item as {id, name}, for search suggestions. */
    public static java.util.List<String[]> items() {
        synchronized (ITEMS) {
            return ITEMS.stream().map(i -> new String[]{i.id(), i.name()}).toList();
        }
    }

    public static String displayName(String id) {
        synchronized (ITEMS) {
            for (ItemEntry item : ITEMS) if (item.id().equalsIgnoreCase(id)) return item.name();
            // NEU repo ids use "-" for variants (INK_SACK-4) where Hypixel uses ":" (INK_SACK:4).
            String hypixelId = id.replace('-', ':');
            for (ItemEntry item : ITEMS) if (item.id().equalsIgnoreCase(hypixelId)) return item.name();
        }
        return id.replace('_', ' ');
    }

}
