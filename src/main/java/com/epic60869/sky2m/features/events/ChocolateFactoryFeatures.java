package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chocolate Factory helpers: the best upgrade to buy, the Time Tower and the Stray Rabbit timer. Ported from
 * SkyHanni's chocolate factory features (https://github.com/hannibal002/SkyHanni, features/inventory/chocolatefactory/,
 * LGPL-2.1). The advice compares cost per extra chocolate per second like SkyHanni does for the rabbits and Coach
 * Jackrabbit, but does not factor the Time Tower in.
 */
public final class ChocolateFactoryFeatures {
    private static final String ADVICE_HUD = "events_cf_advice";
    private static final String STRAY_HUD = "events_cf_stray";
    private static final String TOWER_HUD = "events_cf_tower";
    private static final int INFO_SLOT = 13;
    private static final int TOWER_SLOT = 38;
    private static final int COACH_SLOT = 41;
    private static final int FIRST_RABBIT_SLOT = 28;
    private static final int LAST_RABBIT_SLOT = 34;
    private static final long STRAY_MS = 30_000L;
    private static final long TOWER_WARNING_MS = 5 * 60 * 1000L;

    private static final Pattern PER_SECOND = Pattern.compile("([\\d.,]+) per second");
    private static final Pattern MULTIPLIER = Pattern.compile("Total Multiplier: ([\\d.,]+)x");
    private static final Pattern AMOUNT = Pattern.compile("([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern COST = Pattern.compile("^([\\d,]+) Chocolate$");
    private static final Pattern CHARGES = Pattern.compile("Charges: (\\d+)/(\\d+)");
    private static final Pattern STATUS = Pattern.compile("Status: (INACTIVE|ACTIVE)\\s*(.*)");

    /** What the last look at the open factory found. */
    private record Option(int slot, String name, long cost, double extraPerSecond) {
        double paybackSeconds() {
            return cost / extraPerSecond;
        }
    }

    private static volatile Option best;
    private static volatile Option affordable;
    private static volatile double chocolate;
    private static volatile double perSecond;

    private static volatile int towerCharges = -1;
    private static volatile int towerMax;
    private static volatile String towerStatus = "";
    private static volatile long towerSeenAt;
    private static long lastTowerWarning;

    private static boolean strayArmed;
    private static long strayRemaining;
    private static long strayLastTick;
    private static long lastDing;
    private static int ticks;

    private ChocolateFactoryFeatures() {}

    private static EventsConfig.ChocolateFactory config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.chocolateFactory;
    }

