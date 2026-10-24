package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Winter events: the Refined Bottle of Jyrre timer, the Frozen Treasure tracker and the Reindrake warp message.
 * Ported from SkyHanni's JyrreTimer, FrozenTreasureTracker and ReindrakeWarpHelper
 * (https://github.com/hannibal002/SkyHanni, features/event/winter/ and features/event/jerry/, LGPL-2.1).
 */
public final class WinterFeatures {
    private static final String JYRRE_HUD = "events_winter_jyrre";
    private static final String FROZEN_HUD = "events_winter_frozen";
    private static final String JYRRE_KEY = "jyrreEnd";
    private static final String FROZEN_KEY = "frozenTreasure";
    private static final long JYRRE_MS = 60 * 60 * 1000L;
    private static final String WORKSHOP = "Jerry's Workshop";
    private static final Pattern REINDRAKE = Pattern.compile("^WOAH! .+ summoned (?:a Reindrake|TWO Reindrakes) from the depths!");
    private static final Pattern TREASURE = Pattern.compile("^FROZEN TREASURE! You found (.+)!$");
    private static final String COMPACT = "COMPACT! You found an Enchanted Ice!";

    /** What the cave drops, how many one find gives and how much ice that is worth each. */
    private enum Treasure {
        WHITE_GIFT("White Gift", ChatFormatting.WHITE, 1, 0),
        GREEN_GIFT("Green Gift", ChatFormatting.GREEN, 1, 0),
        RED_GIFT("Red Gift", ChatFormatting.RED, 1, 0),
        PACKED_ICE("Packed Ice", ChatFormatting.WHITE, 32, 9),
        ENCHANTED_ICE("Enchanted Ice", ChatFormatting.GREEN, 9, 160),
        ENCHANTED_PACKED_ICE("Enchanted Packed Ice", ChatFormatting.BLUE, 1, 25600),
        ICE_BAIT("Ice Bait", ChatFormatting.GREEN, 16, 0),
        GLOWY_CHUM_BAIT("Glowy Chum Bait", ChatFormatting.GREEN, 16, 0),
        GLACIAL_FRAGMENT("Glacial Fragment", ChatFormatting.DARK_PURPLE, 1, 0),
        GLACIAL_TALISMAN("Glacial Talisman", ChatFormatting.WHITE, 1, 0),
        FROZEN_BAIT("Frozen Bait", ChatFormatting.BLUE, 1, 0),
        EINARYS_RED_HOODIE("Einary's Red Hoodie", ChatFormatting.RED, 1, 0);

        final String label;
        final ChatFormatting colour;
        final int defaultAmount;
        final int iceMultiplier;

        Treasure(String label, ChatFormatting colour, int defaultAmount, int iceMultiplier) {
            this.label = label;
            this.colour = colour;
            this.defaultAmount = defaultAmount;
            this.iceMultiplier = iceMultiplier;
        }
    }

    private WinterFeatures() {}

    private static EventsConfig.Winter config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.winter;
    }

    static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register(JYRRE_HUD, "Refined Jyrre Timer", () -> {
            EventsConfig.Winter c = config();
            return c != null && c.jyrreTimer && Sky2MLocation.onSkyblock();
        }, WinterFeatures::jyrreLines, List.of(
            Component.literal("Jyrre Boost: ").withStyle(ChatFormatting.GREEN).append(Component.literal("42m 10s").withStyle(ChatFormatting.AQUA))), 10, 180);
        Sky2MHuds.setSettingsSearch(JYRRE_HUD, "Refined Jyrre Timer");
        Sky2MHuds.setting(JYRRE_HUD, () -> config() != null && config().jyrreTimer);

        Sky2MHuds.register(FROZEN_HUD, "Frozen Treasure Tracker", () -> {
            EventsConfig.Winter c = config();
            if (c == null || !c.frozenTreasure || !Sky2MLocation.onSkyblock() || !Sky2MLocation.areaIs(WORKSHOP)) return false;
            return !c.frozenOnlyCave || Sky2MLocation.location().contains("Glacial Cave");
        }, WinterFeatures::frozenLines, List.of(
            Component.literal("Frozen Treasure Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
            Component.literal("12 Treasures Mined").withStyle(ChatFormatting.GOLD),
            Component.literal("1.2k Total Ice").withStyle(ChatFormatting.DARK_AQUA)), 10, 200);
        Sky2MHuds.setSettingsSearch(FROZEN_HUD, "Frozen Treasure Tracker");
        Sky2MHuds.setting(FROZEN_HUD, () -> config() != null && config().frozenTreasure);
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        EventsConfig.Winter c = config();
        if (c == null) return;
        if (c.jyrreTimer && text.contains("You consumed a Refined Bottle of Jyrre")) {
            EventsFeatures.put(JYRRE_KEY, System.currentTimeMillis() + JYRRE_MS);
            EventsFeatures.save();
            return;
        }
        if (c.reindrakeWarp && Sky2MLocation.areaIs(WORKSHOP) && REINDRAKE.matcher(text).find()) {
            EventsFeatures.sayClickable("A Reindrake was detected. Click to warp to the Winter Island spawn!", "/warp winter", "Click to run /warp winter");
            return;
        }
        if (!c.frozenTreasure) return;
        if (text.equals(COMPACT)) {
            add("compact");
            return;
        }
        Matcher m = TREASURE.matcher(text);
        if (!m.find()) return;
        for (Treasure treasure : Treasure.values()) {
            if (treasure.label.equals(m.group(1))) {
                add("mined");
                add(treasure.name());
                return;
            }
        }
    }

    private static void add(String key) {
        JsonObject data = EventsFeatures.object(FROZEN_KEY);
        data.addProperty(key, count(key) + 1);
        EventsFeatures.save();
    }

    private static long count(String key) {
        var e = EventsFeatures.object(FROZEN_KEY).get(key);
        try {
            return e == null ? 0 : e.getAsLong();
        } catch (Exception ex) {
            return 0;
        }
    }

    static void resetTracker() {
        EventsFeatures.remove(FROZEN_KEY);
        EventsFeatures.save();
        Minecraft.getInstance().execute(() -> EventsFeatures.say(Component.literal("Reset the Frozen Treasure Tracker.").withStyle(ChatFormatting.YELLOW)));
    }

    // ------------------------------------------------------------------------------------------------ display

    private static List<Component> jyrreLines() {
        long left = EventsFeatures.number(JYRRE_KEY, 0) - System.currentTimeMillis();
        EventsConfig.Winter c = config();
        if (left <= 0) {
            if (c == null || !c.jyrreInactive) return List.of();
            return List.of(Component.literal("Jyrre Boost: ").withStyle(ChatFormatting.GREEN).append(Component.literal("Inactive!").withStyle(ChatFormatting.RED)));
        }
        return List.of(Component.literal("Jyrre Boost: ").withStyle(ChatFormatting.GREEN).append(Component.literal(EventsFeatures.duration(left)).withStyle(ChatFormatting.AQUA)));
    }

    private static List<Component> frozenLines() {
        List<Component> lines = new ArrayList<>();
        long ice = count("compact") * 160;
        for (Treasure t : Treasure.values()) ice += count(t.name()) * t.defaultAmount * t.iceMultiplier;
        lines.add(Component.literal("Frozen Treasure Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        lines.add(Component.literal(EventsFeatures.grouped(count("mined")) + " Treasures Mined").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal(EventsFeatures.compact(ice) + " Total Ice").withStyle(ChatFormatting.DARK_AQUA));
        lines.add(Component.literal(EventsFeatures.grouped(count("compact")) + " Compact Procs").withStyle(ChatFormatting.DARK_GRAY));
        for (Treasure t : Treasure.values()) {
            long amount = count(t.name());
            if (amount > 0) lines.add(Component.literal(EventsFeatures.grouped(amount) + " " + t.label).withStyle(t.colour));
        }
        return lines;
    }
}
