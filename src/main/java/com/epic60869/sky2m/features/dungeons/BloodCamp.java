package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Blood camp helpers, following Odin's BloodCamp (https://github.com/odtheking/Odin, BSD-3-Clause):
 * <ul>
 *     <li>Watcher move prediction: when the Watcher says "Let's see how you can handle this.", predicts when it moves,
 *     shows a Move Timer HUD and a "Kill Mobs" title when it is time to kill the first spawns.</li>
 *     <li>Blood camp assist: each head the Watcher throws gets a box where it lands, a box on the head moved ahead by
 *     your ping, and a countdown until the mob spawns. Like Odin, a head is only tracked once it moves, so the heads
 *     hanging on the blood room walls are ignored.</li>
 *     <li>Watcher bar: blood mobs left in the Watcher's boss bar.</li>
 * </ul>
 * Everything is counted in server ticks.
 */
public final class BloodCamp {
    private static final Pattern BLOOD_START = Pattern.compile("^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|Ah, we meet again\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|You've managed to scratch and claw your way here, eh\\?|I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|Things feel a little more roomy now, eh\\?)$");
    private static final Pattern BLOOD_MOVE = Pattern.compile("^\\[BOSS] The Watcher: Let's see how you can handle this\\.$");
    private static final float[] LINE_COLOUR = {1f, 0.33f, 0.33f};

    /** A head the Watcher has thrown. Created on its first movement, like Odin's EntityEvent.Move. */
    private static final class Head {
        final Vec3 start;
        final long started;
        final boolean firstSpawns;
        Vec3 last;
        Vec3 totalDelta = Vec3.ZERO;
        Vec3 end;
        Vec3 lastEnd;
        long endUpdated;
        Vec3 speed = Vec3.ZERO;

        Head(Vec3 start, long started, boolean firstSpawns) {
            this.start = start;
            this.last = start;
            this.end = start;
            this.started = started;
            this.endUpdated = started;
            this.firstSpawns = firstSpawns;
        }
    }

    private static final Map<ArmorStand, Head> HEADS = new HashMap<>();
    /** Where each mob-skull armor stand near the Watcher was first seen. It only becomes a {@link Head} once it moves. */
    private static final Map<ArmorStand, Vec3> RESTING = new HashMap<>();
    private static Zombie watcher;
    private static boolean firstSpawns = true;
    /** Server time in ms, advanced 50 per server tick. */
    private static long tickTime;
    private static long startTime = -1;
    private static float moveSeconds = -1;
    private static int killTitleIn = -1;

    private BloodCamp() {}

