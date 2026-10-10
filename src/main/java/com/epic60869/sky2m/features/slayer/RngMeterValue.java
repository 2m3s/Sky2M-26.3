package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.inventory.Slot;
import net.minecraft.core.component.DataComponents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RNG meter value: in a slayer (or dungeon) RNG Meter menu, adds each drop's price and how many coins one point of
 * meter progress is worth to its tooltip, so a 10M drop needing 100k Slayer XP shows "1 XP = 100 coins".
 */
public final class RngMeterValue {
    /** The "0/100k" (or "1,234/1.3M") progress amount on an RNG meter drop. */
    private static final Pattern PROGRESS = Pattern.compile("([\\d,.]+[kKmMbB]?)/([\\d,.]+[kKmMbB]?)\\s*$");
    /** "Required Slayer XP: 100k" style lines, used when the drop shows no progress amount. */
    private static final Pattern REQUIRED = Pattern.compile("(?i)(?:required|needed)[^:]*:\\s*([\\d,.]+[kmb]?)");
    /** "Smite VII" -> enchantment and level, for enchanted book drops. */
    private static final Pattern ENCHANT = Pattern.compile("^([A-Za-z' ]+) ([IVX]+)$");

    private RngMeterValue() {}

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!enabled() || !inRngMeter()) return;
            addValue(stack, lines);
        });
        // The best drop per point, above the menu (worked out twice a second: prices and the menu change slowly).
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container) || !isRngMeter(container)) return;
            Component[] line = {null};
            int[] ticks = {0};
            ScreenEvents.afterTick(screen).register(s -> {
                if (ticks[0]++ % 10 == 0) line[0] = enabled() ? best(container) : null;
            });
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                if (line[0] == null) return;
                var font = Minecraft.getInstance().font;
                int top = ((Sky2MContainerScreenAccessor) container).sky2m$getTopPos();
                graphics.text(font, line[0], container.width / 2 - font.width(line[0]) / 2, Math.max(2, top - 12), 0xFFFFFFFF, true);
            });
        });
    }

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.slayers.rngMeterValue && Compat.isOnSkyblock();
    }

    private static boolean isRngMeter(AbstractContainerScreen<?> container) {
        String title = ChatFormatting.stripFormatting(container.getTitle().getString());
        return title != null && title.contains("RNG Meter");
    }

    private static boolean inRngMeter() {
        return Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> container && isRngMeter(container);
    }

    /** A drop's price, what the meter needs for it, and whether that's Slayer XP or dungeon Score. */
    private record Value(double price, double required, String unit) {
        double perPoint() {
            return price / required;
        }
    }

    /** The drop's value from its tooltip (or name and lore) lines, or null if it isn't an RNG meter drop. */
    private static Value value(ItemStack stack, List<Component> lines) {
        double required = -1;
        String unit = "XP";
        for (Component line : lines) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text == null) continue;
            text = text.trim();
            if (text.toLowerCase(Locale.ROOT).contains("score")) unit = "Score";
            Matcher m = PROGRESS.matcher(text);
            if (m.find()) {
                required = parse(m.group(2));
            } else if (required <= 0 && (m = REQUIRED.matcher(text)).find()) {
                required = parse(m.group(1));
            }
        }
        if (required <= 0) return null;
        String id = priceId(stack, lines);
        return new Value(id == null ? 0 : Sky2MPriceTooltip.unitPrice(id), required, unit);
    }

    private static void addValue(ItemStack stack, List<Component> lines) {
        Value value = value(stack, lines);
        if (value == null) return;
        double price = value.price();
        if (price <= 0) {
            lines.add(Component.literal("RNG Value: ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("No price data").withStyle(ChatFormatting.RED)));
            return;
        }
        lines.add(Component.literal("RNG Value: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(CombatFeatures.formatCoins(price) + " coins").withStyle(ChatFormatting.DARK_AQUA)));
        lines.add(Component.literal("1 " + value.unit() + " = ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(perPoint(value.perPoint()) + " coins").withStyle(ChatFormatting.GREEN)));
    }

    // ---------------------------------------------------------------- best profit, above the menu

    /** The menu's best drop per point, e.g. "Best Profit: Tarantula Silk (1 XP = 123 coins)"; null if none has a price. */
    private static Component best(AbstractContainerScreen<?> screen) {
        String bestName = null;
        Value best = null;
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            List<Component> lines = new ArrayList<>();
            lines.add(Compat.realName(stack));
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) lines.addAll(lore.lines());
            Value value = value(stack, lines);
            if (value == null || value.price() <= 0) continue;
            if (best == null || value.perPoint() > best.perPoint()) {
                best = value;
                bestName = ChatFormatting.stripFormatting(lines.get(0).getString());
            }
        }
        if (best == null) return null;
        return Component.literal("Best Profit: ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(bestName == null ? "?" : bestName.trim()).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" (1 " + best.unit() + " = " + perPoint(best.perPoint()) + " coins)").withStyle(ChatFormatting.GREEN));
    }

    /** Coins per point, with decimals when it's small (books can be worth under a coin per XP). */
    private static String perPoint(double value) {
        if (value >= 1_000) return CombatFeatures.formatCoins(value);
        if (value >= 10) return String.format(Locale.US, "%.1f", value);
        return String.format(Locale.US, "%.2f", value);
    }

    /** The price key for the drop: from the item's data, else its name (or the enchantment for a book). */
    private static String priceId(ItemStack stack, List<Component> lines) {
        String id = Sky2MPriceTooltip.marketId(stack);
        if (!id.isEmpty()) return id;
        String name = ChatFormatting.stripFormatting(Compat.realName(stack).getString());
        if (name == null || name.isBlank()) return null;
        name = name.trim();
        String byName = RepoItems.idByName(name);
        if (byName != null) return byName;
        // Book drops are named after their enchantment ("Smite VII"), or "Enchanted Book" with it on the next line.
        if (name.equalsIgnoreCase("Enchanted Book") && lines.size() > 1) {
            name = ChatFormatting.stripFormatting(lines.get(1).getString()).trim();
        }
        Matcher m = ENCHANT.matcher(name);
        if (!m.matches()) return null;
        return "ENCHANTMENT_" + m.group(1).trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "")
            + "_" + roman(m.group(2));
    }

    private static int roman(String numeral) {
        int total = 0, previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (numeral.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    /** Parses "1,234", "100k", "1.3M", "2B". Returns -1 when invalid. */
    private static double parse(String text) {
        String t = text.replace(",", "").toLowerCase(Locale.ROOT);
        double multiplier = 1;
        if (t.endsWith("k")) multiplier = 1_000;
        else if (t.endsWith("m")) multiplier = 1_000_000;
        else if (t.endsWith("b")) multiplier = 1_000_000_000;
        if (multiplier != 1) t = t.substring(0, t.length() - 1);
        try {
            return Double.parseDouble(t) * multiplier;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
