package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Voidgloom Seraph helpers, ported from SkyHanni (https://github.com/hannibal002/SkyHanni, LGPL-2.1):
 * EndermanSlayerFeatures (Yang Glyph and Nukekubi skull highlights), EndermanSlayerHideParticles and
 * LineToVoidgloomSeraph. The phase numbers are in {@link SlayerFeatures}.
 */
public final class EndermanSlayer {
    /** The Nukekubi Fixation skull's texture (SkyHanni-REPO constants/Skulls.json, MOB_NUKEKUBI). */
    private static final String NUKEKUBI_TEXTURE = "eb07594e2df273921a77c101d0bfdfa1115abed5b9b2029eb496ceba9bdbb4b3";
    /** A Yang Glyph explodes five seconds after it lands. */
    private static final long BEACON_FUSE_MS = 5_000L;

    private static final Set<Enderman> endermenWithBeacons = new HashSet<>();
    private static final Set<ArmorStand> flyingBeacons = new HashSet<>();
    private static final Set<ArmorStand> nukekubiSkulls = new HashSet<>();
    private static final Map<BlockPos, Long> sittingBeacons = new HashMap<>();
    /** Entities already checked for a Nukekubi texture (the check decodes the skull's texture). */
    private static final Set<Integer> checkedSkulls = new HashSet<>();

    private EndermanSlayer() {}

    private static FeatureConfigs.EndermanSlayer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.enderman;
    }

    private static boolean inEnd() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("The End");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(EndermanSlayer::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MWorldRender.register(collector -> render(collector));
        EntityGlow.register(entity -> {
            FeatureConfigs.EndermanSlayer config = config();
            if (config == null || !(entity instanceof ArmorStand stand)) return -1;
            if (config.highlightBeacon && flyingBeacons.contains(stand)) return rgb(config.beaconColor);
            if (config.highlightNukekubi && nukekubiSkulls.contains(stand)) return 0xFFAA00;
            return -1;
        });
    }

    private static void reset() {
        endermenWithBeacons.clear();
        flyingBeacons.clear();
        nukekubiSkulls.clear();
        sittingBeacons.clear();
        checkedSkulls.clear();
    }

    /** SlayerFeatures only finds your boss while something needs it. */
    static boolean needsBoss() {
        FeatureConfigs.EndermanSlayer config = config();
        return config != null && (config.lineToBoss || config.phaseDisplay);
    }

    static boolean phaseNumbers() {
        FeatureConfigs.EndermanSlayer config = config();
        return config != null && config.phaseDisplay;
    }

    private static boolean beaconFeatures(FeatureConfigs.EndermanSlayer config) {
        return config.highlightBeacon || config.beaconWarning || config.beaconLine;
    }

    private static void tick(Minecraft mc) {
        FeatureConfigs.EndermanSlayer config = config();
        if (config == null || mc.level == null || mc.player == null || !inEnd()) {
            if (!flyingBeacons.isEmpty() || !nukekubiSkulls.isEmpty() || !sittingBeacons.isEmpty()) reset();
            return;
        }
        boolean beacons = beaconFeatures(config);
        boolean skulls = config.highlightNukekubi || config.lineToNukekubi;
        if (!beacons && !skulls) return;

        endermenWithBeacons.removeIf(e -> !e.isAlive() || !hasBeacon(e));
        flyingBeacons.removeIf(e -> !e.isAlive());
        nukekubiSkulls.removeIf(e -> !e.isAlive());

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity.distanceToSqr(mc.player) > 40 * 40) continue;
            if (beacons && entity instanceof Enderman enderman && !endermenWithBeacons.contains(enderman)
                && hasBeacon(enderman) && canSee(mc, enderman, 15)) {
                endermenWithBeacons.add(enderman);
            }
            if (!(entity instanceof ArmorStand stand)) continue;
            ItemStack helmet = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (helmet.isEmpty()) continue;
            if (beacons && !flyingBeacons.contains(stand) && helmet.getHoverName().getString().equals("Beacon")
                && canSee(mc, stand, 15)) {
                flyingBeacons.add(stand);
                if (config.beaconWarning) {
                    mc.gui.hud.setTimes(0, 40, 5);
                    mc.gui.hud.setTitle(Component.literal("Beacon").withStyle(ChatFormatting.DARK_RED));
                    mc.gui.hud.setSubtitle(Component.empty());
                }
            }
            if (skulls && checkedSkulls.add(stand.getId()) && isNukekubi(helmet)) nukekubiSkulls.add(stand);
        }

        if (beacons) updateSittingBeacons(mc);
    }

    /**
     * A thrown Yang Glyph lands as a beacon block: SkyHanni swaps the flying one for the block when the block
     * appears within three blocks of it. The block is dropped when it's gone, or after seven seconds at most.
     */
    private static void updateSittingBeacons(Minecraft mc) {
        long now = System.currentTimeMillis();
        for (ArmorStand stand : new ArrayList<>(flyingBeacons)) {
            BlockPos landed = findBeaconBlock(mc, stand.blockPosition());
            if (landed == null || sittingBeacons.containsKey(landed)) continue;
            flyingBeacons.remove(stand);
            sittingBeacons.put(landed, now);
        }
        sittingBeacons.entrySet().removeIf(e -> now - e.getValue() > 7_000L
            || !mc.level.getBlockState(e.getKey()).is(Blocks.BEACON));
    }

    private static BlockPos findBeaconBlock(Minecraft mc, BlockPos around) {
        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-2, -2, -2), around.offset(2, 2, 2))) {
            if (mc.level.getBlockState(pos).is(Blocks.BEACON)) return pos.immutable();
        }
        return null;
    }

    private static boolean hasBeacon(Enderman enderman) {
        var carried = enderman.getCarriedBlock();
        return carried != null && carried.is(Blocks.BEACON);
    }

    private static boolean canSee(Minecraft mc, Entity entity, double range) {
        return entity.distanceToSqr(mc.player) <= range * range && mc.player.hasLineOfSight(entity);
    }

    private static boolean isNukekubi(ItemStack helmet) {
        String texture = Compat.getHeadTexture(helmet);
        if (texture.isEmpty()) return false;
        try {
            return new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8).contains(NUKEKUBI_TEXTURE);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void render(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector) {
        FeatureConfigs.EndermanSlayer config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || mc.player == null || !Sky2MLocation.onSkyblock()) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);

        if (config.lineToBoss) {
            LivingEntity boss = SlayerFeatures.boss();
            if (boss instanceof Enderman && canSee(mc, boss, 30)) {
                collector.submitLineFromCursor(boss.getPosition(partial).add(0, 1, 0), new float[]{0.33f, 1f, 1f}, 1f, config.bossLineWidth);
            }
        }
        if (!inEnd()) return;

        float[] beaconColour = colour(config.beaconColor);
        float[] lineColour = colour(config.beaconLineColor);
        if (config.highlightBeacon) {
            for (Enderman enderman : endermenWithBeacons) {
                Vec3 pos = enderman.getPosition(partial);
                collector.submitFilledBox(new AABB(pos.x - 0.5, pos.y + 0.2, pos.z - 0.5, pos.x + 0.5, pos.y + 1.2, pos.z + 0.5), beaconColour, 0.5f, false);
            }
        }
        for (ArmorStand beacon : flyingBeacons) {
            if (!canSee(mc, beacon, 40)) continue;
            Vec3 pos = beacon.getPosition(partial);
            if (config.highlightBeacon) collector.submitText(Component.literal("Beacon").withStyle(ChatFormatting.DARK_RED), pos.add(0, 2.5, 0), 1.8f, true);
            if (config.beaconLine) collector.submitLineFromCursor(pos.add(0, 2, 0), lineColour, lineColour[3], config.beaconLineWidth);
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, Long> entry : sittingBeacons.entrySet()) {
            BlockPos pos = entry.getKey();
            if (Vec3.atCenterOf(pos).distanceToSqr(mc.player.position()) > 20 * 20) continue;
            if (config.beaconLine) collector.submitLineFromCursor(Vec3.atCenterOf(pos), lineColour, lineColour[3], config.beaconLineWidth);
            if (config.highlightBeacon) {
                double left = Math.max(0, BEACON_FUSE_MS - (now - entry.getValue())) / 1000.0;
                collector.submitFilledBoxWithBeaconBeam(pos, beaconColour, 1f, true);
                collector.submitText(Component.literal(String.format(Locale.US, "Beacon %.1fs", left)).withStyle(ChatFormatting.DARK_RED),
                    Vec3.atCenterOf(pos).add(0, 1.5, 0), 1.8f, true);
            }
        }
        for (ArmorStand skull : nukekubiSkulls) {
            Vec3 pos = skull.getPosition(partial);
            if (config.highlightNukekubi && skull.distanceToSqr(mc.player) <= 20 * 20) {
                collector.submitText(Component.literal("Nukekubi Skull").withStyle(ChatFormatting.GOLD), pos.add(0, 2.5, 0), 1.6f, false);
            }
            if (config.lineToNukekubi && canSee(mc, skull, 20)) {
                collector.submitLineFromCursor(pos.add(0, 2, 0), new float[]{1f, 0.67f, 0f}, 1f, 3f);
            }
        }
    }

    /** Hide Particles: smoke, flame and witch particles within three blocks of an enderman in The End. */
    public static boolean hideParticle(ClientboundLevelParticlesPacket packet) {
        FeatureConfigs.EndermanSlayer config = config();
        if (config == null || !config.hideParticles || !inEnd()) return false;
        ParticleType<?> type = packet.particle().getType();
        if (type != ParticleTypes.LARGE_SMOKE && type != ParticleTypes.FLAME && type != ParticleTypes.WITCH) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        AABB area = new AABB(packet.x() - 3, packet.y() - 3, packet.z() - 3, packet.x() + 3, packet.y() + 3, packet.z() + 3);
        return !mc.level.getEntitiesOfClass(Enderman.class, area).isEmpty();
    }

    private static int rgb(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB() & 0xFFFFFF;
        } catch (Exception e) {
            return 0xFF0058;
        }
    }

    private static float[] colour(String value) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f};
        } catch (Exception e) {
            return new float[]{1f, 0f, 0.35f, 1f};
        }
    }
}
