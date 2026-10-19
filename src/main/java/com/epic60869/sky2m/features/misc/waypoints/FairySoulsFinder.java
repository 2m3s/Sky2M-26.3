package com.epic60869.sky2m.features.misc.waypoints;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SkyHanni's Fairy Soul finder (https://github.com/hannibal002/SkyHanni, LGPL-2.1 — its Fast Fairy Soul Finder and
 * NEU-Souls Pathfinder), built on the coordinates in NEU's constants/fairy_souls.json (the same file Skyblocker and
 * SkyHanni's pathfinder use): every missing soul on the island you're on is drawn as a box with a "Fairy Soul" label,
 * the missing souls nearest you in a stronger colour with their distance, like SkyHanni's route to the nearest one.
 * Soul positions come down once from the NEU repo and found souls are saved in {@code sky2m/fairy-souls.json}.
 * A soul is marked found when you click one right on it (Skyblocker's approach) or on Hypixel's pickup message, and
 * /s2 souls has mark, unmark, markall and unmarkall commands (Skyblocker's island-wide marking).
 */
public final class FairySoulsFinder {
    private static final String HUD_ID = "fairy_souls_finder";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern SOUL_PICKUP = Pattern.compile("fairy soul", Pattern.CASE_INSENSITIVE);
    /** NEU key → the names Sky2MLocation.area() reports for that island, matched case-insensitively. */
    private static final Map<String, List<String>> ISLANDS = Map.ofEntries(
        Map.entry("hub", List.of("Hub", "Dark Auction")),
        Map.entry("combat_1", List.of("Spider's Den")),
        Map.entry("combat_3", List.of("The End")),
        Map.entry("crimson_isle", List.of("Crimson Isle")),
        Map.entry("foraging_1", List.of("The Park", "Birch Park")),
        Map.entry("foraging_2", List.of("The Park", "Spruce Woods", "Dark Taiga")),
        Map.entry("foraging_3", List.of("The Park", "Savanna Woods")),
        Map.entry("farming_1", List.of("The Farming Islands")),
        Map.entry("mining_1", List.of("Gold Mine")),
        Map.entry("mining_2", List.of("Deep Caverns")),
        Map.entry("mining_3", List.of("Deep Caverns")),
        Map.entry("winter", List.of("Jerry's Workshop")),
        Map.entry("dungeon_hub", List.of("Dungeon Hub")),
        Map.entry("fishing_1", List.of("Hub")),
        Map.entry("lotus_atoll", List.of("Backwater Bayou")),
        Map.entry("safari", List.of("Galatea")));
    /// The island's usual name for statuses, chat and the list (at most one per NEU key).
    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("hub", "Hub"),
        Map.entry("combat_1", "Spider's Den"),
        Map.entry("combat_3", "The End"),
        Map.entry("crimson_isle", "Crimson Isle"),
        Map.entry("foraging_1", "The Park"),
        Map.entry("foraging_2", "The Park"),
        Map.entry("foraging_3", "The Park"),
        Map.entry("farming_1", "The Farming Islands"),
        Map.entry("mining_1", "Gold Mine"),
        Map.entry("mining_2", "Deep Caverns"),
        Map.entry("mining_3", "Deep Caverns"),
        Map.entry("winter", "Jerry's Workshop"),
        Map.entry("dungeon_hub", "Dungeon Hub"),
        Map.entry("fishing_1", "Hub"),
        Map.entry("lotus_atoll", "Backwater Bayou"),
        Map.entry("safari", "Galatea"));

    /** The souls of one island: where each soul is, in the order of NEU's file. */
    public static final class Souls {
        public final String key;
        public final List<BlockPos> positions = new ArrayList<>();

        Souls(String key) {
            this.key = key;
        }

        public int total() {
            return positions.size();
        }
    }

    private record SoulAt(String key, int index, BlockPos pos, Vec3 centre, double distance) {}

    private static final Map<String, Souls> DATA = new LinkedHashMap<>();
    /** NEU island key → the indexes of the file's souls you've marked found. */
    private static final Map<String, List<Integer>> FOUND = new HashMap<>();
    private static volatile boolean dataLoaded;
    private static Path file;
    private static List<Component> hudLines = List.of();
    private static int ticksSinceHud;

    private FairySoulsFinder() {}

    private static FeatureConfigs.FairySouls config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.fairySouls;
    }

    private static boolean enabled() {
        FeatureConfigs.FairySouls c = config();
        return c != null && c.enabled && Sky2MLocation.onSkyblock();
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("fairy-souls.json");
        load();
        Sky2MWorldRender.register(FairySoulsFinder::render);
        Sky2MLocation.onAreaChange(FairySoulsFinder::onAreaChange);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(FairySoulsFinder::tick);
        Sky2MChat.onChat(FairySoulsFinder::onChat);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) mark(hit.getBlockPos());
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide()) mark(pos);
            return InteractionResult.PASS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("souls")
                    .executes(c -> {
                        Minecraft.getInstance().execute(FairySoulsFinder::list);
                        return 1;
                    })
                    .then(ClientCommands.literal("list").executes(c -> {
                        Minecraft.getInstance().execute(FairySoulsFinder::list);
                        return 1;
                    }))
                    .then(ClientCommands.literal("mark").executes(c -> {
                        Minecraft.getInstance().execute(() -> markNearest(true));
                        return 1;
                    }))
                    .then(ClientCommands.literal("unmark").executes(c -> {
                        Minecraft.getInstance().execute(() -> markNearest(false));
                        return 1;
                    }))
                    .then(ClientCommands.literal("markall").executes(c -> {
                        Minecraft.getInstance().execute(() -> changeAll(true));
                        return 1;
                    }))
                    .then(ClientCommands.literal("unmarkall").executes(c -> {
                        Minecraft.getInstance().execute(() -> changeAll(false));
                        return 1;
                    }))));
            }
        });
        Sky2MHuds.register(HUD_ID, "Fairy Souls", FairySoulsFinder::hudEnabled,
            () -> hudLines,
            List.of(Component.literal("Fairy Souls 45/81 — Hub").withStyle(ChatFormatting.LIGHT_PURPLE),
                Component.literal("244 found in total").withStyle(ChatFormatting.GRAY)),
            4, 180);
        Sky2MHuds.setSettingsSearch(HUD_ID, "Fairy Souls");
        Sky2MHuds.setting(HUD_ID, FairySoulsFinder::hudEnabled);
        RepoItems.runAsync(FairySoulsFinder::loadData);
    }

    private static boolean hudEnabled() {
        FeatureConfigs.FairySouls c = config();
        return c != null && c.enabled && c.hud;
    }

    /** NEU's constants/fairy_souls.json: {"Max Souls": n, "<island>": ["x,y,z", ...]}. */
    private static void loadData() {
        try {
            JsonObject root = JsonParser.parseString(RepoItems.neuRepoFile("constants/fairy_souls.json")).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (entry.getKey().equals("Max Souls") || !entry.getValue().isJsonArray()) continue;
                Souls souls = new Souls(entry.getKey());
                for (JsonElement e : entry.getValue().getAsJsonArray()) {
                    try {
                        String[] split = e.getAsString().split(",");
                        souls.positions.add(new BlockPos(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2])));
                    } catch (Exception ignored) {}
                }
                if (!souls.positions.isEmpty()) DATA.put(entry.getKey(), souls);
            }
            synchronized (DATA) {
                dataLoaded = true;
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load fairy soul coordinates: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ where am I

    /** The island's usual name, or the raw NEU key when the file has an island Sky2M doesn't map yet. */
    private static String islandName(String key) {
        return NAMES.getOrDefault(key, key);
    }

    /** Every NEU island key that matches the area you're on, file order, or all keys off SkyBlock. */
    private static List<String> matchingKeys() {
        List<String> keys = new ArrayList<>();
        if (!dataLoaded) return keys;
        synchronized (DATA) {
            keys.addAll(DATA.keySet());
        }
        if (!Sky2MLocation.onSkyblock()) return keys;
        String area = Sky2MLocation.area().toLowerCase(Locale.ROOT);
        if (area.isEmpty() || Sky2MLocation.inDungeon()) return keys;
        keys.removeIf(key -> {
            for (String name : ISLANDS.getOrDefault(key, List.of(key))) {
                if (area.equals(name.toLowerCase(Locale.ROOT))) return false;
            }
            return true;
        });
        return keys;
    }

    // ------------------------------------------------------------------------------------------------ found souls

    /** How many of the island's souls are marked found. */
    private static int found(String key) {
        List<Integer> marked = FOUND.get(key);
        return marked == null ? 0 : marked.size();
    }

    private static boolean isFound(String key, int index) {
        List<Integer> marked = FOUND.get(key);
        return marked != null && marked.contains(index);
    }

    private static void setFound(String key, int index, boolean found) {
        List<Integer> marked = FOUND.computeIfAbsent(key, k -> new ArrayList<>());
        if (found) {
            if (!marked.contains(index)) marked.add(index);
        } else {
            marked.remove(Integer.valueOf(index));
        }
        save();
    }

    /** Marks or unmarks the soul at that exact block position, if there is one. */
    private static void mark(BlockPos pos) {
        if (!enabled()) return;
        for (String key : matchingKeys()) {
            Souls souls;
            synchronized (DATA) {
                souls = DATA.get(key);
            }
            if (souls == null) continue;
            for (int i = 0; i < souls.positions.size(); i++) {
                if (!souls.positions.get(i).equals(pos)) continue;
                boolean was = isFound(key, i);
                setFound(key, i, !was);
                Sky2MAlerts.chat(Component.literal("Fairy Soul marked as ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(was ? Component.literal("not found again.").withStyle(ChatFormatting.YELLOW)
                        : Component.literal("found!").withStyle(ChatFormatting.GREEN)));
                return;
            }
        }
    }

    /** The nearest soul in the state "found" asks for, within 64 blocks. */
    private static void markNearest(boolean found) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || mc.player == null) {
            Sky2MAlerts.chat(Component.literal("Fairy Souls is off here.").withStyle(ChatFormatting.RED));
            return;
        }
        Vec3 player = mc.player.position();
        List<String> keys = matchingKeys();
        String bestKey = null;
        Integer bestIndex = null;
        double best = 64 * 64;
        for (String key : keys) {
            Souls souls;
            synchronized (DATA) {
                souls = DATA.get(key);
            }
            if (souls == null) continue;
            for (int i = 0; i < souls.positions.size(); i++) {
                if (isFound(key, i) == found) continue;
                BlockPos pos = souls.positions.get(i);
                double d = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (d < best) {
                    best = d;
                    bestKey = key;
                    bestIndex = i;
                }
            }
        }
        if (bestKey == null) {
            Sky2MAlerts.chat(Component.literal(found ? "No missing Fairy Soul within 64 blocks."
                : "No marked Fairy Soul within 64 blocks.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        setFound(bestKey, bestIndex, found);
        Sky2MAlerts.chat(statusLine(bestKey));
    }

    /** /s2 souls markall and unmarkall: every soul of the island you're on. */
    private static void changeAll(boolean found) {
        if (!enabled()) {
            Sky2MAlerts.chat(Component.literal("Fairy Souls is disabled. Turn it on in /s2 → Misc → Waypoints, Trackers & Sounds → Fairy Souls.").withStyle(ChatFormatting.RED));
            return;
        }
        List<String> keys = matchingKeys();
        if (keys.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("Fairy Soul positions aren't loaded for this area.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        int marked = 0;
        for (String key : keys) {
            Souls souls;
            synchronized (DATA) {
                souls = DATA.get(key);
            }
            if (souls != null) marked += found ? souls.total() - found(key) : found(key);
            List<Integer> list = FOUND.computeIfAbsent(key, k -> new ArrayList<>());
            if (found) {
                for (int i = 0; souls != null && i < souls.positions.size(); i++) if (!list.contains(i)) list.add(i);
            } else {
                list.clear();
            }
        }
        save();
        Sky2MAlerts.chat(Component.literal((found ? "Marked all missing Fairy Souls here (" : "Cleared the marks here (") + marked + " changed).").withStyle(ChatFormatting.GREEN));
        list();
    }

    // ------------------------------------------------------------------------------------------------ the world

    /** The missing souls near you, nearest first, like SkyHanni's route to the next one. */
    private static List<SoulAt> nearestSouls(Vec3 player, List<String> keys, int limit) {
        List<SoulAt> out = new ArrayList<>();
        synchronized (DATA) {
            for (String key : keys) {
                Souls souls = DATA.get(key);
                if (souls == null) continue;
                for (int i = 0; i < souls.positions.size(); i++) {
                    if (isFound(key, i)) continue;
                    BlockPos pos = souls.positions.get(i);
                    Vec3 centre = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    double d = player.distanceToSqr(centre);
                    if (d > 200 * 200) continue;
                    out.add(new SoulAt(key, i, pos, centre, d));
                }
            }
        }
        out.sort(Comparator.comparingDouble(s -> s.distance));
        return out.subList(0, Math.min(limit, out.size()));
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.FairySouls c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.enabled || !dataLoaded || mc.player == null) return;
        List<String> keys = matchingKeys();
        if (keys.isEmpty()) return;
        Vec3 player = mc.player.position();
        List<SoulAt> souls = nearestSouls(player, keys, Math.max(1, c.nearbySouls));
        if (souls.isEmpty()) return;
        for (int j = 0; j < souls.size(); j++) {
            SoulAt soul = souls.get(j);
            boolean nearest = j == 0;
            int colour = nearest ? 0xFFA839CE : ChromaColours.parse(c.faded).getEffectiveColourRGB();
            AABB box = new AABB(soul.pos.getX() - 0.3, soul.pos.getY() - 0.3, soul.pos.getZ() - 0.3,
                soul.pos.getX() + 1.3, soul.pos.getY() + 1.3, soul.pos.getZ() + 1.3);
            float[] rgb = {((colour >> 16) & 0xFF) / 255f, ((colour >> 8) & 0xFF) / 255f, (colour & 0xFF) / 255f};
            float alpha = Math.max(0.25f, ((colour >>> 24) & 0xFF) / 255f);
            collector.submitOutlinedBox(box, rgb, alpha, 2f, c.throughWalls);
            if (c.labels) {
                MutableComponent label = Component.literal("Fairy Soul").withStyle(nearest ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_PURPLE);
                if (c.distances) label.append(Component.literal(" " + (int) Math.sqrt(soul.distance) + "m").withStyle(ChatFormatting.GRAY));
                collector.submitText(label, new Vec3(soul.centre.x, box.maxY + 0.4, soul.centre.z), 1f, c.throughWalls);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ chat & HUD

    /** On joining the island, like SkyHanni's NEU-Souls Pathfinder: the souls found so far in chat. */
    private static void onAreaChange(String area) {
        if (!dataLoaded) return;
        List<String> keys = matchingKeys();
        if (keys.isEmpty()) return;
        FeatureConfigs.FairySouls c = config();
        if (c != null && !c.enabled) return;
        if (c == null || c.progress) {
            for (String key : keys) Sky2MAlerts.chat(statusLine(key));
        }
    }

    /** Hypixel tells you when a soul is collected: mark the nearest missing soul. */
    private static void onChat(Sky2MChat.Message message) {
        FeatureConfigs.FairySouls c = config();
        String text = message.text();
        if (c == null || !c.enabled || !dataLoaded) return;
        if (!SOUL_PICKUP.matcher(text).find()) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("found") && !lower.contains("obtained") && !lower.contains("collected")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 player = mc.player.position();
        String bestKey = null;
        Integer bestIndex = null;
        double best = 100 * 100;
        for (String key : matchingKeys()) {
            Souls souls;
            synchronized (DATA) {
                souls = DATA.get(key);
            }
            if (souls == null) continue;
            for (int i = 0; i < souls.positions.size(); i++) {
                if (isFound(key, i)) continue;
                BlockPos pos = souls.positions.get(i);
                double d = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (d < best) {
                    best = d;
                    bestKey = key;
                    bestIndex = i;
                }
            }
        }
        if (bestKey != null) {
            setFound(bestKey, bestIndex, true);
            Sky2MAlerts.chat(statusLine(bestKey));
        }
    }

    private static void tick(Minecraft mc) {
        if (!enabled() || !dataLoaded || ++ticksSinceHud % 10 != 0) return;
        List<Component> out = new ArrayList<>();
        List<String> keys = matchingKeys();
        int total = 0, got = 0;
        for (String key : keys) {
            total += DATA.get(key).total();
            got += found(key);
        }
        if (total > 0) {
            out.add(Component.literal("Fairy Souls " + got + "/" + total + " — ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(Sky2MLocation.area()).withStyle(ChatFormatting.AQUA)));
            int markedAll = 0;
            for (String key : FOUND.keySet()) markedAll += found(key);
            out.add(Component.literal(markedAll + " found in total").withStyle(ChatFormatting.GRAY));
        }
        hudLines = out;
    }

    /** A "Fairy Souls 45/81 — Hub" line for the HUD and chat replies. */
    private static Component statusLine(String key) {
        Souls souls;
        synchronized (DATA) {
            souls = DATA.get(key);
        }
        int total = souls == null ? 0 : souls.total();
        int got = found(key);
        boolean done = total > 0 && got >= total;
        MutableComponent line = Component.literal("Fairy Souls: ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(got + "/" + total).withStyle(done ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
            .append(Component.literal(" — " + islandName(key)).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (done) line.append(Component.literal(" done!").withStyle(ChatFormatting.GREEN));
        return line;
    }

    /** The whole list of islands with your own marks, the one you're on first. */
    private static void list() {
        synchronized (DATA) {
            if (!dataLoaded) {
                Sky2MAlerts.chat(Component.literal("Fairy Soul coordinates aren't loaded yet (they come in from the NEU repo).").withStyle(ChatFormatting.YELLOW));
                return;
            }
            List<String> top = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            String area = Sky2MLocation.onSkyblock() ? Sky2MLocation.area().toLowerCase(Locale.ROOT) : "";
            for (String key : DATA.keySet()) {
                List<String> names = ISLANDS.getOrDefault(key, List.of(key));
                boolean current = false;
                for (String name : names) if (area.equals(name.toLowerCase(Locale.ROOT))) current = true;
                (current ? top : rest).add(key);
            }
            top.addAll(rest);
            for (String key : top) Sky2MAlerts.chat(statusLine(key));
        }
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static void load() {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> island : root.getAsJsonObject("islands").entrySet()) {
                List<Integer> marked = new ArrayList<>();
                for (JsonElement e : island.getValue().getAsJsonArray()) marked.add(e.getAsInt());
                FOUND.put(island.getKey(), marked);
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            JsonObject islands = new JsonObject();
            synchronized (DATA) {
                for (Map.Entry<String, List<Integer>> e : FOUND.entrySet()) {
                    JsonArray array = new JsonArray();
                    for (int i : e.getValue()) array.add(i);
                    islands.add(e.getKey(), array);
                }
            }
            root.add("islands", islands);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
