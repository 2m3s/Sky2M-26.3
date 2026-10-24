// Ported from NoammAddons (https://github.com/Noamm9/NoammAddons, CC0 1.0): features/impl/floor7/MaxorsCrystals.kt,
// F7Titles.kt, GateHighlight.kt, TerminalESP.kt, LeapCounter.kt, M7Relics.kt (Relic Spawn Timer) and
// utils/dungeons/F7Listener.kt (Necron's death sound), and Odin (https://github.com/odtheking/Odin, BSD-3-Clause):
// features/impl/boss/TerminalSounds.kt.
package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.mixin.Sky2MBossOverlayAccessor;
import com.epic60869.sky2m.sb.events.ServerTickCallback;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Floor 7 > F7 Extras:
 * <ul>
 *     <li>Maxor's Crystals: the crystal respawn timer, how long you took to place yours (with a PB) and a reminder while
 *     you hold one.</li>
 *     <li>Boss titles: crystal progress, the withers being stunned, crushed, enraged and killed, and Storm's lightning
 *     timer in place of his countdown titles.</li>
 *     <li>P3: the gate of your section and its inactive terminals boxed, and the Leap Counter.</li>
 *     <li>M7: the relic spawn timer, started by Necron's death sound.</li>
 *     <li>Terminal click and complete sounds (Odin's Terminal Sounds).</li>
 * </ul>
 * F7 phases are read from your height in the boss room, like NoammAddons and Odin: P1 above y 210, P2 above 155, P3
 * above 100, P4 above 45, then P5.
 */
public final class F7Extras {
    /** The F7/M7 boss room (as in {@link WitherDragons}). */
    private static final AABB BOSS_ROOM = new AABB(-8, 0, -8, 134, 254, 147);
    /** NoammAddons' four P3 sections. */
    private static final AABB[] P3_SECTIONS = {
        new AABB(90, 105, 32, 111, 158, 123),
        new AABB(16, 105, 122, 111, 158, 143),
        new AABB(-3, 106, 48, 19, 158, 142),
        new AABB(-3, 106, 30, 91, 158, 50),
    };
    /** The gate at the end of P3 sections 1-3: a block that's cracked or infested stone bricks until it's destroyed, and its box. */
    private static final BlockPos[] GATE_BLOCKS = {new BlockPos(103, 134, 123), new BlockPos(17, 134, 135), new BlockPos(5, 134, 49)};
    private static final AABB[] GATE_BOXES = {
        new AABB(95, 114, 122, 106, 134, 124),
        new AABB(18, 114, 127, 19, 134, 138),
        new AABB(1, 114, 49, 14, 134, 51),
    };
    /** Where each section's terminals stand (NoammAddons' Terminal ESP), by section. */
    private static final Vec3[][] TERMINALS = {
        {new Vec3(110, 113, 73), new Vec3(110, 119, 79), new Vec3(90, 112, 92), new Vec3(90, 122, 101)},
        {new Vec3(68, 109, 122), new Vec3(59, 119, 123), new Vec3(47, 109, 122), new Vec3(39, 108, 142), new Vec3(40, 124, 123)},
        {new Vec3(-2, 109, 112), new Vec3(-2, 119, 93), new Vec3(18, 123, 93), new Vec3(-2, 109, 77)},
        {new Vec3(41, 109, 30), new Vec3(44, 121, 30), new Vec3(67, 109, 30), new Vec3(72, 114, 47)},
    };

    private static final int CRYSTAL_RESPAWN_TICKS = 34, RELIC_SPAWN_TICKS = 61;
    private static final Pattern CRYSTAL_PICKUP = Pattern.compile("^(\\w{1,16}) picked up an Energy Crystal!$");
    private static final Pattern CRYSTALS_ACTIVE = Pattern.compile("^(\\d)/(\\d) Energy Crystals are now active!$");
    private static final Pattern ENRAGED = Pattern.compile("^⚠ (\\w+) is enraged! ⚠$");

    private static long serverTicks;
    private static long crystalSpawnAt = -1, crystalPickupMs = -1;
    private static long lightningEndAt = -1;
    private static long relicSpawnAt = -1;
    private static long maxorEntryTick = -1;
    private static boolean necronStart, necronDeathHeard, coreOpened;
    private static boolean maxorDeadTitle, goldorDeadTitle, necronDeadTitle;
    /** Inactive terminal stands by entity id, with the section they're in. */
    private static final Map<Integer, Integer> inactiveTerminals = new HashMap<>();
    private static final Map<Integer, Long> terminalFlash = new HashMap<>();

    private F7Extras() {}

    private static FeatureConfigs.F7Extras config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.extras;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        ServerTickCallback.EVENT.register(() -> serverTicks++);
        ClientTickEvents.END_CLIENT_TICK.register(F7Extras::onClientTick);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MWorldRender.register(F7Extras::render);
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> onEntityLoad(entity));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) flashTerminal(entity);
            return InteractionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) flashTerminal(entity);
            return InteractionResult.PASS;
        });

        bigHud("crystal_spawn_timer", "Crystal Spawn Timer", () -> config() != null && config().crystalSpawnTimer,
            () -> crystalSpawnAt < 0 || serverTicks >= crystalSpawnAt ? null
                : Component.literal(seconds(crystalSpawnAt - serverTicks)).withStyle(ChatFormatting.AQUA),
            Component.literal("1.70").withStyle(ChatFormatting.AQUA), 2.5f, 440, 280);
        bigHud("crystal_place_alert", "Crystal Place Alert", () -> config() != null && config().crystalPlaceAlert,
            () -> phase() == 1 && holdingCrystal() ? crystalAlert() : null, crystalAlert(), 3f, 400, 110);
        bigHud("storm_lightning_timer", "Storm Lightning Timer", () -> config() != null && config().lightningTimer,
            () -> lightningEndAt < 0 || serverTicks >= lightningEndAt ? null
                : Component.literal(String.format(Locale.US, "%.1f", (lightningEndAt - serverTicks) / 20f)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
            Component.literal("5.4").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), 3f, 460, 230);
        bigHud("relic_spawn_timer", "Relic Spawn Timer", () -> config() != null && config().relicSpawnTimer,
            () -> relicSpawnAt < 0 || serverTicks >= relicSpawnAt ? null
                : Component.literal(seconds(relicSpawnAt - serverTicks)).withColor(classColour()),
            Component.literal("1.25").withColor(0x36C6E3), 1f, 460, 290);
        Sky2MHuds.setting("leap_counter", () -> config() != null && config().leapCounter);
        Sky2MHuds.register("leap_counter", "Leap Counter", () -> config() != null && config().leapCounter,
            F7Extras::leapLines, List.of(Component.literal("§42§9/4 Players Leaped")), 440, 300);
    }

    private static void reset() {
        crystalSpawnAt = crystalPickupMs = lightningEndAt = relicSpawnAt = maxorEntryTick = -1;
        necronStart = necronDeathHeard = coreOpened = false;
        maxorDeadTitle = goldorDeadTitle = necronDeadTitle = false;
        inactiveTerminals.clear();
        terminalFlash.clear();
        for (LeapSpot spot : LeapSpot.values()) spot.reset();
    }

    /** A big text HUD (drawn at {@code baseScale} times its /s2 gui scale); {@code text} null hides it. */
    private static void bigHud(String id, String name, BooleanSupplier setting, Supplier<Component> text, Component preview, float baseScale, int x, int y) {
        Sky2MHuds.setting(id, setting);
        Sky2MHuds.registerCustom(id, name, setting, new Sky2MHuds.CustomHud() {
            private Component shown(boolean preview2) {
                Component live = text.get();
                return live == null || preview2 ? preview : live;
            }

            @Override
            public int width() {
                return Math.round(Minecraft.getInstance().font.width(shown(false)) * baseScale);
            }

            @Override
            public int height() {
                return Math.round(Minecraft.getInstance().font.lineHeight * baseScale);
            }

            @Override
            public boolean visible() {
                return text.get() != null;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean isPreview) {
                Component line = isPreview ? shown(true) : text.get();
                if (line == null) return;
                graphics.pose().pushMatrix();
                graphics.pose().scale(baseScale, baseScale);
                graphics.text(Minecraft.getInstance().font, line, 0, 0, 0xFFFFFFFF, true);
                graphics.pose().popMatrix();
            }
        }, x, y);
    }

    private static String seconds(long ticks) {
        return String.format(Locale.US, "%.2f", ticks / 20f);
    }

    private static Component crystalAlert() {
        return Component.literal("⚠ ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
            .append(Component.literal("Crystal").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
            .append(Component.literal(" ⚠").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
    }

    private static int classColour() {
        DungeonClass c = SelfClass.get();
        return c == null || c == DungeonClass.UNKNOWN ? 0xFFFFFF : c.color();
    }

    // ----- Where you are -----

    static boolean inBoss() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && PositionalMessages.onFloor7() && BOSS_ROOM.contains(mc.player.position());
    }

    /** The F7 phase (1-5) you're in by height, or 0 outside the F7/M7 boss room. */
    static int phase() {
        if (!inBoss()) return 0;
        return phaseAt(Minecraft.getInstance().player.getY());
    }

    private static int phaseAt(double y) {
        return y > 210 ? 1 : y > 155 ? 2 : y > 100 ? 3 : y > 45 ? 4 : 5;
    }

    /** The P3 section (1-4) a position is in, or 0. */
    private static int section(Vec3 pos) {
        for (int i = 0; i < P3_SECTIONS.length; i++) if (P3_SECTIONS[i].contains(pos)) return i + 1;
        return 0;
    }

    private static boolean onMaster() {
        return Sky2MLocation.dungeonFloor().startsWith("M");
    }

    private static String selfName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : mc.player.getGameProfile().name();
    }

    private static boolean holdingCrystal() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        String name = ChatFormatting.stripFormatting(mc.player.getInventory().getItem(8).getHoverName().getString());
        return name != null && name.trim().equalsIgnoreCase("Energy Crystal");
    }

    // ----- Chat -----

    private static void onChat(String text) {
        if (!PositionalMessages.onFloor7()) return;
        FeatureConfigs.F7Extras c = config();
        switch (text) {
            case "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> maxorEntryTick = serverTicks;
            case "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!", "[BOSS] Maxor: YOU TRICKED ME!" -> {
                crystalSpawnAt = serverTicks + CRYSTAL_RESPAWN_TICKS;
                if (c != null && c.witherTitles) showTitle(Component.literal("Maxor Stunned!").withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            case "[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!" -> {
                if (c != null && c.witherTitles) showTitle(Component.literal("Storm Crushed!").withStyle(ChatFormatting.AQUA));
            }
            case "[BOSS] Storm: I should have known that I stood no chance." -> {
                lightningEndAt = -1;
                if (c != null && c.witherTitles) showTitle(Component.literal("Storm Dead!").withStyle(ChatFormatting.AQUA));
            }
            case "The Core entrance is opening!" -> coreOpened = true;
            case "[BOSS] Necron: ARGH!" -> necronStart = true;
            default -> {}
        }
        Matcher pickup = CRYSTAL_PICKUP.matcher(text);
        if (pickup.matches() && pickup.group(1).equalsIgnoreCase(selfName())) crystalPickupMs = System.currentTimeMillis();
        // Odin's Terminal Sounds: a sound when you finish a terminal.
        FeatureConfigs.Terminals terms = terminals();
        if (terms != null && terms.completeSound && text.startsWith(selfName() + " completed a terminal!")) {
            Sky2MAlerts.play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f);
        }
    }

    private static FeatureConfigs.Terminals terminals() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.f7.terminals;
    }

    /** NoammAddons' titles: a subtitle with a quiet pling. */
    private static void showTitle(Component subtitle) {
        Sky2MAlerts.title(Component.empty(), subtitle, false);
        Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 0.25f);
    }

    // ----- Packets (Sky2MFloor7PacketsMixin, on the render thread) -----

    /** A subtitle from the server; true hides it. */
    public static boolean onSubtitle(Component component) {
        if (!inBoss()) return false;
        String text = ChatFormatting.stripFormatting(component.getString());
        if (text == null || text.isBlank()) return false;
        text = text.trim();
        FeatureConfigs.F7Extras c = config();
        if (c != null && c.crystalTitles) {
            if (text.equals("The Energy Laser is charging up!")) return true;
            Matcher m = CRYSTALS_ACTIVE.matcher(text);
            if (m.matches()) {
                boolean all = m.group(1).equals(m.group(2));
                Sky2MAlerts.title(Component.empty(), Component.literal("Crystal ").withStyle(ChatFormatting.DARK_AQUA)
                    .append(Component.literal("(").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(m.group(1)).withStyle(all ? ChatFormatting.AQUA : ChatFormatting.RED))
                    .append(Component.literal("/").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(m.group(2)).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(")").withStyle(ChatFormatting.WHITE)), false);
                return true;
            }
        }
        if (c != null && c.witherTitles) {
            Matcher m = ENRAGED.matcher(text);
            if (m.matches()) {
                ChatFormatting colour = switch (m.group(1)) {
                    case "Storm" -> ChatFormatting.AQUA;
                    case "Maxor" -> ChatFormatting.DARK_PURPLE;
                    default -> ChatFormatting.WHITE;
                };
                showTitle(Component.literal(text).withStyle(colour));
                return true;
            }
        }
        return phase() == 3 && TerminalProgress.onSubtitle(text);
    }

    /** A title from the server; true hides it. Storm's lightning countdown is a title of just a number. */
    public static boolean onTitle(Component component) {
        FeatureConfigs.F7Extras c = config();
        if (c == null || !c.lightningTimer || phase() != 2) return false;
        String text = ChatFormatting.stripFormatting(component.getString());
        if (text == null || !text.trim().matches("\\d{1,2}")) return false;
        int number = Integer.parseInt(text.trim());
        // NoammAddons: the first number (4 or 6) gives how long the lightning lasts, 1.35 seconds per number.
        if ((lightningEndAt < 0 || serverTicks >= lightningEndAt) && (number == 4 || number == 6)) {
            lightningEndAt = serverTicks + Math.round(number * 1.35 * 20);
        }
        return true;
    }

    /** A sound from the server; true mutes it. */
    public static boolean onSound(String name, float volume, float pitch) {
        // Necron's death (NoammAddons' F7Listener): a loud wither hurt sound once his last phase has started.
        if (necronStart && !necronDeathHeard && name.equals("entity.wither.hurt") && volume == 15f && pitch == 1f) {
            necronDeathHeard = true;
            if (onMaster()) relicSpawnAt = serverTicks + RELIC_SPAWN_TICKS;
        }
        if (name.equals("entity.arrow.hit_player")) WitherDragons.onArrowHit();
        // Odin's Terminal Sounds: Hypixel's own click pling is muted when Sky2M plays its click sound.
        FeatureConfigs.Terminals terms = terminals();
        return terms != null && terms.clickSound && OdinTerminals.terminalOpen()
            && name.equals("block.note_block.pling") && volume == 8f && Math.abs(pitch - 4.047619f) < 0.01f;
    }

    /** A right click in a terminal (OdinTerminals), for the click sound. */
    static void onTerminalClick() {
        FeatureConfigs.Terminals terms = terminals();
        if (terms != null && terms.clickSound) Sky2MAlerts.play(SoundEvents.BLAZE_HURT, 1f, 0.5f);
    }

    // ----- Crystals -----

    private static void onEntityLoad(Entity entity) {
        FeatureConfigs.F7Extras c = config();
        if (c == null || !c.crystalPlaceTimer || crystalPickupMs < 0 || !(entity instanceof EndCrystal)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (int) entity.getY() != 224) return;
        double dx = entity.getX() - mc.player.getX(), dz = entity.getZ() - mc.player.getZ();
        if (Math.sqrt(dx * dx + dz * dz) >= 5) return;
        float seconds = (System.currentTimeMillis() - crystalPickupMs) / 1000f;
        crystalPickupMs = -1;
        boolean pb = DungeonFeatures.newPersonalBest("F7", "Crystal Placement", seconds);
        var line = Component.literal("Crystal placed in ").withStyle(ChatFormatting.GREEN)
            .append(Component.literal(String.format(Locale.US, "%.3fs", seconds)).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(".").withStyle(ChatFormatting.GREEN));
        if (pb) line.append(Component.literal(" (PB)").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        Sky2MAlerts.chat(line);
    }

    // ----- Client tick: boss bars, terminals, leaps -----

    private static void onClientTick(Minecraft mc) {
        FeatureConfigs.F7Extras c = config();
        if (c == null || mc.level == null || mc.player == null || !inBoss()) return;
        if (lightningEndAt >= 0 && serverTicks >= lightningEndAt) {
            lightningEndAt = -1;
            if (c.lightningTimer) showTitle(Component.literal("Storm's Lightning Ended!").withStyle(ChatFormatting.GREEN));
        }
        if (c.witherTitles) checkBossBars(mc);
        int phase = phase();
        if (c.terminalBoxes && phase == 3) scanTerminals(mc);
        if (c.leapCounter) countLeaps(mc, c);
    }

    /** NoammAddons: a wither has died when its boss bar empties. */
    private static void checkBossBars(Minecraft mc) {
        for (LerpingBossEvent bar : ((Sky2MBossOverlayAccessor) mc.gui.hud.getBossOverlay()).sky2m$getEvents().values()) {
            if (bar.getProgress() > 0f) continue;
            String name = bar.getName().getString();
            if (name.contains("Maxor") && !maxorDeadTitle && maxorEntryTick >= 0 && serverTicks - maxorEntryTick > 120) {
                maxorDeadTitle = true;
                showTitle(Component.literal("Maxor Dead!").withStyle(ChatFormatting.LIGHT_PURPLE));
            } else if (name.contains("Goldor") && !goldorDeadTitle && coreOpened) {
                goldorDeadTitle = true;
                showTitle(Component.literal("Goldor Dead!").withStyle(ChatFormatting.GRAY));
            } else if (name.contains("Necron") && !necronDeadTitle && necronStart) {
                necronDeadTitle = true;
                showTitle(Component.literal("Necron Dead!!").withStyle(ChatFormatting.RED));
            }
        }
    }

    private static void scanTerminals(Minecraft mc) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || stand.getCustomName() == null) continue;
            String name = ChatFormatting.stripFormatting(stand.getCustomName().getString());
            if ("Terminal Active".equals(name)) {
                inactiveTerminals.remove(stand.getId());
            } else if ("Inactive Terminal".equals(name) && !inactiveTerminals.containsKey(stand.getId())) {
                for (int s = 0; s < TERMINALS.length; s++) {
                    for (Vec3 pos : TERMINALS[s]) {
                        if (stand.distanceToSqr(pos) <= 1.5) inactiveTerminals.put(stand.getId(), s + 1);
                    }
                }
            }
        }
    }

    private static void flashTerminal(Entity entity) {
        if (entity instanceof ArmorStand && inactiveTerminals.containsKey(entity.getId())) terminalFlash.put(entity.getId(), System.currentTimeMillis());
    }

    // ----- Leap Counter (NoammAddons) -----

    /** Leap spots: the box you stand in, how many can leap to it at most, and where a leaped teammate lands. */
    private enum LeapSpot {
        SS(new AABB(106, 119, 92, 109, 121, 96), 3, p -> section(p) == 1),
        EE2(new AABB(57, 108, 130, 59, 110, 132), 4, p -> section(p) == 2),
        HIGH_EE2(new AABB(57, 132, 138, 62, 133, 140), 4, p -> section(p) == 2),
        EE3(new AABB(1, 108, 101, 3, 110, 107), 3, p -> section(p) == 3),
        CORE(new AABB(51, 114, 49, 58, 117, 54), 4, p -> section(p) == 4),
        IN_CORE(new AABB(51, 114, 55, 58, 117, 60), 4, p -> new AABB(42, 106, 54, 68, 155, 119).contains(p)),
        RELICS(new AABB(46.5, 3, 68.5, 62.5, 8, 84.5), 4, p -> phaseAt(p.y) == 5);

        final AABB box;
        final int max;
        final Predicate<Vec3> landed;
        final Set<Integer> leaped = new HashSet<>();
        long completedAt;

        LeapSpot(AABB box, int max, Predicate<Vec3> landed) {
            this.box = box;
            this.max = max;
            this.landed = landed;
        }

        void reset() {
            leaped.clear();
            completedAt = 0;
        }

        /** Done a second after everyone leaped, so the spot stops showing. */
        boolean completed() {
            return completedAt > 0 && System.currentTimeMillis() - completedAt > 1000;
        }
    }

    private static LeapSpot currentSpot;

    private static List<Player> teammates(Minecraft mc) {
        List<Player> out = new ArrayList<>();
        for (Player p : mc.level.players()) if (p != mc.player && !p.isSpectator()) out.add(p);
        return out;
    }

    private static int maxLeaps(LeapSpot spot, Minecraft mc) {
        return Math.min(spot.max, teammates(mc).size());
    }

    private static void countLeaps(Minecraft mc, FeatureConfigs.F7Extras c) {
        currentSpot = null;
        Vec3 self = mc.player.position();
        for (LeapSpot spot : LeapSpot.values()) {
            if (!spot.completed() && spot.box.contains(self)) {
                currentSpot = spot;
                break;
            }
        }
        if (currentSpot == null) return;
        int max = maxLeaps(currentSpot, mc);
        if (max <= 0) return;
        for (Player p : teammates(mc)) {
            if (currentSpot.leaped.contains(p.getId()) || !currentSpot.landed.test(p.position())) continue;
            currentSpot.leaped.add(p.getId());
            if (currentSpot.leaped.size() == max) {
                currentSpot.completedAt = System.currentTimeMillis();
                if (c.leapAlert) {
                    Sky2MAlerts.title(Component.empty(), Component.literal("Everyone Leaped!").withStyle(ChatFormatting.GREEN), false);
                    Sky2MAlerts.play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1f);
                }
            }
        }
    }

    private static List<Component> leapLines() {
        Minecraft mc = Minecraft.getInstance();
        LeapSpot spot = currentSpot;
        if (spot == null || mc.level == null || !inBoss()) return List.of();
        int max = maxLeaps(spot, mc);
        if (max <= 0) return List.of();
        int count = Math.min(spot.leaped.size(), max);
        String start = max - count <= 1 ? "§9" : "§4";
        return List.of(Component.literal(start + count + "§9/" + max + " Players Leaped"));
    }

    // ----- World render -----

    private static void render(PrimitiveCollector collector) {
        FeatureConfigs.F7Extras c = config();
        if (c == null || phase() != 3) return;
        Minecraft mc = Minecraft.getInstance();
        int section = section(mc.player.position());
        if (c.gateHighlight && section >= 1 && section <= 3) {
            BlockState gate = mc.level.getBlockState(GATE_BLOCKS[section - 1]);
            if (gate.is(Blocks.CRACKED_STONE_BRICKS) || gate.is(Blocks.INFESTED_STONE_BRICKS)) {
                int argb = argb(c.gateColour, 0xFFFF55FF);
                collector.submitOutlinedBox(GATE_BOXES[section - 1], rgb(argb), alpha(argb), 2.5f, c.throughWalls);
            }
        }
        if (c.terminalBoxes && section > 0) {
            int argb = argb(c.terminalColour, 0xFF55FFFF);
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, Integer> e : inactiveTerminals.entrySet()) {
                if (e.getValue() != section) continue;
                Entity stand = mc.level.getEntity(e.getKey());
                if (stand == null) continue;
                Long flashed = terminalFlash.get(e.getKey());
                boolean flash = c.terminalFlash && flashed != null && now - flashed < 250;
                float[] colour = flash ? new float[]{1f, 0f, 0f} : rgb(argb);
                collector.submitOutlinedBox(stand.getBoundingBox(), colour, alpha(argb), 2.5f, c.throughWalls);
            }
        }
    }

    private static int argb(String value, int fallback) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float[] rgb(int argb) {
        return new float[]{((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f};
    }

    private static float alpha(int argb) {
        float a = ((argb >>> 24) & 255) / 255f;
        return a <= 0 ? 1f : a;
    }
}
