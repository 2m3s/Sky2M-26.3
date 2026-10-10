package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.sbc.Flags;
import com.epic60869.sky2m.features.sbc.SbcConfig;
import com.epic60869.sky2m.features.sbc.SbcCrashReports;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Item ability cooldowns on the item's slot (a durability-style bar and/or a shade from the top) and optionally a HUD.
 * The cooldown is read from the item's lore ("Cooldown: 5s" under its ability) and starts when you use the ability:
 * right-clicking with it (abilities that cost mana start when the action bar shows "-50 Mana (Ability Name)", so a
 * failed use doesn't start it) and "This ability is on cooldown for Xs" corrects it. Items whose ability isn't a
 * right-click (masks, the grappling hook) are in {@link #SPECIAL}. Display only: nothing is used for you.
 */
public final class ItemCooldowns {
    private record Ability(String name, long cooldownMs, boolean sneak, int manaCost) {}

    private record Cooldown(String id, String name, long start, long duration) {
        float remaining(long now) {
            return Math.clamp(1f - (now - start) / (float) duration, 0f, 1f);
        }
    }

    /** A cooldown that starts from a chat message, for items the lore can't describe. */
    private record Special(Pattern message, List<String> ids, long defaultMs) {}

    private static final Pattern ABILITY = Pattern.compile("Ability: (.+?)\\s+(SNEAK )?(RIGHT|LEFT) CLICK");
    private static final Pattern MANA_COST = Pattern.compile("Mana Cost: ([\\d,]+)");
    private static final Pattern COOLDOWN = Pattern.compile("Cooldown: (?:(\\d+)m\\s*)?(\\d+(?:\\.\\d+)?)?s?");
    private static final Pattern USED = Pattern.compile("-[\\d,]+ Mana \\(([^)]+)\\)");
    private static final Pattern ON_COOLDOWN = Pattern.compile("This (?:ability|item) is on cooldown for (\\d+(?:\\.\\d+)?)s");
    private static final long GRAPPLE_MS = 2_000L;
    private static final List<Special> SPECIAL = List.of(
        new Special(Pattern.compile("^Your (?:⚚ )?Bonzo's Mask saved your life!"), List.of("BONZO_MASK", "STARRED_BONZO_MASK"), 360_000L),
        new Special(Pattern.compile("^Second Wind Activated! Your Spirit Mask saved your life!"), List.of("SPIRIT_MASK", "STARRED_SPIRIT_MASK"), 30_000L));

    private static final Map<String, Cooldown> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, Cooldown> DEPLOYED = new ConcurrentHashMap<>();
    private static final Map<String, Cooldown> DEPLOYABLES = Map.of(
        "RADIANT_POWER_ORB", new Cooldown("RADIANT_POWER_ORB", "Radiant Orb", 0, 30_000L),
        "MANA_FLUX_POWER_ORB", new Cooldown("MANA_FLUX_POWER_ORB", "Mana Flux", 0, 30_000L),
        "OVERFLUX_POWER_ORB", new Cooldown("OVERFLUX_POWER_ORB", "Overflux", 0, 60_000L),
        "PLASMAFLUX_POWER_ORB", new Cooldown("PLASMAFLUX_POWER_ORB", "Plasmaflux", 0, 60_000L),
        "WARNING_FLARE", new Cooldown("WARNING_FLARE", "Warning Flare", 0, 180_000L),
        "ALERT_FLARE", new Cooldown("ALERT_FLARE", "Alert Flare", 0, 180_000L),
        "SOS_FLARE", new Cooldown("SOS_FLARE", "SOS Flare", 0, 180_000L));
    /** A power orb's name tag, "Overflux 57s": the time it has left. */
    private static final Pattern ORB_TIME = Pattern.compile("^(Radiant|Mana Flux|Overflux|Plasmaflux) (\\d+)s$");
    /** Where you were when you deployed each one, to find its name tag. */
    private static final Map<String, net.minecraft.world.phys.Vec3> DEPLOYED_AT = new ConcurrentHashMap<>();
    /** A mana ability that was clicked, waiting for the action bar to confirm it was used. */
    private static String pendingId;
    private static Ability pendingAbility;
    private static long pendingAt;

    private ItemCooldowns() {}

    private static SbcConfig.ItemCooldowns config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? new SbcConfig.ItemCooldowns() : c.misc.itemCooldowns;
    }

    private static boolean enabled() {
        return config().enabled && Flags.isEnabled("itemCooldowns") && Compat.isOnSkyblock();
    }

    public static void init() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) onUse(player.getItemInHand(hand), player.fishing != null);
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) onUse(player.getItemInHand(hand), false);
            return InteractionResult.PASS;
        });
        Sky2MChat.onActionBar(message -> {
            if (pendingId == null || System.currentTimeMillis() - pendingAt > 1_000L) return;
            Matcher m = USED.matcher(message.text());
            if (m.find() && m.group(1).trim().equalsIgnoreCase(pendingAbility.name())) {
                String id = pendingId;
                start(id, pendingAbility.name(), pendingAbility.cooldownMs());
                startDeployable(id);
                pendingId = null;
            }
        });
        Sky2MChat.onChat(message -> {
            if (!enabled()) return;
            String text = message.text().trim();
            Matcher m = ON_COOLDOWN.matcher(text);
            if (m.find()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;
                String id = Compat.neuName(mc.player.getMainHandItem());
                Cooldown c = ACTIVE.get(id);
                long left = Math.round(Double.parseDouble(m.group(1)) * 1000);
                long duration = Math.max(c != null ? c.duration() : 1000, left);
                ACTIVE.put(id, new Cooldown(id, c != null ? c.name() : "", System.currentTimeMillis() - (duration - left), duration));
                return;
            }
            for (Special special : SPECIAL) {
                if (!special.message().matcher(text).find()) continue;
                Minecraft mc = Minecraft.getInstance();
                ItemStack helmet = mc.player == null ? ItemStack.EMPTY : mc.player.getItemBySlot(EquipmentSlot.HEAD);
                String id = Compat.neuName(helmet);
                if (!special.ids().contains(id)) id = special.ids().getFirst();
                long duration = special.defaultMs();
                for (Ability a : abilities(helmet)) if (a.cooldownMs() > 0) duration = a.cooldownMs();
                start(id, "Mask", duration);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            if (!ACTIVE.isEmpty()) {
                ACTIVE.values().removeIf(c -> {
                    if (now - c.start() < c.duration()) return false;
                    if (config().readySound && mc.player != null) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 2f, 0.6f);
                    return true;
                });
            }
            DEPLOYED.values().removeIf(c -> now - c.start() >= c.duration());
            if (config().deployableHud) DeployableScanner.tick(mc);
            if (!DEPLOYED.isEmpty() && mc.level != null && mc.player != null && mc.player.tickCount % 10 == 0) syncOrbTimes(mc, now);
        });
        Sky2MHuds.register("itemCooldowns", "Item Cooldowns", () -> config().enabled && config().hud, ItemCooldowns::hudLines,
            List.of(Component.literal("Instant Transmission: ").withStyle(ChatFormatting.GOLD).append(Component.literal("1.4s").withStyle(ChatFormatting.WHITE))), 8, 200);
        Sky2MHuds.register("deployable_timers", "Deployable Timers", () -> config().deployableHud,
            ItemCooldowns::deployableLines,
            List.of(Component.literal("Overflux: ").withStyle(ChatFormatting.GOLD).append(Component.literal("42.0s").withStyle(ChatFormatting.WHITE))), 8, 120);
    }

    private static void onUse(ItemStack stack, boolean hookOut) {
        try {
            if (stack.isEmpty() || !Compat.isOnSkyblock()) return;
            String id = Compat.neuName(stack);
            // Power orbs and flares have no cooldown in their lore: they only start the deployable timer, which works
            // with or without item cooldowns on.
            if (DEPLOYABLES.containsKey(id)) {
                if (config().deployableHud) startDeployable(id);
                return;
            }
            if (!enabled()) return;
            if (id.isEmpty() || ACTIVE.containsKey(id)) return;
            // The grappling hook cools down after it pulls you (the second click).
            if (id.equals("GRAPPLING_HOOK")) {
                if (hookOut) start(id, "Grapple", GRAPPLE_MS);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            boolean sneaking = mc.player != null && mc.player.isShiftKeyDown();
            Ability chosen = null;
            for (Ability a : abilities(stack)) {
                if (a.cooldownMs() <= 0) continue;
                if (a.sneak() == sneaking) {
                    chosen = a;
                    break;
                }
                if (chosen == null && !a.sneak()) chosen = a;
            }
            if (chosen == null) return;
            if (chosen.manaCost() > 0) {
                pendingId = id;
                pendingAbility = chosen;
                pendingAt = System.currentTimeMillis();
            } else {
                start(id, chosen.name(), chosen.cooldownMs());
                startDeployable(id);
            }
        } catch (Exception e) {
            SbcCrashReports.report(e, "item cooldowns");
        }
    }

    private static void start(String id, String name, long duration) {
        if (duration <= 0) return;
        ACTIVE.put(id, new Cooldown(id, name, System.currentTimeMillis(), duration));
    }

    private static void startDeployable(String id) {
        Cooldown template = DEPLOYABLES.get(id);
        if (template == null) return;
        // Only one of your deployables is out at a time: placing a flare removes your power orb and the other way round.
        DEPLOYED.clear();
        DEPLOYED_AT.clear();
        long duration = template.duration() * (holdingBubblegum() ? 2 : 1);
        DEPLOYED.put(id, new Cooldown(id, template.name(), System.currentTimeMillis(), duration));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) DEPLOYED_AT.put(id, mc.player.position());
    }

    /** Bubblegum on the summoned pet doubles how long deployables placed with it last. */
    private static boolean holdingBubblegum() {
        String pet = com.epic60869.sky2m.Sky2MNopoFeatures.currentPet();
        if (pet.isEmpty()) return false;
        String held = com.epic60869.sky2m.PetHeldItems.heldItemId(pet);
        return held != null && held.contains("BUBBLEGUM");
    }

    /** Power orbs show their time left on their name tag; use it, so the timer is right even if the orb was cut short. */
    private static void syncOrbTimes(Minecraft mc, long now) {
        for (var stand : mc.level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ArmorStand.class,
                mc.player.getBoundingBox().inflate(40), e -> e.hasCustomName())) {
            Matcher m = ORB_TIME.matcher(ChatFormatting.stripFormatting(stand.getCustomName().getString()).trim());
            if (!m.matches()) continue;
            for (Cooldown c : DEPLOYED.values()) {
                if (!c.name().equals(m.group(1).equals("Radiant") ? "Radiant Orb" : m.group(1))) continue;
                net.minecraft.world.phys.Vec3 at = DEPLOYED_AT.get(c.id());
                // The orb floats above where you stood when you placed it.
                if (at == null || Math.abs(stand.getX() - at.x) > 3 || Math.abs(stand.getZ() - at.z) > 3) continue;
                long left = Long.parseLong(m.group(2)) * 1000;
                DEPLOYED.put(c.id(), new Cooldown(c.id(), c.name(), now + left - c.duration(), c.duration()));
            }
        }
    }

    /** Right-click abilities with their cooldown and mana cost, from the lore. */
    private static List<Ability> abilities(ItemStack stack) {
        List<Ability> out = new ArrayList<>();
        ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        String name = null;
        boolean sneak = false;
        boolean right = false;
        int mana = 0;
        long cooldown = 0;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text == null) continue;
            Matcher a = ABILITY.matcher(text);
            if (a.find()) {
                if (name != null && right) out.add(new Ability(name, cooldown, sneak, mana));
                name = a.group(1).trim();
                sneak = a.group(2) != null;
                right = "RIGHT".equals(a.group(3));
                mana = 0;
                cooldown = 0;
                continue;
            }
            if (name == null) continue;
            Matcher m = MANA_COST.matcher(text);
            if (m.find()) mana = Integer.parseInt(m.group(1).replace(",", ""));
            Matcher c = COOLDOWN.matcher(text);
            if (c.find() && (c.group(1) != null || c.group(2) != null)) {
                double seconds = (c.group(1) == null ? 0 : Integer.parseInt(c.group(1)) * 60) + (c.group(2) == null ? 0 : Double.parseDouble(c.group(2)));
                cooldown = Math.round(seconds * 1000);
            }
        }
        if (name != null && right) out.add(new Ability(name, cooldown, sneak, mana));
        return out;
    }

    /** Drawn over every item slot (Sky2MItemCooldownMixin). */
    public static void drawOverlay(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        if (ACTIVE.isEmpty() || stack.isEmpty()) return;
        Cooldown c = ACTIVE.get(Compat.neuName(stack));
        if (c == null) return;
        SbcConfig.ItemCooldowns config = config();
        if (!config.enabled) return;
        float left = c.remaining(System.currentTimeMillis());
        if (left <= 0) return;
        if (config.overlay) {
            int h = Math.round(16 * left);
            g.fill(x, y + 16 - h, x + 16, y + 16, 0x7FFFFFFF);
        }
        if (config.bar) {
            g.fill(x + 2, y + 13, x + 15, y + 15, 0xFF000000);
            int w = Math.round(13 * (1 - left));
            int colour = net.minecraft.util.Mth.hsvToRgb((1 - left) / 3f, 1f, 1f);
            g.fill(x + 2, y + 13, x + 2 + w, y + 14, 0xFF000000 | colour);
        }
    }

    private static List<Component> hudLines() {
        if (ACTIVE.isEmpty()) return List.of();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        Map<String, Cooldown> sorted = new HashMap<>(ACTIVE);
        sorted.values().stream().sorted((a, b) -> Long.compare(a.start() + a.duration(), b.start() + b.duration())).forEach(c -> {
            double left = Math.max(0, c.start() + c.duration() - now) / 1000.0;
            String name = c.name().isBlank() ? c.id().replace('_', ' ').toLowerCase(Locale.ROOT) : c.name();
            lines.add(Component.literal(name + ": ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ENGLISH, "%.1fs", left)).withStyle(ChatFormatting.WHITE)));
        });
        return lines;
    }

    private static List<Component> deployableLines() {
        // What's around you (SkyblockAddons' way): anyone's orb or flare that buffs you, with its real time left.
        List<Component> scanned = DeployableScanner.lines();
        if (!scanned.isEmpty()) return scanned;
        if (DEPLOYED.isEmpty()) return List.of();
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        DEPLOYED.values().stream()
            .sorted((a, b) -> Long.compare(a.start() + a.duration(), b.start() + b.duration()))
            .forEach(c -> {
                double left = Math.max(0, c.start() + c.duration() - now) / 1000.0;
                lines.add(Component.literal(c.name() + ": ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format(Locale.ENGLISH, "%.1fs", left)).withStyle(ChatFormatting.WHITE)));
            });
        return lines;
    }
}
