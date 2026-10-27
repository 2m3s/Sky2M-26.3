package com.epic60869.sky2m.features.dungeons.party;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.social.Sky2MLogin;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.ScoreCalculator;
import com.epic60869.sky2m.features.dungeons.WitherDragons;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Counter;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Flag;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Source;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Dungeon Data, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, partydata/ and
 * interop/), sent over the Sky2M server instead of their relay: teammates running Sky2M share what each of them has
 * worked out about the run (the Mimic and Prince dead, blood opened, secrets, crypts and deaths, P3 terminal counts,
 * each room's secrets, M7 dragon spawns and Melody progress), and every client merges what the party knows into
 * {@link PartyDungeonState}. The score calculator counts a Mimic or Prince a teammate killed out of your sight.
 * <p>
 * Only facts this client already knows are sent, only when they change, and at most a few packets a second. The
 * server groups everyone in the same Hypixel dungeon instance (its server code) who's logged in to Sky2M, and only
 * passes on packets from players in your party. Nothing about you (where you are, your items, your chat) is sent.
 * The server side is described in party-data-server-prompt.md.
 */
public final class PartyDungeonData {
    private static final Pattern LOBBY = Pattern.compile("^\\d{2}/\\d{2}/\\d{2,4} (\\w+)");
    private static final Pattern DEVICE = Pattern.compile("^(\\w{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final int MAX_FACTS = 10;

    private static String run;
    private static boolean joined;
    private static int ticks;
    private static final Map<String, String> SENT = new HashMap<>();
    private static final Deque<JsonObject> QUEUE = new ArrayDeque<>();
    private static double tokens = 8;
    private static long refilledAt = System.currentTimeMillis();
    /** Teammates whose Sky2M has sent something this run. */
    private static final Set<String> SHARING = new LinkedHashSet<>();

    private PartyDungeonData() {}

    static FeatureConfigs.PartyData config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.partyData;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("party_dungeon_data", "Party Dungeon Data", () -> config() != null && config().hud, PartyDungeonData::lines,
            List.of(Component.literal("Party Dungeon Data").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("§7Mimic: §a✔ §8(Steve)"), Component.literal("§7Secrets: §f34 §7Crypts: §f5"), Component.literal("§7Sharing: §a2 teammates")), 10, 280);
    }

    // ------------------------------------------------------------------------------------------------ own facts

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        if (text.equals("The BLOOD DOOR has been opened!")) PartyDungeonState.offerFlag(Flag.BLOOD_OPENED, Source.SELF, null);
        else if (text.startsWith("[BOSS] The Watcher: You have proven yourself.")) PartyDungeonState.offerFlag(Flag.BLOOD_DONE, Source.SELF, null);
        Matcher m = DEVICE.matcher(text);
        if (m.matches()) {
            Counter c = switch (m.group(3)) {
                case "terminal" -> Counter.TERMINALS_DONE;
                case "device" -> Counter.DEVICES_DONE;
                default -> Counter.LEVERS_DONE;
            };
            PartyDungeonState.offerCounter(c, Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Source.SELF, null);
        }
    }

    private static void tick() {
        boolean inDungeon = Sky2MLocation.inDungeon();
        if (!inDungeon) {
            if (run != null) leave();
            return;
        }
        if (++ticks % 10 != 0) {
            drain();
            return;
        }
        // What this client knows itself.
        if (ScoreCalculator.started()) {
            if (ScoreCalculator.mimicKilled()) PartyDungeonState.offerFlag(Flag.MIMIC_KILLED, Source.SELF, null);
            if (ScoreCalculator.princeKilled()) PartyDungeonState.offerFlag(Flag.PRINCE_KILLED, Source.SELF, null);
            PartyDungeonState.offerCounter(Counter.SECRETS_FOUND, ScoreCalculator.foundSecrets(), -1, Source.SELF, null);
            PartyDungeonState.offerCounter(Counter.CRYPTS, ScoreCalculator.crypts(), -1, Source.SELF, null);
            PartyDungeonState.offerCounter(Counter.DEATHS, ScoreCalculator.deaths(), -1, Source.SELF, null);
        }
        DungeonManager.getRoomsStream().filter(Room::isMatched).forEach(room -> {
            int found = room.getFoundSecretCount(), max = room.getMaxSecretCount();
            if (room.getName() != null && found >= 0 && max > 0) PartyDungeonState.offerRoomSecrets(room.getName(), found, max, Source.SELF, null);
        });
        for (String dragon : WitherDragons.spawningTicks().keySet()) PartyDungeonState.offerDragonSpawn(dragon, Source.SELF, null);
        share();
        drain();
    }

    // ------------------------------------------------------------------------------------------------ sharing

    /** This instance's Hypixel server code (m12AB), from the sidebar, or null. */
    private static String serverCode() {
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = LOBBY.matcher(line);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static boolean canShare() {
        FeatureConfigs.PartyData c = config();
        return c != null && c.share && SbcNet.online() && Sky2MLogin.loggedIn();
    }

    private static void share() {
        if (!canShare()) return;
        String code = serverCode();
        if (code == null) return;
        if (!code.equals(run) || !joined) {
            run = code;
            joined = true;
            SENT.clear();
            SHARING.clear();
            JsonObject join = Sbc.packet("partyDataJoin");
            join.addProperty("run", code);
            JsonArray members = new JsonArray();
            DungeonPlayerManager.DungeonPlayer[] players = DungeonPlayerManager.getPlayers();
            if (players != null) for (DungeonPlayerManager.DungeonPlayer p : players) if (p != null) members.add(p.name());
            join.add("members", members);
            QUEUE.add(join);
        }
        List<JsonObject> facts = new ArrayList<>();
        for (Flag flag : Flag.values()) {
            PartyDungeonState.Fact<Boolean> f = PartyDungeonState.flagFact(flag);
            if (f != null && f.source() == Source.SELF) diff(facts, "flag:" + flag.wire, "1", () -> {
                JsonObject o = fact("flag");
                o.addProperty("f", flag.wire);
                return o;
            });
        }
        for (Counter counter : Counter.values()) {
            PartyDungeonState.Fact<Integer> f = PartyDungeonState.counter(counter);
            if (f != null && f.source() == Source.SELF) diff(facts, "counter:" + counter.wire, String.valueOf(f.value()), () -> {
                JsonObject o = fact("counter");
                o.addProperty("c", counter.wire);
                o.addProperty("v", f.value());
                return o;
            });
        }
        for (Map.Entry<String, PartyDungeonState.Fact<PartyDungeonState.RoomSecrets>> e : PartyDungeonState.rooms().entrySet()) {
            PartyDungeonState.Fact<PartyDungeonState.RoomSecrets> f = e.getValue();
            if (f.source() != Source.SELF) continue;
            diff(facts, "room:" + e.getKey(), f.value().found() + "/" + f.value().total(), () -> {
                JsonObject o = fact("room");
                o.addProperty("r", e.getKey());
                o.addProperty("f", f.value().found());
                o.addProperty("t", f.value().total());
                return o;
            });
        }
        for (Map.Entry<String, PartyDungeonState.Fact<Long>> e : PartyDungeonState.dragons().entrySet()) {
            if (e.getValue().source() != Source.SELF) continue;
            diff(facts, "dragon:" + e.getKey(), String.valueOf(e.getValue().at() / 10_000), () -> {
                JsonObject o = fact("dragon");
                o.addProperty("c", e.getKey());
                return o;
            });
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            PartyDungeonState.Fact<PartyDungeonState.Melody> mine = PartyDungeonState.melody().get(mc.player.getGameProfile().name().toLowerCase(Locale.ROOT));
            if (mine != null && mine.source() == Source.SELF) {
                PartyDungeonState.Melody m = mine.value();
                diff(facts, "melody", m.row() + "/" + m.rows() + "/" + m.done(), () -> {
                    JsonObject o = fact("melody");
                    o.addProperty("row", m.row());
                    o.addProperty("rows", m.rows());
                    o.addProperty("done", m.done());
                    return o;
                });
            }
        }
        for (int i = 0; i < facts.size(); i += MAX_FACTS) {
            JsonObject packet = Sbc.packet("partyData");
            packet.addProperty("run", run);
            JsonArray array = new JsonArray();
            for (JsonObject f : facts.subList(i, Math.min(facts.size(), i + MAX_FACTS))) array.add(f);
            packet.add("facts", array);
            if (QUEUE.size() < 32) QUEUE.add(packet);
        }
    }

    private static JsonObject fact(String kind) {
        JsonObject o = new JsonObject();
        o.addProperty("k", kind);
        return o;
    }

    private static void diff(List<JsonObject> out, String id, String value, java.util.function.Supplier<JsonObject> make) {
        if (value.equals(SENT.get(id))) return;
        SENT.put(id, value);
        out.add(make.get());
    }

    /** Sends queued packets, at most 4 a second (8 at once). */
    private static void drain() {
        long now = System.currentTimeMillis();
        tokens = Math.min(8, tokens + (now - refilledAt) / 250.0);
        refilledAt = now;
        while (!QUEUE.isEmpty() && tokens >= 1) {
            if (!SbcNet.sendQuietly(QUEUE.poll())) {
                QUEUE.clear();
                joined = false;
                return;
            }
            tokens--;
        }
    }

    private static void leave() {
        if (joined && SbcNet.online()) SbcNet.sendQuietly(Sbc.packet("partyDataLeave"));
        run = null;
        joined = false;
        SENT.clear();
        QUEUE.clear();
        SHARING.clear();
        PartyDungeonState.reset();
    }

    // ------------------------------------------------------------------------------------------------ receiving

    /** A teammate's facts from the Sky2M server: checked field by field before any is believed. */
    public static void handle(JsonObject packet) {
        FeatureConfigs.PartyData c = config();
        if (c == null || !c.receive || !Sky2MLocation.inDungeon()) return;
        String from = Sbc.str(packet, "name");
        if (from == null || !from.matches("\\w{1,16}") || !inParty(from)) return;
        String packetRun = Sbc.str(packet, "run");
        if (run != null && packetRun != null && !packetRun.equals(run)) return;
        JsonArray facts = Sbc.arr(packet, "facts");
        if (facts == null) return;
        SHARING.add(from);
        int n = 0;
        for (JsonElement e : facts) {
            if (++n > MAX_FACTS || !e.isJsonObject()) break;
            JsonObject f = e.getAsJsonObject();
            String kind = Sbc.str(f, "k");
            if (kind == null) continue;
            switch (kind) {
                case "flag" -> PartyDungeonState.offerFlag(Flag.ofWire(Sbc.str(f, "f")), Source.RELAY, from);
                case "counter" -> PartyDungeonState.offerCounter(Counter.ofWire(Sbc.str(f, "c")), (int) Sbc.lng(f, "v", -1), -1, Source.RELAY, from);
                case "room" -> PartyDungeonState.offerRoomSecrets(Sbc.str(f, "r"), (int) Sbc.lng(f, "f", -1), (int) Sbc.lng(f, "t", -1), Source.RELAY, from);
                case "dragon" -> PartyDungeonState.offerDragonSpawn(Sbc.str(f, "c"), Source.RELAY, from);
                case "melody" -> PartyDungeonState.offerMelody(from, (int) Sbc.lng(f, "row", -1), (int) Sbc.lng(f, "rows", 4), Sbc.bool(f, "done"), Source.RELAY);
                default -> { }
            }
        }
    }

    /** Whether a name is one of your dungeon teammates (the tab list's party). */
    public static boolean inParty(String name) {
        DungeonPlayerManager.DungeonPlayer[] players = DungeonPlayerManager.getPlayers();
        if (players == null) return false;
        for (DungeonPlayerManager.DungeonPlayer p : players) if (p != null && p.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ HUD

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        if (!Sky2MLocation.inDungeon()) return out;
        out.add(Component.literal("Party Dungeon Data").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Flag flag : new Flag[]{Flag.MIMIC_KILLED, Flag.PRINCE_KILLED, Flag.BLOOD_DONE}) {
            PartyDungeonState.Fact<Boolean> f = PartyDungeonState.flagFact(flag);
            MutableComponent line = Component.literal("§7" + flag.label.split(" ")[0] + ": " + (f == null ? "§c✖" : "§a✔"));
            if (f != null && f.source() != Source.SELF && f.reporter() != null) line.append(Component.literal(" §8(" + f.reporter() + ")"));
            out.add(line);
        }
        StringBuilder counts = new StringBuilder();
        for (Counter c : new Counter[]{Counter.SECRETS_FOUND, Counter.CRYPTS, Counter.DEATHS}) {
            PartyDungeonState.Fact<Integer> f = PartyDungeonState.counter(c);
            counts.append("§7").append(c.label).append(": §f").append(f == null ? "-" : f.value()).append(' ');
        }
        out.add(Component.literal(counts.toString().trim()));
        long fromTeammates = PartyDungeonState.rooms().values().stream().filter(f -> f.source() != Source.SELF).count();
        if (fromTeammates > 0) out.add(Component.literal("§7Rooms from teammates: §f" + fromTeammates));
        out.add(Component.literal("§7Sharing: " + (SHARING.isEmpty() ? "§8nobody yet" : "§a" + String.join(", ", SHARING))));
        return out;
    }
}
