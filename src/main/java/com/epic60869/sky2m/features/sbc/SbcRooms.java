package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sky2M chat rooms and the local mute list. Rooms are private chats on the Sky2M server: /s2c join <room> (creates it if
 * nobody is in it), /s2c leave <room>, /s2c rooms, /s2c use <room> (plain /s2c messages go there; /s2c use goes back to
 * global) and /s2c room <room> <message>. Muting hides a player's /s2c messages on your screen only (/s2c mute <player>);
 * blocking is the server-side ignore list (/s2c block <player> = /s2 ignore).
 */
public final class SbcRooms {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("chat-rooms.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ROOM = Pattern.compile("[a-z0-9_-]{1,24}");

    private static final class Data {
        Set<String> rooms = new LinkedHashSet<>();
        String active = "";
        Set<String> muted = new LinkedHashSet<>();
    }

    private static Data data;
    /** Rejoining after a reconnect is quiet until this time. */
    private static volatile long quietUntil;

    private SbcRooms() {}

    private static synchronized Data data() {
        if (data == null) {
            try {
                if (Files.exists(FILE)) data = GSON.fromJson(Files.readString(FILE), Data.class);
            } catch (Exception ignored) {}
            if (data == null) data = new Data();
            if (data.rooms == null) data.rooms = new LinkedHashSet<>();
            if (data.muted == null) data.muted = new LinkedHashSet<>();
            if (data.active == null) data.active = "";
        }
        return data;
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(data()));
        } catch (Exception ignored) {}
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            LiteralArgumentBuilder<FabricClientCommandSource> s2c = ClientCommands.literal("s2c");
            addRoomCommands(s2c);
            addMuteCommands(s2c);
            dispatcher.register(s2c);
            for (String root : Compat.COMMAND_ROOTS) {
                LiteralArgumentBuilder<FabricClientCommandSource> room = ClientCommands.literal("room");
                addRoomCommands(room);
                LiteralArgumentBuilder<FabricClientCommandSource> r = ClientCommands.literal(root).then(room);
                addMuteCommands(r);
                dispatcher.register(r);
            }
        });
    }

    private static void addRoomCommands(LiteralArgumentBuilder<FabricClientCommandSource> p) {
        p.then(ClientCommands.literal("join").executes(c -> usage("/s2c join <room> [password]"))
            .then(roomArg().executes(c -> SbcCommands.run(() -> join(StringArgumentType.getString(c, "room"), "", false)))
                .then(ClientCommands.argument("password", StringArgumentType.greedyString())
                    .executes(c -> SbcCommands.run(() -> join(StringArgumentType.getString(c, "room"), StringArgumentType.getString(c, "password"), false))))));
        p.then(ClientCommands.literal("create").executes(c -> usage("/s2c create <room> [password]"))
            .then(roomArg().executes(c -> SbcCommands.run(() -> join(StringArgumentType.getString(c, "room"), "", true)))
                .then(ClientCommands.argument("password", StringArgumentType.greedyString())
                    .executes(c -> SbcCommands.run(() -> join(StringArgumentType.getString(c, "room"), StringArgumentType.getString(c, "password"), true))))));
        p.then(ClientCommands.literal("leave")
            .executes(c -> SbcCommands.run(() -> leave(data().active)))
            .then(roomArg().executes(c -> SbcCommands.run(() -> leave(StringArgumentType.getString(c, "room"))))));
        p.then(ClientCommands.literal("rooms").executes(c -> SbcCommands.run(SbcRooms::list))
            .then(ClientCommands.literal("public").executes(c -> SbcCommands.run(() -> {
                browsedRooms = System.currentTimeMillis();
                SbcNet.send(Sbc.packet("roomBrowse"));
            }))));
        p.then(ClientCommands.literal("use")
            .executes(c -> SbcCommands.run(() -> use("")))
            .then(roomArg().executes(c -> SbcCommands.run(() -> use(StringArgumentType.getString(c, "room"))))));
        p.then(ClientCommands.literal("room").executes(c -> usage("/s2c room <room> <message>"))
            .then(roomArg().executes(c -> usage("/s2c room <room> <message|invite|uninvite|kick|password|invite-only|public|private>"))
                .then(adminPlayer("invite")).then(adminPlayer("uninvite")).then(adminPlayer("kick"))
                .then(ClientCommands.literal("password").executes(c -> usage("/s2c room <room> password <new password|off>"))
                    .then(ClientCommands.argument("value", StringArgumentType.greedyString())
                        .executes(c -> SbcCommands.run(() -> admin(StringArgumentType.getString(c, "room"), "password", "", StringArgumentType.getString(c, "value"))))))
                .then(ClientCommands.literal("invite-only").executes(c -> usage("/s2c room <room> invite-only <on|off>"))
                    .then(ClientCommands.literal("on").executes(c -> SbcCommands.run(() -> admin(StringArgumentType.getString(c, "room"), "inviteOnly", "", "on"))))
                    .then(ClientCommands.literal("off").executes(c -> SbcCommands.run(() -> admin(StringArgumentType.getString(c, "room"), "inviteOnly", "", "off")))))
                .then(ClientCommands.literal("public").executes(c -> SbcCommands.run(() -> admin(StringArgumentType.getString(c, "room"), "visibility", "", "public"))))
                .then(ClientCommands.literal("private").executes(c -> SbcCommands.run(() -> admin(StringArgumentType.getString(c, "room"), "visibility", "", "private"))))
                .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                    .executes(c -> SbcCommands.run(() -> say(StringArgumentType.getString(c, "room"), StringArgumentType.getString(c, "message")))))));
    }

    private static void addMuteCommands(LiteralArgumentBuilder<FabricClientCommandSource> p) {
        p.then(ClientCommands.literal("mute").executes(c -> usage("/s2c mute <player>"))
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .executes(c -> SbcCommands.run(() -> mute(StringArgumentType.getString(c, "player"), true)))));
        p.then(ClientCommands.literal("unmute").executes(c -> usage("/s2c unmute <player>"))
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(data().muted, b))
                .executes(c -> SbcCommands.run(() -> mute(StringArgumentType.getString(c, "player"), false)))));
        p.then(ClientCommands.literal("mutes").executes(c -> SbcCommands.run(SbcRooms::listMuted)));
        p.then(ClientCommands.literal("block").executes(c -> usage("/s2c block <player>"))
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .executes(c -> SbcCommands.run(() -> block(StringArgumentType.getString(c, "player"), true)))));
        p.then(ClientCommands.literal("unblock").executes(c -> usage("/s2c unblock <player>"))
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .executes(c -> SbcCommands.run(() -> block(StringArgumentType.getString(c, "player"), false)))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource, String> roomArg() {
        return ClientCommands.argument("room", StringArgumentType.word())
            .suggests((c, b) -> SharedSuggestionProvider.suggest(data().rooms, b));
    }

    private static int usage(String text) {
        Sbc.say(Component.literal("Usage: " + text).withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static String clean(String room) {
        String r = room == null ? "" : room.trim().toLowerCase(Locale.ROOT);
        if (r.startsWith("#")) r = r.substring(1);
        return ROOM.matcher(r).matches() ? r : "";
    }

    // ------------------------------------------------------------------------------------------------ rooms

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> adminPlayer(String action) {
        return ClientCommands.literal(action).executes(c -> usage("/s2c room <room> " + action + " <player>"))
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .executes(c -> SbcCommands.run(() -> admin(StringArgumentType.getString(c, "room"), action,
                    StringArgumentType.getString(c, "player"), ""))));
    }

    /** Owner commands: invite, uninvite, kick (player), password, inviteOnly, visibility (value). The server checks ownership. */
    private static void admin(String name, String action, String player, String value) {
        String room = clean(name);
        if (room.isEmpty()) {
            Sbc.error("Room names are 1-24 letters, numbers, - or _.");
            return;
        }
        JsonObject p = Sbc.packet("roomAdmin");
        p.addProperty("room", room);
        p.addProperty("action", action);
        if (!player.isEmpty()) p.addProperty("player", player.trim());
        if (!value.isEmpty()) p.addProperty("value", value.trim());
        SbcNet.send(p);
    }

    private static volatile long browsedRooms;

    private static void join(String name, String password, boolean create) {
        String room = clean(name);
        if (room.isEmpty()) {
            Sbc.error("Room names are 1-24 letters, numbers, - or _.");
            return;
        }
        JsonObject p = Sbc.packet(create ? "roomCreate" : "roomJoin");
        p.addProperty("room", room);
        if (!password.isBlank()) p.addProperty("password", password.trim());
        SbcNet.send(p);
    }

    private static void leave(String name) {
        String room = clean(name);
        if (room.isEmpty()) {
            usage("/s2c leave <room>");
            return;
        }
        JsonObject p = Sbc.packet("roomLeave");
        p.addProperty("room", room);
        SbcNet.send(p);
    }

    private static void list() {
        Data d = data();
        if (d.rooms.isEmpty()) {
            Sbc.say(Component.literal("You aren't in any rooms. /s2c join <room> makes or joins one.").withStyle(ChatFormatting.GRAY));
            return;
        }
        // The reply (counts of who is in each) is shown when it arrives.
        askedRooms = System.currentTimeMillis();
        SbcNet.send(Sbc.packet("roomList"));
    }

    private static volatile long askedRooms;

    private static void use(String name) {
        Data d = data();
        if (name.isBlank()) {
            d.active = "";
            save();
            Sbc.say(Component.literal("Your /s2c messages go to global Sky2M chat.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        String room = clean(name);
        if (room.isEmpty() || !d.rooms.contains(room)) {
            Sbc.error("Join the room first: /s2c join " + (room.isEmpty() ? "<room>" : room));
            return;
        }
        d.active = room;
        save();
        Sbc.say(Component.literal("Your /s2c messages now go to room #" + room + ". /s2c use returns to global.").withStyle(ChatFormatting.AQUA));
    }

    private static void say(String name, String message) {
        String room = clean(name);
        if (room.isEmpty() || !data().rooms.contains(room)) {
            Sbc.error("You aren't in that room. /s2c join " + (room.isEmpty() ? "<room>" : room));
            return;
        }
        JsonObject p = Sbc.packet("message");
        p.addProperty("message", message.trim());
        p.addProperty("room", room);
        com.epic60869.sky2m.Sky2MGlobalChat.sendPacket(p);
    }

    /** The room plain messages go to, or "" for global. Used by SbcChat.outgoing. */
    public static String activeRoom() {
        Data d = data();
        return d.active != null && d.rooms.contains(d.active) ? d.active : "";
    }

    /** Called on every new connection: the server forgets rooms with the connection, so join them again. */
    public static void onConnected() {
        Data d = data();
        if (d.rooms.isEmpty()) return;
        quietUntil = System.currentTimeMillis() + 5_000L;
        for (String room : d.rooms) {
            JsonObject p = Sbc.packet("roomJoin");
            p.addProperty("room", room);
            SbcNet.sendQuietly(p);
        }
    }

    static void handle(String type, JsonObject packet) {
        boolean quiet = System.currentTimeMillis() < quietUntil;
        switch (type) {
            case "roomResult" -> {
                boolean ok = Sbc.bool(packet, "ok");
                String room = Sbc.str(packet, "room");
                String action = Sbc.str(packet, "action");
                Data d = data();
                if (ok && ("roomJoin".equals(action) || "roomCreate".equals(action))) d.rooms.add(room);
                // A saved room that now refuses us (kicked, password changed, invite-only): stop retrying it every reconnect.
                if (!ok && quiet && "roomJoin".equals(action) && !room.isEmpty() && !Sbc.str(packet, "message").contains("already")) {
                    d.rooms.remove(room);
                    if (room.equals(d.active)) d.active = "";
                    save();
                    Sbc.say(Component.literal("Couldn't rejoin #" + room + ": " + Sbc.str(packet, "message")).withStyle(ChatFormatting.RED));
                    return;
                }
                if ((ok || Sbc.str(packet, "message").contains("not in")) && "roomLeave".equals(action)) {
                    d.rooms.remove(room);
                    if (room.equals(d.active)) d.active = "";
                }
                if (ok || !room.isEmpty()) save();
                if (quiet) return;
                String message = Sbc.str(packet, "message");
                if (!message.isBlank()) {
                    Sbc.say(Component.literal(message).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
                }
            }
            case "roomEvent" -> {
                String user = Sbc.str(packet, "username");
                Sbc.say(Component.literal("#" + Sbc.str(packet, "room") + ": " + user + " " + Sbc.str(packet, "event") + " the room.")
                    .withStyle(ChatFormatting.GRAY));
            }
            case "roomInvite" -> {
                String room = Sbc.str(packet, "room");
                String from = Sbc.str(packet, "from");
                MutableComponent msg = Component.literal((from.isBlank() ? "Someone" : from) + " invited you to the chat room #" + room + ". ")
                    .withStyle(ChatFormatting.AQUA);
                msg.append(SbcSettingsSync.button("[Join]", ChatFormatting.GREEN, "/s2c join " + room, "Join #" + room + " (no password needed)"));
                Sbc.say(msg);
            }
            case "roomKicked" -> {
                String room = Sbc.str(packet, "room");
                Data d = data();
                if (d.rooms.remove(room)) {
                    if (room.equals(d.active)) d.active = "";
                    save();
                }
                Sbc.say(Component.literal("You were removed from room #" + room + (Sbc.str(packet, "by").isBlank() ? "." : " by " + Sbc.str(packet, "by") + "."))
                    .withStyle(ChatFormatting.RED));
            }
            case "roomBrowse" -> {
                if (System.currentTimeMillis() - browsedRooms > 10_000L) return;
                browsedRooms = 0;
                JsonArray list = Sbc.arr(packet, "rooms");
                if (list.isEmpty()) {
                    Sbc.say(Component.literal("No public rooms right now. /s2c room <room> public makes yours show up here.").withStyle(ChatFormatting.GRAY));
                    return;
                }
                MutableComponent out = Component.literal("Public rooms:").withStyle(ChatFormatting.YELLOW);
                for (JsonElement e : list) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String name = Sbc.str(o, "name");
                    String tags = (Sbc.bool(o, "passwordProtected") ? " [password]" : "") + (Sbc.bool(o, "inviteOnly") ? " [invite-only]" : "");
                    out.append(Component.literal("\n  #" + name).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" (" + Sbc.lng(o, "members", 1) + " in room)" + tags).withStyle(ChatFormatting.GRAY))
                        .append(SbcSettingsSync.button(" [Join]", ChatFormatting.GREEN, "/s2c join " + name, "Join #" + name));
                }
                Sbc.say(out);
            }
            case "rooms" -> {
                if (System.currentTimeMillis() - askedRooms > 10_000L) return;
                askedRooms = 0;
                MutableComponent out = Component.literal("Your rooms:").withStyle(ChatFormatting.YELLOW);
                for (JsonElement e : Sbc.arr(packet, "rooms")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String name = Sbc.str(o, "name");
                    out.append(Component.literal("\n  #" + name).withStyle(ChatFormatting.AQUA))
                        .append(Component.literal(" (" + Sbc.lng(o, "members", 1) + " in room)" + (name.equals(data().active) ? " [active]" : "")
                                + (Sbc.bool(o, "owner") ? " [owner]" : "") + (Sbc.bool(o, "passwordProtected") ? " [password]" : "")
                                + (Sbc.bool(o, "inviteOnly") ? " [invite-only]" : "") + (Sbc.bool(o, "public") ? " [public]" : ""))
                            .withStyle(ChatFormatting.GRAY));
                }
                Sbc.say(out);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------------------------------------ mute and block

    /** Whether messages from this sender are hidden. Matches the name (Discord names without "[Discord] ") or UUID. */
    public static boolean isMuted(String username, String uuid) {
        Data d = data();
        if (d.muted.isEmpty()) return false;
        String name = username == null ? "" : username.replaceFirst("^\\[Discord] ", "").toLowerCase(Locale.ROOT);
        return d.muted.contains(name) || (uuid != null && !uuid.isBlank() && d.muted.contains(uuid.toLowerCase(Locale.ROOT)));
    }

    private static void mute(String player, boolean add) {
        String name = player.trim().replaceFirst("^\\[Discord] ", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return;
        Data d = data();
        boolean changed = add ? d.muted.add(name) : d.muted.remove(name);
        if (changed) save();
        Sbc.say(Component.literal(changed
                ? (add ? "Muted " + name + ": you won't see their Sky2M chat messages (only on your screen)." : "Unmuted " + name + ".")
                : (add ? name + " is already muted." : name + " isn't muted."))
            .withStyle(changed ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static void listMuted() {
        Data d = data();
        if (d.muted.isEmpty()) {
            Sbc.say(Component.literal("You haven't muted anyone. /s2c mute <player> hides their messages.").withStyle(ChatFormatting.GRAY));
            return;
        }
        MutableComponent out = Component.literal("Muted in Sky2M chat:").withStyle(ChatFormatting.YELLOW);
        for (String name : d.muted) {
            out.append(Component.literal("\n  " + name).withStyle(ChatFormatting.WHITE))
                .append(SbcSettingsSync.button(" [Unmute]", ChatFormatting.GREEN, "/s2c unmute " + name, "Show their messages again"));
        }
        Sbc.say(out);
    }

    /** Block = the server-side ignore list (they can't reach you at all, on any device). */
    private static void block(String player, boolean add) {
        JsonObject p = Sbc.packet(add ? "ignoreAdd" : "ignoreRemove");
        p.addProperty("username", player.trim());
        SbcNet.sendAuthed(p);
    }
}
