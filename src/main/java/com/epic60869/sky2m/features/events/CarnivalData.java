package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MRepo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Carnival's wording and values, from Sky2M-REPO's {@code constants/Carnival.json} (bundled copy first, then the
 * live one). Everything Hypixel could reword lives here: goal sentences, the COMPLETE line, the chat lines that start
 * and end games, the Fruit Digging hints, fruit values and head textures, and the game NPCs.
 */
public final class CarnivalData {
    public record Game(String id, String label, ChatFormatting colour) {}

    public record Goal(String id, Game game, String sentence, String display) {}

    public record FruitInfo(String label, int points, int count, String texture) {}

    /** One loaded snapshot, swapped whole so readers never see half an update. */
    public record Snapshot(List<Game> games, List<Goal> goals, String menuTitle, Pattern completeLine,
                           String shootoutStart, String shootoutEnd, String fruitStart, String fruitEnd, String npcOptions,
                           Pattern fruitTreasure, Pattern fruitNoFruit, Pattern fruitMines, Pattern fruitReveal,
                           Map<String, String> npcs, String quickStartOption, int maxDigs, int boardX, int boardY, int boardZ,
                           Map<CarnivalFruitDigging.Fruit, FruitInfo> fruits) {}

    private static volatile Snapshot current;

    private CarnivalData() {}

    public static Snapshot get() {
        Snapshot s = current;
        if (s == null) {
            JsonObject bundled = Sky2MRepo.bundled("Carnival");
            if (bundled == null) throw new IllegalStateException("Carnival.json is missing from the jar");
            current = s = parse(bundled);
        }
        return s;
    }

    static void init() {
        Sky2MRepo.load("Carnival", json -> current = parse(json));
    }

    /** Reads a Carnival.json. Throws on a broken file, so {@link Sky2MRepo} logs it and the old snapshot stays. */
    public static Snapshot parse(JsonObject json) {
        JsonObject goals = json.getAsJsonObject("goals");
        Map<String, Game> games = new LinkedHashMap<>();
        for (JsonElement e : goals.getAsJsonArray("games")) {
            JsonObject g = e.getAsJsonObject();
            ChatFormatting colour;
            try {
                colour = ChatFormatting.valueOf(g.get("colour").getAsString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                colour = ChatFormatting.WHITE;
            }
            games.put(g.get("id").getAsString(), new Game(g.get("id").getAsString(), g.get("label").getAsString(), colour));
        }
        List<Goal> list = new ArrayList<>();
        for (JsonElement e : goals.getAsJsonArray("list")) {
            JsonObject g = e.getAsJsonObject();
            Game game = games.get(g.get("game").getAsString());
            if (game == null) throw new IllegalArgumentException("goal " + g.get("id") + " has an unknown game");
            list.add(new Goal(g.get("id").getAsString(), game, g.get("sentence").getAsString(), g.get("display").getAsString()));
        }
        JsonObject chat = json.getAsJsonObject("chat");
        Map<String, String> npcs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("npcs").entrySet()) npcs.put(e.getKey(), e.getValue().getAsString());
        JsonObject fruitDigging = json.getAsJsonObject("fruitDigging");
        JsonObject board = fruitDigging.getAsJsonObject("board");
        Map<CarnivalFruitDigging.Fruit, FruitInfo> fruits = new EnumMap<>(CarnivalFruitDigging.Fruit.class);
        for (Map.Entry<String, JsonElement> e : fruitDigging.getAsJsonObject("fruits").entrySet()) {
            CarnivalFruitDigging.Fruit fruit;
            try {
                fruit = CarnivalFruitDigging.Fruit.valueOf(e.getKey());
            } catch (IllegalArgumentException unknown) {
                continue; // a fruit this jar doesn't know about
            }
            JsonObject f = e.getValue().getAsJsonObject();
            fruits.put(fruit, new FruitInfo(f.get("label").getAsString(), f.get("points").getAsInt(), f.get("count").getAsInt(),
                f.get("texture").getAsString()));
        }
        return new Snapshot(List.copyOf(games.values()), List.copyOf(list), goals.get("menuTitle").getAsString(),
            Pattern.compile(goals.get("completeLine").getAsString()),
            chat.get("shootoutStart").getAsString(), chat.get("shootoutEnd").getAsString(),
            chat.get("fruitStart").getAsString(), chat.get("fruitEnd").getAsString(), chat.get("npcOptions").getAsString(),
            Pattern.compile(chat.get("fruitTreasure").getAsString()), Pattern.compile(chat.get("fruitNoFruit").getAsString()),
            Pattern.compile(chat.get("fruitMines").getAsString()), Pattern.compile(chat.get("fruitReveal").getAsString()),
            Map.copyOf(npcs), json.get("quickStartOption").getAsString(), fruitDigging.get("maxDigs").getAsInt(),
            board.get("x").getAsInt(), board.get("y").getAsInt(), board.get("z").getAsInt(), fruits);
    }
}
