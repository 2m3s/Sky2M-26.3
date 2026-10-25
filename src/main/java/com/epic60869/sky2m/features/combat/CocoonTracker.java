package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The mob you cocooned, named over its cocoon with a timer until it opens. Hypixel's chat line ("CAUGHT! You cocooned a
 * Alligator!") doesn't say where the cocoon is or how long it takes, so the cocoon is the head-wearing armor stand that
 * appears near you right after it, and the label goes once that stand is gone. How long cocoons took to open is
 * remembered (config/sky2m/cocoon-time.json), so after the first one the timer counts down instead of up.
 */
public final class CocoonTracker {
    /** How long after the chat line to look for the cocoon, and how far from you. */
    private static final long FIND_MS = 2_000L;
    private static final double FIND_RANGE = 12;
    /** A cocoon that never opens (you left, it despawned) stops being shown after this. */
    private static final long GIVE_UP_MS = 60_000L;

    /** How long a cocoon takes to hatch before one has been timed (a slayer boss's took 6.55s). */
    private static final long DEFAULT_OPEN_MS = 6_500L;
    /** A cocoon can appear in the same tick as the chat line or just before it: stands this new still count. */
    private static final long SPAWNED_BEFORE_MS = 1_500L;

    private static String mob;
    private static long cocoonedAt;
    /** When each armor stand around was first seen, so the cocoon is a stand that appeared around the chat line. */
    private static final Map<Integer, Long> firstSeen = new HashMap<>();
    private static ArmorStand cocoon;
    /** Average time cocoons took to open, in ms (0 until one has been seen), and how many that is. */
    private static long openMs;
    private static int seen;
    private static boolean loaded;

    private CocoonTracker() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            clear();
            firstSeen.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(CocoonTracker::tick);
        Sky2MWorldRender.register(collector -> {
            Minecraft mc = Minecraft.getInstance();
            if (cocoon == null || mc.player == null || !labelOn()) return;
            Vec3 top = cocoon.position().add(0, cocoon.getBbHeight() + 0.9, 0);
            collector.submitText(Component.literal(mob).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), top, 1.5f, true);
            collector.submitText(timer(), top.add(0, -0.45, 0), 1.1f, true);
        });
    }

    /** "CAUGHT! You cocooned a X!" (or your slayer boss): from CombatFeatures. */
    static void onCocoon(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !labelOn()) return;
        load();
        mob = name;
        cocoonedAt = System.currentTimeMillis();
        // The cocoon may already be here (spawned with the chat line): look this tick too.
        cocoon = find(mc);
    }

    private static void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (mc.level != null && labelOn()) {
            Set<Integer> present = new HashSet<>();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof ArmorStand)) continue;
                present.add(e.getId());
                firstSeen.putIfAbsent(e.getId(), now);
            }
            firstSeen.keySet().retainAll(present);
        }
        if (mob == null) return;
        if (mc.level == null || mc.player == null || now - cocoonedAt > GIVE_UP_MS) {
            clear();
            return;
        }
        if (cocoon == null) {
            if (now - cocoonedAt > FIND_MS) {
                clear();
                return;
            }
            cocoon = find(mc);
            return;
        }
        if (cocoon.isRemoved() || mc.level.getEntity(cocoon.getId()) != cocoon) {
            opened(now - cocoonedAt);
            clear();
        }
    }

    /**
     * The nearest armor stand wearing a head (the cocoon model) that appeared around the chat line. Hypixel can make
     * it a marker stand, so markers count; name tags don't wear heads.
     */
    private static ArmorStand find(Minecraft mc) {
        ArmorStand best = null;
        double bestDistance = FIND_RANGE * FIND_RANGE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand)) continue;
            // Not seen before this tick: it's new now.
            long seen = firstSeen.getOrDefault(stand.getId(), System.currentTimeMillis());
            if (seen < cocoonedAt - SPAWNED_BEFORE_MS) continue;
            if (stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) continue;
            double distance = stand.distanceToSqr(mc.player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stand;
            }
        }
        return best;
    }

    private static Component timer() {
        long elapsed = System.currentTimeMillis() - cocoonedAt;
        // Until a cocoon has been seen hatching, Hypixel's usual hatch time (about 6.5s) is used.
        long left = (openMs > 0 ? openMs : DEFAULT_OPEN_MS) - elapsed;
        if (left <= 0) return Component.literal("Hatching...").withStyle(ChatFormatting.YELLOW);
        return Component.literal("Hatches in " + seconds(left)).withStyle(ChatFormatting.YELLOW);
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    private static void opened(long tookMs) {
        if (tookMs < 500 || tookMs > GIVE_UP_MS) return;
        openMs = (openMs * seen + tookMs) / (seen + 1);
        seen = Math.min(seen + 1, 20);
        save();
    }

    private static void clear() {
        mob = null;
        cocoon = null;
    }

    private static boolean labelOn() {
        com.epic60869.sky2m.Sky2MConfig config = com.epic60869.sky2m.Sky2MConfig.current();
        FeatureConfigs.Combat c = config == null ? null : config.combat;
        return c != null && c.cocoonAlert.label;
    }

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("cocoon-time.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!java.nio.file.Files.exists(file())) return;
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file())).getAsJsonObject();
            openMs = root.has("openMs") ? root.get("openMs").getAsLong() : 0;
            seen = root.has("seen") ? root.get("seen").getAsInt() : 0;
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read cocoon-time.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            java.nio.file.Files.createDirectories(file().getParent());
            com.google.gson.JsonObject root = new com.google.gson.JsonObject();
            root.addProperty("openMs", openMs);
            root.addProperty("seen", seen);
            java.nio.file.Files.writeString(file(), root.toString());
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save cocoon-time.json: " + e.getMessage());
        }
    }
}
