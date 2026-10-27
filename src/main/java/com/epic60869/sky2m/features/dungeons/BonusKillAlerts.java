package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonScore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Dungeons > Score > Kill Titles: a title the first time the Mimic, a Prince or a bonus bat dies in a run. It reads
 * what the score calculator already knows (the kill itself, Hypixel's bonus score lines, teammates' "Mimic dead!"
 * messages and Party Dungeon Data), so whoever killed it, it only shows once.
 */
public final class BonusKillAlerts {
    private static boolean mimic, prince, bat;
    private static int ticks;

    private BonusKillAlerts() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 == 0) tick();
        });
    }

    private static void reset() {
        mimic = prince = bat = false;
    }

    private static void tick() {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || !Sky2MLocation.inDungeon()) return;
        boolean on = c.dungeons.score.killTitles;
        if (!mimic && ScoreCalculator.mimicSeenKilled()) {
            mimic = true;
            if (on) Sky2MAlerts.title(Component.literal("Mimic Killed!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.literal("+2 score").withStyle(ChatFormatting.GRAY));
        }
        if (!prince && ScoreCalculator.princeKilled()) {
            prince = true;
            if (on) Sky2MAlerts.title(Component.literal("Prince Killed!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.literal("+1 score").withStyle(ChatFormatting.GRAY));
        }
        if (!bat && DungeonScore.wasBatKilled()) {
            bat = true;
            if (on) Sky2MAlerts.title(Component.literal("Bat Killed!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), Component.literal("+1 score").withStyle(ChatFormatting.GRAY));
        }
    }
}
