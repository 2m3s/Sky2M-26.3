package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonMap;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonMapUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Highlights wither and blood doors (green when you have the key, red when locked) and the dropped keys.
 * Key tracking, messages and colours follow Odin's DoorHighlight (https://github.com/odtheking/Odin, BSD-3-Clause);
 * doors are found by checking the dungeon's door grid for coal (wither) and red terracotta (blood), and shown once
 * Hypixel's dungeon map draws them (when a room next to them is opened), like Odin's map scan. Drawn through walls.
 */
public final class DoorHighlight {
    private static final Pattern WITHER_KEY_OBTAINED = Pattern.compile("^(\\[[^]]*?])? ?(\\w{1,16}) has obtained Wither Key!?$");
    private static final Pattern WITHER_KEY_PICKED_UP = Pattern.compile("^A Wither Key was picked up!$");
    private static final Pattern WITHER_DOOR_OPENED = Pattern.compile("^(\\[[^]]*?])? ?(\\w{1,16}) opened a WITHER door!$");
    private static final Pattern BLOOD_KEY_OBTAINED = Pattern.compile("^(\\[[^]]*?])? ?(\\w{1,16}) has obtained Blood Key!$");
    private static final Pattern BLOOD_KEY_PICKED_UP = Pattern.compile("^A Blood Key was picked up!$");
    private static final float[] LOCKED = {1f, 0.33f, 0.33f};
    private static final float[] OPENABLE = {0.33f, 1f, 0.33f};
    private static final float[] WITHER_KEY = {0.1f, 0.1f, 0.1f};
    private static final float[] BLOOD_KEY = {1f, 0.2f, 0.2f};

    /** {@code east}: the door is on the east side of its room, otherwise the south side. */
    private record Door(BlockPos centre, boolean blood, boolean east) {}

    private static final List<Door> DOORS = new ArrayList<>();
    private static int witherKeys;
    private static boolean bloodKey;
    private static boolean bloodOpened;
    private static Entity keyEntity;
    private static boolean keyIsBlood;
    private static int ticks;

    private DoorHighlight() {}

    private static FeatureConfigs.Secrets config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.secrets;
    }

    private static boolean inClear() {
        return Sky2MLocation.inDungeon() && !DungeonManager.isInBoss();
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MChat.onChat(message -> {
            if (!inClear()) return;
            String text = message.text();
            if (WITHER_KEY_OBTAINED.matcher(text).matches() || WITHER_KEY_PICKED_UP.matcher(text).matches()) witherKeys++;
            else if (WITHER_DOOR_OPENED.matcher(text).matches()) witherKeys = Math.max(0, witherKeys - 1);
            else if (BLOOD_KEY_OBTAINED.matcher(text).matches() || BLOOD_KEY_PICKED_UP.matcher(text).matches()) bloodKey = true;
            else if (text.equals("The BLOOD DOOR has been opened!")) {
                bloodKey = false;
                bloodOpened = true;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(DoorHighlight::tick);
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.Secrets config = config();
            if (config == null || !inClear()) return;
            if (config.doorHighlight) {
                for (Door door : DOORS) {
                    if (door.blood() && bloodOpened) continue;
                    boolean openable = door.blood() ? bloodKey : witherKeys > 0;
                    BlockPos c = door.centre();
                    AABB box = new AABB(c.getX() - 1, 69, c.getZ() - 1, c.getX() + 2, 73, c.getZ() + 2);
                    // Through walls, so you can see where the next door is from across the map.
                    float[] colour = openable ? OPENABLE : LOCKED;
                    collector.submitFilledBox(box, colour, 0.3f, true);
                    collector.submitOutlinedBox(box, colour, 3f, true);
                }
            }
            if (config.keyHighlight && keyEntity != null && keyEntity.isAlive()) {
                AABB box = AABB.unitCubeFromLowerCorner(keyEntity.position().add(-0.5, 1, -0.5));
                collector.submitOutlinedBox(box, keyIsBlood ? BLOOD_KEY : WITHER_KEY, 3f, true);
            }
        });
    }

    private static void reset() {
        DOORS.clear();
        witherKeys = 0;
        bloodKey = false;
        bloodOpened = false;
        keyEntity = null;
    }

    private static void tick(Minecraft mc) {
        FeatureConfigs.Secrets config = config();
        if (config == null || mc.level == null || !inClear()) {
            if (!Sky2MLocation.inDungeon()) reset();
            return;
        }
        if (++ticks % 10 != 0) return;

        // Doors sit between rooms on the 32-block grid that starts at -200; each is 3 wide and 4 tall from y 69.
        if (config.doorHighlight) {
            DOORS.clear();
            MapItemSavedData map = mc.player == null ? null
                : MapItem.getSavedData(DungeonMap.getMapIdComponent(mc.player.getInventory().getNonEquipmentItems().get(8)), mc.level);
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 6; j++) {
                    int roomX = -185 + 32 * i, roomZ = -185 + 32 * j;
                    checkDoor(mc, map, new BlockPos(roomX + 16, 69, roomZ), true);
                    checkDoor(mc, map, new BlockPos(roomX, 69, roomZ + 16), false);
                }
            }
        }

        // Keys are armor stands named "Wither Key" / "Blood Key".
        if (keyEntity != null && !keyEntity.isAlive()) keyEntity = null;
        if (keyEntity == null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof ArmorStand) || !entity.hasCustomName()) continue;
                String name = ChatFormatting.stripFormatting(entity.getCustomName().getString());
                if (!name.equals("Wither Key") && !name.equals("Blood Key")) continue;
                keyEntity = entity;
                keyIsBlood = name.equals("Blood Key");
                if (config.announceKeySpawn) {
                    Sky2MAlerts.title(Component.literal(name + " spawned!").withStyle(keyIsBlood ? ChatFormatting.RED : ChatFormatting.DARK_GRAY), Component.empty());
                }
                break;
            }
        }
    }

    private static void checkDoor(Minecraft mc, MapItemSavedData map, BlockPos pos, boolean east) {
        BlockState state = mc.level.getBlockState(pos);
        boolean wither = state.is(Blocks.COAL_BLOCK);
        if (!wither && !state.is(Blocks.DYED_TERRACOTTA.red())) return;
        Door door = new Door(pos, !wither, east);
        if (onMap(map, door)) DOORS.add(door);
    }

    /** Whether Hypixel's dungeon map draws the door, which it does once a room next to it has been opened. */
    private static boolean onMap(MapItemSavedData map, Door door) {
        Vector2ic mapEntrance = DungeonManager.getMapEntrancePos();
        Vector2ic physicalEntrance = DungeonManager.getPhysicalEntrancePos();
        int size = DungeonManager.getMapRoomSize();
        if (map == null || mapEntrance == null || physicalEntrance == null || size == 0) return false;
        // Rooms are size pixels on the map with a 4 pixel gap; a door fills the gap after its room, halfway along it.
        BlockPos c = door.centre();
        Vector2ic room = DungeonMapUtils.getMapPosFromPhysical(physicalEntrance, mapEntrance, size,
            DungeonMapUtils.getPhysicalRoomPos(door.east() ? c.getX() - 16 : c.getX(), door.east() ? c.getZ() : c.getZ() - 16));
        int x = door.east() ? room.x() + size + 1 : room.x() + size / 2;
        int z = door.east() ? room.y() + size / 2 : room.y() + size + 1;
        return DungeonMapUtils.getColor(map, x, z) != 0;
    }
}
