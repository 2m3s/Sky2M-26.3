package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.itemlist.ItemListData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /s2 rng: a compact, searchable table of the drops that Magic Find, Pet Luck or Overbloom change (every mob drop with
 * a chance in the NEU item list plus {@link RngSupplement} for Garden pests and the Harvest Feast), with the chance and
 * average tries after your stats. Drops are sorted into categories (tabs) and collapsible groups; the same item from
 * many mobs is one row (click it for its sources). Dungeon, Kuudra and gift drops are left out. Each row is badged with
 * the stats that affect it (M, P, O) and the formula is in its tooltip (see {@link RngCommand.Formula}).
 */
public final class RngScreen extends Screen {
    private static final int ROW = 10;
    private static final String[] FILTERS = {"All", "Magic Find", "Pet Luck", "Overbloom"};
    private static final String[] RARITIES = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
    private static final int[] RARITY_COLORS = {0xFFFFFFFF, 0xFF55FF55, 0xFF5577FF, 0xFFBB55FF, 0xFFFFAA00, 0xFFFF55FF};
    private static final Pattern LEVEL = Pattern.compile("\\[Lvl\\s*\\d+\\]\\s*");
    private static final Pattern PEST_PET = Pattern.compile("^(.*) Pet \\((\\w+)\\)$");
    private static final Pattern FLOOR = Pattern.compile("\\b[FM][1-7]\\b");

    enum Cat {
        ALL("All"), COMBAT("Combat"), SLAYERS("Slayers"), FISHING("Fishing"), MINING("Mining"), FARMING("Farming"),
        FORAGING("Foraging"), PETS("Pets"), OTHER("Other");

        final String label;

        Cat(String label) {
            this.label = label;
        }
    }

    private static final String[] DUNGEON = {"catacombs", "dungeon", "kuudra", "master mode", "bonzo", "scarf", "the professor",
        "thorn", "livid", "sadan", "maxor", "goldor", "necron", "wither king", "lost adventurer", "angry archaeologist",
        "frozen adventurer", "skeleton master", "super archer", "cellar spider", "shadow assassin", "crypt "};
    private static final String[] SLAYER = {"revenant", "tarantula", "sven", "voidgloom", "inferno demonlord", "riftstalker",
        "bloodfiend", "atoned", "deformed revenant", "pack ", "broodfather", "seraph", "demonlord", "slayer"};
    private static final String[] FISHING = {"squid", "sea ", "shark", "yeti", "grinch", "frozen steve", "snowman", "carrot king",
        "thunder", "lord jawbus", "jawbus", "magma slug", "moogma", "lava leech", "pyroclastic", "lava flame", "fire eel",
        "taurus", "flaming worm", "plhlegblast", "abyssal", "oasis", "water hydra", "reindrake", "wiki tiki", "titanoboa",
        "nightmare", "phantom fisher", "ghost fisher", "grim reaper", "agarimoo", "trophy", "catfish", "megalodon", "bayou",
        "alligator", "blue ringed", "stingray", "siren", "fishing"};
    private static final String[] MINING = {"goblin", "glacite", "ice walker", "treasure hoarder", "worm", "scatha", "yog",
        "corleone", "sentry", "mithril golem", "tunnel", "mineshaft", "star sentry", "mining"};
    private static final String[] FORAGING = {"galatea", "hunting", "lumber", "foraging", "sapling"};
    private static final String[] OTHER = {"minos", "gaia construct", "siamese lynx", "minotaur", "diana", "mythological"};

    /** One place an item drops from. */
    private record Src(String source, RngCommand.Formula formula, double base, boolean[] tags) {}

    private static final class Item {
        final String key;
        final String name;
        final Cat cat;
        final String group;
        final int color;
        final boolean dragon;
        final List<Src> srcs = new ArrayList<>();
        final boolean[] tags = new boolean[3];
        Src best;

