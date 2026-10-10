package com.epic60869.sky2m;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 nick: sets your nickname in game, for players who have linked their account on shadowisabot.com (the server says
 * so with an accountStatus packet). For everyone else the command doesn't exist: it isn't suggested and can't be run.
 * The server saves the nickname and sends it to everyone (you included) as a nicknameUpdate.
 *
 * <pre>
 * mod -> server: nickSet {enabled: true, name, mode, customHex}  or  nickSet {enabled: false}
 * server -> mod: accountStatus {websiteLinked}, nickResult {ok, message}
 * </pre>
 */
public final class Sky2MNickCommand {
    private static final List<String> STYLES = List.of("Black", "Dark Blue", "Dark Green", "Dark Aqua", "Dark Red",
        "Dark Purple", "Gold", "Gray", "Dark Gray", "Blue", "Green", "Aqua", "Red", "Light Purple", "Yellow", "White",
        "Rainbow");

    /** Whether this account is linked on shadowisabot.com, from the server. */
    private static volatile boolean websiteLinked;

    private Sky2MNickCommand() {}

    public static boolean allowed() {
        return websiteLinked;
    }

    /** The /s2 nick branch; only usable (and suggested) once the server says the account is linked. */
    public static LiteralArgumentBuilder<FabricClientCommandSource> node() {
        return ClientCommands.literal("nick")
            .requires(source -> allowed())
            .executes(c -> usage())
            .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                .suggests((c, builder) -> SharedSuggestionProvider.suggest(suggestions(), builder))
                .executes(c -> set(StringArgumentType.getString(c, "value"))));
    }

    private static List<String> suggestions() {
        List<String> out = new ArrayList<>();
        out.add("off");
        out.add("#FFAA00");
        for (String style : STYLES) out.add(key(style));
        return out;
    }

    private static String key(String style) {
        return style.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static int usage() {
        say(Component.literal("Usage: /s2 nick <name> | /s2 nick <colour> <name> | /s2 nick rainbow <name> | /s2 nick #hex <name> | /s2 nick off")
            .withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** "/s2 nick Steve", "/s2 nick gold Steve", "/s2 nick #3A7BD5 Steve", "/s2 nick rainbow Steve" or "/s2 nick off". */
    private static int set(String input) {
        String value = input.trim();
        JsonObject packet = new JsonObject();
        packet.addProperty("type", "nickSet");
        if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("reset")) {
            packet.addProperty("enabled", false);
        } else {
            String name = value;
            String mode = "Plain";
            String customHex = "";
            String[] parts = value.split("\\s+", 2);
            if (parts.length == 2) {
                String first = parts[0].toLowerCase(Locale.ROOT);
                if (first.matches("#[0-9a-f]{6}")) {
                    customHex = parts[0].toUpperCase(Locale.ROOT);
                    name = parts[1];
                } else {
                    for (String style : STYLES) {
                        if (key(style).equals(first) || style.toLowerCase(Locale.ROOT).replace(" ", "").equals(first)) {
                            mode = style;
                            name = parts[1];
                        }
                    }
                }
            }
            name = name.trim();
            if (name.isEmpty()) return usage();
            if (name.codePointCount(0, name.length()) > 32) {
                say(Component.literal("That nickname is too long (32 characters at most).").withStyle(ChatFormatting.RED));
                return 1;
            }
            if (Sky2MNickFilter.isBlocked(name, Minecraft.getInstance().getUser().getProfileId())) {
                say(Component.literal("That nickname isn't allowed.").withStyle(ChatFormatting.RED));
                return 1;
            }
            packet.addProperty("enabled", true);
            packet.addProperty("name", name);
            packet.addProperty("mode", mode);
            packet.addProperty("customHex", customHex);
        }
        // Changing your nickname needs a checked login.
        Sky2MLogin.whenLoggedIn(() -> Sky2MGlobalChat.send(packet));
        if (!Sky2MLogin.loggedIn()) {
            say(Component.literal("Logging in to S2C...").withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }

    /** accountStatus and nickResult from the server (on the game thread). */
    public static void handle(String type, JsonObject packet) {
        if ("accountStatus".equals(type)) {
            boolean linked = packet.has("websiteLinked") && packet.get("websiteLinked").getAsBoolean();
            if (linked != websiteLinked) {
                websiteLinked = linked;
                refreshSuggestions();
            }
        } else if ("nickResult".equals(type)) {
            boolean ok = packet.has("ok") && packet.get("ok").getAsBoolean();
            String message = packet.has("message") ? packet.get("message").getAsString() : ok ? "Nickname updated." : "Couldn't change your nickname.";
            if (!ok && packet.has("code") && "notLoggedIn".equals(packet.get("code").getAsString())) Sky2MLogin.forget();
            say(Component.literal(message).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
    }

    /**
     * Fabric only copies the client commands you can use into chat suggestions when the server sends its command list,
     * so /s2 nick would stay hidden (or shown) until the next one. Replaying the last list rebuilds them now.
     */
    private static void refreshSuggestions() {
        try {
            var connection = Minecraft.getInstance().getConnection();
            if (connection instanceof net.fabricmc.fabric.impl.command.client.ClientCommandInternals.LastReceivedCommandsPacketAccessor accessor
                && accessor.fabric_api$getLastReceivedCommandsPacket() != null) {
                connection.handleCommands(accessor.fabric_api$getLastReceivedCommandsPacket());
            }
        } catch (Throwable ignored) {
            // Suggestions catch up on the next command list; running the command already checks access.
        }
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(text));
            }
        });
    }
}
