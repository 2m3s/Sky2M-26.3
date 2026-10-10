package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Commands for switching things on and off without opening the menu:
 * <ul>
 *   <li>/s2 toggle &lt;setting&gt;: flips any on/off setting (e.g. /s2 toggle dungeons.caseOpening), with suggestions.</li>
 *   <li>/s2 disableall: turns every feature off, after you click to confirm (like /skyblocker disableall).</li>
 * </ul>
 */
public final class Sky2MToggleCommands {
    private static long disableAllAsked;

    private Sky2MToggleCommands() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("toggle")
                        .then(ClientCommands.argument("setting", StringArgumentType.greedyString())
                            .suggests((c, b) -> SharedSuggestionProvider.suggest(toggles().keySet(), b))
                            .executes(c -> toggle(StringArgumentType.getString(c, "setting")))))
                    .then(ClientCommands.literal("who").executes(c -> {
                        Sky2MGlobalChat.requestWho();
                        return 1;
                    }))
                    .then(ClientCommands.literal("disableall")
                        .executes(c -> askDisableAll())
                        .then(ClientCommands.literal("confirm").executes(c -> disableAll()))));
            }
            dispatcher.register(ClientCommands.literal("s2disableall")
                .executes(c -> askDisableAll())
                .then(ClientCommands.literal("confirm").executes(c -> disableAll())));
        });
    }

    /** A toggle: the object holding the field, the field, and the name shown in the menu. */
    private record Toggle(Object owner, Field field, String name) {}

    /** Every on/off setting in the config, keyed "category.field" (or "category.section.field"). */
    private static Map<String, Toggle> toggles() {
        Map<String, Toggle> out = new LinkedHashMap<>();
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null) collect(config, "", out, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return out;
    }

    private static void collect(Object obj, String prefix, Map<String, Toggle> out, int depth, java.util.Set<Object> seen) {
        if (obj == null || depth > 12 || !seen.add(obj)) return;
        for (Field field : obj.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object value = field.get(obj);
                String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
                if (field.getType() == boolean.class && field.isAnnotationPresent(ConfigEditorBoolean.class)) {
                    ConfigOption option = field.getAnnotation(ConfigOption.class);
                    out.put(path, new Toggle(obj, field, option == null ? field.getName() : option.name()));
                } else if (value != null && value.getClass().getName().startsWith("com.epic60869.sky2m")
                    && !value.getClass().isEnum() && !(value instanceof Runnable)) {
                    collect(value, path, out, depth + 1, seen);
                }
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static int toggle(String input) {
        String wanted = input.trim();
        Map<String, Toggle> all = toggles();
        Toggle toggle = all.get(wanted);
        if (toggle == null) {
            // Also accept the name from the menu, or just the last part ("caseOpening").
            List<Map.Entry<String, Toggle>> matches = new ArrayList<>();
            for (Map.Entry<String, Toggle> e : all.entrySet()) {
                String last = e.getKey().substring(e.getKey().lastIndexOf('.') + 1);
                if (last.equalsIgnoreCase(wanted) || e.getValue().name().equalsIgnoreCase(wanted)) matches.add(e);
            }
            if (matches.size() == 1) toggle = matches.getFirst().getValue();
            else if (matches.size() > 1) return say(Component.literal("More than one setting is called that; use the full name, e.g. " + matches.getFirst().getKey()).withStyle(ChatFormatting.YELLOW));
        }
        if (toggle == null) return say(Component.literal("No setting called \"" + wanted + "\". Press Tab after /s2 toggle to see them.").withStyle(ChatFormatting.RED));
        try {
            boolean now = !toggle.field().getBoolean(toggle.owner());
            toggle.field().setBoolean(toggle.owner(), now);
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
            return say(Component.literal(toggle.name() + " is now ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(now ? "ON" : "OFF").withStyle(now ? ChatFormatting.GREEN : ChatFormatting.RED)));
        } catch (IllegalAccessException e) {
            return say(Component.literal("Couldn't change that setting.").withStyle(ChatFormatting.RED));
        }
    }

    private static int askDisableAll() {
        disableAllAsked = System.currentTimeMillis();
        return say(Component.literal("This turns off every Sky2M feature. ").withStyle(ChatFormatting.RED)
            .append(Component.literal("[Click to confirm]").withStyle(s -> s.withColor(ChatFormatting.DARK_RED).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/s2 disableall confirm"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Turn off every feature (you can turn them back on in /s2)"))))));
    }

    private static int disableAll() {
        // Only right after /s2 disableall, so it can't happen by accident.
        if (System.currentTimeMillis() - disableAllAsked > 60_000) return askDisableAll();
        disableAllAsked = 0;
        int count = 0;
        for (Toggle t : toggles().values()) {
            try {
                if (t.field().getBoolean(t.owner())) {
                    t.field().setBoolean(t.owner(), false);
                    count++;
                }
            } catch (IllegalAccessException ignored) {}
        }
        count += disableOthers(Sky2MConfig.current());
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        com.epic60869.sky2m.features.dungeons.Sky2MDungeons.syncConfig();
        return say(Component.literal("Turned off " + count + " settings. Turn features back on in /s2.").withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Features that aren't an on/off switch in the Sky2M config: dropdowns whose "off" is a choice, and the
     * Skyblocker waypoints, whose options are saved in their own file.
     */
    private static int disableOthers(Sky2MConfig config) {
        int count = 0;
        if (config != null) {
            var pestSpawn = config.farming.garden.pestSpawn;
            if (pestSpawn.chatMessageFormat != com.epic60869.sky2m.features.FeatureConfigs.PestSpawn.ChatMessageFormat.HYPIXEL) {
                pestSpawn.chatMessageFormat = com.epic60869.sky2m.features.FeatureConfigs.PestSpawn.ChatMessageFormat.HYPIXEL;
                count++;
            }
            var dragons = config.dungeons.f7.witherDragons;
            if (dragons.soloPriority != com.epic60869.sky2m.features.FeatureConfigs.DragonSoloPriority.OFF) {
                dragons.soloPriority = com.epic60869.sky2m.features.FeatureConfigs.DragonSoloPriority.OFF;
                count++;
            }
            if (dragons.waypoints != com.epic60869.sky2m.features.FeatureConfigs.DragonWaypoints.OFF) {
                dragons.waypoints = com.epic60869.sky2m.features.FeatureConfigs.DragonWaypoints.OFF;
                count++;
            }
        }
        var waypoints = com.epic60869.sky2m.sb.config.SkyblockerConfigManager.get().uiAndVisuals.waypoints;
        if (waypoints.enableWaypoints || waypoints.enableChatWaypoints) {
            if (waypoints.enableWaypoints) count++;
            if (waypoints.enableChatWaypoints) count++;
            com.epic60869.sky2m.sb.config.SkyblockerConfigManager.updateOnly(c -> {
                c.uiAndVisuals.waypoints.enableWaypoints = false;
                c.uiAndVisuals.waypoints.enableChatWaypoints = false;
            });
        }
        return count;
    }

    private static int say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message));
        });
        return 1;
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
