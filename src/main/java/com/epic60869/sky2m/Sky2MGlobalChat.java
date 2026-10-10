package com.epic60869.sky2m;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

public final class Sky2MGlobalChat {
    private static final String CONFIGURED_RELAY_URL =
        System.getProperty("sky2m.chat.url", "").trim();
    private static final String[] RELAY_URLS = CONFIGURED_RELAY_URL.isBlank()
        ? new String[] {"wss://shadowisabot.com/mod-api/tf-chat"}
        : new String[] {CONFIGURED_RELAY_URL};

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final AtomicBoolean CONNECTING = new AtomicBoolean(false);
    private static final AtomicLong REQUEST_IDS = new AtomicLong();
    private static volatile WebSocket socket;
    private static volatile long reconnectAt = 0L;
    /** Failed connection attempts in a row; the wait before the next one doubles each time (5 s up to 2 min). */
    private static volatile int failures = 0;
    /** "Connection failed" is said once per outage, not on every retry. */
    private static volatile boolean toldOffline = false;
    private static volatile String username = "Unknown";
    private static volatile int relayIndex = 0;
    private static final Queue<JsonObject> PENDING_MESSAGES = new ArrayDeque<>();
    private static volatile boolean inSky2MChannel = false;

    public static boolean isInSky2MChannel() { return inSky2MChannel; }

    public static void enterSky2MChannel() {
        inSky2MChannel = true;
        mcMessage(Component.literal("You are now in the Sky2M channel")
            .withStyle(Style.EMPTY.withColor(0x55FFFF).withBold(true))
            .append(Component.literal(" — anything you type will be sent to Sky2M chat.")
                .withStyle(Style.EMPTY.withColor(0xAAAAAA))));
    }

    public static void leaveSky2MChannel() {
        inSky2MChannel = false;
        mcMessage(Component.literal("You have left the Sky2M channel.")
            .withStyle(Style.EMPTY.withColor(0xFFAA00).withBold(true)));
    }

    private Sky2MGlobalChat() {}

    public static void init() {
        Minecraft mc = Minecraft.getInstance();
        username = mc.getUser().getName();
        connect();
    }

    /** /s2 who: the online list (a screen, or chat when the screen is turned off). */
    public static void requestWho() {
        com.epic60869.sky2m.features.sbc.SbcSocial.requestWho(true);
    }

    public static void sendBotCommand(String command) {
        String clean = String.valueOf(command == null ? "" : command).trim();
        if (clean.isEmpty() || !clean.startsWith("!")) return;

        WebSocket ws = socket;
        if (ws == null || ws.isInputClosed() || ws.isOutputClosed()) {
            connect();
            mcMessage(Component.literal("[S2M] Bot command is still connecting...")
                .withStyle(Style.EMPTY.withColor(0xFFFF55)));
            return;
        }

        JsonObject packet = new JsonObject();
        packet.addProperty("type", "command");
        packet.addProperty("username", username);
        packet.addProperty("command", clean.substring(0, Math.min(clean.length(), 500)));
        ws.sendText(GSON.toJson(packet), true);
        mcMessage(Component.literal("[S2M] Sending " + clean).withStyle(Style.EMPTY.withColor(0xAAAAAA)));
    }

    public static void send(String message) {
        String clean = String.valueOf(message == null ? "" : message).trim();
        if (clean.isEmpty()) return;
        // The reply being written and an [item] in the text go with the message.
        java.util.List<String> expanded = com.epic60869.sky2m.features.sbc.SbcItems.expandBrag(clean);
        JsonObject packet = com.epic60869.sky2m.features.sbc.SbcChat.outgoing(expanded.getFirst());
        if (packet != null) sendPacket(packet);
        com.epic60869.sky2m.features.sbc.SbcItems.enqueueSky2MFollowups(expanded.subList(1, expanded.size()));
    }

    /** Sends a Sky2M chat "message" packet (text, reply, item), or queues it until connected. */
    public static void sendPacket(JsonObject packet) {
        packet.addProperty("type", "message");
        packet.addProperty("username", username);
        packet.addProperty("minecraftUuid", Minecraft.getInstance().getUser().getProfileId().toString());
        int[] level = ownLevel();
        if (level != null) {
            packet.addProperty("level", level[0]);
            packet.addProperty("levelColor", String.format("#%06X", level[1] & 0xFFFFFF));
        }

        WebSocket ws = socket;
        if (ws == null || ws.isInputClosed() || ws.isOutputClosed()) {
            synchronized (PENDING_MESSAGES) {
                if (PENDING_MESSAGES.size() >= 20) PENDING_MESSAGES.poll();
                PENDING_MESSAGES.offer(packet);
            }
            connect();
            mcMessage(Component.literal("[S2M] S2C offline: your message will be sent when Sky2M chat reconnects.")
                .withStyle(Style.EMPTY.withColor(0xFFFF55)));
            return;
        }
        ws.sendText(GSON.toJson(packet), true);
    }

