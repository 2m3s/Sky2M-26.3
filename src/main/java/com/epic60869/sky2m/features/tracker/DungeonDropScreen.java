package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 tracker, drawn like /s2 rnglist and /s2 hour ({@link Sky2MGuiStyle}). Category tabs across the top: Today is the
 * day's profit across everything ({@link TrackerDrops#today}); Dungeons is
 * {@link DungeonDropTracker} (chest loot since turning it on, floors on the left with how many chests you opened on
 * each, and what the chests cost); Slayer, Fishing, Diana, Mining, Farming and Foraging are what Sky2M's own trackers
 * counted ({@link TrackerDrops}). The list is most expensive first; RNG only (the default) hides anything under
 * {@link #RNG_PRICE} each. Search narrows it. There's no reset button here: /s2 tracker reset asks in chat.
 */
public class DungeonDropScreen extends Screen {
    private static final int ROW = 14;
    private static final int HEAD = 12;
    private static final int LEFT_W = 132;
    /** RNG only hides items worth less than this each. */
    static final double RNG_PRICE = 100_000;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ROOT);
    private static final String DUNGEONS = "Dungeons";
    private static final String TODAY = "Today";

    /** Kept while the game runs, like the RNG screen's search. */
    private static String search = "";
    private static String category = DUNGEONS;
    /** The floor (Dungeons) or part (a slayer boss), null = all. */
    private static String part;
    private static boolean rngOnly = true;

    private final Screen parent;
    private EditBox searchBox;
    private double listScroll, partScroll;
    /** The open tab's tracker counts, read once per tab. */
    private List<TrackerDrops.Part> parts = List.of();
    /** The Today tab's activities, read when the tab opens. */
    private List<TrackerDrops.Day> days = List.of();
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    public DungeonDropScreen(Screen parent) {
        super(Component.literal("Drop Tracker"));
        this.parent = parent;
        loadCategory();
    }

    private void loadCategory() {
        parts = category.equals(DUNGEONS) || category.equals(TODAY) ? List.of() : TrackerDrops.load(category);
        days = category.equals(TODAY) ? TrackerDrops.today() : List.of();
    }

    /** The Today activities picked in the left box (all when none is). */
    private List<TrackerDrops.Day> pickedDays() {
        return part == null ? days : days.stream().filter(d -> d.name().equals(part)).toList();
    }

    private void pickCategory(String c) {
        if (c.equals(category)) return;
        category = c;
        part = null;
        listScroll = partScroll = 0;
        loadCategory();
    }

    // ------------------------------------------------------------------------------------------------ layout

    private int panelW() {
        return Math.min(560, width - 20);
    }

    private int panelX() {
        return (width - panelW()) / 2;
    }

    private int panelH() {
        return Math.max(160, Math.min(340, height - 12));
    }

    private int panelY() {
        return Math.max(6, (height - panelH()) / 2);
    }

    private int bodyTop() {
        return panelY() + 50;
    }

    private int bodyBottom() {
        return panelY() + panelH() - 32;
    }

    private int listX() {
        return panelX() + 8 + LEFT_W + 6;
    }

    private int listW() {
        return panelX() + panelW() - 8 - listX();
    }

    @Override
    protected void init() {
        searchBox = new EditBox(font, 0, 0, 10, 10, Component.empty());
        searchBox.setMaxLength(40);
        Sky2MGuiStyle.placeSearch(searchBox, panelX() + 8, panelY() + 28, LEFT_W - Sky2MGuiStyle.SEARCH_ICON);
        searchBox.setValue(search);
        searchBox.setResponder(s -> {
            search = s;
            listScroll = 0;
        });
        addRenderableWidget(searchBox);
    }

    /** The open tab's rows, RNG only applied. */
    private List<DungeonDropTracker.Row> rows() {
        List<DungeonDropTracker.Row> rows;
        if (category.equals(TODAY)) {
            List<DungeonDropTracker.Drop> drops = new ArrayList<>();
            for (TrackerDrops.Day day : pickedDays()) drops.addAll(day.drops());
            rows = DungeonDropTracker.rows(drops, null, search);
        } else {
            rows = category.equals(DUNGEONS)
                ? DungeonDropTracker.rows(DungeonDropTracker.drops(), part, search)
                : TrackerDrops.rows(parts, part, search);
        }
        if (rngOnly) rows = new ArrayList<>(rows.stream().filter(r -> r.unit() >= RNG_PRICE).toList());
        return rows;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        int x = panelX(), y = panelY(), w = panelW(), h = panelH();
        Sky2MGuiStyle.panel(g, x, y, w, h, true);
        Sky2MGuiStyle.header(g, font, x, y, w, Sky2MGuiStyle.title("Drop Tracker"));

        // Search and the category tabs.
        Sky2MGuiStyle.search(g, searchBox);
        int fx = listX() + 2, fy = y + 32;
        List<String> tabs = new ArrayList<>(List.of(TODAY, DUNGEONS));
        tabs.addAll(List.of(TrackerDrops.CATEGORIES));
        for (String tab : tabs) {
            int fw = font.width(tab);
            boolean hover = mouseX >= fx - 3 && mouseX < fx + fw + 3 && mouseY >= fy - 3 && mouseY < fy + 11;
            Sky2MGuiStyle.category(g, font, tab, fx, fy, tab.equals(category), hover);
            hits.add(new Hit(fx - 3, fy - 3, fw + 6, 14, () -> pickCategory(tab)));
            fx += fw + 11;
        }

        List<DungeonDropTracker.Row> rows = rows();
        double worth = 0;
        for (DungeonDropTracker.Row r : rows) worth += r.value();
        parts(g, mouseX, mouseY);
        list(g, mouseX, mouseY, rows, worth);

        // RNG only / Everything, and Done.
        int bw = 96, gap = 8, by = y + h - 24;
        int rngX = x + (w - 2 * bw - gap) / 2, doneX = rngX + bw + gap;
        boolean rngHover = mouseX >= rngX && mouseX < rngX + bw && mouseY >= by && mouseY < by + 18;
        Sky2MGuiStyle.button(g, font, rngOnly ? "RNG only" : "Everything", rngX, by, bw, 18, rngHover);
        hits.add(new Hit(rngX, by, bw, 18, () -> {
            rngOnly = !rngOnly;
            listScroll = 0;
        }));
        boolean doneHover = mouseX >= doneX && mouseX < doneX + bw && mouseY >= by && mouseY < by + 18;
        Sky2MGuiStyle.button(g, font, "Done", doneX, by, bw, 18, doneHover);
        hits.add(new Hit(doneX, by, bw, 18, this::onClose));

        super.extractRenderState(g, mouseX, mouseY, delta);
        if (rngHover) {
            g.setTooltipForNextFrame(font, List.of(Component.literal(rngOnly
                ? "Showing items worth " + DungeonProfitHour.coins(RNG_PRICE) + "+ each - click to show everything"
                : "Showing everything - click for RNG drops only")), java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    /** The left box: Dungeons' floors with how many chests, Slayer's bosses, or just "All" for the other tabs. */
    private void parts(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x0 = panelX() + 8, x1 = x0 + LEFT_W, top = bodyTop(), bottom = bodyBottom();
        Sky2MGuiStyle.inset(g, x0, top, x1, bottom);
        boolean dungeons = category.equals(DUNGEONS);
        boolean today = category.equals(TODAY);
        Sky2MGuiStyle.heading(g, font, today ? "Activities" : dungeons ? "Floors" : category.equals("Slayer") ? "Bosses" : category, x0, top + 5, LEFT_W);
        int viewTop = top + 17, viewBottom = bottom - 2;

        // {label, key, count}
        List<Object[]> entries = new ArrayList<>();
        if (today) {
            double profit = 0;
            for (TrackerDrops.Day day : days) profit += day.profit();
            entries.add(new Object[]{"All", null, DungeonProfitHour.coins(profit)});
            for (TrackerDrops.Day day : days) entries.add(new Object[]{day.name(), day.name(), DungeonProfitHour.coins(day.profit())});
        } else if (dungeons) {
            List<DungeonDropTracker.Cost> chests = DungeonDropTracker.costs();
            entries.add(new Object[]{"All floors", null, chests.size() + " chests"});
            for (String f : DungeonDropTracker.floors(chests)) {
                String label = f.equals("?") ? "Unknown floor" : f.startsWith("M") ? "Master " + f.substring(1) : f.equals("E") ? "Entrance" : "Floor " + f.substring(1);
                entries.add(new Object[]{label, f, String.valueOf(DungeonDropTracker.chests(chests, f))});
            }
        } else {
            entries.add(new Object[]{"All", null, ""});
            if (parts.size() > 1) for (TrackerDrops.Part p : parts) entries.add(new Object[]{p.name(), p.name(), ""});
        }
        if (part != null && entries.stream().noneMatch(e -> part.equals(e[1]))) part = null;

        int content = entries.size() * HEAD + 4;
        int view = viewBottom - viewTop;
        partScroll = Math.max(0, Math.min(partScroll, Math.max(0, content - view)));
        g.enableScissor(x0 + 1, viewTop, x1 - 1, viewBottom);
        int cy = viewTop + 2 - (int) partScroll;
        boolean inView = mouseY >= viewTop && mouseY < viewBottom;
        for (Object[] e : entries) cy = entry(g, mouseX, mouseY, inView, x0, x1, cy, (String) e[0], (String) e[1], (String) e[2]);
        g.disableScissor();
        Sky2MGuiStyle.scrollbar(g, x1 - 6, viewTop, viewBottom, (int) partScroll, view, content);
    }

    private int entry(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean inView, int x0, int x1, int cy,
                      String label, String key, String count) {
        boolean selected = key == null ? part == null : key.equals(part);
        boolean hover = inView && mouseX >= x0 && mouseX < x1 && mouseY >= cy - 2 && mouseY < cy + HEAD - 2;
        Sky2MGuiStyle.category(g, font, font.plainSubstrByWidth(label, x1 - x0 - 22 - font.width(count)), x0 + 10, cy, selected, hover);
        if (!count.isEmpty()) g.text(font, count, x1 - 9 - font.width(count), cy, Sky2MGuiStyle.MUTED, false);
        if (inView) hits.add(new Hit(x0, cy - 2, x1 - x0, HEAD, () -> {
            part = key;
            listScroll = 0;
        }));
        return cy + HEAD;
    }

    /** The right box: one row per item, most expensive first, with how many you got and their worth. */
    private void list(GuiGraphicsExtractor g, int mouseX, int mouseY, List<DungeonDropTracker.Row> rows, double worth) {
        int x0 = listX(), x1 = x0 + listW(), top = bodyTop(), bottom = bodyBottom();
        Sky2MGuiStyle.inset(g, x0, top, x1, bottom);
        boolean dungeons = category.equals(DUNGEONS);
        boolean today = category.equals(TODAY);
        String title = part == null ? category : part;
        g.text(font, title, x0 + 7, top + 5, Sky2MGuiStyle.HEADING, false);

        // Worth, and on Dungeons what the chests cost and the profit (costs belong to chests, so a search or RNG only
        // shows worth only, as their worth isn't all the chests' loot).
        Component total = Component.literal("worth ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(DungeonProfitHour.coins(worth)).withStyle(ChatFormatting.GOLD));
        List<Component> costTip = null;
        if (today && search.isBlank()) {
            // The whole day's profit, whatever RNG only hides from the list below.
            double profit = 0;
            for (TrackerDrops.Day day : pickedDays()) profit += day.profit();
            total = Component.literal("profit ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal((profit >= 0 ? "+" : "") + DungeonProfitHour.coins(profit)).withStyle(profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            costTip = todayTooltip(pickedDays());
        } else if (dungeons && search.isBlank() && !rngOnly) {
            double spent = DungeonDropTracker.spent(DungeonDropTracker.costs(), part);
            double profit = worth - spent;
            total = total.copy().append(Component.literal(", cost ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(DungeonProfitHour.coins(spent)).withStyle(ChatFormatting.RED))
                .append(Component.literal(", profit ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal((profit >= 0 ? "+" : "") + DungeonProfitHour.coins(profit)).withStyle(profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            costTip = costTooltip(part, worth);
        } else if (dungeons && search.isBlank()) {
            costTip = List.of(Component.literal("§7Click Everything to see what the chests cost and your profit"));
        }
        int totalX = x1 - 8 - font.width(total);
        g.text(font, total, totalX, top + 5, Sky2MGuiStyle.TEXT_DIM, false);
        boolean totalHover = costTip != null && mouseX >= totalX && mouseX < x1 - 8 && mouseY >= top + 3 && mouseY < top + 14;

        int viewTop = top + 17, viewBottom = bottom - 2;
        int content = rows.size() * (ROW + 1);
        int view = viewBottom - viewTop;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, content - view)));

        g.enableScissor(x0 + 1, viewTop, x1 - 1, viewBottom);
        int ry = viewTop + 2 - (int) listScroll;
        int rx0 = x0 + 5, rx1 = x1 - 9;
        List<Component> tooltip = null;
        for (DungeonDropTracker.Row r : rows) {
            if (ry + ROW > viewTop && ry < viewBottom) {
                boolean hover = mouseX >= rx0 && mouseX < rx1 && mouseY >= Math.max(ry, viewTop) && mouseY < Math.min(ry + ROW, viewBottom);
                g.fill(rx0, ry, rx1, ry + ROW, hover ? Sky2MGuiStyle.ROW_HOVER : Sky2MGuiStyle.ROW);
                String count = "x" + DungeonProfitHour.whole(r.count());
                String rowWorth = r.value() > 0 ? DungeonProfitHour.coins(r.value()) : "";
                int rightW = font.width(count) + (rowWorth.isEmpty() ? 0 : font.width(rowWorth) + 10);
                String fit = font.plainSubstrByWidth(r.name(), rx1 - rx0 - 14 - rightW);
                // Books like Hypixel's enchant lines: ultimate enchants pink, the rest blue.
                ChatFormatting colour = r.id().startsWith("ENCHANTMENT_ULTIMATE_") ? ChatFormatting.LIGHT_PURPLE
                    : r.id().startsWith("ENCHANTMENT_") ? ChatFormatting.BLUE
                    : r.id().isEmpty() ? ChatFormatting.WHITE : RepoItems.tierColour(RepoItems.tier(r.id()));
                g.text(font, Component.literal(fit).withStyle(colour == null ? ChatFormatting.WHITE : colour), rx0 + 5, ry + 3, Sky2MGuiStyle.TEXT, false);
                int cx = rx1 - 5 - font.width(count);
                g.text(font, count, cx, ry + 3, 0xFFFFFFFF, false);
                if (!rowWorth.isEmpty()) g.text(font, rowWorth, cx - 10 - font.width(rowWorth), ry + 3, 0xFFFFAA00, false);
                if (hover) tooltip = today ? todayRowTooltip(r) : tooltip(r, dungeons);
            }
            ry += ROW + 1;
        }
        if (rows.isEmpty()) {
            String none = today && days.isEmpty() ? "Nothing tracked yet today"
                : dungeons && DungeonDropTracker.costs().isEmpty() ? "Nothing yet - your dungeon chest loot shows up here as you open chests"
                : !search.isBlank() ? "No drops match \"" + search.trim() + "\""
                : rngOnly ? "No RNG drops here yet - click Everything to see the rest"
                : "Nothing tracked here yet";
            none = font.plainSubstrByWidth(none, listW() - 10);
            g.text(font, none, x0 + (listW() - font.width(none)) / 2, viewTop + 8, Sky2MGuiStyle.MUTED, false);
        }
        g.disableScissor();
        Sky2MGuiStyle.scrollbar(g, x1 - 6, viewTop, viewBottom, (int) listScroll, view, content);
        if (tooltip != null) g.setTooltipForNextFrame(font, tooltip, java.util.Optional.empty(), mouseX, mouseY);
        else if (totalHover) g.setTooltipForNextFrame(font, costTip, java.util.Optional.empty(), mouseX, mouseY);
    }

    /** The cost and profit hover: how many chests, the worth, and the cost split into coins, keys and feathers. */
    private static List<Component> costTooltip(String floor, double worth) {
        double coins = 0, keys = 0, kismets = 0;
        int chests = 0;
        for (DungeonDropTracker.Cost c : DungeonDropTracker.costs()) {
            if (floor != null && !floor.equalsIgnoreCase(c.floor)) continue;
            chests++;
            coins += c.coins;
            keys += c.key;
            kismets += c.kismet;
        }
        double profit = worth - coins - keys - kismets;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(chests + " chest" + (chests == 1 ? "" : "s") + " opened").withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal("§7Loot worth: §6" + DungeonProfitHour.coins(worth)));
        lines.add(Component.literal("§7Chest coins: §c" + DungeonProfitHour.coins(coins)));
        if (keys > 0) lines.add(Component.literal("§7Dungeon Chest Keys: §c" + DungeonProfitHour.coins(keys)));
        if (kismets > 0) lines.add(Component.literal("§7Kismet Feathers: §c" + DungeonProfitHour.coins(kismets)));
        lines.add(Component.literal("§7Profit: " + (profit >= 0 ? "§a+" : "§c") + DungeonProfitHour.coins(profit)));
        return lines;
    }

    /** The Today profit hover: each activity's profit, then the day's loot, coins and costs. */
    private static List<Component> todayTooltip(List<TrackerDrops.Day> days) {
        double worth = 0, coins = 0, costs = 0;
        List<Component> lines = new ArrayList<>();
        for (TrackerDrops.Day day : days) {
            worth += day.worth();
            coins += day.coins();
            costs += day.costs();
            double profit = day.profit();
            lines.add(Component.literal("§f" + day.name() + ": " + (profit >= 0 ? "§a+" : "§c") + DungeonProfitHour.coins(profit)));
        }
        if (!lines.isEmpty()) lines.add(Component.empty());
        lines.add(Component.literal("§7Loot worth: §6" + DungeonProfitHour.coins(worth)));
        if (coins > 0) lines.add(Component.literal("§7Coins: §6" + DungeonProfitHour.coins(coins)));
        lines.add(Component.literal("§7Costs: §c" + DungeonProfitHour.coins(costs)));
        lines.add(Component.literal("§8Since midnight. Chest loot at its price when claimed, the rest priced now."));
        return lines;
    }

    private static List<Component> todayRowTooltip(DungeonDropTracker.Row r) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(r.name()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal("§7Got today: §f" + DungeonProfitHour.whole(r.count())));
        if (r.value() > 0) {
            lines.add(Component.literal("§7Worth: §6" + DungeonProfitHour.coins(r.value()) + (r.count() > 1 ? " §8(" + DungeonProfitHour.coins(r.unit()) + " each)" : "")));
        }
        return lines;
    }

    private static List<Component> tooltip(DungeonDropTracker.Row r, boolean dungeons) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(r.name()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.literal("§7Got: §f" + DungeonProfitHour.whole(r.count()) + (dungeons && r.times() != r.count() ? " §8in " + r.times() + " chests" : "")));
        if (r.value() > 0) {
            lines.add(Component.literal("§7Worth: §6" + DungeonProfitHour.coins(r.value()) + (r.count() > 1 ? " §8(" + DungeonProfitHour.coins(r.unit()) + " each)" : "")));
            if (!dungeons) lines.add(Component.literal("§8Priced now"));
        }
        if (dungeons) lines.add(Component.literal("§7Last: §f" + DATE.format(Instant.ofEpochMilli(r.last()).atZone(ZoneId.systemDefault()))));
        else lines.add(Component.literal("§8From Sky2M's trackers, all time"));
        return lines;
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (Sky2MGuiStyle.inSearch(searchBox, event.x(), event.y())) {
                setFocused(searchBox);
                return super.mouseClicked(event, doubleClick) || true;
            }
            for (Hit hit : List.copyOf(hits)) {
                if (event.x() >= hit.x && event.x() < hit.x + hit.w && event.y() >= hit.y && event.y() < hit.y + hit.h) {
                    Sky2MGuiStyle.click();
                    hit.action.run();
                    return true;
                }
            }
            setFocused(null);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < listX()) partScroll -= scrollY * HEAD * 2;
        else listScroll -= scrollY * (ROW + 1) * 3;
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
