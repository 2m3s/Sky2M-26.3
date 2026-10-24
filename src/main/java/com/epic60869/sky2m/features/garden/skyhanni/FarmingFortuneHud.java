package com.epic60869.sky2m.features.garden.skyhanni;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SkyHanni's true Farming Fortune display (https://github.com/hannibal002/SkyHanni, LGPL-2.1: FarmingFortuneDisplay):
 * the Farming Fortune of the Stats tab widget (universal plus the crop's), the pest fortune bonus and how much the
 * pests in your Garden reduce it. Needs the Stats widget with the Farming Fortune and Latest Crop Fortune lines.
 */
public final class FarmingFortuneHud {
    private static List<Component> lines = List.of();
    private static int ticks;

    private FarmingFortuneHud() {}

    private static SkyHanniGardenConfig.Fortune config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.skyhanniGarden.fortune;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            SkyHanniGardenConfig.Fortune c = config();
            if (c == null || !c.enabled || mc.player == null || !Sky2MLocation.inGarden()) return;
            if (++ticks % 10 == 0) lines = build(c);
        });
        Sky2MHuds.setting("sh_farming_fortune", () -> config() != null && config().enabled);
        Sky2MHuds.register("sh_farming_fortune", "True Farming Fortune", () -> config() != null && config().enabled && Sky2MLocation.inGarden(),
            () -> lines,
            List.of(Component.literal("§6Farming Fortune§7: §e1,234"), Component.literal("§7Universal §e900 §7+ Crop §e334"),
                Component.literal("§eBonus: §6+200 §b29m")),
            8, 190);
    }

    /** How many percent of the fortune the pests in your Garden take away (SkyHanni's getPestFFReduction). */
    public static int pestReduction() {
        int alive = GardenTabData.pestsAlive();
        if (alive < 0) return 0;
        int pests = Math.max(0, alive - (int) Math.floor(GardenTabData.bonusPestChance() / 100));
        if (pests <= 3) return 0;
        return switch (pests) {
            case 4 -> 5;
            case 5 -> 15;
            case 6 -> 30;
            case 7 -> 50;
            default -> 75;
        };
    }

    private static List<Component> build(SkyHanniGardenConfig.Fortune c) {
        double total = GardenTabData.trueFortune();
        List<Component> out = new ArrayList<>();
        if (total < 0) {
            out.add(Component.literal("§cNo Farming Fortune found: turn on the Stats tab widget (/widget)"));
            return out;
        }
        GardenCrop held = GardenCrop.held();
        GardenCrop tabCrop = GardenTabData.cropFortuneCrop();
        boolean wrongCrop = held != null && tabCrop != null && held != tabCrop;
        int reduction = pestReduction();
        String color = reduction > 0 ? "§c" : "§e";
        out.add(Component.literal("§6Farming Fortune§7: " + color + FarmingHud.number(total)));
        if (c.breakdown) {
            String crop = tabCrop == null ? "Crop" : tabCrop.displayName;
            out.add(Component.literal("§7Universal §e" + FarmingHud.number(GardenTabData.universalFortune())
                + " §7+ " + crop + " §e" + FarmingHud.number(Math.max(0, GardenTabData.cropFortune()))));
        }
        if (wrongCrop) {
            out.add(Component.literal("§cBreak §e" + held.displayName + "§c to see its fortune!"));
        }
        if (c.pestBonus) {
            GardenTabData.Bonus bonus = GardenTabData.pestBonus();
            if (bonus != null) {
                if (!bonus.active()) {
                    out.add(Component.literal("§eBonus: §cInactive!"));
                } else {
                    long left = Math.max(0, (bonus.expiresAt() - System.currentTimeMillis()) / 1000);
                    out.add(Component.literal("§eBonus: §6+" + bonus.fortune() + " §b" + FarmingHud.duration(left)));
                }
            }
        }
        if (reduction > 0) out.add(Component.literal("§cPests are reducing your fortune by §e" + reduction + "%§c!"));
        return out;
    }
}
