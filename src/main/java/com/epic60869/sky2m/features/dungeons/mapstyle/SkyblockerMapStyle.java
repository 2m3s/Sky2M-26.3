package com.epic60869.sky2m.features.dungeons.mapstyle;

import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.config.configs.DungeonsConfig;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonMap;
import com.epic60869.sky2m.sb.utils.render.GuiHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.CommonColors;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/**
 * Skyblocker's dungeon map. In a run it is Skyblocker's own drawing, unchanged ({@link DungeonMap#extract}: the real
 * Hypixel map item's texture, room names and the heads over it). In the Dungeon Sim there is no map item, so the same
 * look is drawn from the {@link MapView}: the colours are the vanilla map palette ids Hypixel itself uses (entrance
 * 30 green, blood 18 red, puzzle 66, trap 62, miniboss 74, fairy 82, normal 63, rare 34, unopened 85, wither door
 * 119), 16 px rooms with 4 px connectors and 6 px doors, a checkmark per room and Skyblocker's head markers.
 */
public final class SkyblockerMapStyle implements MapStyleRenderer {
    @Override
    public String id() {
        return "skyblocker";
    }

    @Override
    public int width() {
        return MapView.MAP_SIZE;
    }

    @Override
    public int height() {
        return MapView.MAP_SIZE;
    }

    @Override
    public void render(GuiGraphicsExtractor g, MapView view, boolean preview) {
        DungeonsConfig.DungeonMap cfg = SkyblockerConfigManager.get().dungeons.dungeonMap;
        if (preview) {
            g.fill(0, 0, MapView.MAP_SIZE, MapView.MAP_SIZE, 0x80202020);
            return;
        }
        if (!view.simulated()) {
            DungeonMap.extract(g, 0, 0, 1f, cfg.fancyMap); // the real map item, exactly as before
            return;
        }
        if (!view.valid()) return;
        if (cfg.backgroundBlur) GuiHelper.blurredRectangle(g, 0, 0, MapView.MAP_SIZE, MapView.MAP_SIZE, 5);
        if (cfg.showOutline) GuiHelper.border(g, 0, 0, MapView.MAP_SIZE, MapView.MAP_SIZE, CommonColors.LIGHT_GRAY);
        drawRooms(g, view);
        drawDoors(g, view);
        drawLabels(g, view, cfg.showRoomLabels);
        if (cfg.fancyMap) drawHeads(g, view, cfg.showSelfHead);
    }

    /** A vanilla map colour id as ARGB. */
    private static int palette(int packedId) {
        int abgr = MapColor.getColorFromPackedId(packedId);
        return 0xFF000000 | ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
    }

    private static int roomColor(MapView.Room room) {
        if (room.state() == MapView.RoomState.UNOPENED) return palette(85);
        return palette(switch (room.type()) {
            case ENTRANCE -> 30;
            case BLOOD -> 18;
            case PUZZLE -> 66;
            case TRAP -> 62;
            case MINIBOSS -> 74;
            case FAIRY -> 82;
            case RARE -> 34;
            case UNKNOWN -> 85;
            default -> 63;
        });
    }

