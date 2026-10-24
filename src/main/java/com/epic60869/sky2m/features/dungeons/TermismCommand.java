package com.epic60869.sky2m.features.dungeons;

/** Registers /s2 termism without loading the (large) screen class at startup. */
public final class TermismCommand {
    private TermismCommand() {}

    /** /s2 termism opens the practice menu. */
    public static void registerCommand() {
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : com.epic60869.sky2m.custom.util.Compat.COMMAND_ROOTS) {
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(root)
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("termism").executes(c -> {
                        open();
                        return 1;
                    })));
            }
        });
    }

    private static void open() {
        com.epic60869.sky2m.custom.util.Compat.queueOpenScreen(new TermismScreen());
    }
}
