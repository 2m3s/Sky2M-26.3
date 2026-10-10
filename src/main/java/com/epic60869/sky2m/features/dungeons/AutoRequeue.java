package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.regex.Pattern;

/**
 * Auto Requeue, like NoammAddons': when a dungeon run ends (Hypixel's "> EXTRA STATS <" line), /instancerequeue a few
 * seconds later, so the party goes straight into another run of the same floor. It doesn't requeue when someone left
 * or disconnected meanwhile, and /s2 requeue cancel skips the next one. /instancerequeue only works for the leader.
 */
public final class AutoRequeue {
    private static final Pattern MEMBER_GONE = Pattern.compile("(?:has left the party|has been removed from the party|was removed from your party because they disconnected|has disconnected)");
    private static long requeueAt;
    private static boolean skipNext;

    private AutoRequeue() {}

    private static FeatureConfigs.AutoRequeue config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.autoRequeue;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            FeatureConfigs.AutoRequeue c = config();
            if (c == null || !c.enabled) return;
            String text = message.text().trim();
            if (text.equals("> EXTRA STATS <") && Sky2MLocation.inDungeon()) {
                if (skipNext) {
                    skipNext = false;
                    say("Skipped this requeue.");
                    return;
                }
                requeueAt = System.currentTimeMillis() + c.delaySeconds * 1000L;
                if (c.announce) say("Requeueing in " + c.delaySeconds + "s. /s2 requeue cancel to stop.");
            } else if (requeueAt != 0 && c.cancelOnLeave && MEMBER_GONE.matcher(text).find()) {
                requeueAt = 0;
                say("Not requeueing: someone left the party.");
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (requeueAt == 0 || System.currentTimeMillis() < requeueAt) return;
            requeueAt = 0;
            FeatureConfigs.AutoRequeue c = config();
            if (c == null || !c.enabled || mc.getConnection() == null) return;
            mc.getConnection().sendCommand("instancerequeue");
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("requeue")
                    .then(ClientCommands.literal("cancel").executes(ctx -> {
                        if (requeueAt != 0) {
                            requeueAt = 0;
                            say("Requeue cancelled.");
                        } else {
                            skipNext = true;
                            say("The next run won't requeue.");
                        }
                        return 1;
                    }))));
            }
        });
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
    }
}
