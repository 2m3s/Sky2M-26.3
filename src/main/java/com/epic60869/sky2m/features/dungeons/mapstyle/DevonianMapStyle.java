package com.epic60869.sky2m.features.dungeons.mapstyle;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.HashMap;
import java.util.Map;

/**
 * Devonian's dungeon map (https://github.com/Synnerz/devonian, GPL-3.0: {@code features/dungeons/map/DungeonMap.kt} and
 * {@code DungeonMapBaseRenderer.kt}), redrawn from the {@link MapView} with Devonian's default settings: the floor is
 * laid out on a square of {@code max(cols, rows) + 2 * 0.12} room widths (0.12 padding), rooms are 0.8 of a cell wide
 * with 0.2 connectors, doors are 0.31 of a cell across, the background is a faint dark translucent square with no
 * border, rooms use Devonian's colours (entrance 20/133/0, normal and miniboss-mob brown 107/58/17, puzzle 117/0/133,
 * trap 216/127/51, yellow 254/223/0, fairy 224/0/255, blood red, rare 255/203/89, unknown 65/65/65), unexplored rooms
 * are grey and only drawn next to an explored door, rooms not yet entered are darkened by 0.7 once the run started,
 * normal doors take the colour of the room beside them, wither doors are black, blood doors red, entrance doors
 * green. Room text is the name split into words plus {@code found/total} secrets (green cleared, white done, red
 * failed, grey otherwise; no name on entrance, fairy or blood), drawn with a drop shadow in the middle of the room.
 * Others are player heads, you are a pointer marker. Devonian draws no run-info text on the map itself.
 */
public final class DevonianMapStyle implements MapStyleRenderer {
    private static final double PADDING = 0.12;
    private static final double ROOM_WIDTH = 0.8;
    private static final double DOOR_WIDTH = 0.31;
    private static final double DARKEN = 0.7;
    private static final int BACKGROUND = 0x5E020101;

    @Override
    public String id() {
        return "devonian";
    }

    @Override
    public int width() {
        return MapView.MAP_SIZE;
    }

    @Override
    public int height() {
        return MapView.MAP_SIZE;
    }

    /** Per-frame layout: pixel size of one 1.0 cell and where the floor starts. */
    private record Geo(double cell, double ox, double oy) {
        int px(double cx) { return (int) (cx * cell + ox); }
        int py(double cz) { return (int) (cz * cell + oy); }
        int len(double c) { return (int) Math.ceil(c * cell); }
    }

    @Override
    public void render(GuiGraphicsExtractor g, MapView view, boolean preview) {
        FeatureConfigs.DungeonMap cfg = config();
        g.fill(0, 0, MapView.MAP_SIZE, MapView.MAP_SIZE, BACKGROUND);
        if (preview) {
            Font font = Minecraft.getInstance().font;
            String s = "Dungeon Map :)";
            g.text(font, s, (MapView.MAP_SIZE - font.width(s)) / 2, (MapView.MAP_SIZE - font.lineHeight) / 2, 0xFFFFFFFF, true);
            return;
        }
        if (!view.valid()) return;

        double maxDim = Math.max(view.cols(), view.rows());
        double total = maxDim + PADDING * 2;
        double cell = MapView.MAP_SIZE / total;
        Geo geo = new Geo(cell, ((maxDim - view.cols()) / 2.0 + PADDING) * cell, ((maxDim - view.rows()) / 2.0 + PADDING) * cell);

        Map<Integer, MapView.Room> byCell = new HashMap<>();
        for (MapView.Room room : view.rooms()) {
            for (int[] t : room.tiles()) byCell.put(t[1] * 2 * 11 + t[0] * 2, room);
        }

        drawDoors(g, view, geo, byCell);
        drawRooms(g, view, geo, byCell);
        if (cfg == null || cfg.roomLabels) drawText(g, view, geo);
        else drawChecks(g, view, geo);
        if (!view.inBoss()) drawPlayers(g, view, geo, cfg == null || cfg.fancy);
    }