    static boolean inFactory() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
            && Sky2MLocation.strip(screen.getTitle().getString()).trim().equals("Chocolate Factory");
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ChocolateFactoryFeatures::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> highlight(container, graphics));
        });
        Sky2MChat.onChat(message -> onChat(message.text().trim()));

        ScreenHuds.register(ADVICE_HUD, ChocolateFactoryFeatures::adviceLines);
        Sky2MHuds.register(ADVICE_HUD, "Chocolate Factory Advice", () -> false, List::of, List.of(
            Component.literal("Best upgrade: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Rabbit Bro").withStyle(ChatFormatting.GREEN)),
            Component.literal(" 1.2M, pays back in 3h 20m").withStyle(ChatFormatting.DARK_GRAY)), 10, 40);
        Sky2MHuds.setSettingsSearch(ADVICE_HUD, "Upgrade Advice");
        Sky2MHuds.setting(ADVICE_HUD, () -> config() != null && config().upgradeAdvice);

        ScreenHuds.register(STRAY_HUD, ChocolateFactoryFeatures::strayLines);
        Sky2MHuds.register(STRAY_HUD, "Stray Rabbit Timer", () -> false, List::of, List.of(
            Component.literal("Stray Timer").withStyle(ChatFormatting.YELLOW),
            Component.literal("24.50s").withStyle(ChatFormatting.AQUA)), 10, 80);
        Sky2MHuds.setSettingsSearch(STRAY_HUD, "Stray Rabbit Timer");
        Sky2MHuds.setting(STRAY_HUD, () -> config() != null && config().strayTimer);

        Sky2MHuds.register(TOWER_HUD, "Time Tower", () -> {
            EventsConfig.ChocolateFactory c = config();
            return c != null && c.timeTower && Sky2MLocation.onSkyblock() && towerCharges >= 0;
        }, ChocolateFactoryFeatures::towerLines, List.of(
            Component.literal("Time Tower: ").withStyle(ChatFormatting.GOLD).append(Component.literal("2/3 charges").withStyle(ChatFormatting.AQUA)),
            Component.literal(" Inactive").withStyle(ChatFormatting.GRAY)), 10, 100);
        Sky2MHuds.setSettingsSearch(TOWER_HUD, "Time Tower Display");
        Sky2MHuds.setting(TOWER_HUD, () -> config() != null && config().timeTower);
    }

    // ------------------------------------------------------------------------------------------------ chat / timer

    private static void onChat(String text) {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.strayTimer || !text.startsWith("HOPPITY'S HUNT You found")) return;
        if (text.contains("Stray Rabbit")) {
            strayArmed = false;
        } else if (text.contains("Egg ")) {
            strayArmed = true;
            strayRemaining = STRAY_MS;
            strayLastTick = System.currentTimeMillis();
        }
    }

    private static void tick(Minecraft mc) {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        boolean factory = inFactory();
        if (c.strayTimer && strayArmed) {
            long dt = Math.min(500, now - strayLastTick);
            if (factory) {
                strayRemaining -= dt;
                if (strayRemaining <= 0) {
                    strayArmed = false;
                } else if (c.strayDing > 0 && strayRemaining < c.strayDing * 1000L && now - lastDing > 1000) {
                    Sky2MAlerts.ding();
                    lastDing = now;
                }
            } else {
                strayRemaining = STRAY_MS;
            }
        }
        strayLastTick = now;

        if (factory && ++ticks % 5 == 0 && (c.upgradeAdvice || c.timeTower || c.timeTowerWarning)) read(mc);
        if (!factory && c.timeTowerWarning && Sky2MLocation.onSkyblock() && towerCharges >= 0 && towerCharges >= towerMax && towerMax > 0
            && now - lastTowerWarning > TOWER_WARNING_MS) {
            lastTowerWarning = now;
            EventsFeatures.sayClickable("Your Time Tower was full (" + towerCharges + "/" + towerMax + " charges) when you last looked. Use one so none go to waste. Click to open the Chocolate Factory.",
                "/cf", "Click to run /cf");
        }
    }

    // ------------------------------------------------------------------------------------------------ reading the factory

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore != null) for (Component line : lore.lines()) out.add(Sky2MLocation.strip(line.getString()).trim());
        return out;
    }

    private static String name(ItemStack stack) {
        return Sky2MLocation.strip(stack.getHoverName().getString()).trim();
    }

    private static Long cost(List<String> lore) {
        boolean afterCost = false;
        for (String line : lore) {
            if (line.equals("Cost")) {
                afterCost = true;
                continue;
            }
            if (!afterCost) continue;
            Matcher m = COST.matcher(line);
            if (m.matches()) return Long.parseLong(m.group(1).replace(",", ""));
        }
        return null;
    }

    private static double number(String text) {
        return Double.parseDouble(text.replace(",", ""));
    }

    private static void read(Minecraft mc) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        try {
            var slots = screen.getMenu().slots;
            if (slots.size() <= COACH_SLOT) return;
            ItemStack info = slots.get(INFO_SLOT).getItem();
            if (info.isEmpty()) return;
            Matcher m = AMOUNT.matcher(name(info));
            double current = m.find() ? number(m.group(1)) : -1;
            double cps = -1;
            double multiplier = -1;
            for (String line : lore(info)) {
                Matcher p = PER_SECOND.matcher(line);
                if (p.find()) cps = number(p.group(1));
            }
            for (int i = 0; i < Math.min(54, slots.size()) && multiplier < 0; i++) {
                for (String line : lore(slots.get(i).getItem())) {
                    Matcher p = MULTIPLIER.matcher(line);
                    if (p.find()) {
                        multiplier = number(p.group(1));
                        break;
                    }
                }
            }
            readTower(slots.get(TOWER_SLOT).getItem());
            if (current < 0 || cps <= 0 || multiplier <= 0) return;
            chocolate = current;
            perSecond = cps;
            double raw = cps / multiplier;

            List<Option> options = new ArrayList<>();
            for (int slot = FIRST_RABBIT_SLOT; slot <= LAST_RABBIT_SLOT; slot++) {
                ItemStack stack = slots.get(slot).getItem();
                if (stack.isEmpty() || !name(stack).startsWith("Rabbit ")) continue;
                Long cost = cost(lore(stack));
                if (cost == null) continue;
                options.add(new Option(slot, name(stack), cost, (slot - FIRST_RABBIT_SLOT + 1) * multiplier));
            }
            ItemStack coach = slots.get(COACH_SLOT).getItem();
            if (!coach.isEmpty() && name(coach).startsWith("Coach Jackrabbit")) {
                Long cost = cost(lore(coach));
                if (cost != null) options.add(new Option(COACH_SLOT, name(coach), cost, raw * 0.01));
            }
            Option bestOption = null;
            Option affordableOption = null;
            for (Option o : options) {
                if (o.extraPerSecond() <= 0) continue;
                if (bestOption == null || o.paybackSeconds() < bestOption.paybackSeconds()) bestOption = o;
                if (o.cost() <= current && (affordableOption == null || o.paybackSeconds() < affordableOption.paybackSeconds())) affordableOption = o;
            }
            best = bestOption;
            affordable = affordableOption;
        } catch (Exception ignored) {
            // The menu changed while it was read; try again next time.
        }
    }

    private static void readTower(ItemStack stack) {
        if (stack.isEmpty()) return;
        for (String line : lore(stack)) {
            Matcher m = CHARGES.matcher(line);
            if (m.find()) {
                towerCharges = Integer.parseInt(m.group(1));
                towerMax = Integer.parseInt(m.group(2));
                towerSeenAt = System.currentTimeMillis();
                if (towerCharges < towerMax) lastTowerWarning = System.currentTimeMillis();
                continue;
            }
            m = STATUS.matcher(line);
            if (m.find()) {
                towerStatus = m.group(1).equals("ACTIVE") ? "Active " + m.group(2).trim() : "Inactive";
                towerSeenAt = System.currentTimeMillis();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ display

    private static void highlight(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.upgradeAdvice || !inFactory()) return;
        Option bestOption = best;
        Option affordableOption = affordable;
        Sky2MContainerScreenAccessor access = (Sky2MContainerScreenAccessor) screen;
        if (bestOption != null) box(graphics, screen, access, bestOption.slot(), 0xFF55FF55);
        if (affordableOption != null && (bestOption == null || affordableOption.slot() != bestOption.slot())) {
            box(graphics, screen, access, affordableOption.slot(), 0xFFFFAA00);
        }
    }

    private static void box(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen, Sky2MContainerScreenAccessor access, int index, int argb) {
        if (index >= screen.getMenu().slots.size()) return;
        Slot slot = screen.getMenu().slots.get(index);
        int x = access.sky2m$getLeftPos() + slot.x;
        int y = access.sky2m$getTopPos() + slot.y;
        g.fill(x - 1, y - 1, x + 17, y, argb);
        g.fill(x - 1, y + 16, x + 17, y + 17, argb);
        g.fill(x - 1, y, x, y + 16, argb);
        g.fill(x + 16, y, x + 17, y + 16, argb);
        g.fill(x, y, x + 16, y + 16, (argb & 0x00FFFFFF) | 0x38000000);
    }

    private static List<Component> adviceLines() {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.upgradeAdvice || !inFactory()) return List.of();
        Option bestOption = best;
        if (bestOption == null) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(option("Best upgrade", ChatFormatting.GREEN, bestOption));
        if (bestOption.cost() > chocolate && perSecond > 0) {
            lines.add(Component.literal(" Affordable in " + EventsFeatures.duration((long) ((bestOption.cost() - chocolate) / perSecond * 1000)))
                .withStyle(ChatFormatting.GRAY));
        }
        Option affordableOption = affordable;
        if (affordableOption != null && affordableOption.slot() != bestOption.slot()) {
            lines.add(option("Best affordable", ChatFormatting.GOLD, affordableOption));
        }
        return lines;
    }

    private static Component option(String label, ChatFormatting colour, Option o) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(o.name()).withStyle(colour))
            .append(Component.literal(" (" + EventsFeatures.compact(o.cost()) + ", pays back in "
                + EventsFeatures.duration((long) (o.paybackSeconds() * 1000)) + ")").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static List<Component> strayLines() {
        EventsConfig.ChocolateFactory c = config();
        if (c == null || !c.strayTimer || !strayArmed || !inFactory() || strayRemaining <= 0) return List.of();
        return List.of(Component.literal("Stray Timer").withStyle(ChatFormatting.YELLOW),
            Component.literal(String.format(Locale.US, "%.2fs", strayRemaining / 1000.0)).withStyle(ChatFormatting.AQUA));
    }

    private static List<Component> towerLines() {
        List<Component> lines = new ArrayList<>();
        boolean full = towerCharges >= towerMax && towerMax > 0;
        lines.add(Component.literal("Time Tower: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(towerCharges + "/" + towerMax + " charges").withStyle(full ? ChatFormatting.RED : ChatFormatting.AQUA)));
        if (!towerStatus.isEmpty()) {
            lines.add(Component.literal(" " + towerStatus + " (seen " + EventsFeatures.duration(System.currentTimeMillis() - towerSeenAt) + " ago)").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }
}