    private static final java.util.regex.Pattern TAB_LEVEL = java.util.regex.Pattern.compile("^\\[(\\d+)\\] (\\w+)");

    /** Your SkyBlock level and its colour, read from your own tab-list entry ("[279] name"), or null. */
    private static int[] ownLevel() {
        String me = Minecraft.getInstance().getUser().getName();
        for (var info : Sky2MTabWidgetManager.players()) {
            Component name = com.epic60869.sky2m.custom.util.Compat.rawTabName(info);
            if (name == null) continue;
            String text = net.minecraft.ChatFormatting.stripFormatting(name.getString()).trim();
            java.util.regex.Matcher m = TAB_LEVEL.matcher(text);
            if (!m.find() || !m.group(2).equalsIgnoreCase(me)) continue;
            int level = Integer.parseInt(m.group(1));
            // Colour of the level number itself.
            final int[] colour = {0xAAAAAA};
            final boolean[] found = {false};
            name.visit((style, value) -> {
                if (!found[0] && value.chars().anyMatch(Character::isDigit)) {
                    if (style.getColor() != null) colour[0] = style.getColor().getValue();
                    found[0] = true;
                }
                return java.util.Optional.empty();
            }, Style.EMPTY);
            return new int[]{level, colour[0]};
        }
        return null;
    }

    private static void flushPending(WebSocket ws) {
        synchronized (PENDING_MESSAGES) {
            while (!PENDING_MESSAGES.isEmpty()) {
                ws.sendText(GSON.toJson(PENDING_MESSAGES.poll()), true);
            }
        }
    }

    public static void requestDiscord(String action, JsonObject data) {
        WebSocket ws = socket;
        if (ws == null || ws.isInputClosed() || ws.isOutputClosed()) {
            connect();
            mcMessage(Component.literal("[S2M] Discord is still connecting...")
                .withStyle(Style.EMPTY.withColor(0xFFFF55)));
            return;
        }

        JsonObject packet = data == null ? new JsonObject() : data.deepCopy();
        packet.addProperty("type", "discord");
        packet.addProperty("action", action);
        packet.addProperty("requestId", Long.toString(REQUEST_IDS.incrementAndGet()));
        ws.sendText(GSON.toJson(packet), true);
    }

    public static void sendDiscordDm(String target, String message) {
        String cleanTarget = String.valueOf(target == null ? "" : target).trim();
        String cleanMessage = String.valueOf(message == null ? "" : message).trim();

        if (cleanTarget.isEmpty()) {
            mcMessage(Component.literal("[S2M] Usage: /s2 dm <discord-user> <message/link>")
                .withStyle(Style.EMPTY.withColor(0xFFFF55)));
            return;
        }

        if (cleanMessage.isEmpty()) {
            mcMessage(Component.literal("[S2M] The Discord DM cannot be empty.")
                .withStyle(Style.EMPTY.withColor(0xFF5555)));
            return;
        }

        JsonObject packet = new JsonObject();
        packet.addProperty("type", "dm");
        packet.addProperty("username", username);
        packet.addProperty("target", cleanTarget);
        packet.addProperty("message", cleanMessage.substring(0, Math.min(cleanMessage.length(), 1900)));

        WebSocket ws = socket;
        if (ws == null || ws.isInputClosed() || ws.isOutputClosed()) {
            connect();
            mcMessage(Component.literal("[S2M] Discord link is still connecting. Try again in a moment.")
                .withStyle(Style.EMPTY.withColor(0xFFFF55)));
            return;
        }

        ws.sendText(GSON.toJson(packet), true);
    }

    /** The open connection, or null; identifies it (a reconnect is a new one, needing a new login). */
    public static Object currentConnection() {
        WebSocket ws = socket;
        return ws == null || ws.isInputClosed() || ws.isOutputClosed() ? null : ws;
    }

    public static void ensureConnected() {
        if (currentConnection() == null) connect();
    }

