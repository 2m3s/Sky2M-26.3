package com.epic60869.sky2m.features.combat.extra;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayers > Vampire: Skyblocker's rift EffigyWaypoints and its Vampire Slayer ManiaIndicator, StakeIndicator and
 * TwinClawsIndicator (https://github.com/SkyblockerMod/Skyblocker, LGPL-3.0), plus SkyHanni's Blood Ichor / Killer Spring
 * highlight (https://github.com/hannibal002/SkyHanni, LGPL-2.1). Display only.
 */
public final class VampireSlayerHelper {
    private static final List<BlockPos> EFFIGIES = List.of(
        new BlockPos(150, 79, 95), new BlockPos(193, 93, 119), new BlockPos(235, 110, 147),
        new BlockPos(293, 96, 134), new BlockPos(262, 99, 94), new BlockPos(240, 129, 118));
    private static final float[] RED = {1f, 0.2f, 0.2f};
    private static final float[] ICHOR = {0.7f, 0f, 0f};
    /** Hypixel's texture hashes of the Blood Ichor and Killer Spring skulls (SkyHanni-REPO Skulls.json). */
    private static final String ICHOR_HASH = CombatExtras.textureHash(
        "ewogICJ0aW1lc3RhbXAiIDogMTYxNTg4ODAwMDU1MywKICAicHJvZmlsZUlkIiA6ICI5ZDIyZGRhOTVmZGI0MjFmOGZhNjAzNTI1YThkZmE4ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYWZlRHJpZnQ0OCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMDM0MDkyM2E2ZGU0ODI1YTE3NjgxM2QxMzM1MDNlZmYxODZkYjA4OTZlMzJiNjcwNDkyOGMyYTJiZjY4NDIyIgogICAgfQogIH0KfQ==");
    private static final String SPRING_HASH = CombatExtras.textureHash(
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTU5NDQxNjY5NywKICAicHJvZmlsZUlkIiA6ICJjY2MxNGM2ZDUwMDE0MjBmYmMxYjkyMTM2Y2JmOWU4MSIsCiAgInByb2ZpbGVOYW1lIiA6ICJXaGlybGluZ0F0b2w5NDQiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzdmN2E3YmM4YWM4NmYyM2NhN2JmOThhZmViNzY5NjAyMjdlMTgzMmZlMjA5YTMwMjZmNmNlYjhiZGU3NGY1NCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
    private static final Pattern SPAWNED_BY = Pattern.compile("Spawned by:\\s*(\\S+)");
    private static final Pattern HEALTH = Pattern.compile("([\\d.,]+[kKmMbB]?)\\s*❤");
    private static final int GRAY = 0xAAAAAA;

    private static final List<BlockPos> unbroken = new CopyOnWriteArrayList<>();
    private static List<Vec3> ichors = List.of();
    private static int ticks;
    private static int clawTicksLeft = -1;
    private static long lastClawTitle, lastManiaTitle, lastStakeTitle;
    private static boolean clawsSeen;
    private static double bossMax;

    private VampireSlayerHelper() {}

    private static CombatExtraConfig.VampireSlayer config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.vampire;
    }

    private static boolean inRift() {
        return Sky2MLocation.onSkyblock() && Sky2MLocation.areaIs("The Rift");
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(VampireSlayerHelper::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            unbroken.clear();
            ichors = List.of();
            clawTicksLeft = -1;
            bossMax = 0;
        });
        Sky2MWorldRender.register(collector -> {
            CombatExtraConfig.VampireSlayer c = config();
            if (c == null || !inRift()) return;
            if (c.effigyWaypoints) {
                for (BlockPos effigy : unbroken) {
                    if (c.compactEffigies) {
                        collector.submitFilledBoxWithBeaconBeam(effigy.below(6), RED, 0.5f, true);
                    } else {
                        collector.submitFilledBoxWithBeaconBeam(effigy, RED, 0.5f, true);
                        for (int i = 1; i < 6; i++) collector.submitFilledBox(effigy.below(i), RED, 0.5f - 0.075f * i, true);
                    }
                }
            }
            if (c.highlightIchor) {
                for (Vec3 pos : ichors) {
                    collector.submitFilledBox(pos.subtract(0.4, 0, 0.4), new Vec3(0.8, 0.8, 0.8), ICHOR, 0.5f, true);
                    collector.submitText(Component.literal("Ichor").withStyle(ChatFormatting.DARK_RED), pos.add(0, 1.4, 0), 1.3f, false);
                }
            }
        });
    }

    private static void tick(Minecraft mc) {
        CombatExtraConfig.VampireSlayer c = config();
        if (c == null || mc.level == null || mc.player == null || !inRift()) {
            if (!unbroken.isEmpty()) unbroken.clear();
            ichors = List.of();
            return;
        }
        ticks++;
        if (clawTicksLeft > 0 && --clawTicksLeft == 0) {
            clawTicksLeft = -1;
            lastClawTitle = System.currentTimeMillis();
            Sky2MAlerts.title(Component.literal("Holy Ice!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), null);
        }
        if (c.effigyWaypoints && ticks % 20 == 0) updateEffigies(mc);
        else if (!c.effigyWaypoints && !unbroken.isEmpty()) unbroken.clear();
        if (ticks % 5 == 0 && c.highlightIchor) scanIchor(mc);
        else if (!c.highlightIchor) ichors = List.of();
        if (ticks % 3 == 0 && (c.maniaIndicator || c.stakeIndicator || c.twinclawsIndicator)) scanBoss(mc, c);
    }

    // ------------------------------------------------------------------------------------------------ effigies

    /** The "Effigies: ⧯⧯⧯⧯⧯⧯" sidebar line: a grey symbol is an effigy you have not broken yet. */
    private static void updateEffigies(Minecraft mc) {
        List<BlockPos> found = new ArrayList<>();
        for (PlayerTeam team : mc.level.getScoreboard().getPlayerTeams()) {
            Component line = Component.empty().append(team.getPlayerPrefix()).append(team.getPlayerSuffix());
            if (!line.getString().contains("Effigies")) continue;
            List<Integer> colours = new ArrayList<>();
            StringBuilder seen = new StringBuilder();
            boolean[] afterLabel = {false};
            line.visit((style, text) -> {
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    if (!afterLabel[0]) {
                        seen.append(ch);
                        if (seen.toString().contains("Effigies:")) afterLabel[0] = true;
                        continue;
                    }
                    if (Character.isWhitespace(ch) || ch == '§') continue;
                    colours.add(style.getColor() == null ? -1 : style.getColor().getValue());
                }
                return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            for (int i = 0; i < colours.size() && i < EFFIGIES.size(); i++) {
                if (colours.get(i) == GRAY) found.add(EFFIGIES.get(i));
            }
            break;
        }
        unbroken.clear();
        unbroken.addAll(found);
    }

    // ------------------------------------------------------------------------------------------------ boss

    private static String ownName(Minecraft mc) {
        return mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
    }

    /** Finds your Bloodfiend, reads the nametags around it and fires the indicators. */
    private static void scanBoss(Minecraft mc, CombatExtraConfig.VampireSlayer c) {
        Vec3 anchor = null;
        double best = 40 * 40;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.getName().getString().contains("Bloodfiend")) continue;
            double d = p.distanceToSqr(mc.player);
            if (d < best) {
                best = d;
                anchor = p.position();
            }
        }
        if (anchor == null) {
            bossMax = 0;
            return;
        }
        StringBuilder text = new StringBuilder();
        boolean foreign = false;
        for (Entity e : mc.level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(anchor.x - 2.5, anchor.y - 1, anchor.z - 2.5, anchor.x + 2.5, anchor.y + 4.5, anchor.z + 2.5),
                x -> CombatExtras.nametag(x) != null)) {
            String t = Sky2MLocation.strip(CombatExtras.nametag(e).getString());
            text.append(t).append('\n');
            Matcher owner = SPAWNED_BY.matcher(t);
            if (owner.find() && !owner.group(1).toLowerCase(Locale.ROOT).equals(ownName(mc))) foreign = true;
        }
        if (foreign) return;
        String all = text.toString();
        long now = System.currentTimeMillis();

        Matcher hp = HEALTH.matcher(all);
        double health = hp.find() ? CombatExtras.parseNumber(hp.group(1)) : -1;
        if (health > bossMax) bossMax = health;

        if (c.maniaIndicator && all.contains("MANIA") && now - lastManiaTitle > 700) {
            lastManiaTitle = now;
            boolean green = mc.level.getBlockState(mc.player.blockPosition().below()).is(Blocks.DYED_TERRACOTTA.green());
            Sky2MAlerts.title(Component.literal("MANIA").withStyle(green ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD),
                null, !green);
        }
        boolean canStake = all.contains("҉") || (health > 0 && bossMax > 0 && health <= bossMax * 0.2);
        if (c.stakeIndicator && canStake && now - lastStakeTitle > 1_500) {
            lastStakeTitle = now;
            Sky2MAlerts.title(Component.literal("STAKE NOW!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), null);
        }
        boolean claws = all.contains("TWINCLAWS");
        if (c.twinclawsIndicator) {
            if (claws && !clawsSeen && clawTicksLeft < 0 && now - lastClawTitle > 5_000) clawTicksLeft = Math.max(1, c.twinclawsDelay);
            if (!claws) clawTicksLeft = -1;
        }
        clawsSeen = claws;
    }

    private static void scanIchor(Minecraft mc) {
        List<Vec3> found = new ArrayList<>();
        for (Entity e : mc.level.getEntities((Entity) null, mc.player.getBoundingBox().inflate(15), x -> x instanceof ArmorStand)) {
            String hash = CombatExtras.headHash((ArmorStand) e);
            if (hash.isEmpty()) continue;
            if (hash.equals(ICHOR_HASH) || hash.equals(SPRING_HASH)) found.add(e.position());
        }
        ichors = found;
    }
}
