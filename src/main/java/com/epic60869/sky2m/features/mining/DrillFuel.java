package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyblockAddons' drill fuel display (https://github.com/BiscuitDevelopment/SkyblockAddons, MIT): the current fuel is
 * the drill's "drill_fuel" custom data and the tank size comes from the "Fuel: 3,000/20k" lore line. Only shown when
 * the item really has that data, never a guessed value.
 */
public final class DrillFuel {
    private static final String ID = "drill_fuel";
    /** "Fuel: 3,000/20k" or "Fuel: 3,000/20,000". */
    private static final Pattern FUEL = Pattern.compile("Fuel: (?<fuel>[\\d,.]+[kKmM]?)/(?<max>[\\d,.]+[kKmM]?)");

    /** The stack the cached values were read from (compared by identity). */
    private static ItemStack cachedStack = ItemStack.EMPTY;
    private static int fuel = -1;
    private static int maxFuel = -1;
    private static boolean warned;
    private static int ticks;

    private DrillFuel() {}

    private static FeatureConfigs.MiningFeatures config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.features;
    }

    private static boolean turnedOn() {
        FeatureConfigs.MiningFeatures c = config();
        return c != null && c.drillFuelHud;
    }

    public static void init() {
        Sky2MHuds.setting(ID, DrillFuel::turnedOn);
        Sky2MHuds.register(ID, "Drill Fuel", () -> turnedOn() && Compat.isOnSkyblock() && fuel >= 0, DrillFuel::lines,
            List.of(Component.literal("Drill Fuel: ").withStyle(ChatFormatting.DARK_GREEN)
                .append(Component.literal("3,000/20,000").withStyle(ChatFormatting.YELLOW))),
            8, 330);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 == 0) tick(mc);
        });
    }

    private static void tick(Minecraft mc) {
        FeatureConfigs.MiningFeatures c = config();
        if (mc.player == null || c == null || !c.drillFuelHud || !Sky2MLocation.onSkyblock()) {
            clear();
            return;
        }
        ItemStack stack = findDrill(mc, c.drillFuelOnlyWhenHolding);
        if (stack.isEmpty()) {
            clear();
            return;
        }
        if (stack != cachedStack) {
            cachedStack = stack;
            read(stack);
        }
        if (fuel < 0 || maxFuel <= 0 || !c.drillFuelWarning) return;
        double percent = fuel * 100.0 / maxFuel;
        if (percent < c.drillFuelWarningThreshold) {
            if (!warned) {
                warned = true;
                Sky2MAlerts.title(Component.literal("LOW DRILL FUEL!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal(format(fuel) + "/" + format(maxFuel) + " fuel left").withStyle(ChatFormatting.YELLOW));
            }
        } else {
            warned = false; // refuelled
        }
    }

    private static void clear() {
        cachedStack = ItemStack.EMPTY;
        fuel = -1;
        maxFuel = -1;
    }

    /** The drill in your main hand, else (unless only when holding) the first drill in your hotbar. */
    private static ItemStack findDrill(Minecraft mc, boolean onlyWhenHolding) {
        ItemStack held = mc.player.getMainHandItem();
        if (isDrill(held)) return held;
        if (onlyWhenHolding) return ItemStack.EMPTY;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isDrill(stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static boolean isDrill(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return Compat.neuName(stack).contains("DRILL") || Compat.customDataView(stack).contains("drill_fuel");
    }

    /** Fuel from "drill_fuel", tank size (and fuel, if the tag is missing) from the "Fuel: X/Y" lore line. */
    private static void read(ItemStack stack) {
        CompoundTag tag = Compat.customDataView(stack);
        fuel = tag.contains("drill_fuel") ? tag.getIntOr("drill_fuel", -1) : -1;
        maxFuel = -1;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return;
        for (Component line : lore.lines()) {
            Matcher m = FUEL.matcher(Sky2MLocation.strip(line.getString()));
            if (!m.find()) continue;
            int max = parse(m.group("max"));
            if (max <= 0) continue;
            maxFuel = max;
            if (fuel < 0) fuel = parse(m.group("fuel"));
            return;
        }
    }

    /** "3,000" -> 3000, "20k" -> 20000, "1.5M" -> 1500000; -1 when unreadable. */
    private static int parse(String text) {
        String s = text.replace(",", "").trim();
        double multiplier = 1;
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        if (last == 'k') multiplier = 1_000;
        else if (last == 'm') multiplier = 1_000_000;
        if (multiplier != 1) s = s.substring(0, s.length() - 1);
        try {
            return (int) Math.round(Double.parseDouble(s) * multiplier);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String format(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static List<Component> lines() {
        FeatureConfigs.MiningFeatures c = config();
        if (c == null || fuel < 0) return List.of();
        Component label = Component.literal("Drill Fuel: ").withStyle(ChatFormatting.DARK_GREEN);
        if (maxFuel <= 0) {
            // No tank size in the lore: just the amount, uncoloured.
            return List.of(label.copy().append(Component.literal(format(fuel)).withStyle(ChatFormatting.WHITE)));
        }
        double percent = fuel * 100.0 / maxFuel;
        ChatFormatting colour = percent > 50 ? ChatFormatting.GREEN : percent > 20 ? ChatFormatting.YELLOW : ChatFormatting.RED;
        String amount = format(fuel) + "/" + format(maxFuel);
        String pct = String.format(Locale.US, "%.1f%%", percent);
        String value = switch (c.drillFuelDisplay) {
            case AMOUNT -> amount;
            case PERCENTAGE -> pct;
            case BOTH -> amount + " (" + pct + ")";
        };
        return List.of(label.copy().append(Component.literal(value).withStyle(colour)));
    }
}