    private static void drawRooms(GuiGraphicsExtractor g, MapView v) {
        for (MapView.Room room : v.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED) continue;
            int color = roomColor(room);
            for (int[] t : room.tiles()) {
                int x = v.cellX(t[0] * 2);
                int y = v.cellY(t[1] * 2);
                g.fill(x, y, x + v.roomSize(), y + v.roomSize(), color);
                // connector to the right / below, and the centre of a 2x2 room
                boolean right = has(room, t[0] + 1, t[1]);
                boolean down = has(room, t[0], t[1] + 1);
                if (right) g.fill(x + v.roomSize(), y, x + v.roomSize() + v.gap(), y + v.roomSize(), color);
                if (down) g.fill(x, y + v.roomSize(), x + v.roomSize(), y + v.roomSize() + v.gap(), color);
                if (right && down && has(room, t[0] + 1, t[1] + 1)) {
                    g.fill(x + v.roomSize(), y + v.roomSize(), x + v.roomSize() + v.gap(), y + v.roomSize() + v.gap(), color);
                }
            }
        }
    }

    private static boolean has(MapView.Room room, int col, int row) {
        for (int[] t : room.tiles()) if (t[0] == col && t[1] == row) return true;
        return false;
    }

    private static void drawDoors(GuiGraphicsExtractor g, MapView v) {
        Map<Integer, MapView.Room> byCell = new HashMap<>();
        for (MapView.Room room : v.rooms()) {
            for (int[] t : room.tiles()) byCell.put(t[1] * 2 * 11 + t[0] * 2, room);
        }
        int doorWidth = 6;
        for (MapView.Door d : v.doors()) {
            int color;
            if (d.unopened()) {
                color = palette(85);
            } else if (d.type() == MapView.DoorType.WITHER && d.locked()) {
                color = palette(119);
            } else if (d.type() == MapView.DoorType.BLOOD && d.locked()) {
                color = palette(18);
            } else if (d.type() == MapView.DoorType.ENTRANCE) {
                color = palette(30);
            } else {
                // a plain door takes the colour of the more interesting room beside it
                color = palette(63);
                int bestPrio = 99;
                for (int[] side : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int nx = d.gx() + side[0];
                    int nz = d.gz() + side[1];
                    MapView.Room r = nx < 0 || nz < 0 ? null : byCell.get(nz * 11 + nx);
                    if (r == null || r.state() == MapView.RoomState.UNDISCOVERED || r.type() == MapView.RoomType.NORMAL) continue;
                    if (r.type().ordinal() < bestPrio) {
                        bestPrio = r.type().ordinal();
                        color = roomColor(r);
                    }
                }
            }
            int x = v.cellX(d.gx());
            int y = v.cellY(d.gz());
            int w = d.gx() % 2 == 1 ? v.gap() : doorWidth;
            int h = d.gz() % 2 == 1 ? v.gap() : doorWidth;
            if (d.gx() % 2 == 0) x += (v.roomSize() - doorWidth) / 2;
            if (d.gz() % 2 == 0) y += (v.roomSize() - doorWidth) / 2;
            g.fill(x, y, x + w, y + h, color);
        }
    }

    private static void drawLabels(GuiGraphicsExtractor g, MapView v, boolean names) {
        Font font = Minecraft.getInstance().font;
        for (MapView.Room room : v.rooms()) {
            float cx = v.startX() + room.labelCol() * (v.roomSize() + v.gap()) + v.roomSize() / 2f;
            float cy = v.startY() + room.labelRow() * (v.roomSize() + v.gap()) + v.roomSize() / 2f;
            String glyph = switch (room.state()) {
                case CLEARED, GREEN -> "✔";
                case FAILED -> "✖";
                case UNOPENED -> "?";
                default -> null;
            };
            int color = switch (room.state()) {
                case GREEN -> 0xFF55FF55;
                case FAILED -> 0xFFFF5555;
                case UNOPENED -> 0xFF1A1A1A;
                default -> 0xFFFFFFFF;
            };
            boolean showName = names && room.name() != null && room.state() != MapView.RoomState.UNDISCOVERED
                    && room.type() != MapView.RoomType.ENTRANCE && room.type() != MapView.RoomType.BLOOD
                    && room.type() != MapView.RoomType.FAIRY;
            if (showName) {
                String[] words = room.name().split(" ");
                float scale = 0.45f;
                g.pose().pushMatrix();
                g.pose().translate(cx, cy - words.length * font.lineHeight * scale / 2f - (glyph != null ? 3 : 0));
                g.pose().scale(scale, scale);
                for (int i = 0; i < words.length; i++) {
                    g.text(font, words[i], -font.width(words[i]) / 2, i * font.lineHeight, 0xFFFFFFFF, true);
                }
                g.pose().popMatrix();
            }
            if (glyph != null) {
                float gy = showName ? cy + 4 : cy;
                g.pose().pushMatrix();
                g.pose().translate(cx, gy);
                g.pose().scale(0.75f, 0.75f);
                g.text(font, glyph, -font.width(glyph) / 2, -font.lineHeight / 2, color, false);
                g.pose().popMatrix();
            }
        }
    }

    /** Skyblocker's marker: the skin's face (8 px) in a 1 px frame in the class colour, a tick towards where it looks. */
    private static void drawHeads(GuiGraphicsExtractor g, MapView v, boolean showSelf) {
        for (MapView.Head head : v.heads()) {
            if (head.self() && !showSelf) continue;
            int color = head.classColor() >= 0 ? 0xFF000000 | head.classColor() : 0xFFFFFFFF;
            g.pose().pushMatrix();
            g.pose().translate(head.x(), head.y());
            g.pose().rotate((float) Math.toRadians(head.yaw() + 180f));
            if (head.skin() != null) {
                g.fill(-5, -5, 5, 5, color);
                var texture = head.skin().body().texturePath();
                g.pose().translate(-4, -4);
                g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8f, 8f, 8, 8, 64, 64);
                g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40f, 8f, 8, 8, 64, 64);
            } else {
                g.fill(-3, -3, 3, 3, color);
            }
            g.pose().popMatrix();
            g.pose().pushMatrix();
            g.pose().translate(head.x(), head.y());
            g.pose().rotate((float) Math.toRadians(head.yaw() + 180f));
            g.fill(-1, -7, 1, -5, color);
            g.pose().popMatrix();
        }
    }
}
