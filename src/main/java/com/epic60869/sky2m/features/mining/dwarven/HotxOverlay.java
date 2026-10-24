package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heart of the Mountain / Heart of the Forest overlays, after SkyHanni's HotxFeatures and PowderPerHotmPerk
 * (hannibal002/SkyHanni, LGPL-2.1; SkyOcean's HotmHelper does similar): a perk's level as stack size, green and red
 * highlights for enabled and disabled perks, and the powder left to max a perk. The level and enabled state are read from
 * each perk's lore. The per-level cost formulas are SkyHanni's HotmData ones (cost of level n = (n + 1)^exponent); the
 * next level's cost is taken from the lore when it is listed there.
 */
public final class HotxOverlay {
    private static final Pattern LEVEL = Pattern.compile("^Level (\\d+)/(\\d+)$");
    private static final Pattern COST = Pattern.compile("([\\d,]+) (Mithril|Gemstone|Glacite) Powder");

    /** HOTM perk -> {max level, cost exponent, cost offset (n + offset)^exponent}. */
    private record Perk(int max, double exponent, double offset, String powder) {}

    private static final Map<String, Perk> HOTM = new HashMap<>();

    static {
        mithril("Mining Speed", 50, 3.0);
        mithril("Mining Fortune", 50, 3.05);
        mithril("Titanium Insanium", 50, 3.1);
        mithril("Luck of the Cave", 45, 3.07);
        mithril("Efficient Miner", 100, 2.6);
        mithril("Quick Forge", 20, 3.2);
        gemstone("Old-School", 20, 4.0);
        gemstone("Professional", 140, 2.3);
        gemstone("Mole", 200, 2.17883);
        gemstone("Gem Lover", 20, 4.0);
        gemstone("Seasoned Mineman", 100, 2.3);
        gemstone("Fortunate Mineman", 50, 3.2);
        gemstone("Blockhead", 20, 4.0);
        gemstone("Keep It Cool", 50, 3.07);
        gemstone("Lonesome Miner", 45, 3.07);
        gemstone("Great Explorer", 20, 4.0);
        gemstone("Powder Buff", 50, 3.2);
        gemstone("Speedy Mineman", 50, 3.2);
        gemstone("Subterranean Fisher", 40, 3.07);
        glacite("No Stone Unturned", 50, 3.05, 1);
        glacite("Strong Arm", 100, 2.3, 1);
        glacite("Steady Hand", 100, 2.6, 1);
        glacite("Warm Heart", 50, 3.1, 1);
        glacite("Surveyor", 20, 4.0, 1);
        glacite("Metal Head", 20, 4.0, 1);
        glacite("Rags to Riches", 50, 3.05, 1);
        glacite("Eager Adventurer", 100, 2.3, 1);
        glacite("Crystalline", 50, 3.3, 1);
        glacite("Gifts from the Departed", 100, 2.45, 1);
        glacite("Mining Master", 10, 5.0, 7);
        glacite("Dead Man's Chest", 50, 3.2, 1);
        glacite("Vanguard Seeker", 50, 3.1, 1);
    }

    private static void mithril(String name, int max, double exponent) {
        HOTM.put(name, new Perk(max, exponent, 1, "Mithril"));
    }

    private static void gemstone(String name, int max, double exponent) {
        HOTM.put(name, new Perk(max, exponent, 1, "Gemstone"));
    }

    private static void glacite(String name, int max, double exponent, double offset) {
        HOTM.put(name, new Perk(max, exponent, offset, "Glacite"));
    }

    private HotxOverlay() {}

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            DwarvenConfig.Hotx c = DwarvenMining.hotx();
            if (c == null || !c.hotmTooltip || screen() != Tree.HOTM) return;
            tooltip(stack, lines);
        });
    }

    private enum Tree { NONE, HOTM, HOTF }

    private static Tree screen() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !Sky2MLocation.onSkyblock()) return Tree.NONE;
        net.minecraft.network.chat.Component screenTitle = screen.getTitle();
        if (screenTitle != lastTitle) {
            lastTitle = screenTitle;
            String title = Sky2MLocation.strip(screenTitle.getString());
            lastTree = title.equals("Heart of the Mountain") ? Tree.HOTM : title.equals("Heart of the Forest") ? Tree.HOTF : Tree.NONE;
        }
        return lastTree;
    }

    // Asked for every slot every frame: the title is only read when it changes.
    private static net.minecraft.network.chat.Component lastTitle;
    private static Tree lastTree = Tree.NONE;

    /** {level, max} read from a perk's lore, or null when it has no "Level x/y" line. */
    private static int[] level(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return null;
        for (Component line : lore.lines()) {
            Matcher m = LEVEL.matcher(Sky2MLocation.strip(line.getString()).trim());
            if (m.matches()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
    }

    private static String state(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return null;
        for (Component line : lore.lines()) {
            String text = Sky2MLocation.strip(line.getString()).trim();
            if (text.equals("ENABLED") || text.equals("DISABLED")) return text;
        }
        return null;
    }

    /** Green and red behind enabled and disabled perks (Sky2MSlotBackgroundMixin). */
    public static void renderSlotBackground(GuiGraphicsExtractor graphics, Slot slot) {
        DwarvenConfig.Hotx c = DwarvenMining.hotx();
        if (c == null || !c.hotmHighlight || !slot.hasItem() || slot.container instanceof Inventory) return;
        if (screen() != Tree.HOTM) return;
        String state = state(slot.getItem());
        if (state == null) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, state.equals("ENABLED") ? 0x8055FF55 : 0x80FF5555);
    }

    /** The perk's level in the corner of its icon (Sky2MSlotBackgroundMixin). */
    public static void renderSlotLabel(GuiGraphicsExtractor graphics, Slot slot) {
        DwarvenConfig.Hotx c = DwarvenMining.hotx();
        if (c == null || !slot.hasItem() || slot.container instanceof Inventory) return;
        Tree tree = screen();
        if (tree == Tree.NONE || (tree == Tree.HOTM ? !c.hotmLevel : !c.hotfLevel)) return;
        int[] level = level(slot.getItem());
        if (level == null || level[0] == 0 || level[0] == level[1] || level[1] <= 1) return;
        String text = String.valueOf(level[0]);
        var font = Minecraft.getInstance().font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(slot.x + 17, slot.y + 17);
        graphics.pose().scale(0.8f, 0.8f);
        graphics.text(font, text, -font.width(text), -8, 0xFFFFFF55, true);
        graphics.pose().popMatrix();
    }

    private static double cost(Perk perk, int level) {
        return Math.pow(level + perk.offset(), perk.exponent());
    }

    private static void tooltip(ItemStack stack, java.util.List<Component> lines) {
        String name = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
        Perk perk = HOTM.get(name);
        int[] level = level(stack);
        if (perk == null || level == null || level[1] != perk.max()) return;
        int current = level[0];

        long spent = 0;
        for (int l = 2; l <= current; l++) spent += (long) cost(perk, l);
        long remaining = 0;
        long next = 0;
        if (current < perk.max()) {
            next = (long) cost(perk, current + 1);
            // The next level's cost as the menu lists it, when it does.
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    Matcher m = COST.matcher(Sky2MLocation.strip(line.getString()));
                    if (m.find()) {
                        next = Long.parseLong(m.group(1).replace(",", ""));
                        break;
                    }
                }
            }
            remaining = next;
            for (int l = current + 2; l <= perk.max(); l++) remaining += (long) cost(perk, l);
        }
        String powder = perk.powder() + " Powder";
        lines.add(Component.empty());
        lines.add(Component.literal("Powder spent: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(String.format(Locale.US, "%,d", spent) + " " + powder).withStyle(ChatFormatting.GREEN)));
        if (current < perk.max()) {
            lines.add(Component.literal("Left to max: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format(Locale.US, "%,d", remaining) + " " + powder).withStyle(ChatFormatting.GOLD)));
        } else {
            lines.add(Component.literal("Maxed").withStyle(ChatFormatting.GREEN));
        }
    }
}
