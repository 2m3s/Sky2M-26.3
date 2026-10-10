package com.epic60869.sky2m.features.sbc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feature flags from the mod server: {@code flags {flags: {"cosmetics.capes": {enabled: false, message: "..."}}}}, sent
 * after hello and on every change. A flag the server hasn't mentioned is on, so the mod keeps working when the server
 * is down. Flag names used by the mod: chat.replies, chat.reactions, chat.items,
 * friends, cosmetics, cosmetics.capes, calendar, pestHighlight, itemCooldowns, settingsSync,
 * crashReports.
 */
public final class Flags {
    private record Flag(boolean enabled, String message) {}

    private static final Map<String, Flag> FLAGS = new ConcurrentHashMap<>();

    private Flags() {}

    /** Whether the feature is on (a missing flag means on). A disabled "cosmetics" also turns off "cosmetics.capes". */
    public static boolean isEnabled(String name) {
        if (FLAGS.isEmpty()) return true;
        Flag flag = FLAGS.get(name);
        if (flag != null && !flag.enabled()) return false;
        int dot = name.lastIndexOf('.');
        return dot < 0 || isEnabled(name.substring(0, dot));
    }

    /** The server's reason for turning the feature off, or a generic one. */
    public static String message(String name) {
        for (String key = name; ; key = key.substring(0, key.lastIndexOf('.'))) {
            Flag flag = FLAGS.get(key);
            if (flag != null && !flag.enabled() && !flag.message().isBlank()) return flag.message();
            if (key.lastIndexOf('.') < 0) break;
        }
        return "This feature is turned off on the Sky2M server right now.";
    }

    /**
     * Checks the flag before a command or button runs; when it's off, says why in chat and returns false.
     */
    public static boolean check(String name) {
        if (isEnabled(name)) return true;
        Sbc.say(Component.literal(message(name)).withStyle(ChatFormatting.RED));
        return false;
    }

    static void update(JsonObject packet) {
        JsonObject flags = Sbc.obj(packet, "flags");
        FLAGS.clear();
        for (Map.Entry<String, JsonElement> entry : flags.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                FLAGS.put(entry.getKey(), new Flag(value.getAsBoolean(), ""));
            } else if (value.isJsonObject()) {
                JsonObject o = value.getAsJsonObject();
                FLAGS.put(entry.getKey(), new Flag(!o.has("enabled") || Sbc.bool(o, "enabled"), Sbc.str(o, "message")));
            }
        }
    }
}
