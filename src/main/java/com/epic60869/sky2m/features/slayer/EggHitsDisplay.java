package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Slayers > Egg Hits Display, ported from NoFrills' EggHitsDisplay (https://github.com/WhatYouThing/NoFrills,
 * features/slayer/EggHitsDisplay.java, GPL-3.0): while you fight a Tarantula Broodfather, the hits each egg sack still
 * needs ("3/5" from its "12s 3/5" nametag) drawn big on the egg, seen through walls.
 */
public final class EggHitsDisplay {
    private static final Pattern EGG = Pattern.compile("[0-9]+s [0-9]+/[0-9]+");
    private static final double RANGE = 32;

    private static List<ArmorStand> eggs = List.of();
    private static int ticks;

    private EggHitsDisplay() {}

    private static FeatureConfigs.EggHitsDisplay config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.eggHitsDisplay;
    }

    /** Fighting your Tarantula Broodfather (or its Tier 5 Conjoined Brood phase) right now. */
    private static boolean fightingTarantula() {
        String boss = SlayerTimes.lastBoss();
        return Sky2MLocation.onSkyblock() && SlayerTimes.bossSpawnedAt() != 0 && boss != null && boss.startsWith("Tarantula Broodfather");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            FeatureConfigs.EggHitsDisplay c = config();
            if (c == null || !c.enabled || mc.level == null || mc.player == null || !fightingTarantula()) {
                eggs = List.of();
                return;
            }
            if (++ticks % 4 != 0) return;
            List<ArmorStand> found = new ArrayList<>();
            AABB area = mc.player.getBoundingBox().inflate(RANGE);
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName)) {
                String name = ChatFormatting.stripFormatting(stand.getCustomName().getString());
                if (name != null && EGG.matcher(name.trim()).matches()) found.add(stand);
            }
            eggs = found;
        });
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.EggHitsDisplay c = config();
            if (c == null || !c.enabled || eggs.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            int colour = colour(c.colour);
            for (ArmorStand egg : eggs) {
                if (egg.isRemoved() || !egg.hasCustomName()) continue;
                String name = ChatFormatting.stripFormatting(egg.getCustomName().getString());
                if (name == null) continue;
                name = name.trim();
                // "12s 3/5" -> "3/5": the hits it still needs.
                String hits = name.substring(name.indexOf(' ') + 1);
                collector.submitText(Component.literal(hits).withColor(colour), egg.getPosition(partial), c.scale, true);
            }
        });
    }

    private static int colour(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB() & 0xFFFFFF;
        } catch (Exception e) {
            return 0xFFFFFF;
        }
    }
}