        Item(String key, String name, Cat cat, String group, int color, boolean dragon) {
            this.key = key;
            this.name = name;
            this.cat = cat;
            this.group = group;
            this.color = color;
            this.dragon = dragon;
        }

        void add(Src s) {
            srcs.add(s);
            for (int i = 0; i < 3; i++) tags[i] |= s.tags()[i];
            if (best == null || s.base() > best.base()) best = s;
        }

        boolean affected() {
            return tags[0] || tags[1] || tags[2];
        }
    }

    /** A displayed line: a group header, an item or one of an item's sources. */
    private record Line(int type, Item item, Src src, String key, String text, int count, boolean open) {}

    private static final int GROUP = 0, ITEM = 1, SOURCE = 2;

    private final String initialSearch;
    private final List<Item> items = new ArrayList<>();
    // Which groups you opened: kept while the game runs (closing the menu keeps them), every group closed on launch.
    private static final Map<String, Boolean> open = new HashMap<>();
    private List<Line> lines = List.of();
    private boolean dirty = true;
    private boolean built;
    private EditBox magicFind;
    private EditBox petLuck;
    private EditBox overbloom;
    private EditBox looting;
    private EditBox luck;
    private EditBox search;
    private Button[] filterButtons;
    private Button unaffectedButton;
    private int filter;
    private boolean showUnaffected;
    private Cat cat = Cat.ALL;
    private int shown;
    private int scroll;

    /** {@code search} null keeps the remembered search text. */
    public RngScreen(String search) {
        super(Component.literal("RNG Drop Chances"));
        this.initialSearch = search;
        FeatureConfigs.RareDrops s = store();
        if (s != null) {
            try {
                cat = Cat.valueOf(s.rngCategory);
            } catch (Exception ignored) {}
        }
    }

