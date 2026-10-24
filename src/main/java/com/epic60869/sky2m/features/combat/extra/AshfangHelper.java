package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Crimson Isle > Ashfang, ported from SkyHanni's AshfangManager, AshfangHighlights, AshfangFreezeCooldown and
 * AshfangNextResetCooldown (https://github.com/hannibal002/SkyHanni, LGPL-2.1): boxes on Ashfang's blazes, Blazing
 * Souls and Gravity Orbs, the Cryogenic Blast freeze timer and the time to Ashfang's next reset.
 */
public final class AshfangHelper {
    private static final Pattern CRYOGENIC = Pattern.compile("Ashfang Follower's Cryogenic Blast hit you for .* damage!");
    private static final Pattern ASHFANG_TAG = Pattern.compile("Ashfang(?! (?:Follower|Underling|Acolyte))\\b");
    private static final long FREEZE_MS = 3_000L, RESET_MS = 46_100L, SCAN_RANGE = 50;
    /** Texture hashes of the Blazing Soul and Gravity Orb skulls (SkyHanni-REPO Skulls.json). */
    private static final String SOUL_HASH = CombatExtras.textureHash(
        "eyJ0aW1lc3RhbXAiOjE1ODQ1OTYxMjEyMTIsInByb2ZpbGVJZCI6IjIzZjFhNTlmNDY5YjQzZGRiZGI1MzdiZmVjMTA0NzFmIiwicHJvZmlsZU5hbWUiOiIyODA3Iiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS84Mjg3YjM5N2RhZjk1MTZhMGJkNzZmNWYxYjdiZjk3OTUxNWRmM2Q1ZDgzM2UwNjM1ZmE2OGIzN2VlMDgyMjEyIn19fQ==");
    private static final String ORB_HASH = CombatExtras.textureHash(
        "ewogICJ0aW1lc3RhbXAiIDogMTU5NTUyMTkzMDExNSwKICAicHJvZmlsZUlkIiA6ICI1NjY3NWIyMjMyZjA0ZWUwODkxNzllOWM5MjA2Y2ZlOCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUaGVJbmRyYSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS82ZGUzNTY0ZDVmMjA1NmE3ZTE3ZTk2NDE5MjE4YjQyMTM4NDhhMmY0ZDJiNWZlMDM1Yjc0NjBjOTg2ZTZmNDhjIgogICAgfQogIH0KfQ==");

    private record Blaze(LivingEntity mob, float[] rgb) {}

    private static boolean ashfangUp;
    private static long frozenUntil, lastSpawn;
    private static List<Blaze> blazes = List.of();
    private static List<Vec3> souls = List.of(), orbs = List.of();
    private static final Set<Integer> minionTags = new HashSet<>();
    private static int ticks;

    private AshfangHelper() {}

    private static CombatExtraConfig.Ashfang config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.crimsonIsle.ashfang;
    }

    private static boolean inCrimson() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("Crimson Isle");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AshfangHelper::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MChat.onChat(message -> {
            CombatExtraConfig.Ashfang c = config();
            if (c == null || !c.freezeCooldown || !ashfangUp) return;
            if (CRYOGENIC.matcher(message.text().trim()).find()) frozenUntil = System.currentTimeMillis() + FREEZE_MS;
        });
        Sky2MHuds.register("ashfang_freeze", "Ashfang Freeze Cooldown",
            () -> config() != null && config().freezeCooldown && ashfangUp && inCrimson() && System.currentTimeMillis() < frozenUntil,
            () -> List.of(Component.literal("Ashfang Freeze: ").withStyle(ChatFormatting.RED)
                .append(Component.literal(CombatExtras.duration((frozenUntil - System.currentTimeMillis()) / 1000.0)).withStyle(ChatFormatting.GREEN))),
            List.of(Component.literal("Ashfang Freeze: ").withStyle(ChatFormatting.RED).append(Component.literal("2.1s").withStyle(ChatFormatting.GREEN))),
            8, 400);
        Sky2MHuds.setting("ashfang_freeze", () -> config() != null && config().freezeCooldown);
        Sky2MHuds.register("ashfang_reset", "Ashfang Next Reset",
            () -> config() != null && config().nextReset && ashfangUp && inCrimson() && lastSpawn > 0,
            () -> {
                long left = lastSpawn + RESET_MS - System.currentTimeMillis();
                return List.of(Component.literal("Ashfang next reset in: ").withStyle(ChatFormatting.RED)
                    .append(left <= 0 ? Component.literal("Now!").withStyle(ChatFormatting.GREEN)
                        : Component.literal(CombatExtras.duration(left / 1000.0)).withStyle(ChatFormatting.AQUA)));
            },
            List.of(Component.literal("Ashfang next reset in: ").withStyle(ChatFormatting.RED).append(Component.literal("23.4s").withStyle(ChatFormatting.AQUA))),
            8, 414);
        Sky2MHuds.setting("ashfang_reset", () -> config() != null && config().nextReset);
        Sky2MWorldRender.register(AshfangHelper::render);
    }

    private static void reset() {
        ashfangUp = false;
        frozenUntil = 0;
        lastSpawn = 0;
        blazes = List.of();
        souls = List.of();
        orbs = List.of();
        minionTags.clear();
    }

    private static void tick(Minecraft mc) {
        CombatExtraConfig.Ashfang c = config();
        if (c == null || mc.level == null || mc.player == null || !inCrimson()
            || !(c.highlightBlazes || c.blazingSouls || c.gravityOrbs || c.freezeCooldown || c.nextReset)) {
            if (ashfangUp) reset();
            return;
        }
        if (++ticks % 5 != 0) return;
        boolean found = false;
        long now = System.currentTimeMillis();
        List<Blaze> newBlazes = new ArrayList<>();
        Map<Integer, Boolean> currentMinions = new HashMap<>();
        for (Entity e : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(SCAN_RANGE), x -> CombatExtras.nametag(x) != null)) {
            String text = Sky2MLocation.strip(CombatExtras.nametag(e).getString());
            if (!text.contains("Ashfang")) continue;
            if (ASHFANG_TAG.matcher(text).find()) {
                found = true;
                continue;
            }
            float[] rgb = text.contains("Follower") ? new float[]{0.33f, 0.33f, 0.33f}
                : text.contains("Underling") ? new float[]{1f, 0.2f, 0.2f}
                : text.contains("Acolyte") ? new float[]{0.3f, 0.3f, 1f} : null;
            if (rgb == null) continue;
            currentMinions.put(e.getId(), true);
            // SkyHanni's onMobFirstSeen: a new Ashfang blaze at least 10s after the last one starts the 46.1s reset clock.
            if (minionTags.add(e.getId()) && now - lastSpawn > 10_000L) lastSpawn = now;
            if (c.highlightBlazes) {
                LivingEntity mob = CombatExtras.mobBelow(mc, e);
                if (mob != null) newBlazes.add(new Blaze(mob, rgb));
            }
        }
        minionTags.retainAll(currentMinions.keySet());
        if (ashfangUp && !found) lastSpawn = 0;
        ashfangUp = found;
        blazes = newBlazes;

        List<Vec3> newSouls = new ArrayList<>(), newOrbs = new ArrayList<>();
        if (ashfangUp && (c.blazingSouls || c.gravityOrbs)) {
            for (Entity e : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(SCAN_RANGE), x -> x instanceof ArmorStand)) {
                String hash = CombatExtras.headHash((ArmorStand) e);
                if (hash.isEmpty()) continue;
                if (hash.equals(SOUL_HASH)) newSouls.add(e.position());
                else if (hash.equals(ORB_HASH)) newOrbs.add(e.position());
            }
        }
        souls = newSouls;
        orbs = newOrbs;
    }

    private static void render(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector) {
        CombatExtraConfig.Ashfang c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || mc.player == null || !ashfangUp || !inCrimson()) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        if (c.highlightBlazes) {
            for (Blaze blaze : blazes) {
                if (!blaze.mob().isAlive()) continue;
                net.minecraft.world.phys.AABB box = blaze.mob().getBoundingBox().move(blaze.mob().getPosition(partial).subtract(blaze.mob().position()));
                collector.submitFilledBox(box, blaze.rgb(), 0.25f, true);
                collector.submitOutlinedBox(box, blaze.rgb(), 1f, 2f, true);
            }
        }
        if (c.blazingSouls) {
            float[] yellow = {1f, 0.85f, 0.2f};
            for (Vec3 pos : souls) {
                collector.submitFilledBox(pos.add(-0.35, 1.25, -0.35), new Vec3(0.7, 0.7, 0.7), yellow, 0.5f, true);
                labelByDistance(collector, mc, pos.add(0, 2.5, 0), "Blazing Soul");
            }
        }
        if (c.gravityOrbs) {
            for (Vec3 pos : orbs) {
                collector.submitFilledCircle(pos.add(0, -1.5, 0), 3.5f, 32, 0x55AA00FF);
                labelByDistance(collector, mc, pos.add(0, 2.5, 0), "Gravity Orb");
            }
        }
    }

    /** SkyHanni's blend from green (close) to red (15 blocks away). */
    private static void labelByDistance(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector, Minecraft mc, Vec3 pos, String text) {
        double distance = mc.player.position().distanceTo(pos);
        if (distance >= 15) return;
        double t = distance / 15;
        int r = (int) (85 + (255 - 85) * t), g = (int) (255 + (85 - 255) * t), b = 85;
        collector.submitText(Component.literal(text).withStyle(net.minecraft.network.chat.Style.EMPTY.withColor((r << 16) | (g << 8) | b)), pos, 1.4f, false);
    }
}
