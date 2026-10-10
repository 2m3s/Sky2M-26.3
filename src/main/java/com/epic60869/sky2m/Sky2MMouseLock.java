package com.epic60869.sky2m;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Set;

/** Farming > Mouse Lock: the camera doesn't turn while you hold a farming tool (Sky2MMouseHandlerMixin). */
public final class Sky2MMouseLock {
    /**
     * Every farming tool, by SkyBlock id: Skyblocker's list (FarmingHudWidget.FARMING_TOOLS). The id is used rather
     * than the name, so renamed tools ("The Last Breath") still count.
     */
    private static final Set<String> FARMING_TOOLS = Set.of(
        "THEORETICAL_HOE_WHEAT_1", "THEORETICAL_HOE_WHEAT_2", "THEORETICAL_HOE_WHEAT_3",
        "THEORETICAL_HOE_CARROT_1", "THEORETICAL_HOE_CARROT_2", "THEORETICAL_HOE_CARROT_3",
        "THEORETICAL_HOE_POTATO_1", "THEORETICAL_HOE_POTATO_2", "THEORETICAL_HOE_POTATO_3",
        "THEORETICAL_HOE_CANE_1", "THEORETICAL_HOE_CANE_2", "THEORETICAL_HOE_CANE_3",
        "THEORETICAL_HOE_SUNFLOWER_1", "THEORETICAL_HOE_SUNFLOWER_2", "THEORETICAL_HOE_SUNFLOWER_3",
        "THEORETICAL_HOE_WILD_ROSE_1", "THEORETICAL_HOE_WILD_ROSE_2", "THEORETICAL_HOE_WILD_ROSE_3",
        "THEORETICAL_HOE_WARTS_1", "THEORETICAL_HOE_WARTS_2", "THEORETICAL_HOE_WARTS_3",
        "FUNGI_CUTTER", "FUNGI_CUTTER_2", "FUNGI_CUTTER_3",
        "CACTUS_KNIFE", "CACTUS_KNIFE_2", "CACTUS_KNIFE_3",
        "MELON_DICER", "MELON_DICER_2", "MELON_DICER_3",
        "PUMPKIN_DICER", "PUMPKIN_DICER_2", "PUMPKIN_DICER_3",
        "COCO_CHOPPER", "COCO_CHOPPER_2", "COCO_CHOPPER_3",
        "BASIC_GARDENING_HOE", "ADVANCED_GARDENING_HOE", "BASIC_GARDENING_AXE", "ADVANCED_GARDENING_AXE", "BINGHOE"
    );

    private static Sky2MConfig config;
    private static boolean locked;

    private Sky2MMouseLock() {}

    public static void init(Sky2MConfig cfg) {
        config = cfg;
    }

    public static void tick(Minecraft mc) {
        locked = config != null && config.farming.mouseLock.enabled && mc.player != null && mc.gui.screen() == null
            && (!config.farming.mouseLock.groundOnly || mc.player.onGround())
            && (!config.farming.mouseLock.gardenOnly || com.epic60869.sky2m.features.core.Sky2MLocation.inGarden())
            && !(com.epic60869.sky2m.features.core.Sky2MLocation.inGarden() && isInBarn(mc.player))
            && isFarmingTool(mc.player.getMainHandItem());
    }

    public static boolean isLocked() {
        return locked;
    }

    public static boolean isFarmingTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && FARMING_TOOLS.contains(data.copyTag().getStringOr("id", ""));
    }

    /** Skyblocker pauses the lock in the Garden's barn, where you walk around rather than farm. */
    private static boolean isInBarn(Player player) {
        return player.getX() <= 35.5d && player.getX() >= -32.5d && player.getZ() <= -4.5d && player.getZ() >= -46.5d;
    }
}
