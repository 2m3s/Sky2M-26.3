package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** Glowing outlines on your dungeon teammates in their class's Leap Menu colour. Ghosts (no class) aren't outlined. */
public final class TeammateHighlight {
    private TeammateHighlight() {}

    public static void init() {
        EntityGlow.register(entity -> {
            if (!(entity instanceof Player player) || player == Minecraft.getInstance().player) return -1;
            Sky2MConfig config = Sky2MConfig.current();
            if (config == null || !config.dungeons.mobs.teammates || !Sky2MLocation.inDungeon()) return -1;
            DungeonClass dungeonClass = DungeonPlayerManager.getClassFromPlayer(player);
            return dungeonClass == DungeonClass.UNKNOWN ? -1 : LeapMenu.classColour(dungeonClass) & 0xFFFFFF;
        });
    }
}
