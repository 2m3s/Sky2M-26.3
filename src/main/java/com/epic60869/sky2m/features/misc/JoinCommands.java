package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/**
 * Quick instance commands: /f0 (Entrance) to /f7 and /m1 to /m7 join that Catacombs floor, /t1 to /t5 join Kuudra
 * (Basic, Hot, Burning, Fiery, Infernal). Each runs Hypixel's /joininstance. Turn off in Misc > Join Commands.
 */
public final class JoinCommands {
    private static final String[] NUMBERS = {"ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN"};
    private static final String[] KUUDRA = {"NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL"};

    private JoinCommands() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.misc.joinCommands;
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            if (!enabled()) return;
            for (int floor = 0; floor <= 7; floor++) {
                String instance = floor == 0 ? "CATACOMBS_ENTRANCE" : "CATACOMBS_FLOOR_" + NUMBERS[floor];
                dispatcher.register(ClientCommands.literal("f" + floor).executes(c -> join(instance)));
                if (floor > 0) {
                    String master = "MASTER_CATACOMBS_FLOOR_" + NUMBERS[floor];
                    dispatcher.register(ClientCommands.literal("m" + floor).executes(c -> join(master)));
                }
            }
            for (int tier = 1; tier <= 5; tier++) {
                String instance = "KUUDRA_" + KUUDRA[tier - 1];
                dispatcher.register(ClientCommands.literal("t" + tier).executes(c -> join(instance)));
            }
        });
    }

    private static int join(String instance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) mc.player.connection.sendCommand("joininstance " + instance);
        return 1;
    }
}
