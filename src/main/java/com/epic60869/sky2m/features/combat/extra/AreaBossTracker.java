package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Combat > Area Bosses: a box, spawn title and "time since it died" timer for the area bosses Boss Corleone (Crystal
 * Hollows), Arachne's Keepers (Spider's Den) and Zealot Bruisers (the End), found by their nametags the way SkyHanni's
 * area mini boss features do (https://github.com/hannibal002/SkyHanni, LGPL-2.1). A boss counts as dead when its
 * nametag vanishes while you are close to it. Display only.
 */
public final class AreaBossTracker {
    private enum Boss {
        CORLEONE("Boss Corleone", "Corleone", ChatFormatting.RED, new float[]{1f, 0.2f, 0.2f}),
        KEEPER("Arachne's Keeper", "Arachne's Keeper", ChatFormatting.LIGHT_PURPLE, new float[]{0.85f, 0.3f, 1f}),
        BRUISER("Zealot Bruiser", "Zealot Bruiser", ChatFormatting.DARK_PURPLE, new float[]{0.6f, 0.2f, 0.9f});

        final String tag, label;
        final ChatFormatting colour;
        final float[] rgb;

        Boss(String tag, String label, ChatFormatting colour, float[] rgb) {
            this.tag = tag;
            this.label = label;
            this.colour = colour;
            this.rgb = rgb;
        }
    }

    private record Seen(Boss boss, Vec3 pos) {}

    private static final double RANGE = 64, CLOSE = 24;
    private static final Map<Integer, Seen> seen = new HashMap<>();
    private static final Map<Boss, Long> deathAt = new EnumMap<>(Boss.class);
    private static final Map<Boss, Long> alertedAt = new EnumMap<>(Boss.class);
    private static List<Seen> targets = List.of();
    private static final Map<Integer, LivingEntity> mobs = new HashMap<>();
    private static int ticks;

    private AreaBossTracker() {}

    private static CombatExtraConfig.AreaBosses config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.areaBosses;
    }

    private static int respawn(Boss boss, CombatExtraConfig.AreaBosses c) {
        return switch (boss) {
            case CORLEONE -> c.corleoneRespawn;
            case KEEPER -> c.keeperRespawn;
            case BRUISER -> c.bruiserRespawn;
        };
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(AreaBossTracker::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            seen.clear();
            mobs.clear();
            deathAt.clear();
            alertedAt.clear();
            targets = List.of();
        });
        Sky2MHuds.register("area_boss_timers", "Area Boss Timers",
            () -> config() != null && config().timer && Sky2MLocation.onSkyblock(), AreaBossTracker::lines,
            List.of(Component.literal("Boss Corleone: ").withStyle(ChatFormatting.RED).append(Component.literal("respawns in 3m 10s").withStyle(ChatFormatting.YELLOW)),
                Component.literal("Zealot Bruiser: ").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal("died 45.0s ago").withStyle(ChatFormatting.GRAY))),
            8, 440);
        Sky2MHuds.setting("area_boss_timers", () -> config() != null && config().timer);
        Sky2MWorldRender.register(collector -> {
            CombatExtraConfig.AreaBosses c = config();
            Minecraft mc = Minecraft.getInstance();
            if (c == null || !c.highlight || mc.player == null || !Sky2MLocation.onSkyblock()) return;
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            for (Seen s : targets) {
                collector.submitText(Component.literal(s.boss().label).withStyle(s.boss().colour, ChatFormatting.BOLD), s.pos().add(0, 3, 0), 1.5f, true);
            }
            for (LivingEntity mob : mobs.values()) {
                if (!mob.isAlive()) continue;
                Boss boss = null;
                for (Seen s : targets) if (s.pos().distanceToSqr(mob.position()) < 16) boss = s.boss();
                float[] rgb = boss == null ? new float[]{1f, 0.2f, 0.2f} : boss.rgb;
                AABB box = mob.getBoundingBox().move(mob.getPosition(partial).subtract(mob.position()));
                collector.submitFilledBox(box, rgb, 0.25f, true);
                collector.submitOutlinedBox(box, rgb, 1f, 2f, true);
            }
        });
    }

    private static void tick(Minecraft mc) {
        CombatExtraConfig.AreaBosses c = config();
        if (c == null || mc.level == null || mc.player == null || !Sky2MLocation.onSkyblock() || !(c.highlight || c.spawnAlert || c.timer)) {
            if (!seen.isEmpty()) {
                seen.clear();
                mobs.clear();
                targets = List.of();
            }
            return;
        }
        if (++ticks % 4 != 0) return;
        long now = System.currentTimeMillis();
        Map<Integer, Seen> current = new HashMap<>();
        List<Seen> found = new ArrayList<>();
        for (Entity e : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(RANGE), x -> CombatExtras.nametag(x) != null)) {
            String text = Sky2MLocation.strip(CombatExtras.nametag(e).getString());
            for (Boss boss : Boss.values()) {
                if (!text.contains(boss.tag)) continue;
                Seen s = new Seen(boss, e.position());
                current.put(e.getId(), s);
                found.add(s);
                if (!seen.containsKey(e.getId()) && c.spawnAlert && now - alertedAt.getOrDefault(boss, 0L) > 10_000L) {
                    alertedAt.put(boss, now);
                    Sky2MAlerts.title(Component.literal(boss.label).withStyle(boss.colour, ChatFormatting.BOLD),
                        Component.literal("spawned nearby").withStyle(ChatFormatting.GRAY));
                }
                if (c.highlight && !mobs.containsKey(e.getId())) {
                    LivingEntity mob = CombatExtras.mobBelow(mc, e);
                    if (mob != null) mobs.put(e.getId(), mob);
                }
                break;
            }
        }
        // A nametag that vanished close to you was the boss dying; far away it was just unloaded.
        for (Map.Entry<Integer, Seen> e : seen.entrySet()) {
            if (current.containsKey(e.getKey())) continue;
            if (e.getValue().pos().distanceToSqr(mc.player.position()) <= CLOSE * CLOSE) deathAt.put(e.getValue().boss(), now);
        }
        mobs.keySet().retainAll(current.keySet());
        seen.clear();
        seen.putAll(current);
        targets = found;
    }

    private static List<Component> lines() {
        CombatExtraConfig.AreaBosses c = config();
        List<Component> out = new ArrayList<>();
        if (c == null) return out;
        long now = System.currentTimeMillis();
        for (Boss boss : Boss.values()) {
            Long died = deathAt.get(boss);
            if (died == null) continue;
            double ago = (now - died) / 1000.0;
            int respawn = respawn(boss, c);
            Component value;
            if (respawn > 0 && ago < respawn) value = Component.literal("respawns in " + CombatExtras.duration(respawn - ago)).withStyle(ChatFormatting.YELLOW);
            else if (respawn > 0) value = Component.literal("ready (died " + CombatExtras.duration(ago) + " ago)").withStyle(ChatFormatting.GREEN);
            else value = Component.literal("died " + CombatExtras.duration(ago) + " ago").withStyle(ChatFormatting.GRAY);
            out.add(Component.literal(boss.label + ": ").withStyle(boss.colour).append(value));
        }
        return out;
    }
}
