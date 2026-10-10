package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.UUID;

/**
 * The Sky2M Online (SBC) client: packets from the mod server that aren't handled by the older classes
 * (Sky2MGlobalChat, Sky2MLeaderboards) come here, on the game thread, and are passed to the
 * feature they belong to. Also holds the small JSON and chat helpers those features share.
 */
public final class Sbc {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);

    private Sbc() {}

    public static void init() {
        SbcCrashReports.init();
        SbcServer.init();
        SbcCommands.init();
        SbcItems.init();
        SbcChat.init();
        SbcChatOverlay.init();
        SbcSocial.init();
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (com.epic60869.sky2m.Sky2MKeyMappings.SHARE_ITEM == null
                    || !com.epic60869.sky2m.Sky2MKeyMappings.SHARE_ITEM.matches(event)) return true;
                SbcChat.share("");
                return false;
            });
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                while (com.epic60869.sky2m.Sky2MKeyMappings.SHARE_ITEM != null
                    && com.epic60869.sky2m.Sky2MKeyMappings.SHARE_ITEM.consumeClick()) SbcChat.share("");
            } catch (Exception e) {
                SbcCrashReports.report(e, "S2C tick");
            }
        });
    }

    public static SbcConfig.Online config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? new SbcConfig.Online() : c.online;
    }

    /** A packet the older handlers don't know; returns false when nobody took it. Called on the game thread. */
    public static boolean handle(String type, JsonObject packet) {
        try {
            switch (type) {
                case "flags" -> Flags.update(packet);
                // Errors nothing else asked for (a message the server refused...) were dropped silently; say them.
                case "error" -> {
                    String message = str(packet, "message");
                    error(message.isBlank() ? "The Sky2M server refused that (" + str(packet, "code") + ")." : message);
                }
                case "museumResult" -> com.epic60869.sky2m.features.misc.MuseumTooltip.handle(packet);
                case "updateAvailable", "announcement", "motd" -> SbcServer.handle(type, packet);
                case "reactions", "reactionError", "chatBlocked", "muted", "unmuted" -> SbcChat.handle(type, packet);
                case "cosmetics", "cosmeticsProfile", "cosmeticsError", "badgeEarned" -> SbcCosmetics.handle(type, packet);
                case "online", "whoResult", "friends", "friendEvent", "socialSettings", "ignoreResult", "ignoreList", "socialError" -> SbcSocial.handle(type, packet);
                case "settingsSaved", "settings", "settingsSlots", "settingsDeleted", "settingsError" -> SbcSettingsSync.handle(type, packet);
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            SbcCrashReports.report(e, "packet " + type);
        }
        return true;
    }

    /** Called by Sky2MGlobalChat on the game thread whenever a new connection is open (after hello). */
    public static void onConnected() {
        SbcSocial.onConnected();
    }

    /** Called on the game thread when the connection closes. */
    public static void onDisconnected() {
    }

    // ------------------------------------------------------------------------------------------------ chat helpers

    /** "[S2M] message" in chat. Safe from any thread. */
    public static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(prefix().append(message));
        });
    }

    public static void error(String message) {
        say(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    public static MutableComponent prefix() {
        return Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    /** A popup in the corner of the screen. Safe from any thread. */
    public static void toast(Component title, Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.add(mc.gui.toastManager(), TOAST, title, message));
    }

    public static UUID self() {
        return Minecraft.getInstance().getUser().getProfileId();
    }

    public static boolean isSelf(String uuid) {
        UUID parsed = uuid(uuid);
        return parsed != null && parsed.equals(self());
    }

    /** "1h 5m", "3m 20s" or "12s". */
    public static String duration(long ms) {
        long seconds = Math.max(0, ms / 1000);
        if (seconds >= 86400) return seconds / 86400 + "d " + seconds % 86400 / 3600 + "h";
        if (seconds >= 3600) return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
        if (seconds >= 60) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds + "s";
    }

    /** "5m ago", "2h ago", "3d ago". */
    public static String ago(long at) {
        if (at <= 0) return "never";
        long ms = System.currentTimeMillis() - at;
        if (ms < 60_000) return "just now";
        return duration(ms).replaceFirst(" .*", "") + " ago";
    }

    public static String number(long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    /** 1.2k, 3.4m, 5.6b. */
    public static String shortNumber(double value) {
        double abs = Math.abs(value);
        if (abs >= 1e9) return String.format(Locale.ENGLISH, "%.1fb", value / 1e9);
        if (abs >= 1e6) return String.format(Locale.ENGLISH, "%.1fm", value / 1e6);
        if (abs >= 1e3) return String.format(Locale.ENGLISH, "%.1fk", value / 1e3);
        return String.format(Locale.ENGLISH, "%.0f", value);
    }

    /** "#RRGGBB" to 0xRRGGBB, or {@code fallback}. */
    public static int hex(String hex, int fallback) {
        if (hex == null) return fallback;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return h.matches("[0-9a-fA-F]{6}") ? Integer.parseInt(h, 16) : fallback;
    }

    /** MoulConfig's colour string ("chroma:alpha:r:g:b") to 0xAARRGGBB. */
    public static int colour(String value, int fallback) {
        try {
            String[] parts = value.split(":");
            int n = parts.length;
            int a = Integer.parseInt(parts[n - 4]);
            int r = Integer.parseInt(parts[n - 3]);
            int g = Integer.parseInt(parts[n - 2]);
            int b = Integer.parseInt(parts[n - 1]);
            return a << 24 | r << 16 | g << 8 | b;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------------------------ JSON helpers

    public static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static long lng(JsonObject o, String key, long fallback) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static double dbl(JsonObject o, String key, double fallback) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    public static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    public static boolean hasObj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject();
    }

    public static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    public static UUID uuid(String text) {
        if (text == null) return null;
        String t = text.replace("-", "");
        if (!t.matches("[0-9a-fA-F]{32}")) return null;
        return UUID.fromString(t.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
    }

    public static JsonObject packet(String type) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        return p;
    }
}
