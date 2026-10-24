package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hoppity's Hunt: waypoints for every known egg location while an egg is waiting, and a list of the eggs that have
 * appeared. Ported from SkyHanni's HoppityEggLocator, HoppityEggLocations and HoppityEggsManager
 * (https://github.com/hannibal002/SkyHanni, LGPL-2.1), without the Egglocator particle guess: all the island's
 * locations are shown instead, and the ones you already got a rabbit from are hidden.
 */
public final class HoppityFeatures {
    private static final String HUD = "events_hoppity_eggs";
    /** A meal egg is replaced by the next one a SkyBlock day (20 minutes) later. */
    private static final long EGG_LIFETIME_MS = 20 * 60 * 1000L;
    private static final Pattern SPAWNED = Pattern.compile("^HOPPITY'S HUNT A Chocolate (\\S+) Egg has appeared!");
    private static final Pattern FOUND = Pattern.compile("^HOPPITY'S HUNT You found a Chocolate (\\S+) Egg ");
    private static final Pattern ALREADY = Pattern.compile("^You have already collected this Chocolate (\\S+) Egg");
    private static final String COLLECTED_KEY = "hoppityCollected";

    /** Island name in SkyHanni's constants -> the tab list's area name. */
    private static final Map<String, String> AREAS = Map.ofEntries(
        Map.entry("THE_PARK", "The Park"), Map.entry("HUB", "Hub"), Map.entry("GOLD_MINES", "Gold Mine"),
        Map.entry("CRYSTAL_HOLLOWS", "Crystal Hollows"), Map.entry("DEEP_CAVERNS", "Deep Caverns"),
        Map.entry("THE_FARMING_ISLANDS", "The Farming Islands"), Map.entry("THE_END", "The End"),
        Map.entry("DWARVEN_MINES", "Dwarven Mines"), Map.entry("SPIDER_DEN", "Spider's Den"),
        Map.entry("CRIMSON_ISLE", "Crimson Isle"), Map.entry("DUNGEON_HUB", "Dungeon Hub"),
        Map.entry("BACKWATER_BAYOU", "Backwater Bayou"), Map.entry("GALATEA", "Galatea"),
        Map.entry("LOTUS_ATOLL", "Lotus Atoll"), Map.entry("TORRHUS_CANYON", "Torrhus Canyon"));

    /** Lower-case area name -> egg locations. */
    private static volatile Map<String, List<BlockPos>> locations = Map.of();
    /** Meal name -> when its "has appeared" message arrived. */
    private static final Map<String, Long> ACTIVE = new LinkedHashMap<>();
    private static Set<String> collected;

    private HoppityFeatures() {}

    static void init() {
        SkyHanniRepo.load("HoppityEggLocations", HoppityFeatures::readLocations);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MWorldRender.register(HoppityFeatures::render);
        Sky2MHuds.register(HUD, "Unclaimed Hoppity Eggs", () -> {
            EventsConfig c = EventsFeatures.config();
            return c != null && c.hoppity.unclaimedEggs && EventsFeatures.onSkyblock();
        }, HoppityFeatures::eggLines, List.of(
            Component.literal("Hoppity Eggs").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            Component.literal("Lunch: ").withStyle(ChatFormatting.GOLD).append(Component.literal("12m 30s").withStyle(ChatFormatting.AQUA))), 10, 120);
        Sky2MHuds.setSettingsSearch(HUD, "Unclaimed Eggs");
        Sky2MHuds.setting(HUD, () -> EventsFeatures.config() != null && EventsFeatures.config().hoppity.unclaimedEggs);
    }

    private static void readLocations(JsonObject json) {
        JsonElement api = json.get("apiEggLocations");
        if (api == null || !api.isJsonObject()) return;
        Map<String, List<BlockPos>> read = new HashMap<>();
        for (Map.Entry<String, JsonElement> island : api.getAsJsonObject().entrySet()) {
            String area = AREAS.get(island.getKey());
            if (area == null || !island.getValue().isJsonObject()) continue;
            List<BlockPos> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> egg : island.getValue().getAsJsonObject().entrySet()) {
                String[] parts = egg.getValue().getAsString().split(":");
                if (parts.length != 3) continue;
                try {
                    list.add(new BlockPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
                } catch (NumberFormatException ignored) {}
            }
            read.put(area.toLowerCase(Locale.ROOT), List.copyOf(list));
        }
        locations = read;
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        EventsConfig c = EventsFeatures.config();
        if (c == null) return;
        Matcher m = SPAWNED.matcher(text);
        if (m.find()) {
            synchronized (ACTIVE) {
                ACTIVE.put(m.group(1), System.currentTimeMillis());
            }
            return;
        }
        m = FOUND.matcher(text);
        if (m.find()) {
            if (m.group(1).equalsIgnoreCase("Hitman")) return;
            synchronized (ACTIVE) {
                ACTIVE.remove(m.group(1));
            }
            markNearestCollected();
            return;
        }
        m = ALREADY.matcher(text);
        if (m.find()) {
            // The egg is not here to find any more.
            synchronized (ACTIVE) {
                ACTIVE.remove(m.group(1));
            }
        }
    }

    private static void pruneExpired() {
        long now = System.currentTimeMillis();
        synchronized (ACTIVE) {
            ACTIVE.values().removeIf(at -> now - at > EGG_LIFETIME_MS);
        }
    }

    // ------------------------------------------------------------------------------------------------ collected

    private static Set<String> collected() {
        if (collected == null) {
            collected = new HashSet<>();
            for (JsonElement e : EventsFeatures.array(COLLECTED_KEY)) collected.add(e.getAsString());
        }
        return collected;
    }

    private static String key(String area, BlockPos pos) {
        return area.toLowerCase(Locale.ROOT) + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
    }

    private static void markNearestCollected() {
        Minecraft mc = Minecraft.getInstance();
        List<BlockPos> list = locations.get(Sky2MLocation.area().toLowerCase(Locale.ROOT));
        if (mc.player == null || list == null) return;
        BlockPos best = null;
        double bestDistance = 12 * 12;
        for (BlockPos pos : list) {
            double d = pos.distToCenterSqr(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (d < bestDistance) {
                bestDistance = d;
                best = pos;
            }
        }
        if (best == null) return;
        if (collected().add(key(Sky2MLocation.area(), best))) {
            JsonArray array = EventsFeatures.array(COLLECTED_KEY);
            array.add(key(Sky2MLocation.area(), best));
            EventsFeatures.save();
        }
    }

    static void resetCollected() {
        collected = new HashSet<>();
        EventsFeatures.remove(COLLECTED_KEY);
        EventsFeatures.save();
        EventsFeatures.say(Component.literal("Forgot the Hoppity egg locations you collected.").withStyle(ChatFormatting.YELLOW));
    }

    // ------------------------------------------------------------------------------------------------ display

    private static List<Component> eggLines() {
        pruneExpired();
        List<Component> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (ACTIVE) {
            if (ACTIVE.isEmpty()) return lines;
            lines.add(Component.literal("Hoppity Eggs").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
            for (Map.Entry<String, Long> e : ACTIVE.entrySet()) {
                long left = EGG_LIFETIME_MS - (now - e.getValue());
                lines.add(Component.literal(e.getKey() + ": ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(EventsFeatures.duration(left)).withStyle(left < 5 * 60 * 1000L ? ChatFormatting.RED : ChatFormatting.AQUA)));
            }
        }
        return lines;
    }

    private static void render(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector) {
        EventsConfig c = EventsFeatures.config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.hoppity.eggWaypoints || mc.player == null || !EventsFeatures.onSkyblock()) return;
        pruneExpired();
        synchronized (ACTIVE) {
            if (ACTIVE.isEmpty()) return;
        }
        String area = Sky2MLocation.area();
        List<BlockPos> list = locations.get(area.toLowerCase(Locale.ROOT));
        if (list == null) return;
        Set<String> done = collected();
        Vec3 eye = mc.player.position();
        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (BlockPos pos : list) {
            boolean isCollected = done.contains(key(area, pos));
            if (isCollected && c.hoppity.hideCollected) continue;
            Vec3 centre = Vec3.atCenterOf(pos);
            double distance = centre.distanceTo(eye);
            float[] colour = isCollected ? new float[]{1f, 0.2f, 0.2f} : new float[]{0.2f, 1f, 0.3f};
            collector.submitFilledBox(pos, colour, isCollected ? 0.3f : 0.5f, true);
            collector.submitText(Component.literal(isCollected ? "Egg (collected)" : "Egg").withStyle(isCollected ? ChatFormatting.RED : ChatFormatting.GREEN)
                .append(Component.literal(" " + Math.round(distance) + "m").withStyle(ChatFormatting.GRAY)), centre.add(0, 0.9, 0), 1.2f, true);
            if (!isCollected && distance < closestDistance) {
                closestDistance = distance;
                closest = pos;
            }
        }
        if (c.hoppity.lineToClosest && closest != null) {
            collector.submitLineFromCursor(Vec3.atCenterOf(closest), new float[]{0.2f, 1f, 0.3f}, 0.9f, 2f);
        }
    }
}
