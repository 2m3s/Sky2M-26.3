package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spring Boots jump height, from Odin's SpringBoots (https://github.com/odtheking/Odin, BSD-3-Clause) and SkyHanni's
 * SpringBootsHelper (https://github.com/hannibal002/SkyHanni, LGPL-2.1). Hypixel plays a note block pling for every
 * step of the charge while you crouch in Spring Boots, at a pitch that tells how far along it is, and a firework
 * launch sound when you jump; Odin's table turns the count into blocks.
 */
final class SpringBootsHelper {
    private static final Set<Float> HIGH_PITCHES = Set.of(0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f);
    private static final float LOW_PITCH = 0.6984127f;
    private static final float[] BLOCKS = {
        0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f,
        20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
        34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f,
        44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
        53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f
    };

    private static float blockAmount;
    private static int highCount, lowCount, streak;

    private SpringBootsHelper() {}

    static void init() {
        Sky2MHuds.register("spring_boots", "Spring Boots", () -> enabled() && DungeonExtras.config().springBoots.hud && blockAmount > 0,
            SpringBootsHelper::lines, List.of(Component.literal("Height: ").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal("22.5").withStyle(ChatFormatting.YELLOW))), 10, 250);
        Sky2MHuds.setting("spring_boots", () -> DungeonExtras.config() != null && DungeonExtras.config().springBoots.hud);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (!Compat.isOnSkyblock()) return;
            if (mc.player == null || mc.player.isCrouching() && wearing(mc)) return;
            highCount = lowCount = streak = 0;
            blockAmount = 0;
        });
        Sky2MWorldRender.register(collector -> {
            Minecraft mc = Minecraft.getInstance();
            if (!enabled() || !DungeonExtras.config().springBoots.box || blockAmount <= 0 || mc.player == null) return;
            Vec3 at = mc.player.position().add(0, blockAmount, 0);
            collector.submitOutlinedBox(AABB.unitCubeFromLowerCorner(at), new float[] {1f, 0.33f, 0.33f}, 3f, true);
        });
    }

    private static boolean enabled() {
        return DungeonExtras.config() != null && Compat.isOnSkyblock();
    }

    private static boolean wearing(Minecraft mc) {
        return mc.player != null && "SPRING_BOOTS".equals(Compat.neuName(mc.player.getItemBySlot(EquipmentSlot.FEET)));
    }

    /** From the sound packet mixin: the charge sounds. */
    public static void onSound(String name, float pitch) {
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !wearing(mc)) return;
        DungeonExtras.SpringBoots c = DungeonExtras.config().springBoots;
        if (name.endsWith("note_block.pling") && mc.player.isCrouching()) {
            if (Math.abs(pitch - LOW_PITCH) < 1e-4f) lowCount = Math.min(2, lowCount + 1);
            else if (isHigh(pitch)) highCount++;
            else return;
            if (c.readyTitle && Sky2MLocation.inDungeon() && Sky2MLocation.dungeonFloor().endsWith("7") && ++streak == 5) {
                Sky2MAlerts.title(Component.literal("Spring Boots ready!").withStyle(ChatFormatting.RED), Component.empty());
            }
        } else if (name.endsWith("firework_rocket.launch") && (Math.abs(pitch - 0.0952381f) < 1e-4f || Math.abs(pitch - 1.6984127f) < 1e-4f)) {
            highCount = lowCount = streak = 0;
        } else {
            return;
        }
        blockAmount = BLOCKS[Math.max(0, Math.min(BLOCKS.length - 1, lowCount + highCount))];
    }

    private static boolean isHigh(float pitch) {
        for (float p : HIGH_PITCHES) if (Math.abs(p - pitch) < 1e-4f) return true;
        return false;
    }

    private static List<Component> lines() {
        if (blockAmount <= 0) return List.of();
        ChatFormatting colour = blockAmount <= 13.5 ? ChatFormatting.RED : blockAmount <= 22.5 ? ChatFormatting.YELLOW
            : blockAmount <= 33 ? ChatFormatting.GOLD : blockAmount <= 43.5 ? ChatFormatting.GREEN : ChatFormatting.AQUA;
        return List.of(Component.literal("Height: ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(String.format(Locale.US, "%.1f", blockAmount)).withStyle(colour)));
    }
}
