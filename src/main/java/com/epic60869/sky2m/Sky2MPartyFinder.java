package com.epic60869.sky2m;

import com.epic60869.sky2m.features.sbc.Flags;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /s2 pf: the Tasty Fish Party Finder. The SBC chat server keeps the listings, groups and join requests and decides
 * who can do what (limits, expiry, cooldowns); the mod only shows what it's sent and sends requests. Browsing needs
 * no login; everything else uses the Mojang login ({@link Sky2MLogin}).
 *
 * <pre>
 * mod -> server: pfList {category?}, pfMine {}, pfCreate {category, detail, size, note}, pfClose {},
 *                pfJoin {listingId, note?}, pfCancel {listingId}, pfAccept {uuid}, pfDecline {uuid}, pfKick {uuid},
 *                pfLeave {}
 * server -> mod: pfListings {categories: {name: {maxSize, details}}, listings: [{id, leader, leaderUuid, category,
 *                    detail, note, size, members: [username], full, createdAt, expiresAt}]}  (pushed after hello)
 *                pfMine {role: leader|member|none, listing: {..., members: [{uuid, username}],
 *                    requests: [{uuid, username, note, at}]}, pending: [listingId]}
 *                pfEvent {event, listingId, username, message}
 *                pfAnnounce {listing: {...}, message}  (a new listing, to everyone; message has § colours)
 *                pfError {code, message}
 * </pre>
 */
public final class Sky2MPartyFinder {
    public record Category(String name, int maxSize, List<String> details) {
        public boolean freeText() {
            return details.isEmpty();
        }
    }

    public record Member(String uuid, String username) {}

    public record Request(String uuid, String username, String note, long at) {}

    public record Listing(String id, String leader, String leaderUuid, String category, String detail, String note, int size,
                          List<Member> members, boolean full, long createdAt, long expiresAt, List<Request> requests) {}

    /** Your state: role is "leader", "member" or "none". */
    public record Mine(String role, Listing listing, Set<String> pending) {
        public boolean inGroup() {
            return listing != null && !"none".equals(role);
        }

        public boolean leader() {
            return "leader".equals(role) && listing != null;
        }
    }

    private static final Mine NONE = new Mine("none", null, Set.of());

    // Last state the server sent; replaced whole every time.
    static volatile Map<String, Category> categories = defaultCategories();
    static volatile List<Listing> listings = List.of();
    static volatile Mine mine = NONE;
    static volatile boolean listingsReceived;
    static volatile boolean mineReceived;
    /** Bumped on every packet, so the open screen knows to rebuild its buttons. */
    static volatile int version;
    static String error = "";
    static long errorAt;

    /** The last packet that needed the login, sent again once if the server says we're not logged in. */
    private static JsonObject lastAuthed;
    private static boolean retried;
    /** The connection pfMine was asked for on (a reconnect asks again). */
    private static Object mineAskedOn;

