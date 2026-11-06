package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 tracker: every dungeon chest you open from the moment the tracker is turned on, its loot counted per item with
 * the most expensive at the top ({@link DungeonDropScreen}). It starts empty; nothing from before is imported. Chests
 * are copied from Sky2M's Chest History as each is saved there (so Hypixel refusing a chest is already handled), with
 * the worth the loot had when you claimed it and what the chest cost (coins, key, Kismet Feather), so the screen shows
 * your profit. Needs Sky2M's Chest History on. Saved to config/sky2m/drop-tracker.json and never re-priced.
 */
public final class DungeonDropTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern COUNT = Pattern.compile("^(?<n>\\d+)x\\s+(?<name>.+)$|^(?<name2>.+?)\\s+x(?<n2>[\\d,]+)$");
    static Path file;

    /** One item from a chest. */
    public static final class Drop {
        public long time;
        public String floor = "?";
        /** "Chest"; saves from when mob drops were tracked also have "Mob" ones, which are dropped on load. */
        public String source = "Chest";
        public String name = "";
        public String id = "";
        public int count = 1;
        /** Worth when it dropped, all of them together. */
        public double value;
    }

    /** What one chest cost to open: its coins, the Dungeon Chest Key and a Kismet Feather, at the time. */
    public static final class Cost {
        public long time;
        public String floor = "?";
        public double coins, key, kismet;

        public double total() {
            return coins + key + kismet;
        }
    }

    private static final class Save {
        /** When tracking started; chests saved before this aren't counted. */
        long since;
        /** The newest Chest History chest already copied. */
        long lastChest;
        List<Drop> drops = new ArrayList<>();
        List<Cost> costs = new ArrayList<>();
        /** False in saves from before costs were kept: their chests' costs are taken from Chest History once. */
        boolean costsKept;
    }

    private static Save save;
    private static long chestFileModified;
    private static boolean openScreen;
    private static int ticks;
    /** When /s2 tracker reset was typed; its chat button only works for a minute after. */
    private static long resetAskedAt;

    private DungeonDropTracker() {}

    static boolean on() {
        var c = TrackLines.config();
        return c != null && c.dropTracker;
    }

    public static void init() {
        file = TrackLines.movedFrom(DungeonProfitHour.DIR.resolve("sky2m").resolve("drop-tracker.json"),
            DungeonProfitHour.DIR.resolve("sky2m-personal").resolve("dungeon-drops.json"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root).then(command()));
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (openScreen) {
                openScreen = false;
                mc.gui.setScreen(new DungeonDropScreen(null));
            }
            if (++ticks % 40 == 0 && on()) copyChests();
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> command() {
        return ClientCommands.literal("tracker").executes(ctx -> {
                if (!on()) {
                    ctx.getSource().sendFeedback(Component.literal("§cTurn on Drop Tracker" + DungeonProfitHour.TURN_ON));
                    return 0;
                }
                openScreen = true;
                return 1;
            }).then(ClientCommands.literal("reset")
                // Not a button on the screen, so a misclick can't wipe it: this asks in chat, and only the chat
                // button (within a minute) resets.
                .executes(ctx -> {
                    resetAskedAt = System.currentTimeMillis();
                    ctx.getSource().sendFeedback(Component.literal("§eForget every dungeon chest the Drop Tracker counted? ")
                        .append(Component.literal("§c§l[Click to reset]").withStyle(s -> s
                            .withClickEvent(new ClickEvent.RunCommand("/s2 tracker reset confirm"))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("§cCan't be undone. Slayer, Fishing and the other tabs are Sky2M's trackers and aren't touched."))))));
                    return 1;
                })
                .then(ClientCommands.literal("confirm").executes(ctx -> {
                    if (System.currentTimeMillis() - resetAskedAt > 60_000) {
                        ctx.getSource().sendFeedback(Component.literal("§7Type §f/s2 tracker reset §7first, then click the button in chat."));
                        return 0;
                    }
                    resetAskedAt = 0;
                    reset();
                    ctx.getSource().sendFeedback(Component.literal("§aDrop Tracker reset: it counts dungeon chests from now."));
                    return 1;
                })));
    }

    private static Save data() {
        if (save == null) {
            try {
                if (file != null && Files.exists(file)) save = GSON.fromJson(Files.readString(file), Save.class);
            } catch (Exception e) {
                TrackLines.LOG.warn("Dungeon Drop Tracker couldn't read {}: {}", file, e.toString());
            }
            if (save == null) {
                save = new Save();
                save.since = save.lastChest = System.currentTimeMillis();
                save.costsKept = true;
            }
            if (save.drops == null) save.drops = new ArrayList<>();
            if (save.costs == null) save.costs = new ArrayList<>();
            // Mob drops aren't tracked any more.
            if (save.drops.removeIf(d -> "Mob".equals(d.source))) write();
            if (!save.costsKept) {
                save.costsKept = true;
                for (JsonElement e : DungeonProfitHour.read(DungeonProfitHour.chestFile())) {
                    if (!e.isJsonObject()) continue;
                    long time = (long) number(e.getAsJsonObject(), "time");
                    if (time >= save.since && time <= save.lastChest) save.costs.add(cost(e.getAsJsonObject(), time));
                }
                write();
            }
        }
        return save;
    }

    private static void write() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data()));
        } catch (Exception e) {
            TrackLines.LOG.warn("Dungeon Drop Tracker couldn't save {}: {}", file, e.toString());
        }
    }

    public static List<Drop> drops() {
        return data().drops;
    }

    public static List<Cost> costs() {
        return data().costs;
    }

    public static long since() {
        return data().since;
    }

    /** Forgets every chest and starts counting again from now. */
    public static void reset() {
        Save s = data();
        s.drops.clear();
        s.costs.clear();
        s.since = s.lastChest = System.currentTimeMillis();
        write();
    }

    // ------------------------------------------------------------------------------------------------ capture

    /** Copies the chests Sky2M's Chest History saved since last time. */
    private static void copyChests() {
        Path chests = DungeonProfitHour.chestFile();
        long modified = chests.toFile().lastModified();
        if (modified == 0 || modified == chestFileModified) return;
        chestFileModified = modified;
        Save s = data();
        if (addChests(s, DungeonProfitHour.read(chests)) > 0) write();
    }

    /** Adds every chest newer than the last one copied (and not from before tracking started). Returns how many. */
    static int addChests(Save s, JsonArray chests) {
        long newest = s.lastChest;
        int added = 0;
        for (JsonElement e : chests) {
            if (!e.isJsonObject()) continue;
            JsonObject chest = e.getAsJsonObject();
            long time = (long) number(chest, "time");
            if (time <= s.lastChest || time < s.since) continue;
            newest = Math.max(newest, time);
            added++;
            s.costs.add(cost(chest, time));
            if (!chest.has("items") || !chest.get("items").isJsonArray()) continue;
            for (JsonElement i : chest.getAsJsonArray("items")) {
                if (!i.isJsonObject()) continue;
                JsonObject item = i.getAsJsonObject();
                Drop d = new Drop();
                d.time = time;
                d.floor = string(chest, "floor").isEmpty() ? "?" : string(chest, "floor");
                d.source = "Chest";
                d.id = string(item, "id");
                d.count = Math.max(1, (int) number(item, "count"));
                d.name = clean(string(item, "name"));
                d.value = number(item, "value");
                s.drops.add(d);
            }
        }
        s.lastChest = newest;
        return added;
    }

    private static Cost cost(JsonObject chest, long time) {
        Cost c = new Cost();
        c.time = time;
        c.floor = string(chest, "floor").isEmpty() ? "?" : string(chest, "floor");
        c.coins = number(chest, "coins");
        c.key = number(chest, "keyCost");
        c.kismet = number(chest, "kismetCost");
        return c;
    }

    /** What the chests on one floor (null = all) cost to open, all together. */
    public static double spent(List<Cost> costs, String floor) {
        double total = 0;
        for (Cost c : costs) if (floor == null || floor.equalsIgnoreCase(c.floor)) total += c.total();
        return total;
    }

    /** "Wither Essence x20" -> "Wither Essence"; the count is saved on its own. */
    private static String clean(String name) {
        Matcher c = COUNT.matcher(name.trim());
        if (!c.matches()) return name.trim();
        return c.group("name") != null ? c.group("name") : c.group("name2");
    }

    // ------------------------------------------------------------------------------------------------ totals

    /** One item's totals: how many, in how many chests, their worth, and the last time. */
    public record Row(String name, String id, int count, int times, double value, long last) {
        /** What one is worth (the worth when they dropped), for RNG only. */
        public double unit() {
            return count == 0 ? 0 : value / count;
        }
    }

    /**
     * The drops on one floor (null = all) whose name has the search in it, one row per item, the highest total worth
     * first (7 Chimeras at 189M above 1 Braided Griffin Feather at 27M); items worth the same (or nothing) by how many.
     */
    public static List<Row> rows(List<Drop> drops, String floor, String search) {
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Map<String, Row> by = new LinkedHashMap<>();
        for (Drop d : drops) {
            if (floor != null && !floor.equalsIgnoreCase(d.floor)) continue;
            String name = displayName(d.name, d.id);
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
            // Generic names ("Enchanted Book") are told apart by id.
            String key = d.name.equalsIgnoreCase("Enchanted Book") && !d.id.isEmpty() ? d.id : name.toLowerCase(Locale.ROOT);
            Row r = by.get(key);
            by.put(key, r == null
                ? new Row(name, d.id, d.count, 1, d.value, d.time)
                : new Row(r.name, r.id.isEmpty() ? d.id : r.id, r.count + d.count, r.times + 1, r.value + d.value, Math.max(r.last, d.time)));
        }
        List<Row> out = new ArrayList<>(by.values());
        // By the worth shown on the row (all of them together), not one's price, so the list reads top-down.
        out.sort(Comparator.comparingDouble(Row::value).reversed().thenComparing(Comparator.comparingInt(Row::count).reversed()).thenComparing(Row::name));
        return out;
    }

    /** Ultimate enchants whose name keeps "Ultimate" (the rest drop it: ULTIMATE_LEGION is "Legion"). */
    private static final java.util.Set<String> KEEPS_ULTIMATE = java.util.Set.of("WISE", "JERRY", "REITERATE");
    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /**
     * What to call a drop: an enchanted book by its enchant ("ENCHANTMENT_ULTIMATE_LEGION_1" -> "Legion I"), as Chest
     * History saves books as just "Enchanted Book"; anything else by its own name.
     */
    public static String displayName(String name, String id) {
        if (!name.equalsIgnoreCase("Enchanted Book") || id == null || !id.startsWith("ENCHANTMENT_")) return name;
        String rest = id.substring("ENCHANTMENT_".length());
        String level = "";
        int cut = rest.lastIndexOf('_');
        if (cut > 0 && rest.substring(cut + 1).chars().allMatch(Character::isDigit)) {
            int n = Integer.parseInt(rest.substring(cut + 1));
            level = " " + (n > 0 && n < ROMAN.length ? ROMAN[n] : String.valueOf(n));
            rest = rest.substring(0, cut);
        }
        if (rest.startsWith("ULTIMATE_") && !KEEPS_ULTIMATE.contains(rest.substring("ULTIMATE_".length()))) rest = rest.substring("ULTIMATE_".length());
        StringBuilder out = new StringBuilder();
        for (String word : rest.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out + level;
    }

    /** The floors you've opened chests on, in dungeon order (E, F1-F7, M1-M7, then anything else). */
    public static List<String> floors(List<Cost> chests) {
        List<String> order = new ArrayList<>(List.of("E", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"));
        List<String> out = new ArrayList<>();
        for (String f : order) if (chests.stream().anyMatch(c -> f.equalsIgnoreCase(c.floor))) out.add(f);
        for (Cost c : chests) if (!order.contains(c.floor.toUpperCase(Locale.ROOT)) && !out.contains(c.floor)) out.add(c.floor);
        return out;
    }

    /** How many chests you've opened on one floor (null = all). */
    public static int chests(List<Cost> chests, String floor) {
        int n = 0;
        for (Cost c : chests) if (floor == null || floor.equalsIgnoreCase(c.floor)) n++;
        return n;
    }

    private static double number(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsDouble();
    }

    private static String string(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** For the game test: a fresh, unsaved tracker started at {@code since}; the drops, and the costs into {@code costs}. */
    public static List<Drop> testAdd(long since, JsonArray chests, List<Cost> costs) {
        Save s = new Save();
        s.since = s.lastChest = since;
        addChests(s, chests);
        costs.addAll(s.costs);
        return s.drops;
    }
}
