package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * SkyHanni's Auto Join SkyBlock: when you join Hypixel and land in a lobby, run /skyblock. Only once per connection,
 * so going to /lobby or another game on purpose afterwards isn't undone. Turn on in Misc > Auto Join SkyBlock.
 */
public final class AutoJoinSkyblock {
    /** Ticks to wait after joining, so the scoreboard has loaded and an already-SkyBlock join isn't mistaken for a lobby. */
    private static final int DELAY_TICKS = 60;

    private static boolean pending;
    private static int ticks;

    private AutoJoinSkyblock() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            // JOIN also fires on Hypixel's server switches: only the first one after connecting counts.
            if (ticks < 0) return;
            pending = true;
            ticks = 0;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            pending = false;
            ticks = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(AutoJoinSkyblock::tick);
    }

    private static void tick(Minecraft mc) {
        if (!pending || mc.player == null) return;
        if (++ticks < DELAY_TICKS) return;
        pending = false;
        ticks = -1; // done for this connection
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.autoJoinSkyblock) return;
        // On Hypixel (not Compat.isOnSkyblock, which SkyBlock Only turns off in the lobby) and not in SkyBlock yet.
        if (!com.epic60869.sky2m.Sky2MCustom.isHypixel(mc) || Sky2MLocation.onSkyblock()) return;
        mc.player.connection.sendCommand("skyblock");
    }
}
