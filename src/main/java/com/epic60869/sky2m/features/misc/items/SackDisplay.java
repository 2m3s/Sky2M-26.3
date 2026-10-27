package com.epic60869.sky2m.features.misc.items;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.FeatureConfigs.SackPriceSource;
import com.epic60869.sky2m.features.FeatureConfigs.SackSort;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.mixin.gui.Sky2MContainerScreenAccessor;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sack Display: while a sack menu is open, a panel beside it lists what's in that sack (amount, capacity, price of
 * one and of them all) with the sack's total. Each sack keeps its own price source, picked with the panel's Price
 * button and saved in config/sky2m/sack-display.json; sacks without one use the Default Price Source setting.
 * <p>
 * Amounts come from the menu's item lore: "Stored: 1,234/80k" on most sacks, and one line per quality on the
 * Gemstones Sack ("Rough: 1,234", "Flawed: 56/..."), which become a row each.
 */
public final class SackDisplay {
    /** "Stored: 1,234/80k": the amount and, after the slash, how many the sack holds. */
    private static final Pattern STORED = Pattern.compile("^Stored: ([\\d,]+)(?:/(\\S+))?.*$");
    /** One quality of a gemstone in the Gemstones Sack: "Rough: 1,234" (maybe "/2,240" or "(...)" after it). */
    private static final Pattern GEM_QUALITY = Pattern.compile("^(Rough|Flawed|Fine|Flawless|Perfect)(?: Gemstones?)?: ([\\d,]+)(?:/(\\S+))?.*$");
    /** A page number after a menu's name: "Gemstones Sack (1/2)". */
    private static final Pattern PAGE = Pattern.compile("\\s*\\(\\d+/\\d+\\)$");
    private static final Gson GSON = new Gson();

    private static final int ROW_HEIGHT = 12;
    private static final int PAD = 6;
    private static final int NAME_MAX = 120;
    private static final long REFRESH_MS = 250;

    /** One line of the panel: an item (or one gemstone quality) in the sack. */
    public record Row(ItemStack icon, String name, String id, long amount, long capacity, String capacityText,
                      double unitPrice, double value, boolean fallback) {}

    /** What the panel shows for one sack menu. */
    public record View(String sack, SackPriceSource source, boolean ownSource, List<Row> rows, double total, long items) {}

    /** Lower-case sack name -> the price source picked for it. */
    private static final Map<String, SackPriceSource> SOURCES = new ConcurrentHashMap<>();
    private static Path file;

    // The open sack menu's panel (only one menu is open at a time).
    private static View view;
    private static long lastRefresh;
    private static int scroll;
    private static int[] priceButton = new int[4];
    private static int[] resetButton = new int[4];
    private static int[] sortButton = new int[4];
    private static int[] panelBox = new int[4];

