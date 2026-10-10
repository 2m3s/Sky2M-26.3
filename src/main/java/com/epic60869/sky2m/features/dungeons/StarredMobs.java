package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Boxes starred dungeon mobs, bats and the F7/M7 boss withers. Hypixel marks starred mobs with a "✯" in the nametag armor stand above the
 * mob, so the mob is the closest living entity just below a starred stand. The mobs are found every few ticks; the
 * boxes are drawn every frame at the mob's interpolated position, depth-tested so nothing shows through walls.
 */
public final class StarredMobs {
    private static final String STAR = "✯";
    private static List<LivingEntity> starred = List.of();
    private static List<LivingEntity> bats = List.of();
    private static List<LivingEntity> withers = List.of();

    private StarredMobs() {}

    private static FeatureConfigs.DungeonMobs config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.dungeons.mobs;
    }

    private static int ticks;

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Every 4 ticks: it reads every nametag.
            if (++ticks % 4 != 0) return;
            FeatureConfigs.DungeonMobs config = config();
            if (config == null || (!config.starredMobs && !config.bats && !config.withers) || !Sky2MLocation.inDungeon() || mc.level == null || mc.player == null) {
                starred = List.of();
                bats = List.of();
                withers = List.of();
                return;
            }
            boolean findWithers = config.withers && PositionalMessages.onFloor7();
            List<LivingEntity> foundStarred = new ArrayList<>();
            List<LivingEntity> foundBats = new ArrayList<>();
            List<LivingEntity> foundWithers = new ArrayList<>();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (config.bats && entity instanceof Bat bat && bat.isAlive()) foundBats.add(bat);
                // Hypixel parks invisible withers around the boss room between phases; only the visible ones are fighting.
                if (findWithers && entity instanceof WitherBoss wither && wither.isAlive() && !wither.isInvisible()) foundWithers.add(wither);
                if (!config.starredMobs || !isStarredTag(entity)) continue;
                LivingEntity mob = mobBelow(mc, entity);
                if (mob != null && !foundStarred.contains(mob)) foundStarred.add(mob);
            }
            starred = foundStarred;
            bats = foundBats;
            withers = foundWithers;
        });
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.DungeonMobs config = config();
            if (config == null) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            if (config.starredMobs && !starred.isEmpty()) {
                float[] colour = colour(config.starredColor, new float[]{1f, 0.85f, 0.2f, 1f});
                for (LivingEntity mob : starred) drawBox(collector, mob, partial, colour, config.starredFill, config.starredLineWidth);
            }
            if (config.bats && !bats.isEmpty()) {
                float[] colour = colour(config.batColor, new float[]{0.33f, 1f, 1f, 1f});
                for (LivingEntity bat : bats) drawBox(collector, bat, partial, colour, config.starredFill, config.starredLineWidth);
            }
            if (config.withers && !withers.isEmpty()) {
                float[] colour = colour(config.witherColor, new float[]{1f, 0.67f, 0f, 1f});
                for (LivingEntity wither : withers) drawBox(collector, wither, partial, colour, config.starredFill, config.starredLineWidth);
            }
        });
    }

    private static void drawBox(PrimitiveCollector collector, LivingEntity mob, float partial, float[] colour, float fill, float lineWidth) {
        if (!mob.isAlive()) return;
        Vec3 offset = mob.getPosition(partial).subtract(mob.position());
        AABB box = mob.getBoundingBox().move(offset);
        if (fill > 0f) collector.submitFilledBox(box, colour, fill * colour[3], false);
        collector.submitOutlinedBox(box, colour, colour[3], lineWidth, false);
    }

    /** A nametag (an armor stand, or a non-living entity such as a text display) whose name has the star. */
    private static boolean isStarredTag(Entity entity) {
        if (!(entity instanceof ArmorStand) && entity instanceof LivingEntity) return false;
        Component name = entity.getCustomName();
        return name != null && name.getString().contains(STAR);
    }

    private static float[] colour(String value, float[] fallback) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f};
        } catch (Exception e) {
            return fallback;
        }
    }

    private static LivingEntity mobBelow(Minecraft mc, Entity tag) {
        AABB search = new AABB(tag.getX() - 1.5, tag.getY() - 4, tag.getZ() - 1.5, tag.getX() + 1.5, tag.getY() + 0.5, tag.getZ() + 1.5);
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (LivingEntity mob : mc.level.getEntitiesOfClass(LivingEntity.class, search,
                // Not teammates standing next to the mob: real players have version 4 UUIDs, Hypixel's player-skinned mobs
                // (Lost Adventurers, Shadow Assassins...) version 2.
                e -> !(e instanceof ArmorStand) && !(e instanceof Player p && p.getUUID().version() == 4) && e.isAlive())) {
            double distance = mob.distanceToSqr(tag.getX(), tag.getY(), tag.getZ());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = mob;
            }
        }
        return best;
    }

    /** /s2 debug starred: what the highlight sees, to find out why a mob isn't boxed. */
    public static Component debug() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return Component.literal("Not in a world.");
        int named = 0, tags = 0, matched = 0, batCount = 0;
        StringBuilder sample = new StringBuilder();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Bat) batCount++;
            Component name = entity.getCustomName();
            if (name == null) continue;
            named++;
            if (!isStarredTag(entity)) continue;
            tags++;
            LivingEntity mob = mobBelow(mc, entity);
            if (mob != null) matched++;
            if (sample.length() < 300) {
                sample.append("\n ").append(entity.getType().toShortString()).append(" \"").append(name.getString())
                    .append("\" -> ").append(mob == null ? "no mob below" : mob.getType().toShortString());
            }
        }
        FeatureConfigs.DungeonMobs config = config();
        return Component.literal("In dungeon: " + Sky2MLocation.inDungeon() + " (area \"" + Sky2MLocation.area() + "\")"
            + "\nStarred on: " + (config != null && config.starredMobs) + ", bats on: " + (config != null && config.bats)
            + "\nNamed entities: " + named + ", with " + STAR + ": " + tags + ", matched to a mob: " + matched
            + "\nBats: " + batCount + ", boxed now: " + starred.size() + " starred, " + bats.size() + " bats" + sample);
    }
}
