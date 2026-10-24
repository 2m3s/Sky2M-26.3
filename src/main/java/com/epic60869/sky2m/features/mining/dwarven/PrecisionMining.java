package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Precision Mining highlighter and Flowstate helper, ported from SkyHanni's PrecisionMiningHighlight and FlowstateHelper
 * (hannibal002/SkyHanni, LGPL-2.1).
 * <ul>
 *     <li>Precision Mining: while you hold attack, the crit / happy villager particle that lands on the block you look at
 *     is the spot to aim for. It is boxed for a few ticks, green when it is the "aim here" happy villager particle.</li>
 *     <li>Flowstate: each block you mine within reach keeps your streak (up to 200 blocks, 10 seconds between blocks) and
 *     gives level x streak mining speed. Mined blocks are noticed by the server turning a block next to you into air or
 *     bedrock. The personal best is kept in {@code sky2m/flowstate.json}. The speed bonus is the one SkyHanni shows.</li>
 * </ul>
 */
public final class PrecisionMining {
    private static final String ID = "flowstate";
    private static final long STREAK_MS = 10_000;
    private static final Gson GSON = new Gson();

    // Precision Mining.
    private static AABB lastParticle;
    private static boolean lookingAtParticle;
    private static long particleUntil;

    // Flowstate.
    private static int level;
    private static int streak;
    private static long streakEnds;
    private static int personalBest;
    private static Path file;
    private static int ticks;

    private PrecisionMining() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("flowstate.json");
        loadBest();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        Sky2MWorldRender.register(PrecisionMining::render);
        Sky2MHuds.setting(ID, () -> DwarvenMining.precision() != null && DwarvenMining.precision().flowstate);
        Sky2MHuds.register(ID, "Flowstate Helper",
            () -> {
                DwarvenConfig.Precision c = DwarvenMining.precision();
                return c != null && c.flowstate && level > 0 && System.currentTimeMillis() < streakEnds;
            },
            PrecisionMining::lines,
            List.of(Component.literal("Flowstate Helper").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                Component.literal("Time Remaining: ").withStyle(ChatFormatting.GRAY).append(Component.literal("9.7s").withStyle(ChatFormatting.AQUA)),
                Component.literal("Streak: ").withStyle(ChatFormatting.GRAY).append(Component.literal("123").withStyle(ChatFormatting.YELLOW)).append(Component.literal("/200").withStyle(ChatFormatting.DARK_GRAY)),
                Component.literal("+600⸕").withStyle(ChatFormatting.GOLD)),
            8, 400);
    }

    private static boolean miningArea() {
        return Sky2MLocation.onSkyblock() && (Sky2MLocation.inMiningIsland() || Sky2MLocation.areaIs("Gold Mine") || Sky2MLocation.areaIs("Deep Caverns"));
    }

    // ----------------------------------------------------------------------------------------------- Precision Mining

    /** Every particle packet (Sky2MSlayerPacketsMixin). */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        DwarvenConfig.Precision c = DwarvenMining.precision();
        if (c == null || !c.highlight || !miningArea()) return;
        var type = packet.particle().getType();
        if (type != ParticleTypes.CRIT && type != ParticleTypes.HAPPY_VILLAGER) return;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.keyAttack.isDown() || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
        AABB particle = new AABB(packet.x() - 0.12, packet.y() - 0.12, packet.z() - 0.12,
            packet.x() + 0.12, packet.y() + 0.12, packet.z() + 0.12);
        BlockPos block = hit.getBlockPos();
        if (!new AABB(block).intersects(particle)) return;
        lookingAtParticle = type == ParticleTypes.HAPPY_VILLAGER;
        lastParticle = particle;
        particleUntil = System.currentTimeMillis() + 250;
    }

    private static void render(PrimitiveCollector collector) {
        DwarvenConfig.Precision c = DwarvenMining.precision();
        AABB box = lastParticle;
        if (c == null || !c.highlight || box == null) return;
        if (System.currentTimeMillis() > particleUntil) {
            lastParticle = null;
            return;
        }
        float[] colour = lookingAtParticle ? new float[]{0f, 1f, 0f} : new float[]{0f, 1f, 1f};
        collector.submitFilledBox(box, colour, 0.6f, true);
    }

    // ---------------------------------------------------------------------------------------------------- Flowstate

    private static void tick(Minecraft mc) {
        DwarvenConfig.Precision c = DwarvenMining.precision();
        if (c == null || mc.player == null) return;
        if (++ticks % 5 == 0) level = c.flowstate ? flowstateLevel(mc.player.getMainHandItem()) : 0;
        if (streak > 0 && System.currentTimeMillis() >= streakEnds) endStreak(c);
    }

    private static int flowstateLevel(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return Compat.customDataView(stack).getCompoundOrEmpty("enchantments").getIntOr("ultimate_flowstate", 0);
    }

    /** A block update from the server (Sky2MMiningBlocksMixin), read before the client applies it. */
    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        DwarvenConfig.Precision c = DwarvenMining.precision();
        if (c == null || !c.flowstate || level <= 0 || !miningArea()) return;
        if (!state.isAir() && !state.is(Blocks.BEDROCK)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.player.position().distanceToSqr(Vec3.atCenterOf(pos)) > 49) return;
        BlockState old = mc.level.getBlockState(pos);
        if (old.isAir() || old.is(Blocks.BEDROCK)) return;
        streak++;
        streakEnds = System.currentTimeMillis() + STREAK_MS;
    }

    private static void endStreak(DwarvenConfig.Precision c) {
        if (streak > personalBest) {
            // No point announcing a record before the first time you reached max speed.
            if (personalBest > 200 && c.flowstatePbMessage) {
                int old = personalBest;
                int now = streak;
                Sky2MAlerts.chat(Component.literal("NEW FLOWSTATE PERSONAL BEST! ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                    .append(Component.literal("Streak: " + now + ". You beat your old personal best by " + (now - old) + " blocks!").withStyle(ChatFormatting.WHITE)));
            }
            personalBest = streak;
            saveBest();
        }
        streak = 0;
    }

    private static List<Component> lines() {
        long left = Math.max(0, streakEnds - System.currentTimeMillis());
        int bonus = Math.min(streak, 200) * level;
        ChatFormatting streakColour = streak < 200 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        Component streakLine = Component.literal("Streak: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.valueOf(streak)).withStyle(streakColour));
        if (streak < 200) streakLine = streakLine.copy().append(Component.literal("/200").withStyle(ChatFormatting.DARK_GRAY));
        ChatFormatting timeColour = left > 6000 ? ChatFormatting.GREEN : left > 3000 ? ChatFormatting.YELLOW : ChatFormatting.RED;
        Component pb = streak <= personalBest
            ? Component.literal("Personal Best: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(streak)).withStyle(streakColour))
                .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(String.valueOf(personalBest)).withStyle(ChatFormatting.LIGHT_PURPLE))
            : Component.literal("New Personal Best " + streak).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
        return List.of(
            Component.literal("Flowstate Helper").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            Component.literal("Time Remaining: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format(Locale.US, "%.1fs", left / 1000.0)).withStyle(timeColour)),
            streakLine,
            Component.literal("+" + bonus + "⸕").withStyle(ChatFormatting.GOLD),
            pb);
    }

    private static void loadBest() {
        try {
            if (Files.exists(file)) {
                JsonObject o = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (o != null && o.has("personalBest")) personalBest = o.get("personalBest").getAsInt();
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read the Flowstate record: " + e);
        }
    }

    private static void saveBest() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("personalBest", personalBest);
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save the Flowstate record: " + e);
        }
    }
}
