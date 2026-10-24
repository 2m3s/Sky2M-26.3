package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MErrors;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** /s2 health: Sky2M's version, how long it took to start, the Sky2M chat connection, related mods and this session's errors. */
public final class Sky2MHealth {
    /** Companion and overlapping mods worth listing; the others installed aren't Sky2M's business. */
    private static final List<String> COMPANIONS = List.of("noammaddons", "odin", "devonian", "skysoft", "skyhanni", "skyblocker",
        "customscoreboard", "bobby", "packdisabler", "skyblock-item-list");

    private Sky2MHealth() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("health").executes(c -> {
                    show();
                    return 1;
                })));
            }
        });
    }

    private static void show() {
        FabricLoader loader = FabricLoader.getInstance();
        String version = loader.getModContainer("sky2m").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("dev");
        List<String> companions = new ArrayList<>();
        for (String id : COMPANIONS) {
            loader.getModContainer(id).ifPresent(m -> companions.add(m.getMetadata().getName() + " " + m.getMetadata().getVersion().getFriendlyString()));
        }
        loader.getAllMods().stream().filter(m -> m.getMetadata().getId().startsWith("clip") && !COMPANIONS.contains(m.getMetadata().getId()))
            .forEach(m -> companions.add(m.getMetadata().getName() + " " + m.getMetadata().getVersion().getFriendlyString()));
        int distinct = 0;
        int total = 0;
        for (Sky2MErrors.Record record : Sky2MErrors.records()) {
            distinct++;
            total += record.count();
        }
        boolean online = Sky2MGlobalChat.isOnline();
        long startup = Sky2MMod.startupMillis();
        line("Sky2M " + version, ChatFormatting.AQUA);
        line("Startup: " + (startup < 0 ? "unknown" : startup + " ms"), ChatFormatting.GRAY);
        line("Sky2M chat (S2C): " + (online ? "connected" : "not connected"), online ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        line("Related mods: " + (companions.isEmpty() ? "none" : String.join(", ", companions)), ChatFormatting.GRAY);
        line("Errors this session: " + (distinct == 0 ? "none" : distinct + " distinct, " + total + " total (/s2 errors)"),
            distinct == 0 ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static void line(String text, ChatFormatting colour) {
        Sky2MProfiles.say(Component.literal(text).withStyle(colour));
    }
}
