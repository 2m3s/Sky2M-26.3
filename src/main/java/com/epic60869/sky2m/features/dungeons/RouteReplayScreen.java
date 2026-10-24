package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.dungeons.RouteReplay.Event;
import com.epic60869.sky2m.features.dungeons.RouteReplay.Layout;
import com.epic60869.sky2m.features.dungeons.RouteReplay.Replay;
import com.epic60869.sky2m.sb.utils.render.GuiHelper;
import com.epic60869.sky2m.sim.livemap.LiveMapFeature;
import com.epic60869.sky2m.sim.livemap.MapPainter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Route Replay: a recorded run drawn on its dungeon map, with your head moving along the path, the trail left in the
 * rooms behind it, play/pause, 1x/2x/4x/8x, a scrub bar and the run clock.
 */
public final class RouteReplayScreen extends Screen {
    private static final int EDGE = 0xFF3B465B, GOLD = 0xFFFFD35A, TEXT = 0xFF9AA5B8, BUTTON = 0xFF2A3345, SELECTED = 0xFF3B5B8B;
    private static final int[] SPEEDS = {1, 2, 4, 8};
    private static final int MAP_UNITS = 116;

    private final Replay replay;
    private final Screen parent;
    private double time;
    private boolean playing = true, scrubbing;
    private int speed = 1;
    private long lastNanos;
    private int barX, barY, barW;
    private final List<int[]> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    public RouteReplayScreen(Replay replay, Screen parent) {
        super(Component.literal("Route Replay"));
        this.replay = replay;
        this.parent = parent;
    }

