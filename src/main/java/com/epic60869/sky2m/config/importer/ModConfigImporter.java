package com.epic60869.sky2m.config.importer;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.loader.api.FabricLoader;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads other mods' config files and lists the Sky2M settings they have an equivalent of, for the import screen.
 * Mods in {@link ModImportSources} use their mapping table. For any other mod's config, on/off settings whose name is
 * the same as a Sky2M setting's are offered as guesses (unticked until the player picks them). Only reads other mods'
 * files; only Sky2M's own settings are written.
 */
public final class ModConfigImporter {
    private ModConfigImporter() {}

    /** Set by tests to read a fake config folder instead of the game's. */
    public static volatile Path configDirOverride;

    private static final long MAX_FILE = 20L * 1024 * 1024;
    private static final long MAX_GUESS_FILE = 4L * 1024 * 1024;
    private static final Set<String> CONFIG_EXT = Set.of("json", "json5", "jsonc", "toml", "properties", "cfg");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    /** Sky2M's own files, under every name it has had. */
    private static final List<String> OWN = List.of("sky2m", "skyballs", "skyjew");
    /** Parts of a config that hold saved data rather than settings. */
    private static final Set<String> DATA = Set.of("storage", "profiles", "players", "cache", "history", "backups", "backup", "data");
    /** Setting names too generic to match on their own; the section they're in is used instead. */
    private static final Set<String> GENERIC = Set.of("enabled", "enable", "toggle", "toggled", "value", "display", "show", "on",
        "active", "hud", "alert", "sound", "title", "chat", "highlight");

    public enum Choice { ENABLE, DISABLE, SKIP }

    /** A mod found in the config folder: a mapped one ({@code source} set), or one only offered as guesses. */
    public record Detected(String id, String name, Path file, ModImportSources.Source source) {
        public boolean guessedOnly() {
            return source == null;
        }
    }

    /** One Sky2M setting the other mod has an equivalent of. */
    public static final class Row {
        public final String label;
        public final String where;
        public final String sky2mPath;
        public final String otherPath;
        public final boolean otherValue;
        public final boolean guessed;
        public Choice choice;
        final Field field;
        final Object owner;

        Row(Target target, String otherPath, boolean otherValue, boolean guessed) {
            this.label = target.label;
            this.where = target.where;
            this.sky2mPath = target.path;
            this.otherPath = otherPath;
            this.otherValue = otherValue;
            this.guessed = guessed;
            this.field = target.field;
            this.owner = target.owner;
            this.choice = guessed ? Choice.SKIP : otherValue ? Choice.ENABLE : Choice.DISABLE;
        }

        public boolean current() {
            try {
                return field.getBoolean(owner);
            } catch (IllegalAccessException e) {
                return false;
            }
        }

        /** Whether applying this row's choice would change the Sky2M setting. */
        public boolean changes() {
            return choice != Choice.SKIP && (choice == Choice.ENABLE) != current();
        }

        /** The choice that copies the other mod's value. */
        public Choice matching() {
            return otherValue ? Choice.ENABLE : Choice.DISABLE;
        }
    }

    /** A Sky2M on/off setting: where it lives in the config, and its name as the settings show it. */
    record Target(String path, String label, String where, Field field, Object owner) {}

    public static Path configDir() {
        Path override = configDirOverride;
        return override != null ? override : FabricLoader.getInstance().getConfigDir();
    }

    // ------------------------------------------------------------------------------------------------ detection

