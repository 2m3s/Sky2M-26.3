package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Custom Mage Beam, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod,
 * dungeonextras/MageBeamFeature), a port of NoammAddons' MageBeam (https://github.com/Noamm9/NoammAddons). The beam
 * arrives as firework particles about half a block apart along a straight line, within a tick of each other: each
 * point in line with the last beam (and close to its end) extends it, anything else starts a new one. Hypixel sends
 * every beam twice, so repeated points are skipped. A beam of 6 or more points is drawn as one line, start to end.
 */
public final class MageBeam {
    private static final double POINT_SPACE = 0.5;
    private static final int MIN_POINTS = 6;
    private static final float WIDTH_BLOCKS = 0.05f;

    private static final List<Beam> BEAMS = new ArrayList<>();
    private static long tick;

    private static final class Beam {
        final List<Vec3> points = new ArrayList<>();
        final long createdTick, createdMs = System.currentTimeMillis();
        long updateTick;

        Beam(Vec3 point, long tick) {
            points.add(point);
            createdTick = updateTick = tick;
        }

        Vec3 start() {
            return points.getFirst();
        }

        Vec3 end() {
            return points.getLast();
        }

        boolean inLine(Vec3 point) {
            if (points.size() < 2) return true;
            Vec3 axis = end().subtract(start()).normalize();
            Vec3 toPoint = point.subtract(end()).normalize();
            return Math.abs(axis.dot(toPoint)) > 0.99 && point.distanceToSqr(end()) < POINT_SPACE * POINT_SPACE;
        }
    }

    private MageBeam() {}

    private static FeatureConfigs.MageBeam config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.mageBeam;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null) {
                BEAMS.clear();
                return;
            }
            tick++;
            FeatureConfigs.MageBeam c = config();
            int duration = c == null ? 40 : c.durationTicks;
            BEAMS.removeIf(beam -> tick - beam.createdTick >= duration);
        });
        Sky2MWorldRender.register(MageBeam::render);
    }

    /** From the particle packet handler: true to hide the particle. */
    public static boolean onParticle(ClientboundLevelParticlesPacket packet) {
        FeatureConfigs.MageBeam c = config();
        if (c == null || !c.enabled || !Sky2MLocation.inDungeon() || packet.particle() == null
            || packet.particle().getType() != ParticleTypes.FIREWORK) return false;
        Minecraft.getInstance().execute(() -> point(new Vec3(packet.x(), packet.y(), packet.z())));
        return c.hideParticles;
    }

    private static void point(Vec3 point) {
        for (Beam beam : BEAMS) if (beam.points.contains(point)) return;
        Beam last = BEAMS.isEmpty() ? null : BEAMS.getLast();
        if (last != null && tick - last.updateTick <= 1 && last.inLine(point)) {
            last.points.add(point);
            last.updateTick = tick;
        } else {
            BEAMS.add(new Beam(point, tick));
        }
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.MageBeam c = config();
        if (c == null || !c.enabled || BEAMS.isEmpty()) return;
        int argb = ChromaColours.parse(c.colour).getEffectiveColourRGB();
        float[] rgb = {(argb >> 16 & 0xFF) / 255f, (argb >> 8 & 0xFF) / 255f, (argb & 0xFF) / 255f};
        float baseAlpha = (argb >>> 24) / 255f;
        long duration = c.durationTicks * 50L, now = System.currentTimeMillis();
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        Iterator<Beam> it = BEAMS.iterator();
        while (it.hasNext()) {
            Beam beam = it.next();
            if (beam.points.size() < MIN_POINTS) continue;
            float alpha = baseAlpha;
            if (c.fade) alpha *= 1f - Math.clamp((now - beam.createdMs) / (float) duration, 0f, 1f);
            if (alpha <= 0.01f) continue;
            Vec3 start = beam.start(), end = beam.end();
            // A flat strip facing the camera, so the width counts (a GL line is always 1 pixel), with a line down its middle.
            Vec3 axis = end.subtract(start);
            Vec3 side = axis.cross(start.add(end).scale(0.5).subtract(camera));
            if (side.lengthSqr() < 1e-6) side = axis.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() > 1e-6) {
                Vec3 half = side.normalize().scale(Math.max(0.01f, c.width * WIDTH_BLOCKS) * 0.5);
                collector.submitQuad(new Vec3[]{start.subtract(half), end.subtract(half), end.add(half), start.add(half)}, rgb, alpha, false);
                collector.submitQuad(new Vec3[]{start.add(half), end.add(half), end.subtract(half), start.subtract(half)}, rgb, alpha, false);
            }
            collector.submitLinesFromPoints(new Vec3[]{start, end}, rgb, alpha, 2f, false);
        }
    }
}
