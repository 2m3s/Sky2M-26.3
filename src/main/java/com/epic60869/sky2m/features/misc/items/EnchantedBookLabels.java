package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shows an abbreviated enchantment name in the top-right and its numeric level in the bottom-right of enchanted
 * books, so books such as Legion V are identifiable without opening their tooltip.
 */
public final class EnchantedBookLabels {
    private static final float SCALE = 0.75f;
    private static final int COLOUR = 0xFFFFFFFF;
    private static final int ULTIMATE_COLOUR = 0xFFFF55FF;
    private static final Label NONE = new Label("", 0);

    private record Label(String name, int level) {}

    /** The label for each book, so its data isn't read every frame. */
    private static final Map<ItemStack, Label> CACHE = new WeakHashMap<>();

    private EnchantedBookLabels() {}

    /** Drawn with the item's count and durability (Sky2MItemCooldownMixin). */
    public static void drawOverlay(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.inventory.enchantedBookLabels || stack.isEmpty()) return;
        if (!Compat.isOnSkyblock() || !"ENCHANTED_BOOK".equals(Compat.neuName(stack))) return;
        Label enchant = CACHE.computeIfAbsent(stack, EnchantedBookLabels::enchantment);
        if (enchant.name().isEmpty()) return;
        boolean ultimate = enchant.name().startsWith("ultimate_");
        String name = label(ultimate ? enchant.name().substring("ultimate_".length()) : enchant.name(), ultimate);
        String level = Integer.toString(enchant.level());
        var font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate(x + 17, y);
        g.pose().scale(SCALE, SCALE);
        g.text(font, name, -font.width(name), 0, ultimate ? ULTIMATE_COLOUR : COLOUR, true);
        // Keep the level aligned to the same right edge, in the bottom corner.
        g.text(font, level, -font.width(level), 10, ultimate ? ULTIMATE_COLOUR : COLOUR, true);
        g.pose().popMatrix();
    }

    /** The book's only enchantment ("ultimate_wise") and its level, or an empty label for books with multiple enchants. */
    private static Label enchantment(ItemStack stack) {
        CompoundTag enchants = Compat.customDataView(stack).getCompoundOrEmpty("enchantments");
        if (enchants.size() != 1) return NONE;
        String key = enchants.keySet().iterator().next().toLowerCase(Locale.ROOT);
        return new Label(key, Math.max(1, enchants.getIntOr(key, 1)));
    }

    /** "sharpness" -> "Sh", "first_strike" -> "FS"; Ultimate enchantments use the initials of the rest ("wise" -> "UW"). */
    static String label(String enchant, boolean ultimate) {
        String[] words = enchant.split("_");
        if (words.length >= 2) return (initial(words[0]) + initial(words[1])).toUpperCase(Locale.ROOT);
        String word = words[0];
        if (word.isEmpty()) return NONE.name();
        if (ultimate) return "U" + Character.toUpperCase(word.charAt(0));
        return Character.toUpperCase(word.charAt(0)) + (word.length() > 1 ? word.substring(1, 2) : "");
    }

    private static String initial(String word) {
        return word.isEmpty() ? "" : word.substring(0, 1);
    }
}
