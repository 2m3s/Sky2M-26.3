package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.DianaBurrows;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyHanni's Fishing Hotspot Radar guess (features/fishing/FishingHotspotRadar.kt): right-click a Hotspot Radar and the
 * trail of flame particles it shows is fitted with a curve and followed to where the hotspot is, marked with a label,
 * its distance and a line from your crosshair. The fit is the one the Diana spade guess uses.
 */
public final class FishingHotspotRadar {
    private static final float[] PINK = {1f, 0.33f, 1f};

    private static final List<Vec3> points = new ArrayList<>();
    private static long lastUse;
    private static Vec3 hotspot;

    private FishingHotspotRadar() {}

    public static void init() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && enabled() && Compat.neuName(player.getItemInHand(hand)).equals("HOTSPOT_RADAR")) {
                points.clear();
                lastUse = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MWorldRender.register(FishingHotspotRadar::render);
    }

    private static boolean enabled() {
        FishingExtras.Config c = FishingExtras.config();
        return c != null && c.guessHotspotRadar && Compat.isOnSkyblock();
    }

    private static void reset() {
        points.clear();
        hotspot = null;
        lastUse = 0;
    }

    /** Every particle packet (Sky2MSlayerPacketsMixin); never hides them. */
    public static void onParticle(ClientboundLevelParticlesPacket p) {
        if (p.particle().getType() != ParticleTypes.FLAME || p.count() != 1 || p.xMaxSpeed() != 0f) return;
        if (!enabled() || System.currentTimeMillis() - lastUse > 1_000L) return;
        Vec3 location = new Vec3(p.x(), p.y(), p.z());
        if (points.isEmpty()) {
            points.add(location);
            return;
        }
        // The trail's points are a few blocks apart; Hypixel repeats them, and a repeat would stall the curve.
        if (points.get(points.size() - 1).distanceTo(location) > 3) return;
        for (Vec3 seen : points) if (seen.distanceToSqr(location) < 0.0001) return;
        points.add(location);
        Vec3 guess = DianaBurrows.guessFromPoints(points);
        Minecraft mc = Minecraft.getInstance();
        if (guess == null || mc.player == null || !Double.isFinite(guess.x) || !Double.isFinite(guess.y) || !Double.isFinite(guess.z)) return;
        // A bad fit can land far off the map.
        if (guess.distanceTo(mc.player.position()) > 1_500) {
            hotspot = null;
            return;
        }
        hotspot = guess;
    }

    private static void render(PrimitiveCollector collector) {
        FishingExtras.Config c = FishingExtras.config();
        Minecraft mc = Minecraft.getInstance();
        if (hotspot == null || c == null || !c.guessHotspotRadar || mc.player == null) return;
        double distance = hotspot.distanceTo(mc.player.getEyePosition());
        if (distance <= 10) {
            // You're there: the guess has done its job.
            hotspot = null;
            points.clear();
            return;
        }
        if (c.lineToHotspot) collector.submitLineFromCursor(hotspot, PINK, 1f, 3f);
        float scale = (float) Math.max(1, Math.min(6, distance / 10));
        collector.submitText(Component.literal("HOTSPOT").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), hotspot.add(0, 1.7, 0), scale, true);
        collector.submitText(Component.literal((int) distance + "m").withStyle(ChatFormatting.YELLOW), hotspot.add(0, 1.2, 0), scale * 0.6f, true);
    }
}
