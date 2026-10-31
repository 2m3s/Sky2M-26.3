package com.epic60869.sky2m.gui;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.misc.visual.OwnNameTag;
import com.epic60869.sky2m.features.portfolio.CollectionPortfolio;
import com.epic60869.sky2m.features.sbc.SbcNet;
import com.epic60869.sky2m.features.slayer.SlayerLeaderboard;
import com.epic60869.sky2m.features.slayer.SlayerTimes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.epic60869.sky2m.chat.Sky2MGlobalChat;
import com.epic60869.sky2m.social.Sky2MLeaderboards;
import com.epic60869.sky2m.social.Sky2MRivals;

/**
 * /s2 leaderboard: every Sky2M leaderboard in one screen, laid out like /s2 collections.
 *
 * <p>Tabs along the top (Collections, Slayer XP, Kill Times, Events) and a search box; the boards are listed on the
 * left (collections with their icons, grouped and filtered by Hypixel's skill categories) and the picked board's
 * standings are a table on the right, with your own row highlighted and your rank above it.
 *
 * <p>All data flows through {@link Sky2MLeaderboards}' WebSocket packets: this screen sends its requests through it
 * (which keeps the answers out of chat) and reads its caches ({@link Sky2MLeaderboards#statList()},
 * {@link Sky2MLeaderboards#statResult()}, {@link Sky2MLeaderboards#eventStandings()},
 * {@link SlayerLeaderboard#cached(String)}). Answers are kept per board here, so going back to a board is instant.
 */
public final class Sky2MLeaderboardScreen extends Screen {
    private enum Tab {
        COLLECTIONS("Collections"), SLAYER_XP("Slayer XP"), KILL_TIMES("Kill Times"), EVENTS("Events");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** Secondary text (hints, notes): between the settings' grey category names and dark grey. */
    private static final int GREY = 0xFF808080;
    private static final int LIST_ROW = 16;
    private static final int TABLE_ROW = 13;
    private static final int ICON = 12;
    /** How long to wait for a server answer before the screen says so (it retries twice by itself). */
    private static final long ANSWER_TIMEOUT_MS = 4_000L;
    private static final long RETRY_GAP_MS = 5_000L;
    private static final String WEBSITE = "shadowisabot.com/leaderboards";

    /** Atoned Horror and Conjoined Brood are Tier 5 Revenant and Tarantula, so they're under those. */
    private static final List<String> BOSSES = List.of("Revenant Horror", "Tarantula Broodfather", "Sven Packmaster",
        "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend");
    private static final List<String> TIERS = List.of("I", "II", "III", "IV", "V");
    /** Hypixel's collection menu order; anything else is under Other. */
    private static final List<String> CATEGORIES = List.of("Farming", "Mining", "Combat", "Foraging", "Fishing", "Rift", "Other");
    private static final Map<String, String> CATEGORY_BY_ID = buildCategories();

    // Kept between openings, like /s2 collections' period.
    private static Tab tab = Tab.COLLECTIONS;
    private static String category = "All";
    private static String search = "";
    /** Collections and Slayer XP: this week's gains instead of all-time totals. */
    private static boolean thisWeek = false;
    /** Answers already received this game, per board: "type|id" -> standings. */
    private static final Map<String, Sky2MLeaderboards.StatResult> STAT_CACHE = new HashMap<>();
    private static final Map<String, Sky2MLeaderboards.EventStandings> EVENT_CACHE = new HashMap<>();

    /** One board in the left list (a header when {@code header} is set). */
    private record Board(String key, String type, String id, String name, String hint, ItemStack icon, String header) {}

    /** One line of the standings table. */
    private record Standing(String rank, String player, String value, boolean self, int rankColour, long amount) {}

    /** The rival picker above the table: its title, the players in it (best rank first) and you (null: not on the board). */
    private record Picker(String title, List<Standing> picks, Standing you) {}

    /** A [Rival] button drawn this frame, for clicks. */
    private record PickButton(int x0, int y0, int x1, int y1, String player) {}

    private static final int PICK_ROW = 11;
    /** The table keeps at least this many rows; the picker drops players (then hides) to leave them. */
    private static final int MIN_TABLE_ROWS = 4;
    /** Narrower than this, the picker hides: the name, gap and button don't fit. */
    private static final int MIN_PICKER_WIDTH = 170;

    private EditBox searchBox;
    private Board selected;
    private int listScroll;
    private int tableScroll;
    private List<Board> boards = List.of();
    private Object askedOn;
    private long pendingSince;
    private int retries;
    private boolean forceOnce;
    private final List<PickButton> pickButtons = new ArrayList<>();
    /** The toolbar's tabs, filters and buttons drawn this frame, for clicks. */
    private final List<Hit> hits = new ArrayList<>();

