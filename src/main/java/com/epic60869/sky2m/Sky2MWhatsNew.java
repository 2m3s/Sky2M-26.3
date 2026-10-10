package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What's New, like SkyHanni's update message: the first time you join a server after updating, a chat message with a
 * [What's New] button. It opens {@link Sky2MWhatsNewScreen}: the features added since the version you had before,
 * from the bundled changelog, each with an on/off toggle and a button to its settings. /s2 whatsnew opens it any time.
 *
 * <p>A changelog entry is matched to its setting by its name ("Spirit Bear (Dungeons > Timers, on by default): ...")
 * and the settings path in its brackets.
 */
public final class Sky2MWhatsNew {
    /** "Spirit Bear (Dungeons > Timers, on by default): ..." — the feature name, then what's in the brackets. */
    private static final Pattern ENTRY = Pattern.compile("^(?<name>[^(:]+?)\\s*(?:\\((?<where>[^)]*)\\))?\\s*[:,.]");
    private static final int JOIN_DELAY_TICKS = 60;

    /** A new feature: its name, description, the setting it's under (null if none was found) and its toggle. */
    record Feature(String name, String text, String version, Option option, Option toggle) {}

    /** A config option: its name, the field and the object holding it. */
    record Option(String name, Field field, Object owner) {
        boolean isBoolean() {
            return field.getType() == boolean.class;
        }

        boolean get() {
            try {
                return field.getBoolean(owner);
            } catch (IllegalAccessException e) {
                return false;
            }
        }

        void set(boolean value) {
            try {
                field.setBoolean(owner, value);
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static int joinTicks = -1;

    private Sky2MWhatsNew() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("whatsnew").executes(c -> {
                    open(previousVersionForCommand());
                    return 1;
                })));
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> joinTicks = 0);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (joinTicks < 0 || mc.player == null) return;
            if (++joinTicks < JOIN_DELAY_TICKS) return;
            joinTicks = -1;
            checkForUpdate();
        });
    }

    private static Sky2MConfig.General general() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.general;
    }

    /** Once per update: remember the version, and say what's new. */
    private static void checkForUpdate() {
        Sky2MConfig.General general = general();
        String installed = Sky2MChangelog.installedVersion();
        if (general == null || installed.isEmpty() || installed.equals(general.lastSeenVersion)) return;
        String previous = general.lastSeenVersion;
        general.lastSeenVersion = installed;
        general.whatsNewSince = previous;
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        if (!general.whatsNewMessage || features(previous).isEmpty()) return;

        Component button = Component.literal("[What's New]").withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand("s2 whatsnew"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("See the new features and turn them on or off"))));
        Component message = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(previous.isEmpty() ? "Sky2M " + installed + " is installed! " : "Sky2M updated to " + installed + "! ")
                .withStyle(ChatFormatting.GOLD))
            .append(button);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
    }

    /** The version you updated from, so /s2 whatsnew shows the same list as the update message. */
    private static String previousVersionForCommand() {
        Sky2MConfig.General general = general();
        return general == null ? "" : general.whatsNewSince;
    }

    static void open(String since) {
        Compat.queueOpenScreen(new Sky2MWhatsNewScreen(features(since), Sky2MChangelog.installedVersion()));
    }

    /**
     * The "Added" entries of every version newer than {@code since}, newest first, down to the installed version. When
     * {@code since} is unknown, the installed version's (and anything listed above it, like "Unreleased").
     */
    static List<Feature> features(String sinceVersion) {
        List<Sky2MChangelog.Version> versions = Sky2MChangelog.versions();
        String installed = Sky2MChangelog.installedVersion();
        List<Option> options = options();
        List<Feature> out = new ArrayList<>();
        // A version that isn't in the changelog (a test build): only show what's new in this one.
        String since = versions.stream().anyMatch(v -> v.name().equalsIgnoreCase(sinceVersion)) ? sinceVersion : "";
        boolean reachedInstalled = false;
        for (Sky2MChangelog.Version version : versions) {
            if (!since.isEmpty() && version.name().equalsIgnoreCase(since)) break;
            if (reachedInstalled) break;
            if (version.name().equalsIgnoreCase(installed)) reachedInstalled = since.isEmpty();
            for (Sky2MChangelog.Section section : version.sections()) {
                if (!section.title().equalsIgnoreCase("Added")) continue;
                for (String entry : section.entries()) out.add(feature(entry, version.name(), options));
            }
        }
        return out;
    }

    private static Feature feature(String entry, String version, List<Option> all) {
        Matcher m = ENTRY.matcher(entry);
        boolean found = m.find();
        String name = found ? m.group("name").trim() : entry;
        String where = found && m.group("where") != null ? m.group("where") : "";
        Option option = find(name, where, all);
        Option toggle = option == null ? null : option.isBoolean() ? option : enabledInside(option);
        return new Feature(name, entry, version, option, toggle);
    }

    // ------------------------------------------------------------------------------------------------ settings

    /** Every @ConfigOption and @Category in the config, with its path of category and section names in front. */
    private static List<Option> options() {
        List<Option> out = new ArrayList<>();
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null) collect(config, "", out, 0);
        return out;
    }

    private static void collect(Object owner, String path, List<Option> out, int depth) {
        if (owner == null || depth > 8) return;
        // Dropdown groups (a header field, then the options that name its id): part of the path, not options.
        java.util.Map<Integer, String> groups = new java.util.HashMap<>();
        for (Field field : owner.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = null;
            ConfigOption option = field.getAnnotation(ConfigOption.class);
            Category category = field.getAnnotation(Category.class);
            if (option != null) name = option.name();
            else if (category != null) name = category.name();
            if (name == null) continue;
            ConfigAccordionId inGroup = field.getAnnotation(ConfigAccordionId.class);
            String base = inGroup != null ? groups.getOrDefault(inGroup.id(), path) : path;
            String full = base.isEmpty() ? name : base + " > " + name;
            ConfigEditorAccordion header = field.getAnnotation(ConfigEditorAccordion.class);
            if (header != null) {
                groups.put(header.id(), full);
                continue;
            }
            out.add(new Option(full, field, owner));
            Class<?> type = field.getType();
            if (!type.isPrimitive() && !type.isEnum() && type.getName().startsWith("com.epic60869")) {
                try {
                    collect(field.get(owner), full, out, depth + 1);
                } catch (IllegalAccessException ignored) {}
            }
        }
    }

    private static String leaf(Option option) {
        int i = option.name().lastIndexOf(" > ");
        return i < 0 ? option.name() : option.name().substring(i + 3);
    }

    /**
     * The setting for a feature named {@code name}, listed in the changelog under {@code where} ("Misc > Price Tooltip,
     * on by default"). Within that section: an option with the same name, else one whose name is part of the feature's
     * name ("Order Colours" for "Bazaar Order Colours"), else the section itself.
     */
    private static Option find(String name, String where, List<Option> all) {
        String path = where.split(",")[0].trim();
        List<String> segments = new ArrayList<>();
        if (path.contains(">") || !path.isEmpty() && all.stream().anyMatch(o -> leaf(o).equalsIgnoreCase(path))) {
            for (String s : path.split(">")) if (!s.isBlank()) segments.add(s.trim().toLowerCase(Locale.ROOT));
        }
        List<Option> scope = all.stream().filter(o -> under(o, segments)).toList();
        Option exact = scope.stream().filter(o -> leaf(o).equalsIgnoreCase(name)).findFirst().orElse(null);
        if (exact != null) return exact;
        // The brackets named the section differently: an option with exactly the feature's name anywhere.
        if (!segments.isEmpty()) {
            exact = all.stream().filter(o -> leaf(o).equalsIgnoreCase(name)).findFirst().orElse(null);
            if (exact != null) return exact;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // Only guess from part of the name inside the section the changelog names.
        if (segments.isEmpty()) return null;
        Option partial = scope.stream()
            .filter(o -> leaf(o).length() >= 5 && lower.contains(leaf(o).toLowerCase(Locale.ROOT)))
            .filter(o -> o.isBoolean() || enabledInside(o) != null)
            .max(java.util.Comparator.comparingInt(o -> leaf(o).length())).orElse(null);
        if (partial != null) return partial;
        // The section named last in the brackets ("Mute Overflow Drop Sound (Farming > Garden)" has no toggle of its
        // own name, but the section opens the right settings).
        String last = segments.getLast();
        return all.stream().filter(o -> leaf(o).toLowerCase(Locale.ROOT).contains(last) && under(o, segments.subList(0, segments.size() - 1)))
            .findFirst().orElse(null);
    }

    /** Whether the option's path contains the segments in order (sections in between may be skipped). */
    private static boolean under(Option option, List<String> segments) {
        String[] parts = option.name().toLowerCase(Locale.ROOT).split(" > ");
        int i = 0;
        // "Timers" finds "Timers and Alerts", "Price Tooltip" finds "Item Price Tooltip".
        for (int p = 0; p < parts.length - 1 && i < segments.size(); p++) if (parts[p].contains(segments.get(i))) i++;
        return i == segments.size();
    }

    /** A section's own on/off switch: its "Enabled" option. */
    private static Option enabledInside(Option section) {
        if (section.isBoolean()) return section;
        try {
            Object inner = section.field().get(section.owner());
            if (inner == null) return null;
            for (Field field : inner.getClass().getFields()) {
                ConfigOption option = field.getAnnotation(ConfigOption.class);
                if (option != null && field.getType() == boolean.class
                    && (option.name().equalsIgnoreCase("Enabled") || option.name().equalsIgnoreCase("Enable"))) {
                    return new Option(section.name() + " > " + option.name(), field, inner);
                }
            }
        } catch (IllegalAccessException ignored) {}
        return null;
    }
}
