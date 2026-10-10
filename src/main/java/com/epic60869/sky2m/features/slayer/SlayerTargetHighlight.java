package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayers > Boss Highlight, ported from Skysoft's SlayerTargetHighlighting (https://github.com/Akinsoft/Skysoft,
 * features/slayer/SlayerTargetHighlighting.kt, LGPL-3.0): a box (or Skysoft's glowing outline) on your slayer boss and
 * on the minibosses you spawned, only while you can see them (not through walls), with an optional line from your crosshair to your boss, or the
 * closest miniboss when there's no boss. Minibosses are yours when Hypixel said "SLAYER MINI-BOSS ... has spawned!".
 */
public final class SlayerTargetHighlight {
    private static final Pattern MINIBOSS_SPAWNED = Pattern.compile("^\\s*SLAYER MINI-BOSS (?<name>.+?) has spawned!\\s*$");
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final double SCAN_RANGE = 48;

    public enum Style {
        BOX("Box"), OUTLINE("Outline");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Names of the minibosses you spawned this quest. */
    private static final Set<String> minibossNames = new HashSet<>();
    private static List<LivingEntity> minibosses = List.of();
    private static final Set<Integer> glowing = new HashSet<>();
    private static int ticks;

    private SlayerTargetHighlight() {}

    private static FeatureConfigs.SlayerTargetHighlight config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.targetHighlight;
    }

    private static boolean active() {
        FeatureConfigs.SlayerTargetHighlight c = config();
        return c != null && c.enabled && Sky2MLocation.onSkyblock();
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            Matcher m = MINIBOSS_SPAWNED.matcher(text);
            if (m.matches()) minibossNames.add(m.group("name").trim().toLowerCase(Locale.ROOT));
            else if (text.startsWith("SLAYER QUEST COMPLETE") || text.startsWith("SLAYER QUEST FAILED")
                || text.startsWith("SLAYER QUEST STARTED") || text.startsWith("Your Slayer Quest has been cancelled")) {
                minibossNames.clear();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(SlayerTargetHighlight::tick);
        EntityGlow.register(entity -> {
            FeatureConfigs.SlayerTargetHighlight c = config();
            if (c == null || c.style != Style.OUTLINE || !glowing.contains(entity.getId())) return -1;
            return colour(c.colour) & 0xFFFFFF;
        });
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.SlayerTargetHighlight c = config();
            if (!active()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            List<LivingEntity> targets = targets(c);
            if (c.style == Style.BOX) {
                float[] rgb = rgb(colour(c.colour));
                for (LivingEntity target : targets) {
                    AABB box = target.getBoundingBox().move(target.getPosition(partial).subtract(target.position()));
                    // Depth-tested and only for targets you can see, like the dungeon mob highlight.
                    collector.submitFilledBox(box, rgb, 0.2f, false);
                    collector.submitOutlinedBox(box, rgb, 1f, 2f, false);
                }
            }
            if (!c.targetLine) return;
            // Your boss, else the closest miniboss (Skysoft's selectSlayerLineTarget).
            LivingEntity boss = c.highlightBosses ? SlayerFeatures.boss() : null;
            LivingEntity lineTo = boss != null && targets.contains(boss) ? boss : null;
            if (lineTo == null && c.highlightMinibosses) {
                double best = Double.MAX_VALUE;
                for (LivingEntity mini : minibosses) {
                    double d = mini.distanceToSqr(mc.player);
                    if (targets.contains(mini) && d < best) {
                        best = d;
                        lineTo = mini;
                    }
                }
            }
            if (lineTo != null) {
                collector.submitLineFromCursor(lineTo.getPosition(partial).add(0, lineTo.getBbHeight() / 2.0, 0),
                    rgb(colour(c.lineColour)), 1f, 2f);
            }
        });
    }

    /** Your boss and minibosses that you can see (nothing is highlighted through walls). */
    private static List<LivingEntity> targets(FeatureConfigs.SlayerTargetHighlight c) {
        List<LivingEntity> targets = new ArrayList<>();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return targets;
        LivingEntity boss = SlayerFeatures.boss();
        if (c.highlightBosses && boss != null && player.hasLineOfSight(boss)) targets.add(boss);
        if (c.highlightMinibosses) {
            for (LivingEntity mini : minibosses) if (mini.isAlive() && player.hasLineOfSight(mini)) targets.add(mini);
        }
        return targets;
    }

    private static void tick(Minecraft mc) {
        if (!active() || mc.level == null || mc.player == null) {
            if (!glowing.isEmpty() || !minibosses.isEmpty()) {
                glowing.clear();
                minibosses = List.of();
            }
            return;
        }
        if (++ticks % SCAN_INTERVAL_TICKS != 0) return;
        minibosses = minibossNames.isEmpty() ? List.of() : findMinibosses(mc);
        glowing.clear();
        for (LivingEntity target : targets(config())) glowing.add(target.getId());
    }

    /** Mobs under a nametag with one of your minibosses' names. */
    private static List<LivingEntity> findMinibosses(Minecraft mc) {
        List<LivingEntity> found = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(SCAN_RANGE);
        for (ArmorStand tag : mc.level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName)) {
            String name = ChatFormatting.stripFormatting(tag.getCustomName().getString());
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            boolean matches = false;
            for (String mini : minibossNames) {
                if (lower.contains(mini)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;
            LivingEntity mob = mobNear(mc, tag);
            if (mob != null && !found.contains(mob)) found.add(mob);
        }
        return found;
    }

    /** The mob nearest a nametag: players, armor stands and text displays aren't it. */
    private static LivingEntity mobNear(Minecraft mc, Entity tag) {
        AABB area = tag.getBoundingBox().inflate(1.5, 3, 1.5);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mc.level.getEntitiesOfClass(LivingEntity.class, area,
                e -> !(e instanceof ArmorStand) && !(e instanceof Player) && e.isAlive())) {
            double distance = mob.distanceToSqr(tag);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    private static void clear() {
        minibossNames.clear();
        minibosses = List.of();
        glowing.clear();
    }

    private static int colour(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xFFFF5555;
        }
    }

    private static float[] rgb(int colour) {
        return new float[]{(colour >> 16 & 255) / 255f, (colour >> 8 & 255) / 255f, (colour & 255) / 255f};
    }
}
