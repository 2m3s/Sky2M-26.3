package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Mining > Ordered Waypoints, ported from SkyHanni's OrderedWaypoints (LGPL-2.1,
 * https://github.com/hannibal002/SkyHanni, features/mining/OrderedWaypoints.kt and data/model/waypoints/), which is
 * itself from Coleweight and SoopyV2. A route is a numbered list of block waypoints: the previous, current and next
 * few are drawn, and reaching the next one moves the route along. Routes load from the clipboard in Coleweight's or
 * Skytils' format and can be saved by name in {@code sky2m/ordered-waypoints.json}. Commands are SkyHanni's: /sho
 * (or /shordered, and /s2 sho when SkyHanni itself is installed).
 */
public final class OrderedWaypoints {
    private static final String SKYTILS_V1 = "<Skytils-Waypoint-Data>(V1):";
    private static final Pattern SHAFT = Pattern.compile("\\b([A-Z]{4}_[0-9C])\\b");
    private static final double SNEAK_EYE = 1.27, STAND_EYE = 1.62;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One waypoint; {@code options} keeps what the route's format had (Coleweight's "name"). */
    private static final class Wp {
        int x, y, z;
        int number;
        Map<String, String> options = new LinkedHashMap<>();

        /** For Gson. */
        @SuppressWarnings("unused") // Gson creates these, so options starts as an empty map
        Wp() {}

        Wp(int x, int y, int z, int number) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.number = number;
            options.put("name", String.valueOf(number));
        }

        Vec3 centre() {
            return new Vec3(x + 0.5, y + 0.5, z + 0.5);
        }

        double distance() {
            Minecraft mc = Minecraft.getInstance();
            return mc.player == null ? Double.MAX_VALUE : Math.sqrt(mc.player.distanceToSqr(x + 0.5, y + 0.5, z + 0.5));
        }

        Wp copy() {
            Wp w = new Wp(x, y, z, number);
            w.options = new LinkedHashMap<>(options);
            return w;
        }
    }

    private static List<Wp> route = new ArrayList<>();
    private static final List<Integer> shown = new ArrayList<>();
    private static int current, lastCloser;
    private static Map<String, List<Wp>> saved = new LinkedHashMap<>();
    private static Path file;
    private static int shaftCheckTicks = -1;

    private OrderedWaypoints() {}

    private static FeatureConfigs.OrderedWaypoints config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.orderedWaypoints;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("ordered-waypoints.json");
        load();
        Sky2MWorldRender.register(OrderedWaypoints::render);
        Sky2MLocation.onAreaChange(area -> {
            FeatureConfigs.OrderedWaypoints c = config();
            current = 0;
            if (c != null && c.autoUnload) unload(false);
            if (c != null && c.autoLoadShaft && "Mineshaft".equals(area)) shaftCheckTicks = 60;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (shaftCheckTicks > 0 && --shaftCheckTicks == 0) loadShaftRoute();
        });
        boolean skyHanni = FabricLoader.getInstance().isModLoaded("skyhanni");
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root).then(tree("sho")));
            if (!skyHanni) {
                dispatcher.register(tree("sho"));
                dispatcher.register(tree("shordered"));
            }
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> tree(String name) {
        return ClientCommands.literal(name)
            .executes(c -> help())
            .then(load("load")).then(load("import"))
            .then(ClientCommands.literal("unload").executes(c -> run(() -> unload(true))))
            .then(ClientCommands.literal("clear").executes(c -> run(() -> unload(true))))
            .then(ClientCommands.literal("skip").executes(c -> run(() -> skip(1)))
                .then(ClientCommands.argument("amount", IntegerArgumentType.integer()).executes(c -> run(() -> skip(IntegerArgumentType.getInteger(c, "amount"))))))
            .then(ClientCommands.literal("skipto").executes(c -> run(() -> skipTo(1)))
                .then(ClientCommands.argument("number", IntegerArgumentType.integer()).executes(c -> run(() -> skipTo(IntegerArgumentType.getInteger(c, "number"))))))
            .then(ClientCommands.literal("unskip").executes(c -> run(() -> skip(-1)))
                .then(ClientCommands.argument("amount", IntegerArgumentType.integer()).executes(c -> run(() -> skip(-IntegerArgumentType.getInteger(c, "amount"))))))
            .then(numbered("delete", OrderedWaypoints::delete)).then(numbered("remove", OrderedWaypoints::delete))
            .then(numbered("add", OrderedWaypoints::add)).then(numbered("insert", OrderedWaypoints::add))
            .then(ClientCommands.literal("export").executes(c -> run(() -> export("coleweight")))
                .then(ClientCommands.argument("format", StringArgumentType.word()).suggests((ctx, b) -> b.suggest("coleweight").suggest("skytils").buildFuture())
                    .executes(c -> run(() -> export(StringArgumentType.getString(c, "format"))))))
            .then(ClientCommands.literal("save").then(ClientCommands.argument("name", StringArgumentType.word())
                .executes(c -> run(() -> save(StringArgumentType.getString(c, "name"))))))
            .then(eraseCommand("erase")).then(eraseCommand("delete-route"))
            .then(ClientCommands.literal("setupmode").executes(c -> run(() -> setupMode(null)))
                .then(ClientCommands.argument("enable", BoolArgumentType.bool()).executes(c -> run(() -> setupMode(BoolArgumentType.getBool(c, "enable"))))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> load(String name) {
        return ClientCommands.literal(name).executes(c -> run(() -> loadRoute("")))
            .then(ClientCommands.argument("name", StringArgumentType.word()).suggests((ctx, b) -> {
                for (String key : saved.keySet()) b.suggest(key);
                return b.buildFuture();
            }).executes(c -> run(() -> loadRoute(StringArgumentType.getString(c, "name")))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> eraseCommand(String name) {
        return ClientCommands.literal(name).then(ClientCommands.argument("name", StringArgumentType.word()).suggests((ctx, b) -> {
            for (String key : saved.keySet()) b.suggest(key);
            return b.buildFuture();
        }).executes(c -> run(() -> erase(StringArgumentType.getString(c, "name")))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> numbered(String name, java.util.function.IntConsumer action) {
        return ClientCommands.literal(name).then(ClientCommands.argument("number", IntegerArgumentType.integer())
            .executes(c -> run(() -> action.accept(IntegerArgumentType.getInteger(c, "number")))));
    }

    private static int run(Runnable action) {
        Minecraft.getInstance().execute(action);
        return 1;
    }

    private static int help() {
        say(Component.literal("/sho load [name], unload, save <name>, erase <name>, skip [n], skipto <n>, unskip [n], add <n>, delete <n>, export [coleweight|skytils], setupmode").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static void say(Component text) {
        Sky2MAlerts.chat(text);
    }

    private static void error(String text) {
        say(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    // ------------------------------------------------------------------------------------------------ commands

    private static void loadRoute(String name) {
        List<Wp> loaded;
        String format;
        if (name.isEmpty()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            loaded = coleweight(clip);
            format = "coleweight";
            if (loaded == null) {
                loaded = skytils(clip);
                format = "skytils";
            }
            if (loaded == null || loaded.isEmpty()) {
                error("There was an error parsing waypoints. Make sure they're in a supported format: coleweight, skytils.");
                return;
            }
        } else {
            List<Wp> found = saved.get(name);
            if (found == null) {
                error("Route " + name + " doesn't exist. Saved routes: " + String.join(", ", saved.keySet())
                    + ". To import a route from your clipboard, leave the route name blank.");
                return;
            }
            loaded = found;
            format = "saved";
        }
        route = new ArrayList<>();
        for (Wp w : loaded) route.add(w.copy());
        Wp nearest = route.getFirst();
        for (Wp w : route) if (w.distance() < nearest.distance()) nearest = w;
        current = Math.max(0, nearest.number - 1);
        shown.clear();
        say(Component.literal("Loaded " + route.size() + " ordered waypoints! (").withStyle(ChatFormatting.GREEN)
            .append(Component.literal(format).withStyle(ChatFormatting.YELLOW)).append(Component.literal(")").withStyle(ChatFormatting.GREEN)));
        FeatureConfigs.OrderedWaypoints c = config();
        if (c != null && !c.enabled) {
            c.enabled = true;
            say(Component.literal("Ordered Waypoints was disabled, auto-enabled it.").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void unload(boolean tell) {
        if (tell && !route.isEmpty()) say(Component.literal("Unloaded ordered waypoints.").withStyle(ChatFormatting.YELLOW));
        route = new ArrayList<>();
        shown.clear();
        current = lastCloser = 0;
    }

    private static void skip(int amount) {
        if (route.isEmpty()) {
            error("There are no waypoints to skip.");
            return;
        }
        increment(amount);
        say(Component.literal((amount >= 0 ? "Skipped " : "Unskipped ") + Math.abs(amount) + (Math.abs(amount) == 1 ? " waypoint." : " waypoints.")).withStyle(ChatFormatting.GREEN));
    }

    private static void skipTo(int number) {
        if (route.isEmpty()) {
            error("There are no waypoints to skip to.");
            return;
        }
        if (number < 1 || number > route.size()) {
            error(number + " is not between 1 and " + route.size() + ".");
            return;
        }
        current = number - 1;
        say(Component.literal("Skipped to " + number + ".").withStyle(ChatFormatting.GREEN));
    }

    private static void delete(int number) {
        if (route.isEmpty()) {
            error("There are no waypoints to delete.");
            return;
        }
        if (number < 1 || number > route.size()) {
            error(number + " is not between 1 and " + route.size() + ".");
            return;
        }
        for (int i = number - 1; i < route.size(); i++) renumber(route.get(i), route.get(i).number - 1);
        route.remove(number - 1);
        shown.clear();
        say(Component.literal("Removed waypoint " + number + ".").withStyle(ChatFormatting.GREEN));
    }

    private static void add(int number) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (number < 1 || number > route.size() + 1) {
            error(number + " is not between 1 and " + (route.size() + 1) + ".");
            return;
        }
        BlockPos pos = mc.player.blockPosition().below();
        Wp wp = new Wp(pos.getX(), pos.getY(), pos.getZ(), number);
        if (number <= route.size()) for (int i = number - 1; i < route.size(); i++) renumber(route.get(i), route.get(i).number + 1);
        route.add(number - 1, wp);
        say(Component.literal("Inserted waypoint " + number + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".").withStyle(ChatFormatting.GREEN));
    }

    private static void renumber(Wp wp, int number) {
        wp.number = number;
        wp.options.put("name", String.valueOf(number));
    }

    private static void export(String format) {
        String out;
        if (format.equalsIgnoreCase("coleweight")) {
            JsonArray array = new JsonArray();
            for (Wp w : route) {
                JsonObject o = new JsonObject();
                o.addProperty("x", w.x);
                o.addProperty("y", w.y);
                o.addProperty("z", w.z);
                o.addProperty("r", 0.0);
                o.addProperty("g", 1.0);
                o.addProperty("b", 0.0);
                JsonObject options = new JsonObject();
                w.options.forEach(options::addProperty);
                o.add("options", options);
                array.add(o);
            }
            out = new Gson().toJson(array);
        } else if (format.equalsIgnoreCase("skytils")) {
            JsonArray array = new JsonArray();
            for (Wp w : route) {
                JsonObject o = new JsonObject();
                o.addProperty("name", String.valueOf(w.number));
                o.addProperty("x", (double) w.x);
                o.addProperty("y", (double) w.y);
                o.addProperty("z", (double) w.z);
                array.add(o);
            }
            out = new Gson().toJson(array);
        } else {
            error("Invalid waypoint format. Formats: coleweight, skytils");
            return;
        }
        Minecraft.getInstance().keyboardHandler.setClipboard(out);
        say(Component.literal("Route was copied to clipboard.").withStyle(ChatFormatting.GREEN));
    }

    private static void save(String name) {
        List<Wp> copy = new ArrayList<>();
        for (Wp w : route) copy.add(w.copy());
        saved.put(name, copy);
        saveFile();
        say(Component.literal("Route saved as " + name + ". Do /sho load " + name + " to import it.").withStyle(ChatFormatting.GREEN));
    }

    private static void erase(String name) {
        if (saved.remove(name) == null) {
            error("Route " + name + " doesn't exist.");
            return;
        }
        saveFile();
        say(Component.literal("Route " + name + " successfully deleted.").withStyle(ChatFormatting.GREEN));
    }

    private static void setupMode(Boolean value) {
        FeatureConfigs.OrderedWaypoints c = config();
        if (c == null) return;
        c.setupMode = value == null ? !c.setupMode : value;
        say(Component.literal("Toggled setup mode to " + c.setupMode).withStyle(ChatFormatting.GREEN));
    }

    /** Auto-Load Shaft Routes: the Mineshaft's type (like TOPA_1) from the scoreboard, if a route has that name. */
    private static void loadShaftRoute() {
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = SHAFT.matcher(line);
            if (m.find() && saved.containsKey(m.group(1))) {
                loadRoute(m.group(1));
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ formats

    private static List<Wp> coleweight(String data) {
        try {
            JsonElement root = JsonParser.parseString(data.trim());
            if (!root.isJsonArray()) return null;
            List<Wp> out = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                JsonObject options = o.getAsJsonObject("options");
                int number = Integer.parseInt(options.get("name").getAsString());
                Wp wp = new Wp(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), number);
                for (String key : options.keySet()) wp.options.put(key, options.get(key).getAsString());
                out.add(wp);
            }
            out.sort(java.util.Comparator.comparingInt(w -> w.number));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Wp> skytils(String data) {
        try {
            String text = data.trim();
            if (text.startsWith(SKYTILS_V1)) {
                byte[] bytes = Base64.getDecoder().decode(text.substring(SKYTILS_V1.length()).trim());
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    text = out.toString(StandardCharsets.UTF_8);
                }
            }
            JsonElement root = JsonParser.parseString(text);
            List<JsonObject> points = new ArrayList<>();
            if (root.isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray()) points.add(e.getAsJsonObject());
            } else if (root.isJsonObject() && root.getAsJsonObject().has("categories")) {
                // Skytils' newer export: categories, each with its own waypoints.
                for (JsonElement cat : root.getAsJsonObject().getAsJsonArray("categories")) {
                    for (JsonElement e : cat.getAsJsonObject().getAsJsonArray("waypoints")) points.add(e.getAsJsonObject());
                }
            } else {
                return null;
            }
            List<Wp> out = new ArrayList<>();
            for (JsonObject o : points) {
                Wp wp = new Wp((int) Math.floor(o.get("x").getAsDouble()), (int) Math.floor(o.get("y").getAsDouble()), (int) Math.floor(o.get("z").getAsDouble()), out.size() + 1);
                if (o.has("name")) wp.options.put("name", o.get("name").getAsString());
                out.add(wp);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.OrderedWaypoints c = config();
        if (c == null || !c.enabled || route.isEmpty()) return;
        decide(c);
        for (int i = 0; i < shown.size(); i++) {
            int index = shown.get(i);
            if (index >= route.size()) continue;
            Wp wp = route.get(index);
            String colour = c.showAll ? c.nextColor : switch (i) {
                case 0 -> c.previousColor;
                case 1 -> c.currentColor;
                default -> i <= 1 + c.nextCount ? c.nextColor : "0:102:255:0:0";
            };
            float[] rgb = rgb(colour);
            float alpha = alpha(colour);
            BlockPos pos = new BlockPos(wp.x, wp.y, wp.z);
            if (c.fillBlock) collector.submitFilledBox(pos, rgb, alpha, true);
            else collector.submitOutlinedBox(pos, rgb, c.outlineThickness, true);
            boolean named = c.showName && (c.setupMode || c.showAll || i <= 1 + c.nextCount);
            if (named) collector.submitText(Component.literal(String.valueOf(wp.number)).withStyle(ChatFormatting.YELLOW), new Vec3(wp.x + 0.5, wp.y + 2.5, wp.z + 0.5), true);
            if (c.showDistance) collector.submitText(Component.literal(String.format(Locale.US, "%,.1f m", wp.distance())).withStyle(ChatFormatting.YELLOW),
                new Vec3(wp.x + 0.5, wp.y + 2.0, wp.z + 0.5), true);
        }
        if (shown.size() <= 1) return;
        Wp trace = shown.size() == 2 ? route.get(shown.getFirst()) : route.get(shown.get(2));
        if (c.traceLine && !c.showAll && !c.setupMode) {
            collector.submitLineFromCursor(new Vec3(trace.x + 0.5, trace.y + 0.25, trace.z + 0.5), rgb(c.traceLineColor), alpha(c.traceLineColor), 2f);
        }
        if (c.setupMode && !c.showAll) {
            Wp cur = route.get(shown.get(1));
            double eye = c.sneaking ? SNEAK_EYE : STAND_EYE;
            collector.submitLinesFromPoints(new Vec3[]{new Vec3(cur.x + 0.5, cur.y + 1.0 + eye, cur.z + 0.5), trace.centre()},
                new float[]{1f, 0f, 0f}, 0.4f, 2f, true);
        }
    }

    /** SkyHanni's decideWaypoints: what to show, and moving along the route as you reach waypoints. */
    private static void decide(FeatureConfigs.OrderedWaypoints c) {
        shown.clear();
        if (route.isEmpty()) return;
        current = Math.floorMod(current, route.size());
        if (c.autoSkipForward) {
            Wp closest = null;
            for (Wp w : route) if (w.distance() < c.range && (closest == null || w.distance() < closest.distance())) closest = w;
            if (closest != null && closest.number - 1 > current) {
                current = closest.number - 1;
                lastCloser = current;
                return;
            }
        }
        Wp before = current - 1 >= 0 ? route.get(current - 1) : route.getLast();
        shown.add(before.number - 1);
        Wp now = route.get(current);
        double toCurrent = now.distance();
        shown.add(now.number - 1);
        Wp next = current + 1 < route.size() ? route.get(current + 1) : route.getFirst();
        double toNext = next.distance();
        if (!shown.contains(next.number - 1)) shown.add(next.number - 1);
        for (int i = 1; i < c.nextCount; i++) {
            int index = (next.number - 1 + i) % route.size();
            if (!shown.contains(index)) shown.add(index);
        }
        if (lastCloser == current && toCurrent > toNext && toNext < c.range) {
            increment(1);
            return;
        }
        if (toCurrent < c.range) lastCloser = current;
        if (toNext < c.range) increment(1);
        for (Wp w : route) {
            boolean inSetupRange = c.setupMode && !shown.contains(w.number - 1) && w.distance() < c.setupModeRange;
            if ((inSetupRange || c.showAll) && !shown.contains(w.number - 1)) shown.add(w.number - 1);
        }
    }

    private static void increment(int by) {
        if (route.isEmpty()) return;
        current = Math.floorMod(current + by, route.size());
    }

    private static float[] rgb(String colour) {
        try {
            int argb = ChromaColours.parse(colour).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
        } catch (Exception e) {
            return new float[]{1f, 1f, 1f};
        }
    }

    private static float alpha(String colour) {
        String[] parts = colour.split(":");
        try {
            return parts.length >= 4 ? Math.max(0.15f, Integer.parseInt(parts[parts.length - 4].trim()) / 255f) : 1f;
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, List<Wp>> read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, List<Wp>>>() {}.getType());
            if (read != null) saved = read;
        } catch (Exception ignored) {}
    }

    private static void saveFile() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
