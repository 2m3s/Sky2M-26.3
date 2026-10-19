package com.epic60869.sky2m.features.profileviewer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

/** /s2 pv [player] (and /pv, if turned on) opens {@link ProfileViewerScreen}; with no name, your own profile. */
public final class ProfileViewer {
    private ProfileViewer() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(command("pv")).then(command("profile")));
            }
            // SBPV and NEU use /pv too, so Sky2M only takes it over when asked.
            Sky2MConfig c = Sky2MConfig.current();
            if (c != null && c.misc.profileViewer.pvCommand) dispatcher.register(command("pv"));
        });
        // The networth prices (a few MB), NEU's level tables and the item list, in the background once the game has
        // started, so the first /pv doesn't wait on them.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            com.epic60869.sky2m.features.profileviewer.networth.SkyHelperNetworth.warm();
            ProfileApi.loadTables();
            ProfileApi.warmBackend();
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command(String name) {
        return ClientCommands.literal(name)
            .executes(ctx -> open(Minecraft.getInstance().getUser().getName()))
            .then(ClientCommands.argument("player", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(onlineNames(), builder))
                .executes(ctx -> open(StringArgumentType.getString(ctx, "player"))));
    }

    private static Iterable<String> onlineNames() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return java.util.List.of();
        return connection.getOnlinePlayers().stream().map(p -> p.getProfile().name())
            .filter(n -> n.matches("\\w{1,16}")).toList();
    }

    public static int open(String name) {
        Compat.queueOpenScreen(new ProfileViewerScreen(name));
        return 1;
    }
}
