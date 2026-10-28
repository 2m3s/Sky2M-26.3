package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Party Finder, from SkyHanni's DungeonFinderFeatures (LGPL-2.1): in the Party Finder, groups get a coloured
 * slot when you can't join them (dark red), they sell carries (red), they aren't PUGs (purple), a member's class level
 * is low (yellow) or nobody plays your class (green); each group's tooltip lists the missing classes and shows member
 * lines in colour; and the floor is written on groups and on the Catacombs Gate and Select Floor menus' floors.
 * Duplicate Class Warning ({@link DupeClass}) still marks groups that already have your class.
 */
public final class PartyFinderHelper {
    private static final String[] CLASSES = {"Healer", "Mage", "Berserk", "Archer", "Tank"};
    /** " Name: Archer (42)"; the level can carry extra text, as SkyHanni allows for. */
    private static final Pattern MEMBER = Pattern.compile("^ ?(\\w{1,16}): (Healer|Mage|Berserk|Archer|Tank) \\(.*?(\\d+).*?\\)$");
    private static final Pattern INELIGIBLE = Pattern.compile("Requires .*$|You don't meet the requirement!|Complete previous floor first!$");
    private static final Pattern NOTE = Pattern.compile("^Note: (.*)$");
    private static final Pattern PRICE = Pattern.compile("(?i).*(?:[0-9]{2,3}K|[0-9]{1,3}M|[0-9]+\\.[0-9]M|[0-9] ?MIL).*");
    private static final Pattern CARRY = Pattern.compile("(?i).*(?:CARRY|CARY|CARRIES|CARIES|COMP|TO CATA [0-9]{2}).*");
    private static final Pattern NON_PUG = Pattern.compile("(?i).*(?:PERM|VC|DISCORD).*");
    private static final Pattern DUNGEON = Pattern.compile("^Dungeon: (.*)$");
    private static final Pattern FLOOR = Pattern.compile("^Floor:? (.*)$");
    private static final Pattern FLOOR_NUMBER = Pattern.compile(".* ([IVX]+|\\d+)$");

    private static final int INELIGIBLE_COLOUR = 0x80AA0000, CARRY_COLOUR = 0x80FF5555, NON_PUG_COLOUR = 0x80FF55FF,
        LOW_LEVEL_COLOUR = 0x80FFFF55, MISSING_CLASS_COLOUR = 0x8055FF55;

    /** One Party Finder group, read from its lore once per item. */
    private record Group(boolean party, boolean ineligible, String note, Set<String> classes, int lowestLevel, String floor) {}

    private static final Map<ItemStack, Group> GROUPS = new WeakHashMap<>();

    private PartyFinderHelper() {}

