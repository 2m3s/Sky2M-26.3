package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonBoss;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Odin's Livid Solver (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/boss/LividSolver.kt): in the F5
 * and M5 boss, the wool block above the arena turns the colour of the real Livid. The solver names her in chat and
 * boxes her, and a HUD counts down her 340 tick invulnerability from her opening line.
 */
public final class LividSolver {
    private static final String START = "[BOSS] Livid: Welcome, you've arrived right on time. I am Livid, the Master of Shadows.";
    private static final BlockPos WOOL = new BlockPos(5, 108, 43);
    private static final int INVULNERABLE_TICKS = 340;

    private static Livid current = Livid.HOCKEY;
    /** The wool has shown which Livid is real (until then Odin assumes Hockey). */
    private static boolean found;
    private static Player entity;
    private static int invulnerableTicks;
    private static Level lastLevel;

    private LividSolver() {}

    private static FeatureConfigs.Dungeons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            if (onFloor5() && message.text().equals(START)) invulnerableTicks = INVULNERABLE_TICKS;
        });
        WorldEvents.BLOCK_STATE_UPDATE.register(LividSolver::onBlockUpdate);
        ServerTickCallback.EVENT.register(() -> {
            if (invulnerableTicks > 0 && onFloor5()) invulnerableTicks--;
        });
        ClientTickEvents.END_CLIENT_TICK.register(LividSolver::onClientTick);
        Sky2MWorldRender.register(LividSolver::render);
        Sky2MHuds.setting("livid_timer", () -> config() != null && config().lividTimer);
        Sky2MHuds.register("livid_timer", "Livid Invulnerability",
            () -> config() != null && config().lividTimer && inBoss() && invulnerableTicks > 0,
            () -> List.of(line(invulnerableTicks)),
            List.of(line(INVULNERABLE_TICKS)),
            200, 760);
    }

    private static boolean onFloor5() {
        return Sky2MLocation.inDungeon() && Sky2MLocation.dungeonFloor().endsWith("5");
    }

    private static boolean inBoss() {
        return Sky2MLocation.inDungeon() && DungeonManager.getBoss() == DungeonBoss.LIVID;
    }

    private static Component line(int ticks) {
        String colour = ticks > 260 ? "§a" : ticks > 130 ? "§e" : "§c";
        return Component.literal("§bLivid: " + colour + ticks + "t");
    }

    private static void onBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        if (!pos.equals(WOOL) || !inBoss()) return;
        for (Livid livid : Livid.values()) {
            if (!updated.is(livid.wool)) continue;
            if (found && livid == current) return;
            current = livid;
            found = true;
            entity = null;
            FeatureConfigs.Dungeons config = config();
            if (config != null && config.lividSolver) Sky2MAlerts.chat(Component.literal("§7Found Livid: §" + livid.colourCode + livid.displayName));
            return;
        }
    }

    private static void onClientTick(Minecraft mc) {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            current = Livid.HOCKEY;
            found = false;
            entity = null;
            invulnerableTicks = 0;
        }
        if (mc.level == null || !inBoss()) return;
        if (entity != null && entity.isAlive() && !entity.isRemoved()) return;
        entity = null;
        String name = current.displayName + " Livid";
        for (Player player : mc.level.players()) {
            if (player.getName().getString().equals(name)) {
                entity = player;
                break;
            }
        }
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.Dungeons config = config();
        Minecraft mc = Minecraft.getInstance();
        if (config == null || !config.lividSolver || entity == null || mc.player == null || !inBoss()) return;
        if (mc.player.hasEffect(MobEffects.BLINDNESS)) return;
        int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(config.lividColour).getEffectiveColourRGB();
        float[] colour = {((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f};
        float alpha = ((argb >>> 24) & 255) / 255f;
        collector.submitFilledBox(entity.getBoundingBox(), colour, alpha * 0.3f, true);
        collector.submitOutlinedBox(entity.getBoundingBox(), colour, alpha, 2f, true);
    }

    private enum Livid {
        VENDETTA("Vendetta", 'f', DyeColor.WHITE),
        CROSSED("Crossed", 'd', DyeColor.MAGENTA),
        ARCADE("Arcade", 'e', DyeColor.YELLOW),
        SMILE("Smile", 'a', DyeColor.LIME),
        DOCTOR("Doctor", '7', DyeColor.GRAY),
        PURPLE("Purple", '5', DyeColor.PURPLE),
        SCREAM("Scream", '9', DyeColor.BLUE),
        FROG("Frog", '2', DyeColor.GREEN),
        HOCKEY("Hockey", 'c', DyeColor.RED);

        final String displayName;
        final char colourCode;
        final Block wool;

        Livid(String displayName, char colourCode, DyeColor dye) {
            this.displayName = displayName;
            this.colourCode = colourCode;
            this.wool = Blocks.WOOL.pick(dye);
        }
    }
}