    private Sky2MPartyFinder() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : com.epic60869.sky2m.custom.util.Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(command()));
            }
        });
    }

    private static Map<String, Category> defaultCategories() {
        Map<String, Category> map = new LinkedHashMap<>();
        map.put("dungeons", new Category("dungeons", 5, List.of("Entrance", "F1", "F2", "F3", "F4", "F5", "F6", "F7",
            "M1", "M2", "M3", "M4", "M5", "M6", "M7")));
        map.put("kuudra", new Category("kuudra", 4, List.of("Basic", "Hot", "Burning", "Fiery", "Infernal")));
        for (String name : List.of("slayer", "diana", "fishing", "mining", "other")) map.put(name, new Category(name, 5, List.of()));
        return map;
    }

    // ---------------------------------------------------------------- sending

    static boolean online() {
        return SbcNet.online();
    }

    /** Status line for the screen, or "" when ready. */
    static String status() {
        if (!Flags.isEnabled("partyFinder")) return Flags.message("partyFinder");
        if (!online()) return "S2C offline. Reconnecting...";
        if (!listingsReceived) return "Loading listings...";
        return "";
    }

    /** While the screen is open: asks for your group once per connection (browsing is pushed by the server). */
    static void ensureReady() {
        Object connection = Sky2MGlobalChat.currentConnection();
        if (connection == null) {
            Sky2MGlobalChat.ensureConnected();
            return;
        }
        if (connection == mineAskedOn) return;
        mineAskedOn = connection;
        if (!listingsReceived) SbcNet.sendQuietly(packet("pfList"));
        authed(packet("pfMine"));
    }

    private static boolean enabled() {
        return Flags.check("partyFinder");
    }

    /** When a pf packet was last sent; a plain "error" soon after is the server answering it. */
    private static volatile long sentAt;

    /** Whether a plain server "error" packet is probably the answer to a Party Finder packet. */
    static boolean awaitingReply() {
        return System.currentTimeMillis() - sentAt < 5_000L;
    }

    /** A plain "error" packet the server sent in answer to a Party Finder packet. */
    static void plainError(String message) {
        onError("", message.isBlank() ? "The server couldn't handle that." : message);
        version++;
    }

    private static void authed(JsonObject packet) {
        sentAt = System.currentTimeMillis();
        lastAuthed = packet;
        retried = false;
        SbcNet.sendAuthed(packet);
    }

    static void list(String category) {
        JsonObject p = packet("pfList");
        if (category != null && !category.isBlank()) p.addProperty("category", category.toLowerCase(Locale.ROOT));
        sentAt = System.currentTimeMillis();
        SbcNet.send(p);
    }

    static void requestMine() {
        authed(packet("pfMine"));
    }

    static void create(String category, String detail, int size, String note) {
        if (!enabled()) return;
        JsonObject p = packet("pfCreate");
        p.addProperty("category", category.toLowerCase(Locale.ROOT));
        p.addProperty("detail", detail);
        p.addProperty("size", size);
        p.addProperty("note", note == null ? "" : note);
        authed(p);
    }

    static void close() {
        if (enabled()) authed(packet("pfClose"));
    }

    static void leave() {
        if (enabled()) authed(packet("pfLeave"));
    }

    static void join(String listingId, String note) {
        if (!enabled()) return;
        JsonObject p = packet("pfJoin");
        p.addProperty("listingId", listingId);
        if (note != null && !note.isBlank()) p.addProperty("note", note);
        authed(p);
    }

    static void cancel(String listingId) {
        JsonObject p = packet("pfCancel");
        p.addProperty("listingId", listingId);
        authed(p);
    }

    static void byUuid(String type, String uuid) {
        if (!enabled()) return;
        JsonObject p = packet(type);
        p.addProperty("uuid", uuid);
        authed(p);
    }

    private static JsonObject packet(String type) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        return p;
    }

    // ---------------------------------------------------------------- receiving

    /** A pf* packet from the server (on the game thread). */
    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "pfListings" -> {
                if (Sbc.hasObj(packet, "categories")) {
                    Map<String, Category> map = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> e : Sbc.obj(packet, "categories").entrySet()) {
                        if (!e.getValue().isJsonObject()) continue;
                        JsonObject o = e.getValue().getAsJsonObject();
                        List<String> details = new ArrayList<>();
                        for (JsonElement d : Sbc.arr(o, "details")) if (d.isJsonPrimitive()) details.add(d.getAsString());
                        map.put(e.getKey(), new Category(e.getKey(), (int) Sbc.lng(o, "maxSize", 5), List.copyOf(details)));
                    }
                    if (!map.isEmpty()) categories = map;
                }
                List<Listing> list = new ArrayList<>();
                for (JsonElement e : Sbc.arr(packet, "listings")) {
                    if (e.isJsonObject()) list.add(parseListing(e.getAsJsonObject()));
                }
                listings = List.copyOf(list);
                listingsReceived = true;
            }
            case "pfMine" -> {
                Listing listing = Sbc.hasObj(packet, "listing") ? parseListing(Sbc.obj(packet, "listing")) : null;
                List<String> pending = new ArrayList<>();
                for (JsonElement e : Sbc.arr(packet, "pending")) if (e.isJsonPrimitive()) pending.add(e.getAsString());
                String role = Sbc.str(packet, "role");
                mine = new Mine(role.isEmpty() ? "none" : role, listing, Set.copyOf(pending));
                mineReceived = true;
            }
            case "pfAnnounce" -> onAnnounce(packet);
            case "pfEvent" -> onEvent(packet);
            case "pfError" -> onError(Sbc.str(packet, "code"), Sbc.str(packet, "message"));
            default -> {}
        }
        version++;
    }

    /**
     * pfAnnounce: the server's announcement of a new listing ({@code message}, already coloured with § codes), with a
     * [JOIN] button unless the listing is yours. Hidden with Party Finder Announcements off.
     */
    private static void onAnnounce(JsonObject packet) {
        if (!Sbc.config().chat.partyFinderAnnouncements) return;
        String message = Sbc.str(packet, "message");
        if (message.isBlank()) return;
        MutableComponent line = Component.literal(message);
        JsonObject listing = Sbc.obj(packet, "listing");
        String id = Sbc.str(listing, "id");
        if (!id.isBlank() && !Sbc.isSelf(Sbc.str(listing, "leaderUuid"))) {
            String leader = safeName(Sbc.str(listing, "leader"));
            line.append(" ").append(button("[JOIN]", ChatFormatting.GREEN, "/s2 pf join " + id,
                leader.isEmpty() ? "Ask to join" : "Ask " + leader + " to join"));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(line);
    }

    private static Listing parseListing(JsonObject o) {
        List<Member> members = new ArrayList<>();
        for (JsonElement e : Sbc.arr(o, "members")) {
            if (e.isJsonPrimitive()) members.add(new Member("", e.getAsString()));
            else if (e.isJsonObject()) members.add(new Member(Sbc.str(e.getAsJsonObject(), "uuid"), Sbc.str(e.getAsJsonObject(), "username")));
        }
        List<Request> requests = new ArrayList<>();
        for (JsonElement e : Sbc.arr(o, "requests")) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            requests.add(new Request(Sbc.str(r, "uuid"), Sbc.str(r, "username"), Sbc.str(r, "note"), Sbc.lng(r, "at", 0)));
        }
        return new Listing(Sbc.str(o, "id"), Sbc.str(o, "leader"), Sbc.str(o, "leaderUuid"), Sbc.str(o, "category"),
            Sbc.str(o, "detail"), Sbc.str(o, "note"), (int) Sbc.lng(o, "size", 0), List.copyOf(members),
            Sbc.bool(o, "full"), Sbc.lng(o, "createdAt", 0), Sbc.lng(o, "expiresAt", 0), List.copyOf(requests));
    }

    private static void onEvent(JsonObject packet) {
        String event = Sbc.str(packet, "event");
        String username = safeName(Sbc.str(packet, "username"));
        String message = Sbc.str(packet, "message");
        MutableComponent line = Component.literal(message.isBlank() ? event : message).withStyle(ChatFormatting.YELLOW);
        switch (event) {
            case "request" -> {
                if (!username.isEmpty()) {
                    line.append(" ").append(button("[ACCEPT]", ChatFormatting.GREEN, "/s2 pf accept " + username, "Let " + username + " into your group"))
                        .append(" ").append(button("[DECLINE]", ChatFormatting.RED, "/s2 pf decline " + username, "Decline " + username));
                }
                Sbc.toast(Component.literal("Party Finder"), Component.literal(message.isBlank() ? username + " wants to join" : message));
            }
            case "invite" -> {
                // The leader accepted someone: invite them to the Hypixel party.
                Minecraft mc = Minecraft.getInstance();
                if (!username.isEmpty() && mc.getConnection() != null) mc.getConnection().sendCommand("party invite " + username);
            }
            case "accepted" -> Sbc.toast(Component.literal("Party Finder"), Component.literal("You got in! Watch for the party invite."));
            default -> {}
        }
        say(line);
    }

    private static void onError(String code, String message) {
        error = message.isBlank() ? code : message;
        errorAt = System.currentTimeMillis();
        if ("notLoggedIn".equals(code)) {
            Sky2MLogin.forget();
            mineAskedOn = null;
            if (lastAuthed != null && !retried) {
                // Log in and try once more.
                retried = true;
                SbcNet.sendAuthed(lastAuthed);
                return;
            }
        }
        say(Component.literal(error).withStyle(ChatFormatting.RED));
    }

    static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(prefix().append(message));
        });
    }

    static MutableComponent prefix() {
        return Component.literal("[Party Finder] ").withStyle(ChatFormatting.GOLD);
    }

    private static MutableComponent button(String text, ChatFormatting colour, String command, String hover) {
        return Component.literal(text).withStyle(s -> s.withColor(colour).withBold(true)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    static String safeName(String name) {
        return name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "");
    }

    static String categoryLabel(String category) {
        if (category == null || category.isEmpty()) return "";
        return Character.toUpperCase(category.charAt(0)) + category.substring(1);
    }

    static String timeLeft(Listing listing) {
        if (listing.expiresAt() <= 0) return "";
        long ms = listing.expiresAt() - System.currentTimeMillis();
        if (ms <= 0) return "expired";
        long minutes = ms / 60_000L;
        return minutes >= 1 ? minutes + "m left" : ms / 1000 + "s left";
    }

    // ---------------------------------------------------------------- commands

    private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return ClientCommands.literal("pf")
            .executes(c -> !Flags.check("partyFinder") ? 1
                : com.epic60869.sky2m.custom.util.Compat.queueOpenScreen(new Sky2MPartyFinderScreen()))
            .then(ClientCommands.literal("list")
                .executes(c -> printListings(null))
                .then(ClientCommands.argument("category", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(categories.keySet(), b))
                    .executes(c -> printListings(StringArgumentType.getString(c, "category")))))
            .then(ClientCommands.literal("create")
                .then(ClientCommands.argument("category", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(categories.keySet(), b))
                    .then(ClientCommands.argument("detail", StringArgumentType.word())
                        .suggests((c, b) -> {
                            Category cat = categories.get(StringArgumentType.getString(c, "category").toLowerCase(Locale.ROOT));
                            return SharedSuggestionProvider.suggest(cat == null ? List.of() : cat.details(), b);
                        })
                        .then(ClientCommands.argument("size", IntegerArgumentType.integer(1, 10))
                            .executes(c -> createCommand(c.getArgument("category", String.class), c.getArgument("detail", String.class),
                                IntegerArgumentType.getInteger(c, "size"), ""))
                            .then(ClientCommands.argument("note", StringArgumentType.greedyString())
                                .executes(c -> createCommand(c.getArgument("category", String.class), c.getArgument("detail", String.class),
                                    IntegerArgumentType.getInteger(c, "size"), StringArgumentType.getString(c, "note"))))))))
            .then(ClientCommands.literal("close").executes(c -> run(Sky2MPartyFinder::close)))
            .then(ClientCommands.literal("leave").executes(c -> run(Sky2MPartyFinder::leave)))
            .then(ClientCommands.literal("mine").executes(c -> printMine()))
            .then(ClientCommands.literal("join")
                .then(ClientCommands.argument("listingId", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(listings.stream().map(Listing::id).toList(), b))
                    .executes(c -> run(() -> join(StringArgumentType.getString(c, "listingId"), null)))
                    .then(ClientCommands.argument("note", StringArgumentType.greedyString())
                        .executes(c -> run(() -> join(StringArgumentType.getString(c, "listingId"), StringArgumentType.getString(c, "note")))))))
            .then(ClientCommands.literal("cancel")
                .then(ClientCommands.argument("listingId", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(mine.pending(), b))
                    .executes(c -> run(() -> cancel(StringArgumentType.getString(c, "listingId"))))))
            .then(playerCommand("accept", "pfAccept", true))
            .then(playerCommand("decline", "pfDecline", true))
            .then(playerCommand("kick", "pfKick", false));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> playerCommand(String name, String type, boolean fromRequests) {
        return ClientCommands.literal(name)
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(names(fromRequests), b))
                .executes(c -> {
                    String player = StringArgumentType.getString(c, "player");
                    String uuid = uuidOf(player, fromRequests);
                    if (uuid == null) {
                        say(Component.literal(fromRequests ? player + " hasn't asked to join your group." : player + " isn't in your group.")
                            .withStyle(ChatFormatting.RED));
                        if (!mineReceived) requestMine();
                        return 1;
                    }
                    byUuid(type, uuid);
                    return 1;
                }));
    }

    private static List<String> names(boolean fromRequests) {
        Listing listing = mine.listing();
        if (listing == null) return List.of();
        return fromRequests ? listing.requests().stream().map(Request::username).toList()
            : listing.members().stream().filter(m -> !Sbc.isSelf(m.uuid())).map(Member::username).toList();
    }

    private static String uuidOf(String player, boolean fromRequests) {
        Listing listing = mine.listing();
        if (listing == null) return null;
        if (fromRequests) {
            for (Request r : listing.requests()) if (r.username().equalsIgnoreCase(player)) return r.uuid();
        } else {
            for (Member m : listing.members()) if (m.username().equalsIgnoreCase(player)) return m.uuid();
        }
        return null;
    }

    private static int run(Runnable action) {
        Minecraft.getInstance().execute(action);
        return 1;
    }

    private static int createCommand(String category, String detail, int size, String note) {
        return run(() -> create(category, detail, size, note));
    }

    private static int printListings(String category) {
        Minecraft.getInstance().execute(() -> {
            if (!online()) {
                say(Component.literal(SbcNet.OFFLINE).withStyle(ChatFormatting.RED));
                Sky2MGlobalChat.ensureConnected();
                return;
            }
            List<Listing> shown = listings.stream()
                .filter(l -> category == null || l.category().equalsIgnoreCase(category)).toList();
            if (shown.isEmpty()) {
                say(Component.literal(listingsReceived ? "No open listings" + (category == null ? "." : " for " + category + ".")
                    : "No listings received yet.").withStyle(ChatFormatting.GRAY));
                return;
            }
            say(Component.literal(shown.size() + " open listing" + (shown.size() == 1 ? "" : "s") + ":").withStyle(ChatFormatting.GRAY));
            for (Listing l : shown) {
                MutableComponent line = Component.literal(categoryLabel(l.category()) + " " + l.detail()).withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(" " + l.leader()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" " + l.members().size() + "/" + l.size()).withStyle(l.full() ? ChatFormatting.RED : ChatFormatting.GREEN));
                if (!l.note().isBlank()) line.append(Component.literal(" " + l.note()).withStyle(ChatFormatting.GRAY));
                line.append(" ");
                if (mine.pending().contains(l.id())) {
                    line.append(button("[CANCEL]", ChatFormatting.YELLOW, "/s2 pf cancel " + l.id(), "Withdraw your join request"));
                } else if (!l.full()) {
                    line.append(button("[JOIN]", ChatFormatting.GREEN, "/s2 pf join " + l.id(), "Ask " + l.leader() + " to join"));
                }
                say(line);
            }
        });
        return 1;
    }

    private static int printMine() {
        Minecraft.getInstance().execute(() -> {
            Mine m = mine;
            if (!mineReceived || !m.inGroup()) {
                if (!mineReceived) requestMine();
                say(Component.literal(mineReceived ? "You're not in a Party Finder group." : "Asking the server...").withStyle(ChatFormatting.GRAY));
                if (mineReceived && !m.pending().isEmpty()) say(Component.literal("Pending join requests: " + m.pending().size()).withStyle(ChatFormatting.GRAY));
                return;
            }
            Listing l = m.listing();
            say(Component.literal((m.leader() ? "You lead " : "You're in ") + l.leader() + "'s " + categoryLabel(l.category()) + " " + l.detail()
                + " group (" + l.members().size() + "/" + l.size() + ")").withStyle(ChatFormatting.AQUA));
            say(Component.literal("Members: " + String.join(", ", l.members().stream().map(Member::username).toList())).withStyle(ChatFormatting.GRAY));
            for (Request r : l.requests()) {
                String name = safeName(r.username());
                say(Component.literal(r.username() + (r.note().isBlank() ? "" : ": " + r.note()) + " ").withStyle(ChatFormatting.YELLOW)
                    .append(button("[ACCEPT]", ChatFormatting.GREEN, "/s2 pf accept " + name, "Let " + name + " in"))
                    .append(" ").append(button("[DECLINE]", ChatFormatting.RED, "/s2 pf decline " + name, "Decline " + name)));
            }
        });
        return 1;
    }
}
