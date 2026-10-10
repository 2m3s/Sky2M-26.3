package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MNick;
import com.epic60869.sky2m.Sky2MLogin;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Other Sky2M players: /s2 who (a screen, or a chat list), your presence (area, server and AFK, sent when they
 * change and at most every 2 s), the ignore list, Sky2M friends (requests, the friends screen, online popups) and
 * who may see your location.
 */
public final class SbcSocial {
    public record Prefix(String label, int colour, boolean bold) {}

    public record Player(String username, UUID uuid, String nickname, boolean nicknameEnabled, String mode, String hex,
                         String hex2, String font, Prefix prefix, SbcCosmetics.Symbol badge, boolean friend,
                         long onlineSince, String modVersion, String area, String server, String island, boolean afk,
                         boolean online, long lastSeen) {
        /** Styled nickname (or name) with the player's symbols. */
        public Component name() {
            return Sky2MNick.displayName(uuid, username, nickname, nicknameEnabled, mode, hex, hex2, font);
        }

        public MutableComponent prefixComponent() {
            if (prefix == null || prefix.label().isBlank()) return Component.empty();
            return Component.literal("[" + prefix.label() + "] ").withStyle(Style.EMPTY.withColor(prefix.colour()).withBold(prefix.bold()));
        }
    }

    public record Friend(String username, UUID uuid, boolean online, long lastSeen, String area, String server, String island, boolean afk) {}

    public record Request(String username, UUID uuid, long at) {}

    private static final long PRESENCE_EVERY_MS = 2_000L;
    private static final Pattern SERVER_LINE = Pattern.compile("\\d{2}/\\d{2}/\\d{2}\\s+(\\S+)");
    private static final Map<String, String> ISLANDS = Map.ofEntries(
        Map.entry("Hub", "hub"), Map.entry("Private Island", "dynamic"), Map.entry("Garden", "garden"),
        Map.entry("Gold Mine", "mining_1"), Map.entry("Deep Caverns", "mining_2"), Map.entry("Dwarven Mines", "mining_3"),
        Map.entry("Crystal Hollows", "crystal_hollows"), Map.entry("Mineshaft", "mineshaft"), Map.entry("Spider's Den", "combat_1"),
        Map.entry("The End", "combat_3"), Map.entry("Crimson Isle", "crimson_isle"), Map.entry("The Park", "foraging_1"),
        Map.entry("The Farming Islands", "farming_1"), Map.entry("Dungeon Hub", "dungeon_hub"), Map.entry("Catacombs", "dungeon"),
        Map.entry("Jerry's Workshop", "winter"), Map.entry("The Rift", "rift"), Map.entry("Kuudra", "kuudra"),
        Map.entry("Backwater Bayou", "fishing_1"), Map.entry("Galatea", "foraging_2"));

    // /s2 who
    /** Everyone from the last /s2 who: online players first, then offline ones most recently seen first. */
    static volatile List<Player> online = List.of();
    static volatile long onlineAt;
    /** Whether the server has sent offline players too (a server that only knows online players doesn't). */
    private static boolean haveOffline;
    private static long whoAskedAt;
    private static boolean showWhenArrives;

    // Friends
    static volatile List<Friend> friends = List.of();
    static volatile List<Request> incoming = List.of();
    static volatile List<Request> outgoing = List.of();
    static volatile boolean friendsLoaded;
    private static long ignoreListAskedAt;

    // Presence
    private static String sentArea;
    private static String sentServer;
    private static boolean sentAfk;
    private static long presenceAt;
    private static boolean presenceWasOn;
    private static long lastInput = System.currentTimeMillis();
    private static Vec3 lastPos;
    private static float lastYaw;
    private static float lastPitch;

    // Social settings: what the server last confirmed, so config changes are sent once.
    private static String syncedSettings;
    private static long settingsCheckAt;

