package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.core.BindKeys;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SkyHanni's smaller hunting features (https://github.com/hannibal002/SkyHanni, features/hunting/ and
 * features/inventory/HuntrapMisclickPrevention.kt, LGPL-2.1): the Galatea mob highlights (Birries, Hideonleaf,
 * Hideonsun and Invisibugs, found by their crit particles), the Lasso Display, the Fusion Display, the Fusion Keybinds,
 * Prevent Huntrap Misclick and the Shulker Finder.
 */
public final class HuntingFeatures {
    private static final String LASSO_HUD = "hunting_lasso";
    private static final String FUSION_HUD = "hunting_fusion";

    /** Entity id -> glow colour, rebuilt twice a second. */
    private static Map<Integer, Integer> glow = Map.of();
    private static final Set<ArmorStand> invisibugs = new HashSet<>();
    private static List<Vec3> invisibugBoxes = List.of();
    private static List<Component> lasso = List.of();
    private static AttributeShards.Shard lastFused;
    private static int pureReptiles;
    private static boolean repeatWasDown, confirmWasDown, shulkerWasDown;
    private static int lastRepeatKey = -1, lastConfirmKey = -1;
    private static long lastMisclickNotice;
    private static List<Integer> shulkerRoute;
    private static int shulkerIndex;
    private static int ticks;
    private static Object lastLevel;

    private HuntingFeatures() {}

