package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * SkyHanni's solo class message filter (ChatFilter, "Solo Class" and "Solo Class Stats") and Milestones Display
 * (DungeonMilestonesDisplay), https://github.com/hannibal002/SkyHanni (LGPL-2.1); and NoFrills' Shard Score Message
 * (BonusScoreMessage, https://github.com/WhatYouThing/NoFrills, BSD-3-Clause).
 */
final class DungeonChatTweaks {
    private static final Pattern SOLO_CLASS = Pattern.compile("^Your (?:Healer|Mage|Berserk|Archer|Tank) stats are doubled because you are the only player using this class!$");
    private static final Pattern SOLO_STATS = Pattern.compile("^\\[(?:Healer|Mage|Berserk|Archer|Tank)].*");
    private static final Pattern MILESTONE = Pattern.compile(".*Milestone .: You have (?:tanked and )?(?:dealt|healed) *.*so far! .*");
    private static final String PRINCE = "A Prince falls. +1 Bonus Score";
    private static final String BAT = "A Bat has been slain. +1 Bonus Score";

    private static int milestone;
    private static String display = "";
    private static long reachedAt;
    private static int ticks;

    private DungeonChatTweaks() {}

    static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !hidden(message));
        Sky2MChat.onChat(message -> {
            DungeonExtras.Config c = DungeonExtras.config();
            if (c == null || !Sky2MLocation.inDungeon()) return;
            String text = message.text().trim();
            DungeonExtras.ShardScore s = c.shardScore;
            if (s.enabled && s.sendPrince && text.equals(PRINCE)) send(s.princeMessage);
            else if (s.enabled && s.sendBat && text.equals(BAT)) send(s.batMessage);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> {
            if (area.equals("Catacombs")) {
                milestone = 0;
                update();
            } else {
                reset();
            }
        });
        // SkyHanni's fade-out: a few seconds after milestone 3, the text loses a letter every quarter second.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 != 0 || display.isEmpty() || milestone < 3 || System.currentTimeMillis() - reachedAt < 3000) return;
            display = display.substring(1);
        });
        Sky2MHuds.register("dungeon_milestone", "Dungeon Milestone", () -> {
            DungeonExtras.Config c = DungeonExtras.config();
            return c != null && c.chat.milestones && Sky2MLocation.inDungeon() && !display.isEmpty();
        }, () -> List.of(Component.literal(display).withStyle(colour())),
            List.of(Component.literal("Current Milestone: 2").withStyle(ChatFormatting.YELLOW)), 10, 270);
        Sky2MHuds.setting("dungeon_milestone", () -> DungeonExtras.config() != null && DungeonExtras.config().chat.milestones);
    }

    private static void reset() {
        milestone = 0;
        display = "";
    }

    private static ChatFormatting colour() {
        return milestone <= 1 ? ChatFormatting.RED : milestone == 2 ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
    }

    private static void update() {
        if (milestone > 3) return;
        if (milestone == 3) reachedAt = System.currentTimeMillis();
        display = "Current Milestone: " + milestone;
    }

    private static boolean hidden(Component message) {
        DungeonExtras.Config c = DungeonExtras.config();
        if (c == null || !Sky2MLocation.inDungeon() || !Compat.isOnSkyblock()) return false;
        String text = ChatFormatting.stripFormatting(message.getString()).trim();
        if (c.chat.hideSoloClass && SOLO_CLASS.matcher(text).matches()) return true;
        if (c.chat.hideSoloStats && SOLO_STATS.matcher(text).matches() && startsGreen(message)) return true;
        if (c.chat.milestones && MILESTONE.matcher(text).matches()) {
            milestone++;
            update();
            return true;
        }
        return false;
    }

    /** The boosted class stats lines are green; a teammate's chat that happens to start with a class in brackets is not. */
    private static boolean startsGreen(Component message) {
        TextColor[] first = {null};
        message.visit((style, value) -> {
            if (first[0] == null && !value.isBlank()) first[0] = style.getColor();
            return first[0] == null && !value.isBlank() ? java.util.Optional.of(Boolean.TRUE) : java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return first[0] != null && first[0].getValue() == TextColor.fromLegacyFormat(ChatFormatting.GREEN).getValue();
    }

    private static void send(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || message == null || message.isBlank()) return;
        if (message.startsWith("/")) mc.getConnection().sendCommand(message.substring(1));
        else mc.getConnection().sendChat(message);
    }
}
