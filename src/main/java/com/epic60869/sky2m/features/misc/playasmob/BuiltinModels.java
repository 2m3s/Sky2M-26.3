package com.epic60869.sky2m.features.misc.playasmob;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 3D models that ship inside the mod (no download): picked as the look "builtin:&lt;id&gt;" in /s2 cosmetics and kept
 * in the Play as Mob settings, so they work without the Sky2M server. They are Blockbench {@code .bbmodel} resources read
 * by {@link CustomModels}, so held items, the walk and swing animations and the first-person arm all work the same as for
 * the models staff give out. Other players only see a built-in model when their own client has it selected.
 */
public final class BuiltinModels {

    public static final String PREFIX = "builtin:";

    /** One bundled model: the id after {@link #PREFIX}, its display name and its resource path. */
    public record Entry(String id, String name, String resource) {
    }

    public static final List<Entry> ALL = List.of(
            new Entry("generic_man", "Generic Man", "/assets/sky2m/playasmob/generic-man/generic-man.bbmodel"));

    /** How a bundled model is placed: its own size, fitted to the player's height, facing the way the player does. */
    private static final CustomModels.ServerModelPlacement PLACEMENT = new CustomModels.ServerModelPlacement(1f, 0f, true);

    /** Loaded models by id; a model that failed to load stays null so it is not read again every frame. */
    private static final Map<String, CustomModels.Model> LOADED = new HashMap<>();
    private static final Map<String, Boolean> TRIED = new HashMap<>();

    private BuiltinModels() {
    }

    /** {@code value} ("builtin:generic_man") when it names a bundled model, else null. */
    public static String sanitize(String value) {
        Entry e = entry(value);
        return e == null ? null : PREFIX + e.id();
    }

    private static Entry entry(String value) {
        if (value == null || !value.startsWith(PREFIX)) return null;
        String id = value.substring(PREFIX.length()).toLowerCase(Locale.ROOT);
        for (Entry e : ALL) if (e.id().equals(id)) return e;
        return null;
    }

    /** The model's name ("Generic Man"), or the value itself when it is not a bundled model. */
    public static String displayName(String value) {
        Entry e = entry(value);
        return e == null ? String.valueOf(value) : e.name();
    }

    /** The model to draw for {@code value} and how to place it, or null when it is not a bundled model or can't load. */
    public static PlayAsMobRender.Drawn drawn(String value) {
        Entry e = entry(value);
        if (e == null) return null;
        CustomModels.Model model = load(e);
        return model == null ? null : new PlayAsMobRender.Drawn(model, PLACEMENT);
    }

    /** Render thread only (the textures are registered with the texture manager). */
    private static CustomModels.Model load(Entry e) {
        if (TRIED.containsKey(e.id())) return LOADED.get(e.id());
        TRIED.put(e.id(), Boolean.TRUE);
        try (InputStream in = BuiltinModels.class.getResourceAsStream(e.resource())) {
            if (in == null) throw new IllegalStateException("resource " + e.resource() + " is missing");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            // No parent folder: the textures are embedded in the model file.
            CustomModels.Model model = CustomModels.loadBbModel(root, Path.of(e.id() + ".bbmodel"));
            LOADED.put(e.id(), model);
            return model;
        } catch (Throwable t) {
            CustomModels.LOGGER.error("[Play as Mob] could not load the built-in model {}", e.id(), t);
            return null;
        }
    }
}