    private static FeatureConfigs.PartyFinderHelper config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.partyFinderHelper;
    }

    public static void init() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            FeatureConfigs.PartyFinderHelper c = config();
            if (c == null || !inScreen("Party Finder")) return;
            Group group = group(stack);
            if (!group.party()) return;
            if (c.colouredClassLevel) colourMembers(lines);
            if (c.showMissingClasses) addMissing(lines, group);
        });
    }

    private static boolean inScreen(String title) {
        return Compat.isOnSkyblock() && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
            && Sky2MLocation.strip(screen.getTitle().getString()).contains(title);
    }

    private static Group group(ItemStack stack) {
        return GROUPS.computeIfAbsent(stack, PartyFinderHelper::read);
    }

    private static Group read(ItemStack stack) {
        boolean party = Sky2MLocation.strip(stack.getHoverName().getString()).endsWith("'s Party");
        ItemLore lore = stack.get(DataComponents.LORE);
        if (!party || lore == null) return new Group(false, false, "", Set.of(), -1, "");
        boolean ineligible = false, master = false;
        StringBuilder note = new StringBuilder();
        Set<String> classes = new LinkedHashSet<>();
        int lowest = Integer.MAX_VALUE;
        String floorText = "";
        for (Component line : lore.lines()) {
            String text = Sky2MLocation.strip(line.getString()).trim();
            String untrimmed = Sky2MLocation.strip(line.getString());
            Matcher m;
            if (INELIGIBLE.matcher(text).find()) ineligible = true;
            if ((m = NOTE.matcher(text)).matches()) note.append(m.group(1)).append(' ');
            if ((m = DUNGEON.matcher(text)).matches() && m.group(1).contains("Master")) master = true;
            if ((m = FLOOR.matcher(text)).matches()) floorText = m.group(1);
            if ((m = MEMBER.matcher(untrimmed.startsWith(" ") ? untrimmed : " " + text)).matches()) {
                classes.add(m.group(2));
                lowest = Math.min(lowest, Integer.parseInt(m.group(3)));
            }
        }
        return new Group(true, ineligible, note.toString().trim().toUpperCase(Locale.ROOT), classes,
            lowest == Integer.MAX_VALUE ? -1 : lowest, floorLabel(floorText, master));
    }

    /** "Floor VII" → F7 (M7 in Master Mode), "Entrance" → E, "" when it isn't a floor. */
    static String floorLabel(String floor, boolean master) {
        if (floor == null || floor.isEmpty()) return "";
        if (floor.endsWith("Entrance")) return "E";
        Matcher m = FLOOR_NUMBER.matcher(floor);
        if (!m.matches()) return "";
        int number = number(m.group(1));
        return number <= 0 ? "" : (master ? "M" : "F") + number;
    }

    private static int number(String text) {
        if (text.chars().allMatch(Character::isDigit)) return Integer.parseInt(text);
        int total = 0, previous = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            int value = switch (text.charAt(i)) {
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

    /** The highlight colour for a group, or 0; the first that applies, in SkyHanni's order. */
    private static int colour(FeatureConfigs.PartyFinderHelper c, Group group) {
        if (c.markIneligible && group.ineligible()) return INELIGIBLE_COLOUR;
        if (c.markPaidCarries && PRICE.matcher(group.note()).matches() && CARRY.matcher(group.note()).matches()) return CARRY_COLOUR;
        if (c.markNonPugs && NON_PUG.matcher(group.note()).matches()) return NON_PUG_COLOUR;
        if (c.markBelowClassLevel > 0 && group.lowestLevel() >= 0 && group.lowestLevel() <= c.markBelowClassLevel) return LOW_LEVEL_COLOUR;
        String mine = DupeClass.myClass();
        if (c.markMissingClass && !mine.isEmpty() && !group.classes().contains(mine)) return MISSING_CLASS_COLOUR;
        return 0;
    }

    /** From the slot background mixin: the group's highlight. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        FeatureConfigs.PartyFinderHelper c = config();
        if (c == null || !slot.hasItem() || slot.container instanceof Inventory || !inScreen("Party Finder")) return;
        Group group = group(slot.getItem());
        if (!group.party()) return;
        int colour = colour(c, group);
        if (colour != 0) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour);
    }

    /** From the slot mixin, over the item: the floor, bottom right like a stack count. */
    public static void renderSlotLabel(GuiGraphicsExtractor graphics, Slot slot) {
        FeatureConfigs.PartyFinderHelper c = config();
        if (c == null || !c.floorAsStackSize || !slot.hasItem() || slot.container instanceof Inventory) return;
        String label = label(slot.getItem());
        if (label.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        graphics.text(font, label, slot.x + 17 - font.width(label), slot.y + 9, 0xFFFFFFFF, true);
    }

    private static String label(ItemStack stack) {
        if (!Compat.isOnSkyblock() || !(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return "";
        String title = Sky2MLocation.strip(screen.getTitle().getString());
        String name = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
        if (title.contains("Party Finder")) return group(stack).floor();
        if (title.contains("Catacombs Gate")) {
            if (!name.matches("(?:MM )?The Catacombs.*")) return "";
            return floorLabel(name, name.startsWith("MM "));
        }
        if (title.contains("Select Floor")) {
            if (name.equals("Any")) return "A";
            if (name.endsWith("Entrance")) return "E";
            Matcher m = FLOOR_NUMBER.matcher(name);
            return name.startsWith("Floor") && m.matches() ? String.valueOf(number(m.group(1))) : "";
        }
        return "";
    }

    /** Member lines as "Name: Class (Level)" with the name aqua, the class yellow and the level by its colour. */
    private static void colourMembers(List<Component> lines) {
        for (int i = 1; i < lines.size(); i++) {
            Matcher m = MEMBER.matcher(Sky2MLocation.strip(lines.get(i).getString()));
            if (!m.matches()) continue;
            int level = Integer.parseInt(m.group(3));
            lines.set(i, Component.literal(" ").append(Component.literal(m.group(1)).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(m.group(2)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(level)).withStyle(levelColour(level)))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY)));
        }
    }

    /** SkyHanni's DungeonApi level colours: higher levels in warmer colours, 50 bold red. */
    static ChatFormatting[] levelColour(int level) {
        if (level >= 50) return new ChatFormatting[]{ChatFormatting.RED, ChatFormatting.BOLD};
        ChatFormatting colour = level >= 45 ? ChatFormatting.RED : level >= 40 ? ChatFormatting.GOLD : level >= 35 ? ChatFormatting.LIGHT_PURPLE
            : level >= 30 ? ChatFormatting.BLUE : level >= 25 ? ChatFormatting.AQUA : level >= 20 ? ChatFormatting.DARK_GREEN
            : level >= 15 ? ChatFormatting.GREEN : level >= 10 ? ChatFormatting.YELLOW : level >= 5 ? ChatFormatting.WHITE : ChatFormatting.GRAY;
        return new ChatFormatting[]{colour};
    }

    /** "Missing: Archer, Tank" after the lore, your class in green. */
    private static void addMissing(List<Component> lines, Group group) {
        List<String> missing = missingClasses(group.classes());
        if (missing.isEmpty()) return;
        String mine = DupeClass.myClass();
        MutableComponent line = Component.literal("Missing: ").withStyle(ChatFormatting.RED);
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
            line.append(Component.literal(missing.get(i)).withStyle(missing.get(i).equals(mine) ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        lines.add(Component.empty());
        lines.add(line);
    }

    /** The classes nobody in the group plays, in the menu's order. */
    static List<String> missingClasses(Set<String> present) {
        List<String> missing = new ArrayList<>();
        for (String cls : CLASSES) if (!present.contains(cls)) missing.add(cls);
        return missing;
    }
}