    /** A clickable toolbar entry drawn this frame. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {}

    public Sky2MLeaderboardScreen() {
        super(Component.literal("Sky2M Leaderboards"));
    }

    // ---------------------------------------------------------------- layout
    // Laid out like the /s2 settings: a header bar with the title, then (under a toolbar with the tabs, search and
    // buttons) the board list in a left panel, like the settings' categories, and the standings in a right panel,
    // like the options. Each list sits in the settings' sunken box with its scroll bar.

    private int left() { return 10; }
    private int top() { return 10; }
    private int panelWidth() { return width - 20; }
    private int panelHeight() { return height - 20; }
    private int toolbarTop() { return top() + 29; }
    /** Tabs and search. */
    private int row1() { return toolbarTop() + 4; }
    /** Collection filters, the week switch and Refresh. */
    private int row2() { return toolbarTop() + 22; }
    private int panelsTop() { return toolbarTop() + 46; }
    /** The bottom of both sunken boxes. */
    private int contentBottom() { return top() + panelHeight() - 10; }
    /** Narrower (down to 120) only on the smallest screens, where the standings need the room more. */
    private int listWidth() { return Math.max(120, Math.min(240, panelWidth() * 36 / 100)); }
    private int listPanelLeft() { return left() + 4; }
    private int listPanelWidth() { return listWidth() + 24; }
    /** The list's sunken box, under the purple heading; its scroll bar is on its left, like the settings' categories. */
    private int listInsetLeft() { return listPanelLeft() + 5; }
    private int listInsetTop() { return panelsTop() + 18; }
    private int listLeft() { return listInsetLeft() + 10; }
    private int tablePanelLeft() { return listPanelLeft() + listPanelWidth() + 5; }
    private int tablePanelRight() { return left() + panelWidth() - 5; }
    /** The standings' sunken box, under the board's title; its scroll bar is on its right, like the settings' options. */
    private int tableInsetTop() { return panelsTop() + 26; }
    private int tableLeft() { return tablePanelLeft() + 11; }
    private int tableRight() { return tablePanelRight() - 18; }
    private int tableBottom() { return contentBottom() - 2; }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        searchBox = new EditBox(font, 0, 0, 80, 10, Component.literal("Search"));
        searchBox.setMaxLength(40);
        searchBox.setHint(Component.literal("Search...").withStyle(ChatFormatting.DARK_GRAY));
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value;
            listScroll = 0;
        });
        // Right of the tabs, like the settings' search in the top right; as wide as there's room for (60-140).
        int tabsEnd = left() + 12;
        for (Tab t : Tab.values()) tabsEnd += font.width(t.label) + TAB_GAP;
        int fieldRight = left() + panelWidth() - 10 - Sky2MGuiStyle.SEARCH_ICON;
        int fieldWidth = Math.max(60, Math.min(140, fieldRight - tabsEnd - 6));
        Sky2MGuiStyle.placeSearch(searchBox, fieldRight - fieldWidth, row1(), fieldWidth);
        addRenderableWidget(searchBox);
    }

    private static final int TAB_GAP = 12;

    private void toggleWeek() {
        thisWeek = !thisWeek;
        tableScroll = 0;
        retries = 0;
        pendingSince = 0;
        request(false);
    }

    private void switchTab(Tab t) {
        tab = t;
        selected = null;
        listScroll = 0;
        tableScroll = 0;
        pendingSince = 0;
    }

    // ---------------------------------------------------------------- requests

    @Override
    public void tick() {
        boolean online = SbcNet.online();
        if (!online) Sky2MGlobalChat.ensureConnected();
        Object connection = Sky2MGlobalChat.currentConnection();
        if (online && (connection != askedOn || forceOnce)) {
            askedOn = connection;
            if (Sky2MLeaderboards.statList() == null || forceOnce) Sky2MLeaderboards.requestStatList();
            if (selected != null) request(forceOnce);
            forceOnce = false;
        }
        remember();
        boards = buildBoards();
        if (selected == null) {
            // Show something straight away: the first board on the list.
            for (Board b : boards) {
                if (b.header() == null) {
                    select(b);
                    break;
                }
            }
        }
        // An answer that never came: ask again twice, then leave the note up.
        if (selected != null && standings() == null && timedOut() && retries < 2
            && System.currentTimeMillis() - pendingSince > (retries + 1) * RETRY_GAP_MS) {
            retries++;
            request(true);
        }
    }

    /** Keeps the answer to the board on screen, so coming back to it later needs no new request. */
    private void remember() {
        Sky2MLeaderboards.StatResult stat = Sky2MLeaderboards.statResult();
        if (stat != null && !stat.key().isEmpty() && !stat.key().equals("|")) STAT_CACHE.put(stat.key(), stat);
        Sky2MLeaderboards.EventStandings event = Sky2MLeaderboards.eventStandings();
        if (event != null) EVENT_CACHE.put(event.boardName().toLowerCase(Locale.ROOT), event);
    }

    private void select(Board board) {
        selected = board;
        tableScroll = 0;
        retries = 0;
        pendingSince = 0;
        request(false);
    }

    /** Asks for the selected board's standings (always, so they're fresh; the cached ones show meanwhile). */
    private void request(boolean force) {
        if (selected == null || !SbcNet.online()) return;
        if (!force && standings() != null && tab != Tab.KILL_TIMES && tab != Tab.EVENTS) {
            // Stat boards only change every few minutes on the server: the kept answer is fine.
            return;
        }
        boolean sent = switch (tab) {
            case COLLECTIONS, SLAYER_XP -> Sky2MLeaderboards.requestStat(thisWeek ? selected.type() + "week" : selected.type(), selected.id());
            case KILL_TIMES -> SlayerLeaderboard.fetch(selected.id());
            case EVENTS -> Sky2MLeaderboards.requestEvent(selected.id(), selected.name());
        };
        if (sent) pendingSince = System.currentTimeMillis();
    }

    /** The board's key as the server answers it: "collection|WHEAT", or "collectionweek|WHEAT" for this week. */
    private static String statKey(Board board) {
        return (thisWeek ? board.type() + "week" : board.type()) + "|" + board.id();
    }

    private boolean timedOut() {
        return pendingSince > 0 && System.currentTimeMillis() - pendingSince > ANSWER_TIMEOUT_MS;
    }

    // ---------------------------------------------------------------- the boards (left)

    private List<Board> buildBoards() {
        List<Board> out = new ArrayList<>();
        String query = search.trim().toLowerCase(Locale.ROOT);
        Sky2MLeaderboards.StatListData list = Sky2MLeaderboards.statList();
        switch (tab) {
            case COLLECTIONS -> {
                if (list == null) return out;
                Map<String, List<Board>> grouped = new LinkedHashMap<>();
                for (String c : CATEGORIES) grouped.put(c, new ArrayList<>());
                for (Sky2MLeaderboards.StatBoard b : list.collections()) {
                    String c = categoryOf(b);
                    if (!category.equals("All") && !category.equals(c)) continue;
                    if (!matches(b.name(), query)) continue;
                    grouped.computeIfAbsent(c, k -> new ArrayList<>())
                        .add(new Board(b.type() + "|" + b.id(), b.type(), b.id(), b.name().replaceFirst(" Collection$", ""), null,
                            CollectionPortfolio.icon(b.id()), null));
                }
                for (Map.Entry<String, List<Board>> group : grouped.entrySet()) {
                    if (group.getValue().isEmpty()) continue;
                    group.getValue().sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                    if (category.equals("All")) out.add(new Board(null, null, null, group.getKey(), null, null, group.getKey()));
                    out.addAll(group.getValue());
                }
            }
            case SLAYER_XP -> {
                if (list == null) return out;
                for (Sky2MLeaderboards.StatBoard b : list.slayers()) {
                    if (!matches(b.name(), query)) continue;
                    out.add(new Board(b.type() + "|" + b.id(), b.type(), b.id(), b.name(), null, slayerIcon(b.id() + " " + b.name()), null));
                }
            }
            case KILL_TIMES -> {
                for (String boss : BOSSES) {
                    boolean header = false;
                    for (String tier : TIERS) {
                        String key = SlayerLeaderboard.canonical(boss + " " + tier);
                        if (key == null || !matches(key, query)) continue;
                        if (!header) {
                            out.add(new Board(null, null, null, boss, null, null, boss));
                            header = true;
                        }
                        Long own = SlayerTimes.personalBests().get(key);
                        out.add(new Board("pb|" + key, "pb", key, "Tier " + tier, own == null ? null : SlayerTimes.format(own),
                            slayerIcon(boss), null));
                    }
                }
            }
            case EVENTS -> {
                long now = System.currentTimeMillis();
                for (Sky2MLeaderboards.EventBoard b : Sky2MLeaderboards.eventBoards()) {
                    if (!matches(b.name(), query)) continue;
                    String ends = b.endsAt() <= 0 ? "" : b.endsAt() < now ? "ended" : duration(b.endsAt() - now) + " left";
                    out.add(new Board("event|" + b.id(), "event", b.id(), b.name(), ends, null, null));
                }
            }
        }
        return out;
    }

    private static boolean matches(String name, String query) {
        return query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query);
    }

    /** A recognisable item for a slayer: its boss's main drop. */
    private static ItemStack slayerIcon(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        String id = t.contains("zombie") || t.contains("revenant") || t.contains("atoned") ? "REVENANT_FLESH"
            : t.contains("spider") || t.contains("tarantula") || t.contains("brood") ? "TARANTULA_WEB"
            : t.contains("wolf") || t.contains("sven") ? "WOLF_TOOTH"
            : t.contains("enderman") || t.contains("voidgloom") ? "NULL_SPHERE"
            : t.contains("blaze") || t.contains("inferno") ? "DERELICT_ASHE"
            : t.contains("vampire") || t.contains("riftstalker") ? "COVEN_SEAL" : null;
        ItemStack stack = id == null ? ItemStack.EMPTY : RepoItems.itemStack(id);
        return stack == null || stack.is(Items.BARRIER) ? ItemStack.EMPTY : stack;
    }

    // ---------------------------------------------------------------- the standings (right)

    /** The selected board's standings as table rows, or null while they haven't arrived. */
    private List<Standing> standings() {
        if (selected == null) return null;
        String self = Minecraft.getInstance().getUser().getName();
        List<Standing> out = new ArrayList<>();
        switch (tab) {
            case COLLECTIONS, SLAYER_XP -> {
                Sky2MLeaderboards.StatResult r = STAT_CACHE.get(statKey(selected));
                if (r == null) return null;
                boolean xp = tab == Tab.SLAYER_XP;
                for (Sky2MLeaderboards.StatEntry e : r.entries()) {
                    out.add(new Standing("#" + e.rank(), e.username(), (thisWeek ? "+" : "") + fmt(e.value()) + (xp ? " XP" : ""),
                        self.equalsIgnoreCase(e.username()), rankColour(e.rank()), e.value()));
                }
            }
            case KILL_TIMES -> {
                SlayerLeaderboard.Standings s = SlayerLeaderboard.cached(selected.id());
                if (s == null) return null;
                for (int i = 0; i < s.entries().size(); i++) {
                    SlayerLeaderboard.Entry e = s.entries().get(i);
                    out.add(new Standing("#" + (i + 1), e.username(), SlayerTimes.format(e.timeMs()),
                        self.equalsIgnoreCase(e.username()), rankColour(i + 1), -e.timeMs()));
                }
                if (s.youRank() > s.entries().size()) {
                    out.add(new Standing("#" + s.youRank(), self, SlayerTimes.format(s.youTimeMs()), true, 0xFF55FF55, -s.youTimeMs()));
                }
            }
            case EVENTS -> {
                Sky2MLeaderboards.EventStandings s = EVENT_CACHE.get(selected.name().trim().toLowerCase(Locale.ROOT));
                if (s == null) return null;
                for (int i = 0; i < s.entries().size(); i++) {
                    Sky2MLeaderboards.EventEntry e = s.entries().get(i);
                    out.add(new Standing("#" + (i + 1), e.username(), fmt(e.amount()), self.equalsIgnoreCase(e.username()), rankColour(i + 1), e.amount()));
                }
                if (s.youRank() > s.entries().size()) {
                    out.add(new Standing("#" + s.youRank(), self, fmt(s.youAmount()), true, 0xFF55FF55, s.youAmount()));
                }
            }
        }
        return out;
    }

    /** "Error from the server" for a stat board that answered with one, else "". */
    private String standingsError() {
        if (selected == null || (tab != Tab.COLLECTIONS && tab != Tab.SLAYER_XP)) return "";
        Sky2MLeaderboards.StatResult r = STAT_CACHE.get(statKey(selected));
        return r == null ? "" : r.error();
    }

    private static int rankColour(int rank) {
        return rank == 1 ? 0xFFFFD35A : rank == 2 ? 0xFFD8D8D8 : rank == 3 ? 0xFFE0904A : 0xFFB8C0CC;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelWidth(), panelHeight(), true);
        drawHeader(g);
        hits.clear();
        drawToolbar(g, mouseX, mouseY);

        int panelsHeight = contentBottom() + 5 - panelsTop();
        Sky2MGuiStyle.panel(g, listPanelLeft(), panelsTop(), listPanelWidth(), panelsHeight, false);
        Sky2MGuiStyle.panel(g, tablePanelLeft(), panelsTop(), tablePanelRight() - tablePanelLeft(), panelsHeight, false);
        drawBoards(g, mouseX, mouseY);
        drawTable(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    /** The settings' header bar: the title in the middle, the status on the right when both fit. */
    private void drawHeader(GuiGraphicsExtractor g) {
        int x = left() + 4, w = panelWidth() - 9;
        Component title = Sky2MGuiStyle.title("Leaderboards");
        String status = statusText();
        int titleX = x + (w - font.width(title)) / 2;
        int statusRoom = x + w - 6 - (titleX + font.width(title) + 10);
        if (statusRoom < font.width(status)) {
            // Too narrow for both with the title in the middle: title on the left, the status after it.
            titleX = x + 6;
            statusRoom = x + w - 6 - (titleX + font.width(title) + 10);
        }
        Sky2MGuiStyle.panel(g, x, top() + 5, w, 20, false);
        g.text(font, title, titleX, top() + 11, Sky2MGuiStyle.TITLE, false);
        String fit = font.plainSubstrByWidth(status, Math.max(0, statusRoom));
        g.text(font, fit, x + w - 6 - font.width(fit), top() + 11, Sky2MGuiStyle.TITLE, false);
    }

    /** Tabs and the search field, then the collection filters, the week switch and Refresh. */
    private void drawToolbar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.panel(g, left() + 4, toolbarTop(), panelWidth() - 9, 42, false);
        int x = left() + 12;
        for (Tab t : Tab.values()) {
            x += category(g, t.label, x, row1(), t == tab, mouseX, mouseY, () -> {
                if (t != tab) switchTab(t);
            }) + TAB_GAP;
        }
        Sky2MGuiStyle.search(g, searchBox);

        int right = left() + panelWidth() - 10;
        int refreshWidth = Sky2MGuiStyle.buttonWidth(font, "Refresh");
        int bx = right - refreshWidth;
        button(g, "Refresh", bx, refreshWidth, mouseX, mouseY, () -> forceOnce = true);
        if (tab == Tab.COLLECTIONS || tab == Tab.SLAYER_XP) {
            int weekWidth = Sky2MGuiStyle.buttonWidth(font, "This Week");
            bx -= weekWidth + 4;
            if (button(g, thisWeek ? "This Week" : "All Time", bx, weekWidth, mouseX, mouseY, this::toggleWeek)) {
                g.setTooltipForNextFrame(font, List.of(Component.literal(
                    "All Time: everyone's totals. This Week: what everyone gained since Monday (00:00 UTC).")), java.util.Optional.empty(), mouseX, mouseY);
            }
        }
        if (tab == Tab.COLLECTIONS) {
            List<String> filters = new ArrayList<>();
            filters.add("All");
            filters.addAll(CATEGORIES);
            int total = 0;
            for (String c : filters) total += font.width(c);
            int start = left() + 12;
            // The gap shrinks to fit them left of the buttons.
            int gap = Math.max(4, Math.min(10, (bx - 8 - start - total) / (filters.size() - 1)));
            int fx = start;
            for (String c : filters) {
                fx += category(g, c, fx, row2(), c.equals(category), mouseX, mouseY, () -> {
                    category = c;
                    listScroll = 0;
                }) + gap;
            }
        }
    }

    /** A category-style toolbar entry on a 16px row, clickable; returns its width. */
    private int category(GuiGraphicsExtractor g, String text, int x, int rowY, boolean isSelected, int mouseX, int mouseY, Runnable action) {
        int w = font.width(text);
        Hit hit = new Hit(x - 3, rowY, x + w + 3, rowY + 16, action);
        hits.add(hit);
        return Sky2MGuiStyle.category(g, font, text, x, rowY + 4, isSelected, inside(hit, mouseX, mouseY));
    }

    /** A MoulConfig button on the second toolbar row; returns whether it's hovered. */
    private boolean button(GuiGraphicsExtractor g, String text, int x, int w, int mouseX, int mouseY, Runnable action) {
        Hit hit = new Hit(x, row2(), x + w, row2() + 16, action);
        hits.add(hit);
        boolean hover = inside(hit, mouseX, mouseY);
        Sky2MGuiStyle.button(g, font, text, x, row2(), w, 16, hover);
        return hover;
    }

    private static boolean inside(Hit hit, double x, double y) {
        return x >= hit.x0() && x < hit.x1() && y >= hit.y0() && y < hit.y1();
    }

    private String statusText() {
        if (!SbcNet.online()) return "Offline";
        Sky2MLeaderboards.StatListData list = Sky2MLeaderboards.statList();
        if (list != null && list.refreshing()) return "Hypixel stats refreshing...";
        return "Full lists on " + WEBSITE;
    }

    /** The list's purple heading, like the settings' "Categories". */
    private String listHeading() {
        return switch (tab) {
            case COLLECTIONS -> category.equals("All") ? "Collections" : category;
            case SLAYER_XP -> "Slayers";
            case KILL_TIMES -> "Bosses";
            case EVENTS -> "Events";
        };
    }

    private void drawBoards(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.heading(g, font, listHeading(), listPanelLeft(), panelsTop() + 6, listPanelWidth());
        int insetRight = listPanelLeft() + listPanelWidth() - 5;
        Sky2MGuiStyle.inset(g, listInsetLeft(), listInsetTop(), insetRight, contentBottom());
        int x = listLeft(), y = listTop(), w = listWidth();
        if (boards.isEmpty()) {
            String text = !SbcNet.online() ? "Can't reach the Sky2M server."
                : (tab == Tab.COLLECTIONS || tab == Tab.SLAYER_XP) && Sky2MLeaderboards.statList() == null ? "Loading boards..."
                : !search.isBlank() ? "Nothing matches \"" + search.trim() + "\"."
                : tab == Tab.EVENTS ? "No events are running right now." : "Nothing here yet.";
            for (var line : font.split(Component.literal(text), w)) {
                g.text(font, line, x, y + 4, GREY, false);
                y += 10;
            }
            return;
        }
        int visible = listVisible();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, boards.size() - visible)));
        headBoard = OwnNameTag.headBoard();
        g.enableScissor(listInsetLeft() + 1, listInsetTop() + 1, insetRight - 1, contentBottom() - 1);
        for (int i = listScroll; i < boards.size() && i < listScroll + visible; i++) {
            Board b = boards.get(i);
            int ry = y + (i - listScroll) * LIST_ROW;
            if (b.header() != null) {
                // A group heading in the settings' heading purple, with the sub-category line after it.
                g.text(font, b.header(), x, ry + 5, Sky2MGuiStyle.HEADING, false);
                g.fill(x + font.width(b.header()) + 4, ry + 9, x + w, ry + 10, Sky2MGuiStyle.LINE);
                continue;
            }
            boolean isSelected = selected != null && b.key().equals(selected.key());
            boolean hover = mouseX >= x - 2 && mouseX < x + w + 2 && mouseY >= ry && mouseY < ry + LIST_ROW;
            if (hover && !isSelected) g.fill(x - 2, ry, x + w + 2, ry + LIST_ROW, Sky2MGuiStyle.ROW_HOVER);
            int nameX = x + 2;
            if (b.icon() != null && !b.icon().isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(nameX, ry + 2);
                g.pose().scale(ICON / 16f, ICON / 16f);
                g.item(b.icon(), 0, 0);
                g.pose().popMatrix();
                nameX += ICON + 4;
            }
            int hintWidth = b.hint() == null ? 0 : font.width(b.hint()) + 6;
            // The board shown over your head (right-click a board to pick it): an aqua arrow before its hint.
            boolean overHead = b.key().equals(headBoard);
            if (overHead) hintWidth += font.width(HEAD_MARK) + 4;
            // Like the settings' categories: the selected board aqua and underlined, the rest grey (white while
            // hovered); boards with your rivals on them stay orange.
            String name = font.plainSubstrByWidth(b.name(), x + w - nameX - hintWidth);
            Component label = isSelected ? Component.literal(name).withStyle(ChatFormatting.UNDERLINE) : Component.literal(name);
            g.text(font, label, nameX, ry + 4, isSelected ? Sky2MGuiStyle.SELECTED : Sky2MRivals.hasOwnRivalsOn(b.key()) ? 0xFFFFAA00
                : hover ? 0xFFFFFFFF : Sky2MGuiStyle.UNSELECTED, false);
            if (b.hint() != null) g.text(font, b.hint(), x + w - font.width(b.hint()), ry + 4, GREY, false);
            if (overHead) g.text(font, HEAD_MARK, x + w - hintWidth + 2, ry + 4, 0xFF55FFFF, false);
        }
        g.disableScissor();
        Sky2MGuiStyle.scrollbar(g, listInsetLeft() + 2, listInsetTop() + 4, contentBottom() - 4, listScroll, visible, boards.size());
    }

    /** The first board row's top, inside the list's sunken box. */
    private int listTop() { return listInsetTop() + 2; }

    private int listVisible() { return Math.max(1, (contentBottom() - 1 - listTop()) / LIST_ROW); }

    /** The standings table's first row (text line; its highlight starts 2px above). */
    private int tableRowsTop(Picker picker) { return tableInsetTop() + 6 + pickerHeight(picker) + 14; }

    private void drawTable(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        pickButtons.clear();
        int x = tableLeft(), right = tableRight(), y = panelsTop() + 5;
        int insetLeft = tablePanelLeft() + 5, insetRight = tablePanelRight() - 5;
        Sky2MGuiStyle.inset(g, insetLeft, tableInsetTop(), insetRight, contentBottom());
        if (selected == null) {
            g.text(font, font.plainSubstrByWidth("Pick a board on the left. Right-click one to show it over your head.", insetRight - x),
                x, y, Sky2MGuiStyle.DESCRIPTION, true);
            return;
        }
        String title = switch (tab) {
            case COLLECTIONS -> selected.name() + " Collection";
            case KILL_TIMES -> selected.id();
            default -> selected.name();
        };
        if (thisWeek && (tab == Tab.COLLECTIONS || tab == Tab.SLAYER_XP)) title += " · This Week (ends in " + duration(weekEndsIn()) + ")";

        List<Standing> rows = standings();
        String error = standingsError();
        if (rows == null || !error.isEmpty() || rows.isEmpty()) {
            g.text(font, Component.literal(font.plainSubstrByWidth(title, insetRight - x)).withStyle(ChatFormatting.BOLD), x, y, Sky2MGuiStyle.TEXT, true);
            String text = !SbcNet.online() ? SbcNet.OFFLINE
                : !error.isEmpty() ? error
                : rows == null ? (timedOut() ? "The Sky2M server hasn't answered yet; press Refresh to ask again." : "Loading...")
                : tab == Tab.KILL_TIMES ? "Nobody has a time for this boss yet."
                : thisWeek ? "Nobody has gained any this week yet. Gains count from the first time the server sees you each week." : "No scores yet.";
            int ly = tableInsetTop() + 6;
            for (var line : font.split(Component.literal(text), right - x)) {
                g.text(font, line, x, ly, GREY, false);
                ly += 10;
            }
            return;
        }

        // Your own place, right of the title (the title gives way to it).
        Standing you = null;
        for (Standing s : rows) if (s.self()) you = s;
        String yours = you == null ? "You're not in the top " + rows.size() + " yet" : "You: " + you.rank() + "  ·  " + you.value();
        g.text(font, yours, right - font.width(yours), y, you == null ? GREY : 0xFF55FF55, false);
        String fitTitle = font.plainSubstrByWidth(title, Math.max(0, right - font.width(yours) - 8 - x));
        g.text(font, Component.literal(fitTitle).withStyle(ChatFormatting.BOLD), x, y, Sky2MGuiStyle.TEXT, true);
        // Your rivals on this board: how far ahead or behind you they are.
        StringBuilder rivalText = new StringBuilder();
        for (Standing s : rows) {
            if (s.self() || !Sky2MRivals.isRival(s.player(), selected.key())) continue;
            if (!rivalText.isEmpty()) rivalText.append("   ");
            rivalText.append("⚔ ").append(s.player()).append(' ').append(s.rank());
            if (you != null) {
                long gap = Math.abs(s.amount() - you.amount());
                boolean ahead = s.amount() > you.amount();
                String amount = tab == Tab.KILL_TIMES ? SlayerTimes.format(gap) : fmt(gap);
                rivalText.append(ahead ? " (" + amount + " ahead)" : s.amount() == you.amount() ? " (tied)" : " (" + amount + " behind)");
            }
        }
        if (!rivalText.isEmpty()) g.text(font, font.plainSubstrByWidth(rivalText.toString(), right - x), x, y + 10, 0xFFFFAA00, false);

        Picker picker = picker(rows);
        drawPicker(g, picker, x, right, tableInsetTop() + 6, mouseX, mouseY);

        int[] c = {x, x + 40, Math.max(x + 160, right - 120)};
        String valueHeader = tab == Tab.KILL_TIMES ? "Time" : (thisWeek && tab != Tab.EVENTS ? "Gained" : tab == Tab.SLAYER_XP ? "XP" : "Amount");
        int top = tableRowsTop(picker);
        int hy = top - 14;
        g.text(font, "Rank", c[0], hy, Sky2MGuiStyle.HEADING, false);
        g.text(font, "Player", c[1], hy, Sky2MGuiStyle.HEADING, false);
        g.text(font, valueHeader, right - font.width(valueHeader), hy, Sky2MGuiStyle.HEADING, false);
        g.fill(x - 2, hy + 10, right + 2, hy + 11, Sky2MGuiStyle.LINE);

        int visible = Math.max(1, (tableBottom() - top + 2) / TABLE_ROW);
        tableScroll = Math.max(0, Math.min(tableScroll, Math.max(0, rows.size() - visible)));
        for (int i = tableScroll; i < rows.size() && i < tableScroll + visible; i++) {
            Standing s = rows.get(i);
            int ry = top + (i - tableScroll) * TABLE_ROW;
            boolean rival = !s.self() && Sky2MRivals.isRival(s.player(), selected.key());
            boolean over = mouseX >= x - 2 && mouseX < right + 2 && mouseY >= ry - 2 && mouseY < ry + TABLE_ROW - 2;
            if (s.self()) g.fill(x - 2, ry - 2, right + 2, ry + TABLE_ROW - 2, 0xFF1F3324);
            else if (rival) g.fill(x - 2, ry - 2, right + 2, ry + TABLE_ROW - 2, 0xFF3A2A12);
            else if (over) g.fill(x - 2, ry - 2, right + 2, ry + TABLE_ROW - 2, Sky2MGuiStyle.ROW_HOVER);
            else if (i % 2 == 1) g.fill(x - 2, ry - 2, right + 2, ry + TABLE_ROW - 2, Sky2MGuiStyle.ROW);
            g.text(font, s.rank(), c[0], ry, s.rankColour(), false);
            g.text(font, font.plainSubstrByWidth((rival ? "⚔ " : "") + s.player(), right - c[1] - font.width(s.value()) - 10), c[1], ry,
                s.self() ? 0xFF55FF55 : rival ? 0xFFFFAA00 : Sky2MGuiStyle.TEXT, false);
            g.text(font, s.value(), right - font.width(s.value()), ry, s.self() ? 0xFF55FF55 : Sky2MGuiStyle.TEXT_DIM, false);
            if (over && !s.self()) g.setTooltipForNextFrame(font, rivalTooltip(s.player(), rival), java.util.Optional.empty(), mouseX, mouseY);
        }
        Sky2MGuiStyle.scrollbar(g, insetRight - 10, tableInsetTop() + 4, contentBottom() - 4, tableScroll, visible, rows.size());
    }

    /**
     * The rival picker: the 1-3 players ranked directly above you, or the bottom of the list when you're not on it
     * ("Closest to beat"). Null (hidden) when you're first, the table is too narrow, or it would leave the table fewer
     * than {@link #MIN_TABLE_ROWS} rows; at small sizes it shows fewer players first.
     */
    private Picker picker(List<Standing> rows) {
        if (rows == null || rows.isEmpty() || selected == null || tableRight() - tableLeft() < MIN_PICKER_WIDTH) return null;
        int room = tableBottom() + 2 - (tableInsetTop() + 6) - 14 - MIN_TABLE_ROWS * TABLE_ROW;
        int fit = Math.min(3, (room - PICK_ROW - 3) / PICK_ROW);
        if (fit < 1) return null;
        int youIndex = -1;
        for (int i = 0; i < rows.size() && youIndex < 0; i++) if (rows.get(i).self()) youIndex = i;
        Standing you = youIndex < 0 ? null : rows.get(youIndex);
        int end = youIndex < 0 ? rows.size() : youIndex;
        List<Standing> picks = new ArrayList<>();
        for (int i = Math.max(0, end - fit); i < end; i++) picks.add(rows.get(i));
        if (picks.isEmpty()) return null;
        return new Picker(you == null ? "Closest to beat" : "Closest above you", picks, you);
    }

    private static int pickerHeight(Picker picker) {
        return picker == null ? 0 : PICK_ROW * (picker.picks().size() + 1) + 3;
    }

    /** The picker's rows: rank, player, value and gap to you, and a [Rival] / [Rival ✓] button on the right. */
    private void drawPicker(GuiGraphicsExtractor g, Picker picker, int x, int right, int top, int mouseX, int mouseY) {
        if (picker == null) return;
        // A box like one of the settings' options, titled like an accordion.
        Sky2MGuiStyle.panel(g, x - 3, top - 3, right - x + 6, pickerHeight(picker) - 1, false);
        g.text(font, picker.title(), x, top, Sky2MGuiStyle.TEXT_DIM, false);
        int ry = top + PICK_ROW;
        for (Standing s : picker.picks()) {
            boolean rival = Sky2MRivals.isRival(s.player(), selected.key());
            String label = rival ? "[Rival ✓]" : "[Rival]";
            int bx = right - font.width(label);
            boolean over = mouseX >= bx - 2 && mouseX < right + 2 && mouseY >= ry - 2 && mouseY < ry + PICK_ROW - 2;
            if (over) g.fill(bx - 2, ry - 2, right + 2, ry + PICK_ROW - 2, Sky2MGuiStyle.ROW_HOVER);
            g.text(font, label, bx, ry, rival ? 0xFF55FF55 : over ? 0xFFFFFFFF : 0xFFFFAA00, false);
            pickButtons.add(new PickButton(bx - 2, ry - 2, right + 2, ry + PICK_ROW - 2, s.player()));

            g.text(font, s.rank(), x, ry, s.rankColour(), false);
            int nameX = x + 40, valueRight = bx - 8;
            // Value and gap when there's room for them and a name; the gap goes first, then the value.
            String gap = picker.you() == null ? "" : gapText(s, picker.you());
            String value = gap.isEmpty() ? s.value() : s.value() + "  " + gap;
            if (valueRight - nameX - font.width(value) < 40) value = s.value();
            if (valueRight - nameX - font.width(value) < 40) value = "";
            if (!value.isEmpty()) g.text(font, value, valueRight - font.width(value), ry, Sky2MGuiStyle.TEXT_DIM, false);
            int nameWidth = valueRight - nameX - (value.isEmpty() ? 0 : font.width(value) + 6);
            g.text(font, font.plainSubstrByWidth((rival ? "⚔ " : "") + s.player(), Math.max(0, nameWidth)), nameX, ry,
                rival ? 0xFFFFAA00 : Sky2MGuiStyle.TEXT, false);
            if (over) g.setTooltipForNextFrame(font, pickTooltip(s.player(), rival), java.util.Optional.empty(), mouseX, mouseY);
            ry += PICK_ROW;
        }
    }

    /** "(1,234 ahead)" or "(tied)": how far {@code s} is ahead of you. */
    private String gapText(Standing s, Standing you) {
        long gap = Math.abs(s.amount() - you.amount());
        if (gap == 0) return "(tied)";
        return "(" + (tab == Tab.KILL_TIMES ? SlayerTimes.format(gap) : fmt(gap)) + (s.amount() > you.amount() ? " ahead)" : " behind)");
    }

    /** What the picker's button does for this player. */
    private List<Component> pickTooltip(String player, boolean rival) {
        List<Component> lines = new ArrayList<>();
        if (!rival) {
            lines.add(Component.literal("Make " + player + " your rival on " + rivalBoardName()));
        } else if (Sky2MRivals.isRivalEverywhere(player)) {
            lines.add(Component.literal("Your rival on all boards").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Click: rival only on " + rivalBoardName()));
        } else {
            lines.add(Component.literal(player + " is your rival on " + rivalBoardName()).withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Click to undo"));
        }
        return lines;
    }

    /** The board's name for rivals ("Wheat", "Slayer XP Zombie", "Kill Times Revenant Horror V"). */
    private String rivalBoardName() {
        return switch (tab) {
            case COLLECTIONS, EVENTS -> selected.name();
            case SLAYER_XP -> "Slayer XP " + selected.name();
            case KILL_TIMES -> "Kill Times " + selected.id();
        };
    }

    /** What clicking a player does to their rival status, and which boards they're your rival on. */
    private List<Component> rivalTooltip(String player, boolean rival) {
        List<Component> lines = new ArrayList<>();
        if (!rival) {
            lines.add(Component.literal("Click: make " + player + " your rival on " + rivalBoardName()));
            List<String> others = Sky2MRivals.boardNamesOf(player);
            if (!others.isEmpty()) lines.add(Component.literal("Rival on: " + String.join(", ", others)).withStyle(ChatFormatting.GOLD));
        } else if (Sky2MRivals.isRivalEverywhere(player)) {
            lines.add(Component.literal("Your rival on all boards").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Click: rival only on " + rivalBoardName()));
            lines.add(Component.literal("Right-click: no longer your rival").withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(Component.literal("Rival on: " + String.join(", ", Sky2MRivals.boardNamesOf(player))).withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal("Click: no longer your rival on " + rivalBoardName()));
            lines.add(Component.literal("Right-click: no longer your rival on any board").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) return false;
        // The search field's border and icon, which the borderless EditBox inside it doesn't cover.
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && Sky2MGuiStyle.inSearch(searchBox, event.x(), event.y())) {
            setFocused(searchBox);
            return true;
        }
        // Tabs, filters and buttons, where they were drawn last frame.
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hit hit : List.copyOf(hits)) {
                if (inside(hit, event.x(), event.y())) {
                    Sky2MGuiStyle.click();
                    hit.action().run();
                    return true;
                }
            }
        }
        // The rival picker's buttons: rival on this board, or undo.
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && selected != null) {
            for (PickButton b : pickButtons) {
                if (event.x() >= b.x0() && event.x() < b.x1() && event.y() >= b.y0() && event.y() < b.y1()) {
                    Sky2MRivals.toggle(b.player(), selected.key(), rivalBoardName());
                    return true;
                }
            }
        }
        // A player in the standings: make them your rival on this board, or stop (right-click: on every board).
        List<Standing> rows = standings();
        int tableTop = tableRowsTop(picker(rows));
        if (rows != null && selected != null && event.x() >= tableLeft() - 2 && event.x() < tableRight() + 2 && event.y() >= tableTop - 2 && event.y() < tableBottom()) {
            int row = tableScroll + (int) ((event.y() - tableTop + 2) / TABLE_ROW);
            int visible = Math.max(1, (tableBottom() - tableTop + 2) / TABLE_ROW);
            if (row >= 0 && row < rows.size() && row < tableScroll + visible && !rows.get(row).self()) {
                String name = rows.get(row).player();
                if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) Sky2MRivals.toggle(name, selected.key(), rivalBoardName());
                else if (Sky2MRivals.isRival(name, selected.key())) Sky2MRivals.remove(name, null);
                return true;
            }
        }
        int x = listLeft(), w = listWidth(), y = listTop();
        if (event.x() < x - 2 || event.x() >= x + w + 2 || event.y() < y || event.y() >= y + listVisible() * LIST_ROW) return false;
        int index = listScroll + (int) ((event.y() - y) / LIST_ROW);
        if (index < 0 || index >= boards.size() || boards.get(index).header() != null) return false;
        Board b = boards.get(index);
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) {
            toggleOverHead(b);
            return true;
        }
        if (selected == null || !b.key().equals(selected.key())) select(b);
        return true;
    }

    private static final String HEAD_MARK = "⬆";
    /** The board shown over your head, read once per frame for the list's marker. */
    private String headBoard;

    /** Right-click on a board: show it over your head, or stop when it already is. */
    private void toggleOverHead(Board b) {
        if (!OwnNameTag.canShowOverHead(b.key())) {
            Sky2MAlerts.chat(Component.literal("Only collection, slayer XP and kill time boards can be shown over your head.").withStyle(ChatFormatting.RED));
            return;
        }
        boolean showing = OwnNameTag.toggleHeadBoard(b.key());
        String name = b.type().equals("pb") ? b.id() : b.name();
        Sky2MAlerts.chat(showing
            ? Component.literal("Showing " + name + " over your head (third person). Right-click it again to stop.").withStyle(ChatFormatting.AQUA)
            : Component.literal("Stopped showing " + name + " over your head.").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = (int) Math.signum(scrollY) * 3;
        if (mouseX < tablePanelLeft()) listScroll = Math.max(0, listScroll - step);
        else tableScroll = Math.max(0, tableScroll - step);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- helpers

    /** Until the next Monday 00:00 UTC, when the weekly boards start again. */
    private static long weekEndsIn() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
        java.time.ZonedDateTime next = now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY))
            .atStartOfDay(java.time.ZoneOffset.UTC);
        return java.time.Duration.between(now, next).toMillis();
    }

    private static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    private static String duration(long ms) {
        long minutes = Math.max(1, ms / 60_000L);
        if (minutes < 60) return minutes + "m";
        if (minutes < 48 * 60) return minutes / 60 + "h";
        return minutes / (60 * 24) + "d";
    }

    /** A board's category: the server's own grouping when it sent one, else Hypixel's (Other when unknown). */
    private static String categoryOf(Sky2MLeaderboards.StatBoard board) {
        String server = board.category();
        if (server != null && !server.isBlank()) {
            for (String c : CATEGORIES) if (c.equalsIgnoreCase(server.trim())) return c;
        }
        String clean = board.id() == null ? "" : board.id().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        String category = CATEGORY_BY_ID.get(clean);
        return category != null ? category : CATEGORY_BY_ID.getOrDefault(clean.replaceFirst(":\\d+$", ""), "Other");
    }

    /**
     * Collection ID -> skill category, from Hypixel's own resource API (api.hypixel.net/v2/resources/skyblock/collections),
     * for boards the server sends without one.
     */
    private static Map<String, String> buildCategories() {
        Map<String, String> out = new LinkedHashMap<>();
        String[][] groups = {
            {"Farming", "INK_SACK:3", "MOONFLOWER", "CARROT_ITEM", "DOUBLE_PLANT", "WILD_ROSE", "CACTUS", "RAW_CHICKEN",
                "SUGAR_CANE", "PUMPKIN", "WHEAT", "SEEDS", "MUSHROOM_COLLECTION", "RABBIT", "NETHER_STALK", "MUTTON",
                "MELON", "POTATO_ITEM", "LEATHER", "PORK", "FEATHER"},
            {"Mining", "INK_SACK:4", "REDSTONE", "UMBER", "COAL", "MYCEL", "ENDER_STONE", "QUARTZ", "SAND", "IRON_INGOT",
                "GEMSTONE_COLLECTION", "TUNGSTEN", "OBSIDIAN", "DIAMOND", "COBBLESTONE", "GLOWSTONE_DUST", "GOLD_INGOT",
                "GRAVEL", "HARD_STONE", "MITHRIL_ORE", "EMERALD", "SAND:1", "ICE", "GLACITE", "SULPHUR_ORE", "NETHERRACK"},
            {"Combat", "ENDER_PEARL", "CHILI_PEPPER", "SLIME_BALL", "MAGMA_CREAM", "GHAST_TEAR", "SULPHUR",
                "ROTTEN_FLESH", "SPIDER_EYE", "BONE", "BLAZE_ROD", "STRING"},
            {"Foraging", "SEA_LUMIES", "LOG_2", "RUBY_VEILSHROOM", "LOG:1", "VINESAP", "LOG:3", "LOG:2", "LUSHLILAC",
                "MANGROVE_LOG", "HELIX_LOG", "LOG", "HONEYCOMB", "FIG_LOG", "TENDER_WOOD", "LOG_2:1"},
            {"Fishing", "WATER_LILY", "PRISMARINE_SHARD", "INK_SACK", "RAW_FISH", "RAW_FISH:3", "LOTUS", "RAW_FISH:2",
                "RAW_FISH:1", "MAGMA_FISH", "PRISMARINE_CRYSTALS", "CLAY_BALL", "SPONGE"},
            {"Rift", "WILTED_BERBERIS", "METAL_HEART", "CADUCOUS_STEM", "AGARICUS_CAP", "HEMOVIBE", "HALF_EATEN_CARROT", "TIMITE"}
        };
        for (String[] group : groups) for (int i = 1; i < group.length; i++) out.put(group[i], group[0]);
        return Map.copyOf(out);
    }
}
