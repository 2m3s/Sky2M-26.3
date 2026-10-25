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

import java.util.HashSet;
import java.util.Locale;
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

    private static String mob;
    private static long cocoonedAt;
    private static Set<Integer> before = Set.of();
    private static ArmorStand cocoon;
    /** Average time cocoons took to open, in ms (0 until one has been seen), and how many that is. */
    private static long openMs;
    private static int seen;
    private static boolean loaded;

    private CocoonTracker() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> clear());
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
        cocoon = null;
        // The cocoon is a stand that isn't here yet: remember the ones that are.
        Set<Integer> ids = new HashSet<>();
        for (Entity e : mc.level.entitiesForRendering()) if (e instanceof ArmorStand) ids.add(e.getId());
        before = ids;
    }

    private static void tick(Minecraft mc) {
        if (mob == null) return;
        long now = System.currentTimeMillis();
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

    /** The nearest new armor stand wearing a head (the cocoon model), not a name tag. */
    private static ArmorStand find(Minecraft mc) {
        ArmorStand best = null;
        double bestDistance = FIND_RANGE * FIND_RANGE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof ArmorStand stand) || before.contains(stand.getId()) || stand.isMarker()) continue;
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
        if (openMs > 0) {
            long left = Math.max(0, openMs - elapsed);
            return Component.literal("Hatches in ~" + seconds(left)).withStyle(ChatFormatting.YELLOW);
        }
        // No cocoon seen hatching yet: count up until this one does, and the next ones count down.
        return Component.literal("Cocooned " + seconds(elapsed) + " ago").withStyle(ChatFormatting.YELLOW);
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
        before = Set.of();
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
