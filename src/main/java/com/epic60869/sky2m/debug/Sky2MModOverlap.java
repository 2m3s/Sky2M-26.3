package com.epic60869.sky2m.debug;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.config.importer.ModImportSources;
import com.epic60869.sky2m.gui.Sky2MModImportScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.social.Sky2MProfiles;

/**
 * Other mods that do some of what Sky2M does: a one-time chat notice listing them, and /s2 import [mod], which opens
 * the import screen ({@link Sky2MModImportScreen}) to pick which of the other mod's settings to carry over. Import only
 * reads the other mod's config and writes Sky2M's own.
 */
public final class Sky2MModOverlap {
    /** Installed mod id -> the Sky2M features it overlaps. */
    private static final Map<String, String[]> OVERLAPS = new LinkedHashMap<>();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        add("skyhanni", "SkyHanni", "Estimated Item Value", "Bestiary overlay", "Compact chat", "Slayer, Diana and mining HUDs", "Chat translator");
        add("skyblocker", "Skyblocker", "Item price tooltips", "Dungeon map and solvers", "Fishing and mining HUDs", "Search overlays", "Quick navigation");
        add("noammaddons", "NoammAddons", "Dungeon features", "Kuudra features");
        add("odin", "Odin", "Dungeon features", "Chat commands");
        add("devonian", "Devonian", "Dungeon map", "Mining HUDs");
        add("skysoft", "SkySoft", "Item price tooltips", "Mining HUDs");
        add("customscoreboard", "Custom Scoreboard", "Scoreboard cleanup");
    }

    private static void add(String id, String name, String... features) {
        OVERLAPS.put(id, features);
        NAMES.put(id, name);
    }

    private Sky2MModOverlap() {}

    /** Ids of the installed mods that overlap Sky2M. */
    public static List<String> installed() {
        List<String> found = new ArrayList<>();
        for (String id : OVERLAPS.keySet()) if (FabricLoader.getInstance().isModLoaded(id)) found.add(id);
        return found;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(Sky2MModOverlap::notice));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("import")
                    .executes(c -> Compat.queueOpenScreen(new Sky2MModImportScreen(null, null)))
                    .then(ClientCommands.argument("mod", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(
                            ModImportSources.all().stream().map(ModImportSources.Source::id).toList(), b))
                        .executes(c -> Compat.queueOpenScreen(new Sky2MModImportScreen(null, StringArgumentType.getString(c, "mod")))))));
            }
        });
    }

    private static void notice() {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.general.overlapNotice || config.general.overlapNoticeShown) return;
        List<String> ids = installed();
        if (ids.isEmpty()) return;
        config.general.overlapNoticeShown = true;
        Sky2MConfig.saveCurrent(config);
        StringBuilder text = new StringBuilder("Other installed mods overlap some Sky2M features: ");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) text.append("; ");
            text.append(NAMES.get(ids.get(i))).append(" (").append(String.join(", ", OVERLAPS.get(ids.get(i)))).append(")");
        }
        text.append(". Turn one side off to avoid doubled HUDs and tooltips. ");
        Component message = Component.literal(text.toString()).withStyle(ChatFormatting.YELLOW).append(
            Component.literal("[Open /s2]").withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/s2"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the Sky2M settings. \"Warn About Overlapping Mods\" there silences this.")))))
            .append(Component.literal(" "))
            .append(Component.literal("[Import Their Settings]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/s2 import"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Pick which of their on/off settings to copy into Sky2M.")))));
        Sky2MProfiles.say(message);
    }
}
