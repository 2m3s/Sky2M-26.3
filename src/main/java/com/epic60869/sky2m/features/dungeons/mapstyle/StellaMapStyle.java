package com.epic60869.sky2m.features.dungeons.mapstyle;

import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.config.configs.DungeonsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stella's dungeon map (StellaNav), redrawn from the {@link MapView}. Ported from Stella
 * (https://github.com/Eclipse-5214/stella, LGPL-3.0, {@code features/stellanav/render/Clear.kt} and
 * {@code MapRenderer.kt}): 18 px rooms with 4 px connectors (22 px spacing, the whole map scaled 1.5x on the
 * Entrance, 1.2x on F1-F3), Stella's default room and door colours, a 138x138 background (black, alpha 100) with a
 * 2 px black border, a checkmark on the first tile of each room, the room name and secrets stacked at 0.75 scale in
 * the room's checkmark colour, player markers, and the "Secrets / Score / Deaths | M | P | Crypts" info lines.
 *
 * <p>Differences from Stella: its checkmark/marker/crown images are downloaded assets, so the checkmarks are drawn
 * as text glyphs and the default markers as small arrows; the boss-room image map, room guessing and the Paul /
 * prince crown are not drawn. Names and secrets follow Dungeons > Dungeon Map > Room Labels (off in Stella).
 */
public final class StellaMapStyle implements MapStyleRenderer {
    private static final int ROOM = 18;
    private static final int GAP = 4;
    private static final int SPACING = ROOM + GAP;
    private static final int HALF = ROOM / 2;
    private static final int MAP = 138;
    private static final int PAD = 5;
    private static final int BORDER = 2;
    private static final int INFO = 10; // Stella's "map info under": the box grows by 10 px
    private static final double TINT = 0.7;

    private static final int BACKGROUND = 0x64000000;
    private static final int BORDER_COLOR = 0xFF000000;
    private static final int DISCOVERED = argb(65, 65, 65);

    private static final int NORMAL = argb(107, 58, 17);
    private static final int PUZZLE = argb(117, 0, 133);
    private static final int TRAP = argb(216, 127, 51);
    private static final int MINIBOSS = argb(254, 223, 0);
    private static final int BLOOD = argb(255, 0, 0);
    private static final int FAIRY = argb(224, 0, 255);
    private static final int ENTRANCE = argb(20, 133, 0);
    private static final int UNKNOWN = argb(100, 100, 100);

    private static final int DOOR_NORMAL = argb(80, 40, 10);
    private static final int DOOR_WITHER = argb(0, 0, 0);
    private static final int DOOR_BLOOD = argb(255, 0, 0);
    private static final int DOOR_ENTRANCE = argb(0, 204, 0);

    private static final int GRAY = 0xFFAAAAAA;
    private static final int DARK_GRAY = 0xFF555555;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int AQUA = 0xFF55FFFF;

