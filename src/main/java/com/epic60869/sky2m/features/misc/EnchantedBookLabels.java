package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Two letters in the top right of an enchanted book naming its enchantment ("Sh" for Sharpness, "UW" for Ultimate
 * Wise), so you can tell books apart without hovering each one. Books with more than one enchantment are left alone.
 */
public final class EnchantedBookLabels {
    private static final float SCALE = 0.75f;
    private static final int COLOUR = 0xFFFFFFFF;
    private static final int ULTIMATE_COLOUR = 0xFFFF55FF;
    private static final String NONE = "";

    /** The label for each book, so its data isn't read every frame. */
    private static final Map<ItemStack, String> CACHE = new WeakHashMap<>();

    private EnchantedBookLabels() {}

    /** Drawn with the item's count and durability (Sky2MItemCooldownMixin). */
    public static void drawOverlay(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.enchantedBookLabels || stack.isEmpty()) return;
        if (!Compat.isOnSkyblock() || !"ENCHANTED_BOOK".equals(Compat.neuName(stack))) return;
        String enchant = CACHE.computeIfAbsent(stack, EnchantedBookLabels::enchantment);
        if (enchant.isEmpty()) return;
        boolean ultimate = enchant.startsWith("ultimate_");
        String label = label(ultimate ? enchant.substring("ultimate_".length()) : enchant, ultimate);
        var font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate(x + 17, y);
        g.pose().scale(SCALE, SCALE);
        g.text(font, label, -font.width(label), 0, ultimate ? ULTIMATE_COLOUR : COLOUR, true);
        g.pose().popMatrix();
    }

    /** The book's only enchantment ("ultimate_wise"), or "" when it has none or several. */
    private static String enchantment(ItemStack stack) {
        CompoundTag enchants = Compat.customDataView(stack).getCompoundOrEmpty("enchantments");
        if (enchants.size() != 1) return NONE;
        return enchants.keySet().iterator().next().toLowerCase(Locale.ROOT);
    }

    /** "sharpness" -> "Sh", "first_strike" -> "FS"; Ultimate enchantments use the initials of the rest ("wise" -> "W"). */
    static String label(String enchant, boolean ultimate) {
        String[] words = enchant.split("_");
        if (words.length >= 2) return (initial(words[0]) + initial(words[1])).toUpperCase(Locale.ROOT);
        String word = words[0];
        if (word.isEmpty()) return NONE;
        if (ultimate) return "U" + Character.toUpperCase(word.charAt(0));
        return Character.toUpperCase(word.charAt(0)) + (word.length() > 1 ? word.substring(1, 2) : "");
    }

    private static String initial(String word) {
        return word.isEmpty() ? "" : word.substring(0, 1);
    }
}
