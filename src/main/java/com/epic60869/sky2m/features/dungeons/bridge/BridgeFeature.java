package com.epic60869.sky2m.features.dungeons.bridge;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonData;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Source;
import com.epic60869.sky2m.features.misc.PartyCommands;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Ported from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, bridge/), changed to use
 * Sky2M's own dungeon, party and settings code.
 *
 * Cross-Mod Bridge: Sky2M speaks Devonian's, NoammAddons' and Odin's own party sockets, so party data
 * flows between our users and theirs in both directions. killer560 (2026-09-21): "I will never have access to
 * joy's bridge so I need you to build it", "Also make it work for Odin", "I really want everything to tie into
 * my mod."
 * <p>
 * The protocols are documented, source-cited, in the staging research ({@code BRIDGE-PROTOCOLS.md}); every
 * identifier translation lives in {@link BridgeTables}; the wire formats are {@link DevonianCodec},
 * {@link NoammCodec} and {@link OdinCodec}; the connection rules shared by all three are in
 * {@link SocketAdapter}. This class is the client-thread glue:
 * <ul>
 * <li><b>Out:</b> every 5 ticks it reads a {@link BridgeContext} - including
 * {@link PartyDataFeature#selfFactSnapshot()}, the same SELF facts our own relay publishes, so nothing is
 * re-derived - and hands it to each adapter, which translates and queues what its mod understands.</li>
 * <li><b>In:</b> adapters decode and validate on their own threads, then call back here on the client thread,
 * where facts go into {@link PartyInteropState} as {@link InteropSource#SOCKET} (below our own relay and the
 * local-mod bridge, above party chat) or into {@link PartyRoomIntel}/{@link MelodyIntel}.</li>
 * </ul>
 * <b>Hard rules</b> (all enforced in the adapters' {@code decide}): never connect to a mod's socket when that
 * mod is installed here - it already holds the connection and a duplicate might kick it; Hypixel only; only while
 * the grouping applies; only while the per-mod toggle is on; never any identity but the signed-in account's own.
 */
public final class BridgeFeature {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Sky2M Bridge");
    private static final int TICK_DIVISOR = 5;
    private static final int SERVER_CODE_DIVISOR = 20;

    private static final DevonianAdapter DEVONIAN = new DevonianAdapter();
    private static final NoammAdapter NOAMM = new NoammAdapter();
    private static final OdinAdapter ODIN = new OdinAdapter();
    private static final List<SocketAdapter> ADAPTERS = List.of(DEVONIAN, NOAMM, ODIN);

    private static int tickCounter;
    private static int serverCodeCounter;
    private static String serverCode;
    private static Object serverCodeLevel;
    private static boolean wasInDungeon;
    private static int runEpoch;
    private static volatile boolean p3Active;

    private BridgeFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BridgeFeature::tick);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        LOGGER.info("[Bridge] Registered");
    }

    /** For the settings tab. */
    public static List<SocketAdapter> adapters() {
        return ADAPTERS;
    }

    /** Each connection's status in chat (the settings' Show Status button and /s2 bridge). */
    public static void printStatus() {
        Minecraft.getInstance().execute(() -> {
            com.epic60869.sky2m.features.core.Sky2MAlerts.chat(net.minecraft.network.chat.Component.literal("Cross-Mod Bridge"
                + (BridgeConfig.getInstance().isEnabled() ? "" : " (off)") + ":").withStyle(ChatFormatting.GOLD));
            for (SocketAdapter adapter : ADAPTERS) {
                String detail = adapter.statusDetail();
                com.epic60869.sky2m.features.core.Sky2MAlerts.chat(net.minecraft.network.chat.Component.literal(" " + adapter.displayName() + ": ")
                    .withStyle(ChatFormatting.GRAY).append(net.minecraft.network.chat.Component.literal(adapter.status().label
                        + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")")).withStyle(s -> s.withColor(adapter.status().colour))));
            }
        });
    }

    /** /s2 bridge. */
    public static void registerCommand() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : com.epic60869.sky2m.custom.util.Compat.COMMAND_ROOTS) {
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(root)
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("bridge").executes(c -> {
                        printStatus();
                        return 1;
                    })));
            }
        });
    }

    /** A toggle changed: let a "gave up for this session" adapter try again on demand. */
    public static void onSettingsChanged() {
        for (SocketAdapter adapter : ADAPTERS) {
            adapter.resetSession();
        }
    }

    // ------------------------------------------------------------------ client tick

    private static void tick(Minecraft client) {
        boolean inDungeon = Sky2MLocation.inDungeon();
        if (inDungeon != wasInDungeon) {
            wasInDungeon = inDungeon;
            if (inDungeon) {
                runEpoch++;
            }
            p3Active = false;
            MelodyIntel.clear();
            PartyRoomIntel.reset();
        }
        if (++tickCounter < TICK_DIVISOR) {
            return;
        }
        tickCounter = 0;
        BridgeContext ctx;
        try {
            ctx = context(client, inDungeon);
        } catch (Throwable t) {
            LOGGER.debug("[Bridge] context failed: {}", t.toString());
            return;
        }
        for (SocketAdapter adapter : ADAPTERS) {
            adapter.clientTick(ctx);
        }
    }

    private static void onChat(String plain) {
        if (plain == null || plain.isEmpty()) {
            return;
        }
        if (OdinCodec.P3_START.matcher(plain).matches()) {
            p3Active = true;
        } else if (OdinCodec.CORE_OPEN.matcher(plain).matches()) {
            // spec 3.3: Odin shuts its socket and clears every player's melody here
            p3Active = false;
            MelodyIntel.clear();
        }
    }

    private static BridgeContext context(Minecraft client, boolean inDungeon) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        boolean anyOn = cfg.isEnabled() && (cfg.isDevonian() || cfg.isNoamm() || cfg.isOdin());
        if (!anyOn || client.player == null || client.level == null) {
            return new BridgeContext(false, false, false, null, null, null, List.of(), null, null, -1, -1,
                    List.of(), List.of(), false, runEpoch);
        }
        boolean active = true;
        boolean onHypixel = onHypixel(client);

        String selfName = null;
        UUID selfUuid = null;
        if (client.getUser() != null && client.getUser().getName() != null
                && client.getUser().getName().equals(client.player.getGameProfile().name())) {
            selfName = client.getUser().getName();
            selfUuid = client.getUser().getProfileId();
        }
        List<String> teammates = teammates();

        if (++serverCodeCounter >= SERVER_CODE_DIVISOR / TICK_DIVISOR || client.level != serverCodeLevel) {
            serverCodeCounter = 0;
            if (client.level != serverCodeLevel) {
                serverCodeLevel = client.level;
                serverCode = null;
            }
            String code = readServerCode(client);
            if (code != null) {
                serverCode = code;
            }
        }

        Integer partyHash = inDungeon && onHypixel ? devonianPartyHash(client, selfUuid, teammates) : null;

        List<SelfFact> selfFacts = List.of();
        List<BridgeContext.RoomCell> cells = List.of();
        int entranceCol = -1;
        int entranceRow = -1;
        if (inDungeon && active && onHypixel && (DEVONIAN.isReady() || NOAMM.isReady())) {
            selfFacts = SelfFact.snapshot();
            List<BridgeContext.RoomCell> out = new ArrayList<>();
            for (Room room : DungeonManager.getRoomsStream().filter(Room::isMatched).toList()) {
                String name = room.getName();
                if (name == null || name.isBlank()) continue;
                java.util.Set<String> seen = new HashSet<>();
                for (org.joml.Vector2ic corner : room.getSegments()) {
                    // Rooms are 32 blocks from -200; NoammAddons' 11x11 grid has a cell every 16 (rooms on even cells).
                    int col = (corner.x() + 200) / 16, row = (corner.y() + 200) / 16;
                    addCell(out, seen, name, col, row);
                    // The connector cells between this room's tiles (NoammAddons' "separators").
                    if (room.getSegments().stream().anyMatch(o -> o.x() == corner.x() + 32 && o.y() == corner.y())) addCell(out, seen, name, col + 1, row);
                    if (room.getSegments().stream().anyMatch(o -> o.x() == corner.x() && o.y() == corner.y() + 32)) addCell(out, seen, name, col, row + 1);
                }
                if (entranceCol < 0 && room.getType() == Room.Type.ENTRANCE && !room.getSegments().isEmpty()) {
                    org.joml.Vector2ic e = room.getSegments().iterator().next();
                    entranceCol = (e.x() + 200) / 16;
                    entranceRow = (e.y() + 200) / 16;
                }
            }
            cells = out;
        }
        return new BridgeContext(active, onHypixel, inDungeon, floor(), selfName, selfUuid,
                teammates, partyHash, serverCode, entranceCol, entranceRow, selfFacts, cells, p3Active, runEpoch);
    }

    private static void addCell(List<BridgeContext.RoomCell> out, java.util.Set<String> seen, String name, int col, int row) {
        if (col < 0 || col > 10 || row < 0 || row > 10 || !seen.add(col + "," + row)) return;
        out.add(new BridgeContext.RoomCell(name, -185 + col * 16, -185 + row * 16, col, row, col % 2 == 1 || row % 2 == 1));
    }

    private static String floor() {
        String floor = Sky2MLocation.dungeonFloor();
        return floor.isEmpty() ? null : floor;
    }

    /** Your party, not you: the dungeon tab list, else Party Commands' list. */
    static List<String> teammates() {
        Minecraft mc = Minecraft.getInstance();
        String self = mc.player == null ? "" : mc.player.getGameProfile().name();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        DungeonPlayerManager.DungeonPlayer[] players = Sky2MLocation.inDungeon() ? DungeonPlayerManager.getPlayers() : null;
        if (players != null) for (DungeonPlayerManager.DungeonPlayer p : players) if (p != null && !p.name().equalsIgnoreCase(self)) names.add(p.name());
        if (names.isEmpty()) for (String n : PartyCommands.members()) if (!n.equalsIgnoreCase(self)) names.add(n);
        return List.copyOf(names);
    }

    /** hypixel.net only. p3sim has none of the identifiers these sockets group by (no Hypixel party for
     *  Devonian's Mod-API hash, no Hypixel server code for NoammAddons/Odin), so it is deliberately excluded. */
    private static boolean onHypixel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    /**
     * Devonian's party hash (spec 1.3), or null unless it can be computed EXACTLY: our UUID plus every party
     * mate's, each resolved from the player list Hypixel sends this client. Devonian itself reads the member set
     * from Hypixel's Mod API party packet, which this mod does not depend on; inside a dungeon the whole party is
     * in this instance, so their real UUIDs are known. Anything unresolvable -> null -> do not connect (a wrong
     * set would just be a room nobody else is in). Hypixel's fake tab-list entries use version-2 UUIDs; a real
     * account is always version 4, so only those count.
     */
    static Integer devonianPartyHash(Minecraft client, UUID self, List<String> teammates) {
        ClientPacketListener connection = client.getConnection();
        if (self == null || connection == null || teammates.isEmpty() || teammates.size() > 4) {
            return null;
        }
        List<UUID> members = new ArrayList<>(teammates.size() + 1);
        members.add(self);
        for (String name : teammates) {
            PlayerInfo info = connection.getPlayerInfo(name);
            if (info == null || info.getProfile() == null) {
                return null;
            }
            UUID id = info.getProfile().id();
            if (id == null || id.version() != 4 || members.contains(id)) {
                return null;
            }
            members.add(id);
        }
        return DevonianCodec.partyHash(members);
    }

    /** The sidebar date line's server code, exactly the way NoammAddons ({@code LocationUtils.kt:54-56}) and
     *  Odin read it: each scoreboard team's prefix + suffix, formatting stripped, through the shared regex. */
    private static String readServerCode(Minecraft client) {
        try {
            for (PlayerTeam team : client.level.getScoreboard().getPlayerTeams()) {
                String text = ChatFormatting.stripFormatting(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
                String code = OdinCodec.serverCode(text);
                if (code != null) {
                    return code;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ inbound (client thread)

    static void applyOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                LOGGER.debug("[Bridge] inbound failed: {}", t.toString());
            }
        });
    }

    /** Inbound facts are per-run facts: only while this client is itself in a real run with Interop on. */
    private static boolean acceptInbound() {
        Sky2MConfig c = Sky2MConfig.current();
        return BridgeConfig.getInstance().isEnabled() && c != null && c.dungeons.partyData.receive && Sky2MLocation.inDungeon();
    }

    static void applyDevonian(DevonianCodec.Message msg) {
        if (!acceptInbound()) {
            return;
        }
        if (msg instanceof DevonianCodec.RoomSecrets rs) {
            String name = resolveDevonianRoom(rs.devonianRoomId());
            BridgeTables.Room known = BridgeTables.room(name);
            if (known == null || rs.total() != known.secrets()) {
                return; // unmapped id, ambiguous Blaze, or a total that disagrees with the room database
            }
            PartyDungeonState.offerRoomSecrets(name, rs.found(), rs.total(), Source.SOCKET, "Devonian");
        } else if (msg instanceof DevonianCodec.SecretTracker st) {
            String teammate = teammate(st.ign());
            if (teammate != null) {
                PartyRoomIntel.offerPlayerSecrets(teammate, st.secrets());
            }
        }
    }

    /** Devonian id -> our room name; for the one id that covers two of ours (Blaze), whichever of the two our
     *  own map scan found this run - or null if neither/both (never a guess). */
    private static String resolveDevonianRoom(int devonianId) {
        List<BridgeTables.Room> rooms = BridgeTables.roomsForDevonianId(devonianId);
        if (rooms.size() == 1) {
            return rooms.get(0).name();
        }
        if (rooms.isEmpty()) {
            return null;
        }
        Set<String> onMap = new HashSet<>();
        DungeonManager.getRoomsStream().filter(Room::isMatched).forEach(r -> onMap.add(r.getName()));
        String match = null;
        for (BridgeTables.Room room : rooms) {
            if (onMap.contains(room.name())) {
                if (match != null) {
                    return null;
                }
                match = room.name();
            }
        }
        return match;
    }

    static void applyNoamm(NoammCodec.Message msg) {
        if (!acceptInbound()) {
            return;
        }
        String who = "NoammAddons";
        if (msg instanceof NoammCodec.Door d) {
            PartyRoomIntel.offerDoor(d.x(), d.z(), d.col(), d.row(), d.ourType(), who);
        } else if (msg instanceof NoammCodec.Room r) {
            PartyRoomIntel.offerRoom(r.name(), r.x(), r.z(), r.col(), r.row(), who);
        } else if (msg instanceof NoammCodec.Flag f) {
            PartyDungeonState.Flag flag = switch (f.which()) {
                case "mimic" -> PartyDungeonState.Flag.MIMIC_KILLED;
                case "prince" -> PartyDungeonState.Flag.PRINCE_KILLED;
                case "bat" -> PartyDungeonState.Flag.BAT_KILLED;
                default -> null;
            };
            if (flag != null) {
                PartyDungeonState.offerFlag(flag, Source.SOCKET, who);
            }
        } else if (msg instanceof NoammCodec.Dragon dr) {
            // M7 P5 only; DEATH has no slot in PartyInteropState, so only spawns are recorded.
            if (dr.spawn() && "M7".equals(floor())) {
                PartyDungeonState.offerDragonSpawn(dr.ourColour(), Source.SOCKET, who);
            }
        } else if (msg instanceof NoammCodec.RoomSecrets rs) {
            BridgeTables.Room known = BridgeTables.room(rs.room());
            if (known != null) {
                PartyDungeonState.offerRoomSecrets(rs.room(), rs.secrets(), known.secrets(), Source.SOCKET, who);
            }
        }
    }

    static void applyOdin(OdinCodec.Update update) {
        if (!acceptInbound() || !("F7".equals(floor()) || "M7".equals(floor()))) {
            return;
        }
        // Odin has no authentication (spec 3.2): only names our own party list vouches for, never ourselves.
        String teammate = teammate(update.ign());
        if (teammate != null) {
            MelodyIntel.offer(teammate, update.type(), update.slot());
        }
    }

    /** @return the party list's spelling of this name if it is a current teammate (not us), else null. */
    private static String teammate(String ign) {
        if (ign == null) {
            return null;
        }
        for (String name : teammates()) {
            if (name.equalsIgnoreCase(ign)) {
                return name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ JSON helpers for SelfFact payloads

    /** Case-preserving (unlike {@code PartyDataProtocol}'s reader, which lower-cases). */
    static String str(JsonObject o, String field) {
        JsonElement el = o == null ? null : o.get(field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    static Integer integer(JsonObject o, String field) {
        JsonElement el = o == null ? null : o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = el.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) <= 1_000_000 ? (int) d : null;
        } catch (Exception e) {
            return null;
        }
    }
}
