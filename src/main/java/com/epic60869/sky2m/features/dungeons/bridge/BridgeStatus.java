package com.epic60869.sky2m.features.dungeons.bridge;


/** What one adapter is doing right now - the per-mod status line on the settings tab. */
public enum BridgeStatus {

    OFF("off", 0x9A8C80),
    /** The real mod is installed in this game: it already holds this connection, so we never open a second
     *  one (the protocols cannot rule out a duplicate login kicking the real mod). {@code LocalModBridge}
     *  reads that mod's state instead. */
    SKIPPED_INSTALLED("skipped - installed here, it uses its own connection", 0x9A8C80),
    NOT_ON_HYPIXEL("idle - Hypixel only", 0x9A8C80),
    /** On, on Hypixel, but the grouping this socket needs does not apply yet (no party / not in a run / no
     *  server code / not in P3). The detail says which. */
    WAITING("idle", 0x9A8C80),
    AUTHENTICATING("signing in", 0xFFA040),
    CONNECTING("connecting", 0xFFA040),
    CONNECTED("connected", 0x55FF55),
    RETRYING("reconnecting", 0xFFA040),
    /** Repeated authentication failures: stays down for the rest of the session (toggle the mod's setting off
     *  and on to try again). */
    AUTH_FAILED("sign-in failed - gave up for this session", 0xFF5555);

    public final String label;
    public final int colour;

    BridgeStatus(String label, int colour) {
        this.label = label;
        this.colour = colour;
    }
}
