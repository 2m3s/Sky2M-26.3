package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dungeons > Secrets > Missed Secrets Nudge: says so when you leave a room you got a secret in and it still has some left.
 * It can't tell when a teammate has taken the room over, so it's off by default. Stepping out and back within a few
 * seconds says nothing, and each room only says it once.
 */
public final class MissedSecrets {
    private static final int CHECK_EVERY_TICKS = 5;
    private static final long GRACE_MS = 3000;

    /** When each room you've just left is due its nudge. */
    private static final Map<Room, Long> LEFT = new WeakHashMap<>();
    private static final Set<Room> NUDGED = Collections.newSetFromMap(new WeakHashMap<>());
    private static Room previous;
    private static int ticks;

    private MissedSecrets() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % CHECK_EVERY_TICKS != 0) return;
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.dungeons.secrets.missedSecretsNudge || !Sky2MLocation.inDungeon() || DungeonManager.isInBoss()) {
                previous = null;
                LEFT.clear();
                return;
            }
            Room now = DungeonManager.getCurrentRoom();
            if (now != previous) {
                if (previous != null) LEFT.put(previous, System.currentTimeMillis() + GRACE_MS);
                previous = now;
            }
            if (now != null) LEFT.remove(now);
            long time = System.currentTimeMillis();
            LEFT.entrySet().removeIf(entry -> {
                if (time < entry.getValue()) return false;
                nudge(entry.getKey());
                return true;
            });
        });
    }

    private static void nudge(Room room) {
        if (room.getType() == Room.Type.FAIRY || room.getType() == Room.Type.ENTRANCE) return;
        String name = RoomClears.nameIfSecretFound(room);
        int found = room.getFoundSecretCount();
        int max = room.getMaxSecretCount();
        if (name == null || !shouldNudge(found, max) || !NUDGED.add(room)) return;
        Sky2MAlerts.chat(Component.literal(message(name, max - found)).withStyle(ChatFormatting.YELLOW));
    }

    /** Whether a room is worth a nudge: some of its secrets found and not all. Public for the game test. */
    public static boolean shouldNudge(int found, int max) {
        return found > 0 && found < max;
    }

    /** "Left Raccoon with 2 secrets". Public for the game test. */
    public static String message(String room, int left) {
        return "Left " + room + " with " + left + (left == 1 ? " secret" : " secrets");
    }
}
