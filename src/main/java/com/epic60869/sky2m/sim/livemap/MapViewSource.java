package com.epic60869.sky2m.sim.livemap;

import com.epic60869.sky2m.features.dungeons.ScoreCalculator;
import com.epic60869.sky2m.features.dungeons.mapstyle.MapView;
import com.epic60869.sky2m.sim.dungeonclass.DungeonClass;
import com.epic60869.sky2m.sim.roomdatabase.RoomEntry;
import com.epic60869.sky2m.sim.roomsim.SimState;
import com.epic60869.sky2m.sim.secrets.DungeonState;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the neutral {@link MapView} every Dungeon Map style draws from: on Hypixel from the dungeon map item
 * ({@link DungeonMapScanner}: calibrated geometry, room states, doors, decorations) plus the room scanner's names and
 * types ({@link LiveMapFeature}), in the Dungeon Sim from the floor the sim published (no map item exists there).
 * The reveal rules are the live map's own: on Hypixel only what the map item shows is included, and an unopened room
 * carries no name, type or secret count. Rebuilt at most once per client tick.
 */
public final class MapViewSource {
    private static MapView cached = MapView.empty();
    private static int cachedTick = Integer.MIN_VALUE;
    private static int cachedGeneration = Integer.MIN_VALUE;

    private MapViewSource() {}

    /** The current view (client thread). Empty/invalid outside a dungeon and the sim. */
    public static MapView current() {
        int tick = LiveMapFeature.tickCount();
        int generation = LiveMapFeature.resetGeneration();
        if (tick != cachedTick || generation != cachedGeneration) {
            cachedTick = tick;
            cachedGeneration = generation;
            try {
                cached = build();
            } catch (RuntimeException e) {
                cached = MapView.empty();
            }
        }
        return cached;
    }

