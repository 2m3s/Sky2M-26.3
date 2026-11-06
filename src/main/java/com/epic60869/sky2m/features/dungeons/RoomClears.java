package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.dungeons.mapstyle.MapView;
import com.epic60869.sky2m.sb.events.DungeonEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sim.livemap.MapViewSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Two things read off the dungeon's rooms: roughly how many rooms each teammate cleared, for {@link TeamSecrets}, and how
 * long the room you're in took to get all its secrets.
 *
 * <p>Rooms done works like NoammAddons' ClearInfoUpdater: when the map shows a checkmark on a room, whoever's head is in
 * that room gets it, as solo if they're alone and stacked if not, so a player's count is the range "solo to solo+stacked".
 * It's rough: the map doesn't say who did the work, only who was standing there.
 */
public final class RoomClears {
    private static final int CHECK_EVERY_TICKS = 5;

    /** The rooms a player got: alone, and with someone else in the room. */
    public record Counts(List<String> solo, List<String> stacked) {
        Counts() {
            this(new ArrayList<>(), new ArrayList<>());
        }
    }

    /** Tiles of rooms already handed out, as {col * 100 + row}. */
    private static final Set<Integer> COUNTED = new HashSet<>();
    private static final Map<String, Counts> BY_PLAYER = new HashMap<>();
    /** When the first secret was found in each room while you were in it, so running through on a blood rush doesn't count. */
    private static final Map<Room, Long> FIRST_SECRET = new WeakHashMap<>();
    /** What the map called each of those rooms while you were in it, as it only says the room you're in now. */
    private static final Map<Room, String> FIRST_SECRET_NAME = new WeakHashMap<>();
    /** One secret has no time between first and last. */
    private static final int MIN_SECRETS_TO_TIME = 2;
    private static int ticks;