    static HuntingConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.hunting;
    }

    static boolean onGalatea() {
        return Sky2MLocation.areaIs("Galatea") || Sky2MLocation.areaIs("Moonglade Marsh");
    }

    static boolean onTorrhus() {
        return Sky2MLocation.areaIs("Torrhus Canyon");
    }

    public static void init() {
        IslandGraph.load("GALATEA");
        IslandGraph.load("TORRHUS_CANYON");
        ClientTickEvents.END_CLIENT_TICK.register(HuntingFeatures::tick);
        EntityGlow.register(entity -> glow.isEmpty() ? -1 : glow.getOrDefault(entity.getId(), -1));
        Sky2MWorldRender.register(collector -> {
            if (invisibugBoxes.isEmpty()) return;
            HuntingConfig c = config();
            int rgb = ChromaColours.parse(c.mobHighlight.invisibug.color).getEffectiveColourRGB();
            float[] colour = {(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
            for (Vec3 at : invisibugBoxes) {
                collector.submitFilledBox(new AABB(at.x - 0.4, at.y + 0.3, at.z - 0.4, at.x + 0.4, at.y + 1.1, at.z + 0.4), colour, 0.5f, true);
            }
        });
        Sky2MHuds.register(LASSO_HUD, "Lasso Display", () -> {
            HuntingConfig c = config();
            return c != null && c.lassoDisplay && Sky2MLocation.onSkyblock();
        }, () -> lasso, List.of(Component.literal("REEL").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)), 380, 210);
        Sky2MHuds.setSettingsSearch(LASSO_HUD, "Lasso Display");
        Sky2MHuds.setting(LASSO_HUD, () -> config() != null && config().lassoDisplay);
        Sky2MHuds.register(FUSION_HUD, "Fusion Display", () -> false, List::of,
            List.of(Component.literal("Invisibug Shard").withStyle(ChatFormatting.BLUE).append(Component.literal(": 42").withStyle(ChatFormatting.GRAY))), 30, 210);
        Sky2MHuds.setSettingsSearch(FUSION_HUD, "Fusion Display");
        Sky2MHuds.setting(FUSION_HUD, () -> config() != null && config().fusionDisplay);
        ScreenHud.register(FUSION_HUD, HuntingFeatures::fusionLines, null);
        AttributeShards.onShard((shard, amount, source, gain) -> {
            if (source != AttributeShards.Source.FUSE || amount < 0) return;
            if (lastFused != shard) pureReptiles = 0;
            lastFused = shard;
        });
        Sky2MChat.onChat(message -> {
            if (message.text().trim().startsWith("PURE REPTILE")) pureReptiles++;
        });
    }

    // ------------------------------------------------------------------------------------------------ ticking

    private static void tick(Minecraft mc) {
        HuntingConfig c = config();
        if (c == null || mc.player == null || mc.level == null) {
            glow = Map.of();
            invisibugs.clear();
            invisibugBoxes = List.of();
            shulkerRoute = null;
            return;
        }
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            onWorldChange();
            SafariFeatures.onWorldChange();
        }
        ticks++;
        fusionKeybinds(mc, c.fusionKeybinds);
        shulkerKey(mc, c);
        if (ticks % 5 != 0) return;
        if (!Sky2MLocation.onSkyblock()) {
            glow = Map.of();
            lasso = List.of();
            invisibugBoxes = List.of();
            return;
        }
        if (ticks % 10 == 0) highlights(mc, c.mobHighlight);
        invisibugs(mc, c.mobHighlight.invisibug);
        lasso = c.lassoDisplay ? lasso(mc) : List.of();
    }

    // ------------------------------------------------------------------------------------------------ highlights

    private static void highlights(Minecraft mc, HuntingConfig.MobHighlights c) {
        boolean galatea = onGalatea(), torrhus = onTorrhus();
        if (!galatea && !torrhus) {
            glow = Map.of();
            return;
        }
        Map<Integer, Integer> out = new HashMap<>();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            HuntingConfig.Highlight highlight;
            double range;
            if (galatea && name.contains("Birries")) {
                highlight = c.birries;
                range = 10;
            } else if (galatea && name.contains("Hideonleaf")) {
                highlight = c.hideonleaf;
                range = 20;
            } else if (torrhus && name.contains("Hideonsun")) {
                highlight = c.hideonsun;
                range = 20;
            } else {
                continue;
            }
            if (!highlight.enabled) continue;
            LivingEntity mob = mobUnder(mc, stand, name.contains("Hideon"));
            if (mob == null || mob.distanceTo(mc.player) >= range) continue;
            out.put(mob.getId(), ChromaColours.parse(highlight.color).getEffectiveColourRGB() & 0xFFFFFF);
        }
        glow = out.isEmpty() ? Map.of() : out;
    }

    /** The mob a nametag stand belongs to: the nearest living entity just below it. */
    private static LivingEntity mobUnder(Minecraft mc, ArmorStand stand, boolean shulker) {
        AABB box = stand.getBoundingBox().inflate(1.5, 0, 1.5).expandTowards(0, -3.5, 0);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof ArmorStand) && !(e instanceof Player))) {
            if (shulker && !(e instanceof Shulker)) continue;
            double d = e.distanceToSqr(stand);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** Hypixel's crit particles around an Invisibug, from Sky2MSlayerPacketsMixin. */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        HuntingConfig c = config();
        if (c == null || !c.mobHighlight.invisibug.enabled || packet.particle().getType() != ParticleTypes.CRIT || !onGalatea()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 at = new Vec3(packet.x(), packet.y(), packet.z());
        for (ArmorStand known : invisibugs) if (known.position().distanceTo(at) < 5) return;
        ArmorStand nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(at, at).inflate(5))) {
            if (!completelyDefault(stand)) continue;
            double d = stand.position().distanceToSqr(at);
            if (d < bestDist) {
                bestDist = d;
                nearest = stand;
            }
        }
        if (nearest != null) invisibugs.add(nearest);
    }

    private static boolean completelyDefault(ArmorStand stand) {
        if (stand.hasCustomName()) return false;
        for (EquipmentSlot slot : EquipmentSlot.values()) if (!stand.getItemBySlot(slot).isEmpty()) return false;
        return true;
    }

    private static void invisibugs(Minecraft mc, HuntingConfig.Highlight c) {
        invisibugs.removeIf(stand -> stand.isRemoved() || stand.level() != mc.level);
        if (!c.enabled || invisibugs.isEmpty()) {
            invisibugBoxes = List.of();
            return;
        }
        List<Vec3> boxes = new ArrayList<>();
        for (ArmorStand stand : invisibugs) {
            if (stand.distanceTo(mc.player) <= 32 && mc.player.hasLineOfSight(stand)) boxes.add(stand.position());
        }
        invisibugBoxes = boxes;
    }

    // ------------------------------------------------------------------------------------------------ lasso

    private static List<Component> lasso(Minecraft mc) {
        if (!HuntingProfitTracker.isHuntingTool(mc.player.getMainHandItem())
            || !AttributeShards.lore(mc.player.getMainHandItem()).stream().anyMatch(l -> l.endsWith(" LASSO"))) return List.of();
        Component progress = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Leashable leashable) || leashable.getLeashHolder() != mc.player) continue;
            Vec3 above = Vec3.atLowerCornerOf(entity.blockPosition().above(2));
            for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, new AABB(above, above).inflate(2))) {
                Component name = stand.getCustomName();
                if (name == null) continue;
                if (Sky2MLocation.strip(name.getString()).trim().equals("REEL")) {
                    return List.of(Component.literal("          REEL          ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
                }
                for (Component part : name.toFlatList()) {
                    if (part.getStyle().isStrikethrough() && part.getStyle().isBold()) {
                        progress = name;
                        break;
                    }
                }
            }
        }
        return progress == null ? List.of() : List.of(progress);
    }

    // ------------------------------------------------------------------------------------------------ fusion

    private static List<Component> fusionLines() {
        HuntingConfig c = config();
        if (c == null || !c.fusionDisplay || lastFused == null || !AttributeShards.inFusionMachine()) return List.of();
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(lastFused.rarityColour() + lastFused.displayName() + " Shard")
            .append(Component.literal(": " + AttributeShards.amountInBox(lastFused)).withStyle(ChatFormatting.GRAY)));
        if (pureReptiles > 0) {
            out.add(Component.literal("PURE REPTILE").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                .append(Component.literal(": ").withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false))).append(Component.literal(String.valueOf(pureReptiles)).withStyle(ChatFormatting.YELLOW)));
        }
        return out;
    }

    private static void fusionKeybinds(Minecraft mc, HuntingConfig.FusionKeybinds c) {
        // The two keys must differ: a key changed to the other one's is cleared.
        if (c.repeatFusionKeybind != lastRepeatKey || c.confirmFusionKeybind != lastConfirmKey) {
            if (BindKeys.isSet(c.repeatFusionKeybind) && c.repeatFusionKeybind == c.confirmFusionKeybind) {
                if (c.repeatFusionKeybind != lastRepeatKey) c.repeatFusionKeybind = -1;
                else c.confirmFusionKeybind = -1;
                Sky2MAlerts.chat(Component.literal("Repeat Fusion Keybind ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("and ").withStyle(ChatFormatting.YELLOW)).append(Component.literal("Confirm Fusion Keybind ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("cannot be the same!").withStyle(ChatFormatting.YELLOW)));
            }
            lastRepeatKey = c.repeatFusionKeybind;
            lastConfirmKey = c.confirmFusionKeybind;
        }
        boolean repeat = BindKeys.down(c.repeatFusionKeybind), confirm = BindKeys.down(c.confirmFusionKeybind);
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && Sky2MLocation.onSkyblock()) {
            String title = Sky2MLocation.strip(screen.getTitle().getString());
            if (repeat && !repeatWasDown && !confirm && AttributeShards.FUSION_BOX.matcher(title).find()) middleClick(mc, screen, 47);
            if (confirm && !confirmWasDown && !repeat && AttributeShards.CONFIRM_FUSION.matcher(title).find()) middleClick(mc, screen, 33);
        }
        repeatWasDown = repeat;
        confirmWasDown = confirm;
    }

    private static void middleClick(Minecraft mc, AbstractContainerScreen<?> screen, int slot) {
        if (mc.gameMode == null || slot >= screen.getMenu().slots.size()) return;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 2, ContainerInput.CLONE, mc.player);
    }

    // ------------------------------------------------------------------------------------------------ huntrap

    /** Prevent Huntrap Misclick, from ContainerSolverScreenMixin: true cancels the click. */
    public static boolean onSlotClicked(AbstractContainerScreen<?> screen, Slot slot) {
        HuntingConfig c = config();
        if (c == null || !c.huntrapMisclick || slot == null || !slot.hasItem()) return false;
        if (!Sky2MLocation.strip(screen.getTitle().getString()).startsWith("Hunting Toolkit")) return false;
        if (AttributeShards.lore(slot.getItem()).stream().noneMatch(line -> line.contains("Status: EMPTY"))) return false;
        long now = System.currentTimeMillis();
        if (now - lastMisclickNotice > 10_000L) {
            lastMisclickNotice = now;
            Sky2MAlerts.chat(Component.literal("Prevented clicking an empty trap in Hunting Toolkit! ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("(turn off in /s2 → Hunting)").withStyle(ChatFormatting.GRAY)));
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ shulker finder

    private static void shulkerKey(Minecraft mc, HuntingConfig c) {
        boolean down = BindKeys.down(c.nextShulkerKeybind);
        boolean pressed = down && !shulkerWasDown;
        shulkerWasDown = down;
        if (!pressed || mc.gui.screen() != null || IslandGraph.navigating() || !c.shulkerFinder) return;
        boolean galatea = onGalatea();
        if (!galatea && !onTorrhus()) return;
        IslandGraph graph = IslandGraph.get(galatea ? "GALATEA" : "TORRHUS_CANYON");
        if (graph == null) return;
        if (shulkerRoute == null) {
            List<Integer> spots = graph.withTag(galatea ? "hideonleaf" : "hideonsun");
            if (spots.isEmpty()) return;
            shulkerRoute = graph.route(spots, graph.nearest(mc.player.position()));
            shulkerIndex = 0;
        }
        nextShulker(graph, galatea);
    }

    private static void nextShulker(IslandGraph graph, boolean galatea) {
        int goal = shulkerRoute.get(shulkerIndex++ % shulkerRoute.size());
        MutableComponent label = galatea ? Component.literal("nearest Hideonleaf").withStyle(ChatFormatting.DARK_GREEN)
            : Component.literal("nearest Hideonsun").withStyle(ChatFormatting.YELLOW);
        IslandGraph.navigate(graph, goal, label.getString(), galatea ? 0x00AA00 : 0xFFFF55, () -> {
            HuntingConfig c = config();
            return c != null && c.shulkerFinder && (galatea ? onGalatea() : onTorrhus());
        }, () -> {
            Minecraft mc = Minecraft.getInstance();
            Vec3 at = graph.pos[goal];
            if (mc.level != null && mc.level.getEntitiesOfClass(Shulker.class, new AABB(at, at).inflate(3)).isEmpty()) nextShulker(graph, galatea);
        });
    }

    /** A new world: routes and Invisibugs are for one lobby. */
    private static void onWorldChange() {
        shulkerRoute = null;
        invisibugs.clear();
        IslandGraph.stopNavigation();
    }
}
