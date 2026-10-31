package com.epic60869.sky2m.features.foraging;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Foraging > Safari Ticket Reminder: a title and chat line once per SkyBlock day to get your Safari ticket. */
public final class SafariTicketReminder {
    /** SkyBlock year 1 began at this real time; a SkyBlock day is 20 real minutes. */
    private static final long SKYBLOCK_EPOCH_MS = 1_560_275_700_000L;
    private static final long SKYBLOCK_DAY_MS = 1_200_000L;

    private static int ticks;
    private static long lastSafariDay = -1;
    private static boolean wasOnSkyblock;

    private SafariTicketReminder() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(SafariTicketReminder::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return;
        boolean onSkyblock = Sky2MLocation.onSkyblock();
        if (onSkyblock && !wasOnSkyblock) ticks = 0;
        wasOnSkyblock = onSkyblock;
        if (!onSkyblock) return;
        if (++ticks % 40 != 0) return;
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || c.foraging == null || !c.foraging.safariTicketReminder) return;
        long day = Math.floorDiv(System.currentTimeMillis() - SKYBLOCK_EPOCH_MS, SKYBLOCK_DAY_MS);
        if (day == lastSafariDay) return;
        lastSafariDay = day;
        Sky2MAlerts.title(Component.literal("§6Safari Ticket"), Component.literal("§eA new SkyBlock day started: get your Safari ticket!"));
        Sky2MAlerts.chat(Component.literal("§eA new SkyBlock day started: don't forget your §6Safari ticket§e!"));
    }
}