    private static FeatureConfigs.RareDrops store() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat.rareDrops;
    }

    private int panelW() {
        return Math.min(460, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    private int tableTop() {
        return 112;
    }

    private int tableBottom() {
        return height - 28;
    }

    private EditBox box(String label, int x, int w, String value) {
        EditBox b = new EditBox(font, x, 22, w, 16, Component.literal(label));
        b.setMaxLength(12);
        b.setValue(value);
        b.setHint(Component.literal("0").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(b);
        return b;
    }

    private void refreshButtons() {
        for (int i = 0; i < filterButtons.length; i++) {
            filterButtons[i].setMessage(Component.literal(i == filter ? "> " + FILTERS[i] + " <" : FILTERS[i]));
        }
        unaffectedButton.setMessage(Component.literal("Unaffected: " + (showUnaffected ? "on" : "off")));
    }

    @Override
    protected void init() {
        clearWidgets();
        FeatureConfigs.RareDrops s = store();
        int l = left();
        int w = panelW();
        // Keep what was typed on a resize.
        String mf = magicFind != null ? magicFind.getValue() : s == null ? "" : s.rngMagicFind;
        String pl = petLuck != null ? petLuck.getValue() : s == null ? "" : s.rngPetLuck;
        String ob = overbloom != null ? overbloom.getValue() : s == null ? "" : s.rngOverbloom;
        String lo = looting != null ? looting.getValue() : s == null ? "" : s.rngLooting;
        String lu = luck != null ? luck.getValue() : s == null ? "" : s.rngLuck;
        String q = search != null ? search.getValue() : initialSearch != null ? initialSearch : s == null ? "" : s.rngSearch;
        int bw = (w - 4 * 4) / 5;
        magicFind = box("Magic Find", l, bw, mf);
        petLuck = box("Pet Luck", l + (bw + 4), bw, pl);
        overbloom = box("Overbloom", l + (bw + 4) * 2, bw, ob);
        looting = box("Looting", l + (bw + 4) * 3, bw, lo);
        luck = box("Luck", l + (bw + 4) * 4, bw, lu);
        search = new EditBox(font, l, 42, w, 16, Component.literal("Search"));
        search.setMaxLength(64);
        search.setValue(q);
        search.setHint(Component.literal("Search drops or sources...").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(v -> {
            scroll = 0;
            dirty = true;
        });
        addRenderableWidget(search);
        filterButtons = new Button[FILTERS.length];
        for (int i = 0; i < FILTERS.length; i++) {
            final int index = i;
            filterButtons[i] = Button.builder(Component.literal(FILTERS[i]), b -> {
                filter = index;
                scroll = 0;
                dirty = true;
                refreshButtons();
            }).bounds(l + (bw + 4) * i, 62, bw, 16).build();
            addRenderableWidget(filterButtons[i]);
        }
        unaffectedButton = Button.builder(Component.literal("Unaffected"), b -> {
            showUnaffected = !showUnaffected;
            scroll = 0;
            dirty = true;
            refreshButtons();
        }).bounds(l + (bw + 4) * 4, 62, bw, 16).build();
        addRenderableWidget(unaffectedButton);
        refreshButtons();
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width / 2 - 50, height - 24, 100, 20).build());
        setInitialFocus(search);
        dirty = true;
    }

    // ---------------------------------------------------------------- data

    private static boolean has(String text, String[] words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    private static boolean pet(ItemListData.Entry item) {
        return item != null && item.category() == ItemListData.Category.PET;
    }

    private static double chanceOf(ItemListData.Drop d) {
        double base = RngCommand.parseChance(d.chance());
        if (base <= 0 && d.extra() != null) {
            for (String e : d.extra()) {
                base = RngCommand.parseChance(e);
                if (base > 0) break;
            }
        }
        return base;
    }

    /** A pet's rarity index (0 common to 5 mythic) from its id suffix or the colour of its name, or -1. */
    private static int rarityOf(ItemListData.Entry item, String id) {
        int semi = id.lastIndexOf(';');
        if (semi >= 0) {
            try {
                int r = Integer.parseInt(id.substring(semi + 1));
                if (r >= 0 && r < RARITIES.length) return r;
            } catch (NumberFormatException ignored) {}
        }
        if (item != null) {
            String raw = item.name();
            int bracket = raw.indexOf(']');
            Matcher m = Pattern.compile("§([0-9a-f])").matcher(bracket >= 0 ? raw.substring(bracket + 1) : raw);
            if (m.find()) {
                switch (m.group(1).charAt(0)) {
                    case 'f' -> {
                        return 0;
                    }
                    case 'a' -> {
                        return 1;
                    }
                    case '9' -> {
                        return 2;
                    }
                    case '5' -> {
                        return 3;
                    }
                    case '6' -> {
                        return 4;
                    }
                    case 'd' -> {
                        return 5;
                    }
                    default -> {}
                }
            }
        }
        return -1;
    }

    private static Cat categoryOf(String source, boolean farming) {
        if (farming) return Cat.FARMING;
        String s = source.toLowerCase(Locale.ROOT);
        if (has(s, SLAYER)) return Cat.SLAYERS;
        if (has(s, FISHING)) return Cat.FISHING;
        if (has(s, MINING)) return Cat.MINING;
        if (has(s, FORAGING)) return Cat.FORAGING;
        if (has(s, OTHER)) return Cat.OTHER;
        return Cat.COMBAT;
    }

    private void addDrop(Map<String, Item> map, Set<String> seen, String rawName, String id, ItemListData.Entry entry,
                         String source, double base, RngCommand.Formula formula, boolean pet, int rarity, boolean farming) {
        String name = LEVEL.matcher(rawName).replaceAll("").trim();
        String lower = (name + " " + id + " " + source).toLowerCase(Locale.ROOT);
        if (lower.contains("gift") || lower.contains("present")) return;
        Cat c = pet ? Cat.PETS : categoryOf(source, farming);
        boolean dragon = pet && name.toLowerCase(Locale.ROOT).contains("dragon") && !name.toLowerCase(Locale.ROOT).contains("rose")
            && source.toLowerCase(Locale.ROOT).contains("dragon");
        String group;
        if (pet) group = dragon ? "Dragon Pets" : "Pets: " + (rarity >= 0 ? RARITIES[rarity] : "Other");
        else if (c == Cat.FARMING && source.startsWith("Harvest Feast")) group = "Harvest Feast";
        else group = source;
        String key = c.name() + "|" + name.toLowerCase(Locale.ROOT) + "|" + (pet ? rarity : -2);
        if (!seen.add(key + "|" + source.toLowerCase(Locale.ROOT) + "|" + base)) return;
        int color = pet && rarity >= 0 ? RARITY_COLORS[rarity] : 0xFF55FFFF;
        Item item = map.computeIfAbsent(key, k -> new Item(k, name, c, group, color, dragon));
        item.add(new Src(source, formula, base, RngCommand.affectedBy(formula, base)));
    }

    private void build() {
        Map<String, Item> map = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (ItemListData.Entry mob : ItemListData.entries()) {
            if (mob.category() != ItemListData.Category.MOB) continue;
            String mobLore = mob.lore() == null ? "" : ItemListData.strip(String.join(" ", mob.lore())).toLowerCase(Locale.ROOT);
            for (ItemListData.Recipe r : ItemListData.usages(mob.id())) {
                if (!(r instanceof ItemListData.Drops drops) || !mob.id().equals(drops.owner())) continue;
                String source = ItemListData.strip(drops.name() == null ? mob.name() : drops.name());
                String raw = source + " " + (drops.extra() == null ? "" : ItemListData.strip(String.join(" ", drops.extra())));
                String dungeonText = (raw + " " + mobLore).toLowerCase(Locale.ROOT);
                // No dungeon, Kuudra or Master Mode sources.
                if (has(dungeonText, DUNGEON) || FLOOR.matcher(raw).find()) continue;
                boolean farmingSource = source.toLowerCase(Locale.ROOT).contains("pest");
                for (ItemListData.Drop d : drops.drops()) {
                    String id = d.item().id();
                    if (id.isEmpty() || id.equals("SKYBLOCK_COIN")) continue;
                    double base = chanceOf(d);
                    if (base <= 0) continue;
                    ItemListData.Entry item = ItemListData.entry(id);
                    if (item != null && item.lore() != null && !item.lore().isEmpty()
                        && ItemListData.strip(item.lore().get(item.lore().size() - 1)).toUpperCase(Locale.ROOT).contains("DUNGEON")) continue;
                    String name = item == null ? id : ItemListData.strip(item.name());
                    boolean pet = pet(item);
                    RngCommand.Formula formula = RngCommand.formulaFor(name, source, pet);
                    // Farming shows only the rare pest drops (the 15% in-season rare crops come from RngSupplement).
                    if ((formula == RngCommand.Formula.PEST || formula == RngCommand.Formula.PEST_PET) && base > 0.05) continue;
                    addDrop(map, seen, name, id, item, source, base, formula, pet, pet ? rarityOf(item, id) : -1, farmingSource);
                }
            }
        }
        for (RngSupplement.Drop d : RngSupplement.drops()) {
            String name = d.name();
            int rarity = -1;
            boolean pet = d.formula() == RngCommand.Formula.PEST_PET;
            if (pet) {
                Matcher m = PEST_PET.matcher(name);
                if (m.matches()) {
                    name = m.group(1);
                    for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equalsIgnoreCase(m.group(2))) rarity = i;
                }
            }
            // The item list may already have it (same drop from the same pest).
            String pestName = d.source().substring(d.source().indexOf(' ') + 1).toLowerCase(Locale.ROOT);
            boolean have = false;
            for (Item i : map.values()) {
                if (!i.name.equalsIgnoreCase(name)) continue;
                for (Src s : i.srcs) if (s.source().toLowerCase(Locale.ROOT).contains(pestName)) have = true;
            }
            if (!have) addDrop(map, seen, name, name, null, d.source(), d.chance(), d.formula(), pet, rarity, true);
        }
        items.clear();
        items.addAll(map.values());
        built = true;
        dirty = true;
    }

    private static double num(EditBox b) {
        try {
            String t = b.getValue();
            return t.isEmpty() || t.equals(".") ? 0 : Math.min(100000, Double.parseDouble(t));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void rebuildLines() {
        dirty = false;
        String f = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Item> vis = new ArrayList<>();
        for (Item i : items) {
            if (cat != Cat.ALL && i.cat != cat) continue;
            if (filter == 0 ? !i.affected() && !showUnaffected : !i.tags[filter - 1]) continue;
            if (!f.isEmpty()) {
                boolean match = i.name.toLowerCase(Locale.ROOT).contains(f);
                for (Src s : i.srcs) if (s.source().toLowerCase(Locale.ROOT).contains(f)) match = true;
                if (!match) continue;
            }
            vis.add(i);
        }
        shown = vis.size();
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        vis.sort(Comparator.comparing((Item i) -> i.cat.ordinal())
            .thenComparing(i -> i.group.equals("Dragon Pets") ? 0 : 1)
            .thenComparing(i -> i.group.toLowerCase(Locale.ROOT))
            .thenComparing(i -> i.name.toLowerCase(Locale.ROOT)));
        for (Item i : vis) groups.computeIfAbsent(i.cat.name() + "|" + i.group, k -> new ArrayList<>()).add(i);
        List<Line> out = new ArrayList<>();
        for (Map.Entry<String, List<Item>> g : groups.entrySet()) {
            Item first = g.getValue().get(0);
            boolean isOpen = open.getOrDefault(g.getKey(), !f.isEmpty());
            String title = (cat == Cat.ALL && first.cat != Cat.PETS ? first.cat.label + ": " : "") + first.group;
            out.add(new Line(GROUP, first, null, g.getKey(), title, g.getValue().size(), isOpen));
            if (!isOpen) continue;
            for (Item i : g.getValue()) {
                boolean srcOpen = i.srcs.size() > 1 && open.getOrDefault("s|" + i.key, false);
                out.add(new Line(ITEM, i, i.best, i.srcs.size() > 1 ? "s|" + i.key : null, i.name, i.srcs.size(), srcOpen));
                if (srcOpen) {
                    List<Src> sorted = new ArrayList<>(i.srcs);
                    sorted.sort(Comparator.comparingDouble((Src s) -> -s.base()));
                    for (Src s : sorted) out.add(new Line(SOURCE, i, s, null, s.source(), 0, false));
                }
            }
        }
        lines = out;
    }

    // ---------------------------------------------------------------- UI

    private String clip(String s, int w) {
        if (font.width(s) <= w) return s;
        while (s.length() > 1 && font.width(s + "..") > w) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private static String trimNumber(double v) {
        String t = String.format(Locale.US, "%.1f", v);
        return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t;
    }

    /** 12500 -> "12.5k". */
    static String abbreviate(double n) {
        if (n >= 1e9) return trimNumber(n / 1e9) + "B";
        if (n >= 1e6) return trimNumber(n / 1e6) + "M";
        if (n >= 1e3) return trimNumber(n / 1e3) + "k";
        return String.format(Locale.US, "%.0f", n);
    }

    private List<Cat> tabs() {
        List<Cat> out = new ArrayList<>();
        out.add(Cat.ALL);
        for (Cat c : Cat.values()) {
            if (c == Cat.ALL) continue;
            for (Item i : items) {
                if (i.cat == c) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    private int tabX(List<Cat> tabs, int index) {
        int x = left();
        for (int i = 0; i < index; i++) x += font.width(tabs.get(i).label) + 10;
        return x;
    }

    private int tabY() {
        return 82;
    }

    @Override
    public void tick() {
        if (!built) {
            if (ItemListData.loaded()) build();
            else ItemListData.ensureLoaded();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        if (built && dirty) rebuildLines();
        int l = left();
        int w = panelW();
        int r = l + w;
        g.centeredText(font, "RNG Drop Chances" + (built ? "  -  " + shown + " drops" : ""), width / 2, 4, 0xFFFFD35A);
        int bw = (w - 4 * 4) / 5;
        String[] labels = {"Magic Find", "Pet Luck", "Overbloom", "Looting lvl", "Luck lvl (armor)"};
        for (int i = 0; i < 5; i++) g.text(font, clip(labels[i], bw), l + (bw + 4) * i, 13, 0xFFAAAAAA, false);

        // Category tabs.
        List<Cat> tabs = tabs();
        if (!tabs.contains(cat)) cat = Cat.ALL;
        for (int i = 0; i < tabs.size(); i++) {
            Cat c = tabs.get(i);
            int x = tabX(tabs, i);
            int tw = font.width(c.label) + 8;
            boolean on = c == cat;
            boolean hover = mouseX >= x && mouseX < x + tw && mouseY >= tabY() && mouseY < tabY() + 12;
            g.fill(x, tabY(), x + tw, tabY() + 12, on ? 0xFF3B5B8B : hover ? 0xFF2A3345 : 0xFF1A2130);
            g.text(font, c.label, x + 4, tabY() + 2, on ? 0xFFFFFFFF : 0xFF9AA5B8, false);
        }

        int top = tableTop();
        int bottom = tableBottom();
        g.text(font, "Drop", l + 4, top - 10, 0xFF9AA5B8, false);
        g.text(font, "Source", l + 138, top - 10, 0xFF9AA5B8, false);
        g.text(font, "Stats", l + 220, top - 10, 0xFF9AA5B8, false);
        g.text(font, "Base", l + 250, top - 10, 0xFF9AA5B8, false);
        g.text(font, "Now", l + 304, top - 10, 0xFF9AA5B8, false);
        g.text(font, "Tries", l + 358, top - 10, 0xFF9AA5B8, false);
        g.fill(l, top, r, bottom, 0xC0121722);
        g.outline(l, top, w, bottom - top, 0xFF3B465B);

        if (!built) {
            String status = ItemListData.status();
            g.centeredText(font, status.isEmpty() ? "Loading the item list (it holds the drop chances)..." : status, width / 2, top + 20, 0xFFAAAAAA);
            g.centeredText(font, "This fills in by itself once it's ready.", width / 2, top + 32, 0xFF666666);
            return;
        }
        if (lines.isEmpty()) {
            g.centeredText(font, items.isEmpty() ? "No drop chances found in the item list yet." : "No drop matches.", width / 2, top + 20, 0xFFFF5555);
            return;
        }
        double mf = num(magicFind), pl = num(petLuck), ob = num(overbloom), lo = num(looting), lu = num(luck);
        int visible = (bottom - top - 2) / ROW;
        scroll = Math.max(0, Math.min(scroll, lines.size() - visible));
        Line hovered = null;
        g.enableScissor(l + 1, top + 1, r - 1, bottom - 1);
        for (int i = scroll; i < Math.min(lines.size(), scroll + visible + 1); i++) {
            Line line = lines.get(i);
            int y = top + 1 + (i - scroll) * ROW;
            boolean hover = mouseX >= l && mouseX < r && mouseY >= y && mouseY < y + ROW && mouseY < bottom && mouseY >= top;
            if (hover) hovered = line;
            if (line.type() == GROUP) {
                g.fill(l + 1, y, r - 1, y + ROW, hover ? 0xFF2A3345 : 0xFF1A2130);
                g.text(font, (line.open() ? "- " : "+ ") + clip(line.text(), w - 60) + " (" + line.count() + ")", l + 3, y + 1, 0xFFFFD35A, false);
                continue;
            }
            if (hover) g.fill(l + 1, y, r - 1, y + ROW, 0x20FFFFFF);
            Src s = line.src();
            double now = RngCommand.chance(s.formula(), s.base(), mf, pl, ob, lo, lu);
            boolean boosted = now > s.base() * 1.0000001;
            if (line.type() == ITEM) {
                String more = line.count() > 1 ? " +" + (line.count() - 1) : "";
                g.text(font, clip(line.text(), 128 - font.width(more)) + "", l + 8, y + 1, line.item().color, false);
                if (!more.isEmpty()) g.text(font, more, l + 8 + font.width(clip(line.text(), 128 - font.width(more))), y + 1, 0xFF888888, false);
                g.text(font, clip(s.source(), 78), l + 138, y + 1, 0xFFAAAAAA, false);
                g.text(font, "M", l + 220, y + 1, line.item().tags[0] ? 0xFF55FF55 : 0xFF444444, false);
                g.text(font, "P", l + 229, y + 1, line.item().tags[1] ? 0xFFFF55FF : 0xFF444444, false);
                g.text(font, "O", l + 238, y + 1, line.item().tags[2] ? 0xFFFFAA00 : 0xFF444444, false);
            } else {
                g.text(font, clip(line.text(), 112), l + 20, y + 1, 0xFF8899AA, false);
                boolean[] t = s.tags();
                g.text(font, "M", l + 220, y + 1, t[0] ? 0xFF55FF55 : 0xFF444444, false);
                g.text(font, "P", l + 229, y + 1, t[1] ? 0xFFFF55FF : 0xFF444444, false);
                g.text(font, "O", l + 238, y + 1, t[2] ? 0xFFFFAA00 : 0xFF444444, false);
            }
            g.text(font, RngCommand.percent(s.base()), l + 250, y + 1, 0xFF888888, false);
            g.text(font, RngCommand.percent(now), l + 304, y + 1, boosted ? 0xFF55FF55 : 0xFFCCCCCC, false);
            g.text(font, "1 in " + abbreviate(1.0 / now), l + 358, y + 1, 0xFFCCCCCC, false);
        }
        g.disableScissor();
        if (hovered != null && hovered.type() != GROUP && hovered.src() != null) {
            Src s = hovered.src();
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(hovered.item().name + " (" + s.source() + ")").withStyle(ChatFormatting.AQUA));
            tip.add(Component.literal(s.formula().label + ": " + s.formula().formula).withStyle(ChatFormatting.YELLOW));
            tip.add(Component.literal(s.formula().note).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal("M = Magic Find, P = Pet Luck, O = Overbloom").withStyle(ChatFormatting.DARK_GRAY));
            tip.add(Component.literal("Base " + RngCommand.chanceText(s.base())).withStyle(ChatFormatting.DARK_GRAY));
            if (hovered.type() == ITEM && hovered.count() > 1) {
                tip.add(Component.literal("Click for all " + hovered.count() + " sources").withStyle(ChatFormatting.DARK_GRAY));
            }
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double x = event.x(), y = event.y();
        if (built) {
            List<Cat> tabs = tabs();
            for (int i = 0; i < tabs.size(); i++) {
                int tx = tabX(tabs, i);
                if (x >= tx && x < tx + font.width(tabs.get(i).label) + 8 && y >= tabY() && y < tabY() + 12) {
                    cat = tabs.get(i);
                    scroll = 0;
                    dirty = true;
                    return true;
                }
            }
            int l = left();
            if (x >= l && x < l + panelW() && y >= tableTop() && y < tableBottom()) {
                int index = scroll + (int) ((y - tableTop() - 1) / ROW);
                if (index >= 0 && index < lines.size()) {
                    Line line = lines.get(index);
                    if (line.key() != null) {
                        open.put(line.key(), !line.open());
                        dirty = true;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 3;
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override
    public void removed() {
        FeatureConfigs.RareDrops s = store();
        if (s == null || magicFind == null) return;
        s.rngMagicFind = magicFind.getValue();
        s.rngPetLuck = petLuck.getValue();
        s.rngOverbloom = overbloom.getValue();
        s.rngLooting = looting.getValue();
        s.rngLuck = luck.getValue();
        s.rngSearch = search.getValue();
        s.rngCategory = cat.name();
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
