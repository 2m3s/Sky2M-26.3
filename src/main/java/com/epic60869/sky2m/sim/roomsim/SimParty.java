package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.relay.SimRelay;
import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sim parties: friends practise a run together in the host's own sim world.
 *
 * <p><b>Hosting</b> ({@code /simparty open}) is Minecraft's own "Open to LAN" ({@link IntegratedServer#publishServer})
 * with online-mode authentication on, so names cannot be spoofed. It is strictly INVITE-ONLY: while the sim is
 * active, every login that is not the host's own account and not on this session's invite list is refused in the
 * configuration phase, before the player is placed in the world ({@link #gate}). That check runs for any LAN
 * publish of a sim world, not only one opened through this command, and the list is cleared whenever hosting stops.
 * There is no public listing of any kind; vanilla's LAN announcement still reaches the host's own local network,
 * but nobody there can join without an invite.
 *
 * <p><b>Across the internet</b>: {@link SimRelay} (the Sky2M mod-server's relay, no port forwarding) hosts the LAN
 * port too and hands out a party code; the relay keeps its own copy of the invite list ({@link SimRelay#allow}).
 *
 * <p><b>Invites</b>: Sky2M Online has no private message channel between two players, so {@code /simparty invite}
 * puts the name on both lists and gives the host a copyable {@code /simparty join <code>} line (and the LAN address
 * for friends on the same network) to send himself. The guest runs it: a code goes through
 * {@link SimRelay#joinAndConnect}, an address straight to {@link ConnectScreen}.
 *
 * <p><b>Guests</b> must have Sky2M with the Dungeon Sim on: the host sends them a {@link Hello} on join (and again
 * whenever the map, floor or their ghost state changes), which makes {@link SimState#canAct} true on that one
 * connection so the guest's own map and HUDs run. Only the party leader (the host) uses sim commands
 * ({@link SimCommandGate}); a guest's gameplay inputs his client cannot run itself (class ultimate, Mage beam,
 * Terminator shots, {@code /speed}) go to the host as an {@link Action}, executed for that guest's
 * {@link ServerPlayer} only, and only while he is a player in the host's live sim world. Anything command-driven
 * is refused there ({@link SimCommandGate#guestActionAllowed}).
 *
 * <p><b>Per-player state on the host</b>: each guest's class and wardrobe ({@link #member}) arrive in the "profile"
 * action, with default stats (a guest's own stat numbers are ignored); everything else (health and mana pools, cooldowns, ghosts, class timers) is already keyed
 * by player.
 */
public final class SimParty {

    private static final Logger LOGGER = ModLog.get("sky2m-simparty");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private SimParty() {
    }

    // ------------------------------------------------------------------ payloads

    /** Host to guest: "this connection is a sim world", with what the guest's client-side sim needs to know. */
    public record Hello(String mapCode, String floor, boolean generated, boolean ghost) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sky2m", "sim_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1 << 18), Hello::mapCode,
                ByteBufCodecs.stringUtf8(16), Hello::floor,
                ByteBufCodecs.BOOL, Hello::generated,
                ByteBufCodecs.BOOL, Hello::ghost,
                Hello::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Host to guest: the floor the host built (its placed rooms and the map's published grid) and each room's map state,
     * as JSON. A guest builds nothing, so without this its Dungeon Map, secret waypoints and routes had no floor at all.
     */
    public record Floor(String json) implements CustomPacketPayload {
        public static final Type<Floor> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sky2m", "sim_floor"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Floor> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(1 << 20), Floor::json,
                Floor::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Guest to host: one sim command or input, run on the host server for the guest who sent it. */
    public record Action(String action, String arg) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sky2m", "sim_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Action::action,
                ByteBufCodecs.stringUtf8(1 << 17), Action::arg,
                Action::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------ per-player profiles (server thread)

    /** What one player brings to the run: stats, class, and that class's wardrobe, equipment, pet and Pet Rule. */
    public record Member(SimStats.Profile profile, SimGear.ClassGear gear) {
        /** The pet out. */
        public SimGear.Pet pet() {
            return gear.pet().pet();
        }
    }

    private static final Map<UUID, Member> MEMBERS = new ConcurrentHashMap<>();

    /** Whether this player is the world's owner - the host, or the only player when nobody joined. */
    static boolean isHost(ServerPlayer sp) {
        if (sp == null) {
            return false;
        }
        MinecraftServer server = sp.level().getServer();
        GameProfile owner = server == null ? null : server.getSingleplayerProfile();
        return owner == null || owner.id() == null || owner.id().equals(sp.getUUID());
    }

    /** This player's stats, class and wardrobe: the host's own settings for the host, what a guest sent for him. */
    static Member member(ServerPlayer sp) {
        if (sp != null && !isHost(sp)) {
            Member m = MEMBERS.get(sp.getUUID());
            if (m != null) {
                return m;
            }
            SimStats.Profile d = SimStats.defaults();
            return new Member(d, SimGear.defaultGear(d.selected()));
        }
        SimStats.Profile p = SimStats.profile();
        return new Member(p, SimGear.classGear(p.selected()));
    }

    /**
     * Changes a guest's gear on the host (a wardrobe swap or a Pet Rules swap made on the server). His own client
     * makes the same change to its saved gear ({@link SimGear#equipSlot(Minecraft, int)}, {@link SimPetRules}), so
     * the wardrobe he sends next agrees. Server thread.
     */
    static void updateGear(ServerPlayer sp, java.util.function.UnaryOperator<SimGear.ClassGear> f) {
        Member m = MEMBERS.get(sp.getUUID());
        if (m == null) {
            m = member(sp);
        }
        Member next = new Member(m.profile(), f.apply(m.gear()));
        MEMBERS.put(sp.getUUID(), next);
    }

    /**
     * A guest's class pick in Mort's menu ({@link SimPresets}): his class (default stats, as {@link #receiveProfile})
     * and that class's preset gear, on the host. Holds until his client next sends its own profile (a rejoin).
     * Server thread.
     */
    static void setGuestClass(ServerPlayer sp, SimStats.DungeonClass c, SimGear.ClassGear gear) {
        if (sp == null || c == null || isHost(sp)) {
            return;
        }
        MEMBERS.put(sp.getUUID(), new Member(SimStats.defaultsWithClass(c), gear));
    }

    static SimStats.Profile profile(ServerPlayer sp) {
        return member(sp).profile();
    }

    static SimStats.DungeonClass dungeonClass(ServerPlayer sp) {
        return member(sp).profile().selected();
    }

    static int classLevel(ServerPlayer sp) {
        SimStats.Profile p = member(sp).profile();
        return p.classLevel(p.selected());
    }

    /**
     * Whether {@code player} is a party guest acting on the host's live sim server: a click the host's client never
     * sees, which the puzzles and levers (written against the host's own client) answer through {@link #forGuest}.
     */
    public static boolean guestOnServer(net.minecraft.world.entity.player.Player player) {
        return player instanceof ServerPlayer sp && !isHost(sp) && SimState.isActive() && SimAbilities.simServer(sp);
    }

    /**
     * Runs a guest's click on the host's client thread, where the puzzles and levers keep their state, with the
     * guest's aim and identity in force ({@link SimAim}). Call on the server thread.
     */
    public static void forGuest(ServerPlayer sp, Runnable body) {
        SimAim.Aim aim = SimAim.of(sp);
        SimAbilities.onClient(() -> SimAim.with(aim, body));
    }

    /** Every player in the sim world, host first. */
    static List<ServerPlayer> players(MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>(server.getPlayerList().getPlayers());
        out.sort((a, b) -> Boolean.compare(!isHost(a), !isHost(b)));
        return out;
    }

    // ------------------------------------------------------------------ invites (any thread)

    private static final class Invite {
        final String name;
        volatile UUID id;

        Invite(String name) {
            this.name = name;
        }
    }

    /** This hosting session's invite list, by lower-case name. Cleared whenever hosting stops. */
    private static final Map<String, Invite> INVITES = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile boolean hosting;
    private static volatile String address = "";

    /** Whether this profile may join: by UUID once it is known, by exact name before that. */
    static boolean isInvited(GameProfile profile) {
        if (profile == null || profile.id() == null) {
            return false;
        }
        synchronized (INVITES) {
            for (Invite invite : INVITES.values()) {
                if (invite.id != null) {
                    if (invite.id.equals(profile.id())) {
                        return true;
                    }
                } else if (invite.name.equalsIgnoreCase(profile.name())) {
                    invite.id = profile.id();
                    return true;
                }
            }
        }
        return false;
    }

    private static void stopHosting(String why) {
        SimRelay.stopHosting();
        boolean was = hosting;
        hosting = false;
        address = "";
        INVITES.clear();
        MEMBERS.clear();
        LAST_SENT.clear();
        LAST_FLOOR.clear();
        if (was) {
            LOGGER.info("Sim party closed ({})", why);
        }
    }

    // ------------------------------------------------------------------ registration

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Floor.TYPE, Floor.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, ctx) -> {
            ServerPlayer sp = ctx.player();
            ctx.server().execute(() -> onAction(sp, payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(Hello.TYPE, (payload, ctx) -> {
            Minecraft client = ctx.client();
            client.execute(() -> onHello(client, payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(Floor.TYPE, (payload, ctx) -> {
            Minecraft client = ctx.client();
            client.execute(() -> onFloor(client, payload));
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(SimParty::guestTick);
        // THE INVITE CHECK, before the player is placed in the world: the configuration phase runs after login
        // (online-mode authentication done, so the profile's UUID is Mojang's) and before PlayerList.placeNewPlayer.
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((handler, server) -> {
            if (!(server instanceof IntegratedServer) || !SimState.isActive()) {
                return;
            }
            GameProfile profile = handler.getOwner();
            GameProfile owner = server.getSingleplayerProfile();
            if (profile == null || owner != null && owner.id() != null && owner.id().equals(profile.id())) {
                return;
            }
            if (!isInvited(profile)) {
                LOGGER.info("Sim party: refused {} ({}) - not invited", profile.name(), profile.id());
                handler.disconnect(Component.literal("You aren't invited to this Dungeon Sim party."));
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player, server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onLeave(handler.player, server));
        ServerTickEvents.END_SERVER_TICK.register(SimParty::serverTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (server instanceof IntegratedServer) {
                stopHosting("world closed");
            }
        });
        // A guest leaving the party world ends his relay connection (not the world he leaves to join one).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (SimState.isGuestConnection(handler) && SimRelay.isJoined()) {
                SimRelay.leave();
            }
        });
        // /pc in the sim: Hypixel's party chat, which the Leap Menu's "Leaped to" announce and the party features
        // send and read. Server-side, so it only exists on the sim's own integrated server.
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> dispatcher.register(
                Commands.literal("pc")
                        .requires(src -> SimState.isActive())
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                                    partyChat(sp, StringArgumentType.getString(ctx, "message"));
                                    return 1;
                                }))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommands.literal("simparty")
                        .executes(ctx -> usage())
                        .then(ClientCommands.literal("open").executes(ctx -> open(Minecraft.getInstance())))
                        .then(ClientCommands.literal("close").executes(ctx -> close(Minecraft.getInstance())))
                        .then(ClientCommands.literal("list").executes(ctx -> list(Minecraft.getInstance())))
                        .then(ClientCommands.literal("invite")
                                .then(ClientCommands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> invite(Minecraft.getInstance(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(ClientCommands.literal("uninvite")
                                .then(ClientCommands.argument("player", StringArgumentType.word())
                                        .suggests((ctx, b) -> {
                                            synchronized (INVITES) {
                                                for (Invite i : INVITES.values()) {
                                                    b.suggest(i.name);
                                                }
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> uninvite(Minecraft.getInstance(),
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(ClientCommands.literal("join")
                                .then(ClientCommands.argument("code", StringArgumentType.greedyString())
                                        .executes(ctx -> join(Minecraft.getInstance(),
                                                StringArgumentType.getString(ctx, "code")))))));
    }

    // ------------------------------------------------------------------ host commands (client thread)

    private static int usage() {
        ModChat.send("Sim", ModChat.text("Sim party: "),
                ModChat.dim("/simparty open | close | list | invite <player> | uninvite <player> | join <code>"));
        return 1;
    }

    /** The host's integrated server, when he is in his own sim world; says why not otherwise. */
    private static IntegratedServer hostServer(Minecraft client) {
        IntegratedServer server = client.getSingleplayerServer();
        if (server == null || !SimState.canAct(client) || SimState.isGuest(client)) {
            ModChat.send("Sim", ModChat.bad("Only in your own Dungeon Sim world"),
                    ModChat.dim(" - open the sim first (/s2 sim)."));
            return null;
        }
        return server;
    }

    private static int open(Minecraft client) {
        IntegratedServer server = hostServer(client);
        if (server == null) {
            return 0;
        }
        if (!server.isPublished()) {
            int port = net.minecraft.util.HttpUtil.getAvailablePort();
            // Online mode ON: every guest is authenticated with Mojang, so the invite list's names and UUIDs are
            // real. No commands for guests, survival like the host. The one exception is the dev test clients
            // (offline S2Test accounts), only in a development environment and only with -Dsky2m.simPartyOffline.
            boolean offlineTest = net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
                    && Boolean.getBoolean("sky2m.simPartyOffline");
            server.setUsesAuthentication(!offlineTest);
            if (!publishSurvival(server, port)) {
                ModChat.send("Sim", ModChat.bad("Could not open the sim to friends"),
                        ModChat.dim(" (port " + port + " is not free?)"));
                return 0;
            }
            stopHosting("new session");
        }
        boolean fresh = !hosting;
        hosting = true;
        address = localAddress() + ":" + server.getPort();
        LOGGER.info("Sim party open on {}", address);
        ModChat.send("Sim", ModChat.good("Sim party open"), ModChat.dim(" - invite-only. LAN: "),
                copyable("/simparty join " + address, address));
        if (fresh || !SimRelay.isHosting()) {
            // The relay, for friends on other networks: same invite list, a party code instead of an address.
            synchronized (INVITES) {
                for (Invite i : INVITES.values()) {
                    SimRelay.allow(i.name);
                }
            }
            SimRelay.host(server.getPort(), code -> ModChat.send("Sim", ModChat.text("Party code: "),
                    copyable("/simparty join " + code, code),
                    ModChat.dim(" - invited friends anywhere join with /simparty join " + code)),
                    reason -> ModChat.send("Sim", ModChat.bad("Party relay stopped: "), ModChat.dim(reason
                            + " - friends on your own network can still join by the LAN address.")));
        }
        ModChat.send("Sim", ModChat.dim("Invite friends with "), ModChat.value("/simparty invite <name>"),
                ModChat.dim("."));
        return 1;
    }

    private static int close(Minecraft client) {
        IntegratedServer server = hostServer(client);
        if (server == null) {
            return 0;
        }
        if (server.isPublished()) {
            server.unpublishServer();
        }
        server.execute(() -> {
            for (ServerPlayer sp : new ArrayList<>(server.getPlayerList().getPlayers())) {
                if (!isHost(sp)) {
                    sp.connection.disconnect(Component.literal("The host closed the Dungeon Sim party."));
                }
            }
        });
        stopHosting("closed by the host");
        ModChat.send("Sim", ModChat.text("Sim party closed. Invites cleared."));
        return 1;
    }

    private static int list(Minecraft client) {
        IntegratedServer server = hostServer(client);
        if (server == null) {
            return 0;
        }
        boolean open = server.isPublished();
        ModChat.send("Sim", ModChat.text("Sim party: "), open ? ModChat.good("open") : ModChat.dim("closed"),
                open ? ModChat.dim(" on " + address) : ModChat.dim(" - /simparty open"));
        List<String> online = new ArrayList<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            online.add(sp.getGameProfile().name().toLowerCase(Locale.ROOT));
        }
        String code = SimRelay.currentCode();
        if (open) {
            if (code != null) {
                ModChat.send("Sim", ModChat.text("Party code: "), copyable("/simparty join " + code, code),
                        ModChat.dim("  (" + SimRelay.guestCount() + " through the relay)"));
            } else {
                String err = SimRelay.lastError();
                ModChat.send("Sim", ModChat.dim(SimRelay.isHosting() ? "Party code: waiting for the relay..."
                        : "Relay off" + (err == null || err.isBlank() ? "" : ": " + err) + " - LAN only."));
            }
        }
        List<Invite> invites;
        synchronized (INVITES) {
            invites = new ArrayList<>(INVITES.values());
        }
        if (invites.isEmpty()) {
            ModChat.send("Sim", ModChat.dim("Nobody invited yet."));
        }
        for (Invite i : invites) {
            boolean in = online.contains(i.name.toLowerCase(Locale.ROOT));
            ModChat.send("Sim", ModChat.value(" " + i.name + " "), in ? ModChat.good("joined") : ModChat.dim("invited"));
        }
        return 1;
    }

    private static int invite(Minecraft client, String name) {
        IntegratedServer server = hostServer(client);
        if (server == null) {
            return 0;
        }
        if (!NAME.matcher(name).matches()) {
            ModChat.send("Sim", ModChat.bad("Not a Minecraft name: "), ModChat.value(name));
            return 0;
        }
        if (!server.isPublished() || !hosting) {
            if (open(client) == 0) {
                return 0;
            }
        }
        INVITES.putIfAbsent(name.toLowerCase(Locale.ROOT), new Invite(name));
        // The relay checks the same list: an invite is one name on both.
        SimRelay.allow(name);
        String code = SimRelay.currentCode();
        ModChat.send("Sim", ModChat.good("Invited " + name + ". "),
                ModChat.dim("Sky2M Online can't send private messages, so send them this yourself: "),
                code != null ? copyable("/simparty join " + code, "[Copy invite]")
                        : ModChat.dim("(party code not ready yet - /simparty list shows it)"));
        ModChat.send("Sim", ModChat.dim("Same network? "), copyable("/simparty join " + address, "[Copy LAN invite]"));
        return 1;
    }

    private static int uninvite(Minecraft client, String name) {
        IntegratedServer server = hostServer(client);
        if (server == null) {
            return 0;
        }
        Invite removed = INVITES.remove(name.toLowerCase(Locale.ROOT));
        SimRelay.disallow(name);
        if (removed == null) {
            ModChat.send("Sim", ModChat.dim(name + " was not invited."));
            return 0;
        }
        UUID id = removed.id;
        server.execute(() -> {
            for (ServerPlayer sp : new ArrayList<>(server.getPlayerList().getPlayers())) {
                if (!isHost(sp) && (sp.getUUID().equals(id) || sp.getGameProfile().name().equalsIgnoreCase(name))) {
                    sp.connection.disconnect(Component.literal("You aren't invited to this Dungeon Sim party."));
                }
            }
        });
        ModChat.send("Sim", ModChat.text("Uninvited " + removed.name + "."));
        return 1;
    }

    /**
     * Guest side: connects to a friend's sim party, leaving whatever world he is in first. A party code goes through
     * the relay; an address (has a dot or a colon) is a direct LAN connection.
     */
    private static int join(Minecraft client, String raw) {
        String text = raw.trim();
        if (text.indexOf('.') < 0 && text.indexOf(':') < 0) {
            if (!text.matches("[A-Za-z0-9-]{3,32}")) {
                ModChat.send("Sim", ModChat.bad("Not a party code: "), ModChat.value(text));
                return 0;
            }
            ModChat.send("Sim", ModChat.dim("Joining party " + text + " through the relay..."));
            SimRelay.joinAndConnect(text);
            return 1;
        }
        if (!ServerAddress.isValidAddress(text)) {
            ModChat.send("Sim", ModChat.bad("Not an address: "), ModChat.value(text));
            return 0;
        }
        ServerAddress target = ServerAddress.parseString(text);
        client.execute(() -> {
            if (client.level != null) {
                if (client.isLocalServer()) {
                    client.disconnectWithSavingScreen();
                } else {
                    client.disconnectWithProgressScreen();
                }
            }
            ServerData data = new ServerData("Dungeon Sim party", text, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), client, target, data, false, null);
        });
        return 1;
    }

    private static MutableComponent copyable(String copied, String shown) {
        return Component.literal(shown).withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(copied))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy:\n" + copied))));
    }

    /** The host's address on his local network: the first site-local IPv4 of an interface that is up. */
    private static String localAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // ------------------------------------------------------------------ server side

    /** Last hello state sent to each guest, so it is re-sent only on a change. */
    private static final Map<UUID, Hello> LAST_SENT = new ConcurrentHashMap<>();
    private static int tick;

    private static void onJoin(ServerPlayer sp, MinecraftServer server) {
        if (!(server instanceof IntegratedServer) || !SimState.isActive() || isHost(sp)) {
            return;
        }
        if (!ServerPlayNetworking.canSend(sp, Hello.TYPE)) {
            sp.connection.disconnect(Component.literal(
                    "This Dungeon Sim party needs Sky2M with Dungeon Sim turned on."));
            return;
        }
        LAST_SENT.remove(sp.getUUID());
        sendHello(sp);
        sp.setGameMode(GameType.SURVIVAL);
        SimVitals.refill(sp);
        // Where the party is, not the void at world spawn.
        ServerPlayer host = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isHost(p)) {
                host = p;
            }
        }
        if (host != null && host.level() instanceof ServerLevel level) {
            sp.teleportTo(level, host.getX(), host.getY(), host.getZ(), java.util.Set.of(), host.getYRot(),
                    host.getXRot(), false);
        }
        // Hypixel's party lines, which PartyTracker reads on every client.
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : players(server)) {
            if (p != sp) {
                names.add(p.getGameProfile().name());
            }
        }
        if (!names.isEmpty()) {
            sp.sendSystemMessage(Component.literal("You'll be partying with: " + String.join(", ", names))
                    .withStyle(ChatFormatting.YELLOW));
        }
        Component joined = Component.literal(sp.getGameProfile().name() + " joined the party.")
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != sp) {
                p.sendSystemMessage(joined);
            }
        }
        LOGGER.info("Sim party: {} joined", sp.getGameProfile().name());
    }

    private static void onLeave(ServerPlayer sp, MinecraftServer server) {
        if (sp == null || isHost(sp)) {
            return;
        }
        MEMBERS.remove(sp.getUUID());
        STAFF_GUESTS.remove(sp.getUUID());
        LAST_SENT.remove(sp.getUUID());
        LAST_FLOOR.remove(sp.getUUID());
        if (!SimState.isActive()) {
            return;
        }
        Component left = Component.literal(sp.getGameProfile().name() + " has left the party.")
                .withStyle(ChatFormatting.YELLOW);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != sp) {
                p.sendSystemMessage(left);
            }
        }
    }

    private static void serverTick(MinecraftServer server) {
        if (++tick % 10 != 0 || !SimState.isActive() || !(server instanceof IntegratedServer)) {
            return;
        }
        String floor = null;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!isHost(sp) && ServerPlayNetworking.canSend(sp, Hello.TYPE)) {
                sendHello(sp);
            }
            if (!isHost(sp) && ServerPlayNetworking.canSend(sp, Floor.TYPE)) {
                if (floor == null) {
                    floor = floorJson();
                }
                if (!floor.equals(LAST_FLOOR.get(sp.getUUID()))) {
                    LAST_FLOOR.put(sp.getUUID(), floor);
                    ServerPlayNetworking.send(sp, new Floor(floor));
                }
            }
        }
    }

    /** The last {@link Floor} sent to each guest, so an unchanged floor is not sent again. */
    private static final Map<UUID, String> LAST_FLOOR = new ConcurrentHashMap<>();

    /** Host: the floor and room states for {@link Floor}. */
    private static String floorJson() {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject o = new JsonObject();
        com.epic60869.sky2m.sim.livemap.LiveMapFeature.PublishedFloor published =
                com.epic60869.sky2m.sim.livemap.LiveMapFeature.publishedFloor();
        if (published != null) {
            o.add("map", gson.toJsonTree(published));
        }
        o.add("rooms", gson.toJsonTree(SimRoomIndex.placed()));
        o.add("states", gson.toJsonTree(SimRoomState.snapshot()));
        return o.toString();
    }

    /** Guest: the last floor the host sent, published to the map once the room database is loaded. */
    private static com.epic60869.sky2m.sim.livemap.LiveMapFeature.PublishedFloor guestFloor;
    private static String guestFloorJson = "";

    private static void onFloor(Minecraft client, Floor floor) {
        if (client.getSingleplayerServer() != null || !SimState.isGuest(client)) {
            return;
        }
        com.google.gson.Gson gson = new com.google.gson.Gson();
        JsonObject o = JsonParser.parseString(floor.json()).getAsJsonObject();
        SimRoomState.replaceAll(o.has("states") ? gson.fromJson(o.get("states"),
                new com.google.gson.reflect.TypeToken<Map<String, Integer>>() { }.getType()) : Map.of());
        String layout = String.valueOf(o.get("map")) + o.get("rooms");
        if (layout.equals(guestFloorJson)) {
            return;
        }
        guestFloorJson = layout;
        List<SimRoomIndex.Placed> rooms = o.has("rooms") ? gson.fromJson(o.get("rooms"),
                new com.google.gson.reflect.TypeToken<List<SimRoomIndex.Placed>>() { }.getType()) : List.of();
        SimRoomIndex.replaceAll(rooms);
        guestFloor = o.has("map") ? gson.fromJson(o.get("map"),
                com.epic60869.sky2m.sim.livemap.LiveMapFeature.PublishedFloor.class) : null;
        guestPublished = false;
        com.epic60869.sky2m.sim.roomdatabase.RoomDatabase.ensureLoading();
    }

    private static boolean guestPublished;

    /** Guest: publishes the host's floor once the room database is ready, and again after a world change cleared it. */
    private static void guestTick(Minecraft client) {
        var floor = guestFloor;
        if (floor == null || floor.names().length == 0 || !SimState.isGuest(client)
                || !com.epic60869.sky2m.sim.roomdatabase.RoomDatabase.isReady()) {
            return;
        }
        if (guestPublished && com.epic60869.sky2m.sim.livemap.LiveMapFeature.hasSimFloor()) {
            return;
        }
        guestPublished = true;
        com.epic60869.sky2m.sim.livemap.LiveMapFeature.publishSimFloor(floor.roomCells(), floor.doorCells(),
                floor.names(), floor.clay(), floor.fitToRooms());
    }

    private static void sendHello(ServerPlayer sp) {
        Hello now = new Hello(SimState.mapCode(), SimState.floorLabel(), SimState.isGeneratedFloor(),
                SimDeath.isGhost(sp));
        if (now.equals(LAST_SENT.get(sp.getUUID()))) {
            return;
        }
        LAST_SENT.put(sp.getUUID(), now);
        ServerPlayNetworking.send(sp, now);
    }

    /** "Party > name: message" to everyone in the sim world. */
    static void partyChat(ServerPlayer sp, String message) {
        String clean = message == null ? "" : message.replace('§', ' ').trim();
        if (clean.isEmpty()) {
            return;
        }
        if (clean.length() > 256) {
            clean = clean.substring(0, 256);
        }
        Component line = Component.literal("Party > ").withStyle(ChatFormatting.BLUE)
                .append(Component.literal(sp.getGameProfile().name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(": " + clean).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer p : sp.level().getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
    }

    /**
     * A guest's sim command, on the host's server thread. Only for a guest who is a player in the host's live sim
     * world; the host himself never needs this path.
     */
    private static void onAction(ServerPlayer sp, Action action) {
        if (sp == null || isHost(sp) || !SimAbilities.simServer(sp) || sp.hasDisconnected()) {
            return;
        }
        // Party leader only: a guest's client never offers the commands behind give, revive or start, and this
        // refuses them (and anything else command-driven) from a modified client too. Gameplay inputs stay.
        if (!SimCommandGate.guestActionAllowed(action.action(), STAFF_GUESTS.contains(sp.getUUID()))) {
            LOGGER.debug("Sim party: refused {} action from guest {}", action.action(), sp.getGameProfile().name());
            sp.sendSystemMessage(ModChat.line("Sim", ModChat.bad(SimCommandGate.LEADER_ONLY)));
            return;
        }
        String arg = action.arg() == null ? "" : action.arg();
        try {
            switch (action.action()) {
                case "profile" -> receiveProfile(sp, arg);
                case "wardrobe" -> SimGear.equipSlot(sp, Integer.parseInt(arg.trim()));
                case "give" -> {
                    if (arg.equalsIgnoreCase("all")) {
                        SimItems.giveAllOnServer(sp);
                    } else {
                        SimItems.giveOnServer(sp, arg);
                    }
                }
                case "revive" -> {
                    if (SimDeath.isGhost(sp)) {
                        SimDeath.revive(sp, SimClassKit.reviveHealth(sp));
                    } else {
                        sp.sendSystemMessage(ModChat.line("Sim", ModChat.text("You are not dead.")));
                    }
                }
                case "ultimate" -> SimClassKit.ultimate(sp);
                case "start" -> SimMort.startFor(sp);
                case "beam" -> SimClass.beamOnServer(sp, UUID.fromString(arg));
                case "terminator" -> fireTerminator(sp, "left".equals(arg));
                // A left-click item ability (Gloomlock Grimoire, Gyrokinetic Wand): no packet of its own in the air.
                case "item_left" -> SimWeapons.leftClick(sp);
                case "speed" -> SimSpeed.applyOnServer(sp, Integer.parseInt(arg));
                case "staff_fly" -> SimSurvival.toggleFor(sp);
                case "staff_summon" -> summonFor(sp, arg);
                default -> LOGGER.debug("Sim party: unknown action {} from {}", action.action(),
                        sp.getGameProfile().name());
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Sim party: bad {} action from {}", action.action(), sp.getGameProfile().name(), e);
        }
    }

    /**
     * A guest's Terminator left click (a left click in the air sends no packet of its own): fired on the host's
     * client from the guest's eye and aim, as SimItems does for a right click.
     */
    private static void fireTerminator(ServerPlayer sp, boolean leftClick) {
        if (!SimTerminator.ITEM_ID.equals(com.epic60869.sky2m.sim.util.SimItemIds.skyblockId(sp.getMainHandItem()))) {
            return;
        }
        SimAim.Aim aim = SimAim.of(sp);
        SimAbilities.onClient(() -> {
            if (SimTerminator.readyToFire(aim.shooter())) {
                SimAim.with(aim, () -> SimTerminator.use(Minecraft.getInstance(), leftClick));
            }
        });
    }

    /** A staff guest's /summon on the host: "ZOMBIE", or "miniboss NAME", spawned at his feet. Server thread. */
    private static void summonFor(ServerPlayer sp, String arg) {
        String[] parts = arg.trim().split(" ");
        SimMobs.Kind kind = parts[0].equalsIgnoreCase("miniboss") ? SimMobs.Kind.MINIBOSS
                : SimMobs.Kind.valueOf(parts[0].toUpperCase(java.util.Locale.ROOT));
        SimMiniboss.Name boss = kind == SimMobs.Kind.MINIBOSS && parts.length > 1
                ? SimMiniboss.Name.valueOf(parts[1].toUpperCase(java.util.Locale.ROOT)) : null;
        SimMobs.spawnOnServer(sp.level(), sp.blockPosition(), kind, true,
                boss != null ? boss : SimMobs.DEFAULT_MINIBOSS);
        sp.sendSystemMessage(ModChat.line("Sim", ModChat.text("Summoned "), ModChat.value(
                (boss != null ? boss.text() : kind.name().toLowerCase(java.util.Locale.ROOT)))));
    }

    /**
     * Guests whose client says they are staff ({@code /s2 stafflogin}). The host cannot check another player's staff
     * login, so this trusts the guest's client; a party is invite-only, so only friends the host invited get here.
     */
    private static final java.util.Set<UUID> STAFF_GUESTS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void receiveProfile(ServerPlayer sp, String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        boolean staff = o.has("staff") && o.get("staff").getAsBoolean();
        if (staff) {
            STAFF_GUESTS.add(sp.getUUID());
        } else {
            STAFF_GUESTS.remove(sp.getUUID());
        }
        // A non-staff guest's CLASS is taken but his stat numbers stay at the defaults, since editing them is
        // staff-only. A staff guest keeps the stats he set. Wardrobe and pet come with him either way.
        SimStats.Profile sent = o.get("profile") instanceof JsonObject po
                ? SimStats.fromJson(po, SimStats.defaults()) : SimStats.defaults();
        SimStats.Profile profile = staff ? sent : SimStats.defaultsWithClass(sent.selected());
        // His wardrobe slots, equipment, pet and Pet Rule are item CHOICES, clamped to what real items can be (stars,
        // books and levels are bounded by SimItemBuild), so they come with him; the stat numbers do not.
        SimGear.ClassGear gear;
        if (o.get("gear") instanceof JsonObject go) {
            gear = SimGear.ClassGear.fromJson(go);
        } else {
            // An older guest client: one set and a pet id.
            gear = SimGear.defaultGear(profile.selected());
            if (o.has("armour")) {
                gear = gear.withSlot(0, new SimGear.WardrobeSlot("", SimGear.armour(o.get("armour").getAsString())
                        .pieces(), SimGear.EMPTY_FOUR)).withActive(0);
            }
            if (o.has("pet")) {
                gear = gear.withPet(new SimGear.PetBuild(SimGear.pet(o.get("pet").getAsString()),
                        SimGear.PetItem.NONE));
            }
        }
        Member before = MEMBERS.put(sp.getUUID(), new Member(profile, gear));
        if (before == null || before.gear().active() != gear.active()
                || !before.gear().activeSlot().equals(gear.activeSlot())) {
            SimGear.equipOnServer(sp, gear);
        }
        SimClassKit.ensureOrbOnServer(sp);
        if (before == null || before.profile().selected() != profile.selected()) {
            sp.sendSystemMessage(ModChat.line("Sim", ModChat.text("Class: "), ModChat.value(
                    profile.selected().label + " " + profile.classLevel(profile.selected()))));
        }
    }

    // ------------------------------------------------------------------ guest side (client thread)

    /** Whether the guest's client got a ghost flag from the host. */
    private static volatile boolean guestGhost;

    static boolean guestGhost() {
        return guestGhost && SimState.isGuest(Minecraft.getInstance());
    }

    private static void onHello(Minecraft client, Hello hello) {
        if (client.getSingleplayerServer() != null || client.getConnection() == null) {
            // Our own world never needs this: the host's client is the sim already.
            return;
        }
        boolean fresh = !SimState.isGuest(client);
        SimState.enterAsGuest(client.getConnection(), hello.mapCode(), hello.floor(), hello.generated());
        guestGhost = hello.ghost();
        if (fresh) {
            // A new host (or a rejoin): its floor is taken whole, even if it is the same as last time.
            guestFloorJson = "";
            guestPublished = false;
            LOGGER.info("Sim party: joined as a guest ({} map)", hello.floor());
            ModChat.send("Sim", ModChat.good("Joined a Dungeon Sim party. "),
                    ModChat.dim("Your class and wardrobe come with you; only the party leader can use sim commands."));
            sendProfile();
        }
    }

    /** Whether this client is a party guest. */
    public static boolean guest() {
        return SimState.isGuest(Minecraft.getInstance());
    }

    /** Sends one sim command to the host. False when this client is not a party guest. */
    public static boolean sendAction(String action, String arg) {
        if (!guest() || !ClientPlayNetworking.canSend(Action.TYPE)) {
            return false;
        }
        ClientPlayNetworking.send(new Action(action, arg == null ? "" : arg));
        return true;
    }

    /** Sends this guest's stats profile, class and wardrobe to the host. */
    public static void sendProfile() {
        if (!guest()) {
            return;
        }
        SimStats.Profile p = SimStats.profile();
        JsonObject o = new JsonObject();
        o.add("profile", SimStats.toJson(p));
        o.addProperty("armour", SimGear.armourFor(p.selected()).id());
        o.addProperty("pet", SimGear.petFor(p.selected()).id());
        o.add("gear", SimGear.classGear(p.selected()).toJson());
        o.addProperty("staff", SimCommandGate.isStaff());
        sendAction("profile", o.toString());
    }

    /** Opens the world to the LAN port; guests join in Survival (26.3 sets the game type on the server). */
    private static boolean publishSurvival(MinecraftServer server, int port) {
        server.setDefaultGameType(GameType.SURVIVAL);
        return server.publishServer(MinecraftServer.MultiplayerScope.LAN, false, port);
    }
}