    private static MapView build() {
        boolean sim = SimState.isActive();
        if (!sim && !DungeonState.isInDungeon()) return MapView.empty();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return MapView.empty();

        int cols;
        int rows;
        int roomSize = 16;
        int startX = 6;
        int startY = 6;
        int c0 = 0;
        int r0 = 0;
        if (sim && LiveMapFeature.simRoomExtent() != null) {
            int[] e = LiveMapFeature.simRoomExtent();
            c0 = e[0];
            r0 = e[1];
            cols = e[2] - e[0] + 1;
            rows = e[3] - e[1] + 1;
            // like a real small floor: centred in the 128 px map
            startX = (MapView.MAP_SIZE - (cols * 20 - 4)) / 2 - c0 * 20;
            startY = (MapView.MAP_SIZE - (rows * 20 - 4)) / 2 - r0 * 20;
        } else {
            int[] floor = MapPainter.currentFloorRooms();
            cols = floor[0];
            rows = floor[1];
            if (DungeonMapScanner.isCalibrated()) {
                roomSize = DungeonMapScanner.mapRoomSize();
                startX = DungeonMapScanner.startCornerX();
                startY = DungeonMapScanner.startCornerZ();
            }
        }
        boolean valid = sim || DungeonMapScanner.isCalibrated();
        int gap = 4;

        List<MapView.Room> rooms = new ArrayList<>();
        for (LiveMapFeature.RoomGroup group : LiveMapFeature.groupsView()) {
            if (!MapPainter.isRevealed(group)) continue;
            RoomEntry entry = group.entry;
            boolean identity = MapPainter.identityRevealed(group) && entry != null;
            int mapState = MapPainter.visibleState(group);
            MapView.RoomState state = switch (mapState) {
                case DungeonMapScanner.STATE_GREEN -> MapView.RoomState.GREEN;
                case DungeonMapScanner.STATE_CLEARED -> MapView.RoomState.CLEARED;
                case DungeonMapScanner.STATE_FAILED -> MapView.RoomState.FAILED;
                case DungeonMapScanner.STATE_UNOPENED -> MapView.RoomState.UNOPENED;
                case DungeonMapScanner.STATE_UNDISCOVERED -> MapView.RoomState.UNDISCOVERED;
                default -> MapView.RoomState.DISCOVERED;
            };
            int total = identity ? entry.secrets : -1;
            int found = 0;
            if (identity && total > 0) {
                found = mapState == DungeonMapScanner.STATE_GREEN ? total : Math.max(0, LiveMapFeature.foundSecrets(entry.name));
            }
            List<int[]> tiles = new ArrayList<>();
            for (int cell : group.tiles) {
                tiles.add(new int[]{(cell % LiveMapFeature.GRID) / 2, (cell / LiveMapFeature.GRID) / 2});
            }
            rooms.add(new MapView.Room(identity ? entry.name : null, roomType(MapPainter.roomType(group)), state, tiles,
                    group.labelGX / 2f, group.labelGZ / 2f, found, total, group.lShape));
        }

        boolean strict = !sim && !MapPainter.onP3Sim();
        DungeonLayout layout = DungeonLayout.current();
        List<MapView.Door> doors = new ArrayList<>();
        for (int idx = 0; idx < LiveMapFeature.GRID * LiveMapFeature.GRID; idx++) {
            int gx = idx % LiveMapFeature.GRID;
            int gz = idx / LiveMapFeature.GRID;
            if ((gx % 2) + (gz % 2) != 1) continue;
            int type = layout.doorType(idx);
            boolean locked = layout.isLocked(idx);
            if (strict) {
                type = switch (DungeonMapScanner.doorTileAt(idx)) {
                    case DOOR_WITHER -> DungeonLayout.DOOR_WITHER;
                    case DOOR_BLOOD -> DungeonLayout.DOOR_BLOOD;
                    case DOOR_ENTRANCE -> DungeonLayout.DOOR_ENTRANCE;
                    case DOOR_NORMAL -> DungeonLayout.DOOR_NORMAL;
                    default -> DungeonLayout.DOOR_NONE;
                };
                locked = type == DungeonLayout.DOOR_WITHER || type == DungeonLayout.DOOR_BLOOD;
            }
            if (type == DungeonLayout.DOOR_NONE || !MapPainter.cellRevealed(idx)) continue;
            boolean unopened = !LiveMapFeature.isWorldScanned(idx) && DungeonMapScanner.isCalibrated()
                    && DungeonMapScanner.stateAt(idx) == DungeonMapScanner.STATE_UNOPENED;
            MapView.DoorType doorType = switch (type) {
                case DungeonLayout.DOOR_WITHER -> MapView.DoorType.WITHER;
                case DungeonLayout.DOOR_BLOOD -> MapView.DoorType.BLOOD;
                case DungeonLayout.DOOR_ENTRANCE -> MapView.DoorType.ENTRANCE;
                default -> MapView.DoorType.NORMAL;
            };
            doors.add(new MapView.Door(gx, gz, doorType, locked, unopened));
        }

        boolean inBoss = LiveMapFeature.isInBoss();
        List<MapView.Head> heads = new ArrayList<>();
        if (!inBoss) {
            double mult = (roomSize + 4.0) / 32.0;
            for (InteractiveMapFeature.MapPlayer mp : InteractiveMapFeature.playersCached(client)) {
                float x = (float) ((mp.worldX() - LiveMapFeature.START_X + 15) * mult + startX);
                float y = (float) ((mp.worldZ() - LiveMapFeature.START_Z + 15) * mult + startY);
                DungeonClass cls = mp.dungeonClass();
                heads.add(new MapView.Head(mp.name(), mp.self(), x, y, mp.yaw(), cls == null ? -1 : cls.color() & 0xFFFFFF,
                        MapHeads.skinFor(mp)));
            }
        }

        MapView.RunInfo run = new MapView.RunInfo(ScoreCalculator.started(), ScoreCalculator.score(),
                ScoreCalculator.foundSecrets(), ScoreCalculator.secretsNeeded(), ScoreCalculator.totalSecrets(),
                ScoreCalculator.crypts(), ScoreCalculator.deaths(), ScoreCalculator.mimicKilled(),
                ScoreCalculator.princeKilled(), ScoreCalculator.secondsElapsed());
        String floor = DungeonState.getFloor();
        return new MapView(valid, sim, inBoss, floor == null ? "" : floor, cols, rows, roomSize, gap, startX, startY,
                List.copyOf(rooms), List.copyOf(doors), List.copyOf(heads), run);
    }

    private static MapView.RoomType roomType(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "ENTRANCE" -> MapView.RoomType.ENTRANCE;
            case "PUZZLE" -> MapView.RoomType.PUZZLE;
            case "TRAP" -> MapView.RoomType.TRAP;
            case "CHAMPION", "MINIBOSS", "YELLOW" -> MapView.RoomType.MINIBOSS;
            case "FAIRY" -> MapView.RoomType.FAIRY;
            case "BLOOD" -> MapView.RoomType.BLOOD;
            case "RARE" -> MapView.RoomType.RARE;
            case "UNKNOWN" -> MapView.RoomType.UNKNOWN;
            default -> MapView.RoomType.NORMAL;
        };
    }
}