    private record Seg(String text, int color) {}

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int darken(int color) {
        int r = (int) (((color >> 16) & 0xFF) * TINT);
        int g = (int) (((color >> 8) & 0xFF) * TINT);
        int b = (int) ((color & 0xFF) * TINT);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    @Override
    public String id() {
        return "stella";
    }

    @Override
    public int width() {
        return MAP + PAD * 2;
    }

    @Override
    public int height() {
        return MAP + INFO + PAD * 2;
    }

    @Override
    public void render(GuiGraphicsExtractor g, MapView view, boolean preview) {
        DungeonsConfig.DungeonMap cfg = SkyblockerConfigManager.get().dungeons.dungeonMap;
        // background and border, like Stella (rect at the box's 5,5 inset, border drawn just outside it)
        int h = MAP + INFO;
        g.fill(PAD, PAD, PAD + MAP, PAD + h, BACKGROUND);
        g.fill(PAD - BORDER, PAD - BORDER, PAD + MAP + BORDER, PAD, BORDER_COLOR);
        g.fill(PAD - BORDER, PAD + h, PAD + MAP + BORDER, PAD + h + BORDER, BORDER_COLOR);
        g.fill(PAD - BORDER, PAD, PAD, PAD + h, BORDER_COLOR);
        g.fill(PAD + MAP, PAD, PAD + MAP + BORDER, PAD + h, BORDER_COLOR);

        Font font = Minecraft.getInstance().font;
        if (preview || view == null || !view.valid()) {
            if (preview) drawSample(g);
            else drawCentered(g, font, "Waiting for the dungeon map...", PAD + MAP / 2f, PAD + MAP / 2f - 4, 0.8f, GRAY);
            drawInfo(g, font, preview ? null : MapView.NO_RUN, preview);
            return;
        }

        int floor = floorNumber(view.floor());
        float scale = floor == 0 ? 1.5f : floor <= 3 ? 1.2f : 1f;
        float offset = floor == 1 ? 10.6f : 0f;

        g.pose().pushMatrix();
        g.pose().translate(PAD * 2 + offset, PAD * 2);
        g.pose().scale(scale, scale);
        drawRooms(g, view);
        drawDoors(g, view);
        drawCheckmarks(g, font, view);
        if (cfg.showRoomLabels) drawLabels(g, font, view);
        if (!view.inBoss()) drawHeads(g, view, cfg.fancyMap, cfg.showSelfHead);
        g.pose().popMatrix();

        drawInfo(g, font, view.run(), false);
    }

    private static int floorNumber(String floor) {
        if (floor == null || floor.isEmpty()) return 7;
        if (floor.equals("E")) return 0;
        try {
            return Integer.parseInt(floor.substring(1));
        } catch (NumberFormatException e) {
            return 7;
        }
    }

    // ---- rooms and doors ----------------------------------------------------------------------------------------

    private static int roomColor(MapView.Room room) {
        if (room.state() == MapView.RoomState.UNOPENED) return DISCOVERED;
        return switch (room.type()) {
            case ENTRANCE -> ENTRANCE;
            case PUZZLE -> PUZZLE;
            case TRAP -> TRAP;
            case MINIBOSS -> MINIBOSS;
            case FAIRY -> FAIRY;
            case BLOOD -> BLOOD;
            case UNKNOWN -> UNKNOWN;
            default -> NORMAL; // NORMAL and RARE share a colour in Stella
        };
    }

    private static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    private static boolean has(MapView.Room room, int col, int row) {
        for (int[] t : room.tiles()) if (t[0] == col && t[1] == row) return true;
        return false;
    }

    private static void drawRooms(GuiGraphicsExtractor g, MapView v) {
        for (MapView.Room room : v.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED) continue;
            int color = roomColor(room);
            for (int[] t : room.tiles()) {
                int px = t[0] * SPACING;
                int pz = t[1] * SPACING;
                rect(g, px, pz, ROOM, ROOM, color);
                if (has(room, t[0] + 1, t[1])) rect(g, px + ROOM, pz, GAP, ROOM, color);
                if (has(room, t[0], t[1] + 1)) rect(g, px, pz + ROOM, ROOM, GAP, color);
            }
            if (room.tiles().size() == 4 && room.maxCol() - room.minCol() == 1 && room.maxRow() - room.minRow() == 1) {
                rect(g, room.minCol() * SPACING + ROOM, room.minRow() * SPACING + ROOM, GAP, GAP, color);
            }
        }
    }

    private static void drawDoors(GuiGraphicsExtractor g, MapView v) {
        for (MapView.Door d : v.doors()) {
            int color;
            if (!d.locked() && d.type() != MapView.DoorType.ENTRANCE) {
                color = DOOR_NORMAL; // an opened door is a normal door
            } else {
                color = switch (d.type()) {
                    case WITHER -> DOOR_WITHER;
                    case BLOOD -> DOOR_BLOOD;
                    case ENTRANCE -> DOOR_ENTRANCE;
                    default -> DOOR_NORMAL;
                };
            }
            if (d.unopened()) color = darken(color);
            boolean vertical = d.gz() % 2 == 1; // gap between two rooms stacked above each other
            int cx = d.gx() / 2;
            int cz = d.gz() / 2;
            if (vertical) rect(g, cx * SPACING + 6, cz * SPACING + 18, 6, 4, color);
            else rect(g, cx * SPACING + 18, cz * SPACING + 6, 4, 6, color);
        }
    }

    // ---- checkmarks and text -----------------------------------------------------------------------------------

    private static int stateColor(MapView.RoomState s) {
        return switch (s) {
            case GREEN -> GREEN;
            case CLEARED -> WHITE;
            case FAILED -> RED;
            default -> GRAY;
        };
    }