    private long duration() {
        return replay.t.length == 0 ? 0 : replay.t[replay.t.length - 1];
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean selected, int mx, int my, Runnable action) {
        boolean over = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, selected ? SELECTED : over ? 0xFF33405A : BUTTON);
        g.outline(x, y, w, h, EDGE);
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, selected ? GOLD : 0xFFFFFFFF);
        buttons.add(new int[]{x, y, w, h});
        actions.add(action);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        buttons.clear();
        actions.clear();
        long nowN = System.nanoTime();
        if (playing && lastNanos != 0 && !scrubbing) time += (nowN - lastNanos) / 1_000_000.0 * speed;
        lastNanos = nowN;
        long dur = duration();
        if (time >= dur) {
            time = dur;
            playing = false;
        }
        if (time < 0) time = 0;

        g.centeredText(font, "Route Replay  " + (replay.floor == null ? "" : replay.floor) + (replay.sim ? " (Dungeon Sim)" : ""), width / 2, 6, GOLD);

        int top = 20, bottomArea = 52;
        int size = Math.max(60, Math.min(height - top - bottomArea, width - 20));
        int ox = (width - size) / 2, oy = top;
        float ppu = size / (float) MAP_UNITS;
        g.fill(ox - 2, oy - 2, ox + size + 2, oy + size + 2, 0xC0121722);
        g.outline(ox - 2, oy - 2, size + 4, size + 4, EDGE);
        if (replay.layout != null) drawLayout(g, replay.layout, ox, oy, ppu);
        else g.centeredText(font, "No floor layout was saved with this run", width / 2, oy + size / 2, TEXT);

        // Trail up to now, then events, then the head.
        int idx = indexAt(time);
        int lastPx = Integer.MIN_VALUE, lastPy = 0;
        for (int i = 0; i <= idx && i < replay.t.length; i++) {
            int px = ox + Math.round((float) unit(replay.x[i], 'x') * ppu), py = oy + Math.round((float) unit(replay.z[i], 'z') * ppu);
            if (px < ox - 4 || py < oy - 4 || px > ox + size + 4 || py > oy + size + 4) continue;
            if (px == lastPx && py == lastPy) continue;
            lastPx = px;
            lastPy = py;
            g.fill(px - 1, py - 1, px + 1, py + 1, 0xFF55E0FF);
        }
        for (Event e : replay.events) {
            if (e.t > time) continue;
            int px = ox + Math.round((float) unit(e.x, 'x') * ppu), py = oy + Math.round((float) unit(e.z, 'z') * ppu);
            g.fill(px - 2, py - 2, px + 3, py + 3, "death".equals(e.kind) ? 0xFFFF5555 : 0xFFFFD35A);
        }
        double[] pos = positionAt(time);
        double ux = unit(pos[0], 'x'), uz = unit(pos[1], 'z');
        boolean onMap = ux >= -6 && uz >= -6 && ux <= MAP_UNITS + 6 && uz <= MAP_UNITS + 6;
        if (onMap) {
            int head = Math.max(8, Math.round(9 * Math.max(1f, size / 200f)));
            int hx = ox + Math.round((float) ux * ppu) - head / 2, hy = oy + Math.round((float) uz * ppu) - head / 2;
            g.outline(hx - 1, hy - 1, head + 2, head + 2, 0xFFFFFFFF);
            Minecraft mc = Minecraft.getInstance();
            UUID id = mc.player != null ? mc.player.getUUID() : mc.getUser().getProfileId();
            GuiHelper.playerHead(g, hx, hy, head, id);
        } else {
            g.centeredText(font, "Off the map (boss room)", width / 2, oy + size + 4, TEXT);
        }

        // Latest event line.
        Event last = null;
        for (Event e : replay.events) if (e.t <= time) last = e;
        if (last != null) {
            String line = ("death".equals(last.kind) ? "Death" : "Secret") + (last.room == null ? "" : " in " + last.room) + " at " + clock(last.t);
            g.text(font, font.plainSubstrByWidth(line, width / 2 - 12), 8, 8, "death".equals(last.kind) ? 0xFFFF5555 : GOLD, true);
        }

        // Controls.
        int cy = height - 42;
        button(g, ox, cy, 48, 14, playing ? "Pause" : (time >= dur ? "Again" : "Play"), false, mx, my, () -> {
            if (time >= dur) {
                time = 0;
                playing = true;
            } else {
                playing = !playing;
            }
            lastNanos = 0;
        });
        for (int i = 0; i < SPEEDS.length; i++) {
            int s = SPEEDS[i];
            button(g, ox + 54 + i * 30, cy, 28, 14, s + "x", s == speed, mx, my, () -> speed = s);
        }
        button(g, ox + size - 48, cy, 48, 14, "Close", false, mx, my, this::onClose);
        g.text(font, clock((long) time) + " / " + clock(dur), ox + 54 + SPEEDS.length * 30 + 6, cy + 3, 0xFFFFFFFF, true);

        barX = ox;
        barW = size;
        barY = height - 20;
        g.fill(barX, barY, barX + barW, barY + 8, 0xFF0C0F16);
        g.outline(barX, barY, barW, 8, EDGE);
        if (dur > 0) {
            for (Event e : replay.events) {
                int ex = barX + (int) (barW * (e.t / (double) dur));
                g.fill(ex, barY, ex + 1, barY + 8, "death".equals(e.kind) ? 0xFFFF5555 : 0x99FFD35A);
            }
            int fill = (int) (barW * (time / dur));
            g.fill(barX + 1, barY + 1, barX + Math.max(1, fill), barY + 7, 0x8055E0FF);
            g.fill(barX + fill - 1, barY - 2, barX + fill + 1, barY + 10, 0xFFFFFFFF);
        }
    }

    private void drawLayout(GuiGraphicsExtractor g, Layout l, int ox, int oy, float ppu) {
        for (int idx = 0; idx < l.roomOf.length; idx++) {
            int gx = idx % 11, gz = idx / 11, room = l.roomOf[idx];
            if (room < 0) {
                if (l.doors[idx] > 0) {
                    float[] d = MapPainter.doorUnits(gx, gz);
                    int c = switch (l.doors[idx]) {
                        case 2 -> 0xFF3A3A3A;
                        case 3 -> 0xFFB02020;
                        case 4 -> 0xFF1F9D3A;
                        default -> 0xFF8C6A3C;
                    };
                    g.fill(ox + Math.round(d[0] * ppu), oy + Math.round(d[1] * ppu), ox + Math.round((d[0] + d[2]) * ppu), oy + Math.round((d[1] + d[3]) * ppu), c);
                }
                continue;
            }
            int x0 = cellPos(gx), z0 = cellPos(gz), s = gx % 2 == 0 ? 16 : 4, sh = gz % 2 == 0 ? 16 : 4;
            g.fill(ox + Math.round(x0 * ppu), oy + Math.round(z0 * ppu), ox + Math.round((x0 + s) * ppu), oy + Math.round((z0 + sh) * ppu), color(l.types[room]));
        }
        if (ppu < 2.2f) return;
        for (int r = 0; r < l.names.length; r++) {
            String name = l.names[r];
            if (name == null || "Unknown".equals(name) || "UNKNOWN".equals(l.types[r])) continue;
            float cx = ox + (l.labelGX[r] * 10f + 8f) * ppu, cz = oy + (l.labelGZ[r] * 10f + 8f) * ppu;
            float sc = 0.5f;
            g.pose().pushMatrix();
            g.pose().translate(cx, cz);
            g.pose().scale(sc, sc);
            g.centeredText(font, font.plainSubstrByWidth(name, (int) (30 * ppu / sc)), 0, -4, 0xFFFFFFFF);
            g.pose().popMatrix();
        }
    }

    private static int cellPos(int g) {
        return (g / 2) * 20 + (g % 2 == 1 ? 16 : 0);
    }

    private static int color(String type) {
        return switch (type == null ? "" : type) {
            case "ENTRANCE" -> 0xFF1F9D3A;
            case "PUZZLE" -> 0xFF8B3FB0;
            case "TRAP" -> 0xFFD7762B;
            case "CHAMPION", "MINIBOSS", "YELLOW" -> 0xFFD6C23A;
            case "BLOOD" -> 0xFFB02020;
            case "FAIRY" -> 0xFFE78CC8;
            case "RARE" -> 0xFFB0A070;
            case "UNKNOWN" -> 0xFF4A4F5A;
            default -> 0xFF7A5A32;
        };
    }

    /** World coordinate to map units (the same mapping the live map uses). */
    private static double unit(double world, char axis) {
        return (world - LiveMapFeature.START_X) * 0.625 + 8;
    }

    private int indexAt(double t) {
        int lo = 0, hi = replay.t.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (replay.t[mid] <= t) {
                ans = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return ans;
    }

    private double[] positionAt(double t) {
        int i = indexAt(t);
        if (i >= replay.t.length - 1) return new double[]{replay.x[i], replay.z[i]};
        double span = replay.t[i + 1] - replay.t[i];
        double f = span <= 0 ? 0 : Math.clamp((t - replay.t[i]) / span, 0, 1);
        // A long gap means they stood still (or were off), so don't glide across it.
        if (span > 1500) f = 0;
        return new double[]{replay.x[i] + (replay.x[i + 1] - replay.x[i]) * f, replay.z[i] + (replay.z[i + 1] - replay.z[i]) * f};
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private void scrubTo(double mouseX) {
        time = Math.clamp((mouseX - barX) / barW, 0, 1) * duration();
        lastNanos = 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() >= barX && event.x() < barX + barW && event.y() >= barY - 3 && event.y() < barY + 11) {
            scrubbing = true;
            scrubTo(event.x());
            return true;
        }
        for (int i = 0; i < buttons.size(); i++) {
            int[] b = buttons.get(i);
            if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
                actions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (scrubbing) {
            scrubTo(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrubbing = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_SPACE) {
            playing = !playing;
            lastNanos = 0;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
