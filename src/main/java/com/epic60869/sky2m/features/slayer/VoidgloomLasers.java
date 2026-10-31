package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Slayers > Enderman > Laser Warning and Timer, like SkyHanni's damage indicator: in its laser phase a Voidgloom
 * Seraph sits on an armour stand and spins its beams around, which ends about 8.2 seconds after it got on. The time
 * left is counted from the armour stand's age.
 */
public final class VoidgloomLasers {
    private static final float LASER_SECONDS = 8.2f;

    private static boolean inLasers;
    private static float left;

    private VoidgloomLasers() {}

    private static FeatureConfigs.EndermanSlayer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.enderman;
    }

    static boolean needsBoss() {
        FeatureConfigs.EndermanSlayer c = config();
        return c != null && (c.laserWarning || c.laserTimer);
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Sky2MHuds.register("voidgloom_lasers", "Voidgloom Lasers", () -> config() != null && config().laserTimer, () -> {
            List<Component> out = new ArrayList<>();
            if (inLasers) out.add(Component.literal("Lasers: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(String.format(Locale.US, "%.1fs", Math.max(0, left))).withStyle(ChatFormatting.WHITE)));
            return out;
        }, List.of(Component.literal("Lasers: ").withStyle(ChatFormatting.LIGHT_PURPLE).append(Component.literal("5.3s").withStyle(ChatFormatting.WHITE))), 200, 160);
    }

    private static void tick() {
        FeatureConfigs.EndermanSlayer c = config();
        LivingEntity boss = needsBoss() ? SlayerFeatures.boss() : null;
        Entity seat = boss != null && "enderman".equals(BuiltInRegistries.ENTITY_TYPE.getKey(boss.getType()).getPath()) ? boss.getVehicle() : null;
        if (seat == null) {
            inLasers = false;
            return;
        }
        left = LASER_SECONDS - seat.tickCount / 20f;
        if (!inLasers) {
            inLasers = true;
            if (c.laserWarning) Sky2MAlerts.title(Component.literal("LASERS!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), null);
        }
    }
}
