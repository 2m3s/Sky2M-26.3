package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A trail of flat squares behind you: one per tick while you move, in one colour on the ground and another in the air,
 * fading towards the tail and after a set time. Nothing is drawn while you stand still. Purely visual.
 * <p>
 * Ported from Killer560's Mod (trail/TrailFeature), MIT License, Copyright (c) 2026 Killer560. Their trail only had a
 * length; the colours and the lifetime are Sky2M settings.
 */
public final class Trail {
    public static final int MAX_LENGTH = 100;
    /** Thick enough not to z-fight with the ground. */
    private static final float THICKNESS = 0.04f;
    /** Moving less than this (blocks, squared) a tick counts as standing still, so idle jitter leaves nothing. */
    private static final double STILL_SQ = 0.02 * 0.02;

    // A ring buffer of the newest points, in parallel arrays so ticking and drawing never allocate.
    private static final double[] XS = new double[MAX_LENGTH];
    private static final double[] YS = new double[MAX_LENGTH];
    private static final double[] ZS = new double[MAX_LENGTH];
    private static final boolean[] GROUNDED = new boolean[MAX_LENGTH];
    private static final long[] BORN = new long[MAX_LENGTH];
    private static int head;
    private static int size;
    private static long ticks;
    private static Vec3 last;
    private static ClientLevel lastLevel;

    private Trail() {}

    private static FeatureConfigs.Trail config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.trail;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(Trail::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> clear());
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.Trail c = config();
            if (c == null || !c.enabled || size == 0) return;
            int visible = Math.min(size, c.length);
            float half = c.size / 2f;
            int lifetime = c.lifetimeSeconds * 20;
            int ground = ChromaColours.parse(c.groundColour).getEffectiveColourRGB();
            int air = ChromaColours.parse(c.airColour).getEffectiveColourRGB();
            for (int i = 0; i < visible; i++) {
                int idx = Math.floorMod(head - 1 - i, MAX_LENGTH);
                long age = ticks - BORN[idx];
                if (lifetime > 0 && age >= lifetime) break; // older points only get older
                int argb = GROUNDED[idx] ? ground : air;
                float alpha = (argb >>> 24) / 255f;
                if (c.fadeOut && visible > 1) alpha *= 1f - (float) i / (visible - 1);
                if (lifetime > 0) alpha *= 1f - (float) age / lifetime;
                if (alpha <= 0.01f) continue;
                float[] rgb = {((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f};
                AABB box = new AABB(XS[idx] - half, YS[idx], ZS[idx] - half, XS[idx] + half, YS[idx] + THICKNESS, ZS[idx] + half);
                collector.submitFilledBox(box, rgb, alpha, false);
            }
        });
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.level != lastLevel) {
            // A new world: the old squares belong somewhere else.
            clear();
            lastLevel = mc.level;
        }
        FeatureConfigs.Trail c = config();
        if (c == null || !c.enabled || mc.player == null) {
            clear();
            return;
        }
        Vec3 pos = mc.player.position();
        if (last != null && pos.distanceToSqr(last) >= STILL_SQ) {
            XS[head] = pos.x;
            YS[head] = pos.y;
            ZS[head] = pos.z;
            GROUNDED[head] = mc.player.onGround();
            BORN[head] = ticks;
            head = (head + 1) % MAX_LENGTH;
            if (size < MAX_LENGTH) size++;
        }
        last = pos;
    }

    private static void clear() {
        head = 0;
        size = 0;
        last = null;
    }
}
