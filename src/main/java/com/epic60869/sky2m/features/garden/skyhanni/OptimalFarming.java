package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SkyHanni's Garden Optimal Angles and Garden Optimal Speed (https://github.com/hannibal002/SkyHanni, LGPL-2.1):
 * the optimal yaw / pitch and speed of the crop in your hand next to your current ones, the wrong speed warning,
 * and the list of every crop's angles / speeds over the Squeaky Mousemat and Rancher's Boots signs. Display only:
 * nothing is typed into the sign for you.
 */
public final class OptimalFarming {
    private static final long WARN_COOLDOWN_MS = 20_000L;

    private static GardenCrop lastCrop;
    private static GardenCrop heldCrop;
    private static long lastToolSwitch;
    private static long lastWarn;
    private static int ticks;
    private static List<Component> angleLines = List.of();
    private static List<Component> speedLines = List.of();
    private static Field messagesField;
    private static boolean messagesFieldFailed;

    private OptimalFarming() {}

    private static SkyHanniGardenConfig.Optimal config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.skyhanniGarden.optimal;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractSignEditScreen) || !Sky2MLocation.inGarden()) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                SkyHanniGardenConfig.Optimal c = config();
                if (c == null || !c.signOverlay) return;
                String[] text = signText(s);
                if (text == null) return;
                List<Component> lines = new ArrayList<>();
                if (text.length > 3 && "Set Yaw Above!".equals(text[1]) && "Set Pitch Below!".equals(text[2])) {
                    lines.add(Component.literal("§6Optimal Angles (yaw / pitch)"));
                    for (GardenCrop crop : GardenCrop.values()) {
                        String color = crop == lastCrop ? "§6" : "§a";
                        lines.add(Component.literal(color + crop.displayName + " §7- §e" + trim(crop.yaw) + "§7/§e" + trim(crop.pitch)));
                    }
                } else if (text.length > 3 && "^^^^^^".equals(text[1]) && text[2].startsWith("Set your") && text[3].endsWith("speed cap!")) {
                    lines.add(Component.literal("§6Optimal Speeds"));
                    for (GardenCrop crop : GardenCrop.values()) {
                        String color = crop == lastCrop ? "§6" : "§f";
                        lines.add(Component.literal(color + crop.displayName + " §7- " + color + crop.speed));
                    }
                } else {
                    return;
                }
                Sky2MHuds.render(graphics, lines, 10, 40, 1f);
            });
        });

        Sky2MHuds.setting("sh_optimal_angle", () -> config() != null && config().angleHud);
        Sky2MHuds.register("sh_optimal_angle", "Optimal Angles", () -> config() != null && config().angleHud && Sky2MLocation.inGarden(),
            () -> angleLines,
            List.of(Component.literal("§7Optimal: §eYaw 90§7 / §ePitch 0"), Component.literal("§7Current: §aYaw 90.00§7 / §aPitch 0.10")),
            8, 330);
        Sky2MHuds.setting("sh_optimal_speed", () -> config() != null && config().speedHud);
        Sky2MHuds.register("sh_optimal_speed", "Optimal Speed", () -> config() != null && config().speedHud && Sky2MLocation.inGarden(),
            () -> speedLines,
            List.of(Component.literal("§cOptimal Speed: §f93 (§eCurrent: §f100§f)")),
            8, 350);
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.US, "%.1f", value);
    }

    /** The sign's four lines (AbstractSignEditScreen.messages is private), or null. */
    private static String[] signText(Object screen) {
        if (messagesFieldFailed) return null;
        try {
            if (messagesField == null) {
                messagesField = AbstractSignEditScreen.class.getDeclaredField("messages");
                messagesField.setAccessible(true);
            }
            return (String[]) messagesField.get(screen);
        } catch (Exception | LinkageError e) {
            messagesFieldFailed = true;
            return null;
        }
    }

    private static int walkSpeed(Minecraft mc) {
        return (int) (mc.player.getAbilities().getWalkingSpeed() * 1000);
    }

    private static void tick(Minecraft mc) {
        SkyHanniGardenConfig.Optimal c = config();
        if (c == null || mc.player == null || !Sky2MLocation.inGarden()) return;
        GardenCrop crop = GardenCrop.held();
        if (crop != heldCrop) {
            heldCrop = crop;
            lastToolSwitch = System.currentTimeMillis();
        }
        if (crop != null) lastCrop = crop;
        if (++ticks % 5 != 0) return;

        if (crop == null) {
            angleLines = List.of();
            speedLines = List.of();
            return;
        }
        if (c.angleHud) {
            float yaw = Mth.wrapDegrees(mc.player.getYRot());
            float pitch = mc.player.getXRot();
            boolean yawOk = Math.abs(Mth.wrapDegrees(yaw - crop.yaw)) <= 0.5f;
            boolean pitchOk = Math.abs(pitch - crop.pitch) <= 0.5f;
            angleLines = List.of(
                Component.literal("§7" + crop.displayName + " optimal: §eYaw " + trim(crop.yaw) + "§7 / §ePitch " + trim(crop.pitch)),
                Component.literal("§7Current: " + (yawOk ? "§a" : "§c") + "Yaw " + String.format(Locale.US, "%.2f", yaw)
                    + "§7 / " + (pitchOk ? "§a" : "§c") + "Pitch " + String.format(Locale.US, "%.2f", pitch)));
        }
        int speed = walkSpeed(mc);
        boolean recentSwitch = System.currentTimeMillis() - lastToolSwitch < 1500;
        if (c.speedHud) {
            String color = recentSwitch ? "§7" : speed != crop.speed ? "§c" : "§a";
            String text = "Optimal Speed: §f" + crop.speed + (speed != crop.speed ? " (§eCurrent: §f" + speed + "§f)" : "");
            speedLines = List.of(Component.literal(color + text));
        }
        if (c.speedWarning && speed != crop.speed && !recentSwitch) warn(mc, c, crop, speed);
    }

    private static void warn(Minecraft mc, SkyHanniGardenConfig.Optimal c, GardenCrop crop, int speed) {
        if (!mc.player.onGround()) return;
        if (FarmingHud.blocksPerSecond() <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastWarn < WARN_COOLDOWN_MS) return;
        boolean ranchers = "RANCHERS_BOOTS".equals(Compat.neuName(mc.player.getItemBySlot(EquipmentSlot.FEET)));
        if (c.onlyRanchers && !ranchers) return;
        lastWarn = now;
        Sky2MAlerts.title(Component.literal("§cWrong speed!"), Component.empty());
        Sky2MAlerts.chat(Component.literal("§cWrong speed while farming " + crop.displayName + " detected! §eCurrent Speed: §f" + speed
            + "§e, Optimal Speed: §f" + crop.speed + (ranchers ? "§e. Use /setmaxspeed " + crop.speed + "." : "")));
    }
}
