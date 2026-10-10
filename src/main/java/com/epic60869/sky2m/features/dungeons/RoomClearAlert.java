package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Dungeons > Secrets > Room Clear Alert, ported from Odin's Room Clear (https://github.com/odtheking/Odin,
 * features/impl/dungeon/RoomClear.kt, BSD-3-Clause): when the room you're in gets its white checkmark ("Room Cleared!")
 * or its green one, all secrets done ("Room Complete!"), a title and a ding. Fed by the dungeon map's checkmark reading
 * in DungeonManager.
 */
public final class RoomClearAlert {
    /**
     * Rooms the map has shown without a checkmark. A room is only new to Sky2M when you first walk into it, so one
     * your team cleared before you got there would otherwise alert as you enter.
     */
    private static final Set<Room> SEEN_UNCLEARED = Collections.newSetFromMap(new WeakHashMap<>());

    private RoomClearAlert() {}

    public static void onUncleared(Room room) {
        SEEN_UNCLEARED.add(room);
    }

    /** The map now shows a checkmark on {@code room}; {@code previous} is what it showed before. */
    public static void onCheckmark(Room room, Room currentRoom, Room.ClearState previous, boolean green) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.dungeons.secrets.roomClearAlert) return;
        if (room != currentRoom || !SEEN_UNCLEARED.contains(room)) return;
        if (room.getType() == Room.Type.FAIRY || room.getType() == Room.Type.ENTRANCE) return;
        // White then green alerts twice, like Odin; green again is never reported (the map stops checking it).
        if (green ? previous == Room.ClearState.GREEN_CHECKED : previous == Room.ClearState.WHITE_CHECKED) return;
        FeatureConfigs.RoomClearMode mode = config.dungeons.secrets.roomClearMode;
        if (mode == FeatureConfigs.RoomClearMode.GREEN && !green) return;
        if (mode == FeatureConfigs.RoomClearMode.WHITE && green) return;
        Sky2MAlerts.title(green ? Component.literal("Room Complete!").withStyle(ChatFormatting.GREEN)
            : Component.literal("Room Cleared!"), null);
    }
}
