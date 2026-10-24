package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.Sky2MConfig;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the global search (GlobalSearchScreen) searches: every settings option, category and section, found by
 * reflecting over the live config objects the way MoulConfig does (@Category / @Accordion / @ConfigOption /
 * old-style @ConfigAccordionId), plus HUDs and /s2 subcommands. It is rebuilt each time the screen opens, so
 * anything added to the config tree at runtime is included.
 */
public final class GlobalSearchIndex {
    public enum Kind { SETTING, CATEGORY, SECTION, BUTTON, HUD, COMMAND }

    /** One result. {@code field}/{@code owner} are set for settings; {@code search} is what the settings GUI is opened with. */
    public record Entry(Kind kind, String name, String desc, String path, String search, Field field, Object owner,
                        String hudId) {
        public boolean isToggle() {
            return field != null && (field.getType() == boolean.class || field.getType() == Boolean.class) && kind == Kind.SETTING;
        }

        public boolean on() {
            try {
                if (field != null && owner != null) return Boolean.TRUE.equals(field.get(owner));
            } catch (Throwable ignored) {}
            return false;
        }

        public void toggle() {
            if (!isToggle()) return;
            try {
                field.set(owner, !on());
                Sky2MConfig c = Sky2MConfig.current();
                if (c != null) Sky2MConfig.saveCurrent(c);
            } catch (Throwable ignored) {}
        }

        /** The current value as text, for non-toggle settings (empty when it isn't worth showing). */
        public String valueText() {
            if (field == null || owner == null || kind != Kind.SETTING) return "";
            try {
                Object v = field.get(owner);
                if (v == null || v instanceof Runnable) return "";
                if (v instanceof Enum<?> e) return e.name().replace('_', ' ');
                if (v instanceof Number || v instanceof CharSequence) return String.valueOf(v);
            } catch (Throwable ignored) {}
            return "";
        }

        public void run() {
            try {
                if (kind == Kind.BUTTON && field != null && field.get(owner) instanceof Runnable r) r.run();
            } catch (Throwable ignored) {}
        }
    }

    private static final Map<String, String> COMMAND_INFO = new HashMap<>();
    static {
        COMMAND_INFO.put("settings", "Open the settings, optionally filtered");
        COMMAND_INFO.put("gui", "Move and resize HUDs");
        COMMAND_INFO.put("huds", "Every HUD with its position and status");
        COMMAND_INFO.put("notes", "Open your notes");
        COMMAND_INFO.put("search", "Search storage (with text: search the settings)");
        COMMAND_INFO.put("find", "Search settings, HUDs and commands");
        COMMAND_INFO.put("calc", "Calculate an expression");
        COMMAND_INFO.put("chat", "Sky2M chat");
        COMMAND_INFO.put("discord", "Link to the Sky2M Discord");
        COMMAND_INFO.put("debug", "Debug information");
        COMMAND_INFO.put("whatsnew", "What's new in this version");
        COMMAND_INFO.put("perf", "HUD and renderer timings");
        COMMAND_INFO.put("keyconflicts", "Keys used by more than one feature");
        COMMAND_INFO.put("errors", "This session's errors");
        COMMAND_INFO.put("health", "Version, startup time, S2C connection, related mods and errors");
        COMMAND_INFO.put("selftest", "Self-test report");
        COMMAND_INFO.put("protect", "Protect the held or hovered item");
    }
    private static final List<String> FALLBACK_COMMANDS = List.of("settings", "gui", "huds", "notes", "search", "find", "calc",
        "chat", "discord", "debug", "whatsnew", "perf", "keyconflicts", "errors", "selftest", "protect", "recipe", "nick");

    private GlobalSearchIndex() {}

    private static volatile List<Entry> cached;
    private static volatile long cachedAt;
    private static volatile Sky2MConfig cachedFor;
    private static volatile boolean building;