    private static void drawCheckmarks(GuiGraphicsExtractor g, Font font, MapView v) {
        for (MapView.Room room : v.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED || room.type() == MapView.RoomType.ENTRANCE) continue;
            String glyph = switch (room.state()) {
                case CLEARED, GREEN -> "✔";
                case FAILED -> "✖";
                case UNOPENED -> "?";
                default -> null;
            };
            if (glyph == null) continue;
            int[] first = room.tiles().stream()
                    .min(Comparator.<int[]>comparingInt(t -> t[1]).thenComparingInt(t -> t[0])).orElse(null);
            if (first == null) continue;
            drawCentered(g, font, glyph, first[0] * SPACING + HALF, first[1] * SPACING + HALF - 4.4f, 1.2f, stateColor(room.state()));
        }
    }

    private static void drawLabels(GuiGraphicsExtractor g, Font font, MapView v) {
        for (MapView.Room room : v.rooms()) {
            if (room.state() == MapView.RoomState.UNDISCOVERED || room.state() == MapView.RoomState.UNOPENED) continue;
            MapView.RoomType t = room.type();
            if (t != MapView.RoomType.NORMAL && t != MapView.RoomType.RARE && t != MapView.RoomType.PUZZLE
                    && t != MapView.RoomType.TRAP && t != MapView.RoomType.MINIBOSS) continue;
            List<String> lines = new ArrayList<>();
            if (room.name() != null) for (String word : room.name().split(" ")) lines.add(word);
            if (room.secretsTotal() > 0) {
                int count = room.state() == MapView.RoomState.GREEN ? room.secretsTotal() : Math.max(0, room.secretsFound());
                lines.add(count + "/" + room.secretsTotal());
            }
            if (lines.isEmpty()) continue;
            float scale = 0.75f;
            float x = room.labelCol() * SPACING + HALF;
            float y = room.labelRow() * SPACING + HALF;
            float y0 = y - lines.size() * scale * 9f / 2f;
            int color = stateColor(room.state());
            for (int i = 0; i < lines.size(); i++) {
                drawCentered(g, font, lines.get(i), x, y0 + i * scale * 9f, scale, color);
            }
        }
    }

    /** Text centred on x, top at y, drawn at the given scale. */
    private static void drawCentered(GuiGraphicsExtractor g, Font font, String text, float x, float y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, color, false);
        g.pose().popMatrix();
    }

    // ---- players -----------------------------------------------------------------------------------------------

    private static void drawHeads(GuiGraphicsExtractor g, MapView v, boolean fancy, boolean showSelf) {
        int pitch = v.roomSize() + v.gap();
        for (MapView.Head head : v.heads()) {
            if (head.self() && !showSelf) continue;
            // map pixels -> Stella's room-slot space (22 px per slot) so the marker sits where Hypixel's is
            float x = (head.x() - v.startX()) / pitch * SPACING;
            float y = (head.y() - v.startY()) / pitch * SPACING;
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().rotate((float) Math.toRadians(head.yaw() + 180f));
            if (fancy && head.skin() != null) {
                g.fill(-6, -6, 6, 6, BORDER_COLOR);
                var texture = head.skin().body().texturePath();
                g.pose().scale(1.25f, 1.25f);
                g.pose().translate(-4, -4);
                g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 8f, 8f, 8, 8, 64, 64);
                g.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 40f, 8f, 8, 8, 64, 64);
            } else {
                int color = head.self() ? GREEN : head.classColor() >= 0 ? 0xFF000000 | head.classColor() : WHITE;
                drawArrow(g, BORDER_COLOR, 1);
                drawArrow(g, color, 0);
            }
            g.pose().popMatrix();
        }
    }

    /** A 7x10 arrow marker pointing up (Stella's default marker), grown by {@code grow} px for an outline. */
    private static void drawArrow(GuiGraphicsExtractor g, int color, int grow) {
        for (int r = 0; r < 6; r++) {
            int hw = Math.min(r, 3) + (r == 0 ? 0 : grow);
            g.fill(-hw - (r == 0 ? grow : 0), -5 + r - grow, hw + 1 + (r == 0 ? grow : 0), -4 + r, color);
        }
        g.fill(-1 - grow, 1, 2 + grow, 5 + grow, color);
    }

    // ---- info and preview -----------------------------------------------------------------------------------

    private void drawSample(GuiGraphicsExtractor g) {
        int ox = PAD * 2 + 10;
        int oy = PAD * 2 + 10;
        rect(g, ox, oy, ROOM, ROOM, ENTRANCE);
        rect(g, ox + SPACING, oy + 6, GAP, 6, DOOR_NORMAL);
        rect(g, ox + SPACING, oy, ROOM, ROOM, NORMAL);
        rect(g, ox + SPACING * 2 - 4, oy + 6, GAP, 6, DOOR_NORMAL);
        rect(g, ox + SPACING * 2, oy, ROOM, ROOM, PUZZLE);
        rect(g, ox + SPACING, oy + ROOM, ROOM, GAP, TRAP);
        rect(g, ox + SPACING, oy + SPACING, ROOM, ROOM, TRAP);
        rect(g, ox, oy + SPACING, ROOM, ROOM, BLOOD);
    }

    /** Stella's "map info under" lines: secrets and score on top, deaths / mimic / prince / crypts below. */
    private static void drawInfo(GuiGraphicsExtractor g, Font font, MapView.RunInfo run, boolean placeholder) {
        List<Seg> secrets = new ArrayList<>();
        List<Seg> score = new ArrayList<>();
        List<Seg> bottom = new ArrayList<>();
        if (placeholder || run == null) {
            secrets.add(new Seg("Secrets: ", GRAY));
            secrets.add(new Seg("?", AQUA));
            secrets.add(new Seg("-", DARK_GRAY));
            secrets.add(new Seg("?", YELLOW));
            secrets.add(new Seg("-", DARK_GRAY));
            secrets.add(new Seg("?", RED));
            score.add(new Seg("Score: ", GRAY));
            score.add(new Seg("0", RED));
            bottom.add(new Seg("Deaths: ", GRAY));
            bottom.add(new Seg("0", GREEN));
            bottom.add(new Seg(" | ", DARK_GRAY));
            bottom.add(new Seg("M: ", GRAY));
            bottom.add(new Seg("✘", RED));
            bottom.add(new Seg(" | ", DARK_GRAY));
            bottom.add(new Seg("P: ", GRAY));
            bottom.add(new Seg("✘", RED));
            bottom.add(new Seg(" | ", DARK_GRAY));
            bottom.add(new Seg("Crypts: ", GRAY));
            bottom.add(new Seg("0", RED));
        } else {
            int found = Math.max(0, run.secretsFound());
            int left = run.secretsNeeded() < 0 ? -1 : Math.max(0, run.secretsNeeded() - found);
            secrets.add(new Seg("Secrets: ", GRAY));
            secrets.add(new Seg(String.valueOf(found), AQUA));
            secrets.add(new Seg("-", DARK_GRAY));
            secrets.add(new Seg(left < 0 ? "?" : String.valueOf(left), YELLOW));
            secrets.add(new Seg("-", DARK_GRAY));
            secrets.add(new Seg(run.secretsTotal() < 0 ? "?" : String.valueOf(run.secretsTotal()), RED));
            int s = Math.max(0, run.score());
            score.add(new Seg("Score: ", GRAY));
            score.add(new Seg(String.valueOf(s), s >= 300 ? GREEN : s >= 270 ? YELLOW : RED));
            bottom.add(new Seg("Deaths: ", GRAY));
            bottom.add(new Seg(String.valueOf(Math.max(0, run.deaths())), run.deaths() > 0 ? RED : GREEN));
            bottom.add(new Seg(" | ", DARK_GRAY));
            bottom.add(new Seg("M: ", GRAY));
            bottom.add(new Seg(run.mimicKilled() ? "✔" : "✘", run.mimicKilled() ? GREEN : RED));
            bottom.add(new Seg(" | ", DARK_GRAY));
            bottom.add(new Seg("P: ", GRAY));
            bottom.add(new Seg(run.princeKilled() ? "✔" : "✘", run.princeKilled() ? GREEN : RED));
            bottom.add(new Seg(" | ", DARK_GRAY));
            bottom.add(new Seg("Crypts: ", GRAY));
            int c = Math.max(0, run.crypts());
            bottom.add(new Seg(String.valueOf(c), c >= 5 ? GREEN : c > 0 ? YELLOW : RED));
        }
        int bw = width(font, bottom);
        int sw = width(font, score);
        g.pose().pushMatrix();
        g.pose().translate(PAD + MAP / 2f, PAD + MAP - 3f);
        g.pose().scale(0.6f, 0.6f);
        float left = -bw / 2f;
        drawSegs(g, font, secrets, left, 0);
        drawSegs(g, font, score, bw / 2f - sw, 0);
        drawSegs(g, font, bottom, left, 10);
        g.pose().popMatrix();
    }

    private static int width(Font font, List<Seg> segs) {
        int w = 0;
        for (Seg s : segs) w += font.width(s.text());
        return w;
    }

    private static void drawSegs(GuiGraphicsExtractor g, Font font, List<Seg> segs, float x, float y) {
        for (Seg s : segs) {
            g.text(font, s.text(), Math.round(x), Math.round(y), s.color(), false);
            x += font.width(s.text());
        }
    }
}
