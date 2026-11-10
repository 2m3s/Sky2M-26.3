package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Death Tracker > Death Map: how many times you've died in each room and each boss, from the same chat lines as
 * {@link DeathTracker}. Saved in config/sky2m/death-map.json; /s2 deathmap lists the worst ({@code /s2 deaths} is
 * {@link DeathTracker}'s list of players). Not in the Dungeon Sim.
 */
public final class DeathMap {
    /** Your deaths by where they happened. Public for the game test. */
    public static final class Store {
        private final Map<String, Integer> places = new LinkedHashMap<>();

        public void add(String place) {
            places.merge(place, 1, Integer::sum);
        }

        public int total() {
            return places.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** The places with the most deaths first. */
        public List<Map.Entry<String, Integer>> worst(int limit) {
            return places.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(limit).toList();
        }

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonObject list = new JsonObject();
            places.forEach(list::addProperty);
            root.add("places", list);
            return new GsonBuilder().setPrettyPrinting().create().toJson(root);
        }

        /** Throws on a file that isn't JSON at all; one bad place is skipped so the rest still load. */
        public static Store fromJson(String json) {
            Store store = new Store();
            JsonObject places = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("places");
            if (places == null) return store;
            for (var entry : places.entrySet()) {
                try {
                    store.places.put(entry.getKey(), entry.getValue().getAsInt());
                } catch (RuntimeException ignored) {
                    // Only this place is lost.
                }
            }
            return store;
        }
    }

    private static final RepoPattern DEATH = RepoPattern.of("dungeons.DeathMap.DEATH", "^ ?☠ (You|\\w{1,16}) (.+?)(?: and became a ghost)?\\.$");
    private static final int LISTED = 10;
    private static Store store = new Store();
    private static Path file;

    private DeathMap() {}

    static void init() {
        file = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("death-map.json");
        store = SavedJson.load(file, Store::fromJson, store);
        Sky2MChat.onChat(message -> onChat(message.text()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("deathmap").executes(c -> show())));
            }
        });
    }

    private static void onChat(String text) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.dungeons.deathTracker.deathMap || !Sky2MLocation.inDungeon() || com.epic60869.sky2m.sim.roomsim.SimState.isActive()) return;
        if (!isOwnDeath(text, Minecraft.getInstance().getUser().getName())) return;
        Room room = DungeonManager.getCurrentRoom();
        store.add(place(DungeonManager.isInBoss(), Sky2MLocation.dungeonFloor(), room != null && room.isMatched() ? room.getName() : null));
        SavedJson.save(file, store.toJson());
    }

    /** Whether a chat line is your own death, not a teammate's or someone reconnecting. Public for the game test. */
    public static boolean isOwnDeath(String text, String ownName) {
        Matcher m = DEATH.matcher(text);
        if (!m.matches() || m.group(2).contains("reconnected")) return false;
        return m.group(1).equals("You") || m.group(1).equalsIgnoreCase(ownName);
    }

    /** "M7 boss", "Mithril Cave" from "mithril-cave-2", else "Unknown room". Public for the game test. */
    public static String place(boolean inBoss, String floor, String room) {
        if (inBoss) return floor == null || floor.isEmpty() ? "Boss" : floor + " boss";
        if (room == null || room.isBlank()) return "Unknown room";
        StringBuilder out = new StringBuilder();
        for (String word : room.replaceAll("-\\d+$", "").split("[-_ ]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.isEmpty() ? "Unknown room" : out.toString();
    }

    private static int show() {
        if (store.total() == 0) return DungeonRoutes.say("No deaths saved yet.", ChatFormatting.YELLOW);
        StringBuilder text = new StringBuilder("Where you die most (" + store.total() + " in all):");
        for (var entry : store.worst(LISTED)) text.append('\n').append(entry.getKey()).append(": ").append(entry.getValue());
        return DungeonRoutes.say(text.toString(), ChatFormatting.YELLOW);
    }
}