    private RoomClears() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            COUNTED.clear();
            BY_PLAYER.clear();
            FIRST_SECRET.clear();
            FIRST_SECRET_NAME.clear();
        });
        RoomBests.init();
        DungeonEvents.SECRET_FOUND.register((room, secret) -> onSecret(room, secret.secretIndex));
        // Hypixel's action bar count, for secrets the waypoints didn't see. Green rooms report theirs "from the ws" and are done.
        DungeonEvents.SECRET_COUNT_UPDATED.register((room, fromWS) -> {
            if (!fromWS && room.getFoundSecretCount() > 0) onSecret(room, -1);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null) return;
            if (config.dungeons.teamSecrets.roomsDone && ++ticks % CHECK_EVERY_TICKS == 0) countRooms(MapViewSource.current());
        });
    }

    private static void onSecret(Room room, int secretIndex) {
        if (room != DungeonManager.getCurrentRoom()) return;
        long now = System.currentTimeMillis();
        Long started = FIRST_SECRET.putIfAbsent(room, now);
        if (started == null) FIRST_SECRET_NAME.put(room, displayName(room));
        RoomBests.onSecret(room, started == null ? now : started, secretIndex);
    }

    /** The room's name if a secret was found while you were in it and it hasn't gone green since, else null. */
    static String nameIfSecretFound(Room room) {
        return FIRST_SECRET.containsKey(room) ? FIRST_SECRET_NAME.get(room) : null;
    }

    private static void countRooms(MapView view) {
        if (!view.valid() || view.inBoss()) return;
        for (MapView.Room room : view.rooms()) {
            if (room.state() != MapView.RoomState.CLEARED && room.state() != MapView.RoomState.GREEN) continue;
            if (room.type() == MapView.RoomType.ENTRANCE || room.type() == MapView.RoomType.FAIRY) continue;
            boolean counted = false;
            for (int[] tile : room.tiles()) counted |= !COUNTED.add(tile[0] * 100 + tile[1]);
            if (counted) continue;
            List<String> players = new ArrayList<>();
            for (MapView.Head head : view.heads()) if (inRoom(view, room, head.x(), head.y())) players.add(head.name());
            String name = room.name() == null ? "Unknown room" : room.name();
            for (String player : players) {
                Counts counts = BY_PLAYER.computeIfAbsent(player.toLowerCase(Locale.ROOT), p -> new Counts());
                (players.size() == 1 ? counts.solo() : counts.stacked()).add(name);
            }
        }
    }

    /** Whether a map position is in a room. A door between two slots counts as the slot before it. Public for the game test. */
    public static boolean inRoom(MapView view, MapView.Room room, float x, float y) {
        return roomAt(room, slot(x, view.startX(), view), slot(y, view.startY(), view));
    }

    private static int slot(float pixel, int start, MapView view) {
        return (int) Math.floor((pixel - start) / (view.roomSize() + view.gap()));
    }

    private static boolean roomAt(MapView.Room room, int col, int row) {
        for (int[] tile : room.tiles()) if (tile[0] == col && tile[1] == row) return true;
        return false;
    }

    /** A copy of each player's rooms so far (by lower-case name), kept by a look that outlives the run. */
    public static Map<String, Counts> snapshot() {
        Map<String, Counts> copy = new HashMap<>();
        BY_PLAYER.forEach((name, counts) -> copy.put(name, new Counts(List.copyOf(counts.solo()), List.copyOf(counts.stacked()))));
        return copy;
    }

    /** "3" when every room was solo, else "3-5": from the solo rooms to the solo and stacked rooms. Public for the game test. */
    public static String rangeText(int solo, int stacked) {
        return stacked == 0 ? String.valueOf(solo) : solo + "-" + (solo + stacked);
    }

    /**
     * {@code room} went green after the map had shown it unfinished: says how long it took, if you're in it or every secret
     * came up while you were, and {@link RoomBests} gets the same time, so one room is one line. The time is up to the last
     * secret, as the map takes a second or two to go green; if a secret was missed it's up to the green. With Room Secret Time
     * off, the line only comes for a beaten best.
     */
    public static void onGreen(Room room, Room currentRoom) {
        Long firstSecret = FIRST_SECRET.remove(room);
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || firstSecret == null) return;
        if (room.getType() == Room.Type.FAIRY || room.getType() == Room.Type.ENTRANCE) return;
        int secrets = room.getMaxSecretCount();
        Minecraft mc = Minecraft.getInstance();
        if (secrets < MIN_SECRETS_TO_TIME || mc.player == null) return;
        boolean inRoom = room == currentRoom;
        Long lastSecret = RoomBests.lastSecretAt(room, secrets);
        if (!inRoom && lastSecret == null) return;
        long ms = lastSecret != null ? lastSecret : System.currentTimeMillis() - firstSecret;
        RoomBests.Outcome best = RoomBests.onCleared(room, secrets, ms, inRoom);
        boolean showTime = config.dungeons.secrets.roomSecretTime;
        if (!showTime && (best == null || !best.newBest() || best.previous() == null)) return;
        String name = inRoom || room.getName() == null ? displayName(room) : room.getName();
        var line = Compat.PREFIX.get().append(Component.literal(name + ": ").withStyle(ChatFormatting.GOLD));
        if (showTime) {
            line.append(Component.literal(secrets + "/" + secrets + " secrets").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY));
        }
        var hover = best == null ? null : RoomBests.splitsHover(best.splits());
        line.append(Component.literal(timeText(ms)).withStyle(style -> style.withColor(ChatFormatting.GREEN).withHoverEvent(hover)))
            .append(RoomBests.suffix(best));
        mc.gui.hud.getChat().addClientSystemMessage(line);
    }

    /** The map's name for the room you're standing in ("Melon"), else the room data's ("melon-3"). */
    static String displayName(Room room) {
        MapView view = MapViewSource.current();
        for (MapView.Head head : view.heads()) {
            if (!head.self()) continue;
            for (MapView.Room mapRoom : view.rooms()) {
                if (mapRoom.name() != null && inRoom(view, mapRoom, head.x(), head.y())) return mapRoom.name();
            }
        }
        return room.getName() == null ? "Room" : room.getName();
    }

    /** "42.3s", or "1m 03s" from a minute. Public for the game test. */
    public static String timeText(long ms) {
        long seconds = ms / 1000;
        if (seconds >= 60) return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}
