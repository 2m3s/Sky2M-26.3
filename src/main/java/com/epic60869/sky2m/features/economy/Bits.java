package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bits: a chat line with the bits you gain (read from the scoreboard's Bits line), and in the Community Shop how many
 * coins each bit is worth for an item and how many Booster Cookies your bits make.
 */
final class Bits {
    private static final Pattern NUMBER = Pattern.compile("[\\d,]+");
    private static final Pattern COST = Pattern.compile("^([\\d,]+) Bits$");
    private static final double COOKIE_BITS = 4800;

    private static long last = -1;
    private static int ticks;

    private Bits() {}

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 != 0) return;
            EconomyConfig c = Economy.config();
            if (c == null || !c.bitsGained || !Compat.isOnSkyblock() || mc.player == null) {
                last = -1;
                return;
            }
            long now = current();
            if (now < 0) return;
            if (last >= 0 && now > last) {
                Economy.message(Component.literal("+" + String.format(Locale.ENGLISH, "%,d", now - last) + " Bits").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(" (" + String.format(Locale.ENGLISH, "%,d", now) + " total)").withStyle(ChatFormatting.GRAY)));
            }
            last = now;
        });
    }

    /** The bits on the scoreboard, or -1. */
    static long current() {
        String value = Sky2MLocation.scoreboardValue("Bits: ");
        if (value == null) return -1;
        Matcher m = NUMBER.matcher(value);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group().replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Community Shop tooltips: coins per bit, and your bits as Booster Cookies. */
    static void tooltip(ItemStack stack, List<Component> lines) {
        EconomyConfig c = Economy.config();
        if (c == null || (!c.bitsToCoins && !c.bitsCookie)) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (screen == null || !Economy.screenTitle(screen).contains("Community Shop")) return;
        double bits = -1;
        for (Component line : lines) {
            Matcher m = COST.matcher(String.valueOf(ChatFormatting.stripFormatting(line.getString())).trim());
            if (m.find()) {
                bits = Double.parseDouble(m.group(1).replace(",", ""));
                break;
            }
        }
        if (bits <= 0) return;
        if (c.bitsToCoins) {
            String id = Sky2MPriceTooltip.marketId(stack);
            double price = id.isEmpty() ? 0 : Sky2MPriceTooltip.unitPrice(id);
            if (price > 0) {
                lines.add(Component.literal("Coins per Bit: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format(Locale.ENGLISH, "%,.1f", price / bits)).withStyle(ChatFormatting.AQUA)));
            }
        }
        if (c.bitsCookie) {
            lines.add(Component.literal("Costs ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.ENGLISH, "%.2f", bits / COOKIE_BITS)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" Booster Cookies of bits").withStyle(ChatFormatting.GOLD)));
            long have = current();
            if (have >= 0) {
                lines.add(Component.literal("Your bits: ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(String.format(Locale.ENGLISH, "%.2f cookies", have / COOKIE_BITS)).withStyle(ChatFormatting.AQUA)));
            }
        }
    }
}
