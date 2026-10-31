package com.epic60869.sky2m.features.combat.kuudra;

import com.epic60869.sky2m.price.ItemPriceResolver;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kuudra's menus, ported from Odin's Vesuvius and RemovePerks (BSD-3-Clause, https://github.com/odtheking/Odin):
 * the chest profit panel beside the Free and Paid chest menus (and Vesuvius's run chests), and the Perk Menu cleaner.
 */
final class KuudraMenus {
    private static final Pattern CHEST_TITLE = Pattern.compile("^(?:(?:Free|Paid) Chest|Kuudra - .+)$");
    private static final Pattern QUANTITY = Pattern.compile("^(.+?) x([\\d,]+)$");
    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\(?([\\w ]+) (\\w+)\\)?$");
    private static final Pattern SALVAGE = Pattern.compile("Boots|Leggings|Chestplate|Helmet|Cloak|Aurora Staff|Hollow Wand");
    private static final Pattern USELESS = Pattern.compile("^(?:Contents|Cost|Click to open!|FREE|This Chest is Free!|Already opened!|Can't open another chest!|(?:Paid|Free) Chest|Open Reward Chest)?$");
    private static final int[] STAR_ESSENCE = {120, 138, 158, 182, 210, 240, 272, 308};
    private static final int[] CHEST_SLOTS = {31, 13, 14};

    private record Key(String name, long coins, int material) {}
    private static final List<Key> KEYS = List.of(
        new Key("Kuudra Key", 155_200, 2), new Key("Hot Kuudra Key", 310_400, 4), new Key("Burning Kuudra Key", 582_000, 16),
        new Key("Fiery Kuudra Key", 1_164_000, 40), new Key("Infernal Kuudra Key", 2_328_000, 80));

    private record Entry(Component name, double price) {}
    private record Chest(List<Entry> items, double keyCost, double profit) {}

    private static Map<Integer, Chest> chests = Map.of();
    private static int ticks;

    private KuudraMenus() {}

    private static KuudraConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.crimsonIsle.kuudra;
    }

    static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            chests = Map.of();
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> renderChest(container, g));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                KuudraConfig c = config();
                if (c == null || !c.perkCleaner || !isPerkMenu(container)) return true;
                Slot hovered = ((com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor) container).getFocusedSlot();
                return hovered == null || !hiddenPerk(hovered.getItem(), c);
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            KuudraConfig c = config();
            if (c == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
            if (c.perkCleaner && isPerkMenu(screen)) {
                // Display only: the real items stay on the server, they just aren't drawn or clickable here.
                for (Slot slot : screen.getMenu().slots) {
                    if (!(slot.container instanceof net.minecraft.world.entity.player.Inventory) && hiddenPerk(slot.getItem(), c)) slot.set(ItemStack.EMPTY);
                }
            }
            if (c.chestProfit && ++ticks % 5 == 0) updateChests(screen);
            else if (!c.chestProfit && !chests.isEmpty()) chests = Map.of();
        });
    }

    // ---------------------------------------------------------------------------------------------------- perks

    private static boolean isPerkMenu(AbstractContainerScreen<?> screen) {
        return Compat.isOnSkyblock() && Titles.of(screen).equals("Perk Menu");
    }

    private static boolean hiddenPerk(ItemStack stack, KuudraConfig c) {
        if (stack.isEmpty()) return false;
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        if (name == null) return false;
        return name.contains("Steady Hands") || name.contains("Bomberman") || name.contains("Mining Frenzy")
            || name.equals("Elle's Lava Rod") || name.equals("Elle's Pickaxe") || name.equals("Auto Revive")
            || (!c.keepStunPerk && name.contains("Human Cannonball"));
    }

    /** The menu's title without colour codes. */
    private static final class Titles {
        static String of(AbstractContainerScreen<?> screen) {
            String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
            return title == null ? "" : title.trim();
        }
    }

    // ---------------------------------------------------------------------------------------------------- chest profit

    private static void updateChests(AbstractContainerScreen<?> screen) {
        if (!Compat.isOnSkyblock() || !CHEST_TITLE.matcher(Titles.of(screen)).matches()) {
            if (!chests.isEmpty()) chests = Map.of();
            return;
        }
        ItemPriceResolver.warmup();
        Sky2MPriceTooltip.warmup();
        Map<Integer, Chest> found = new HashMap<>();
        List<Slot> slots = screen.getMenu().slots;
        for (int index : CHEST_SLOTS) {
            if (index >= slots.size()) continue;
            ItemStack stack = slots.get(index).getItem();
            boolean chestItem = stack.is(Items.CHEST) || stack.is(Items.PLAYER_HEAD);
            if (!chestItem) continue;
            Chest chest = read(stack);
            if (chest != null) found.put(index, chest);
        }
        chests = found;
    }

    private static Chest read(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return null;
        KuudraConfig c = config();
        List<Entry> items = new ArrayList<>();
        double total = 0, keyCost = 0;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text == null) continue;
            text = text.trim();
            if (text.contains("Kuudra Key")) {
                keyCost = keyPrice(text);
                continue;
            }
            if (USELESS.matcher(text).matches()) continue;
            double price = value(text, line, c);
            total += price;
            items.add(new Entry(line, price));
        }
        if (items.isEmpty()) return null;
        return new Chest(items, keyCost, total - keyCost);
    }

    private static double keyPrice(String name) {
        for (Key key : KEYS) {
            if (!key.name().equals(name)) continue;
            double material = Math.min(Sky2MPriceTooltip.costPrice("ENCHANTED_RED_SAND"), Sky2MPriceTooltip.costPrice("ENCHANTED_MYCELIUM"));
            return key.coins() + material * key.material() + Sky2MPriceTooltip.costPrice("CORRUPTED_NETHER_STAR") * 2;
        }
        return 0;
    }

    /** What a lore line (an item, maybe with " x4") is worth. */
    private static double value(String text, Component line, KuudraConfig c) {
        double essenceBonus = 1 + c.essenceBonusPercent / 100.0;
        int stars = 0;
        int[] counter = {0};
        line.visit((style, part) -> {
            int count = (int) part.chars().filter(ch -> ch == '✪').count();
            if (count > 0 && style.getColor() != null) {
                int rgb = style.getColor().getValue();
                if (rgb == net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.GOLD).getValue()) counter[0] += count;
                else if (rgb == net.minecraft.network.chat.TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE).getValue()) counter[0] += count * 2;
            }
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        stars = counter[0];
        String item = text.replaceAll("[✪➊➋➌➍➎]", "").trim();

        Matcher book = BOOK.matcher(item);
        if (book.find()) {
            String name = book.group(1)
                .replace("Vivacious Vitality", "Ferocious Mana").replace("Hardened Vitality", "Hardened Mana")
                .replace("Strong Vitality", "Strong Mana").replace("Vampiric Vitality", "Mana Vampire");
            String ultimate = name.equals("Fatal Tempo") || name.equals("Inferno") ? "ULTIMATE_" : "";
            return Sky2MPriceTooltip.unitPrice("ENCHANTMENT_" + ultimate + id(name) + "_" + roman(book.group(2)));
        }
        int quantity = 1;
        Matcher q = QUANTITY.matcher(item);
        if (q.matches()) {
            item = q.group(1);
            try {
                quantity = Integer.parseInt(q.group(2).replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        if (item.endsWith(" Essence")) {
            double bonus = item.startsWith("Crimson") ? essenceBonus : 1;
            return Sky2MPriceTooltip.unitPrice("ESSENCE_" + id(item.substring(0, item.length() - 8))) * quantity * bonus;
        }
        if (item.endsWith(" Shard")) return Sky2MPriceTooltip.unitPrice("SHARD_" + id(item.substring(0, item.length() - 6))) * quantity;
        if (item.endsWith(" Dye")) return Sky2MPriceTooltip.unitPrice("DYE_" + id(item.substring(0, item.length() - 4))) * quantity;
        if (c.salvagePrices && SALVAGE.matcher(item).find()) {
            int essence = item.contains("Molten") ? 600 : STAR_ESSENCE[Math.min(stars, STAR_ESSENCE.length - 1)];
            return Sky2MPriceTooltip.unitPrice("ESSENCE_CRIMSON") * essence * essenceBonus * quantity;
        }
        String itemId = switch (item) {
            case "Hellstorm Wand" -> "HELLSTORM_STAFF";
            case "Aurora Staff" -> "RUNIC_STAFF";
            default -> id(item);
        };
        return Sky2MPriceTooltip.unitPrice(itemId) * quantity;
    }

    private static String id(String name) {
        return name.replace("'s", "").replace(" ", "_").toUpperCase(Locale.ROOT);
    }

    private static int roman(String numeral) {
        if (numeral.chars().allMatch(Character::isDigit)) return Integer.parseInt(numeral);
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

    private static void renderChest(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (chests.isEmpty()) return;
        var accessor = (com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor) screen;
        Slot hovered = accessor.getFocusedSlot();
        Chest chest = hovered != null ? chests.get(hovered.index) : null;
        if (chest == null) {
            for (int index : CHEST_SLOTS) {
                chest = chests.get(index);
                if (chest != null) break;
            }
        }
        if (chest == null) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        List<Entry> items = new ArrayList<>(chest.items());
        items.sort(Comparator.comparingDouble(Entry::price).reversed());
        int width = 150;
        for (Entry e : items) width = Math.max(width, font.width(e.name()) + font.width(fmt(e.price())) + 14);
        int lines = items.size() + 3;
        int x = accessor.getX() + accessor.getImageWidth() + 6;
        if (x + width + 4 > g.guiWidth()) x = Math.max(2, accessor.getX() - width - 10);
        int y = accessor.getY();
        g.fill(x - 4, y - 4, x + width + 4, y + lines * 10 + 8, 0xD0101010);
        int row = y;
        for (Entry e : items) {
            g.text(font, e.name(), x, row, 0xFFFFFFFF, true);
            String price = fmt(e.price());
            g.text(font, price, x + width - font.width(price), row, 0xFFAAAAAA, true);
            row += 10;
        }
        row += 4;
        g.text(font, "Key Cost:", x, row, 0xFFFF5555, true);
        String cost = fmt(chest.keyCost());
        g.text(font, cost, x + width - font.width(cost), row, 0xFFFF5555, true);
        row += 10;
        g.text(font, "Profit:", x, row, 0xFF55FF55, true);
        String profit = fmt(chest.profit());
        g.text(font, profit, x + width - font.width(profit), row, chest.profit() >= 0 ? 0xFF55FF55 : 0xFFFF5555, true);
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }
}
