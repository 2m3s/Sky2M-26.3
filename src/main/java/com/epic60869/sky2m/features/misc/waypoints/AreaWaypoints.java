package com.epic60869.sky2m.features.misc.waypoints;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Misc > Area Waypoints, ported from Odin's Waypoints (BSD-3-Clause, https://github.com/odtheking/Odin,
 * features/impl/render/waypoints/): waypoints that only show in one area (a dungeon boss phase, a Kuudra tier or an
 * island), drawn as a box or a circle with a label. A Proximity waypoint runs its command once when you walk into it;
 * a Click waypoint when you hit or use a block inside it. Each runs one command per thing you do, like typing it.
 * Kept in {@code sky2m/area-waypoints.json}; /s2w opens the manager.
 */
public final class AreaWaypoints {
    public enum Trigger {
        VISUAL("Visual"), PROXIMITY("Proximity"), CLICK("Click");

        public final String label;

        Trigger(String label) {
            this.label = label;
        }
    }

    public enum Group {
        DUNGEON("Dungeon"), KUUDRA("Kuudra"), ISLANDS("Islands");

        public final String title;

        Group(String title) {
            this.title = title;
        }
    }

    public record Area(String key, String label, Group group) {}

    /** One waypoint. Corners are block positions; an "At" waypoint has both corners the same and a radius. */
    public static final class Waypoint {
        public String area = "";
        public double x, y, z, ex, ey, ez;
        public String label = "";
        public int color = 0xFF55FF55;
        public Trigger trigger = Trigger.VISUAL;
        public String command;
        public Float radius;
        public boolean enabled = true;
        transient boolean inside;

        String id() {
            return area + ":" + trigger.ordinal() + ":" + Math.min(x, ex) + "," + Math.min(y, ey) + "," + Math.min(z, ez)
                + "-" + Math.max(x, ex) + "," + Math.max(y, ey) + "," + Math.max(z, ez);
        }

        public boolean isArea() {
            return x != ex || y != ey || z != ez;
        }

        AABB box() {
            AABB base = new AABB(Math.min(x, ex), Math.min(y, ey), Math.min(z, ez), Math.max(x, ex) + 1, Math.max(y, ey) + 1, Math.max(z, ez) + 1);
            return radius == null ? base : base.inflate(radius, 0, radius);
        }

        boolean intersects(AABB hitbox) {
            AABB box = box();
            if (radius == null) return box.intersects(hitbox);
            if (hitbox.minY < box.minY || hitbox.minY >= box.maxY) return false;
            Vec3 c = box.getCenter();
            double dx = Math.clamp(c.x, hitbox.minX, hitbox.maxX) - c.x, dz = Math.clamp(c.z, hitbox.minZ, hitbox.maxZ) - c.z;
            return dx * dx + dz * dz <= radius * radius;
        }

        void fire() {
            Minecraft mc = Minecraft.getInstance();
            if (command != null && !command.isBlank() && mc.getConnection() != null) mc.getConnection().sendCommand(command.trim());
        }
    }

    private static final Pattern KUUDRA_TIER = Pattern.compile("\\(T(\\d)\\)");
    private static final List<String> ISLANDS = List.of("Private Island", "Hub", "The Park", "The Farming Islands", "Garden", "Gold Mine",
        "Deep Caverns", "Dwarven Mines", "Crystal Hollows", "Mineshaft", "Spider's Den", "The End", "Crimson Isle", "The Rift",
        "Backwater Bayou", "Galatea", "Jerry's Workshop", "Dungeon Hub", "Dark Auction");
    private static final Map<Integer, List<String>> BOSS_PHASES = Map.of(
        6, List.of("Terracottas", "Giants", "Cleared"),
        7, List.of("Maxor", "Storm", "Terminals", "Goldor", "Necron", "Cleared"));
    /** The boss lines that start each F6 and F7 phase (the same ones as Sky2M's splits). */
    private static final Map<String, String> PHASE_LINES = Map.of(
        "[BOSS] Sadan: So you made it all the way here... Now you wish to defy me? Sadan?!", "Terracottas",
        "[BOSS] Sadan: ENOUGH!", "Giants",
        "[BOSS] Sadan: You did it. I understand now, you have earned my respect.", "Cleared",
        "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!", "Maxor",
        "[BOSS] Storm: Pathetic Maxor, just like expected.", "Storm",
        "[BOSS] Goldor: Who dares trespass into my domain?", "Terminals",
        "The Core entrance is opening!", "Goldor",
        "[BOSS] Necron: You went further than any human before, congratulations.", "Necron",
        "[BOSS] Necron: All this, for nothing...", "Cleared",
        // Since Hypixel's 2026 dungeon update Necron's last line is gone and the Wither King's first line comes instead.
        "[BOSS] Wither King: You... again?", "Cleared");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<Area> AREAS = new ArrayList<>();
    private static final List<Waypoint> waypoints = new ArrayList<>();
    private static Path file;
    private static String phase;
    private static String activeKey;
    private static int revision;

