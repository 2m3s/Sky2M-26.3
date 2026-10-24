package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonScore;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Dungeon Creation Cooldown (https://github.com/hannibal002/SkyHanni, LGPL-2.1): Hypixel doesn't let you
 * create a second dungeon for 30 seconds after you start one. Starts when the "entered The Catacombs" line names you,
 * shows the time left, tells you when it is over and can swallow /joininstance while it runs.
 */
final class DungeonCreationCooldown {
    private static final Pattern ENTERED = Pattern.compile("^(?:\\[[^]]*] ?)*(\\w{1,16}) entered (?:MM )?The Catacombs, .*!$");
    private static final long COOLDOWN_MS = 30_000;

    private static long until;
    private static boolean warned = true;

    private DungeonCreationCooldown() {}

    private static DungeonExtras.CreationCooldown config() {
        DungeonExtras.Config c = DungeonExtras.config();
        return c == null ? null : c.creationCooldown;
    }

    static void init() {
        Sky2MChat.onChat(message -> {
            Matcher m = ENTERED.matcher(message.text().trim());
            if (!m.matches()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || !m.group(1).equalsIgnoreCase(mc.player.getGameProfile().name())) return;
            until = System.currentTimeMillis() + COOLDOWN_MS;
            warned = false;
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            DungeonExtras.CreationCooldown c = config();
            if (c == null || !c.block || System.currentTimeMillis() >= until || !command.startsWith("joininstance")) return true;
            Sky2MAlerts.chat(Component.literal("Blocked instance creation due to cooldown! ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(left()).withStyle(ChatFormatting.AQUA)));
            return false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            DungeonExtras.CreationCooldown c = config();
            if (c == null || warned || System.currentTimeMillis() < until) return;
            warned = true;
            if (c.chatMessage && c.enabled) Sky2MAlerts.chat(Component.literal("Dungeon creation cooldown is over!").withStyle(ChatFormatting.GRAY));
        });
        Sky2MHuds.register("dungeon_creation_cooldown", "Dungeon Creation Cooldown", DungeonCreationCooldown::visible,
            () -> List.of(Component.literal("Dungeon Creation Cooldown: ").withStyle(ChatFormatting.YELLOW).append(Component.literal(left()).withStyle(ChatFormatting.AQUA))),
            List.of(Component.literal("Dungeon Creation Cooldown: ").withStyle(ChatFormatting.YELLOW).append(Component.literal("18s").withStyle(ChatFormatting.AQUA))), 10, 230);
        Sky2MHuds.setting("dungeon_creation_cooldown", () -> config() != null && config().enabled);
    }

    private static String left() {
        return String.format(Locale.US, "%.0fs", Math.max(0, until - System.currentTimeMillis()) / 1000.0);
    }

    private static boolean visible() {
        DungeonExtras.CreationCooldown c = config();
        if (c == null || !c.enabled || System.currentTimeMillis() >= until || !Compat.isOnSkyblock()) return false;
        if (Sky2MLocation.inDungeon()) return !c.entranceOnly || !DungeonScore.isDungeonStarted();
        return c.showOutside;
    }
}