    private SackDisplay() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("sack-display.json");
        load();
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            if (sackName(screen.getTitle().getString()) == null) return;
            view = null;
            scroll = 0;
            lastRefresh = 0;
            panelBox = new int[4];
            Sky2MPriceTooltip.warmup();
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> render(g, container));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event.x(), event.y(), event.button()));
            ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, horizontal, vertical) -> !scroll(x, y, vertical));
        });
    }

    private static FeatureConfigs.SackDisplay config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.sackDisplay;
    }

    private static boolean enabled() {
        FeatureConfigs.SackDisplay c = config();
        return c != null && c.enabled && Compat.isOnSkyblock();
    }

    // ----- Sack menus -----

    /** The sack a menu title belongs to ("Agronomy Sack", "Gemstones Sack"), or null when it isn't a sack menu. */
    public static String sackName(String title) {
        String plain = ChatFormatting.stripFormatting(title);
        if (plain == null) return null;
        plain = PAGE.matcher(plain.trim()).replaceAll("").trim();
        if (!plain.endsWith("Sack") || plain.equals("Sack of Sacks")) return null;
        return plain;
    }

    /** The open menu's sack as the panel lists it, worked out now (for tests and the panel). Null if not a sack menu. */
    public static View view(AbstractContainerScreen<?> screen) {
        String sack = sackName(screen.getTitle().getString());
        if (sack == null) return null;
        FeatureConfigs.SackDisplay c = config();
        boolean hideEmpty = c == null || c.hideEmpty;
        SackSort sort = c == null ? SackSort.VALUE : c.sortBy;
        SackPriceSource source = sourceFor(sack);
        List<Row> rows = new ArrayList<>();
        int containerSize = screen.getMenu().slots.size() - 36;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.index >= containerSize) break;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            String name = plain(stack.getHoverName().getString());
            for (Component line : lore.lines()) {
                String text = plain(line.getString());
                Matcher stored = STORED.matcher(text);
                if (stored.matches()) {
                    rows.add(row(stack, name, itemId(stack, name), parse(stored.group(1)), stored.group(2), source));
                    break;
                }
                Matcher gem = GEM_QUALITY.matcher(text);
                if (gem.matches()) {
                    String quality = gem.group(1);
                    String type = gemType(name);
                    String id = type.isEmpty() ? null : (quality + "_" + type + "_GEM").toUpperCase(Locale.ROOT);
                    rows.add(row(stack, quality + " " + (type.isEmpty() ? name : capitalise(type) + " Gemstone"), id,
                        parse(gem.group(2)), gem.group(3), source));
                }
            }
        }
        if (hideEmpty) rows.removeIf(r -> r.amount() <= 0);
        Comparator<Row> order = switch (sort) {
            case VALUE -> Comparator.comparingDouble(Row::value).reversed().thenComparing(Comparator.comparingLong(Row::amount).reversed());
            case AMOUNT -> Comparator.comparingLong(Row::amount).reversed();
            case NAME -> Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT));
        };
        rows.sort(order);
        double total = 0;
        long items = 0;
        for (Row r : rows) {
            total += r.value();
            items += r.amount();
        }
        return new View(sack, source, SOURCES.containsKey(key(sack)), List.copyOf(rows), total, items);
    }

    private static Row row(ItemStack stack, String name, String id, long amount, String capacityText, SackPriceSource source) {
        long capacity = capacityText == null ? -1 : parseShort(capacityText);
        double unit = 0;
        boolean fallback = false;
        if (id != null) {
            unit = price(id, source);
            if (unit <= 0) {
                // Not sold that way (not on the bazaar, no NPC price): its lowest BIN, else what an NPC pays.
                unit = positive(Sky2MPriceTooltip.lowestBinPrice(id));
                if (unit <= 0) unit = positive(Sky2MPriceTooltip.npcSellPrice(id));
                fallback = unit > 0;
            }
        }
        return new Row(stack, name, id, amount, capacity, capacityText == null ? "" : capacityText, unit, unit * Math.max(0, amount), fallback);
    }

    private static double price(String id, SackPriceSource source) {
        return switch (source) {
            case NPC_SELL -> positive(Sky2MPriceTooltip.npcSellPrice(id));
            case INSTA_SELL -> positive(Sky2MPriceTooltip.bazaarSellPrice(id));
            // The bazaar has one price for both: the lowest sell offer, which is what an insta-buy pays and what a
            // new sell offer would be listed at.
            case SELL_OFFER, INSTA_BUY -> positive(Sky2MPriceTooltip.bazaarBuyPrice(id));
            case LOWEST_BIN -> positive(Sky2MPriceTooltip.lowestBinPrice(id));
        };
    }

    /** The item id of a sack's item: from the item itself, else looked up by its name in the item repo. */
    private static String itemId(ItemStack stack, String name) {
        if (!Compat.neuName(stack).isEmpty()) {
            String id = Sky2MPriceTooltip.marketId(stack);
            if (!id.isEmpty()) return id;
        }
        return SacksValue.id(name.toLowerCase(Locale.ROOT));
    }

    /** "Ruby" from a Gemstones Sack item's name ("Ruby Gemstones", with or without its symbol). */
    private static String gemType(String name) {
        String letters = name.replaceAll("[^A-Za-z ]", " ").trim();
        int gem = letters.indexOf("Gemstone");
        String before = (gem >= 0 ? letters.substring(0, gem) : letters).trim();
        if (before.isEmpty()) return "";
        String[] words = before.split("\\s+");
        return words[words.length - 1];
    }

    // ----- Price source per sack -----

    private static String key(String sack) {
        return sack.toLowerCase(Locale.ROOT).trim();
    }

    /** The price source a sack uses: its own if one was picked, else the default setting. */
    public static SackPriceSource sourceFor(String sack) {
        SackPriceSource own = SOURCES.get(key(sack));
        if (own != null) return own;
        FeatureConfigs.SackDisplay c = config();
        return c == null || c.defaultSource == null ? SackPriceSource.INSTA_SELL : c.defaultSource;
    }

    /** Whether this sack has its own price source (not the default). */
    public static boolean hasOwnSource(String sack) {
        return SOURCES.containsKey(key(sack));
    }

    /** Gives a sack its own price source, or back to the default with null, and saves it. */
    public static void setSource(String sack, SackPriceSource source) {
        if (source == null) SOURCES.remove(key(sack));
        else SOURCES.put(key(sack), source);
        lastRefresh = 0;
        save();
    }

    private static void load() {
        try {
            if (file == null || !Files.exists(file)) return;
            Map<String, String> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, String>>() {}.getType());
            if (data == null) return;
            data.forEach((sack, source) -> {
                try {
                    SOURCES.put(key(sack), SackPriceSource.valueOf(source));
                } catch (IllegalArgumentException ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private static void save() {
        if (file == null) return;
        try {
            Map<String, String> data = new java.util.TreeMap<>();
            SOURCES.forEach((sack, source) -> data.put(sack, source.name()));
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    // ----- The panel -----

    private static void render(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
        if (!enabled()) {
            panelBox = new int[4];
            return;
        }
        long now = System.currentTimeMillis();
        if (view == null || now - lastRefresh >= REFRESH_MS) {
            lastRefresh = now;
            view = view(screen);
        }
        View v = view;
        if (v == null) return;
        FeatureConfigs.SackDisplay c = config();
        boolean compact = c == null || c.compactNumbers;
        boolean capacity = c == null || c.showCapacity;
        SackSort sort = c == null ? SackSort.VALUE : c.sortBy;
        Font font = Minecraft.getInstance().font;

        // Columns: icon, name, amount, price of one, value.
        int nameW = 0, amountW = 0, unitW = font.width("Each"), valueW = font.width("Value");
        List<String[]> cells = new ArrayList<>();
        for (Row r : v.rows()) {
            String name = r.name();
            if (font.width(name) > NAME_MAX) name = font.plainSubstrByWidth(name, NAME_MAX - font.width("..")) + "..";
            String amount = number(r.amount(), compact) + (capacity && !r.capacityText().isEmpty() ? "/" + r.capacityText() : "");
            String unit = r.unitPrice() > 0 ? (r.fallback() ? "~" : "") + coins(r.unitPrice(), compact, true) : "-";
            String value = r.unitPrice() > 0 ? coins(r.value(), compact, false) : "-";
            cells.add(new String[]{name, amount, unit, value});
            nameW = Math.max(nameW, font.width(name));
            amountW = Math.max(amountW, font.width(amount));
            unitW = Math.max(unitW, font.width(unit));
            valueW = Math.max(valueW, font.width(value));
        }

        Component title = Component.literal(v.sack()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        Component priceText = Component.literal("Price: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(v.source().toString() + " ▸").withStyle(v.ownSource() ? ChatFormatting.AQUA : ChatFormatting.WHITE));
        Component resetText = Component.literal("[Default]").withStyle(ChatFormatting.DARK_GRAY);
        Component sortText = Component.literal("Sort: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(sort.toString() + " ▸").withStyle(ChatFormatting.WHITE));
        Component totalText = Component.literal("Total: ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal(coins(v.total(), compact, false) + " coins").withStyle(ChatFormatting.YELLOW));
        Component itemsText = Component.literal(String.format(Locale.US, "%,d items", v.items())).withStyle(ChatFormatting.DARK_GRAY);

        int gap = 8;
        int rowsW = 14 + nameW + gap + amountW + gap + unitW + gap + valueW;
        int buttonsW = font.width(priceText) + (v.ownSource() ? 6 + font.width(resetText) : 0);
        int w = PAD * 2 + Math.max(Math.max(rowsW, font.width(title)), Math.max(Math.max(buttonsW, font.width(sortText)),
            font.width(totalText) + 8 + font.width(itemsText)));

        int headerH = 4 + 10 + 2 + 10 + 2 + 10 + 4;
        int footerH = 6 + 10 + 4;
        int maxRows = Math.max(1, (screen.height - 8 - headerH - footerH) / ROW_HEIGHT);
        int shown = Math.min(maxRows, Math.max(1, v.rows().size()));
        scroll = Math.max(0, Math.min(scroll, v.rows().size() - shown));
        int h = headerH + shown * ROW_HEIGHT + footerH;

        // Right of the menu (the hovered item's value panel uses the left), else left of it, else the screen's edge.
        var access = (Sky2MContainerScreenAccessor) screen;
        int left = access.sky2m$getLeftPos(), top = access.sky2m$getTopPos(), image = access.sky2m$getImageWidth();
        int x = left + image + 6;
        if (x + w > screen.width - 4) x = left - w - 6;
        if (x < 4) x = Math.max(4, screen.width - w - 4);
        int y = Math.clamp(top, 4, Math.max(4, screen.height - h - 4));
        panelBox = new int[]{x, y, w, h};

        g.nextStratum();
        g.fill(x, y, x + w, y + h, 0xE0101216);
        g.fill(x, y, x + w, y + 2, 0xFFFFAA00);
        g.outline(x, y, w, h, 0xFF2C313A);
        int line = y + 5;
        g.text(font, title, x + PAD, line, 0xFFFFFFFF, true);
        line += 12;
        g.text(font, priceText, x + PAD, line, 0xFFFFFFFF, true);
        priceButton = new int[]{x + PAD - 1, line - 1, font.width(priceText) + 2, 10};
        if (v.ownSource()) {
            int rx = x + PAD + font.width(priceText) + 6;
            g.text(font, resetText, rx, line, 0xFFFFFFFF, true);
            resetButton = new int[]{rx - 1, line - 1, font.width(resetText) + 2, 10};
        } else {
            resetButton = new int[4];
        }
        line += 12;
        g.text(font, sortText, x + PAD, line, 0xFFFFFFFF, true);
        sortButton = new int[]{x + PAD - 1, line - 1, font.width(sortText) + 2, 10};
        line += 12;
        g.fill(x + PAD, line - 2, x + w - PAD, line - 1, 0xFF2C313A);

        int amountX = x + PAD + 14 + nameW + gap;
        int unitRight = amountX + amountW + gap + unitW;
        int valueRight = x + w - PAD;
        if (v.rows().isEmpty()) {
            g.text(font, Component.literal("Nothing in this sack").withStyle(ChatFormatting.GRAY), x + PAD, line + 2, 0xFFFFFFFF, true);
        }
        for (int i = scroll; i < Math.min(v.rows().size(), scroll + shown); i++) {
            Row r = v.rows().get(i);
            String[] cell = cells.get(i);
            int ry = line + (i - scroll) * ROW_HEIGHT;
            g.pose().pushMatrix();
            g.pose().translate(x + PAD, ry);
            g.pose().scale(0.625f, 0.625f);
            g.item(r.icon(), 0, 0);
            g.pose().popMatrix();
            g.text(font, Component.literal(cell[0]).withStyle(ChatFormatting.WHITE), x + PAD + 14, ry + 1, 0xFFFFFFFF, true);
            g.text(font, Component.literal(cell[1]).withStyle(r.capacity() > 0 && r.amount() >= r.capacity() ? ChatFormatting.RED : ChatFormatting.GRAY),
                amountX, ry + 1, 0xFFFFFFFF, true);
            g.text(font, Component.literal(cell[2]).withStyle(ChatFormatting.DARK_GRAY), unitRight - font.width(cell[2]), ry + 1, 0xFFFFFFFF, true);
            g.text(font, Component.literal(cell[3]).withStyle(ChatFormatting.GOLD), valueRight - font.width(cell[3]), ry + 1, 0xFFFFFFFF, true);
        }
        if (v.rows().size() > shown) {
            // A thin scroll bar on the right edge.
            int trackTop = line, trackH = shown * ROW_HEIGHT;
            int barH = Math.max(6, trackH * shown / v.rows().size());
            int barY = trackTop + (trackH - barH) * scroll / Math.max(1, v.rows().size() - shown);
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF8A8F98);
        }
        line += shown * ROW_HEIGHT + 2;
        g.fill(x + PAD, line, x + w - PAD, line + 1, 0xFF2C313A);
        line += 4;
        g.text(font, totalText, x + PAD, line, 0xFFFFFFFF, true);
        g.text(font, itemsText, x + w - PAD - font.width(itemsText), line, 0xFFFFFFFF, true);
    }

    private static boolean inside(int[] box, double mx, double my) {
        return box[2] > 0 && mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3];
    }

    /** A click on the panel: the Price, Default and Sort buttons. True when the click was the panel's. */
    private static boolean click(double mx, double my, int button) {
        View v = view;
        if (v == null || !enabled() || !inside(panelBox, mx, my)) return false;
        if (inside(priceButton, mx, my)) {
            SackPriceSource[] all = SackPriceSource.values();
            int step = button == 1 ? all.length - 1 : 1;
            setSource(v.sack(), all[(v.source().ordinal() + step) % all.length]);
        } else if (inside(resetButton, mx, my)) {
            setSource(v.sack(), null);
        } else if (inside(sortButton, mx, my)) {
            FeatureConfigs.SackDisplay c = config();
            if (c != null) {
                SackSort[] all = SackSort.values();
                int step = button == 1 ? all.length - 1 : 1;
                c.sortBy = all[(c.sortBy.ordinal() + step) % all.length];
                Sky2MConfig.saveCurrent(Sky2MConfig.current());
                lastRefresh = 0;
            }
        }
        return true;
    }

    private static boolean scroll(double mx, double my, double amount) {
        if (view == null || !enabled() || !inside(panelBox, mx, my)) return false;
        scroll = Math.max(0, scroll - (int) Math.signum(amount) * 3);
        return true;
    }

    // ----- Numbers -----

    private static String number(long value, boolean compact) {
        return compact && value >= 10_000 ? CombatFeatures.formatCoins(value) : String.format(Locale.US, "%,d", value);
    }

    private static String coins(double value, boolean compact, boolean each) {
        if (compact && value >= 1_000) return CombatFeatures.formatCoins(value);
        if (each && value < 100) return String.format(Locale.US, "%,.1f", value);
        return String.format(Locale.US, "%,.0f", value);
    }

    private static String plain(String text) {
        String plain = ChatFormatting.stripFormatting(text);
        return plain == null ? "" : plain.trim();
    }

    private static String capitalise(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    private static long parse(String digits) {
        try {
            return Long.parseLong(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "80k", "1.2M", "2,240" -> 80000, 1200000, 2240; -1 when it isn't a number. */
    private static long parseShort(String text) {
        String t = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        if (t.endsWith("k")) mult = 1_000;
        else if (t.endsWith("m")) mult = 1_000_000;
        else if (t.endsWith("b")) mult = 1_000_000_000;
        if (mult > 1) t = t.substring(0, t.length() - 1);
        try {
            return Math.round(Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double positive(Double value) {
        return value == null || value < 0 ? 0 : value;
    }
}
