// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from NoFrills (https://github.com/WhatYouThing/NoFrills, GPL-3.0): features/dungeons/WitherDragons.java and
// RelicHighlight.java. Dragon boxes there are taken from Odin's WitherDragonEnum. Dragon priority (the dragon that
// spawns first, Solo Priority) and the relic place timer follow NoammAddons (https://github.com/Noamm9/NoammAddons,
// CC0 1.0): features/impl/floor7/dragons/DragonCheck.kt and M7Relics.kt.
package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.mixin.Sky2MBossOverlayAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > M7 Dragons and Relics: the Master Mode floor 7 dragon phase.
 * <ul>
 *     <li>Relic Highlight: the cauldron for the relic you're holding; Relic Place Timer: when each relic was placed.</li>
 *     <li>Spawn alerts naming your priority dragon, spawn timers, kill areas, hitboxes, a tracer, stack waypoints,
 *     dragon health and an Ice Spray tracker.</li>
 * </ul>
 * Since the Minister update the dragons spawn one after another rather than in pairs, so (as in NoammAddons) your
 * priority dragon is the spawning one with the least time left, or the next one for the Solo Priority class.
 * A dragon starts spawning when the server sends its flame particles (20 flames at y 19 in its kill area); the entity
 * is matched to a dragon by the relic "collar" armour stand next to it.
 */
public final class WitherDragons {
    private static final Relic[] RELICS = {
        new Relic("Corrupted Green Relic", new BlockPos(49, 7, 44), 0x00FF00),
        new Relic("Corrupted Red Relic", new BlockPos(51, 7, 42), 0xFF0000),
        new Relic("Corrupted Purple Relic", new BlockPos(54, 7, 41), 0xAA00AA),
        new Relic("Corrupted Orange Relic", new BlockPos(57, 7, 42), 0xFFAA00),
        new Relic("Corrupted Blue Relic", new BlockPos(59, 7, 44), 0x55FFFF),
    };

