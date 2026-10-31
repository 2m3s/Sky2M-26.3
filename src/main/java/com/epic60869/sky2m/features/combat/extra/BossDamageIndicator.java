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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combat > Boss Damage Indicator, modelled on SkyHanni's damage indicator
 * (https://github.com/hannibal002/SkyHanni, LGPL-2.1): reads the health in the "☠ Boss 12.3M❤" nametag of the boss you
 * are fighting and works out its health percentage, the damage per second dealt to it, the time it has left, its
 * "15 Hits" counter (Voidgloom Seraph) and the health it heals back. Display only.
 */
public final class BossDamageIndicator {
    private static final Pattern TAG = Pattern.compile("☠\\s*(?<name>.+?)\\s+(?<hp>[\\d.,]+[kKmMbB]?)(?:/(?<max>[\\d.,]+[kKmMbB]?))?\\s*❤");
    private static final Pattern HITS = Pattern.compile("\\b(\\d+) Hits?\\b");
    private static final double RANGE = 40;

    private record Sample(long at, double health) {}

    private static int bossId = -1;
    private static String name = "";
    private static double health, maxHealth, previousHealth = -1;
    private static String hits = "";
    private static final Deque<Sample> samples = new ArrayDeque<>();
    private static long healShownAt;
    private static String healText = "";
    private static int ticks;
    private static Vec3 labelPos;

    private BossDamageIndicator() {}

    private static CombatExtraConfig.BossIndicator config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.bossIndicator;
    }

    private static boolean active() {
        CombatExtraConfig.BossIndicator c = config();
        return c != null && c.enabled && Sky2MLocation.onSkyblock();
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(BossDamageIndicator::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> clear());
        Sky2MHuds.register("boss_damage_indicator", "Boss Damage Indicator", BossDamageIndicator::active, BossDamageIndicator::lines,
            List.of(Component.literal("Voidgloom Seraph ").withStyle(ChatFormatting.RED)
                    .append(Component.literal("21.4M / 40M ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("(53%)").withStyle(ChatFormatting.GREEN)),
                Component.literal("DPS: ").withStyle(ChatFormatting.GOLD).append(Component.literal("4.82M").withStyle(ChatFormatting.WHITE)),
                Component.literal("Time left: ").withStyle(ChatFormatting.GRAY).append(Component.literal("4.4s").withStyle(ChatFormatting.WHITE)),
                Component.literal("Hits: ").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal("12").withStyle(ChatFormatting.WHITE))),
            8, 340);
        Sky2MHuds.setting("boss_damage_indicator", () -> config() != null && config().enabled);
        Sky2MWorldRender.register(collector -> {
            CombatExtraConfig.BossIndicator c = config();
            if (!active() || !c.worldLabel || bossId < 0 || labelPos == null || maxHealth <= 0) return;
            double pct = health * 100 / maxHealth;
            collector.submitText(Component.literal(String.format(Locale.US, "%.1f%%", pct)).withStyle(colour(pct)), labelPos.add(0, 0.8, 0), 1.6f, true);
        });
    }

    private static void clear() {
        bossId = -1;
        name = "";
        health = maxHealth = 0;
        previousHealth = -1;
        hits = "";
        samples.clear();
        labelPos = null;
    }

    private static ChatFormatting colour(double pct) {
        return pct > 50 ? ChatFormatting.GREEN : pct > 20 ? ChatFormatting.YELLOW : ChatFormatting.RED;
    }

    private static void tick(Minecraft mc) {
        if (!active() || mc.level == null || mc.player == null) {
            if (bossId >= 0) clear();
            return;
        }
        if (++ticks % 2 != 0) return;
        Entity tracked = bossId >= 0 ? mc.level.getEntity(bossId) : null;
        Matcher m = null;
        if (tracked != null && tracked.isAlive() && CombatExtras.nametag(tracked) != null) {
            m = TAG.matcher(Sky2MLocation.strip(CombatExtras.nametag(tracked).getString()));
            if (!m.find()) m = null;
        }
        if (m == null) {
            if (bossId >= 0) clear();
            if (ticks % 10 != 0) return;
            Entity best = null;
            double bestDistance = RANGE * RANGE;
            for (Entity e : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(RANGE), x -> CombatExtras.nametag(x) != null)) {
                double d = e.distanceToSqr(mc.player);
                if (d > bestDistance) continue;
                Matcher candidate = TAG.matcher(Sky2MLocation.strip(CombatExtras.nametag(e).getString()));
                if (!candidate.find()) continue;
                best = e;
                bestDistance = d;
            }
            if (best == null) return;
            bossId = best.getId();
            tracked = best;
            m = TAG.matcher(Sky2MLocation.strip(CombatExtras.nametag(best).getString()));
            m.find();
        }
        double hp = CombatExtras.parseNumber(m.group("hp"));
        double max = m.group("max") == null ? -1 : CombatExtras.parseNumber(m.group("max"));
        if (hp < 0) return;
        name = m.group("name").trim();
        health = hp;
        maxHealth = Math.max(Math.max(maxHealth, max), hp);
        labelPos = tracked.position();
        update(mc, tracked);
    }

    private static void update(Minecraft mc, Entity tag) {
        CombatExtraConfig.BossIndicator c = config();
        long now = System.currentTimeMillis();
        samples.addLast(new Sample(now, health));
        while (samples.size() > 1 && now - samples.peekFirst().at() > c.dpsWindow * 1000L) samples.pollFirst();

        if (previousHealth >= 0 && c.healMessages && health - previousHealth >= maxHealth * c.healThreshold / 100.0) {
            double healed = health - previousHealth;
            healText = "Healed +" + CombatExtras.shortNumber(healed) + "❤";
            healShownAt = now;
            Sky2MAlerts.chat(Component.literal(name + " healed ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(CombatExtras.shortNumber(healed) + "❤").withStyle(ChatFormatting.RED)));
        }
        previousHealth = health;

        hits = "";
        if (c.showHits) {
            for (Entity e : mc.level.getEntities((Entity) null, tag.getBoundingBox().inflate(2, 3, 2), x -> CombatExtras.nametag(x) != null)) {
                Matcher h = HITS.matcher(Sky2MLocation.strip(CombatExtras.nametag(e).getString()));
                if (h.find()) {
                    hits = h.group(1);
                    break;
                }
            }
        }
    }

    private static double dps() {
        if (samples.size() < 2) return 0;
        Sample first = samples.peekFirst(), last = samples.peekLast();
        double seconds = (last.at() - first.at()) / 1000.0;
        if (seconds < 1) return 0;
        return Math.max(0, (first.health() - last.health()) / seconds);
    }

    private static List<Component> lines() {
        CombatExtraConfig.BossIndicator c = config();
        if (c == null || bossId < 0 || maxHealth <= 0) return List.of();
        List<Component> out = new ArrayList<>();
        double pct = health * 100 / maxHealth;
        out.add(Component.literal(name + " ").withStyle(ChatFormatting.RED)
            .append(Component.literal(CombatExtras.shortNumber(health) + " / " + CombatExtras.shortNumber(maxHealth) + " ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(Locale.US, "(%.1f%%)", pct)).withStyle(colour(pct))));
        double dps = dps();
        if (c.showDps) {
            out.add(Component.literal("DPS: ").withStyle(ChatFormatting.GOLD).append(Component.literal(CombatExtras.shortNumber(dps)).withStyle(ChatFormatting.WHITE)));
        }
        if (c.showTimeToKill && dps > 0) {
            out.add(Component.literal("Time left: ").withStyle(ChatFormatting.GRAY).append(Component.literal(CombatExtras.duration(health / dps)).withStyle(ChatFormatting.WHITE)));
        }
        if (!hits.isEmpty()) {
            out.add(Component.literal("Hits: ").withStyle(ChatFormatting.DARK_PURPLE).append(Component.literal(hits).withStyle(ChatFormatting.WHITE)));
        }
        if (c.healMessages && System.currentTimeMillis() - healShownAt < 4_000L) {
            out.add(Component.literal(healText).withStyle(ChatFormatting.GREEN));
        }
        return out;
    }
}
