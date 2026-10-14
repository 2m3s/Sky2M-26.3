package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Combat > Damage Numbers. Hypixel's floating damage numbers are nameplates ("1,234,567", or "✧1,234,567✧" in
 * rainbow for crits). Short Numbers rewrites each one the first tick it's seen as "1.23M", giving every character
 * the colour the original had at that place, so crits stay rainbow. The HUD adds up the numbers that appear near you.
 */
public final class DamageNumbers {
    /** Only symbols around the digits: mob names and health bars always have letters. */
    private static final Pattern DAMAGE = Pattern.compile("^([^\\w\\s]{0,2})([\\d,]{1,15})([^\\w\\s]{0,3})$");

    private record Hit(long at, double amount) {}

    private static final Set<Integer> seen = new HashSet<>();
    private static final Deque<Hit> hits = new ArrayDeque<>();
    private static int ticks;

    private DamageNumbers() {}

    private static FeatureConfigs.DamageNumbers config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.damageNumbers;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            seen.clear();
            hits.clear();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        Sky2MHuds.register("damage_numbers", "Damage HUD", () -> config() != null && config().hud && Sky2MLocation.onSkyblock(),
            DamageNumbers::lines,
            List.of(Component.literal("Damage: ").withStyle(ChatFormatting.RED).append(Component.literal("48.2M (10s)").withStyle(ChatFormatting.WHITE)),
                Component.literal("DPS: ").withStyle(ChatFormatting.GOLD).append(Component.literal("4.82M").withStyle(ChatFormatting.WHITE))),
            10, 380);
    }

    private static void tick(Minecraft mc) {
        FeatureConfigs.DamageNumbers c = config();
        if (c == null || mc.level == null || mc.player == null || (!c.compact && !c.hide && !c.hud) || !Sky2MLocation.onSkyblock()) return;
        if (++ticks % 200 == 0 && seen.size() > 4000) seen.clear();
        long now = System.currentTimeMillis();
        while (!hits.isEmpty() && now - hits.peekFirst().at() > c.windowSeconds * 1000L) hits.pollFirst();
        double rangeSq = (double) c.range * c.range;
        for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(32))) {
            if (seen.contains(e.getId())) continue;
            Component name = e instanceof ArmorStand stand && stand.hasCustomName() ? stand.getCustomName()
                : e instanceof Display.TextDisplay display ? display.getEntityData().get(Display.TextDisplay.DATA_TEXT_ID) : null;
            if (name == null) continue;
            String plain = Sky2MLocation.strip(name.getString()).trim();
            Matcher m = DAMAGE.matcher(plain);
            if (!m.matches()) continue;
            seen.add(e.getId());
            double amount;
            try {
                amount = Double.parseDouble(m.group(2).replace(",", ""));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (c.hud && e.distanceToSqr(mc.player) <= rangeSq) hits.addLast(new Hit(now, amount));
            if (c.hide) set(e, Component.empty());
            else if (c.compact) set(e, recolour(name, m.group(1) + CombatFeatures.formatCoins(amount) + m.group(3)));
        }
    }

    private static void set(Entity e, Component text) {
        if (e instanceof ArmorStand stand) {
            if (text.getString().isEmpty()) stand.setCustomNameVisible(false);
            else stand.setCustomName(text);
        } else if (e instanceof Display.TextDisplay display) {
            display.getEntityData().set(Display.TextDisplay.DATA_TEXT_ID, text);
        }
    }

    /** {@code text} with each character in the style the original's character at the same place had. */
    private static Component recolour(Component original, String text) {
        List<Style> styles = new ArrayList<>();
        original.visit((style, part) -> {
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) != '§') styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        MutableComponent out = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            Style style = styles.isEmpty() ? Style.EMPTY : styles.get(Math.min(i, styles.size() - 1));
            out.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style));
        }
        return out;
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        FeatureConfigs.DamageNumbers c = config();
        if (c == null || hits.isEmpty()) return out;
        double total = 0;
        for (Hit h : hits) total += h.amount();
        out.add(Component.literal("Damage: ").withStyle(ChatFormatting.RED)
            .append(Component.literal(CombatFeatures.formatCoins(total) + " (" + c.windowSeconds + "s)").withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("DPS: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(CombatFeatures.formatCoins(total / Math.max(1, c.windowSeconds))).withStyle(ChatFormatting.WHITE)));
        return out;
    }
}