    private static FeatureConfigs.DungeonMap config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.map;
    }

    // ---------------------------------------------------------------- colours

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int argb) {
        int r = (int) (((argb >> 16) & 0xFF) * DARKEN + 0.5);
        int gr = (int) (((argb >> 8) & 0xFF) * DARKEN + 0.5);
        int b = (int) ((argb & 0xFF) * DARKEN + 0.5);
        return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }

    private static boolean explored(MapView.Room room) {
        return room.state().ordinal() >= MapView.RoomState.DISCOVERED.ordinal();
    }

    private static int baseColor(MapView.RoomType type) {
        return switch (type) {
            case ENTRANCE -> rgb(20, 133, 0);
            case NORMAL -> rgb(107, 58, 17);
            case PUZZLE -> rgb(117, 0, 133);
            case TRAP -> rgb(216, 127, 51);
            case MINIBOSS -> rgb(254, 223, 0); // Devonian's "yellow" room
            case FAIRY -> rgb(224, 0, 255);
            case BLOOD -> rgb(255, 0, 0);
            case RARE -> rgb(255, 203, 89);
            case UNKNOWN -> rgb(65, 65, 65);
        };
    }

    private static int roomColor(MapView view, MapView.Room room) {
        int col = explored(room) ? baseColor(room.type()) : baseColor(MapView.RoomType.UNKNOWN);
        return view.run().started() && !explored(room) ? darken(col) : col;
    }

    // ---------------------------------------------------------------- doors and rooms

    private static void drawDoors(GuiGraphicsExtractor g, MapView view, Geo geo, Map<Integer, MapView.Room> byCell) {
        double roomOffset = (1.0 - ROOM_WIDTH) * 0.5;
        double doorOffset = (1.0 - DOOR_WIDTH) * 0.5;
        for (MapView.Door d : view.doors()) {
            MapView.Room best = null;
            boolean anyExplored = false;
            for (int[] side : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = d.gx() + side[0];
                int nz = d.gz() + side[1];
                MapView.Room r = nx < 0 || nz < 0 ? null : byCell.get(nz * 11 + nx);
                if (r == null || r.state() == MapView.RoomState.UNDISCOVERED) continue;
                if (explored(r)) anyExplored = true;
                if (best == null || rank(r) < rank(best)) best = r;
            }
            if (!anyExplored) continue; // Devonian: no door between two rooms nobody has seen
            int color;
            switch (d.type()) {
                case ENTRANCE -> color = rgb(20, 133, 0);
                case WITHER -> color = rgb(0, 0, 0);
                case BLOOD -> color = rgb(255, 0, 0);
                default -> {
                    if (d.unopened()) continue; // a normal door is only drawn once open
                    color = best == null ? baseColor(MapView.RoomType.NORMAL) : roomColor(view, best);
                }
            }
            // door cell (gx, gz) in 11x11 grid: odd = between two rooms. Devonian cell units = gx / 2.
            double cx1 = d.gx() / 2;
            double cz1 = d.gz() / 2;
            double cx;
            double cz;
            double cw;
            double ch;
            if (d.gx() % 2 == 0) { // vertical connection: the door sits below row cz1
                cx = cx1 + doorOffset;
                cz = cz1 + roomOffset + ROOM_WIDTH;
                cw = DOOR_WIDTH;
                ch = roomOffset * 2.0;
            } else {
                cx = cx1 + roomOffset + ROOM_WIDTH;
                cz = cz1 + doorOffset;
                cw = roomOffset * 2.0;
                ch = DOOR_WIDTH;
            }
            int x = geo.px(cx);
            int y = geo.py(cz);
            g.fill(x, y, x + geo.len(cw) + 1, y + geo.len(ch) + 1, color);
        }
    }

    /** Lower = more interesting room, so a door next to a puzzle takes the puzzle's colour. */
    private static int rank(MapView.Room r) {
        return (explored(r) ? 0 : 100) + (r.type() == MapView.RoomType.NORMAL ? 50 : r.type().ordinal());
    }

    private static boolean hasTile(MapView.Room room, int col, int row) {
        for (int[] t : room.tiles()) if (t[0] == col && t[1] == row) return true;
        return false;
    }

    private static void drawRooms(GuiGraphicsExtractor g, MapView view, Geo geo, Map<Integer, MapView.Room> byCell) {
        double roomOffset = (1.0 - ROOM_WIDTH) * 0.5;
        Map<Integer, MapView.Door> doorCells = new HashMap<>();
        for (MapView.Door d : view.doors()) doorCells.put(d.gz() * 11 + d.gx(), d);
        for (MapView.Room room : view.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED) continue;
            boolean seen = explored(room);
            int color = roomColor(view, room);
            for (int[] t : room.tiles()) {
                if (!seen && !touchesExploredDoor(t, doorCells, byCell)) continue; // unexplored: only the cell next to an explored door
                int x = geo.px(t[0] + roomOffset);
                int y = geo.py(t[1] + roomOffset);
                int s = geo.len(ROOM_WIDTH) + 1;
                g.fill(x, y, x + s, y + s, color);
                if (!seen) continue;
                boolean right = hasTile(room, t[0] + 1, t[1]);
                boolean down = hasTile(room, t[0], t[1] + 1);
                int join = geo.len(roomOffset * 2.0) + 1;
                if (right) g.fill(geo.px(t[0] + roomOffset + ROOM_WIDTH), y, geo.px(t[0] + roomOffset + ROOM_WIDTH) + join, y + s, color);
                if (down) g.fill(x, geo.py(t[1] + roomOffset + ROOM_WIDTH), x + s, geo.py(t[1] + roomOffset + ROOM_WIDTH) + join, color);
                if (right && down && hasTile(room, t[0] + 1, t[1] + 1)) {
                    int jx = geo.px(t[0] + roomOffset + ROOM_WIDTH);
                    int jy = geo.py(t[1] + roomOffset + ROOM_WIDTH);
                    g.fill(jx, jy, jx + join, jy + join, color);
                }
            }
        }
    }

    private static boolean touchesExploredDoor(int[] tile, Map<Integer, MapView.Door> doors, Map<Integer, MapView.Room> byCell) {
        int cx = tile[0] * 2;
        int cz = tile[1] * 2;
        for (int[] side : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            int dx = cx + side[0];
            int dz = cz + side[1];
            if (dx < 0 || dz < 0 || !doors.containsKey(dz * 11 + dx)) continue;
            MapView.Room other = byCell.get((cz + side[1] * 2) * 11 + cx + side[0] * 2);
            if (other != null && explored(other)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- text and checkmarks

    private static int stateColor(MapView view, MapView.Room room) {
        return switch (room.state()) {
            case GREEN -> 0xFF55FF55;
            case FAILED -> 0xFFFF5555;
            case CLEARED -> 0xFFFFFFFF;
            default -> view.run().started() || explored(room) ? 0xFFAAAAAA : 0xFFFFFFFF;
        };
    }

    private static void drawText(GuiGraphicsExtractor g, MapView view, Geo geo) {
        Font font = Minecraft.getInstance().font;
        double fontPx = Math.ceil(ROOM_WIDTH * geo.cell() * 0.25 * 1.33);
        float scale = (float) (fontPx / font.lineHeight);
        for (MapView.Room room : view.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED || !explored(room)) continue;
            boolean noName = room.type() == MapView.RoomType.ENTRANCE || room.type() == MapView.RoomType.BLOOD
                    || room.type() == MapView.RoomType.FAIRY;
            java.util.List<String> lines = new java.util.ArrayList<>();
            java.util.List<Integer> colors = new java.util.ArrayList<>();
            boolean green = room.state() == MapView.RoomState.GREEN;
            if (!noName && room.name() != null) {
                int c = stateColor(view, room);
                for (String w : room.name().replace("​", "- ").split(" ")) {
                    lines.add(w);
                    colors.add(c);
                }
            }
            if (room.secretsTotal() > 0) {
                int shown = green ? Math.max(room.secretsTotal(), room.secretsFound()) : room.secretsFound();
                lines.add((shown < 0 ? "?" : String.valueOf(shown)) + "/" + room.secretsTotal());
                colors.add(stateColor(view, room));
            }
            // Devonian shows a tick instead when it wanted the name but the room has none (and when 0 secrets are off)
            String glyph = null;
            if (!noName && room.name() == null) {
                glyph = switch (room.state()) {
                    case CLEARED, GREEN -> "✔";
                    case FAILED -> "✖";
                    default -> null;
                };
            }
            if (lines.isEmpty() && glyph == null) continue;
            float cx = (float) ((room.labelCol() + 0.5) * geo.cell() + geo.ox());
            float cy = (float) ((room.labelRow() + 0.5) * geo.cell() + geo.oy());
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().scale(scale, scale);
            int n = lines.size();
            for (int i = 0; i < n; i++) {
                String s = lines.get(i);
                g.text(font, s, -font.width(s) / 2, Math.round((i - n / 2f) * font.lineHeight), colors.get(i), true);
            }
            if (glyph != null) {
                g.text(font, glyph, -font.width(glyph) / 2, -font.lineHeight / 2, stateColor(view, room), false);
            }
            g.pose().popMatrix();
        }
    }

    /** Room labels off: only the ticks are left, as Devonian does when names are disabled. */
    private static void drawChecks(GuiGraphicsExtractor g, MapView view, Geo geo) {
        Font font = Minecraft.getInstance().font;
        for (MapView.Room room : view.rooms()) {
            String glyph = switch (room.state()) {
                case CLEARED, GREEN -> "✔";
                case FAILED -> "✖";
                default -> null;
            };
            if (glyph == null) continue;
            float cx = (float) ((room.labelCol() + 0.5) * geo.cell() + geo.ox());
            float cy = (float) ((room.labelRow() + 0.5) * geo.cell() + geo.oy());
            g.pose().pushMatrix();
            g.pose().translate(cx, cy);
            g.pose().scale(0.9f, 0.9f);
            g.text(font, glyph, -font.width(glyph) / 2, -font.lineHeight / 2, stateColor(view, room), false);
            g.pose().popMatrix();
        }
    }

    // ---------------------------------------------------------------- players

    private static void drawPlayers(GuiGraphicsExtractor g, MapView view, Geo geo, boolean heads) {
        // Heads come in map-item pixels; this style lays rooms out on its own padded grid, so they're moved onto it
        // (a room slot's pixels span [col, col + roomSize/pitch] cells there, offset by the room inset). Drawn at the
        // map-item pixel, heads sat away from the room they were in, further the nearer the map's edge.
        double pitch = view.roomSize() + view.gap();
        double roomOffset = (1.0 - ROOM_WIDTH) * 0.5;
        // Devonian draws your own marker on top; keep the same order (others first).
        for (int pass = 0; pass < 2; pass++) {
            for (MapView.Head head : view.heads()) {
                if (head.self() != (pass == 1)) continue;
                double col = (head.x() - view.startX()) / pitch;
                double row = (head.y() - view.startY()) / pitch;
                g.pose().pushMatrix();
                g.pose().translate((float) (col * geo.cell() + geo.ox() + roomOffset * geo.cell()),
                    (float) (row * geo.cell() + geo.oy() + roomOffset * geo.cell()));
                g.pose().rotate((float) Math.toRadians(head.yaw() + 180f));
                if (!head.self() && heads && head.skin() != null) {
                    // 2.5 head scale: a face about 10 per cent of the map wide
                    g.pose().scale(1.5f, 1.5f);
                    g.pose().translate(-4, -4);
                    var texture = head.skin().body().texturePath();
                    g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8f, 8f, 8, 8, 64, 64);
                    g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40f, 8f, 8, 8, 64, 64);
                } else {
                    drawMarker(g, head.self() ? 0xFF2EDB2E : 0xFF4D8BFF);
                }
                g.pose().popMatrix();
            }
        }
    }

    /** The pointer marker (Devonian uses an image; this is a same-size arrow: tip up, base 6 wide, 9 long). */
    private static void drawMarker(GuiGraphicsExtractor g, int color) {
        for (int i = 0; i < 9; i++) {
            int half = 1 + i * 3 / 8;
            g.fill(-half - 1, -5 + i - 1, half + 1, -5 + i + 2, 0xFF000000);
        }
        for (int i = 0; i < 9; i++) {
            int half = 1 + i * 3 / 8;
            g.fill(-half, -5 + i, half, -5 + i + 1, color);
        }
    }
}
