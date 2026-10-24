package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MKeyMappings;
import com.epic60869.sky2m.commandkeys.config.Config;
import com.epic60869.sky2m.commandkeys.config.Keybind;
import com.epic60869.sky2m.commandkeys.config.Macro;
import com.epic60869.sky2m.commandkeys.mixin.accessor.KeyMappingAccessor;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.BindKeys;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Warns in chat when two features are bound to the same key: Sky2M's keybind settings (found by walking the config
 * reflectively), Sky2M's key mappings, Command Keys macros, and (only against a Sky2M key) vanilla and other mods'
 * key mappings. Keys that only work inside one menu (the Wardrobe, the Leap menu, the Fusion Box...) don't clash with
 * keys used in the world. Checks a few seconds after joining and when a key changes; /s2 keyconflicts lists them.
 */
public final class KeyConflicts {
    private KeyConflicts() {}

    /** Where a key is read. Two keys only clash in the same context ({@link #GUI} also clashes with every menu). */
    private static final String WORLD = "world";
    /** Any container screen (tooltips, the inventory, the item list). */
    private static final String GUI = "gui";
    private static final String CHAT = "chat";

    /**
     * Settings that only work inside a menu or chat, by "ConfigClass" or "ConfigClass.field". Everything else is read
     * in the world.
     */
    private static final Map<String, String> CONTEXTS = Map.ofEntries(
        Map.entry("TooltipScroll", GUI),
        Map.entry("SlotLocking", GUI),
        Map.entry("ItemList", GUI),
        Map.entry("CopyChat", CHAT),
        Map.entry("WardrobeHotkeys", "gui:wardrobe"),
        Map.entry("LeapMenu", "gui:leap"),
        Map.entry("ShardTracker", "gui:hunting box"),
        Map.entry("FusionKeybinds.repeatFusionKeybind", "gui:fusion box"),
        Map.entry("FusionKeybinds.confirmFusionKeybind", "gui:confirm fusion"),
        Map.entry("VisitorConfig.acceptHotkey", "gui:visitor"),
        Map.entry("RewardWarning", "gui:visitor")
    );

    private static final int JOIN_DELAY_TICKS = 100;
    private static final int POLL_TICKS = 40;

    private enum Source { CONFIG, SKY2M_MAPPING, OTHER_MAPPING, MACRO }

    private record Bind(InputConstants.Key key, String name, String path, String context, Source source) {}

    /** A keybind setting found in the config, kept so the change poll doesn't walk the config again. */
    private record ConfigField(Object owner, Field field, String path, String context) {}

    private static Object cachedRoot;
    private static List<ConfigField> cachedFields = List.of();