    private static final Dragon RED = new Dragon("Red", "RED_KING_RELIC", 0xFF0000,
        AABB.ofSize(new Vec3(27.0, 14.0, 59.0), 1, 1, 1),
        List.of(
            new AABB(25.5, 14.0, 52.0, 28.5, 17.0, 55.0),
            new AABB(24.5, 14.0, 56.0, 29.5, 17.0, 61.0),
            new AABB(26.0, 15.5, 61.5, 28.0, 17.5, 67.5),
            new AABB(29.5, 16.0, 57.0, 33.5, 18.0, 61.0),
            new AABB(20.5, 16.0, 57.0, 24.5, 18.0, 61.0)),
        new AABB(14.5, 5, 45.5, 39.5, 28, 70.5)).stack(50, 28, 58);
    private static final Dragon ORANGE = new Dragon("Orange", "ORANGE_KING_RELIC", 0xFFAA00,
        AABB.ofSize(new Vec3(85.0, 14.0, 56.0), 1, 1, 1),
        List.of(
            new AABB(83.5, 14.0, 49.0, 86.5, 17.0, 52.0),
            new AABB(82.5, 14.0, 53.0, 87.5, 17.0, 58.0),
            new AABB(84.0, 15.5, 58.5, 86.0, 17.5, 64.5),
            new AABB(87.5, 16.0, 54.0, 91.5, 18.0, 58.0),
            new AABB(78.5, 16.0, 54.0, 82.5, 18.0, 58.0)),
        new AABB(72, 5, 47, 102, 28, 77)).stack(62, 85, 56);
    private static final Dragon BLUE = new Dragon("Blue", "BLUE_KING_RELIC", 0x55FFFF,
        AABB.ofSize(new Vec3(84.0, 14.0, 94.0), 1, 1, 1),
        List.of(
            new AABB(82.5, 14.0, 87.0, 85.5, 17.0, 90.0),
            new AABB(81.5, 14.0, 91.0, 86.5, 17.0, 96.0),
            new AABB(83.0, 15.5, 96.5, 85.0, 17.5, 102.5),
            new AABB(86.5, 16.0, 92.0, 90.5, 18.0, 96.0),
            new AABB(77.5, 16.0, 92.0, 81.5, 18.0, 96.0)),
        new AABB(71.5, 5, 82.5, 96.5, 26, 107.5)).stack(47, 84, 97);
    private static final Dragon PURPLE = new Dragon("Purple", "PURPLE_KING_RELIC", 0xAA00AA,
        AABB.ofSize(new Vec3(56.0, 14.0, 125.0), 1, 1, 1),
        List.of(
            new AABB(54.5, 14.0, 118.0, 57.5, 17.0, 121.0),
            new AABB(53.5, 14.0, 122.0, 58.5, 17.0, 127.0),
            new AABB(55.0, 15.5, 127.5, 57.0, 17.5, 133.5),
            new AABB(58.5, 16.0, 123.0, 62.5, 18.0, 127.0),
            new AABB(49.5, 16.0, 123.0, 53.5, 18.0, 127.0)),
        new AABB(45.5, 6, 113.5, 68.5, 23, 136.5)).stack(38, 56, 125);
    private static final Dragon GREEN = new Dragon("Green", "GREEN_KING_RELIC", 0x00FF00,
        AABB.ofSize(new Vec3(27.0, 14.0, 94.0), 1, 1, 1),
        List.of(
            new AABB(25.5, 14.0, 87.0, 28.5, 17.0, 90.0),
            new AABB(24.5, 14.0, 91.0, 29.5, 17.0, 96.0),
            new AABB(26.0, 15.5, 96.5, 28.0, 17.5, 102.5),
            new AABB(29.5, 16.0, 92.0, 33.5, 18.0, 96.0),
            new AABB(20.5, 16.0, 92.0, 24.5, 18.0, 96.0)),
        new AABB(7, 5, 80, 37, 28, 110)).stack(52, 27, 90);
    /** NoammAddons' order, which breaks ties between dragons that start spawning on the same tick. */
    private static final List<Dragon> DRAGONS = List.of(RED, ORANGE, GREEN, BLUE, PURPLE);
    /** The M7 boss room; the dragon phase is the part of it below y 50. */
    private static final AABB BOSS_ROOM = new AABB(-8, 0, -8, 134, 254, 147);

    private static final Pattern RELIC_PICKUP = Pattern.compile("^(\\w{3,16}) picked the Corrupted (\\w{3,6}) Relic!$");

    /** Dragons that have started spawning this run (Solo Priority's First Dragon Only). */
    private static int spawnStarts;
    private static int tickCounter;
    private static long serverTicks;
    /** Server tick the Wither King's boss bar appeared on: the relic place timer counts from it. */
    private static long witherKingTick = -1;
    private static final List<RelicPickup> pickups = new ArrayList<>();

    private WitherDragons() {}

