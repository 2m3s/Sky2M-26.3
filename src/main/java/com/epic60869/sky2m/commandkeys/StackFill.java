package com.epic60869.sky2m.commandkeys;

import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command key set to {@code /gfs ender_pearl}, with no amount, tops the stack up: it asks your sacks for only what a full
 * stack is missing. The idea is killer560's Legit GFS and TBAddon's Sacks. An amount in the command is left as it is.
 */
public final class StackFill {
    private static final Pattern NO_AMOUNT = Pattern.compile("^/(gfs|getfromsacks) (\\S+)$", Pattern.CASE_INSENSITIVE);
    /** Stacks whose size on Hypixel isn't what the item says: a Spirit Leap is a head, which would read as 64. */
    private static final Map<String, Integer> STACK_SIZES = Map.of("ENDER_PEARL", 16, "SPIRIT_LEAP", 16);
    private static final int DEFAULT_STACK = 64;

    private StackFill() {}

    /** The item id in a /gfs with no amount, upper case, or null for anything else. Public for the game test. */
    public static String itemId(String message) {
        Matcher m = NO_AMOUNT.matcher(message == null ? "" : message.trim());
        return m.matches() ? m.group(2).toUpperCase(Locale.ROOT) : null;
    }

    /** How many a full stack is missing, 0 when you have enough. Public for the game test. */
    public static int missing(int stackSize, int carried) {
        return Math.max(0, stackSize - carried);
    }

    /** The stack size for an item: known exceptions first, then what the stack you're carrying says. Public for the game test. */
    public static int stackSize(String id, int carriedStackSize) {
        Integer known = STACK_SIZES.get(id);
        if (known != null) return known;
        return carriedStackSize > 0 ? carriedStackSize : DEFAULT_STACK;
    }

    /** The command to send for {@code message}: the same one, the amount added, or null when the stack is already full. */
    static String command(String message) {
        String id = itemId(message);
        Minecraft mc = Minecraft.getInstance();
        if (id == null || mc.player == null) return message;
        int carried = 0;
        int carriedStackSize = 0;
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || !id.equals(Compat.neuName(stack))) continue;
            carried += stack.getCount();
            carriedStackSize = stack.getMaxStackSize();
        }
        int amount = missing(stackSize(id, carriedStackSize), carried);
        return amount == 0 ? null : message.trim() + " " + amount;
    }
}
