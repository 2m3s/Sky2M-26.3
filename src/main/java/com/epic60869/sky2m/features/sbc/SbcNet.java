package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MGlobalChat;
import com.epic60869.sky2m.Sky2MLogin;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Sending to the mod server: plain packets, and packets that need the Mojang login. */
public final class SbcNet {
    public static final String OFFLINE = "S2C offline: can't reach the Sky2M server right now. It reconnects by itself.";

    private SbcNet() {}

    public static boolean online() {
        return Sky2MGlobalChat.currentConnection() != null;
    }

    /** Sends now if connected; otherwise says "S2C offline" and returns false. */
    public static boolean send(JsonObject packet) {
        if (Sky2MGlobalChat.send(packet)) return true;
        Sbc.error(OFFLINE);
        return false;
    }

    /** Sends without telling the player when offline (for background requests). */
    public static boolean sendQuietly(JsonObject packet) {
        return Sky2MGlobalChat.send(packet);
    }

    /**
     * Sends a packet that needs the login (friends, ignore list, settings, cosmetics...), logging in first if
     * needed. Returns false (after saying why) when the server can't be reached.
     */
    public static boolean sendAuthed(JsonObject packet) {
        if (!online()) {
            Sky2MGlobalChat.ensureConnected();
            Sbc.error(OFFLINE);
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            boolean wasLoggedIn = Sky2MLogin.loggedIn();
            Sky2MLogin.whenLoggedIn(() -> Sky2MGlobalChat.send(packet));
            if (wasLoggedIn) return;
            CompletableFuture.delayedExecutor(8, TimeUnit.SECONDS).execute(() -> mc.execute(() -> {
                if (!Sky2MLogin.loggedIn() && !Sky2MLogin.problem().isEmpty()) {
                    Sbc.error("Couldn't log in to the Sky2M server: " + Sky2MLogin.problem());
                }
            }));
        });
        return true;
    }
}