    static {
        for (int floor = 1; floor <= 5; floor++) AREAS.add(new Area("FLOOR_" + floor, "F" + floor + " / M" + floor + " Boss", Group.DUNGEON));
        for (int floor : new int[]{6, 7}) {
            for (String p : BOSS_PHASES.get(floor)) AREAS.add(new Area("F" + floor + "_" + p.toUpperCase(Locale.ROOT), "F" + floor + " " + p, Group.DUNGEON));
        }
        for (int tier = 1; tier <= 5; tier++) AREAS.add(new Area("KUUDRA_T" + tier, "Kuudra T" + tier, Group.KUUDRA));
        for (String island : ISLANDS) AREAS.add(new Area(islandKey(island), island, Group.ISLANDS));
    }

    private AreaWaypoints() {}

    private static FeatureConfigs.AreaWaypoints config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.areaWaypoints;
    }

    private static String islandKey(String island) {
        return "ISLAND_" + island.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("area-waypoints.json");
        load();
        Sky2MLocation.onAreaChange(area -> {
            phase = null;
            for (Waypoint wp : waypoints) wp.inside = false;
        });
        Sky2MChat.onChat(message -> {
            String p = PHASE_LINES.get(message.text().trim());
            if (p != null && Sky2MLocation.inDungeon()) phase = p;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        Sky2MWorldRender.register(AreaWaypoints::render);
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide()) onClick(pos);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) onClick(hit.getBlockPos());
            return InteractionResult.PASS;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            dispatcher.register(ClientCommands.literal("s2w").executes(c -> open()));
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("wp").executes(c -> open())));
            }
        });
    }

    /** The /s2w command and the Open Manager button. */
    public static int open() {
        Compat.queueOpenScreen(new WaypointsScreen(null));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ areas

    /** Every area, with the island you're on added when it isn't one of the usual ones. */
    public static List<Area> areas() {
        List<Area> out = new ArrayList<>(AREAS);
        String island = Sky2MLocation.area();
        if (!island.isEmpty() && !Sky2MLocation.inDungeon() && !island.equals("Kuudra") && byKey(islandKey(island)) == null) {
            out.add(new Area(islandKey(island), island, Group.ISLANDS));
        }
        for (Waypoint wp : waypoints) {
            if (out.stream().noneMatch(a -> a.key().equals(wp.area))) out.add(new Area(wp.area, wp.area, Group.ISLANDS));
        }
        return out;
    }

    public static Area byKey(String key) {
        for (Area a : AREAS) if (a.key().equals(key)) return a;
        return null;
    }

    public static String label(String key) {
        for (Area a : areas()) if (a.key().equals(key)) return a.label();
        return key;
    }

    /** The area you're in, or null (a dungeon outside its boss room has none). */
    public static String currentKey() {
        if (!Sky2MLocation.onSkyblock()) return null;
        if (Sky2MLocation.inDungeon()) {
            if (!DungeonManager.isInBoss()) return null;
            String floor = Sky2MLocation.dungeonFloor();
            if (floor.isEmpty() || !Character.isDigit(floor.charAt(floor.length() - 1))) return null;
            int n = floor.charAt(floor.length() - 1) - '0';
            if (n >= 1 && n <= 5) return "FLOOR_" + n;
            List<String> phases = BOSS_PHASES.get(n);
            if (phases == null) return null;
            String p = phase != null && phases.contains(phase) ? phase : phases.getFirst();
            return "F" + n + "_" + p.toUpperCase(Locale.ROOT);
        }
        if (Sky2MLocation.areaIs("Kuudra")) {
            for (String line : Sky2MLocation.scoreboard()) {
                Matcher m = KUUDRA_TIER.matcher(line);
                if (m.find()) return "KUUDRA_T" + m.group(1);
            }
            return null;
        }
        String island = Sky2MLocation.area();
        return island.isEmpty() ? null : islandKey(island);
    }

    // ------------------------------------------------------------------------------------------------ list

    public static List<Waypoint> in(String area) {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint wp : waypoints) if (wp.area.equals(area)) out.add(wp);
        return out;
    }

    public static int count(String area) {
        int n = 0;
        for (Waypoint wp : waypoints) if (wp.area.equals(area)) n++;
        return n;
    }

    public static int revision() {
        return revision;
    }

    /** Adds {@code wp}, or puts it in {@code replacing}'s place; an error message, or null. */
    public static String submit(Waypoint wp, Waypoint replacing) {
        for (Waypoint other : waypoints) if (other != replacing && other.id().equals(wp.id())) return "There is already a waypoint here.";
        int index = waypoints.indexOf(replacing);
        if (index < 0) waypoints.add(wp);
        else waypoints.set(index, wp);
        changed();
        return null;
    }

    public static void toggle(Waypoint wp) {
        wp.enabled = !wp.enabled;
        changed();
    }

    public static void remove(Waypoint wp) {
        waypoints.remove(wp);
        changed();
    }

    private static void changed() {
        revision++;
        save();
    }

    public static String exportToClipboard() {
        if (waypoints.isEmpty()) return "§cYou don't have any waypoints to export.";
        JsonArray array = new JsonArray();
        for (Waypoint wp : waypoints) array.add(toJson(wp));
        Minecraft.getInstance().keyboardHandler.setClipboard(GSON.toJson(array));
        return "§aExported §f" + waypoints.size() + " §awaypoints to the clipboard.";
    }

    public static String importFromClipboard() {
        List<Waypoint> found = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(Minecraft.getInstance().keyboardHandler.getClipboard());
            for (JsonElement e : root.getAsJsonArray()) found.add(fromJson(e.getAsJsonObject()));
        } catch (Exception e) {
            return "§cNo waypoints found in your clipboard.";
        }
        Set<String> ids = new HashSet<>();
        for (Waypoint wp : waypoints) ids.add(wp.id());
        int added = 0;
        for (Waypoint wp : found) {
            if (!ids.add(wp.id())) continue;
            waypoints.add(wp);
            added++;
        }
        if (added == 0) return "§cNothing was imported, every waypoint already exists.";
        changed();
        return "§aImported §f" + added + " §awaypoints." + (added < found.size() ? " §7(" + (found.size() - added) + " skipped)" : "");
    }

    // ------------------------------------------------------------------------------------------------ in the world

    private static void tick(Minecraft mc) {
        FeatureConfigs.AreaWaypoints c = config();
        if (c == null || !c.enabled || mc.player == null || waypoints.isEmpty()) return;
        String key = currentKey();
        if (key == null) return;
        if (!key.equals(activeKey)) {
            // Arriving somewhere doesn't fire the waypoints you're already standing in.
            activeKey = key;
            for (Waypoint wp : waypoints) if (wp.area.equals(key)) wp.inside = wp.intersects(mc.player.getBoundingBox());
            return;
        }
        AABB hitbox = mc.player.getBoundingBox();
        for (Waypoint wp : waypoints) {
            if (!wp.enabled || wp.trigger != Trigger.PROXIMITY || !wp.area.equals(key)) continue;
            boolean in = wp.intersects(hitbox);
            if (in && !wp.inside) wp.fire();
            wp.inside = in;
        }
    }

    private static void onClick(BlockPos pos) {
        FeatureConfigs.AreaWaypoints c = config();
        String key = c != null && c.enabled ? currentKey() : null;
        if (key == null) return;
        AABB block = new AABB(pos);
        for (Waypoint wp : waypoints) {
            if (wp.enabled && wp.trigger == Trigger.CLICK && wp.area.equals(key) && wp.box().intersects(block)) {
                wp.fire();
                return;
            }
        }
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.AreaWaypoints c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.enabled || mc.player == null || waypoints.isEmpty()) return;
        String key = currentKey();
        if (key == null) return;
        Vec3 pos = mc.player.position();
        for (Waypoint wp : waypoints) {
            if (!wp.enabled || !wp.area.equals(key)) continue;
            AABB box = wp.box();
            if (box.distanceToSqr(pos) > 128 * 128) continue;
            float[] rgb = {((wp.color >> 16) & 0xFF) / 255f, ((wp.color >> 8) & 0xFF) / 255f, (wp.color & 0xFF) / 255f};
            float alpha = Math.max(0.2f, ((wp.color >>> 24) & 0xFF) / 255f);
            Vec3 centre = box.getCenter();
            if (wp.radius != null) collector.submitOutlinedCircle(new Vec3(centre.x, box.minY + 0.05, centre.z), wp.radius + 0.5f, 0.1f, 48, wp.color | 0xFF000000);
            else collector.submitOutlinedBox(box, rgb, alpha, 2f, c.throughWalls);
            if (!wp.label.isEmpty()) collector.submitText(Component.literal(wp.label), new Vec3(centre.x, box.maxY + 0.5, centre.z), c.textScale, c.throughWalls);
        }
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static JsonObject vec(double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    /** Odin's field names, so waypoints can go between Sky2M and Odin. */
    private static JsonObject toJson(Waypoint wp) {
        JsonObject o = new JsonObject();
        o.addProperty("area", wp.area);
        o.add("blockPos", vec(wp.x, wp.y, wp.z));
        o.add("endPos", vec(wp.ex, wp.ey, wp.ez));
        o.addProperty("label", wp.label);
        o.addProperty("color", String.format("#%08X", wp.color));
        o.addProperty("trigger", wp.trigger.name());
        if (wp.command != null) o.addProperty("command", wp.command);
        if (wp.radius != null) o.addProperty("radius", wp.radius);
        o.addProperty("enabled", wp.enabled);
        return o;
    }

    private static Waypoint fromJson(JsonObject o) {
        Waypoint wp = new Waypoint();
        wp.area = o.get("area").getAsString();
        JsonObject a = o.getAsJsonObject("blockPos");
        JsonObject b = o.has("endPos") ? o.getAsJsonObject("endPos") : a;
        wp.x = a.get("x").getAsDouble();
        wp.y = a.get("y").getAsDouble();
        wp.z = a.get("z").getAsDouble();
        wp.ex = b.get("x").getAsDouble();
        wp.ey = b.get("y").getAsDouble();
        wp.ez = b.get("z").getAsDouble();
        if (o.has("label")) wp.label = o.get("label").getAsString();
        if (o.has("color")) wp.color = colour(o.get("color"));
        if (o.has("trigger")) {
            try {
                wp.trigger = Trigger.valueOf(o.get("trigger").getAsString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }
        if (o.has("command") && !o.get("command").isJsonNull()) wp.command = o.get("command").getAsString();
        if (o.has("radius") && !o.get("radius").isJsonNull()) wp.radius = o.get("radius").getAsFloat();
        if (o.has("enabled")) wp.enabled = o.get("enabled").getAsBoolean();
        return wp;
    }

    /** "#AARRGGBB", a number, or an object with r, g, b (and a) like Odin's colours. */
    private static int colour(JsonElement e) {
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return (int) Long.parseLong(e.getAsString().replace("#", ""), 16);
            if (e.isJsonPrimitive()) return e.getAsInt();
            JsonObject o = e.getAsJsonObject();
            if (o.has("rgba")) return o.get("rgba").getAsInt();
            int r = o.get("r").getAsInt(), g = o.get("g").getAsInt(), b = o.get("b").getAsInt();
            int alpha = o.has("a") ? (int) (o.get("a").getAsFloat() <= 1 ? o.get("a").getAsFloat() * 255 : o.get("a").getAsFloat()) : 255;
            return (alpha << 24) | (r << 16) | (g << 8) | b;
        } catch (Exception ex) {
            return 0xFF55FF55;
        }
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            for (JsonElement e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                try {
                    waypoints.add(fromJson(e.getAsJsonObject()));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            JsonArray array = new JsonArray();
            for (Waypoint wp : waypoints) array.add(toJson(wp));
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(array), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
