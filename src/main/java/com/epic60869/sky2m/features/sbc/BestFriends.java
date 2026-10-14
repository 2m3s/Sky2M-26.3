package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.PartyCommands;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best Friends, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, social/BestFriends*): how
 * long you've been in a party with each player (any party, not just dungeons, kept forever) and how many dungeon runs
 * you finished together on each floor. Players are kept by UUID, so a name change keeps their time. The party comes
 * from Party Commands' list (party messages and /p list) and, in a dungeon, the tab list. Time is saved every 30
 * seconds and when you leave, so a crash loses at most that. Shown in /s2 friends' Party Time tab.
 */
public final class BestFriends {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("best-friends.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final long SAVE_MS = 30_000;

    /** One player: what they were last called, the time partied, when, and runs together by floor. */
    public static final class Record {
        public String uuid, name;
        public long partyMs, lastPartied;
        public Map<String, Integer> runs = new LinkedHashMap<>();

        public int totalRuns() {
            int n = 0;
            for (int r : runs.values()) n += r;
            return n;
        }
    }

    private static Map<String, Record> records;
    /** Partied now: uuid -> when the running stretch started. */
    private static final Map<String, Long> RUNNING = new HashMap<>();
    /** Names whose UUID is known, and names being looked up. */
    private static final Map<String, String> UUIDS = new ConcurrentHashMap<>();
    private static final Set<String> LOOKING_UP = ConcurrentHashMap.newKeySet();
    private static long savedAt;
    private static int ticks;
    private static String dungeonFloor;
    private static boolean wasInDungeon;

    private BestFriends() {}

    static boolean enabled() {
        return Sbc.config() != null && Sbc.config().bestFriends.enabled;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(BestFriends::stopAll));
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> stopAll());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("bestfriends").executes(c -> {
                    Compat.queueOpenScreen(SbcFriendsScreen.partyTime());
                    return 1;
                })));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ tracking

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (++ticks % 5 != 0) return;
        if (mc.player == null || !enabled()) {
            if (!RUNNING.isEmpty()) stopAll();
            return;
        }
        long now = System.currentTimeMillis();
        Set<String> party = new LinkedHashSet<>();
        for (String name : teammates()) {
            String uuid = uuid(name);
            if (uuid == null) continue;
            party.add(uuid);
            Record r = record(uuid);
            r.name = name;
            RUNNING.putIfAbsent(uuid, now);
        }
        for (String uuid : new ArrayList<>(RUNNING.keySet())) {
            if (!party.contains(uuid)) {
                add(uuid, now);
                RUNNING.remove(uuid);
            }
        }
        // A finished dungeon run counts for everyone partied when you leave it.
        boolean inDungeon = Sky2MLocation.inDungeon();
        if (inDungeon && !Sky2MLocation.dungeonFloor().isEmpty()) dungeonFloor = Sky2MLocation.dungeonFloor();
        if (wasInDungeon && !inDungeon && dungeonFloor != null) {
            for (String uuid : RUNNING.keySet()) record(uuid).runs.merge(dungeonFloor, 1, Integer::sum);
            dungeonFloor = null;
            save();
        }
        wasInDungeon = inDungeon;
        if (now - savedAt >= SAVE_MS) {
            for (String uuid : RUNNING.keySet()) add(uuid, now);
            save();
        }
    }

    /** Your party now: Party Commands' members, and in a dungeon everyone in the tab list. */
    private static Set<String> teammates() {
        Set<String> names = new LinkedHashSet<>(PartyCommands.members());
        if (Sky2MLocation.inDungeon()) {
            DungeonPlayerManager.DungeonPlayer[] players = DungeonPlayerManager.getPlayers();
            if (players != null) for (DungeonPlayerManager.DungeonPlayer p : players) if (p != null) names.add(p.name());
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) names.removeIf(n -> n.equalsIgnoreCase(mc.player.getGameProfile().name()));
        return names;
    }

    /** Adds the running stretch so far to the player's time and starts a new stretch now. */
    private static void add(String uuid, long now) {
        Long start = RUNNING.get(uuid);
        if (start == null || now <= start) return;
        Record r = record(uuid);
        r.partyMs += now - start;
        r.lastPartied = now;
        RUNNING.put(uuid, now);
    }

    private static void stopAll() {
        long now = System.currentTimeMillis();
        for (String uuid : RUNNING.keySet()) add(uuid, now);
        RUNNING.clear();
        save();
    }

    /** The UUID for a name: from the tab list, else a Mojang lookup (answered on a later tick). */
    private static String uuid(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String known = UUIDS.get(key);
        if (known != null) return known;
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(name);
        if (info != null && info.getProfile().id() != null && info.getProfile().id().version() == 4) {
            String id = info.getProfile().id().toString();
            UUIDS.put(key, id);
            return id;
        }
        if (LOOKING_UP.add(key)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name))
                .timeout(Duration.ofSeconds(10)).header("User-Agent", "Sky2M").GET().build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(r -> {
                if (r.statusCode() != 200) return;
                JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
                if (!o.has("id")) return;
                String plain = o.get("id").getAsString();
                UUIDS.put(key, plain.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
            }).whenComplete((v, e) -> LOOKING_UP.remove(key));
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static synchronized Map<String, Record> records() {
        if (records == null) {
            try {
                records = Files.exists(FILE) ? GSON.fromJson(Files.readString(FILE), new TypeToken<LinkedHashMap<String, Record>>() {}.getType()) : null;
            } catch (Exception e) {
                System.err.println("[Sky2M] Best Friends couldn't read its file: " + e.getMessage());
            }
            if (records == null) records = new LinkedHashMap<>();
        }
        return records;
    }

    private static Record record(String uuid) {
        return records().computeIfAbsent(uuid, k -> {
            Record r = new Record();
            r.uuid = k;
            return r;
        });
    }

    private static synchronized void save() {
        savedAt = System.currentTimeMillis();
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(records()));
        } catch (Exception e) {
            System.err.println("[Sky2M] Best Friends couldn't save: " + e.getMessage());
        }
    }

    /** Everyone, most time first, counting the stretch running now. */
    public static List<Record> sorted() {
        List<Record> out = new ArrayList<>(records().values());
        out.sort((a, b) -> Long.compare(liveMs(b), liveMs(a)));
        return out;
    }

    public static long liveMs(Record r) {
        Long start = RUNNING.get(r.uuid);
        return r.partyMs + (start == null ? 0 : Math.max(0, System.currentTimeMillis() - start));
    }

    public static boolean partiedNow(Record r) {
        return RUNNING.containsKey(r.uuid);
    }

    static synchronized void forget(Record r) {
        RUNNING.remove(r.uuid);
        records().remove(r.uuid);
        save();
    }

    /** "3d 4h", "2h 15m", "12m 30s". */
    public static String duration(long ms) {
        long s = ms / 1000, d = s / 86400, h = s / 3600 % 24, m = s / 60 % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m " + s % 60 + "s";
    }
}