    /** Mods with a config here: mapped ones first, then any other whose settings share names with Sky2M's. */
    public static List<Detected> detect() {
        Path dir = configDir();
        List<Detected> out = new ArrayList<>();
        Set<String> claimed = new HashSet<>();
        for (ModImportSources.Source source : ModImportSources.all()) {
            for (String file : source.files()) claimed.add(file.split("/")[0].toLowerCase(Locale.ROOT));
            Path file = find(dir, source);
            if (file == null) continue;
            Detected detected = new Detected(source.id(), source.name(), file, source);
            // Only mods with at least one setting Sky2M has.
            if (!rows(detected).isEmpty()) out.add(detected);
        }
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !Files.isDirectory(dir)) return out;
        Map<String, List<Target>> index = nameIndex(config);
        Set<String> skyblockMods = installedSkyblockMods();
        List<Path> entries;
        try (Stream<Path> list = Files.list(dir)) {
            entries = list.sorted().toList();
        } catch (Exception e) {
            return out;
        }
        for (Path entry : entries) {
            String fileName = entry.getFileName().toString();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (claimed.contains(lower) || OWN.stream().anyMatch(lower::startsWith)) continue;
            if (!skyblockMod(Files.isDirectory(entry) ? fileName : stripExtension(fileName), skyblockMods)) continue;
            Path best = null;
            int bestCount = 0;
            for (Path file : guessCandidates(entry)) {
                JsonElement root = read(file, MAX_GUESS_FILE);
                int count = root == null ? 0 : guessRows(root, index).size();
                if (count > bestCount) {
                    best = file;
                    bestCount = count;
                }
            }
            if (best == null) continue;
            String id = Files.isDirectory(entry) ? fileName : stripExtension(fileName);
            out.add(new Detected(id.toLowerCase(Locale.ROOT), id, best, null));
        }
        return out;
    }

    /** SkyBlock mods whose settings may be left in the config folder without the mod installed (normalised names). */
    private static final Set<String> KNOWN_SKYBLOCK = Set.of("skytils", "notenoughupdates", "neu", "firmament", "skyblocker",
        "skyhanni", "odin", "odinclient", "odinfabric", "noammaddons", "nofrills", "devonian", "skyocean", "sbo",
        "skyblockoverhaul", "customscoreboard", "rfu", "stella", "nopo", "skyblockextras", "skyblockaddons", "sba",
        "bazaarutils", "skyhelper", "skyblockfeatures", "dungeonsguide", "partlysaneskies", "soopy", "chattriggers",
        "skyblocktweaks", "cookiemod", "hypixelplus", "meowdding", "skyblockapi", "catharsis", "skycubed", "skyblockpv");

    /**
     * The normalised ids and names of the installed mods made for SkyBlock: they mention SkyBlock or Hypixel in their
     * id, name or description, or need a SkyBlock or Hypixel library.
     */
    private static Set<String> installedSkyblockMods() {
        Set<String> out = new HashSet<>(KNOWN_SKYBLOCK);
        for (var mod : FabricLoader.getInstance().getAllMods()) {
            var meta = mod.getMetadata();
            String text = (meta.getId() + " " + meta.getName() + " " + meta.getDescription()).toLowerCase(Locale.ROOT);
            boolean skyblock = text.contains("skyblock") || text.contains("hypixel");
            for (var dep : meta.getDependencies()) {
                String dependency = dep.getModId().toLowerCase(Locale.ROOT);
                if (dependency.contains("skyblock") || dependency.contains("hypixel")) skyblock = true;
            }
            if (!skyblock) continue;
            out.add(norm(meta.getId()));
            out.add(norm(meta.getName()));
        }
        out.remove("");
        return out;
    }

    /** A config file or folder named after a SkyBlock mod ("skytils", "NotEnoughUpdates", "odin-config"). */
    private static boolean skyblockMod(String name, Set<String> skyblockMods) {
        String key = norm(name);
        if (key.isEmpty()) return false;
        for (String mod : skyblockMods) {
            if (key.equals(mod) || (mod.length() >= 4 && key.startsWith(mod))) return true;
        }
        return false;
    }

    /** The first of a mapped mod's config files that exists (names are tried as written, then lower case). */
    private static Path find(Path dir, ModImportSources.Source source) {
        for (String file : source.files()) {
            Path path = dir.resolve(file);
            if (Files.isRegularFile(path)) return path;
        }
        return null;
    }

    /** Config files to try for a guess: the file itself, or the files directly inside a mod's folder. */
    private static List<Path> guessCandidates(Path entry) {
        if (Files.isRegularFile(entry)) return isConfigFile(entry) ? List.of(entry) : List.of();
        if (!Files.isDirectory(entry)) return List.of();
        try (Stream<Path> list = Files.list(entry)) {
            // Files named like a config first; only a few, so a folder of saved data isn't all parsed.
            return list.filter(Files::isRegularFile).filter(ModConfigImporter::isConfigFile)
                .sorted((a, b) -> Boolean.compare(!looksLikeConfig(a), !looksLikeConfig(b)))
                .limit(6).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean isConfigFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && CONFIG_EXT.contains(name.substring(dot + 1)) && !name.contains("backup") && !name.endsWith(".bak");
    }

    private static boolean looksLikeConfig(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("config") || name.contains("setting") || name.contains("option");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** The rows for a detected mod: every mapped setting found in its config, or the guesses for an unmapped mod. */
    public static List<Row> rows(Detected detected) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || detected == null) return List.of();
        JsonElement root = read(detected.file(), detected.guessedOnly() ? MAX_GUESS_FILE : MAX_FILE);
        if (root == null) return List.of();
        if (detected.guessedOnly()) return guessRows(root, nameIndex(config));
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] map : detected.source().map()) {
            Boolean value = bool(at(root, map[0]));
            if (value == null) continue;
            Target target = target(config, map[1]);
            // Two of the other mod's settings mapped to one Sky2M setting: the first found wins.
            if (target == null || !seen.add(target.path())) continue;
            rows.add(new Row(target, map[0], value, false));
        }
        return rows;
    }

    /** Sets each row's Sky2M setting to its choice and saves. Returns how many settings were set. */
    public static int apply(List<Row> rows) {
        int applied = 0;
        for (Row row : rows) {
            if (row.choice == Choice.SKIP) continue;
            try {
                row.field.setBoolean(row.owner, row.choice == Choice.ENABLE);
                applied++;
            } catch (IllegalAccessException ignored) {
                // Public fields only; can't happen.
            }
        }
        Sky2MConfig config = Sky2MConfig.current();
        if (applied > 0 && config != null) Sky2MConfig.saveCurrent(config);
        return applied;
    }

    // ------------------------------------------------------------------------------------------------ guesses

    /**
     * Every on/off setting in {@code root} whose name, with spaces, case and punctuation ignored, is the name of exactly
     * one Sky2M setting. A generic name ("enabled") uses the section it's in instead. Guesses start on Skip.
     */
    static List<Row> guessRows(JsonElement root, Map<String, List<Target>> index) {
        Map<String, Boolean> leaves = new LinkedHashMap<>();
        flatten(root, "", leaves, 0);
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Boolean> leaf : leaves.entrySet()) {
            String key = guessKey(leaf.getKey());
            if (key == null) continue;
            List<Target> targets = index.get(key);
            if (targets == null || targets.size() != 1 || !seen.add(targets.get(0).path())) continue;
            rows.add(new Row(targets.get(0), leaf.getKey(), leaf.getValue(), true));
        }
        return rows;
    }

    private static String guessKey(String path) {
        String[] parts = path.split("/");
        String key = norm(parts[parts.length - 1]);
        if (GENERIC.contains(key)) {
            if (parts.length < 2) return null;
            key = norm(parts[parts.length - 2]);
        }
        return key.length() >= 8 && !GENERIC.contains(key) ? key : null;
    }

    private static void flatten(JsonElement element, String path, Map<String, Boolean> out, int depth) {
        if (element == null || depth > 7 || out.size() > 20000) return;
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
                String key = e.getKey();
                if (DATA.contains(key.toLowerCase(Locale.ROOT)) || UUID.matcher(key).find()) continue;
                flatten(e.getValue(), path.isEmpty() ? key : path + "/" + key, out, depth + 1);
            }
        } else if (element.isJsonArray()) {
            // Module lists: {"name": "Leap Menu", "enabled": true, ...}.
            for (JsonElement e : element.getAsJsonArray()) {
                if (!e.isJsonObject() || !(e.getAsJsonObject().get("name") instanceof JsonPrimitive name) || !name.isString()) continue;
                JsonObject copy = e.getAsJsonObject().deepCopy();
                copy.remove("name");
                flatten(copy, path.isEmpty() ? name.getAsString() : path + "/" + name.getAsString(), out, depth + 1);
            }
        } else {
            Boolean value = bool(element);
            if (value != null && !path.isEmpty()) out.put(path, value);
        }
    }

    /** Normalised names of Sky2M's on/off settings: the option's name, and its field's name when that says more. */
    static Map<String, List<Target>> nameIndex(Sky2MConfig config) {
        Map<String, List<Target>> index = new HashMap<>();
        collect(config, "", "", "", index, 0);
        // A name two settings share can't say which one is meant.
        for (List<Target> targets : index.values()) {
            List<Target> distinct = new ArrayList<>();
            for (Target t : targets) if (distinct.stream().noneMatch(d -> d.path().equals(t.path()))) distinct.add(t);
            targets.clear();
            targets.addAll(distinct);
        }
        return index;
    }

    private static void collect(Object owner, String path, String where, String parentName, Map<String, List<Target>> index, int depth) {
        if (owner == null || depth > 8) return;
        for (Field field : owner.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getAnnotation(Expose.class) == null) continue;
            String childPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
            String name = displayName(field);
            if (field.getType() == boolean.class) {
                if (field.getAnnotation(ConfigOption.class) == null) continue;
                Target target = new Target(childPath, label(name, where), where, field, owner);
                String byName = norm(name);
                if (!GENERIC.contains(byName)) index.computeIfAbsent(byName, k -> new ArrayList<>()).add(target);
                String byField = norm(field.getName());
                if (GENERIC.contains(byField)) byField = norm(parentName);
                if (!byField.isEmpty() && !GENERIC.contains(byField)) index.computeIfAbsent(byField, k -> new ArrayList<>()).add(target);
                continue;
            }
            Class<?> type = field.getType();
            if (type.isPrimitive() || type.isEnum() || !type.getName().startsWith("com.epic60869")) continue;
            try {
                String nextWhere = name == null ? where : where.isEmpty() ? name : where + " > " + name;
                collect(field.get(owner), childPath, nextWhere, field.getName(), index, depth + 1);
            } catch (IllegalAccessException ignored) {}
        }
    }

    // ------------------------------------------------------------------------------------------------ Sky2M settings

    /** The Sky2M on/off setting at {@code path} (field names, as in sky2m-mod.json), or null. */
    static Target target(Sky2MConfig config, String path) {
        Object owner = config;
        String where = "";
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            Field field;
            try {
                field = owner.getClass().getField(parts[i]);
            } catch (NoSuchFieldException e) {
                return null;
            }
            if (Modifier.isStatic(field.getModifiers())) return null;
            String name = displayName(field);
            if (i == parts.length - 1) {
                if (field.getType() != boolean.class) return null;
                return new Target(path, label(name == null ? field.getName() : name, where), where, field, owner);
            }
            try {
                owner = field.get(owner);
            } catch (IllegalAccessException e) {
                return null;
            }
            if (owner == null) return null;
            if (name != null) where = where.isEmpty() ? name : where + " > " + name;
        }
        return null;
    }

    /** Whether {@code path} is a Sky2M on/off setting (for tests checking the mapping tables). */
    public static boolean isSetting(String path) {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && target(config, path) != null;
    }

    private static String displayName(Field field) {
        ConfigOption option = field.getAnnotation(ConfigOption.class);
        if (option != null) return option.name();
        Category category = field.getAnnotation(Category.class);
        return category != null ? category.name() : null;
    }

    /** "Enabled" says nothing on its own: name it after the section it's in. */
    private static String label(String name, String where) {
        if (name == null || name.isBlank()) name = "?";
        if (!GENERIC.contains(norm(name)) || where.isEmpty()) return name;
        int cut = where.lastIndexOf(" > ");
        return (cut < 0 ? where : where.substring(cut + 3)) + ": " + name;
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** A config file as JSON: JSON with comments and trailing commas (JSONC, JSON5), or TOML/properties as nested objects. */
    public static JsonElement read(Path file, long maxSize) {
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > maxSize) return null;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".toml") || name.endsWith(".properties") || name.endsWith(".cfg")) return parseKeyValues(text);
            JsonReader reader = new JsonReader(new StringReader(stripComments(text)));
            return JsonParser.parseReader(reader);
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    /** Drops // and /* *&#47; comments outside strings, and commas right before a closing bracket. */
    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) out.append(text.charAt(++i));
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
            } else if (c == ',') {
                int j = i + 1;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                if (j < text.length() && (text.charAt(j) == '}' || text.charAt(j) == ']')) continue;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** TOML, .properties and .cfg as nested objects: "[a.b]" sections and "key = value" lines; only true/false kept. */
    static JsonObject parseKeyValues(String text) {
        JsonObject root = new JsonObject();
        JsonObject section = root;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = root;
                for (String part : line.replaceAll("^\\[+|]+$", "").split("\\.")) {
                    String key = unquote(part.strip());
                    JsonObject next;
                    if (section.get(key) instanceof JsonObject existing) {
                        next = existing;
                    } else {
                        next = new JsonObject();
                        section.add(key, next);
                    }
                    section = next;
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) eq = line.indexOf(':');
            if (eq <= 0) continue;
            String key = unquote(line.substring(0, eq).strip());
            String value = line.substring(eq + 1).strip();
            int comment = value.indexOf(" #");
            if (comment >= 0) value = value.substring(0, comment).strip();
            // Forge/Skytils style "B:name=true".
            if (key.length() > 2 && key.charAt(1) == ':') key = key.substring(2);
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) section.addProperty(key, Boolean.parseBoolean(value.toLowerCase(Locale.ROOT)));
        }
        return root;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'")) ? s.substring(1, s.length() - 1) : s;
    }

    /**
     * The value at {@code path}: split on "/" when it has one, else on ".". On an array, a segment picks the element
     * whose "name" it is.
     */
    public static JsonElement at(JsonElement root, String path) {
        JsonElement at = root;
        for (String key : path.contains("/") ? path.split("/") : path.split("\\.")) {
            if (at == null) return null;
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(key);
            } else if (at.isJsonArray()) {
                JsonElement found = null;
                for (JsonElement e : (JsonArray) at) {
                    if (e.isJsonObject() && e.getAsJsonObject().get("name") instanceof JsonPrimitive p && p.isString() && p.getAsString().equals(key)) {
                        found = e;
                        break;
                    }
                }
                at = found;
            } else {
                return null;
            }
        }
        return at;
    }

    private static Boolean bool(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) return null;
        return element.getAsBoolean();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
