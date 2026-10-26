package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An item's Estimated Item Value split into rows (base item, stars, recombobulator, each enchantment, gemstones,
 * scrolls...), like SkyHanni's and NEU's Estimated Item Value breakdown. Uses the same pricing rules as the Price
 * Tooltip's Estimated Item Value ({@link Sky2MPriceTooltip#costPrice}: bazaar insta-buy, else lowest BIN).
 */
public final class ValueBreakdown {
    public record Row(String label, double coins) {}

    private static final Set<String> UNIVERSAL_SLOTS = Set.of("COMBAT", "OFFENSIVE", "DEFENSIVE", "UNIVERSAL", "MINING", "CHISEL");
    private static final String[] MASTER_STARS = {"FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};

    private ValueBreakdown() {}

    static EconomyConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.economy;
    }

    /** The rows for one item (not times its count), empty when it has no price. */
    public static List<Row> rows(ItemStack stack) {
        List<Row> rows = new ArrayList<>();
        String id = Compat.neuName(stack);
        if (id.isEmpty()) return rows;
        String apiId = Sky2MPriceTooltip.marketId(stack);
        double base = Sky2MPriceTooltip.costPrice(apiId);
        if (base <= 0) return rows;
        rows.add(new Row("Base item", base));
        CompoundTag data = Compat.customDataView(stack);

        if (data.getIntOr("rarity_upgrades", 0) > 0) add(rows, "Recombobulator", "RECOMBOBULATOR_3000", 1);
        int potato = data.getIntOr("hot_potato_count", 0);
        add(rows, "Hot Potato Books x" + Math.min(potato, 10), "HOT_POTATO_BOOK", Math.min(potato, 10));
        add(rows, "Fuming Potato Books x" + Math.max(0, potato - 10), "FUMING_POTATO_BOOK", Math.max(0, potato - 10));

        if (!id.equals("ENCHANTED_BOOK")) {
            CompoundTag enchants = data.getCompoundOrEmpty("enchantments");
            for (String enchant : enchants.keySet()) {
                int level = enchants.getIntOr(enchant, 1);
                add(rows, pretty(enchant) + " " + level, "ENCHANTMENT_" + enchant.toUpperCase(Locale.ROOT) + "_" + level, 1);
            }
        }

        int stars = Math.max(data.getIntOr("upgrade_level", 0), data.getIntOr("dungeon_item_level", 0));
        for (int i = 5; i < Math.min(stars, 10); i++) add(rows, "Master Star " + (i - 4), MASTER_STARS[i - 5], 1);

        CompoundTag gems = data.getCompoundOrEmpty("gems");
        for (String slot : gems.keySet()) {
            if (slot.endsWith("_gem") || slot.equals("unlocked_slots")) continue;
            String quality = gems.getString(slot).orElseGet(() -> gems.getCompoundOrEmpty(slot).getStringOr("quality", ""));
            if (quality.isEmpty()) continue;
            String type = slot.substring(0, Math.max(0, slot.lastIndexOf('_')));
            if (UNIVERSAL_SLOTS.contains(type)) type = gems.getStringOr(slot + "_gem", "");
            if (!type.isEmpty()) add(rows, pretty(quality) + " " + pretty(type) + " Gem", quality + "_" + type + "_GEM", 1);
        }

        if (data.getIntOr("art_of_war_count", 0) > 0) add(rows, "The Art of War", "THE_ART_OF_WAR", 1);
        if (data.getIntOr("artOfPeaceApplied", 0) > 0) add(rows, "The Art of Peace", "THE_ART_OF_PEACE", 1);
        add(rows, "Wood Singularity", "WOOD_SINGULARITY", data.getIntOr("wood_singularity_count", 0));
        add(rows, "Farming For Dummies", "FARMING_FOR_DUMMIES", data.getIntOr("farming_for_dummies_count", 0));
        add(rows, "Polarvoid Book", "POLARVOID_BOOK", data.getIntOr("polarvoid", 0));
        add(rows, "Transmission Tuners", "TRANSMISSION_TUNER", data.getIntOr("tuned_transmission", 0));
        add(rows, "Mana Disintegrators", "MANA_DISINTEGRATOR", data.getIntOr("mana_disintegrator_count", 0));
        if (data.getIntOr("jalapeno_count", 0) > 0) add(rows, "Jalapeno Book", "JALAPENO_BOOK", 1);
        if (data.getIntOr("ethermerge", 0) > 0) add(rows, "Etherwarp Conduit", "ETHERWARP_CONDUIT", 1);
        if (data.contains("stats_book")) add(rows, "Book of Stats", "BOOK_OF_STATS", 1);
        if (data.getIntOr("divan_powder_coating", 0) > 0) add(rows, "Divan Powder Coating", "DIVAN_POWDER_COATING", 1);

        for (String key : new String[]{"dye_item", "skin", "power_ability_scroll", "drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module"}) {
            String item = data.getStringOr(key, "");
            if (!item.isEmpty()) add(rows, pretty(item), item.toUpperCase(Locale.ROOT), 1);
        }
        String enrichment = data.getStringOr("talisman_enrichment", "");
        if (!enrichment.isEmpty()) add(rows, "Enrichment " + pretty(enrichment), "TALISMAN_ENRICHMENT_" + enrichment.toUpperCase(Locale.ROOT), 1);

        ListTag scrolls = data.getListOrEmpty("ability_scroll");
        for (int i = 0; i < scrolls.size(); i++) add(rows, pretty(scrolls.getStringOr(i, "")), scrolls.getStringOr(i, ""), 1);

        if (!id.equals("RUNE") && !id.equals("UNIQUE_RUNE")) {
            CompoundTag runes = data.getCompoundOrEmpty("runes");
            for (String rune : runes.keySet()) add(rows, pretty(rune) + " Rune " + runes.getIntOr(rune, 1), rune + "_RUNE_" + runes.getIntOr(rune, 1), 1);
        }
        return rows;
    }

