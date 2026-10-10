package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mythological mob health, ported from SBO's DianaMobDetect (https://github.com/SkyblockOverhaul/SBO, Apache-2.0): the
 * nametags of the Diana mobs near you (the ones with the dark green mythological mark) on a HUD, King Minos's hits
 * left, and a "HP LOW!" title when a rare mob's health drops below a limit. A mob whose health reaches 0 has died,
 * which the tracker uses to count lootshared rare mobs.
 */
public final class DianaMobHealth {
    /** The mythological mob mark in Hypixel's resource pack, and the one without it. */
    private static final char MYTHO_MARK = '';
    private static final char MYTHO_MARK_PLAIN = '✿';
    private static final int DARK_GREEN = 0x00AA00;
    private static final long NAME_TIMEOUT_MS = 1_000L;

    private static final Pattern HEALTH = Pattern.compile("(?<current>\\d+(?:\\.\\d+)?[kKmMbB]?)/\\d");
    private static final Pattern KING_HITS = Pattern.compile("(\\d+)\\s+Hits");

    /** Armor stands that just appeared, waiting for their name: entity id -> first seen. */
    private static final Map<Integer, Long> unconfirmed = new HashMap<>();
    private static final Set<Integer> tracked = new LinkedHashSet<>();
    private static final Set<Integer> defeated = new HashSet<>();
    private static final Set<Integer> warned = new HashSet<>();
    private static List<Component> lines = List.of();

    private DianaMobHealth() {}

    private static FeatureConfigs.DianaMobHealth config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mayors.diana.mobHealth;
    }

    public static void init() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ArmorStand) unconfirmed.put(entity.getId(), System.currentTimeMillis());
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (!(entity instanceof ArmorStand)) return;
            unconfirmed.remove(entity.getId());
            tracked.remove(entity.getId());
            defeated.remove(entity.getId());
        });
        ClientTickEvents.END_CLIENT_TICK.register(DianaMobHealth::tick);
        // SBO's hidden achievements for hitting King Minos with shears and a Manticore with a "core" item.
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && entity instanceof Player && DianaTracker.inHub()) {
                String name = entity.getName().getString();
                String held = player.getMainHandItem().getHoverName().getString();
                if (name.contains("King Minos") && held.contains("Shears")) DianaAchievements.unlock(92);
                if (name.contains("Manticore") && held.toLowerCase(Locale.ROOT).contains("core")) DianaAchievements.unlock(93);
            }
            return InteractionResult.PASS;
        });
        Sky2MHuds.register("diana_mob_hp", "Mythos Mob HP",
            () -> {
                FeatureConfigs.DianaMobHealth c = config();
                return c != null && c.enabled && DianaTracker.inHub();
            },
            () -> lines,
            List.of(Component.literal("[Lv750] ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal("✿ ").withStyle(ChatFormatting.DARK_GREEN))
                    .append(Component.literal("Exalted Minos Inquisitor ").withStyle(ChatFormatting.RED))
                    .append(Component.literal("21.4M").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("/").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal("40M").withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("❤").withStyle(ChatFormatting.RED)),
                Component.literal("King Minos").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal("12 Hits").withStyle(ChatFormatting.DARK_PURPLE))),
            8, 300);
        Sky2MHuds.setting("diana_mob_hp", () -> {
            FeatureConfigs.DianaMobHealth c = config();
            return c != null && c.enabled;
        });
    }

    // ------------------------------------------------------------------------------------------------ nametags

    /** The dark green mythological mark, whether the name uses styles or old § codes. */
    private static boolean hasMythoMark(Component name) {
        String raw = name.getString();
        if (raw.contains("§2" + MYTHO_MARK) || raw.contains("§2" + MYTHO_MARK_PLAIN)) return true;
        return name.visit((Style style, String text) -> {
            TextColor colour = style.getColor();
            if (colour != null && colour.getValue() == DARK_GREEN && (text.indexOf(MYTHO_MARK) >= 0 || text.indexOf(MYTHO_MARK_PLAIN) >= 0)) {
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, Style.EMPTY).isPresent();
    }

    private static Integer kingHits(String name) {
        if (!name.contains("Hits")) return null;
        Matcher m = KING_HITS.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static Double health(String name) {
        Matcher m = HEALTH.matcher(name);
        if (!m.find()) return null;
        String raw = m.group("current");
        double mult = switch (Character.toUpperCase(raw.charAt(raw.length() - 1))) {
            case 'K' -> 1_000d;
            case 'M' -> 1_000_000d;
            case 'B' -> 1_000_000_000d;
            default -> 1d;
        };
        String number = mult == 1d ? raw : raw.substring(0, raw.length() - 1);
        try {
            return Double.parseDouble(number) * mult;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !DianaTracker.inHub()) {
            unconfirmed.clear();
            tracked.clear();
            defeated.clear();
            warned.clear();
            lines = List.of();
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> it = unconfirmed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> e = it.next();
            Entity entity = mc.level.getEntity(e.getKey());
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            Component name = entity.getCustomName();
            if (name != null && (hasMythoMark(name) || kingHits(name.getString()) != null)) {
                tracked.add(e.getKey());
                it.remove();
            } else if (now - e.getValue() > NAME_TIMEOUT_MS) {
                it.remove();
            }
        }

        List<Component> out = new ArrayList<>();
        Iterator<Integer> ids = tracked.iterator();
        while (ids.hasNext()) {
            int id = ids.next();
            Entity entity = mc.level.getEntity(id);
            Component name = entity == null ? null : entity.getCustomName();
            if (entity == null || !entity.isAlive() || name == null) {
                ids.remove();
                defeated.remove(id);
                warned.remove(id);
                continue;
            }
            String text = Sky2MLocation.strip(name.getString());
            Integer hits = kingHits(text);
            if (hits != null) {
                out.add(Component.literal("King Minos").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" - ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(hits + " Hits").withStyle(ChatFormatting.DARK_PURPLE)));
                continue;
            }
            if (!hasMythoMark(name)) continue;
            Double health = health(text);
            DianaTracker.Mob rare = DianaTracker.Mob.rareIn(text);
            lowHealthAlert(id, rare, health);
            if (health != null && health <= 0 && defeated.add(id)) {
                warned.remove(id);
                onDeath(rare, mc.player.distanceTo(entity));
            }
            out.add(name);
        }
        lines = out;
    }

    private static void onDeath(DianaTracker.Mob rare, float distance) {
        if (distance > 30) return;
        DianaTracker.onDianaMobDeath();
        if (rare != null) DianaTracker.onRareMobDeath(rare);
    }

    private static void lowHealthAlert(int id, DianaTracker.Mob rare, Double health) {
        FeatureConfigs.DianaMobHealth c = config();
        if (c == null || rare == null || health == null || health <= 0 || defeated.contains(id) || warned.contains(id)) return;
        if (c.lowHpAlert > 0 && health <= c.lowHpAlert * 1_000_000d) {
            warned.add(id);
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                mc.gui.hud.setTimes(10, 40, 10);
                mc.gui.hud.setTitle(Component.literal("HP LOW!").withStyle(ChatFormatting.RED));
                mc.gui.hud.setSubtitle(Component.literal(rare.label).withStyle(ChatFormatting.GRAY));
            });
            if (c.lowHpSound) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BELL.value(), 1.4f);
        }
    }
}