    /** Sends a packet if connected (returns false and starts connecting otherwise). */
    public static boolean send(JsonObject packet) {
        WebSocket ws = socket;
        if (ws == null || ws.isInputClosed() || ws.isOutputClosed()) {
            connect();
            return false;
        }
        ws.sendText(GSON.toJson(packet), true);
        return true;
    }

    private static void connect() {
        if (!CONNECTING.compareAndSet(false, true)) return;

        String relayUrl = RELAY_URLS[Math.min(relayIndex, RELAY_URLS.length - 1)];

        HTTP.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(relayUrl), new Listener())
            .whenComplete((ws, error) -> {
                CONNECTING.set(false);
                if (error != null) {
                    socket = null;
                    if (relayIndex + 1 < RELAY_URLS.length) {
                        relayIndex++;
                    }
                    if (!toldOffline) {
                        toldOffline = true;
                        mcMessage(Component.literal("[S2M] S2C offline: global chat connection failed ("
                            + shortError(error) + "). Retrying in the background.")
                            .withStyle(Style.EMPTY.withColor(0xFF5555)));
                    }
                    scheduleReconnect();
                    return;
                }

                relayIndex = 0;
                socket = ws;
                failures = 0;
                if (toldOffline) {
                    toldOffline = false;
                    mcMessage(Component.literal("[S2M] Reconnected to Sky2M chat.").withStyle(Style.EMPTY.withColor(0x55FF55)));
                }

                JsonObject hello = new JsonObject();
                hello.addProperty("type", "hello");
                hello.addProperty("username", username);
                // Packets this mod understands; without "rankAnnounce" the server sends a plain bot message instead.
                JsonArray features = new JsonArray();
                features.add("rankAnnounce");
                features.add("leaderboards");
                features.add("chatBlocked");
                features.add("announcements");
                features.add("partyFinder");
                hello.add("features", features);
                hello.addProperty("minecraftUuid", Minecraft.getInstance().getUser().getProfileId().toString());
                hello.addProperty("modVersion", com.epic60869.sky2m.features.sbc.SbcInfo.modVersion());
                hello.addProperty("mcVersion", com.epic60869.sky2m.features.sbc.SbcInfo.mcVersion());
                ws.sendText(GSON.toJson(hello), true);
                // Log in on every connection, right after hello: friends, settings and cosmetics need it.
                Minecraft.getInstance().execute(() -> {
                    Sky2MLogin.whenLoggedIn(null);
                    com.epic60869.sky2m.features.sbc.Sbc.onConnected();
                });
                flushPending(ws);
            });
    }

    private static String shortError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static void scheduleReconnect() {
        long delay = Math.min(120_000L, 5000L << Math.min(5, failures));
        failures++;
        reconnectAt = System.currentTimeMillis() + delay;
    }

    /** Whether the Sky2M chat server is connected. */
    public static boolean isOnline() {
        return currentConnection() != null;
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (socket == null && reconnectAt > 0 && now >= reconnectAt) {
            reconnectAt = 0;
            connect();
        }
    }

    private static final java.util.regex.Pattern LINK = java.util.regex.Pattern.compile("https?://\\S+");

    private static Component linkify(Component text) {
        java.util.regex.Pattern pattern = LINK;
        MutableComponent result = Component.empty();

        text.visit((style, value) -> {
            if (value == null || value.isEmpty()) return java.util.Optional.empty();

            java.util.regex.Matcher matcher = pattern.matcher(value);
            int last = 0;
            while (matcher.find()) {
                if (matcher.start() > last) {
                    result.append(Component.literal(value.substring(last, matcher.start())).setStyle(style));
                }

                String url = matcher.group();
                while (url.length() > 1 && ")]>.".indexOf(url.charAt(url.length() - 1)) >= 0) {
                    url = url.substring(0, url.length() - 1);
                }

                try {
                    result.append(Component.literal(url).setStyle(style.withUnderlined(true)
                        .withColor(0x55AAFF)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal(url)))));
                } catch (Exception ignored) {
                    result.append(Component.literal(url).setStyle(style));
                }
                last = matcher.end();
            }

            if (last < value.length()) {
                result.append(Component.literal(value.substring(last)).setStyle(style));
            }
            return java.util.Optional.empty();
        }, Style.EMPTY);

        return result;
    }

    /**
     * "[S2M] Steve Has Been Granted [VIP] By Console" or "[S2M] Steve's [VIP] Prefix Has Been Removed By Console",
     * with the prefix in its exact hex colour.
     */
    private static Component rankAnnouncement(JsonObject packet) {
        String name = packetString(packet, "username").replace("§", "").replaceAll("[^A-Za-z0-9_]", "");
        if (name.isBlank()) name = "Unknown";
        String prefix = packetString(packet, "prefix").replace("§", "");
        String by = packetString(packet, "by").replace("§", "");
        boolean removed = "removed".equals(packetString(packet, "action"));
        int colour = 0xFFAA00;
        String hex = packetString(packet, "color");
        if (hex.matches("#[0-9a-fA-F]{6}")) colour = Integer.parseInt(hex.substring(1), 16);
        boolean bold = packet.has("bold") && packet.get("bold").getAsBoolean();

        Style gray = Style.EMPTY.withColor(0xAAAAAA);
        // Hovering the name shows the real Minecraft name, like normal SBC messages.
        Component user = Component.literal(name).withStyle(Style.EMPTY.withColor(0xFFFFFF)
            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                Component.literal("Real name: ").withStyle(gray)
                    .append(Component.literal(name).withStyle(Style.EMPTY.withColor(0xFFFFFF))))));
        Component rank = Component.literal("[" + prefix + "]").withStyle(Style.EMPTY.withColor(colour).withBold(bold));
        MutableComponent line = Component.empty()
            .append(Component.literal("[S2M]").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal(" "))
            .append(user);
        if (removed) {
            line.append(Component.literal("'s ").withStyle(gray))
                .append(rank)
                .append(Component.literal(" Prefix Has Been Removed By " + by).withStyle(gray));
        } else {
            line.append(Component.literal(" Has Been Granted ").withStyle(gray))
                .append(rank)
                .append(Component.literal(" By " + by).withStyle(gray));
        }
        return line;
    }

    private static String packetString(JsonObject packet, String key) {
        return packet.has(key) && !packet.get(key).isJsonNull() ? packet.get(key).getAsString() : "";
    }

    private static void mcMessage(Component message) {
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(message);
            }
        });
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                handle(raw);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) socket = null;
            scheduleReconnect();
            Minecraft.getInstance().execute(com.epic60869.sky2m.features.sbc.Sbc::onDisconnected);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) socket = null;
            scheduleReconnect();
            Minecraft.getInstance().execute(com.epic60869.sky2m.features.sbc.Sbc::onDisconnected);
        }

        private void handle(String raw) {
            try {
                JsonObject packet = JsonParser.parseString(raw).getAsJsonObject();
                String type = packet.has("type") ? packet.get("type").getAsString() : "";

                if ("authResult".equals(type) || "casinoAuthResult".equals(type)) {
                    boolean ok = packet.has("ok") && packet.get("ok").getAsBoolean();
                    String message = packet.has("message") ? packet.get("message").getAsString() : "";
                    Minecraft.getInstance().execute(() -> Sky2MLogin.confirmed(ok, message));
                    return;
                }

                if (type.startsWith("leaderboard")) {
                    Minecraft.getInstance().execute(() -> Sky2MLeaderboards.handle(type, packet));
                    return;
                }

                if (type.startsWith("slayerPb")) {
                    Minecraft.getInstance().execute(() -> com.epic60869.sky2m.features.slayer.SlayerLeaderboard.handle(type, packet));
                    return;
                }

                if ("accountStatus".equals(type) || "nickResult".equals(type)) {
                    Minecraft.getInstance().execute(() -> Sky2MNickCommand.handle(type, packet));
                    return;
                }

                // The server answers packets it doesn't know with a plain error; show it where the player is looking.
                if ("error".equals(type) && Sky2MPartyFinder.awaitingReply()) {
                    String message = packet.has("message") ? packet.get("message").getAsString() : "";
                    Minecraft.getInstance().execute(() -> Sky2MPartyFinder.plainError(message));
                    return;
                }

                if (type.startsWith("pf")) {
                    Minecraft.getInstance().execute(() -> Sky2MPartyFinder.handle(type, packet));
                    return;
                }

                // The website changed someone's rank: reload them now instead of waiting for the next check.
                if ("ranksUpdated".equals(type) || "ranks".equals(type)) {
                    Sky2MStaff.refreshNow();
                    return;
                }

                if ("nicknameUpdate".equals(type)) {
                    try {
                        UUID uuid = UUID.fromString(packetString(packet, "minecraftUuid"));
                        // Fields the server leaves null (customHex for a named colour, font, ...) must not throw:
                        // that dropped the whole update, so the nickname showed without its colour.
                        boolean enabled = com.epic60869.sky2m.features.sbc.Sbc.bool(packet, "enabled");
                        String username = packetString(packet, "username");
                        String name = packetString(packet, "name");
                        String mode = packetString(packet, "mode");
                        String hex = packetString(packet, "customHex");
                        String font = packetString(packet, "font");
                        String gradient = packetString(packet, "gradientHex");
                        Sky2MNick.rememberUsername(uuid, username);
                        Sky2MNick.updateRemote(uuid, username, enabled, name, mode, hex, gradient, font);
                    } catch (Exception ignored) {}
                    return;
                }

                // Nicknames are persistent. A socket disconnect is not a
                // reason to erase another player's nickname from this client.
                // The authoritative nicknameUpdate packet handles enable/disable.

                if ("discordResult".equals(type)) {
                    String requestId = packetString(packet, "requestId");
                    boolean ok = com.epic60869.sky2m.features.sbc.Sbc.bool(packet, "ok");
                    JsonObject result = packet.has("result") && packet.get("result").isJsonObject()
                        ? packet.getAsJsonObject("result") : new JsonObject();
                    String detail = packetString(packet, "message");

                    Minecraft.getInstance().execute(() ->
                        Sky2MDiscordScreen.handleResult(requestId, ok, result, detail));
                    return;
                }

                if ("dmResult".equals(type)) {
                    boolean ok = com.epic60869.sky2m.features.sbc.Sbc.bool(packet, "ok");
                    String target = packetString(packet, "target");
                    if (target.isBlank()) target = "Discord user";
                    String detail = packetString(packet, "message").trim();

                    if (ok) {
                        mcMessage(Component.literal("[S2M] Discord DM sent to " + target + ".")
                            .withStyle(Style.EMPTY.withColor(0x55FF55)));
                    } else {
                        // The server may answer {ok:false} with no reason (Discord DMs turned off there).
                        mcMessage(Component.literal("[S2M] Couldn't send the Discord DM: ")
                            .withStyle(Style.EMPTY.withColor(0xFF5555))
                            .append(Component.literal(detail.isBlank() ? "Discord DMs aren't available right now." : detail)
                                .withStyle(Style.EMPTY.withColor(0xAAAAAA))));
                    }
                    return;
                }

                if ("rankAnnounce".equals(type)) {
                    Sky2MConfig announceConfig = Sky2MConfig.current();
                    if (announceConfig != null && !announceConfig.chat.customChat.showSjChat) return;
                    mcMessage(rankAnnouncement(packet));
                    return;
                }

                if (!"message".equals(type)) {
                    // Everything newer (friends, reactions, cosmetics, flags, pv, ...) is handled by the SBC client.
                    Minecraft.getInstance().execute(() -> com.epic60869.sky2m.features.sbc.Sbc.handle(type, packet));
                    return;
                }

                String name = packetString(packet, "username");
                if (name.isBlank()) name = "Unknown";
                String displayName = packetString(packet, "nickname");
                if (displayName.isBlank()) displayName = name;
                String message = packetString(packet, "message");
                // The chat server's bot still calls itself SkyJew and writes [SJ]; show the new name.
                // The chat server's bot may still call itself SkyJew or SkyBalls and write [SJ] or [SB]; show the new name.
                if (name.equalsIgnoreCase("SkyJew") || displayName.equalsIgnoreCase("SkyJew")
                    || name.equalsIgnoreCase("SkyBalls") || displayName.equalsIgnoreCase("SkyBalls")) {
                    name = "Sky2M";
                    displayName = "Sky2M";
                    message = message.replace("[SJ]", "[S2M]").replace("[SB]", "[S2M]").replace("SkyJew", "Sky2M").replace("SkyBalls", "Sky2M");
                }
                boolean hasItem = packet.has("item") && packet.get("item").isJsonObject();
                if (message.isBlank() && !hasItem) return;

                UUID messageUuid = null;
                try {
                    if (packet.has("minecraftUuid")) {
                        messageUuid = UUID.fromString(packetString(packet, "minecraftUuid"));
                        String messageUsername = packetString(packet, "username");
                        boolean nickEnabled = com.epic60869.sky2m.features.sbc.Sbc.bool(packet, "nicknameEnabled");
                        // Null when the message doesn't say: the style nicknameUpdate gave is kept.
                        String nickMode = packet.has("nicknameMode") && !packet.get("nicknameMode").isJsonNull()
                            ? packetString(packet, "nicknameMode") : null;
                        String nickHex = packet.has("nicknameHex") && !packet.get("nicknameHex").isJsonNull()
                            ? packetString(packet, "nicknameHex") : null;

                        // nicknameUpdate is the authoritative state packet.
                        // A chat message from an older/stale connection may not
                        // contain nickname styling, so it must never erase a
                        // nickname that was already synced from the relay.
                        if (nickEnabled) {
                            String nickFont = packet.has("nicknameFont") && !packet.get("nicknameFont").isJsonNull()
                                ? packetString(packet, "nicknameFont") : null;
                            String nickHex2 = packet.has("nicknameHex2") && !packet.get("nicknameHex2").isJsonNull()
                                ? packetString(packet, "nicknameHex2") : null;
                            Sky2MNick.updateRemote(messageUuid, messageUsername, true, displayName, nickMode, nickHex, nickHex2, nickFont);
                        }
                    }
                } catch (Exception ignored) {}

                name = name.replaceAll("[^A-Za-z0-9_]", "");
                if (name.isBlank()) name = "Unknown";

                String source = packet.has("source") ? packet.get("source").getAsString() : "mod";
                String prefix = "discord".equalsIgnoreCase(source) ? "[Discord]" : "[S2M]";

                if (Sky2MNickFilter.isBlocked(displayName, messageUuid)) displayName = name;
                Component shownName;
                try {
                    shownName = Sky2MNick.displayName(messageUuid, displayName);
                } catch (Exception ignored) {
                    shownName = Sky2MNick.displayName(displayName);
                }
                Component messageComponent = com.epic60869.sky2m.features.misc.ItemEmojis.replace(Sky2MNopoFeatures.replaceChatEmojis(Component.literal(message)));
                // [S2M] in dark green, like Hypixel's "Guild >"; messages from Discord add a blue [Discord] after it.
                long messageId = packet.has("id") && !packet.get("id").isJsonNull() ? packet.get("id").getAsLong() : 0;
                MutableComponent line = Component.empty()
                    .append(com.epic60869.sky2m.features.sbc.SbcChat.prefix(messageId))
                    .append(Component.literal(" "));
                if (!"[S2M]".equals(prefix)) {
                    line.append(Component.literal(prefix).withStyle(net.minecraft.ChatFormatting.BLUE)).append(Component.literal(" "));
                }
                // Rank prefix, by account UUID, only for messages sent from the mod (not Discord), if turned on.
                Sky2MConfig rankConfig = Sky2MConfig.current();
                boolean showRanks = rankConfig == null || rankConfig.chat.customChat.showRanks;
                MutableComponent staff = "discord".equalsIgnoreCase(source) || !showRanks ? null : Sky2MStaff.prefix(messageUuid);
                if (staff != null) line.append(staff);
                int level = packet.has("level") ? packet.get("level").getAsInt() : 0;
                if (level > 0) {
                    int levelColor = 0xAAAAAA;
                    try {
                        String hex = packet.has("levelColor") ? packet.get("levelColor").getAsString() : "";
                        if (hex.matches("#[0-9a-fA-F]{6}")) levelColor = Integer.parseInt(hex.substring(1), 16);
                    } catch (Exception ignored) {}
                    line.append(Component.literal("[" + level + "] ").withStyle(Style.EMPTY.withColor(levelColor)));
                }
                // Hovering the sender shows their real Minecraft name.
                MutableComponent sender = Component.empty().withStyle(Style.EMPTY
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
                        Component.literal("Real name: ").withStyle(Style.EMPTY.withColor(0xAAAAAA))
                            .append(Component.literal(name).withStyle(Style.EMPTY.withColor(0xFFFFFF)))))
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand("/msg " + name + " ")));
                sender.append(shownName);
                line.append(Component.literal("["))
                    .append(sender)
                    .append(Component.literal("]: "))
                    .append(linkify(messageComponent));
                Sky2MConfig chatConfig = Sky2MConfig.current();
                if (chatConfig != null && !chatConfig.chat.customChat.showSjChat) return;
                mcMessage(com.epic60869.sky2m.features.sbc.SbcChat.decorate(packet, line, message));
                // Optional ping for other players' messages.
                java.util.UUID self = Minecraft.getInstance().getUser().getProfileId();
                if (chatConfig != null && chatConfig.chat.customChat.pingSound && (messageUuid == null || !messageUuid.equals(self))) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (mc.player != null) mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.5f, 1.8f);
                    });
                }
            } catch (Exception ignored) {
            }
        }
    }
}
