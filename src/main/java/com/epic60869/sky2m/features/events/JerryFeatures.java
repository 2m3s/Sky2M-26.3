package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jerry features: highlight (and draw a line to) the Jerries from the Jerrypocalypse perk, and the Unique Gift Counter.
 * Ported from SkyHanni's HighlightJerries and UniqueGiftCounter
 * (https://github.com/hannibal002/SkyHanni, features/event/jerry/ and features/gifting/, LGPL-2.1).
 */
public final class JerryFeatures {
    private static final String GIFT_HUD = "events_winter_unique_gifts";
    private static final String GIFT_KEY = "uniqueGiftsGifted";
    private static final int MAX_GIFTS = 600;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final double SCAN_RANGE = 48;
    private static final Pattern JERRY = Pattern.compile("(Green|Blue|Purple|Golden) Jerry");
    private static final Pattern GIFTED = Pattern.compile("^\\+1 Unique Gift given! To .+!$");
    private static final Pattern GIFTED_AMOUNT = Pattern.compile("Unique Players Gifted: ([\\d,]+)");

    private static final Map<Integer, Integer> jerries = new HashMap<>();
    private static List<LivingEntity> jerryEntities = List.of();
    private static int ticks;
    private static int menuFrames;

    private JerryFeatures() {}

    private static EventsConfig.Winter config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.winter;
    }

    private static boolean jerriesActive() {
        EventsConfig.Winter c = config();
        return c != null && (c.highlightJerries || c.lineJerries) && Sky2MLocation.onSkyblock();
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(JerryFeatures::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            jerries.clear();
            jerryEntities = List.of();
        });
        EntityGlow.register(entity -> {
            EventsConfig.Winter c = config();
            if (c == null || !c.highlightJerries || jerries.isEmpty()) return -1;
            Integer colour = jerries.get(entity.getId());
            return colour == null ? -1 : colour;
        });
        Sky2MWorldRender.register(collector -> {
            EventsConfig.Winter c = config();
            if (c == null || !c.lineJerries || !jerriesActive()) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            for (LivingEntity jerry : jerryEntities) {
                Integer colour = jerries.get(jerry.getId());
                if (colour == null || !jerry.isAlive()) continue;
                float[] rgb = {(colour >> 16 & 255) / 255f, (colour >> 8 & 255) / 255f, (colour & 255) / 255f};
                collector.submitLineFromCursor(jerry.getPosition(partial).add(0, jerry.getBbHeight() / 2.0, 0), rgb, 1f, 2f);
            }
        });

        Sky2MChat.onChat(message -> {
            EventsConfig.Winter c = config();
            if (c != null && c.uniqueGiftCounter && GIFTED.matcher(message.text().trim()).matches()) {
                EventsFeatures.put(GIFT_KEY, EventsFeatures.number(GIFT_KEY, 0) + 1);
                EventsFeatures.save();
            }
        });
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container
                && Sky2MLocation.strip(container.getTitle().getString()).trim().equals("Generow")) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                    if (++menuFrames % 10 == 1) readGenerow(container);
                });
            }
        });
        Sky2MHuds.register(GIFT_HUD, "Unique Gift Counter", () -> {
            EventsConfig.Winter c = config();
            return c != null && c.uniqueGiftCounter && Sky2MLocation.onSkyblock()
                && LocalDate.now().getMonth() == Month.DECEMBER && holdingGift();
        }, JerryFeatures::giftLines, List.of(
            Component.literal("Unique Players Gifted: ").withStyle(ChatFormatting.GRAY).append(Component.literal("120/600").withStyle(ChatFormatting.YELLOW))), 10, 220);
        Sky2MHuds.setSettingsSearch(GIFT_HUD, "Unique Gift Counter");
        Sky2MHuds.setting(GIFT_HUD, () -> config() != null && config().uniqueGiftCounter);
    }

    // ------------------------------------------------------------------------------------------------ unique gifts

    private static boolean holdingGift() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        String name = mc.player.getMainHandItem().getHoverName().getString();
        return name.endsWith(" Gift") && (name.startsWith("White") || name.startsWith("Green") || name.startsWith("Red"));
    }

    private static List<Component> giftLines() {
        long amount = EventsFeatures.number(GIFT_KEY, 0);
        return List.of(Component.literal("Unique Players Gifted: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(amount + "/" + MAX_GIFTS).withStyle(amount >= MAX_GIFTS ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
    }

    /** The Generow menu shows the real total in its bottom row. */
    private static void readGenerow(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        EventsConfig.Winter c = config();
        if (c == null || !c.uniqueGiftCounter) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= 40) return;
        ItemStack stack = slots.get(40).getItem();
        ItemLore lore = stack.isEmpty() ? null : stack.get(DataComponents.LORE);
        if (lore == null) return;
        for (Component line : lore.lines()) {
            Matcher m = GIFTED_AMOUNT.matcher(Sky2MLocation.strip(line.getString()));
            if (m.find()) {
                try {
                    EventsFeatures.put(GIFT_KEY, Long.parseLong(m.group(1).replace(",", "")));
                    EventsFeatures.save();
                } catch (NumberFormatException ignored) {
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ jerries

    private static void tick(Minecraft mc) {
        if (!jerriesActive() || mc.level == null || mc.player == null) {
            if (!jerries.isEmpty()) {
                jerries.clear();
                jerryEntities = List.of();
            }
            return;
        }
        if (++ticks % SCAN_INTERVAL_TICKS != 0) return;
        String me = mc.player.getName().getString();
        Map<Integer, Integer> found = new HashMap<>();
        List<LivingEntity> entities = new ArrayList<>();
        AABB area = mc.player.getBoundingBox().inflate(SCAN_RANGE);
        List<ArmorStand> stands = mc.level.getEntitiesOfClass(ArmorStand.class, area, ArmorStand::hasCustomName);
        for (ArmorStand tag : stands) {
            String name = ChatFormatting.stripFormatting(tag.getCustomName().getString());
            if (name == null) continue;
            Matcher m = JERRY.matcher(name);
            if (!m.find()) continue;
            int colour = switch (m.group(1)) {
                case "Green" -> 0x55FF55;
                case "Blue" -> 0x5555FF;
                case "Purple" -> 0xAA00AA;
                default -> 0xFFAA00;
            };
            AABB near = tag.getBoundingBox().inflate(1.5, 3, 1.5);
            boolean mine = false;
            for (ArmorStand other : stands) {
                String text = ChatFormatting.stripFormatting(other.getCustomName().getString());
                if (text != null && text.toLowerCase(Locale.ROOT).contains("spawned by") && text.contains(me)
                    && other.getBoundingBox().intersects(near)) {
                    mine = true;
                    break;
                }
            }
            if (!mine) continue;
            LivingEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (LivingEntity mob : mc.level.getEntitiesOfClass(LivingEntity.class, near,
                    e -> !(e instanceof ArmorStand) && !(e instanceof Player) && e.isAlive())) {
                double d = mob.distanceToSqr(tag);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = mob;
                }
            }
            if (best != null) {
                found.put(best.getId(), colour);
                entities.add(best);
            }
        }
        jerries.clear();
        jerries.putAll(found);
        jerryEntities = entities;
    }
}
