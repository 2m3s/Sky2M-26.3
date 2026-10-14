package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Archer & Tank Counters: arrows you shot this run (each arrow entity Hypixel spawns with you as its owner,
 * so a Terminator shot counts 3) and the health you lost (every drop of the action bar's health, so healing doesn't
 * take any off). Both start over when the run starts.
 */
public final class ClassCounters {
    private static final Pattern HEALTH = Pattern.compile("([\\d,]+)/([\\d,]+)❤");

    private static int arrows;
    private static long damage;
    private static int lastHealth = -1;
    private static boolean summarised;

    private ClassCounters() {}

    private static FeatureConfigs.ClassCounters config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.classCounters;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof AbstractArrow arrow) onArrow(arrow);
        });
        Sky2MChat.onActionBar(message -> onActionBar(message.text()));
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("class_arrows", "Arrows Shot", () -> showArrows(config()), () -> {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal("Arrows: ").withStyle(ChatFormatting.GREEN).append(Component.literal(String.format(Locale.US, "%,d", arrows)).withStyle(ChatFormatting.WHITE)));
            return out;
        }, List.of(Component.literal("Arrows: ").withStyle(ChatFormatting.GREEN).append(Component.literal("1,284").withStyle(ChatFormatting.WHITE))), 10, 250);
        Sky2MHuds.register("class_damage_taken", "Damage Taken", () -> showDamage(config()), () -> {
            List<Component> out = new ArrayList<>();
            out.add(Component.literal("Damage Taken: ").withStyle(ChatFormatting.RED).append(Component.literal(CombatFeatures.formatCoins(damage)).withStyle(ChatFormatting.WHITE)));
            return out;
        }, List.of(Component.literal("Damage Taken: ").withStyle(ChatFormatting.RED).append(Component.literal("1.25M").withStyle(ChatFormatting.WHITE))), 10, 262);
        Sky2MHuds.setting("class_arrows", () -> config() != null && config().arrows);
        Sky2MHuds.setting("class_damage_taken", () -> config() != null && config().damageTaken);
    }

    private static void reset() {
        arrows = 0;
        damage = 0;
        lastHealth = -1;
        summarised = false;
    }

    private static boolean showArrows(FeatureConfigs.ClassCounters c) {
        return c != null && c.arrows && Sky2MLocation.inDungeon() && (!c.arrowsArcherOnly || SelfClass.get() == DungeonClass.ARCHER);
    }

    private static boolean showDamage(FeatureConfigs.ClassCounters c) {
        return c != null && c.damageTaken && Sky2MLocation.inDungeon() && (!c.damageTankOnly || SelfClass.get() == DungeonClass.TANK);
    }

    private static void onArrow(AbstractArrow arrow) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !showArrows(config())) return;
        if (arrow.getOwner() == mc.player) arrows++;
    }

    private static void onActionBar(String text) {
        if (!showDamage(config())) {
            lastHealth = -1;
            return;
        }
        Matcher m = HEALTH.matcher(text);
        if (!m.find()) return;
        int health;
        try {
            health = Integer.parseInt(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return;
        }
        if (lastHealth >= 0 && health < lastHealth) damage += lastHealth - health;
        lastHealth = health;
    }

    private static void onChat(String text) {
        if (!Sky2MLocation.inDungeon()) return;
        if (text.equals("Starting in 1 second.")) {
            reset();
            return;
        }
        if (summarised || !text.startsWith("Team Score:")) return;
        summarised = true;
        FeatureConfigs.ClassCounters c = config();
        if (c == null || !c.summary) return;
        List<String> parts = new ArrayList<>();
        if (showArrows(c)) parts.add(String.format(Locale.US, "%,d arrows shot", arrows));
        if (showDamage(c)) parts.add(CombatFeatures.formatCoins(damage) + " damage taken");
        if (!parts.isEmpty()) Sky2MAlerts.chat(Component.literal("This run: " + String.join(", ", parts) + ".").withStyle(ChatFormatting.GRAY));
    }
}
