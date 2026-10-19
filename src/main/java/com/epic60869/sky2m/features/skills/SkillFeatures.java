package com.epic60869.sky2m.features.skills;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sweep display. */
public final class SkillFeatures {
    // Sweep patterns from SkyHanni's repo (MIT).
    private static final Pattern SWEEP = Pattern.compile("^\\s*Sweep: (?<value>[\\d,.]+)");
    private static final Pattern TOUGHNESS = Pattern.compile("^\\s*(?<tree>[\\S ]+) Tree Toughness: (?<toughness>[\\d,.]+) (?<logs>[\\d,.]+) Logs");

    /** XP needed for each skill level 1-50 (standard SkyBlock skill table). */

    private static String sweep;
    private static final Map<String, String> SWEEP_LOGS = new LinkedHashMap<>();
    private static int ticks;

    private SkillFeatures() {}

    private static Sky2MConfig config() {
        return Sky2MConfig.current();
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 20 == 0) tick(mc);
        });
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("sweep", () -> config() != null && config().foraging.sweepDisplay);
        Sky2MHuds.register("sweep", "Sweep Display",
            () -> config() != null && config().foraging.sweepDisplay && sweep != null && holdingAxe(),
            SkillFeatures::sweepLines,
            List.of(kv("Sweep: ", "150"), kv("Oak: ", "12 Logs")),
            8, 620);
    }

    private static Component kv(String key, String value) {
        return Component.literal(key).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.GREEN));
    }

    private static boolean holdingAxe() {
        var player = Minecraft.getInstance().player;
        return player != null && player.getMainHandItem().getItem().toString().contains("axe") && !player.getMainHandItem().getItem().toString().contains("pickaxe");
    }

    private static List<Component> sweepLines() {
        List<Component> lines = new ArrayList<>();
        // /s2 gui draws every HUD, before the tab list has shown your sweep.
        lines.add(kv("Sweep: ", sweep == null ? "?" : sweep));
        SWEEP_LOGS.forEach((tree, logs) -> lines.add(kv(tree + ": ", logs + " Logs")));
        return lines;
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || !Sky2MLocation.onSkyblock()) return;
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            if (com.epic60869.sky2m.custom.util.Compat.rawTabName(info) == null) continue;
            String text = Sky2MLocation.strip(com.epic60869.sky2m.custom.util.Compat.rawTabName(info).getString());
            Matcher m = SWEEP.matcher(text);
            if (m.find()) sweep = m.group("value");
        }

        // Sweep Details lines (tree toughness and logs) shown in item lore, e.g. on your axe.
        ItemLore lore = mc.player.getMainHandItem().get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                Matcher m = TOUGHNESS.matcher(Sky2MLocation.strip(line.getString()));
                if (m.find()) SWEEP_LOGS.put(m.group("tree"), m.group("logs"));
            }
        }

    }

}
