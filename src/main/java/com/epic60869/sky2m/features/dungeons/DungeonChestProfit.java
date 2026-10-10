package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * NoFrills' Dungeon Chest Value and Croesus Solver (features/dungeons, BSD-3-Clause), for dungeon reward chests:
 * <ul>
 *     <li>In a reward chest (at the end of a run, or opened at Croesus): "Chest Value: x" over the chest, its contents'
 *     worth (lowest BIN, else bazaar instant sell; the fish and discs at their NPC price) minus the chest's cost.</li>
 *     <li>In Croesus's (or Vesuvius's) list of runs: each run coloured by whether its chests are unopened, unopened
 *     after a Kismet reroll, opened, or opened with a key too, with its floor ("F7", "M7") on it.</li>
 *     <li>In a run's chest menu: the most profitable chest highlighted (pink when it's very valuable or holds a dye),
 *     and the second best too (aqua when it's still worth a Dungeon Chest Key), with each chest's value in its tooltip.</li>
 * </ul>
 */
public final class DungeonChestProfit {
    private static final Set<String> CHEST_NAMES = Set.of("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock");
    private static final Set<String> NPC_SELL_ITEMS = Set.of("STORM_THE_FISH", "MAXOR_THE_FISH", "GOLDOR_THE_FISH",
        "DUNGEON_DISC_1", "DUNGEON_DISC_2", "DUNGEON_DISC_3", "DUNGEON_DISC_4", "DUNGEON_DISC_5");
    private static final Pattern ITEM_QUANTITY = Pattern.compile(".* x[0-9]*");
    private static final Pattern CROESUS = Pattern.compile("(?:|\\([0-9]*/[0-9]*\\) )Croesus");
    private static final Pattern VESUVIUS = Pattern.compile("(?:|\\([0-9]*/[0-9]*\\) )Vesuvius");
    private static final Set<String> LOOT_AREAS = Set.of("Catacombs", "Kuudra", "Dungeon Hub", "Crimson Isle");

    private enum LootState { UNOPENED, REROLLED, OPENED, OPENED_KEY, UNKNOWN }

    /** The open reward chest's value (0 = nothing to show). */
    private static double currentValue;
    /** Slot index -> background colour (ARGB), and floor labels, for the open Croesus menus. */
    private static Map<Integer, Integer> backgrounds = Map.of();
    private static Map<Integer, String> labels = Map.of();
    /** Slot index -> the chest's value minus its cost, in a run's chest menu. */
    private static Map<Integer, Double> chestValues = Map.of();
    private static int ticks;

    private DungeonChestProfit() {}

    private static FeatureConfigs.ChestProfit config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.dungeons.chestProfit;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            clear();
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ItemPriceResolver.warmup();
            Sky2MPriceTooltip.warmup();
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> renderChestValue(container, g));
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 != 0) return;
            if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && isInLootArea()) update(screen);
            else clear();
        });
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            FeatureConfigs.ChestProfit config = config();
            if (config == null || !config.croesusSolver || !config.valueTooltip || chestValues.isEmpty() || !isInLootArea()) return;
            if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
            for (Slot slot : screen.getMenu().slots) {
                if (slot.getItem() != stack) continue;
                Double value = chestValues.get(slot.index);
                if (value == null || slot.container instanceof Inventory) return;
                lines.add(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("Chest Value: ").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(separator(value)).withStyle(value > 0 ? ChatFormatting.GREEN : ChatFormatting.RED)));
                return;
            }
        });
    }

    private static void clear() {
        currentValue = 0;
        if (!backgrounds.isEmpty()) backgrounds = Map.of();
        if (!labels.isEmpty()) labels = Map.of();
        if (!chestValues.isEmpty()) chestValues = Map.of();
    }

    private static boolean isInLootArea() {
        return Compat.isOnSkyblock() && (Sky2MLocation.inDungeon() || LOOT_AREAS.contains(Sky2MLocation.area()));
    }

    private static String title(Screen screen) {
        return ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
    }

    private static void update(AbstractContainerScreen<?> screen) {
        FeatureConfigs.ChestProfit config = config();
        if (config == null) return;
        String title = title(screen);
        currentValue = config.chestValue && isChest(title) ? rewardChestValue(screen) : 0;
        if (!config.croesusSolver) {
            backgrounds = Map.of();
            labels = Map.of();
            chestValues = Map.of();
            return;
        }
        if (CROESUS.matcher(title).matches() || VESUVIUS.matcher(title).matches()) {
            highlightLoot(screen, config);
        } else if (title.startsWith("Catacombs - Floor") || title.startsWith("Master Catacombs - Floor")) {
            highlightChests(screen, config);
        } else {
            backgrounds = Map.of();
            labels = Map.of();
            chestValues = Map.of();
        }
    }

    // ------------------------------------------------------------------------------------------------ chest value

    private static boolean isChest(String title) {
        for (String name : CHEST_NAMES) {
            if (title.equals(name) || (title.startsWith(name) && title.endsWith("Chest"))) return true;
        }
        return false;
    }

    private static double rewardChestValue(AbstractContainerScreen<?> screen) {
        double value = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || stack.is(Items.STAINED_GLASS_PANE.pick(net.minecraft.world.item.DyeColor.BLACK))) continue;
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            String id = lootId(stack, name);
            if (id.isEmpty()) {
                if (name.equals("Open Reward Chest")) {
                    for (String line : lore(stack)) {
                        if (line.endsWith(" Coins")) {
                            value -= parseInt(line.replace(" Coins", "").replace(",", ""));
                            break;
                        }
                    }
                }
                continue;
            }
            value += lootValue(id) * lootQuantity(stack, name);
        }
        return value;
    }

    /** NoFrills' getLootValue: the fish and discs at their NPC price, the rest lowest BIN, else bazaar instant sell. */
    private static double lootValue(String id) {
        if (NPC_SELL_ITEMS.contains(id)) return ItemPriceResolver.npcPrice(id);
        return Sky2MPriceTooltip.unitPrice(id);
    }

    private static int lootQuantity(ItemStack stack, String name) {
        String[] parts = name.split(" ");
        String last = parts[parts.length - 1];
        if (last.startsWith("x")) {
            int parsed = parseInt(last.replace("x", "").replace(",", ""));
            return parsed > 0 ? parsed : stack.getCount();
        }
        return stack.getCount();
    }

    private static String lootId(ItemStack stack, String name) {
        if (name.startsWith("Wither Essence")) return "ESSENCE_WITHER";
        if (name.startsWith("Undead Essence")) return "ESSENCE_UNDEAD";
        return Sky2MPriceTooltip.marketId(stack);
    }

    private static void renderChestValue(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        FeatureConfigs.ChestProfit config = config();
        if (config == null || !config.chestValue || currentValue == 0) return;
        // Not over the Case Opening spin: it would give away what's inside before the spin lands.
        if (CaseOpening.hidesMenu(screen)) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= 4) return;
        var accessor = (com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor) screen;
        Slot target = slots.get(4);
        var font = Minecraft.getInstance().font;
        String text = "Chest Value: " + separator(currentValue);
        int width = font.width(text);
        int baseX = accessor.getX() + target.x + 8, baseY = accessor.getY() + target.y + 8;
        g.fill((int) Math.floor(baseX - 2 - width * 0.5), baseY - 6, (int) Math.ceil(baseX + 2 + width * 0.5), baseY + 6, colour(config.background, 0xCC202020));
        g.centeredText(font, Component.literal(text), baseX, baseY - 4, currentValue > 0 ? 0xFF55FF55 : 0xFFFF5555);
    }

    // ------------------------------------------------------------------------------------------------ croesus solver

    private static LootState lootState(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return LootState.UNKNOWN;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text.equals("No chests opened yet!")) {
                for (Component other : lore.lines()) {
                    Optional<Style> style = styleOf(other, s -> s.endsWith("Kismet Feather"));
                    if (style.isPresent() && style.get().isStrikethrough()) return LootState.REROLLED;
                }
                return LootState.UNOPENED;
            }
            if (text.startsWith("Opened Chest: ")) return LootState.OPENED;
            if (text.equals("No more chests to open!")) return LootState.OPENED_KEY;
        }
        return LootState.UNKNOWN;
    }

    private static void highlightLoot(AbstractContainerScreen<?> screen, FeatureConfigs.ChestProfit config) {
        Map<Integer, Integer> colours = new HashMap<>();
        Map<Integer, String> floorLabels = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            if (!name.endsWith("The Catacombs")) continue;
            String colour = switch (lootState(stack)) {
                case UNOPENED -> config.unopenedColor;
                case REROLLED -> config.rerolledColor;
                case OPENED -> config.openedColor;
                case OPENED_KEY -> config.openedKeyColor;
                case UNKNOWN -> null;
            };
            if (colour == null) continue;
            colours.put(slot.index, colour(colour, 0x8055FF55));
            if (config.floorLabel) {
                List<String> lore = lore(stack);
                if (!lore.isEmpty()) {
                    String floorLine = lore.getFirst();
                    int floor = roman(floorLine.substring(floorLine.lastIndexOf(' ') + 1));
                    floorLabels.put(slot.index, (name.startsWith("Master Mode") ? "M" : "F") + floor);
                }
            }
        }
        backgrounds = colours;
        labels = floorLabels;
        chestValues = Map.of();
    }

    private static void highlightChests(AbstractContainerScreen<?> screen, FeatureConfigs.ChestProfit config) {
        Map<Integer, Double> values = new HashMap<>();
        Map<Integer, Boolean> dyes = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!CHEST_NAMES.contains(ChatFormatting.stripFormatting(stack.getHoverName().getString()))) continue;
            ItemLore itemLore = stack.get(DataComponents.LORE);
            if (itemLore == null) continue;
            List<Component> lore = itemLore.lines();
            double value = 0, cost = 0;
            int costIndex = -1;
            boolean hasDye = false;
            for (int i = 0; i < lore.size(); i++) {
                Component text = lore.get(i);
                String line = ChatFormatting.stripFormatting(text.getString());
                if (line.isEmpty() || line.equals("Contents") || line.equals("Cost")) {
                    if (line.equals("Cost")) costIndex = i;
                    if (line.isEmpty() && costIndex != -1) break;
                    continue;
                }
                if (costIndex == -1) {
                    String id = marketId(text);
                    if (id.startsWith("DYE_")) hasDye = true;
                    int quantity = ITEM_QUANTITY.matcher(line).matches() ? parseInt(line.substring(line.lastIndexOf('x') + 1)) : 1;
                    value += lootValue(id) * quantity;
                } else if (line.endsWith(" Coins")) {
                    cost += parseInt(line.substring(0, line.indexOf(' ')).replace(",", ""));
                }
            }
            values.put(slot.index, value - cost);
            dyes.put(slot.index, hasDye);
        }
        Map<Integer, Integer> colours = new HashMap<>();
        List<Map.Entry<Integer, Double>> chests = new ArrayList<>(values.entrySet());
        chests.sort(Comparator.comparingDouble((Map.Entry<Integer, Double> e) -> e.getValue()).reversed());
        if (!chests.isEmpty()) {
            Map.Entry<Integer, Double> best = chests.getFirst();
            if (best.getValue() > 0) {
                boolean high = dyes.getOrDefault(best.getKey(), false) || best.getValue() >= config.profitHighThreshold;
                colours.put(best.getKey(), colour(high ? config.profitHighColor : config.profitColor, 0x8055FF55));
            }
        }
        if (chests.size() >= 2) {
            Map.Entry<Integer, Double> second = chests.get(1);
            double keyPrice = Sky2MPriceTooltip.price("DUNGEON_CHEST_KEY", FeatureConfigs.ProfitPriceSource.SELL_ORDER);
            if (keyPrice > 0 && second.getValue() - keyPrice > 0) colours.put(second.getKey(), colour(config.profitKeyColor, 0x8055FFFF));
            else if (second.getValue() > 0) colours.put(second.getKey(), colour(config.profitSecondaryColor, 0x80FFFF55));
        }
        backgrounds = colours;
        labels = Map.of();
        chestValues = values;
    }

    /** Behind the slot's item (Sky2MSlotBackgroundMixin). */
    public static void renderSlotBackground(GuiGraphicsExtractor graphics, Slot slot) {
        if (backgrounds.isEmpty() || slot.container instanceof Inventory) return;
        Integer colour = backgrounds.get(slot.index);
        if (colour != null) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour);
    }

    /** Over the slot's item, like a stack count (Sky2MSlotBackgroundMixin). */
    public static void renderSlotLabel(GuiGraphicsExtractor graphics, Slot slot) {
        if (labels.isEmpty() || slot.container instanceof Inventory) return;
        String label = labels.get(slot.index);
        if (label == null) return;
        var font = Minecraft.getInstance().font;
        graphics.text(font, label, slot.x + 17 - font.width(label), slot.y + 9, 0xFFFFFFFF, true);
    }

    // ------------------------------------------------------------------------------------------------ item names

    /** NoFrills' Utils.getMarketId(Text): the price id for an item named in a chest's lore. */
    private static String marketId(Component text) {
        String name = ChatFormatting.stripFormatting(text.getString()).trim();
        if (ITEM_QUANTITY.matcher(name).matches()) name = name.substring(0, name.lastIndexOf(' ')).trim();
        if (name.startsWith("Enchanted Book (") && name.endsWith(")")) {
            String enchant = name.substring(name.indexOf('(') + 1, name.indexOf(')'))
                .replace("Hardened Vitality", "Hardened Mana")
                .replace("Strong Vitality", "Strong Mana")
                .replace("Vampiric Vitality", "Mana Vampire")
                .replace("Vivacious Vitality", "Ferocious Mana");
            String enchantName = toId(enchant.substring(0, enchant.lastIndexOf(' ')));
            int level = roman(enchant.substring(enchant.lastIndexOf(' ') + 1));
            String wanted = enchant;
            Optional<Style> style = styleOf(text, wanted::equals);
            if (style.isPresent() && hasColour(style.get(), ChatFormatting.LIGHT_PURPLE) && !enchantName.startsWith("ULTIMATE_")) {
                return "ENCHANTMENT_ULTIMATE_" + enchantName + "_" + level;
            }
            return "ENCHANTMENT_" + enchantName + "_" + level;
        }
        if (name.endsWith(" Essence")) return "ESSENCE_" + toId(name.substring(0, name.lastIndexOf(' ')));
        if (name.endsWith(" Dye")) return "DYE_" + toId(name.substring(0, name.lastIndexOf(' ')));
        if (name.startsWith("Master Skull - Tier ")) return toId(name.replace(" - ", " "));
        if (name.startsWith("[Lvl 1] ")) {
            String petName = name.substring(name.indexOf(']') + 2);
            Optional<Style> style = styleOf(text, petName::equals);
            String rarity = "COMMON";
            if (style.isPresent()) {
                if (hasColour(style.get(), ChatFormatting.GOLD)) rarity = "LEGENDARY";
                if (hasColour(style.get(), ChatFormatting.DARK_PURPLE)) rarity = "EPIC";
                if (hasColour(style.get(), ChatFormatting.BLUE)) rarity = "RARE";
                if (hasColour(style.get(), ChatFormatting.GREEN)) rarity = "UNCOMMON";
            }
            return toId(petName) + "_PET_" + rarity;
        }
        return switch (name) {
            case "Shadow Warp" -> "SHADOW_WARP_SCROLL";
            case "Wither Shield" -> "WITHER_SHIELD_SCROLL";
            case "Implosion" -> "IMPLOSION_SCROLL";
            case "Giant's Sword" -> "GIANTS_SWORD";
            case "Warped Stone" -> "AOTE_STONE";
            case "Spirit Boots" -> "THORNS_BOOTS";
            case "Spirit Shortbow" -> "ITEM_SPIRIT_BOW";
            case "Spirit Stone" -> "SPIRIT_DECOY";
            case "Adaptive Blade" -> "STONE_BLADE";
            case "Wither Cloak Sword" -> "WITHER_CLOAK";
            case "Dungeon Disc" -> "DUNGEON_DISC_1";
            case "Clown Disc" -> "DUNGEON_DISC_2";
            case "Watcher Disc" -> "DUNGEON_DISC_3";
            case "Old Disc" -> "DUNGEON_DISC_4";
            case "Necron Disc" -> "DUNGEON_DISC_5";
            case "Shiny Wither Helmet" -> "WITHER_HELMET";
            case "Shiny Wither Chestplate" -> "WITHER_CHESTPLATE";
            case "Shiny Wither Leggings" -> "WITHER_LEGGINGS";
            case "Shiny Wither Boots" -> "WITHER_BOOTS";
            case "Shiny Necron's Handle" -> "NECRON_HANDLE";
            case "Dusty Travel Scroll to the Kuudra Skull" -> "NETHER_FORTRESS_BOSS_TRAVEL_SCROLL";
            case "Hellstorm Wand" -> "HELLSTORM_STAFF";
            default -> toId(name.replace("✪", "").trim());
        };
    }

    private static String toId(String text) {
        return text.replace("'s", "").replace(" ", "_").toUpperCase(Locale.ROOT);
    }

    /** The style of the part of {@code text} whose string matches. */
    private static Optional<Style> styleOf(Component text, java.util.function.Predicate<String> matches) {
        Style[] found = {null};
        text.visit((style, value) -> {
            if (found[0] == null && matches.test(value.trim())) found[0] = style;
            return Optional.empty();
        }, Style.EMPTY);
        return Optional.ofNullable(found[0]);
    }

    private static boolean hasColour(Style style, ChatFormatting formatting) {
        TextColor colour = style.getColor();
        TextColor wanted = TextColor.fromLegacyFormat(formatting);
        return colour != null && wanted != null && colour.getValue() == wanted.getValue();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Component line : lore.lines()) out.add(ChatFormatting.stripFormatting(line.getString()));
        return out;
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int roman(String numeral) {
        int total = 0, previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (numeral.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    private static String separator(double value) {
        return String.format(Locale.US, "%,d", Math.round(value));
    }

    private static int colour(String value, int fallback) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
    }
}
