package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;

/**
 * Slayer Quest Warning, like SkyHanni's: hitting a slayer mob in its slayer's area while you have no slayer quest, or a
 * quest for a different slayer, gets a title and a chat line, so a grind doesn't go uncounted. At most once every 30
 * seconds. The area and the quest come from {@link SlayerArea}.
 */
public final class SlayerQuestWarning {
    private static final long COOLDOWN_MS = 30_000;
    private static long lastWarned;

    private SlayerQuestWarning() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.slayers.questWarning;
    }

    public static void init() {
        SlayerArea.init();
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && enabled() && Sky2MLocation.onSkyblock()) check(entity);
            return InteractionResult.PASS;
        });
    }

    private static void check(Entity target) {
        String here = SlayerArea.slayerHere();
        if (here == null || !isSlayerMob(here, target)) return;
        String quest = SlayerArea.questSlayer();
        if (quest != null && quest.equalsIgnoreCase(here)) return;
        long now = System.currentTimeMillis();
        if (now - lastWarned < COOLDOWN_MS) return;
        lastWarned = now;
        Component title = Component.literal(quest == null ? "No Slayer Quest!" : "Wrong Slayer Quest!").withStyle(ChatFormatting.RED);
        Component sub = Component.literal(quest == null ? "These " + mobName(here) + " count for nothing"
            : "You have " + quest + ", this is " + here).withStyle(ChatFormatting.YELLOW);
        Sky2MAlerts.title(title, sub, true);
        Sky2MAlerts.chat(Component.literal(quest == null
                ? "You're killing " + mobName(here) + " without a slayer quest. Start one at Maddox or a Slayer NPC."
                : "Your slayer quest is " + quest + ", but these mobs are for " + here + ".")
            .withStyle(ChatFormatting.YELLOW));
    }

    /** Whether the mob is one the area's slayer counts (Zombies for Revenant, Spiders for Tarantula...). */
    private static boolean isSlayerMob(String slayer, Entity e) {
        return switch (slayer) {
            case "Revenant Horror" -> e instanceof Zombie;
            case "Tarantula Broodfather" -> e instanceof Spider;
            case "Sven Packmaster" -> e instanceof Wolf;
            case "Voidgloom Seraph" -> e instanceof Enderman;
            case "Inferno Demonlord" -> e instanceof Monster;
            default -> false;
        };
    }

    private static String mobName(String slayer) {
        return switch (slayer) {
            case "Revenant Horror" -> "Zombies";
            case "Tarantula Broodfather" -> "Spiders";
            case "Sven Packmaster" -> "Wolves";
            case "Voidgloom Seraph" -> "Endermen";
            default -> "mobs";
        };
    }
}