    private static FeatureConfigs.BloodCamp config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.bloodCamp;
    }

    private static boolean inClear() {
        return Sky2MLocation.inDungeon() && !DungeonManager.isInBoss();
    }

    /** RGBA 0-1 from a MoulConfig colour string. */
    private static float[] colour(String value, float[] fallback) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f};
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Vec3 lerp(Vec3 current, Vec3 last, float t) {
        return last == null ? current : last.add(current.subtract(last).scale(t));
    }

    private static int ping() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) return 0;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? 0 : Math.max(0, info.getLatency());
    }

    /**
     * The countdown's colour, like Odin's and NoammAddons's: green, yellow, gold as it nears {@code beam} seconds, red
     * from then until the mob spawns (shoot now), aqua once it has.
     */
    private static ChatFormatting timeColour(float seconds, float beam) {
        if (seconds < 0) return ChatFormatting.AQUA;
        if (seconds <= beam) return ChatFormatting.RED;
        if (seconds <= beam + 0.5f) return ChatFormatting.GOLD;
        if (seconds <= beam + 1.0f) return ChatFormatting.YELLOW;
        return ChatFormatting.GREEN;
    }

    private static float[] rgba(ChatFormatting colour, float alpha) {
        net.minecraft.network.chat.TextColor text = net.minecraft.network.chat.TextColor.fromLegacyFormat(colour);
        int rgb = text == null ? 0xFFFFFF : text.getValue();
        return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha};
    }

    private static void box(PrimitiveCollector collector, Vec3 at, double size, float[] colour) {
        if (colour[3] <= 0f) return;
        AABB box = new AABB(0, 0, 0, size, size, size).move(at.add(-size / 2, 1.5, -size / 2));
        collector.submitOutlinedBox(box, colour, colour[3], 2f, false);
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        ServerTickCallback.EVENT.register(BloodCamp::onServerTick);
        Sky2MChat.onChat(message -> onChat(message.text()));
        Sky2MWorldRender.register(collector -> {
            FeatureConfigs.BloodCamp config = config();
            if (config == null || !config.killTimers || !inClear()) return;
            float[] spawn = colour(config.spawnColor, new float[]{1f, 0.33f, 0.33f, 1f});
            float[] fin = colour(config.finalColor, new float[]{0f, 0.67f, 0.67f, 1f});
            float[] position = colour(config.positionColor, new float[]{0.33f, 1f, 0.33f, 1f});
            double size = config.boxSize;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            float mobOffset = config.pingOffset ? ping() : 0f;
            for (var entry : HEADS.entrySet()) {
                ArmorStand stand = entry.getKey();
                if (!stand.isAlive()) continue;
                Head head = entry.getValue();
                Vec3 end = config.interpolation
                    ? lerp(head.end, head.lastEnd, Math.min(tickTime - head.endUpdated, 100) / 100f)
                    : head.end;
                long time = (head.firstSpawns ? 2000 : 0) + config.tick * 50L - (tickTime - head.started) + config.offset;
                Vec3 standPos = stand.getPosition(partial);
                float seconds = (time - config.offset) / 1000f;
                ChatFormatting timeColour = timeColour(seconds, config.beamTime);
                if (mobOffset < time) {
                    box(collector, standPos.add(head.speed.scale(mobOffset)), size, position);
                    box(collector, end, size, config.colourBoxByTime ? rgba(timeColour, spawn[3]) : spawn);
                } else {
                    box(collector, end, size, fin);
                }
                if (config.line) {
                    collector.submitLinesFromPoints(new Vec3[]{standPos.add(0, 2, 0), end.add(0, 2, 0)}, LINE_COLOUR, 1f, 1.5f, false);
                }
                if (config.timeLeft) {
                    collector.submitText(Component.literal(String.format(Locale.US, "%.2fs", seconds)).withStyle(timeColour), end.add(0, 2, 0), 2f, true);
                }
            }
        });
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("blood_move_timer", () -> config() != null && config().movePrediction);
        Sky2MHuds.register("blood_move_timer", "Watcher Move Timer",
            () -> config() != null && config().movePrediction && moveSeconds > 0,
            () -> List.of(Component.literal(String.format(Locale.US, "Move Timer: %.2fs", moveSeconds)).withStyle(ChatFormatting.RED)),
            List.of(Component.literal("Move Timer: 0.50s").withStyle(ChatFormatting.RED)),
            200, 700);
    }

    /** Watcher Bar: "The Watcher 7/19" in the boss bar, like Odin. Called from the boss bar mixin. */
    public static Component watcherBarName(Component name, float progress) {
        FeatureConfigs.BloodCamp config = config();
        if (config == null || !config.watcherBar || !inClear() || progress < 0.05f) return name;
        if (!"The Watcher".equals(Sky2MLocation.strip(name.getString()))) return name;
        String floor = Sky2MLocation.dungeonFloor();
        int floorNumber = floor.isEmpty() || !Character.isDigit(floor.charAt(floor.length() - 1)) ? 0 : floor.charAt(floor.length() - 1) - '0';
        int amount = 12 + floorNumber;
        return name.copy().append(Component.literal(" " + Math.round(amount * progress) + "/" + amount).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
    }

    private static void reset() {
        HEADS.clear();
        RESTING.clear();
        watcher = null;
        firstSpawns = true;
        tickTime = 0;
        startTime = -1;
        moveSeconds = -1;
        killTitleIn = -1;
    }

    private static void onChat(String text) {
        FeatureConfigs.BloodCamp config = config();
        if (config == null || !inClear()) return;
        if (BLOOD_START.matcher(text).matches()) {
            startTime = tickTime;
        } else if (BLOOD_MOVE.matcher(text).matches()) {
            firstSpawns = false;
            if (startTime < 0 || !config.movePrediction) return;
            long moveTicks = (tickTime - startTime) / 20 / 50;
            int predTicks;
            if (moveTicks >= 31 && moveTicks < 34) predTicks = 36;
            else if (moveTicks >= 28) predTicks = moveTicks < 31 ? 33 : (int) moveTicks + 3;
            else if (moveTicks >= 25) predTicks = 30;
            else if (moveTicks >= 22) predTicks = 27;
            else if (moveTicks >= 1) predTicks = 24;
            else predTicks = (int) moveTicks + 3;
            moveSeconds = predTicks / 20f;
            killTitleIn = predTicks;
            String msg = String.format(Locale.US, "Watcher will move in %.2fs.", moveSeconds);
            if (config.moveMessage) Sky2MAlerts.chat(Component.literal(msg).withStyle(ChatFormatting.RED));
            if (config.partyMoveMessage) {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.sendCommand("pc " + msg);
            }
        }
    }

    private static void onServerTick() {
        tickTime += 50;
        if (moveSeconds > 0) moveSeconds = Math.max(0, moveSeconds - 0.05f);
        if (killTitleIn > 0 && --killTitleIn == 0) {
            FeatureConfigs.BloodCamp config = config();
            if (config != null && config.killTitle) Sky2MAlerts.title(Component.literal("Kill Mobs").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.empty());
            moveSeconds = -1;
        }
        FeatureConfigs.BloodCamp config = config();
        if (config == null || !config.killTimers || !inClear()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (watcher != null && !watcher.isAlive()) watcher = null;
        if (watcher == null) {
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof Zombie zombie && BloodCampSkulls.WATCHER.contains(Compat.getHeadTexture(zombie.getItemBySlot(EquipmentSlot.HEAD)))) {
                    watcher = zombie;
                    break;
                }
            }
            if (watcher == null) return;
        }
        HEADS.keySet().removeIf(stand -> !stand.isAlive());
        RESTING.keySet().removeIf(stand -> !stand.isAlive());
        for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, watcher.getBoundingBox().inflate(20))) {
            Vec3 pos = stand.position();
            Head head = HEADS.get(stand);
            if (head == null) {
                Vec3 rest = RESTING.get(stand);
                if (rest == null) {
                    ItemStack helmet = stand.getItemBySlot(EquipmentSlot.HEAD);
                    if (!helmet.is(Items.PLAYER_HEAD) || !BloodCampSkulls.MOBS.contains(Compat.getHeadTexture(helmet))) continue;
                    RESTING.put(stand, pos);
                    continue;
                }
                // Heads on the walls never move; only a thrown head starts being tracked.
                if (pos.equals(rest)) continue;
                RESTING.remove(stand);
                head = new Head(pos, tickTime, firstSpawns);
                HEADS.put(stand, head);
            }
            Vec3 delta = pos.subtract(head.last);
            head.last = pos;
            if (delta.lengthSqr() > 0) head.totalDelta = head.totalDelta.add(delta);
            // The head flies in a straight line: 16.1 blocks for the first wave, 11.9 after.
            Vec3 direction = head.totalDelta.lengthSqr() > 0 ? head.totalDelta.normalize() : Vec3.ZERO;
            Vec3 end = head.start.add(direction.scale(head.firstSpawns ? 16.1 : 11.9));
            long took = tickTime - head.started;
            if (took > 0) head.speed = pos.subtract(head.start).scale(1.0 / took);
            if (!end.equals(head.end)) {
                head.lastEnd = head.end;
                head.end = end;
                head.endUpdated = tickTime;
            }
        }
    }
}
