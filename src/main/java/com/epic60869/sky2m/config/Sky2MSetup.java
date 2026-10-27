package com.epic60869.sky2m.config;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * First-run setup (/s2 setup): pick what you play, and the areas you don't are switched off as a whole.
 *
 * <p>Turning an area off keeps a copy of its settings in config/sky2m/setup.json and turns off every on/off setting
 * in it; turning it on again copies those settings back. Reset puts an area back to Sky2M's defaults. All changes are
 * made in place on the existing settings objects, so features and the /s2 settings see them straight away.
 */
public final class Sky2MSetup {
    /**
     * An area of the mod: its name, what's in it, and the settings it covers: the Sky2MConfig field named
     * {@code key}, or the dotted {@code paths} when it is spread over several sections (setup.json keeps them under
     * the last part of each path).
     */
    record Area(String key, String label, String description, List<String> paths) {
        Area(String key, String label, String description) {
            this(key, label, description, List.of(key));
        }
    }

    static final List<Area> AREAS = List.of(
        new Area("dungeons", "Dungeons", "Map, puzzle solvers, secrets, terminals, splits, timers and Party Finder stats."),
        new Area("slayers", "Slayers", "Boss phases, timers, PBs, highlights and boss profit."),
        new Area("farming", "Farming & Garden", "Garden HUDs, pests, visitors, plots, mouse lock and farming RNG."),
        new Area("mining", "Mining", "Commissions, Crystal Hollows, Scatha, gemstones and waypoints."),
        new Area("fishing", "Fishing", "Sea creature and rare drop alerts, catch messages and highlights."),
        new Area("foraging", "Foraging", "Sweep display and foraging helpers."),
        new Area("hunting", "Hunting", "Shard tracker, hunting profit, lasso, fusion and Galatea mobs."),
        // Diana and Mayor & Election are in Events now; the key stays "mayors" so setup.json keeps its saved state.
        new Area("mayors", "Diana & Mayors", "Diana burrows, rare mob sharing and mayor features.", List.of("events.diana", "events.mayor")));

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static final int JOIN_DELAY_TICKS = 80;
    private static int joinTicks = -1;

