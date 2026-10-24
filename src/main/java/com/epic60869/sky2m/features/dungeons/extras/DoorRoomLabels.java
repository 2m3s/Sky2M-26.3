package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonMapUtils;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector2i;
import org.joml.Vector2ic;

import java.util.ArrayList;
import java.util.List;

/**
 * Room labels on the doors of the room you are in, built on the room database of Skyblocker's secret waypoints (the
 * rooms it has identified: name, type and secrets found). A door is a gap on the 32-block room grid, 16 blocks from the
 * middle of a room; it is labelled when the block there is open (a normal door) or a wither or blood door, and the room
 * on the other side is one the map has drawn. Rooms that haven't been identified yet show their type (Puzzle, Trap...)
 * when the map says it, nothing otherwise: the mod can't know a room it has not seen.
 */
final class DoorRoomLabels {
    private record Label(Vec3 pos, Component text) {}

    private static List<Label> labels = List.of();
    private static int ticks;

    private DoorRoomLabels() {}

    static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> labels = List.of());
        ClientTickEvents.END_CLIENT_TICK.register(DoorRoomLabels::tick);
        Sky2MWorldRender.register(collector -> {
            DungeonExtras.Config c = DungeonExtras.config();
            if (c == null || !c.doorLabels.enabled || labels.isEmpty()) return;
            for (Label label : labels) collector.submitText(label.text(), label.pos(), c.doorLabels.scale, true);
        });
    }

    private static void tick(Minecraft mc) {
        DungeonExtras.Config c = DungeonExtras.config();
        if (c == null || !c.doorLabels.enabled || mc.level == null || mc.player == null || !Compat.isOnSkyblock()
            || !Sky2MLocation.inDungeon() || DungeonManager.isInBoss() || !DungeonManager.isRoomsLoaded()) {
            if (!labels.isEmpty()) labels = List.of();
            return;
        }
        if (++ticks % 10 != 0) return;
        Vector2ic here = DungeonMapUtils.getPhysicalRoomPos(mc.player.position());
        Room current = roomAt(here);
        if (current == null) {
            labels = List.of();
            return;
        }
        List<Label> out = new ArrayList<>();
        double maxSq = c.doorLabels.distance * c.doorLabels.distance;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Vector2ic segment : current.getSegments()) {
            for (int[] d : directions) {
                Vector2i neighbour = new Vector2i(segment.x() + 32 * d[0], segment.y() + 32 * d[1]);
                if (current.getSegments().contains(neighbour)) continue;
                Room other = roomAt(neighbour);
                if (other == null) continue;
                // The door: 16 blocks from the middle of the segment (15 from its corner).
                int doorX = segment.x() + 15 + 16 * d[0], doorZ = segment.y() + 15 + 16 * d[1];
                if (!isDoor(mc, doorX, doorZ)) continue;
                Vec3 pos = new Vec3(doorX + 0.5, 73.5, doorZ + 0.5);
                if (mc.player.position().distanceToSqr(pos) > maxSq) continue;
                Component text = describe(other, c.doorLabels.secrets);
                if (text != null) out.add(new Label(pos, text));
            }
        }
        labels = out;
    }

    private static Room roomAt(Vector2ic segment) {
        return DungeonManager.getRoomsStream().filter(r -> r.getSegments().contains(segment)).findFirst().orElse(null);
    }

    private static boolean isDoor(Minecraft mc, int x, int z) {
        BlockState low = mc.level.getBlockState(new BlockPos(x, 70, z));
        if (low.isAir()) return mc.level.getBlockState(new BlockPos(x, 71, z)).isAir();
        BlockState base = mc.level.getBlockState(new BlockPos(x, 69, z));
        return base.is(Blocks.COAL_BLOCK) || base.is(Blocks.DYED_TERRACOTTA.red());
    }

    private static Component describe(Room room, boolean secrets) {
        if (room.isMatched() && room.getName() != null) {
            DungeonManager.RoomInfo info = DungeonManager.getRoomMetadata(room.getName());
            String name = info == null ? room.getName() : info.name();
            var text = Component.literal(name).withStyle(room.getType() == Room.Type.ROOM ? ChatFormatting.WHITE : ChatFormatting.LIGHT_PURPLE);
            if (secrets && room.getMaxSecretCount() > 0) {
                text.append(Component.literal(" " + room.getFoundSecretCount() + "/" + room.getMaxSecretCount()).withStyle(ChatFormatting.GRAY));
            }
            return text;
        }
        return switch (room.getType()) {
            case PUZZLE, TRAP, MINIBOSS, FAIRY, BLOOD, ENTRANCE -> Component.literal(room.getType().getSerializedName()).withStyle(ChatFormatting.LIGHT_PURPLE);
            default -> null;
        };
    }
}