    private SbcSocial() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("ignore")
                        .then(ClientCommands.literal("list").executes(c -> SbcCommands.run(SbcSocial::askIgnoreList)))
                        .then(ClientCommands.literal("discord").then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes(c -> SbcCommands.run(() -> ignore(StringArgumentType.getString(c, "name"), true, true)))))
                        .then(player().executes(c -> SbcCommands.run(() -> ignore(StringArgumentType.getString(c, "player"), false, true)))))
                    .then(ClientCommands.literal("unignore")
                        .then(ClientCommands.literal("discord").then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes(c -> SbcCommands.run(() -> ignore(StringArgumentType.getString(c, "name"), true, false)))))
                        .then(player().executes(c -> SbcCommands.run(() -> ignore(StringArgumentType.getString(c, "player"), false, false)))))
                    .then(ClientCommands.literal("friends").executes(c -> SbcCommands.run(SbcSocial::openFriends)))
                    .then(ClientCommands.literal("friend")
                        .executes(c -> SbcCommands.run(SbcSocial::openFriends))
                        .then(ClientCommands.literal("list").executes(c -> SbcCommands.run(SbcSocial::openFriends)))
                        .then(ClientCommands.literal("add").then(player().executes(c -> SbcCommands.run(() -> friend("friendRequest", StringArgumentType.getString(c, "player"))))))
                        .then(ClientCommands.literal("remove").then(player().executes(c -> SbcCommands.run(() -> friend("friendRemove", StringArgumentType.getString(c, "player"))))))
                        .then(ClientCommands.literal("accept").then(player().executes(c -> SbcCommands.run(() -> friend("friendAccept", StringArgumentType.getString(c, "player"))))))
                        .then(ClientCommands.literal("deny").then(player().executes(c -> SbcCommands.run(() -> friend("friendDeny", StringArgumentType.getString(c, "player"))))))));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tickPresence(mc);
                tickSettings();
            } catch (Exception e) {
                SbcCrashReports.report(e, "S2C presence");
            }
        });
        // Fresh names for @completion while you type.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof ChatScreen && System.currentTimeMillis() - onlineAt > 30_000L && Sbc.config().chat.mentionCompletion) {
                requestWho(false);
            }
        });
    }

    private static RequiredArgumentBuilder<FabricClientCommandSource, String> player() {
        return ClientCommands.argument("player", StringArgumentType.word())
            .suggests((c, b) -> SharedSuggestionProvider.suggest(knownNames(), b));
    }

    private static List<String> knownNames() {
        List<String> names = new ArrayList<>(onlineNames());
        for (Friend f : friends) if (!names.contains(f.username())) names.add(f.username());
        for (Request r : incoming) if (!names.contains(r.username())) names.add(r.username());
        return names;
    }

    /** Usernames of Sky2M players online right now (from the last /s2 who). */
    public static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : online) if (p.online()) names.add(p.username());
        return names;
    }

    // ------------------------------------------------------------------------------------------------ /s2 who

    /** Asks who is online; {@code show} opens the list (screen or chat) when it arrives. */
    public static void requestWho(boolean show) {
        if (show) showWhenArrives = true;
        if (!SbcNet.online()) {
            com.epic60869.sky2m.Sky2MGlobalChat.ensureConnected();
            if (show) {
                if (Sbc.config().social.whoScreen) Compat.queueOpenScreen(new SbcWhoScreen());
                else Sbc.error(SbcNet.OFFLINE);
                showWhenArrives = false;
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!show && now - whoAskedAt < 5_000L) return;
        whoAskedAt = now;
        JsonObject who = Sbc.packet("who");
        // Ask for offline Sky2M users too; servers that don't know "all" just send who is online.
        who.addProperty("all", true);
        SbcNet.sendQuietly(who);
        if (show && Sbc.config().social.whoScreen) {
            showWhenArrives = false;
            Compat.queueOpenScreen(new SbcWhoScreen());
        }
    }

    /** True when the last /s2 who has had no answer for 8 seconds. */
    static boolean whoUnanswered() {
        return whoAskedAt > onlineAt && System.currentTimeMillis() - whoAskedAt > 8_000L;
    }

    private static void onOnline(JsonObject packet) {
        // An "online" packet without a player list (a presence update) isn't the list; it mustn't empty it.
        if (!packet.has("players") || !packet.get("players").isJsonArray()) return;
        List<Player> players = new ArrayList<>();
        for (JsonElement e : Sbc.arr(packet, "players")) {
            if (e.isJsonPrimitive()) {
                players.add(new Player(e.getAsString(), null, "", false, "Plain", "", "", "Default", null, null, false, 0, "", "", "", "", false, true, 0));
                continue;
            }
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            UUID uuid = Sbc.uuid(Sbc.str(o, "minecraftUuid"));
            String username = Sbc.str(o, "username").replaceAll("[^A-Za-z0-9_]", "");
            if (uuid != null) Sky2MNick.rememberUsername(uuid, username);
            Prefix prefix = null;
            if (Sbc.hasObj(o, "prefix")) {
                JsonObject p = o.getAsJsonObject("prefix");
                String label = Sbc.str(p, "prefix").replaceAll("§.", "").replaceAll("[\\[\\]]", "").trim();
                if (!label.isEmpty()) prefix = new Prefix(label, Sbc.hex(Sbc.str(p, "color"), 0xFFAA00), Sbc.bool(p, "bold"));
            }
            players.add(new Player(username, uuid, Sbc.str(o, "nickname"), Sbc.bool(o, "nicknameEnabled"),
                Sbc.str(o, "nicknameMode"), Sbc.str(o, "nicknameHex"), Sbc.str(o, "nicknameHex2"), Sbc.str(o, "nicknameFont"),
                prefix, SbcCosmetics.symbol(Sbc.obj(o, "badge")), Sbc.bool(o, "friend"), Sbc.lng(o, "onlineSince", 0),
                Sbc.str(o, "modVersion"), Sbc.str(o, "area"), Sbc.str(o, "server"), Sbc.str(o, "island"), Sbc.bool(o, "afk"),
                !o.has("online") || Sbc.bool(o, "online"), Sbc.lng(o, "lastSeen", 0)));
        }
        boolean anyOffline = players.stream().anyMatch(p -> !p.online());
        if (anyOffline || Sbc.bool(packet, "all")) {
            haveOffline = true;
        } else if (haveOffline) {
            // A list of only online players (a presence update): keep the offline ones from the last full list, and
            // whoever is no longer online becomes offline.
            Set<String> now = new HashSet<>();
            for (Player p : players) now.add(p.username().toLowerCase(Locale.ROOT));
            long time = System.currentTimeMillis();
            for (Player p : online) {
                if (now.contains(p.username().toLowerCase(Locale.ROOT))) continue;
                players.add(p.online() ? offline(p, time) : p);
            }
        }
        players.sort(Comparator.comparing((Player p) -> !p.online()).thenComparingLong(p -> p.online() ? 0 : -p.lastSeen()));
        online = List.copyOf(players);
        onlineAt = System.currentTimeMillis();
        if (showWhenArrives) {
            showWhenArrives = false;
            if (Sbc.config().social.whoScreen) Compat.queueOpenScreen(new SbcWhoScreen());
            else showWhoInChat();
        }
    }

    private static Player offline(Player p, long lastSeen) {
        return new Player(p.username(), p.uuid(), p.nickname(), p.nicknameEnabled(), p.mode(), p.hex(), p.hex2(), p.font(), p.prefix(),
            p.badge(), p.friend(), 0, p.modVersion(), "", "", "", false, false, lastSeen);
    }

    /** How many of the players are online. */
    static int onlineCount(List<Player> players) {
        int n = 0;
        for (Player p : players) if (p.online()) n++;
        return n;
    }

    private static void showWhoInChat() {
        List<Player> players = online;
        int onlineCount = onlineCount(players);
        MutableComponent out = Component.literal(onlineCount + " online with Sky2M:").withStyle(ChatFormatting.YELLOW);
        boolean offlineHeader = false;
        for (Player p : players) {
            if (!p.online()) {
                if (!offlineHeader) {
                    offlineHeader = true;
                    out.append(Component.literal("\n" + (players.size() - onlineCount) + " offline:").withStyle(ChatFormatting.GRAY));
                }
                MutableComponent line = Component.literal("\n ").append(p.prefixComponent()).append(p.name());
                if (p.lastSeen() > 0) line.append(Component.literal(" seen " + Sbc.duration(System.currentTimeMillis() - p.lastSeen()) + " ago").withStyle(ChatFormatting.DARK_GRAY));
                out.append(line);
                continue;
            }
            MutableComponent line = Component.literal("\n ");
            if (p.friend()) line.append(Component.literal("★ ").withStyle(ChatFormatting.GOLD));
            line.append(p.prefixComponent()).append(p.name());
            String where = where(p.area(), p.server());
            if (!where.isEmpty()) line.append(Component.literal(" " + where).withStyle(ChatFormatting.GRAY));
            if (p.afk()) line.append(Component.literal(" AFK").withStyle(ChatFormatting.DARK_GRAY));
            MutableComponent hover = Component.literal(p.username()).withStyle(ChatFormatting.WHITE);
            if (p.onlineSince() > 0) hover.append(Component.literal("\nOnline for " + Sbc.duration(System.currentTimeMillis() - p.onlineSince())).withStyle(ChatFormatting.GRAY));
            if (!p.modVersion().isBlank()) hover.append(Component.literal("\nSky2M " + p.modVersion()).withStyle(ChatFormatting.GRAY));
            line.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(hover)));
            out.append(line);
        }
        Sbc.say(out);
    }

    /** "Dwarven Mines (mini104B)", or "" when hidden. */
    static String where(String area, String server) {
        if (area.isBlank() && server.isBlank()) return "";
        if (server.isBlank()) return area;
        return (area.isBlank() ? "" : area + " ") + "(" + server + ")";
    }

    // ------------------------------------------------------------------------------------------------ presence

    private static void tickPresence(Minecraft mc) {
        if (mc.player != null) {
            Vec3 pos = mc.player.position();
            if (lastPos == null || pos.distanceToSqr(lastPos) > 0.01 || mc.player.getYRot() != lastYaw || mc.player.getXRot() != lastPitch
                || mc.gui.screen() != null && mc.mouseHandler.isLeftPressed()) {
                lastInput = System.currentTimeMillis();
            }
            lastPos = pos;
            lastYaw = mc.player.getYRot();
            lastPitch = mc.player.getXRot();
        }
        if (mc.player != null && mc.gui.screen() instanceof ChatScreen) lastInput = System.currentTimeMillis();

        long now = System.currentTimeMillis();
        if (now - presenceAt < PRESENCE_EVERY_MS || !SbcNet.online()) return;
        SbcConfig.Social config = Sbc.config().social;
        boolean on = config.presence;
        String area = on && mc.player != null ? Sky2MLocation.area() : "";
        String server = on && mc.player != null ? server() : "";
        boolean afk = on && now - lastInput > config.afkMinutes * 60_000L;
        // Turning sharing off clears what the others see, once.
        if (!on && !presenceWasOn) return;
        if (area.equals(sentArea) && server.equals(sentServer) && afk == sentAfk) return;
        presenceAt = now;
        sentArea = area;
        sentServer = server;
        sentAfk = afk;
        presenceWasOn = on;
        JsonObject p = Sbc.packet("presence");
        p.addProperty("area", area);
        p.addProperty("server", server);
        p.addProperty("island", ISLANDS.getOrDefault(area, ""));
        p.addProperty("afk", afk);
        SbcNet.sendQuietly(p);
    }

    /** The server id from the sidebar ("11/08/24 m45CD" -> "m45CD"). */
    private static String server() {
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = SERVER_LINE.matcher(line);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    /** A new connection: the server knows nothing about us yet. */
    static void onConnected() {
        sentArea = null;
        sentServer = null;
        presenceAt = 0;
        presenceWasOn = true;
        syncedSettings = null;
    }

    // ------------------------------------------------------------------------------------------------ ignore list

    private static void ignore(String name, boolean discord, boolean add) {
        String clean = name.trim();
        if (clean.isEmpty()) return;
        JsonObject p = Sbc.packet(add ? "ignoreAdd" : "ignoreRemove");
        p.addProperty(discord ? "discord" : "username", clean);
        SbcNet.sendAuthed(p);
    }

    private static void askIgnoreList() {
        ignoreListAskedAt = System.currentTimeMillis();
        SbcNet.sendAuthed(Sbc.packet("ignoreList"));
    }

    private static void onIgnoreList(JsonObject packet) {
        // Also sent after every login and change; only shown when asked for.
        if (System.currentTimeMillis() - ignoreListAskedAt > 15_000L) return;
        ignoreListAskedAt = 0;
        var players = Sbc.arr(packet, "players");
        if (players.isEmpty()) {
            Sbc.say(Component.literal("You aren't ignoring anyone.").withStyle(ChatFormatting.GRAY));
            return;
        }
        MutableComponent out = Component.literal("You're ignoring:").withStyle(ChatFormatting.YELLOW);
        for (JsonElement e : players) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            boolean discord = Sbc.bool(o, "discord");
            String name = Sbc.str(o, "username");
            out.append(Component.literal("\n  " + (discord ? "[Discord] " : "") + name).withStyle(ChatFormatting.WHITE))
                .append(SbcSettingsSync.button(" [Unignore]", ChatFormatting.GREEN,
                    "/s2 unignore " + (discord ? "discord " : "") + name, "See their messages again"));
        }
        Sbc.say(out);
    }

    // ------------------------------------------------------------------------------------------------ friends

    static void friend(String type, String username) {
        if (!Flags.check("friends")) return;
        JsonObject p = Sbc.packet(type);
        p.addProperty("username", username.trim());
        SbcNet.sendAuthed(p);
    }

    public static void openFriends() {
        if (!Flags.check("friends")) return;
        SbcNet.sendAuthed(Sbc.packet("friendList"));
        Compat.queueOpenScreen(new SbcFriendsScreen());
    }

    private static void onFriends(JsonObject packet) {
        List<Friend> list = new ArrayList<>();
        for (JsonElement e : Sbc.arr(packet, "friends")) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            UUID uuid = Sbc.uuid(Sbc.str(o, "minecraftUuid"));
            list.add(new Friend(Sbc.str(o, "username"), uuid, Sbc.bool(o, "online"), Sbc.lng(o, "lastSeen", 0),
                Sbc.str(o, "area"), Sbc.str(o, "server"), Sbc.str(o, "island"), Sbc.bool(o, "afk")));
        }
        // Online first, then most recently seen.
        list.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : Long.compare(b.lastSeen(), a.lastSeen()));
        friends = List.copyOf(list);
        incoming = requests(Sbc.arr(packet, "incoming"));
        outgoing = requests(Sbc.arr(packet, "outgoing"));
        friendsLoaded = true;
        if (Sbc.hasObj(packet, "settings")) applySettings(packet.getAsJsonObject("settings"));
    }

    private static List<Request> requests(Iterable<JsonElement> array) {
        List<Request> out = new ArrayList<>();
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            out.add(new Request(Sbc.str(o, "username"), Sbc.uuid(Sbc.str(o, "minecraftUuid")), Sbc.lng(o, "at", 0)));
        }
        return List.copyOf(out);
    }

    private static void onFriendEvent(JsonObject packet) {
        String name = Sbc.str(packet, "username");
        switch (Sbc.str(packet, "event")) {
            case "request" -> Sbc.say(Component.literal(name + " sent you a Sky2M friend request. ").withStyle(ChatFormatting.YELLOW)
                .append(SbcSettingsSync.button("[Accept]", ChatFormatting.GREEN, "/s2 friend accept " + name, "Become friends with " + name))
                .append(Component.literal(" "))
                .append(SbcSettingsSync.button("[Deny]", ChatFormatting.RED, "/s2 friend deny " + name, "Deny the request")));
            case "requestSent" -> Sbc.say(Component.literal("Friend request sent to " + name + ".").withStyle(ChatFormatting.GREEN));
            case "accepted" -> Sbc.say(Component.literal("You and " + name + " are now Sky2M friends!").withStyle(ChatFormatting.GREEN));
            case "denied" -> Sbc.say(Component.literal("Friend request from " + name + " denied.").withStyle(ChatFormatting.GRAY));
            case "removed" -> Sbc.say(Component.literal(name + " is no longer your friend.").withStyle(ChatFormatting.GRAY));
            case "requestCancelled" -> Sbc.say(Component.literal("Friend request to " + name + " cancelled.").withStyle(ChatFormatting.GRAY));
            case "online", "offline" -> {
                boolean on = "online".equals(Sbc.str(packet, "event"));
                if (Sbc.config().social.friendToasts) {
                    Sbc.toast(Component.literal("Sky2M friend").withStyle(ChatFormatting.GOLD),
                        Component.literal(name + (on ? " is online" : " went offline")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
                }
            }
            default -> {}
        }
        if (Minecraft.getInstance().gui.screen() instanceof SbcFriendsScreen && SbcNet.online()) SbcNet.sendQuietly(Sbc.packet("friendList"));
    }

    // ------------------------------------------------------------------------------------------------ settings

    private static String settingsKey(SbcConfig.Social c) {
        return c.location.id() + "|" + c.friendRequests + "|" + c.notifyFriendsOnline;
    }

    private static void applySettings(JsonObject settings) {
        SbcConfig.Social c = Sbc.config().social;
        String location = Sbc.str(settings, "location");
        for (SbcConfig.LocationSharing value : SbcConfig.LocationSharing.values()) {
            if (value.id().equals(location)) c.location = value;
        }
        if (settings.has("friendRequests")) c.friendRequests = Sbc.bool(settings, "friendRequests");
        if (settings.has("notifyFriendsOnline")) c.notifyFriendsOnline = Sbc.bool(settings, "notifyFriendsOnline");
        syncedSettings = settingsKey(c);
    }

    /** Sends the social settings when they were changed here (config or friends screen). */
    private static void tickSettings() {
        long now = System.currentTimeMillis();
        if (now - settingsCheckAt < 2_000L || !Sky2MLogin.loggedIn()) return;
        settingsCheckAt = now;
        SbcConfig.Social c = Sbc.config().social;
        String key = settingsKey(c);
        // Nothing heard from the server yet on this connection: its settings win (they come with "friends").
        if (syncedSettings == null || key.equals(syncedSettings)) return;
        syncedSettings = key;
        JsonObject p = Sbc.packet("socialSettings");
        p.addProperty("location", c.location.id());
        p.addProperty("friendRequests", c.friendRequests);
        p.addProperty("notifyFriendsOnline", c.notifyFriendsOnline);
        SbcNet.sendQuietly(p);
    }

    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "online", "whoResult" -> onOnline(packet);
            case "friends" -> onFriends(packet);
            case "friendEvent" -> onFriendEvent(packet);
            case "socialSettings" -> {
                if (Sbc.hasObj(packet, "settings")) applySettings(packet.getAsJsonObject("settings"));
            }
            case "ignoreResult" -> {
                String message = Sbc.str(packet, "message");
                boolean ok = !packet.has("ok") || Sbc.bool(packet, "ok");
                if (message.isBlank()) message = ok ? "Done." : "That didn't work.";
                Sbc.say(Component.literal(message).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED));
            }
            case "ignoreList" -> onIgnoreList(packet);
            case "socialError" -> Sbc.error(Sbc.str(packet, "message").isBlank() ? "That didn't work (" + Sbc.str(packet, "code") + ")." : Sbc.str(packet, "message"));
            default -> {}
        }
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
