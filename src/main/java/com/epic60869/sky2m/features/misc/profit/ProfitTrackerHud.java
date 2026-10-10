package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Profit Tracker HUD as Skysoft draws it (features/profit/ProfitTrackerRenderable.kt, ProfitTrackerHud.kt and
 * utils/gui/Overlay*Style.kt, LGPL-3.0): a bold yellow title, item rows with a small icon, the name in its rarity colour,
 * "x<amount>" (bright green for a moment when it changes) and the value in gold on the right, then grey labels with
 * right-aligned values (gold coins, red costs, green or red profit, yellow actions, aqua uptime). With a menu open it's
 * drawn over the menu with clickable Display Mode, Price Source and Reset (with Cancel / Confirm) lines, and its items
 * scroll.
 */
final class ProfitTrackerHud {
    private static final int TITLE_HEIGHT = 12;
    private static final int ROW_HEIGHT = 11;
    private static final int ITEM_ROW_HEIGHT = 13;
    private static final int ITEM_TEXT_Y = 2;
    private static final float ICON_SCALE = 0.75f;
    private static final int ICON_TEXT_OFFSET = 14;
    private static final int VALUE_GAP = 8;
    private static final int QUANTITY_GAP = 4;
    private static final int PADDING = 5;
    private static final int MINIMUM_WIDTH = 145;
    private static final int MAXIMUM_NAME_LENGTH = 20;
    private static final int BACKGROUND = 0xB0101010;
    private static final int OUTLINE = 0x80505050;
    private static final int CONTROL_HOVER = 0x20FFFFFF;
    private static final String KERNEL_ITEM = "ENCHANTMENT_FEAST_1";
    private static final int KERNELS_PER_ITEM = 25;
    private static final ThreadLocal<DecimalFormat> SHORT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.US)));

    enum Control { PERIOD, PRICE_SOURCE, RESET, CANCEL, CONFIRM }

    /** A line of the tracker: Skysoft's ProfitLine. */
    private record Row(String left, String middle, String right, ItemStack icon, int height, int textY, Control control,
                       Control secondary, boolean centered, String leading, int reservedWidth, int leftColumnWidth) {
        static Row text(String left, String right) {
            return new Row(left, null, right, null, ROW_HEIGHT, 0, null, null, false, null, 0, textWidth(left));
        }

        int leadingWidth() {
            return leading == null ? 0 : (reservedWidth > 0 ? reservedWidth : textWidth(leading));
        }

        int contentOffset() {
            return leadingWidth() + (leading == null ? 0 : QUANTITY_GAP);
        }

        int width() {
            int middleWidth = middle == null ? 0 : (reservedWidth > 0 ? reservedWidth : textWidth(middle)) + QUANTITY_GAP;
            return contentOffset() + (icon == null ? 0 : ICON_TEXT_OFFSET) + leftColumnWidth + middleWidth
                + (right == null ? 0 : textWidth(right) + VALUE_GAP);
        }
    }

    private record Area(Control control, int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private static final Map<ProfitTracker.Preset, Integer> scroll = new EnumMap<>(ProfitTracker.Preset.class);
    private static final Map<ProfitTracker.Preset, Integer> widths = new EnumMap<>(ProfitTracker.Preset.class);
    private static long resetPendingUntil;
    /** Where the in-menu tracker was last drawn (screen space) and its controls, for clicks and scrolling. */
    private static final List<Area> areas = new ArrayList<>();
    private static int[] bounds;
    private static ProfitTracker.Preset boundsPreset;

    private ProfitTrackerHud() {}

    static void init() {
        for (ProfitTracker.Preset preset : ProfitTracker.Preset.values()) {
            Sky2MHuds.registerCustom(ProfitTracker.hudId(preset), preset.displayName + " Profit Tracker", () -> ProfitTracker.enabled(preset),
                new Sky2MHuds.CustomHud() {
                    @Override
                    public int width() {
                        return layoutWidth(preset, rows(preset, false));
                    }

                    @Override
                    public int height() {
                        return layoutHeight(preset, rows(preset, false));
                    }

                    @Override
                    public boolean visible() {
                        return ProfitTracker.shownPreset() == preset && !ProfitTracker.containerOpen();
                    }

                    @Override
                    public void render(GuiGraphicsExtractor graphics, boolean preview) {
                        draw(graphics, preset, rows(preset, false), null, null);
                    }
                }, 8, 150);
        }
        ProfitTracker.registerScreenHooks();
    }

    // ------------------------------------------------------------------------------------------------ rows

    private static FeatureConfigs.ProfitTrackerSettings config(ProfitTracker.Preset preset) {
        return ProfitTracker.config(preset);
    }

    private static ProfitTracker.Period period(ProfitTracker.Preset preset) {
        FeatureConfigs.ProfitTrackerSettings c = config(preset);
        return c == null || c.period == null ? ProfitTracker.Period.SESSION : c.period;
    }

    private record Item(String id, String name, ItemStack stack, long amount, Double value) {}

    private static final Map<String, List<Row>> ROW_CACHE = new java.util.HashMap<>();
    private static int rowCacheTick = -1;

    /** The rows, built once per client tick per tracker (the HUD asks for its size and contents every frame). */
    private static List<Row> rows(ProfitTracker.Preset preset, boolean inventoryOpen) {
        int tick = ProfitTracker.tickCount();
        if (tick != rowCacheTick) {
            ROW_CACHE.clear();
            rowCacheTick = tick;
        }
        return ROW_CACHE.computeIfAbsent(preset.name() + inventoryOpen, k -> buildRows(preset, inventoryOpen));
    }

    private static List<Row> buildRows(ProfitTracker.Preset preset, boolean inventoryOpen) {
        FeatureConfigs.ProfitTrackerSettings c = config(preset);
        List<Row> rows = new ArrayList<>();
        if (c == null) return rows;
        ProfitTracker.Period period = period(preset);
        ProfitTracker.Stats stats = ProfitTracker.stats(preset, period);
        FeatureConfigs.ProfitPriceSource source = c.settings.priceSource == null ? FeatureConfigs.ProfitPriceSource.INSTANT_SELL : c.settings.priceSource;

        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Long> e : stats.items.entrySet()) {
            double unit = Sky2MPriceTooltip.price(e.getKey(), source);
            items.add(new Item(e.getKey(), name(e.getKey()), RepoItems.itemStack(e.getKey()), e.getValue(), unit > 0 ? unit * e.getValue() : null));
        }
        items.sort(Comparator.comparingDouble((Item i) -> i.value() == null ? -1 : i.value()).reversed());

        double kernel = 0;
        boolean kernelUnknown = false;
        if (preset == ProfitTracker.Preset.FARMING && stats.kernels > 0) {
            double book = Sky2MPriceTooltip.price(KERNEL_ITEM, source);
            kernel = book * stats.kernels / KERNELS_PER_ITEM;
            kernelUnknown = book <= 0;
        }
        double revenue = stats.coins + kernel;
        boolean unknown = kernelUnknown;
        for (Item item : items) {
            if (item.value() == null) unknown = true;
            else revenue += item.value();
        }
        String profitLabel = unknown ? "Known Profit" : "Total Profit";
        double profit = revenue - stats.costs;

        int max = Math.clamp(c.settings.maximumItems, 1, 15);
        int offset = Math.clamp(scroll.getOrDefault(preset, 0), 0, Math.max(0, items.size() - max));
        scroll.put(preset, offset);
        List<Item> shown = items.subList(offset, Math.min(items.size(), offset + max));

        rows.add(new Row("§e§l" + preset.displayName + " Profit", null, null, null, TITLE_HEIGHT, 0, null, null, false, null, 0, textWidth("§e§l" + preset.displayName + " Profit")));
        if (shown.isEmpty()) {
            rows.add(Row.text("§7No tracked drops yet.", null));
        } else {
            boolean quantityLeft = c.details.quantityPosition == FeatureConfigs.ProfitQuantityPosition.LEFT;
            int nameColumn = 0;
            List<String> names = new ArrayList<>();
            for (Item item : shown) {
                String name = truncate(item.name());
                names.add(name);
                nameColumn = Math.max(nameColumn, textWidth(name));
            }
            for (int i = 0; i < shown.size(); i++) {
                Item item = shown.get(i);
                String name = names.get(i);
                boolean highlighted = c.details.highlightChanges && ProfitTracker.HIGHLIGHTS.containsKey(preset.name() + ":" + item.id());
                String count = "§7x" + (highlighted ? "§a§l" : "") + String.format(Locale.US, "%,d", item.amount());
                int countWidth = textWidth("§7x§a§l" + String.format(Locale.US, "%,d", item.amount()));
                String value = item.value() == null ? "§8Unknown" : "§6" + coins(item.value());
                rows.add(new Row(name, quantityLeft ? null : count, value, c.details.showItemIcons ? item.stack() : null,
                    ITEM_ROW_HEIGHT, ITEM_TEXT_Y, null, null, false, quantityLeft ? count : null, countWidth,
                    quantityLeft ? textWidth(name) : nameColumn));
            }
        }
        int below = items.size() - offset - shown.size();
        if (below > 0) rows.add(centered("§7" + below + " more..."));
        else if (offset > 0) rows.add(centered("§7" + offset + " above..."));

        double hours = stats.activeMs / 3_600_000d;
        double perHour = hours > 0 ? profit / hours : 0;
        List<FeatureConfigs.ProfitSummaryLine> lines = c.details.summaryLines == null ? List.of() : c.details.summaryLines;
        for (FeatureConfigs.ProfitSummaryLine line : lines.stream().distinct().toList()) {
            if (line == null) continue;
            switch (line) {
                case COINS -> {
                    if (stats.coins > 0) rows.add(Row.text("§7" + preset.coinLabel, "§6" + coins(stats.coins)));
                }
                case KERNEL_PROFIT -> {
                    if (preset == ProfitTracker.Preset.FARMING && stats.kernels > 0) rows.add(Row.text("§7Kernel Profit", kernelUnknown ? "§8Unknown" : "§6" + coins(kernel)));
                }
                case QUEST_COSTS -> {
                    if (stats.costs > 0) rows.add(Row.text("§7Quest Costs", "§c-" + coins(stats.costs)));
                }
                case TOTAL_PROFIT -> rows.add(Row.text("§7" + profitLabel, (profit >= 0 ? "§a" : "§c") + signed(profit)));
                case PROFIT_PER_HOUR -> rows.add(Row.text("§7" + (profitLabel.equals("Total Profit") ? "Profit/h" : profitLabel + "/h"),
                    (perHour >= 0 ? "§a" : "§c") + signed(perHour)));
                case ACTIONS -> rows.add(Row.text("§7" + preset.actionLabel, "§e" + String.format(Locale.US, "%,d", stats.actions)));
                case UPTIME -> rows.add(Row.text("§7Uptime", "§b" + uptime(stats.activeMs)
                    + (ProfitTracker.paused(preset, System.currentTimeMillis()) ? " §c(paused)" : "")));
            }
        }
        if (inventoryOpen) {
            rows.add(control("§7Display Mode §a§l[" + period.label + "]", Control.PERIOD));
            rows.add(control("§7Price Source §e§l[" + source + "]", Control.PRICE_SOURCE));
            if (System.currentTimeMillis() < resetPendingUntil) {
                rows.add(new Row("§c[Cancel]", null, "§a[Confirm]", null, ROW_HEIGHT, 0, Control.CANCEL, Control.CONFIRM, false, null, 0, textWidth("§c[Cancel]")));
            } else {
                rows.add(control("§c[Reset " + period.label + "]", Control.RESET));
            }
        }
        return rows;
    }

    private static Row centered(String text) {
        return new Row(text, null, null, null, ROW_HEIGHT, 0, null, null, true, null, 0, textWidth(text));
    }

    private static Row control(String text, Control control) {
        return new Row(text, null, null, null, ROW_HEIGHT, 0, control, null, false, null, 0, textWidth(text));
    }

    /** The item's name in its rarity colour, without "Enchanted" spelled out in full when it's long. */
    private static String name(String id) {
        String name = RepoItems.displayName(id);
        String plain = name == null ? id.replace('_', ' ') : ChatFormatting.stripFormatting(name);
        ChatFormatting colour = RepoItems.tierColour(RepoItems.tier(id));
        return colour + plain;
    }

    private static String truncate(String legacy) {
        String plain = ChatFormatting.stripFormatting(legacy);
        if (plain == null || plain.length() <= MAXIMUM_NAME_LENGTH) return legacy;
        return legacy.substring(0, legacy.length() - (plain.length() - MAXIMUM_NAME_LENGTH + 3)) + "...";
    }

    // ------------------------------------------------------------------------------------------------ layout

    private static boolean background(ProfitTracker.Preset preset) {
        FeatureConfigs.ProfitTrackerSettings c = config(preset);
        return c != null && c.details.showBackground;
    }

    private static int layoutWidth(ProfitTracker.Preset preset, List<Row> rows) {
        int padding = background(preset) ? PADDING : 0;
        int content = MINIMUM_WIDTH;
        for (Row row : rows) content = Math.max(content, row.width());
        int width = content + padding * 2;
        // Skysoft's ProfitTrackerWidthState: grows at once, shrinks only after it's been narrower for a while.
        int previous = widths.getOrDefault(preset, 0);
        if (width >= previous || Minecraft.getInstance().level == null || Minecraft.getInstance().level.getGameTime() % 100 == 0) {
            widths.put(preset, width);
            return width;
        }
        return previous;
    }

    private static int layoutHeight(ProfitTracker.Preset preset, List<Row> rows) {
        int padding = background(preset) ? PADDING : 0;
        int height = padding * 2;
        for (Row row : rows) height += row.height();
        return height;
    }

    /** Draws the tracker at (0, 0); with {@code mouseX} given, hovered controls light up. Returns the controls drawn. */
    private static List<Area> draw(GuiGraphicsExtractor g, ProfitTracker.Preset preset, List<Row> rows, Integer mouseX, Integer mouseY) {
        Font font = Minecraft.getInstance().font;
        int width = layoutWidth(preset, rows), height = layoutHeight(preset, rows);
        boolean background = background(preset);
        int padding = background ? PADDING : 0;
        if (background) {
            g.fill(0, 0, width, height, BACKGROUND);
            g.outline(0, 0, width, height, OUTLINE);
        }
        List<Area> drawn = new ArrayList<>();
        int y = padding;
        for (Row row : rows) {
            int rightWidth = row.right() == null ? 0 : textWidth(row.right());
            if (row.control() != null) {
                int primaryWidth = row.secondary() == null ? row.width() : textWidth(row.left());
                Area area = new Area(row.control(), padding, y, primaryWidth, row.height());
                drawn.add(area);
                if (mouseX != null && area.contains(mouseX, mouseY)) g.fill(area.x(), area.y(), area.x() + area.w(), area.y() + area.h(), CONTROL_HOVER);
            }
            if (row.secondary() != null) {
                Area area = new Area(row.secondary(), width - padding - rightWidth, y, rightWidth, row.height());
                drawn.add(area);
                if (mouseX != null && area.contains(mouseX, mouseY)) g.fill(area.x(), area.y(), area.x() + area.w(), area.y() + area.h(), CONTROL_HOVER);
            }
            if (row.leading() != null) g.text(font, legacy(row.leading()), padding, y + row.textY(), 0xFFFFFFFF, true);
            if (row.icon() != null) {
                g.pose().pushMatrix();
                g.pose().translate(padding + row.contentOffset(), y);
                g.pose().scale(ICON_SCALE, ICON_SCALE);
                g.item(row.icon(), 0, 0);
                g.pose().popMatrix();
            }
            int textX = row.centered() ? (width - textWidth(row.left())) / 2
                : padding + row.contentOffset() + (row.icon() == null ? 0 : ICON_TEXT_OFFSET);
            g.text(font, legacy(row.left()), textX, y + row.textY(), 0xFFFFFFFF, true);
            if (row.middle() != null) g.text(font, legacy(row.middle()), textX + row.leftColumnWidth() + QUANTITY_GAP, y + row.textY(), 0xFFFFFFFF, true);
            if (row.right() != null) g.text(font, legacy(row.right()), width - padding - rightWidth, y + row.textY(), 0xFFFFFFFF, true);
            y += row.height();
        }
        return drawn;
    }

    // ------------------------------------------------------------------------------------------------ in menus

    static void renderInScreen(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        areas.clear();
        bounds = null;
        ProfitTracker.Preset preset = ProfitTracker.shownPreset();
        Screen screen = Minecraft.getInstance().gui.screen();
        // Not over the Spirit Leap menu, which replaces the chest.
        if (preset == null || screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c && com.epic60869.sky2m.features.dungeons.LeapMenu.isActive(c)) return;
        List<Row> rows = rows(preset, true);
        Sky2MHuds.Placement p = ProfitTracker.placement(preset);
        int w = layoutWidth(preset, rows), h = layoutHeight(preset, rows);
        int x = Sky2MHuds.mapX(p.x, Math.round(w * p.scale));
        int y = Sky2MHuds.mapY(p.y, Math.round(h * p.scale));
        int localX = Math.round((mouseX - x) / p.scale), localY = Math.round((mouseY - y) / p.scale);
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(p.scale, p.scale);
        for (Area area : draw(g, preset, rows, localX, localY)) {
            areas.add(new Area(area.control(), x + Math.round(area.x() * p.scale), y + Math.round(area.y() * p.scale),
                Math.round(area.w() * p.scale), Math.round(area.h() * p.scale)));
        }
        g.pose().popMatrix();
        bounds = new int[]{x, y, Math.round(w * p.scale), Math.round(h * p.scale)};
        boundsPreset = preset;
    }

    /** A click on one of the tracker's controls; true if it was one (the menu doesn't get it). */
    static boolean click(double mouseX, double mouseY) {
        ProfitTracker.Preset preset = boundsPreset;
        if (preset == null || bounds == null) return false;
        FeatureConfigs.ProfitTrackerSettings c = config(preset);
        if (c == null) return false;
        for (Area area : areas) {
            if (!area.contains(mouseX, mouseY)) continue;
            switch (area.control()) {
                case PERIOD -> c.period = ProfitTracker.Period.values()[(period(preset).ordinal() + 1) % ProfitTracker.Period.values().length];
                case PRICE_SOURCE -> {
                    FeatureConfigs.ProfitPriceSource[] sources = FeatureConfigs.ProfitPriceSource.values();
                    c.settings.priceSource = sources[(c.settings.priceSource.ordinal() + 1) % sources.length];
                }
                case RESET -> resetPendingUntil = System.currentTimeMillis() + 5_000L;
                case CANCEL -> resetPendingUntil = 0;
                case CONFIRM -> {
                    resetPendingUntil = 0;
                    ProfitTracker.reset(preset, period(preset));
                }
            }
            ROW_CACHE.clear();
            com.epic60869.sky2m.Sky2MConfig.saveCurrent(com.epic60869.sky2m.Sky2MConfig.current());
            ProfitTracker.markDirty();
            return true;
        }
        return false;
    }

    /** Scrolls the tracker's items when the mouse is over it; true if it did. */
    static boolean scroll(double mouseX, double mouseY, double amount) {
        ProfitTracker.Preset preset = boundsPreset;
        if (preset == null || bounds == null || amount == 0) return false;
        if (mouseX < bounds[0] || mouseX >= bounds[0] + bounds[2] || mouseY < bounds[1] || mouseY >= bounds[1] + bounds[3]) return false;
        scroll.merge(preset, amount < 0 ? 1 : -1, Integer::sum);
        ROW_CACHE.clear();
        if (scroll.get(preset) < 0) scroll.put(preset, 0);
        return true;
    }

    // ------------------------------------------------------------------------------------------------ text

    private static Component legacy(String text) {
        return SbcItems.parseLegacy(text);
    }

    private static int textWidth(String text) {
        return text == null ? 0 : Minecraft.getInstance().font.width(legacy(text));
    }

    /** Skysoft's coinFormat: 950, 12.3k, 4.5m, 1.2b. */
    static String coins(double value) {
        double abs = Math.abs(value);
        DecimalFormat format = SHORT.get();
        if (abs >= 1_000_000_000) return format.format(value / 1_000_000_000) + "b";
        if (abs >= 1_000_000) return format.format(value / 1_000_000) + "m";
        if (abs >= 1_000) return format.format(value / 1_000) + "k";
        return format.format(value);
    }

    private static String signed(double value) {
        return (value >= 0 ? "+" : "-") + coins(Math.abs(value));
    }

    /** "1h 2m 3s", "2m 3s" or "3s". */
    private static String uptime(long ms) {
        long seconds = ms / 1000, hours = seconds / 3600, minutes = seconds / 60 % 60;
        StringBuilder out = new StringBuilder();
        if (hours > 0) out.append(hours).append("h ");
        if (minutes > 0 || hours > 0) out.append(minutes).append("m ");
        return out.append(seconds % 60).append("s").toString();
    }
}
