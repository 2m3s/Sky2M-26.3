package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.Map;

/**
 * Fishing visuals: lava drawn as opaque water (SkyHanni's LavaReplacement, LGPL-2.1, through Sky2MFluidModelMixin), other
 * players' bobbers hidden (SkyOcean's hide other bobbers, drawn out of Sky2MHideFireballsMixin) and the bobber fix for
 * Hypixel's lava bobbers (SkyOcean's fix bobber, Sky2MFishingHookMixin). Display only.
 */
public final class FishingVisuals {
    private static FluidModel opaqueWater;
    private static boolean replacing;

    private FishingVisuals() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.fishing == null ? null : c.fishing.visuals;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null) return;
            boolean should = shouldReplace();
            if (should != replacing) {
                replacing = should;
                if (mc.player != null) {
                    int r = mc.options.renderDistance().get() + 1;
                    int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
                    mc.level.setSectionRangeDirty(cx - r, mc.level.getMinSectionY(), cz - r, cx + r, mc.level.getMaxSectionY(), cz + r);
                }
            }
        });
    }

    private static boolean shouldReplace() {
        Config c = config();
        if (c == null || !c.lavaReplacement || !Sky2MLocation.onSkyblock()) return false;
        return c.lavaEverywhere || Sky2MLocation.areaIs("Crimson Isle");
    }

    /** Called when Minecraft bakes its fluid models. */
    public static void onModelsBaked(Map<Fluid, FluidModel> models) {
        FluidModel water = models.get(Fluids.WATER);
        if (water == null) return;
        opaqueWater = new FluidModel(ChunkSectionLayer.SOLID, water.stillMaterial(), water.flowingMaterial(), water.overlayMaterial(), water.tintSource());
    }

    public static FluidModel replacement(FluidState state, FluidModel original) {
        if (!replacing || opaqueWater == null) return original;
        return state.getType() == Fluids.LAVA || state.getType() == Fluids.FLOWING_LAVA ? opaqueWater : original;
    }

    /** Other players' bobbers aren't drawn. */
    public static boolean hideBobber(Entity entity) {
        if (!(entity instanceof FishingHook hook)) return false;
        Config c = config();
        if (c == null || !c.hideOtherBobbers || !Sky2MLocation.onSkyblock()) return false;
        return hook.getPlayerOwner() != Minecraft.getInstance().player;
    }

    public static boolean fixBobber() {
        Config c = config();
        return c != null && c.fixBobber && Sky2MLocation.onSkyblock();
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Lava Replacement", desc = "Draws lava with the water texture, so sea creatures in lava are easier to see.")
        @ConfigEditorBoolean
        public boolean lavaReplacement = false;

        @Expose
        @ConfigOption(name = "Replace Everywhere", desc = "Replace lava on every island. Off: only in the Crimson Isle.")
        @ConfigEditorBoolean
        public boolean lavaEverywhere = true;

        @Expose
        @ConfigOption(name = "Hide Other Bobbers", desc = "Don't draw other players' fishing bobbers.")
        @ConfigEditorBoolean
        public boolean hideOtherBobbers = false;

        @Expose
        @ConfigOption(name = "Fix Bobber", desc = "Stops your bobber from snapping back to your hand or sinking in lava, a Hypixel quirk.")
        @ConfigEditorBoolean
        public boolean fixBobber = false;
    }
}