    private static boolean joinedOnce;
    private static int joinCountdown = -1;
    private static boolean ready;
    private static int pollTicks;
    private static long lastHash;
    /** Conflicts already shown this session, so the same one isn't repeated until the keys change. */
    private static final Set<String> warned = new LinkedHashSet<>();

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            if (!joinedOnce) {
                joinedOnce = true;
                joinCountdown = JOIN_DELAY_TICKS;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(KeyConflicts::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String root : Compat.COMMAND_ROOTS) dispatcher.register(ClientCommands.literal(root)
                .then(ClientCommands.literal("keyconflicts").executes(ctx -> {
                    listAll();
                    return 1;
                })));
        });
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;
        if (joinCountdown > 0 && --joinCountdown == 0) {
            joinCountdown = -1;
            ready = true;
            lastHash = hash();
            warnNew();
            return;
        }
        if (!ready || ++pollTicks % POLL_TICKS != 0) return;
        long now = hash();
        if (now == lastHash) return;
        lastHash = now;
        warnNew();
    }

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.general.warnKeyConflicts;
    }

    /** Shows the conflicts that weren't shown yet (forgetting ones that went away, so they warn again if back). */
    private static void warnNew() {
        Map<String, List<Bind>> conflicts = findConflicts();
        warned.retainAll(conflicts.keySet());
        if (!enabled()) return;
        for (Map.Entry<String, List<Bind>> conflict : conflicts.entrySet()) {
            if (warned.add(conflict.getKey())) Sky2MAlerts.chat(message(conflict.getValue()));
        }
    }

    /** One line of /s2 keybinds: a Sky2M key, where it's set, and what else uses the same key (empty if nothing). */
    public record SheetRow(String name, String path, String key, String where, String clashesWith) {}

    /** Every Sky2M key (settings, Sky2M's Controls and Command Keys macros), sorted by where it's set, then name. */
    public static List<SheetRow> sheet() {
        List<Bind> binds = collect();
        Map<String, List<Bind>> conflicts = findConflicts();
        List<SheetRow> out = new ArrayList<>();
        for (Bind bind : binds) {
            if (bind.source() == Source.OTHER_MAPPING) continue;
            List<String> others = new ArrayList<>();
            for (List<Bind> group : conflicts.values()) {
                if (!group.contains(bind)) continue;
                for (Bind other : group) if (other != bind && !others.contains(other.name())) others.add(other.name());
            }
            String where = switch (bind.source()) {
                case CONFIG -> "Settings";
                case MACRO -> "Command Keys";
                default -> "Controls";
            };
            out.add(new SheetRow(bind.name(), bind.path(), bind.key().getDisplayName().getString(), where, String.join(", ", others)));
        }
        out.sort(java.util.Comparator.comparing(SheetRow::where).thenComparing(SheetRow::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static void listAll() {
        Map<String, List<Bind>> conflicts = findConflicts();
        if (conflicts.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No key conflicts.").withStyle(ChatFormatting.GREEN));
            return;
        }
        Sky2MAlerts.chat(Component.literal(conflicts.size() + " key conflict" + (conflicts.size() == 1 ? "" : "s") + " (hover for where to change them):").withStyle(ChatFormatting.GOLD));
        for (List<Bind> binds : conflicts.values()) Sky2MAlerts.chat(message(binds));
    }

    private static Component message(List<Bind> binds) {
        MutableComponent hover = Component.literal(binds.get(0).key().getDisplayName().getString()).withStyle(ChatFormatting.YELLOW);
        List<String> names = new ArrayList<>();
        for (Bind bind : binds) {
            names.add(bind.name());
            String where = switch (bind.source()) {
                case CONFIG -> "/s2: ";
                case MACRO -> "/s2 keys: ";
                default -> "Controls: ";
            };
            hover.append(Component.literal("\n" + where).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(bind.path()).withStyle(ChatFormatting.GRAY));
        }
        hover.append(Component.literal("\nChange one of them so each key does one thing.").withStyle(ChatFormatting.DARK_GRAY));
        Component hoverText = hover;
        return Component.literal(binds.get(0).key().getDisplayName().getString() + " is used by: " + String.join(", ", names))
            .withStyle(ChatFormatting.YELLOW)
            .withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hoverText)));
    }

    /** The current conflicts, by a signature of the key and the binds on it (stable while nothing changes). */
    private static Map<String, List<Bind>> findConflicts() {
        Map<InputConstants.Key, List<Bind>> byKey = new LinkedHashMap<>();
        for (Bind bind : collect()) byKey.computeIfAbsent(bind.key(), k -> new ArrayList<>()).add(bind);
        Map<String, List<Bind>> conflicts = new LinkedHashMap<>();
        for (Map.Entry<InputConstants.Key, List<Bind>> entry : byKey.entrySet()) {
            List<Bind> binds = entry.getValue();
            if (binds.size() < 2) continue;
            Set<Bind> involved = new LinkedHashSet<>();
            for (int i = 0; i < binds.size(); i++) {
                for (int j = i + 1; j < binds.size(); j++) {
                    if (clash(binds.get(i), binds.get(j))) {
                        involved.add(binds.get(i));
                        involved.add(binds.get(j));
                    }
                }
            }
            if (involved.size() < 2) continue;
            List<String> paths = new ArrayList<>();
            for (Bind bind : involved) paths.add(bind.path());
            Collections.sort(paths);
            conflicts.put(entry.getKey().getName() + "|" + String.join("|", paths), new ArrayList<>(involved));
        }
        return conflicts;
    }

    private static boolean clash(Bind a, Bind b) {
        if (!sameContext(a.context(), b.context())) return false;
        Source x = a.source(), y = b.source();
        // Other mods clashing with each other (or vanilla) is not Sky2M's business; Command Keys handles its macros
        // against key mappings itself (its conflict strategy), and several macros on one key can be on purpose.
        if (x == Source.OTHER_MAPPING && y == Source.OTHER_MAPPING) return false;
        if (x == Source.MACRO && y != Source.CONFIG) return false;
        if (y == Source.MACRO && x != Source.CONFIG) return false;
        return true;
    }

    private static boolean sameContext(String a, String b) {
        if (a.equals(b)) return true;
        return (a.equals(GUI) && b.startsWith("gui:")) || (b.equals(GUI) && a.startsWith("gui:"));
    }

    // Collecting

    private static List<Bind> collect() {
        List<Bind> binds = new ArrayList<>();
        refreshConfigFields();
        for (ConfigField cf : cachedFields) {
            InputConstants.Key key = configKey(readInt(cf));
            if (key != null) binds.add(new Bind(key, shortName(cf.path()), cf.path(), cf.context(), Source.CONFIG));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            for (KeyMapping mapping : mc.options.keyMappings) {
                if (mapping.getCategory().equals(KeyMapping.Category.DEBUG)) continue;
                InputConstants.Key key = ((KeyMappingAccessor) mapping).commandkeys$getKey();
                if (key == null || key.equals(InputConstants.UNKNOWN)) continue;
                boolean ours = mapping.getCategory().equals(Sky2MKeyMappings.CATEGORY);
                String name = Component.translatable(mapping.getName()).getString();
                String path = mapping.getCategory().label().getString() + " > " + name;
                binds.add(new Bind(key, name, path, WORLD, ours ? Source.SKY2M_MAPPING : Source.OTHER_MAPPING));
            }
        }
        try {
            for (Macro macro : Config.get().activeProfile().getMacros()) {
                Keybind keybind = macro.getKeybind();
                if (keybind == null || keybind.getKey().equals(InputConstants.UNKNOWN)) continue;
                // A two-key combination doesn't clash with the plain key.
                if (!keybind.getLimitKey().equals(InputConstants.UNKNOWN)) continue;
                String name = "Macro " + (macro.name == null || macro.name.isBlank() ? "(unnamed)" : macro.name);
                binds.add(new Bind(keybind.getKey(), name, "Command Keys > " + name, WORLD, Source.MACRO));
            }
        } catch (Exception ignored) {
            // Command Keys not loaded yet.
        }
        return binds;
    }

    /** A cheap hash of every key, to notice a change without walking the config again. */
    private static long hash() {
        Sky2MConfig root = Sky2MConfig.current();
        if (root != cachedRoot) refreshConfigFields();
        long h = 17;
        for (ConfigField cf : cachedFields) h = h * 31 + readInt(cf);
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            for (KeyMapping mapping : mc.options.keyMappings) {
                InputConstants.Key key = ((KeyMappingAccessor) mapping).commandkeys$getKey();
                h = h * 31 + (key == null ? 0 : key.hashCode());
            }
        }
        try {
            for (Macro macro : Config.get().activeProfile().getMacros()) {
                Keybind keybind = macro.getKeybind();
                h = h * 31 + (keybind == null ? 0 : keybind.hashCode());
            }
        } catch (Exception ignored) {
        }
        return h;
    }

    private static int readInt(ConfigField cf) {
        try {
            return cf.field().getInt(cf.owner());
        } catch (Exception e) {
            return InputConstants.UNKNOWN.getValue();
        }
    }

    /** A MoulConfig keybind value as a key, or null when unset (see BindKeys.mouseButton for mouse buttons). */
    private static InputConstants.Key configKey(int bind) {
        if (bind == InputConstants.UNKNOWN.getValue() || bind == 0) return null;
        // Mouse buttons are stored as -100 + button on 26.3 (see Input.bindMouseButton).
        int button = BindKeys.mouseButton(bind);
        if (button >= 0) return InputConstants.Type.MOUSE.getOrCreate(button);
        if (bind < 0) return null;
        return InputConstants.Type.KEYBOARD.getOrCreate(bind);
    }

    /** The setting's own name, with its section's when the name alone ("Key") says little. */
    private static String shortName(String path) {
        String[] parts = path.split(" > ");
        String last = parts[parts.length - 1];
        if (parts.length > 1 && !last.trim().contains(" ")) return parts[parts.length - 2] + " " + last;
        return last;
    }

    /** Walks the config (only when it was replaced or on a full check) to find every keybind setting. */
    private static void refreshConfigFields() {
        Sky2MConfig root = Sky2MConfig.current();
        cachedRoot = root;
        if (root == null) {
            cachedFields = List.of();
            return;
        }
        List<ConfigField> found = new ArrayList<>();
        walk(root, "", Collections.newSetFromMap(new IdentityHashMap<>()), found);
        cachedFields = found;
    }

    private static void walk(Object obj, String path, Set<Object> seen, List<ConfigField> out) {
        if (obj == null || !seen.add(obj)) return;
        Class<?> type = obj.getClass();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            ConfigOption option = field.getAnnotation(ConfigOption.class);
            Category category = field.getAnnotation(Category.class);
            String label = category != null ? category.name() : option != null ? option.name() : null;
            Class<?> fieldType = field.getType();
            if (fieldType == int.class) {
                if (!field.isAnnotationPresent(ConfigEditorKeybind.class)) continue;
                try {
                    field.setAccessible(true);
                } catch (Exception e) {
                    continue;
                }
                String name = label != null ? label : field.getName();
                out.add(new ConfigField(obj, field, join(path, name), context(type, field)));
                continue;
            }
            // Saved settings only: skips runtime state kept in config classes.
            if (!field.isAnnotationPresent(com.google.gson.annotations.Expose.class) || !isConfigType(fieldType)) continue;
            Object child;
            try {
                field.setAccessible(true);
                child = field.get(obj);
            } catch (Exception e) {
                continue;
            }
            if (child == null || !isConfigType(child.getClass())) continue;
            walk(child, label != null ? join(path, label) : path, seen, out);
        }
    }

    /** Only Sky2M's own config objects are walked (not strings, lists, enums, lambdas or Minecraft types). */
    private static boolean isConfigType(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type.isArray() || type.isInterface() || type.isSynthetic()) return false;
        String name = type.getName();
        return name.startsWith("com.epic60869.sky2m.") && !name.contains("$$Lambda") && !name.contains("$Lambda");
    }

    private static String context(Class<?> owner, Field field) {
        String cls = owner.getSimpleName();
        String context = CONTEXTS.get(cls + "." + field.getName());
        if (context == null) context = CONTEXTS.get(cls);
        if ("gui:wardrobe".equals(context) && field.getName().startsWith("loadout")) context = "gui:loadouts";
        return context == null ? WORLD : context;
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + " > " + name;
    }
}
