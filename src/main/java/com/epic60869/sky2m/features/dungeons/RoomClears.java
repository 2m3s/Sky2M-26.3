package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.dungeons.mapstyle.MapView;
import com.epic60869.sky2m.sb.events.DungeonEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sim.livemap.MapViewSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** How long the room you're in took to get all its secrets. */
public final class RoomClears {
    /** When the first secret was found in each room while you were in it, so running through on a blood rush doesn't count. */
    private static final Map<Room, Long> FIRST_SECRET = new WeakHashMap<>();
    /** One secret has no time between first and last. */
    private static final int MIN_SECRETS_TO_TIME = 2;

    private RoomClears() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            FIRST_SECRET.clear();
        });
        RoomBests.init();
        DungeonEvents.SECRET_FOUND.register((room, secret) -> onSecret(room, secret.secretIndex));
        // Hypixel's action bar count, for secrets the waypoints didn't see. Green rooms report theirs "from the ws" and are done.
        DungeonEvents.SECRET_COUNT_UPDATED.register((room, fromWS) -> {
            if (!fromWS && room.getFoundSecretCount() > 0) onSecret(room, -1);
        });
    }

    private static void onSecret(Room room, int secretIndex) {
        if (room != DungeonManager.getCurrentRoom()) return;
        long now = System.currentTimeMillis();
        Long started = FIRST_SECRET.putIfAbsent(room, now);
        RoomBests.onSecret(room, started == null ? now : started, secretIndex);
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

    /**
     * {@code room} went green after the map had shown it unfinished: says how long it took, if you're in it, and {@link RoomBests}
     * gets the same time, so one room is one line. With Room Secret Time off, the line only comes for a beaten best.
     */
    public static void onGreen(Room room, Room currentRoom) {
        Long firstSecret = FIRST_SECRET.remove(room);
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || firstSecret == null || room != currentRoom) return;
        if (room.getType() == Room.Type.FAIRY || room.getType() == Room.Type.ENTRANCE) return;
        int secrets = room.getMaxSecretCount();
        Minecraft mc = Minecraft.getInstance();
        if (secrets < MIN_SECRETS_TO_TIME || mc.player == null) return;
        long ms = System.currentTimeMillis() - firstSecret;
        RoomBests.Outcome best = RoomBests.onCleared(room, secrets, ms);
        boolean showTime = config.dungeons.secrets.roomSecretTime;
        if (!showTime && (best == null || !best.newBest() || best.previous() == null)) return;
        var line = Compat.PREFIX.get().append(Component.literal(displayName(room) + ": ").withStyle(ChatFormatting.GOLD));
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
    private static String displayName(Room room) {
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
