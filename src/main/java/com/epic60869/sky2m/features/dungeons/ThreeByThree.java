package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Dungeons > Platform Highlight (3x3): one box over the 3x3 floor at x 53-55, z 113-115 (y 63) on floor 7 and M7. The spot, options and default colours match NoFrills' Platform Highlight.
 */
public final class ThreeByThree {
    private static final AABB BOX = AABB.encapsulatingFullBlocks(new BlockPos(53, 63, 113), new BlockPos(55, 63, 115));

    private ThreeByThree() {}

    public static void init() {
        Sky2MWorldRender.register(collector -> {
            Sky2MConfig config = Sky2MConfig.current();
            Minecraft mc = Minecraft.getInstance();
            if (config == null || mc.player == null) return;
            FeatureConfigs.PlatformHighlight settings = config.dungeons.f7.platformHighlight;
            // Shown whenever you're on F7/M7: the box is in the boss room, so it only appears there, and waiting for
            // Goldor's chat line meant it often didn't show at all.
            if (!settings.enabled || !PositionalMessages.onFloor7()) return;
            if (settings.healerOnly && SelfClass.get() != DungeonClass.HEALER) return;
            int outline = colour(settings.outlineColor);
            int fill = colour(settings.fillColor);
            if (settings.style != FeatureConfigs.BoxStyle.OUTLINE) {
                collector.submitFilledBox(BOX, rgb(fill), ((fill >>> 24) & 255) / 255f, false);
            }
            if (settings.style != FeatureConfigs.BoxStyle.FILLED) {
                collector.submitOutlinedBox(BOX, rgb(outline), ((outline >>> 24) & 255) / 255f, 3f, false);
            }
        });
    }

    private static int colour(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xFF55FF55;
        }
    }

    private static float[] rgb(int argb) {
        return new float[]{((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f};
    }
}
