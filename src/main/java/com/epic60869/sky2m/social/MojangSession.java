package com.epic60869.sky2m.social;

import net.minecraft.client.Minecraft;

/**
 * Every Sky2M sign-in through Mojang's session server ({@code joinServer}: the Sky2M server, the profile viewer's
 * backend, the sim relay, reports) goes through here. Mojang limits how often an account may call it, and joining a
 * Minecraft server uses the same limit: after a failure (a 429, or the service being down) Sky2M waits before trying
 * again, 1 minute and doubling up to 10, so its retries don't keep you from joining Hypixel.
 */
public final class MojangSession {
    private static final long FIRST_WAIT_MS = 60_000L, MAX_WAIT_MS = 10 * 60_000L;
    private static long retryAt, waitMs = FIRST_WAIT_MS;

    private MojangSession() {}

    /** Joins {@code serverId} as the current user; throws without asking Mojang while waiting after a failure. */
    public static void join(String serverId) throws Exception {
        long now = System.currentTimeMillis();
        synchronized (MojangSession.class) {
            if (now < retryAt) throw new IllegalStateException("Mojang's login server refused recently, trying again in " + ((retryAt - now) / 1000 + 1) + "s");
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            mc.services().sessionService().joinServer(mc.getUser().getProfileId(), mc.getUser().getAccessToken(), serverId);
        } catch (Exception e) {
            synchronized (MojangSession.class) {
                retryAt = System.currentTimeMillis() + waitMs;
                waitMs = Math.min(waitMs * 2, MAX_WAIT_MS);
            }
            throw e;
        }
        synchronized (MojangSession.class) {
            waitMs = FIRST_WAIT_MS;
        }
    }

    /** Whether Sky2M is waiting after a failed sign-in. */
    public static synchronized boolean waiting() {
        return System.currentTimeMillis() < retryAt;
    }
}
