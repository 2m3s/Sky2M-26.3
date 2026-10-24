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
 * is down. Flag names used by the mod (the server's flag-catalog.js and docs/MOD-PROTOCOL.md list them with
 * descriptions): chat, chat.replies, chat.reactions, chat.items, chat.rooms, nicknames, friends, who, cosmetics,
 * cosmetics.emblems, cosmetics.capes, cosmetics.models, leaderboards, leaderboards.slayerPbs, partyFinder,
 * partyFinder.stats, calendar, pestHighlight, itemCooldowns, museumTooltip, screenshots, settingsSync, crashReports,
 * reportThreads.
 */
public final class Flags {
    private record Flag(boolean enabled, String message) {}

    private static final Map<String, Flag> FLAGS = new ConcurrentHashMap<>();

    /**
     * The flag that switches off each server packet type, so one kill switch covers a whole online feature without
     * a check at every call site. Staff, login and flag packets are never listed (staff must always get through).
     */
    private static final Map<String, String> PACKET_FLAGS = Map.ofEntries(
        Map.entry("react", "chat.reactions"), Map.entry("reactions", "chat.reactions"), Map.entry("reactionError", "chat.reactions"),
        Map.entry("roomJoin", "chat.rooms"), Map.entry("roomLeave", "chat.rooms"), Map.entry("roomList", "chat.rooms"),
        Map.entry("roomResult", "chat.rooms"), Map.entry("roomEvent", "chat.rooms"), Map.entry("rooms", "chat.rooms"),
        Map.entry("nickSet", "nicknames"), Map.entry("nameTagGet", "nicknames"), Map.entry("nameTagPublish", "nicknames"),
        Map.entry("nameTagTable", "nicknames"), Map.entry("nameTagUpdate", "nicknames"),
        Map.entry("who", "who"), Map.entry("whoResult", "who"), Map.entry("online", "who"),
        Map.entry("cosmeticsGet", "cosmetics"), Map.entry("cosmeticsSelect", "cosmetics"),
        Map.entry("leaderboardGet", "leaderboards"), Map.entry("leaderboardProgress", "leaderboards"),
        Map.entry("statLeaderboardGet", "leaderboards"),
        Map.entry("slayerPb", "leaderboards.slayerPbs"), Map.entry("slayerPbSync", "leaderboards.slayerPbs"),
        Map.entry("slayerPbSummary", "leaderboards.slayerPbs"), Map.entry("slayerPbGet", "leaderboards.slayerPbs"),
        Map.entry("partyData", "partyFinder.stats"), Map.entry("partyDataJoin", "partyFinder.stats"),
        Map.entry("partyDataLeave", "partyFinder.stats"),
        Map.entry("museum", "museumTooltip"), Map.entry("museumResult", "museumTooltip"),
        Map.entry("uploadToken", "screenshots"),
        Map.entry("runSummaryPost", "chat.runSummary"), Map.entry("runSummaryGet", "chat.runSummary"),
        Map.entry("runSummary", "chat.runSummary"), Map.entry("runSummaryMissing", "chat.runSummary"),
        Map.entry("runSummaryPosted", "chat.runSummary"), Map.entry("runSummaryError", "chat.runSummary"));

    private Flags() {}

    /** The flag that switches this packet type off, or null when none does. Packets pf* belong to the party finder. */
    public static String flagForPacket(String type) {
        if (type == null) return null;
        String flag = PACKET_FLAGS.get(type);
        if (flag != null) return flag;
        return type.startsWith("pf") && type.length() > 2 && Character.isUpperCase(type.charAt(2)) ? "partyFinder" : null;
    }

    /** Whether a packet of this type may be sent or handled (its feature's flag is on, or it has none). */
    public static boolean allowsPacket(String type) {
        String flag = flagForPacket(type);
        return flag == null || isEnabled(flag);
    }

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