    /**
     * The last built index (null if none yet). Starts a rebuild in the background when there is none or it is over a
     * minute old, so opening the search never reflects over the whole config on the client thread.
     */
    public static List<Entry> cachedOrBuild() {
        List<Entry> current = cached;
        if (cachedFor != Sky2MConfig.current()) current = null; // the config object was replaced: owners are stale
        if (!building && (current == null || System.currentTimeMillis() - cachedAt > 60_000)) {
            building = true;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    Sky2MConfig forConfig = Sky2MConfig.current();
                    List<Entry> built = build();
                    cached = built;
                    cachedFor = forConfig;
                    cachedAt = System.currentTimeMillis();
                } catch (Throwable ignored) {
                } finally {
                    building = false;
                }
            });
        }
        return current;
    }

    public static List<Entry> build() {
        List<Entry> out = new ArrayList<>();
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null) {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                walk(config, "", out, seen, 0);
            } catch (Throwable ignored) {}
        }
        try {
            for (Sky2MHuds.Element e : Sky2MHuds.elements()) {
                out.add(new Entry(Kind.HUD, e.name(), "HUD", "HUDs", Sky2MHuds.settingsSearch(e.id(), e.name()), null, null, e.id()));
            }
        } catch (Throwable ignored) {}
        for (String command : commands()) {
            out.add(new Entry(Kind.COMMAND, "/s2 " + command, COMMAND_INFO.getOrDefault(command, ""), "Commands", command, null, null, null));
        }
        return out;
    }

    private static List<String> commands() {
        List<String> names = new ArrayList<>();
        try {
            var dispatcher = ClientCommands.getActiveDispatcher();
            var root = dispatcher == null ? null : dispatcher.getRoot().getChild("s2");
            if (root != null) {
                for (var child : root.getChildren()) {
                    if (child instanceof com.mojang.brigadier.tree.LiteralCommandNode<?> lit) names.add(lit.getLiteral());
                }
            }
        } catch (Throwable ignored) {}
        for (String f : FALLBACK_COMMANDS) if (!names.contains(f)) names.add(f);
        return names;
    }

    private static void walk(Object owner, String path, List<Entry> out, Set<Object> seen, int depth) {
        if (owner == null || depth > 8 || !seen.add(owner)) return;
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) fields.add(f);
            }
        }
        // Old-style accordions: @ConfigEditorAccordion(id) names the group, @ConfigAccordionId(id) puts options in it.
        Map<Integer, String> accordionNames = new HashMap<>();
        for (Field f : fields) {
            ConfigEditorAccordion acc = f.getAnnotation(ConfigEditorAccordion.class);
            ConfigOption opt = f.getAnnotation(ConfigOption.class);
            if (acc != null && opt != null) accordionNames.put(acc.id(), opt.name());
        }
        for (Field f : fields) {
            ConfigOption opt = f.getAnnotation(ConfigOption.class);
            Category cat = f.getAnnotation(Category.class);
            if (opt == null && cat == null) continue;
            try {
                f.setAccessible(true);
            } catch (Throwable t) {
                continue;
            }
            String name = cat != null ? cat.name() : opt.name();
            String desc = cat != null ? cat.desc() : opt.desc();
            String where = path;
            ConfigAccordionId in = f.getAnnotation(ConfigAccordionId.class);
            if (in != null && accordionNames.containsKey(in.id())) where = join(path, accordionNames.get(in.id()));
            Object value;
            try {
                value = f.get(owner);
            } catch (Throwable t) {
                continue;
            }
            boolean nested = f.getAnnotation(Accordion.class) != null || cat != null;
            if (cat != null) {
                out.add(new Entry(Kind.CATEGORY, name, desc, where, name, f, owner, null));
                walk(value, join(where, name), out, seen, depth + 1);
            } else if (nested && value != null && !f.getType().isPrimitive() && !(value instanceof Runnable)) {
                out.add(new Entry(Kind.SECTION, name, desc, where, name, f, owner, null));
                walk(value, join(where, name), out, seen, depth + 1);
            } else if (f.getAnnotation(ConfigEditorAccordion.class) != null) {
                out.add(new Entry(Kind.SECTION, name, desc, where, name, f, owner, null));
            } else if (f.getAnnotation(ConfigEditorButton.class) != null || value instanceof Runnable) {
                out.add(new Entry(Kind.BUTTON, name, desc, where, name, f, owner, null));
            } else {
                out.add(new Entry(Kind.SETTING, name, desc, where, name, f, owner, null));
            }
        }
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + " > " + name;
    }

    /** Ranked results for {@code query}: exact name, then name prefix, name words, name contains, path, description, fuzzy. */
    public static List<Entry> search(List<Entry> all, String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        if (q.isEmpty()) {
            for (Entry e : all) {
                if (e.kind() == Kind.CATEGORY) out.add(e);
            }
            return out.size() > limit ? out.subList(0, limit) : out;
        }
        String[] tokens = q.split("\\s+");
        List<Object[]> scored = new ArrayList<>();
        for (Entry e : all) {
            int s = score(e, q, tokens);
            if (s > 0) scored.add(new Object[]{e, s});
        }
        scored.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));
        for (Object[] row : scored) {
            if (out.size() >= limit) break;
            out.add((Entry) row[0]);
        }
        return out;
    }

    private static int score(Entry e, String q, String[] tokens) {
        String name = e.name().toLowerCase(Locale.ROOT);
        String desc = e.desc() == null ? "" : e.desc().toLowerCase(Locale.ROOT);
        String path = e.path() == null ? "" : e.path().toLowerCase(Locale.ROOT);
        int whole;
        if (name.equals(q)) whole = 1000;
        else if (name.startsWith(q)) whole = 800;
        else if (hasWordPrefix(name, q)) whole = 600;
        else if (name.contains(q)) whole = 500;
        else whole = 0;
        int total = 0;
        for (String t : tokens) {
            int s;
            if (name.equals(t)) s = 300;
            else if (name.startsWith(t)) s = 200;
            else if (hasWordPrefix(name, t)) s = 150;
            else if (name.contains(t)) s = 100;
            else if (path.contains(t)) s = 50;
            else if (desc.contains(t)) s = 30;
            else if (tokens.length == 1 && t.length() >= 3 && subsequence(name, t)) s = 10;
            else return whole;
            total += s;
        }
        // Categories and sections rank just above plain options of the same quality.
        int bonus = e.kind() == Kind.CATEGORY ? 5 : e.kind() == Kind.SECTION ? 3 : 0;
        return whole + total + bonus;
    }

    private static boolean hasWordPrefix(String text, String prefix) {
        for (int i = 0; i < text.length(); i++) {
            if ((i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) && text.startsWith(prefix, i)) return true;
        }
        return false;
    }

    private static boolean subsequence(String text, String pattern) {
        int p = 0;
        for (int i = 0; i < text.length() && p < pattern.length(); i++) {
            if (text.charAt(i) == pattern.charAt(p)) p++;
        }
        return p == pattern.length();
    }
}