    /** The total of {@link #rows} times the stack's count. */
    public static double total(ItemStack stack) {
        double sum = 0;
        for (Row row : rows(stack)) sum += row.coins();
        return sum * Math.max(1, stack.getCount());
    }

    private static void add(List<Row> rows, String label, String priceId, int times) {
        if (times <= 0 || priceId.isEmpty()) return;
        double each = Sky2MPriceTooltip.costPrice(priceId);
        if (each > 0) rows.add(new Row(label, each * times));
    }

    private static String pretty(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : text.replace('-', '_').split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    public static String coins(double value) {
        return String.format(Locale.ENGLISH, "%,.0f", value);
    }

    /** Whether the side panel is on, which replaces the Price Tooltip's Estimated Item Value line. */
    public static boolean panelEnabled() {
        EconomyConfig c = config();
        return c != null && c.breakdownPanel;
    }

    /** Draws the hovered item's breakdown in a panel beside the menu (left of it, else right, else the screen's edge). */
    static void renderPanel(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        if (!panelEnabled() || !Compat.isOnSkyblock()) return;
        var access = (com.epic60869.sky2m.mixin.Sky2MContainerScreenAccessor) screen;
        net.minecraft.world.inventory.Slot hovered = access.sky2m$getHoveredSlot();
        if (hovered == null || !hovered.hasItem()) return;
        ItemStack stack = hovered.getItem();
        List<Row> rows = rows(stack);
        if (rows.isEmpty()) return;

        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int count = Math.max(1, stack.getCount());
        double sum = 0;
        for (Row row : rows) sum += row.coins();
        String total = coins(sum * count) + " Coins";
        String title = "Estimated Item Value";
        int labelWidth = font.width(title);
        for (Row row : rows) labelWidth = Math.max(labelWidth, font.width(row.label()) + 12 + font.width(coins(row.coins())));
        int w = Math.max(labelWidth, font.width("Total: " + total)) + 16;
        int h = 8 + 12 + rows.size() * 10 + 6 + 12 + 6;

        int left = access.sky2m$getLeftPos(), top = access.sky2m$getTopPos(), image = access.sky2m$getImageWidth();
        int x = left - w - 6;
        if (x < 4) x = left + image + 6;
        if (x + w > screen.width - 4) x = Math.max(4, screen.width - w - 4);
        int y = Math.clamp(top, 4, Math.max(4, screen.height - h - 4));

        g.nextStratum();
        g.fill(x, y, x + w, y + h, 0xE0101216);
        g.outline(x, y, w, h, 0xFF2C313A);
        int line = y + 8;
        g.text(font, Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), x + 8, line, 0xFFFFFFFF, true);
        line += 12;
        for (Row row : rows) {
            g.text(font, row.label(), x + 8, line, 0xFFCCCCCC, true);
            String value = coins(row.coins());
            g.text(font, value, x + w - 8 - font.width(value), line, 0xFF55FFFF, true);
            line += 10;
        }
        line += 6;
        g.text(font, Component.literal("Total: ").withStyle(ChatFormatting.GOLD).append(Component.literal(total).withStyle(ChatFormatting.YELLOW)),
            x + 8, line, 0xFFFFFFFF, true);
    }

    /** Adds the breakdown rows to a tooltip while Shift is held. */
    static void tooltip(ItemStack stack, List<Component> lines) {
        EconomyConfig c = config();
        if (c == null || !c.breakdownTooltip || !Compat.isOnSkyblock() || !Economy.shiftDown()) return;
        List<Row> rows = rows(stack);
        if (rows.size() < 2) return;
        lines.add(Component.empty());
        lines.add(Component.literal("Value Breakdown").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Row row : rows) {
            lines.add(Component.literal(" " + row.label() + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(coins(row.coins()) + " Coins").withStyle(ChatFormatting.DARK_AQUA)));
        }
    }
}