    private static FeatureConfigs.WitherDragons config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.witherDragons;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        ServerTickCallback.EVENT.register(WitherDragons::onServerTick);
        ClientTickEvents.END_CLIENT_TICK.register(WitherDragons::onClientTick);
        Sky2MChat.onChat(WitherDragons::onChat);
        Sky2MHuds.setting("dragon_spawn_timer", () -> config() != null && config().timer);
        Sky2MHuds.registerCustom("dragon_spawn_timer", "Dragon Spawn Timer", () -> config() != null && config().timer, new TimerHud(), 450, 200);
        Sky2MWorldRender.register(WitherDragons::render);
    }

    private static void reset() {
        spawnStarts = 0;
        tickCounter = 0;
        witherKingTick = -1;
        pickups.clear();
        for (Dragon d : DRAGONS) d.reset();
    }

    static boolean inDragonPhase() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getY() < 50 && PositionalMessages.onFloor7() && BOSS_ROOM.contains(mc.player.position());
    }

    private static boolean onMasterFloor7() {
        return PositionalMessages.onFloor7() && Sky2MLocation.dungeonFloor().startsWith("M");
    }

    /** Whether you're the Solo Priority class, who takes the second of two spawning dragons. */
    private static boolean soloClass(FeatureConfigs.WitherDragons config) {
        DungeonClass c = SelfClass.get();
        return switch (config.soloPriority) {
            case HEALER -> c == DungeonClass.HEALER;
            case TANK -> c == DungeonClass.TANK;
            case OFF -> false;
        };
    }

    /** NoammAddons' priority: the spawning dragon with the least time left, or the next one for the solo class. */
    private static Dragon priority(FeatureConfigs.WitherDragons config) {
        List<Dragon> spawning = DRAGONS.stream().filter(Dragon::isSpawning).sorted(Comparator.comparingInt(d -> d.spawnTicks)).toList();
        if (spawning.isEmpty()) return null;
        if (spawning.size() > 1 && soloClass(config) && (!config.firstDragonOnly || spawnStarts <= 2)) return spawning.get(1);
        return spawning.getFirst();
    }

    private static void announce(Dragon dragon, Dragon priority, boolean several) {
        if (dragon == priority) {
            Sky2MAlerts.title(Component.literal(dragon.name.toUpperCase(Locale.ROOT) + " IS SPAWNING").setStyle(Style.EMPTY.withBold(true).withColor(dragon.colour)), null);
            Sky2MAlerts.chat(Component.literal(dragon.name).withColor(dragon.colour)
                .append(Component.literal(several ? " is your priority dragon." : " is spawning.").withStyle(ChatFormatting.GRAY)));
            return;
        }
        MutableComponent line = Component.literal(dragon.name).withColor(dragon.colour)
            .append(Component.literal(" is spawning.").withStyle(ChatFormatting.GRAY));
        if (priority != null) {
            line.append(Component.literal(" Your priority is ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(priority.name).withColor(priority.colour))
                .append(Component.literal(".").withStyle(ChatFormatting.GRAY));
        }
        Sky2MAlerts.chat(line);
    }

    /** Called from Sky2MWitherDragonsMixin for every particle packet, on the render thread. */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        FeatureConfigs.WitherDragons config = config();
        if (config == null || !isDragonParticle(packet) || !inDragonPhase()) return;
        Vec3 pos = new Vec3(packet.x(), packet.y(), packet.z());
        Dragon started = null;
        for (Dragon dragon : DRAGONS) {
            if (dragon.spawnTicks != 0 || !dragon.area.contains(pos)) continue;
            dragon.spawnTicks = 100;
            spawnStarts++;
            started = dragon;
        }
        if (started == null || !config.alert) return;
        announce(started, priority(config), DRAGONS.stream().filter(Dragon::isSpawning).count() > 1);
    }

    private static boolean isDragonParticle(ClientboundLevelParticlesPacket p) {
        return p.particle().getType() == ParticleTypes.FLAME && p.count() == 20 && p.y() == 19
            && p.xDist() == 2f && p.yDist() == 3f && p.zDist() == 2f && p.xMaxSpeed() == 0f
            && p.x() % 1 == 0 && p.z() % 1 == 0;
    }

    /** Called from Sky2MWitherDragonsMixin after an entity's data (health included) is updated. */
    public static void onEntityData(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || config() == null || !inDragonPhase()) return;
        if (!(mc.level.getEntity(id) instanceof EnderDragon entity)) return;
        // Read the health now: the client resets it on the next tick.
        for (Dragon dragon : DRAGONS) {
            if (dragon.entity == entity) dragon.health = entity.getHealth();
        }
    }

    private static void onServerTick() {
        serverTicks++;
        if (!inDragonPhase()) return;
        // Remember which dragon was your priority as it spawns, for Arrows Hit.
        FeatureConfigs.WitherDragons config = config();
        Dragon priority = config == null ? null : priority(config);
        if (priority != null && priority.spawnTicks == 1) priority.wasPriority = true;
        for (Dragon dragon : DRAGONS) dragon.tick();
        tickCounter++;
    }

    private static void onClientTick(Minecraft mc) {
        FeatureConfigs.WitherDragons config = config();
        if (config == null || mc.level == null) return;
        if (witherKingTick < 0 && config.relicTimer && onMasterFloor7() && witherKingBarShown(mc)) witherKingTick = serverTicks;
        if (!inDragonPhase()) return;
        List<EnderDragon> dragons = new ArrayList<>();
        List<ArmorStand> stands = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EnderDragon d && d.isAlive()) dragons.add(d);
            else if (entity instanceof ArmorStand s) stands.add(s);
        }
        if (config.relicTimer) trackRelics(mc, stands);
        for (Dragon dragon : DRAGONS) {
            if (dragon.hasEntity()) continue;
            find:
            for (ArmorStand stand : stands) {
                if (!dragon.relicId.equals(Compat.neuName(stand.getItemBySlot(EquipmentSlot.HEAD)))) continue;
                for (EnderDragon entity : dragons) {
                    if (horizontalDistance(entity.position(), stand.position()) <= 10) {
                        dragon.setEntity(entity);
                        break find;
                    }
                }
            }
        }
        if (!config.trackIceSpray) return;
        for (ArmorStand stand : stands) {
            if (!isIceSpray(stand)) continue;
            for (Dragon dragon : DRAGONS) {
                if (!dragon.hasEntity() || dragon.iceSprayed) continue;
                EnderDragon entity = dragon.entity;
                if (horizontalDistance(entity.position(), stand.position()) < 2 && stand.getY() > entity.getY()) {
                    Sky2MAlerts.chat(Component.literal(dragon.name).withColor(dragon.colour)
                        .append(Component.literal(" Ice Sprayed in " + (tickCounter - dragon.spawnedAt) + " tick(s).").withStyle(ChatFormatting.GRAY)));
                    dragon.iceSprayed = true;
                    break;
                }
            }
        }
    }

    private static boolean witherKingBarShown(Minecraft mc) {
        for (LerpingBossEvent bar : ((Sky2MBossOverlayAccessor) mc.gui.hud.getBossOverlay()).sky2m$getEvents().values()) {
            if (bar.getName().getString().toLowerCase(Locale.ROOT).contains("wither king")) return true;
        }
        return false;
    }

    /** "Name picked the Corrupted Red Relic!" on M7: the relic place timer waits for that relic to be placed. */
    private static void onChat(Sky2MChat.Message message) {
        FeatureConfigs.WitherDragons config = config();
        if (config == null || !config.relicTimer || !onMasterFloor7()) return;
        Matcher m = RELIC_PICKUP.matcher(message.text());
        if (!m.matches()) return;
        String name = "Corrupted " + m.group(2) + " Relic";
        for (Relic relic : RELICS) {
            if (relic.name.equalsIgnoreCase(name)) pickups.add(new RelicPickup(relic, m.group(1)));
        }
    }

    /** NoammAddons' Place Timer: a relic is placed when a relic armour stand stands on its cauldron. */
    private static void trackRelics(Minecraft mc, List<ArmorStand> stands) {
        if (pickups.isEmpty()) return;
        for (ArmorStand stand : stands) {
            if (!stand.getItemBySlot(EquipmentSlot.HEAD).getHoverName().getString().contains("Relic")) continue;
            for (RelicPickup pickup : pickups) {
                if (pickup.placed || horizontalDistance(stand.position(), pickup.relic.standPos()) >= 1.5) continue;
                pickup.placed = true;
                if (witherKingTick < 0) continue;
                pickup.seconds = (serverTicks - witherKingTick) / 20f;
                if (pickup.player.equalsIgnoreCase(mc.getUser().getName())) {
                    pickup.personalBest = DungeonFeatures.newPersonalBest("M7", relicColour(pickup.relic) + " Relic", pickup.seconds);
                }
            }
        }
        if (pickups.size() < RELICS.length || !pickups.stream().allMatch(p -> p.placed)) return;
        pickups.sort(Comparator.comparingDouble(p -> p.seconds < 0 ? Float.MAX_VALUE : p.seconds));
        for (RelicPickup pickup : pickups) {
            MutableComponent line = Component.literal(relicColour(pickup.relic) + " Relic").withColor(pickup.relic.colour)
                .append(Component.literal(" placed by " + pickup.player).withStyle(ChatFormatting.GRAY));
            if (pickup.seconds >= 0) {
                line.append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format(Locale.ROOT, "%.2fs", pickup.seconds)).withStyle(ChatFormatting.YELLOW));
            }
            if (pickup.personalBest) line.append(Component.literal(" (PB)").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
            Sky2MAlerts.chat(line);
        }
        pickups.clear();
    }

    /** "Green" from "Corrupted Green Relic". */
    private static String relicColour(Relic relic) {
        return relic.name.substring("Corrupted ".length(), relic.name.length() - " Relic".length());
    }

    private static boolean isIceSpray(ArmorStand stand) {
        if (!stand.isMarker()) return false;
        ItemStack item = stand.getItemBySlot(EquipmentSlot.MAINHAND);
        return item.is(Items.PACKED_ICE) && item.getCount() == 1 && Compat.neuName(item).isEmpty();
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.WitherDragons config = config();
        if (config == null || !inDragonPhase()) return;
        Minecraft mc = Minecraft.getInstance();
        if ((config.relicHighlight || config.relicBeacon || config.relicTracer) && mc.player != null) {
            ItemStack stack = mc.player.getInventory().getItem(8);
            if (stack.is(Items.PLAYER_HEAD)) {
                String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
                for (Relic relic : RELICS) {
                    if (!relic.name.equals(name)) continue;
                    // Odin's King Relic beacon and NoammAddons' Box Relics tracer.
                    if (config.relicBeacon) collector.submitFilledBoxWithBeaconBeam(relic.pos, rgb(relic.colour), 0.5f, false);
                    else if (config.relicHighlight) collector.submitFilledBox(relic.pos, rgb(relic.colour), 0.5f, false);
                    if (config.relicTracer) collector.submitLineFromCursor(Vec3.atCenterOf(relic.pos), rgb(relic.colour), 1f, 2f);
                }
            }
        }
        for (Dragon dragon : DRAGONS) {
            float[] colour = rgb(dragon.colour);
            if (config.timer && dragon.isSpawning()) {
                float seconds = dragon.spawnTicks / 20f;
                collector.submitText(Component.literal(String.format(Locale.ROOT, "%.2fs", seconds)).withColor(percentColour(seconds / 5f)),
                    dragon.pos.getCenter().add(0, 4, 0), 3f, true);
            }
            if (config.boxes && (dragon.isSpawning() || dragon.hasEntity())) {
                collector.submitOutlinedBox(dragon.area, colour, 1f, 3f, true);
            }
            if (config.hitboxes && dragon.hasEntity()) {
                for (EnderDragonPart part : dragon.entity.getSubEntities()) {
                    collector.submitOutlinedBox(part.getBoundingBox(), colour, 1f, 2f, false);
                }
            }
            if (config.arrowStack && dragon.isSpawning() && mc.player != null) {
                Vec3 lead = arrowLead(mc.player.getEyePosition(), dragon.stackTarget());
                if (lead != null) {
                    double size = 0.6 * Math.max(0.5, Math.sqrt(mc.player.getEyePosition().distanceTo(lead) / 50.0));
                    collector.submitOutlinedBox(AABB.ofSize(lead, size, size, size), new float[]{0f, 1f, 1f}, 1f, 3f, true);
                }
            }
            if (dragon.isSpawning()) {
                switch (config.waypoints) {
                    case SIMPLE -> collector.submitFilledBox(dragon.pos, colour, 0.5f, false);
                    case ADVANCED -> {
                        for (AABB part : dragon.parts) collector.submitFilledBox(part, colour, 0.33f, false);
                    }
                    default -> {}
                }
            }
            if (config.health && dragon.hasEntity()) {
                double max = dragon.maxHealth > 0 ? dragon.maxHealth : 200;
                collector.submitText(Component.literal(String.format(Locale.ROOT, "%.2fM", dragon.health / 1_000_000.0)).withColor(percentColour((float) (dragon.health / max))),
                    dragon.entity.position(), 3f, true);
            }
        }
        if (config.tracers) {
            Dragon dragon = priority(config);
            if (dragon != null) collector.submitLineFromCursor(dragon.pos.getCenter(), rgb(dragon.colour), 1f, 2f);
        }
    }

    /**
     * NoammAddons' arrow stack lead: walks an arrow's flight (3 blocks a tick, slowing by 1% and falling 0.05 a tick)
     * until it has gone as far as the target, and aims that much higher. Null when it never gets there.
     */
    private static Vec3 arrowLead(Vec3 eye, Vec3 target) {
        double distanceSq = target.distanceToSqr(eye);
        double travelled = 0, speed = 3, yVelocity = 0, drop = 0;
        for (int tick = 0; tick < 160; tick++) {
            travelled += speed;
            speed *= 0.99;
            drop += yVelocity;
            yVelocity -= 0.05;
            yVelocity *= 0.99;
            if (travelled * travelled >= distanceSq) return target.subtract(0, drop, 0);
        }
        return null;
    }

    /** Arrow hit sound (Sky2MFloor7PacketsMixin): counts for your priority dragon in its skip window. */
    static void onArrowHit() {
        FeatureConfigs.WitherDragons config = config();
        if (config == null || !config.arrowsHit || !inDragonPhase()) return;
        for (Dragon dragon : DRAGONS) {
            if (dragon.wasPriority && dragon.hasEntity() && tickCounter - dragon.spawnedAt <= dragon.skipTicks) dragon.arrows++;
        }
    }

    /** Spawn Timer: your priority dragon's timer, big on screen (as in NoammAddons); move it in /s2 gui. */
    private static Component timerText() {
        FeatureConfigs.WitherDragons config = config();
        if (config == null || !config.timer || !inDragonPhase()) return null;
        Dragon dragon = priority(config);
        if (dragon == null) return null;
        return Component.literal(String.format(Locale.ROOT, "%.2fs", dragon.spawnTicks / 20f)).withColor(dragon.colour);
    }

    private static final Component TIMER_PREVIEW = Component.literal("4.25s").withColor(0xAA00AA);

    private static final class TimerHud implements Sky2MHuds.CustomHud {
        private static final float SCALE = 3f;

        private Component shown() {
            Component live = timerText();
            return live == null ? TIMER_PREVIEW : live;
        }

        @Override
        public int width() {
            return Math.round(Minecraft.getInstance().font.width(shown()) * SCALE);
        }

        @Override
        public int height() {
            return Math.round(Minecraft.getInstance().font.lineHeight * SCALE);
        }

        @Override
        public boolean visible() {
            return timerText() != null;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, boolean preview) {
            Component text = preview ? TIMER_PREVIEW : timerText();
            if (text == null) return;
            graphics.pose().pushMatrix();
            graphics.pose().scale(SCALE, SCALE);
            graphics.text(Minecraft.getInstance().font, text, 0, 0, 0xFFFFFFFF, true);
            graphics.pose().popMatrix();
        }
    }

    /** Green when full, through yellow, to red when empty. */
    private static int percentColour(float fraction) {
        return Mth.hsvToRgb(Mth.clamp(fraction, 0f, 1f) / 3f, 1f, 1f);
    }

    private static float[] rgb(int colour) {
        return new float[]{((colour >> 16) & 255) / 255f, ((colour >> 8) & 255) / 255f, (colour & 255) / 255f};
    }

    private record Relic(String name, BlockPos pos, int colour) {
        /** Where the relic's armour stand stands once placed (NoammAddons' cauldron coordinates). */
        Vec3 standPos() {
            return new Vec3(pos.getX() + 1, 0, pos.getZ() + 1);
        }
    }

    private static final class RelicPickup {
        final Relic relic;
        final String player;
        boolean placed;
        float seconds = -1;
        boolean personalBest;

        RelicPickup(Relic relic, String player) {
            this.relic = relic;
            this.player = player;
        }
    }

    /** Each dragon that's spawning now, by name, with the server ticks until it spawns (for the Rag Axe prompts). */
    public static java.util.Map<String, Integer> spawningTicks() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Dragon dragon : DRAGONS) if (dragon.isSpawning()) out.put(dragon.name, dragon.spawnTicks);
        return out;
    }

    /** A dragon that's spawning: where it will appear and the server ticks until it does (for the spawn arrows). */
    public record Spawning(String name, int colour, Vec3 pos, int ticks) {}

    public static List<Spawning> spawningDragons() {
        List<Spawning> out = new java.util.ArrayList<>();
        for (Dragon dragon : DRAGONS) if (dragon.isSpawning()) out.add(new Spawning(dragon.name, dragon.colour, dragon.pos.getCenter(), dragon.spawnTicks));
        return out;
    }

    /** A dragon's spawn or death this run, for the Cross-Mod Bridge: {@code count} is which one (1 for the first). */
    public record DragonEvent(String colour, boolean alive, int count) {}

    /** Every dragon spawn and death seen this run, lower-case colours. */
    public static List<DragonEvent> dragonEvents() {
        List<DragonEvent> out = new java.util.ArrayList<>();
        for (Dragon dragon : DRAGONS) {
            String colour = dragon.name.toLowerCase(java.util.Locale.ROOT);
            for (int i = 1; i <= dragon.spawns; i++) out.add(new DragonEvent(colour, true, i));
            for (int i = 1; i <= dragon.deaths; i++) out.add(new DragonEvent(colour, false, i));
        }
        return out;
    }

    private static final class Dragon {
        final String name;
        final String relicId;
        final int colour;
        final AABB pos;
        final List<AABB> parts;
        final AABB area;
        EnderDragon entity;
        float health;
        double maxHealth = 200;
        int spawnTicks;
        int spawnedAt;
        boolean iceSprayed;
        /** Times this dragon has spawned and died this run, for the Cross-Mod Bridge's events. */
        int spawns, deaths;
        /** NoammAddons' skip window (server ticks after spawning) and arrow stack spot (x, z). */
        int skipTicks;
        double stackX, stackZ;
        /** Whether it was your priority dragon when it spawned, and your arrows that hit it in its skip window. */
        boolean wasPriority;
        int arrows;

        Dragon stack(int skipTicks, double x, double z) {
            this.skipTicks = skipTicks;
            this.stackX = x;
            this.stackZ = z;
            return this;
        }

        /** Where to aim to stack arrows while it spawns (NoammAddons: the stack spot, 3.5 above the spawn height). */
        Vec3 stackTarget() {
            return new Vec3(stackX + 0.5, pos.getCenter().y + 3.5, stackZ + 0.5);
        }

        Dragon(String name, String relicId, int colour, AABB pos, List<AABB> parts, AABB area) {
            this.name = name;
            this.relicId = relicId;
            this.colour = colour;
            this.pos = pos;
            this.parts = parts;
            this.area = area;
        }

        boolean isSpawning() {
            return spawnTicks > 0;
        }

        boolean hasEntity() {
            return entity != null && entity.isAlive() && !entity.isRemoved();
        }

        void setEntity(EnderDragon dragon) {
            entity = dragon;
            health = dragon.getHealth();
            maxHealth = dragon.getAttributeBaseValue(Attributes.MAX_HEALTH);
            if (spawnedAt == 0) {
                spawnedAt = tickCounter;
                spawns++;
            }
        }

        void tick() {
            if (spawnTicks > 0) spawnTicks--;
            if (!hasEntity()) {
                if (entity != null) {
                    deaths++;
                    if (spawnedAt > 0) DragonSplits.onDragonDeath(name, colour, tickCounter - spawnedAt);
                    FeatureConfigs.WitherDragons config = config();
                    if (wasPriority && config != null && config.arrowsHit) {
                        Sky2MAlerts.chat(Component.literal(name).withColor(colour)
                            .append(Component.literal(": Arrows: ").withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(String.valueOf(arrows)).withStyle(ChatFormatting.GOLD)));
                    }
                    wasPriority = false;
                    arrows = 0;
                }
                entity = null;
                iceSprayed = false;
                spawnedAt = 0;
            }
        }

        void reset() {
            entity = null;
            iceSprayed = false;
            health = 0;
            maxHealth = 200;
            spawnTicks = 0;
            spawnedAt = 0;
            spawns = 0;
            deaths = 0;
            wasPriority = false;
            arrows = 0;
        }
    }
}