    private Sky2MSetup() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("setup")
                    .executes(c -> Compat.queueOpenScreen(new Sky2MSetupScreen()))));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> joinTicks = 0);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (joinTicks < 0 || mc.player == null) return;
            if (++joinTicks < JOIN_DELAY_TICKS) return;
            joinTicks = -1;
            offerOnce(mc);
        });
    }

    /** The first time you join with this feature: offer the setup in chat (it never opens by itself mid-game). */
    private static void offerOnce(Minecraft mc) {
        JsonObject state = state();
        if (state.has("offered")) return;
        state.addProperty("offered", true);
        save(state);
        Component button = Component.literal("[Set up Sky2M]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("s2 setup"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Pick what you play; the rest is switched off"))));
        mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("Too many features on? Pick what you play and Sky2M turns off the rest. ").withStyle(ChatFormatting.YELLOW))
            .append(button));
    }

    // ---------------------------------------------------------------- state

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("setup.json");
    }

    private static JsonObject state() {
        try {
            if (Files.exists(file())) {
                JsonElement parsed = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read setup.json: " + e.getMessage());
        }
        return new JsonObject();
    }

    private static void save(JsonObject state) {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save setup.json: " + e.getMessage());
        }
    }

    private static JsonObject off(JsonObject state) {
        if (!state.has("off") || !state.get("off").isJsonObject()) state.add("off", new JsonObject());
        return state.getAsJsonObject("off");
    }

    /** Whether the area is on (not switched off here). */
    static boolean isOn(Area area) {
        return !off(state()).has(area.key());
    }

    // ---------------------------------------------------------------- changes

    /** Turns the area on or off; false if its settings couldn't be found. */
    static boolean set(Area area, boolean on) {
        try {
            return change(area, on);
        } catch (Exception e) {
            System.err.println("[Sky2M] Setup couldn't change " + area.label() + ": " + e);
            return false;
        }
    }

    private static boolean change(Area area, boolean on) {
        List<Object> sections = sections(area);
        if (sections == null) return false;
        JsonObject state = state();
        JsonObject off = off(state);
        if (on) {
            JsonElement kept = off.remove(area.key());
            if (kept != null) {
                for (int i = 0; i < sections.size(); i++) {
                    JsonElement saved = sections.size() == 1 ? kept
                        : kept.isJsonObject() ? kept.getAsJsonObject().get(lastPart(area.paths().get(i))) : null;
                    if (saved == null) continue;
                    Object section = sections.get(i);
                    Object restored = GSON.fromJson(saved, section.getClass());
                    if (restored != null) copyInto(section, restored, 0);
                }
            }
        } else {
            if (off.has(area.key())) return true;
            if (sections.size() == 1) {
                off.add(area.key(), GSON.toJsonTree(sections.get(0)));
            } else {
                JsonObject kept = new JsonObject();
                for (int i = 0; i < sections.size(); i++) kept.add(lastPart(area.paths().get(i)), GSON.toJsonTree(sections.get(i)));
                off.add(area.key(), kept);
            }
            for (Object section : sections) switchOff(section, 0);
        }
        save(state);
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        return true;
    }

    /** Puts the area back to Sky2M's defaults (and on). */
    static boolean reset(Area area) {
        List<Object> sections = sections(area);
        if (sections == null) return false;
        try {
            for (Object section : sections) {
                var constructor = section.getClass().getDeclaredConstructor();
                constructor.setAccessible(true);
                copyInto(section, constructor.newInstance(), 0);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't reset " + area.label() + ": " + e.getMessage());
            return false;
        }
        JsonObject state = state();
        off(state).remove(area.key());
        save(state);
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        return true;
    }

    /** The settings objects the area covers (one per path), or null if any can't be found. */
    private static List<Object> sections(Area area) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null) return null;
        List<Object> out = new java.util.ArrayList<>();
        for (String path : area.paths()) {
            try {
                Object at = config;
                for (String part : path.split("\\.")) at = at.getClass().getField(part).get(at);
                if (at == null) return null;
                out.add(at);
            } catch (Exception e) {
                System.err.println("[Sky2M] Setup: no settings section " + path);
                return null;
            }
        }
        return out;
    }

    private static String lastPart(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    // ---------------------------------------------------------------- reflection

    private static boolean nested(Class<?> type) {
        return !type.isPrimitive() && !type.isEnum() && !type.isArray() && type.getName().startsWith("com.epic60869.")
            && !Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type);
    }

    private static List<Field> settings(Class<?> type) {
        List<Field> out = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.isAnnotationPresent(Expose.class)) continue;
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    /** Every on/off setting in {@code section}, however deep, switched off. */
    private static void switchOff(Object section, int depth) {
        if (section == null || depth > 8) return;
        for (Field f : settings(section.getClass())) {
            try {
                if (f.getType() == boolean.class && f.isAnnotationPresent(ConfigEditorBoolean.class)) f.setBoolean(section, false);
                else if (nested(f.getType())) switchOff(f.get(section), depth + 1);
            } catch (IllegalAccessException ignored) {}
        }
    }

    /** Copies every setting of {@code from} into {@code into}, keeping {@code into}'s nested objects. */
    private static void copyInto(Object into, Object from, int depth) {
        if (into == null || from == null || depth > 8) return;
        for (Field f : settings(into.getClass())) {
            try {
                Object value = f.get(from);
                Object current = f.get(into);
                if (nested(f.getType()) && current != null && value != null && !Modifier.isFinal(f.getModifiers())) {
                    copyInto(current, value, depth + 1);
                } else if (!Modifier.isFinal(f.getModifiers())) {
                    f.set(into, value);
                }
            } catch (IllegalAccessException ignored) {}
        }
    }
}
