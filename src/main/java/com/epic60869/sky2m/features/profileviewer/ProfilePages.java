package com.epic60869.sky2m.features.profileviewer;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.profileviewer.ProfileItems.PvItem;
import com.epic60869.sky2m.features.profileviewer.ProfileStats.Level;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.epic60869.sky2m.features.profileviewer.ProfileExtras.titleCase;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.BAR_BG;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.GOLD;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.H;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.INSET;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.INSET_EDGE;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.LABEL;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.SLOT;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.SLOT_BG;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.VALUE;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.W;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.in;
import static com.epic60869.sky2m.features.profileviewer.ProfileViewerScreen.inset;

/**
 * The Profile Viewer's pages after Pets: Collections, Mining and Foraging, Bestiary, Crimson Isle, Museum, Rift,
 * Farming and Garden, Networth, Weight and Misc. Ported from Killer560's Mod (MIT,
 * https://github.com/Killer560-OG/killer560s-mod, profileviewer/screen/ExtraPages and WeightPage, themselves modelled on
 * skyblock-pv), redrawn in Sky2M's NEU-style theme. Remote data (tables, museum, garden, rank, prices) loads in the
 * background and each page shows "Loading..." until it's there.
 */
final class ProfilePages {
    private static final int GOOD = 0xFF55FF55, BAD = 0xFFFF5555, DIM = 0xFF777777, BAR = 0xFF55AA55, BAR_MAX = 0xFFFFAA00;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ROOT);

    private final ProfileViewerScreen s;
    private final Map<JsonObject, ProfileExtras> extras = new IdentityHashMap<>();
    private final Map<JsonObject, SkyblockWeight.Both> weights = new IdentityHashMap<>();
    private final Map<JsonObject, CompletableFuture<Networth.Result>> networth = new IdentityHashMap<>();
    private final Map<JsonObject, Boolean> networthWithMuseum = new IdentityHashMap<>();
    private final Map<JsonObject, MuseumView> museums = new IdentityHashMap<>();
    private final Map<String, ItemStack> icons = new HashMap<>();

    /** Content area, set each frame. */
    private int cx, cy, cw, ch;

    private record MuseumView(double value, boolean appraised, Map<String, List<PvItem>> items, Set<String> borrowing, List<PvItem> special) {}

    ProfilePages(ProfileViewerScreen screen) {
        this.s = screen;
    }

    void reset() {
        networth.clear();
        networthWithMuseum.clear();
    }

    void draw(GuiGraphicsExtractor g, ProfileViewerScreen.Page page, int left, int top, int mx, int my) {
        cx = left + 6;
        cy = top + 18;
        cw = W - 12;
        ch = H - 24;
        g.text(f(), page.label, left + 8, top + 6, GOLD, true);
        switch (page) {
            case COLLECTIONS -> collections(g, mx, my);
            case MINING -> mining(g, mx, my);
            case BESTIARY -> bestiary(g, mx, my);
            case CRIMSON -> crimson(g, mx, my);
            case MUSEUM -> museum(g, mx, my);
            case RIFT -> rift(g, mx, my);
            case FARMING -> farming(g, mx, my);
            case NETWORTH -> networth(g, mx, my);
            case WEIGHT -> weight(g, mx, my);
            default -> misc(g, mx, my);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Font f() {
        return s.font();
    }

    private JsonObject member() {
        return s.member();
    }

    private ProfileExtras ex() {
        JsonObject m = member();
        return extras.computeIfAbsent(m, k -> new ProfileExtras(s.profile().profile(), k));
    }

    private void text(GuiGraphicsExtractor g, String str, int x, int y, int colour) {
        g.text(f(), str, x, y, colour, true);
    }

    private void title(GuiGraphicsExtractor g, String str, int x, int y) {
        g.text(f(), str, x, y, GOLD, true);
    }

    private void right(GuiGraphicsExtractor g, String str, int rightX, int y, int colour) {
        g.text(f(), str, rightX - f().width(str), y, colour, true);
    }

    /** A label on the left and its value right-aligned; returns the next line's y. */
    private int stat(GuiGraphicsExtractor g, int x, int y, int w, String label, String value, int colour) {
        text(g, label, x, y, LABEL);
        right(g, f().plainSubstrByWidth(value, Math.max(10, w - f().width(label) - 6)), x + w, y, colour);
        return y + 10;
    }

    private int stat(GuiGraphicsExtractor g, int x, int y, int w, String label, String value) {
        return stat(g, x, y, w, label, value, VALUE);
    }

    private void center(GuiGraphicsExtractor g, String str, int x, int w, int colour) {
        g.centeredText(f(), str, x + w / 2, cy + ch / 2 - 4, colour);
    }

    private static Component line(String str, ChatFormatting colour) {
        return Component.literal(str).withStyle(colour);
    }

    private void tip(Component... lines) {
        s.tooltip = List.of(lines);
    }

    private void tip(List<Component> lines) {
        s.tooltip = lines;
    }

    /** A button: highlighted when {@code selected}; runs {@code action} when clicked. */
    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, int mx, int my, boolean selected, Runnable action) {
        boolean over = in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, selected ? 0xFF3A3A3A : over ? 0xFF2E2E2E : INSET);
        g.outline(x, y, w, h, selected ? 0xFF6A6A6A : INSET_EDGE);
        String shown = f().plainSubstrByWidth(label, w - 6);
        g.text(f(), shown, x + (w - f().width(shown)) / 2, y + (h - 8) / 2, selected ? GOLD : VALUE, true);
        s.hotspots.add(new ProfileViewerScreen.Hotspot(x, y, w, h, action));
    }

    /** A column of view buttons on the left; returns the selected one. */
    private int nav(GuiGraphicsExtractor g, String[] views, int x, int y, int w, int mx, int my) {
        s.subView = Math.clamp(s.subView, 0, views.length - 1);
        for (int i = 0; i < views.length; i++) {
            int index = i;
            button(g, x, y + i * 17, w, 15, views[i], mx, my, i == s.subView, () -> {
                if (s.subView != index) {
                    s.subView = index;
                    s.pageScroll = s.subPage = 0;
                }
            });
        }
        return s.subView;
    }

    private int clampScroll(int rows, int visible) {
        s.pageScroll = Math.clamp(s.pageScroll, 0, Math.max(0, rows - visible));
        return s.pageScroll;
    }

    private void scrollbar(GuiGraphicsExtractor g, int x, int y, int h, int rows, int visible) {
        if (rows <= visible) return;
        g.fill(x, y, x + 2, y + h, 0xFF0C0C0C);
        int thumb = Math.max(8, h * visible / rows);
        int ty = y + (h - thumb) * s.pageScroll / Math.max(1, rows - visible);
        g.fill(x, ty, x + 2, ty + thumb, 0xFF6A6A6A);
    }

    private static void progress(GuiGraphicsExtractor g, int x, int y, int w, double progress, boolean maxed) {
        g.fill(x, y, x + w, y + 3, BAR_BG);
        g.fill(x, y, x + (int) Math.round(w * (maxed ? 1 : Math.clamp(progress, 0, 1))), y + 3, maxed ? BAR_MAX : BAR);
    }

    /** A level from a table where level n starts at {@code table[n - 1]} (HOTM, HOTF, garden). */
    private static Level thresholdLevel(long[] table, long xp) {
        int level = 0;
        while (level < table.length && xp >= table[level]) level++;
        long prev = level == 0 ? 0 : table[level - 1];
        if (level >= table.length) return new Level(level, 1, xp, xp - prev, 0, true);
        long need = table[level] - prev;
        return new Level(level, need <= 0 ? 1 : Math.clamp((double) (xp - prev) / need, 0, 1), xp, xp - prev, need, false);
    }

    /** The garden level; with Overflow Levels, SkyHanni's overflow past the max: 10,000 garden XP a level. */
    private static Level gardenLevel(long[] table, long xp) {
        if (!ProfileStats.overflow() || table.length == 0 || xp < table[table.length - 1]) return thresholdLevel(table, xp);
        long past = xp - table[table.length - 1];
        long extra = past / GARDEN_OVERFLOW_XP, into = past % GARDEN_OVERFLOW_XP;
        return new Level(table.length + (int) extra, (double) into / GARDEN_OVERFLOW_XP, xp, into, GARDEN_OVERFLOW_XP, false, true);
    }

    private static final long GARDEN_OVERFLOW_XP = 10_000;

    /**
     * A crop milestone, and with Overflow Levels SkyHanni's overflow tiers past the max (each needing as many crops as
     * the last tier): {milestone, crops into the next one, crops it needs}.
     */
    private static long[] cropMilestone(long[] cum, long amount) {
        int milestone = countAtOrBelow(cum, amount);
        if (cum.length == 0) return new long[]{milestone, 0, 0};
        if (milestone < cum.length) {
            long prev = milestone == 0 ? 0 : cum[milestone - 1];
            return new long[]{milestone, amount - prev, cum[milestone] - prev};
        }
        long last = cum.length > 1 ? cum[cum.length - 1] - cum[cum.length - 2] : cum[0];
        if (!ProfileStats.overflow() || last <= 0) return new long[]{milestone, 0, 0};
        long past = amount - cum[cum.length - 1];
        return new long[]{cum.length + past / last, past % last, last};
    }

    private static int countAtOrBelow(long[] thresholds, long amount) {
        int n = 0;
        for (long t : thresholds) if (amount >= t) n++;
        return n;
    }

    private static String commas(double v) {
        return ProfileStats.commas(v);
    }

    private static String shorten(double v) {
        return ProfileStats.shortNumber(v);
    }

    private static String date(long ms) {
        return ms <= 0 ? "-" : DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String dateTime(long ms) {
        return ms <= 0 ? "-" : DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String duration(long seconds) {
        long d = seconds / 86400, h = seconds / 3600 % 24, m = seconds / 60 % 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m " + seconds % 60 + "s";
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    /** A SkyBlock item's icon by id, or a vanilla item ("minecraft:..." or a plain vanilla id). */
    private ItemStack icon(String id) {
        return icons.computeIfAbsent(id, key -> {
            ItemStack repo = ProfileItems.icon(key);
            if (!repo.is(Items.BARRIER)) return repo;
            Item item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(key.toLowerCase(Locale.ROOT).replace(':', '_').replace("minecraft_", "minecraft:")))
                .orElse(Items.PAPER);
            return new ItemStack(item);
        });
    }

    private void slot(GuiGraphicsExtractor g, PvItem item, int x, int y, int mx, int my) {
        s.slot(g, item, x, y, mx, my);
    }

    // ------------------------------------------------------------------------------------------------ collections

    private void collections(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras ex = ex();
        if (ex.collections == null) {
            center(g, "Their Collections API is off.", cx, cw, DIM);
            return;
        }
        CompletableFuture<List<ProfileTables.CollectionCategory>> tf = ProfileTables.collections();
        List<ProfileTables.CollectionCategory> cats = ProfileTables.now(tf);
        if (cats == null) {
            center(g, tf.isDone() ? "Couldn't load collection tiers." : "Loading collection tiers...", cx, cw, tf.isDone() ? BAD : LABEL);
            return;
        }
        int navW = 84;
        inset(g, cx, cy, navW, ch);
        String[] names = cats.stream().map(ProfileTables.CollectionCategory::name).toArray(String[]::new);
        int sel = nav(g, names, cx + 4, cy + 4, navW - 8, mx, my);
        int totalMaxed = 0, totalCount = 0;
        for (ProfileTables.CollectionCategory c : cats) {
            for (ProfileTables.CollectionItem it : c.items()) {
                totalCount++;
                if (it.tiers().length > 0 && ex.collections.getOrDefault(it.id(), 0L) >= it.tiers()[it.tiers().length - 1]) totalMaxed++;
            }
        }
        int ny = cy + 8 + names.length * 17;
        text(g, "Maxed", cx + 6, ny, LABEL);
        text(g, totalMaxed + " / " + totalCount, cx + 6, ny + 10, totalMaxed == totalCount ? GOLD : VALUE);

        ProfileTables.CollectionCategory cat = cats.get(sel);
        int rx = cx + navW + 4, rw = cw - navW - 4;
        inset(g, rx, cy, rw, ch);
        int maxed = 0;
        for (ProfileTables.CollectionItem it : cat.items()) {
            if (it.tiers().length > 0 && ex.collections.getOrDefault(it.id(), 0L) >= it.tiers()[it.tiers().length - 1]) maxed++;
        }
        title(g, cat.name(), rx + 6, cy + 5);
        right(g, "Maxed " + maxed + " / " + cat.items().size(), rx + rw - 8, cy + 5, VALUE);

        int rowH = 21, top = cy + 18, visible = Math.max(1, (ch - 22) / rowH);
        int rows = (cat.items().size() + 1) / 2;
        int scroll = clampScroll(rows, visible);
        int colW = (rw - 20) / 2;
        for (int i = scroll * 2; i < cat.items().size(); i++) {
            int row = i / 2 - scroll;
            if (row >= visible) break;
            ProfileTables.CollectionItem it = cat.items().get(i);
            int x = rx + 6 + (i % 2) * (colW + 6), y = top + row * rowH;
            long amount = ex.collections.getOrDefault(it.id(), 0L);
            long[] tiers = it.tiers();
            int tier = countAtOrBelow(tiers, amount);
            int max = Math.max(tiers.length, it.maxTiers());
            boolean isMaxed = tiers.length > 0 && tier >= tiers.length;
            long prev = tier == 0 ? 0 : tiers[Math.min(tier, tiers.length) - 1];
            long next = isMaxed || tiers.length == 0 ? prev : tiers[tier];
            double progress = isMaxed ? 1 : next <= prev ? 0 : (double) (amount - prev) / (next - prev);

            g.item(icon(it.id()), x, y + 1);
            String tierText = tier + "/" + max;
            int tx = x + 19, tw = colW - 19;
            text(g, f().plainSubstrByWidth(it.name(), tw - f().width(tierText) - 4), tx, y, amount > 0 ? VALUE : DIM);
            right(g, tierText, tx + tw, y, isMaxed ? GOLD : VALUE);
            progress(g, tx, y + 11, tw, progress, isMaxed);
            if (in(mx, my, x, y, colW, rowH - 2)) {
                List<Component> t = new ArrayList<>();
                t.add(line(it.name(), ChatFormatting.GOLD));
                t.add(line("Collected: " + commas(amount), ChatFormatting.GRAY));
                t.add(line("Tier: " + tier + " / " + max, ChatFormatting.GRAY));
                if (!isMaxed && tiers.length > 0) {
                    t.add(line("Next tier: " + commas(amount) + " / " + commas(next) + " (" + pct(progress) + ")", ChatFormatting.DARK_GRAY));
                    t.add(line("To max: " + pct(Math.min(1, (double) amount / tiers[tiers.length - 1])), ChatFormatting.DARK_GRAY));
                } else if (isMaxed) {
                    t.add(line("MAXED", ChatFormatting.GOLD));
                }
                tip(t);
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * rowH, rows, visible);
    }

    // ------------------------------------------------------------------------------------------------ mining

    private static final String[] CRYSTALS = {"jade_crystal", "amethyst_crystal", "topaz_crystal", "sapphire_crystal", "amber_crystal",
        "ruby_crystal", "jasper_crystal", "opal_crystal", "aquamarine_crystal", "citrine_crystal", "peridot_crystal", "onyx_crystal"};
    private static final Item[] CRYSTAL_ICONS = {Items.STAINED_GLASS.lime(), Items.STAINED_GLASS.purple(), Items.STAINED_GLASS.yellow(),
        Items.STAINED_GLASS.lightBlue(), Items.STAINED_GLASS.orange(), Items.STAINED_GLASS.red(), Items.STAINED_GLASS.magenta(),
        Items.STAINED_GLASS.white(), Items.STAINED_GLASS.blue(), Items.STAINED_GLASS.brown(), Items.STAINED_GLASS.green(), Items.STAINED_GLASS.black()};
    private static final long[] ROCK_BRACKETS = {2500, 7500, 20000, 100000, 250000};
    private static final String[] ROCK_RARITIES = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};

    private void mining(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras ex = ex();
        int navW = 84;
        inset(g, cx, cy, navW, ch);
        int view = nav(g, new String[]{"HOTM", "Glacite", "Foraging"}, cx + 4, cy + 4, navW - 8, mx, my);
        int rx = cx + navW + 4, rw = cw - navW - 4;
        inset(g, rx, cy, rw, ch);
        switch (view) {
            case 0 -> hotm(g, ex.mining, rx, rw, mx, my);
            case 1 -> glacite(g, ex.mining, rx, rw, mx, my);
            default -> foraging(g, ex.foraging, rx, rw, mx, my);
        }
    }

    private void hotm(GuiGraphicsExtractor g, ProfileExtras.Mining m, int rx, int rw, int mx, int my) {
        if (!m.present()) {
            center(g, "No Heart of the Mountain data.", rx, rw, DIM);
            return;
        }
        int x = rx + 6, w = 150, y = cy + 6;
        s.bar(g, x, y, w, "Heart of the Mountain", thresholdLevel(ProfileTables.longs(ProfileTables.bundled().get("hotm_levels")), m.hotmXp()),
            new ItemStack(Items.DIAMOND_PICKAXE), mx, my);
        y += 20;
        title(g, "Powder", x, y);
        right(g, "Available / Total", x + w, y, DIM);
        y += 11;
        String[] names = {"Mithril", "Gemstone", "Glacite"};
        int[] colours = {0xFF00AA00, 0xFFFF55FF, 0xFF55FFFF};
        for (int i = 0; i < 3; i++) {
            text(g, names[i], x, y, colours[i]);
            right(g, shorten(m.powderAvailable()[i]) + " / " + shorten(m.powderTotal()[i]), x + w, y, VALUE);
            if (in(mx, my, x, y, w, 10)) tip(line(names[i] + " Powder", ChatFormatting.GOLD),
                line("Available: " + commas(m.powderAvailable()[i]), ChatFormatting.GRAY), line("Total: " + commas(m.powderTotal()[i]), ChatFormatting.GRAY));
            y += 10;
        }
        y += 4;
        int runs = Integer.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            ProfileExtras.Crystal c = m.crystals().get(CRYSTALS[i]);
            runs = Math.min(runs, c == null ? 0 : c.placed());
        }
        y = stat(g, x, y, w, "Nucleus Runs", commas(runs == Integer.MAX_VALUE ? 0 : runs));
        int rock = countAtOrBelow(ROCK_BRACKETS, m.oresMined());
        if (in(mx, my, x, y, w, 10)) tip(line("Ores Mined: " + commas(m.oresMined()), ChatFormatting.GRAY));
        y = stat(g, x, y, w, "Rock Pet", rock == 0 ? "None" : ROCK_RARITIES[rock - 1]);
        if (m.selectedAbility() != null && !m.selectedAbility().isEmpty()) y = stat(g, x, y, w, "Ability", titleCase(m.selectedAbility()));
        y += 4;
        title(g, "Forge", x, y);
        y += 11;
        if (m.forge().isEmpty()) text(g, "Empty", x, y, DIM);
        for (ProfileExtras.ForgeSlot slot : m.forge()) {
            if (y > cy + ch - 10) break;
            if (in(mx, my, x, y, w, 10)) tip(line(titleCase(slot.id()), ChatFormatting.GOLD), line("Started: " + dateTime(slot.startTime()), ChatFormatting.GRAY));
            y = stat(g, x, y, w, "Slot " + slot.slot(), titleCase(slot.id()));
        }

        int px = rx + 6 + w + 12, pw = rx + rw - 6 - px, py = cy + 6;
        title(g, "Crystals", px, py);
        py += 11;
        int perRow = Math.max(1, Math.min(6, pw / 20));
        for (int i = 0; i < CRYSTALS.length; i++) {
            int ix = px + (i % perRow) * 20, iy = py + (i / perRow) * 20;
            ProfileExtras.Crystal c = m.crystals().get(CRYSTALS[i]);
            String state = c == null ? "NOT_FOUND" : c.state();
            boolean have = state.equals("FOUND") || state.equals("PLACED");
            g.fill(ix, iy, ix + 18, iy + 18, SLOT_BG);
            g.outline(ix, iy, 18, 18, have ? GOOD : INSET_EDGE);
            ItemStack glass = new ItemStack(CRYSTAL_ICONS[i]);
            g.item(glass, ix + 1, iy + 1);
            g.itemDecorations(f(), glass, ix + 1, iy + 1, have ? "§a✔" : "§c✘");
            if (in(mx, my, ix, iy, 18, 18)) tip(line(titleCase(CRYSTALS[i]), ChatFormatting.GOLD), line("State: " + titleCase(state), ChatFormatting.GRAY),
                line("Found: " + commas(c == null ? 0 : c.found()), ChatFormatting.DARK_GRAY), line("Placed: " + commas(c == null ? 0 : c.placed()), ChatFormatting.DARK_GRAY));
        }
        py += (CRYSTALS.length + perRow - 1) / perRow * 20 + 4;
        perks(g, m.nodes(), px, py, pw);
    }

    private void perks(GuiGraphicsExtractor g, Map<String, Integer> nodes, int x, int y, int w) {
        title(g, "Perks", x, y);
        right(g, String.valueOf(nodes.size()), x + w, y, VALUE);
        y += 11;
        int visible = Math.max(1, (cy + ch - 4 - y) / 10);
        List<Map.Entry<String, Integer>> list = new ArrayList<>(nodes.entrySet());
        int scroll = clampScroll(list.size(), visible);
        for (int i = scroll; i < list.size() && i - scroll < visible; i++) {
            Map.Entry<String, Integer> e = list.get(i);
            String lv = String.valueOf(e.getValue());
            int ly = y + (i - scroll) * 10;
            text(g, f().plainSubstrByWidth(titleCase(e.getKey()), w - f().width(lv) - 8), x, ly, VALUE);
            right(g, lv, x + w - 4, ly, GOLD);
        }
        if (list.isEmpty()) text(g, "None", x, y, DIM);
        scrollbar(g, x + w - 2, y, visible * 10, list.size(), visible);
    }

    private void glacite(GuiGraphicsExtractor g, ProfileExtras.Mining m, int rx, int rw, int mx, int my) {
        int x = rx + 6, w = (rw - 24) / 2, y = cy + 6;
        title(g, "Glacite Tunnels", x, y);
        y += 12;
        y = stat(g, x, y, w, "Mineshafts Entered", commas(m.mineshafts()));
        List<Map<String, Integer>> milestones = new ArrayList<>();
        JsonElement raw = ProfileTables.bundled().get("corpse_milestones");
        if (raw != null && raw.isJsonArray()) for (JsonElement el : raw.getAsJsonArray()) {
            Map<String, Integer> req = new LinkedHashMap<>();
            if (el.isJsonObject()) for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) req.put(e.getKey(), e.getValue().getAsInt());
            milestones.add(req);
        }
        int milestone = 0;
        for (int i = 0; i < milestones.size(); i++) {
            boolean ok = true;
            for (Map.Entry<String, Integer> e : milestones.get(i).entrySet()) if (m.corpses().getOrDefault(e.getKey(), 0) < e.getValue()) ok = false;
            if (ok) milestone = i + 1;
        }
        y = stat(g, x, y, w, "Corpse Milestone", milestone + " / " + milestones.size());
        y += 4;
        title(g, "Corpses Looted", x, y);
        y += 11;
        for (String corpse : List.of("lapis", "umber", "tungsten", "vanguard")) y = stat(g, x, y, w, titleCase(corpse), commas(m.corpses().getOrDefault(corpse, 0)));

        int fx = x + w + 12, fy = cy + 6;
        JsonObject fossils = ProfileTables.section("fossils");
        Set<String> donated = new LinkedHashSet<>(m.fossils());
        title(g, "Fossils", fx, fy);
        right(g, donated.size() + " / " + fossils.size(), fx + w, fy, VALUE);
        fy += 12;
        for (Map.Entry<String, JsonElement> e : fossils.entrySet()) {
            JsonObject fo = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
            boolean have = donated.contains(e.getKey());
            String name = fo.has("name") ? fo.get("name").getAsString() : titleCase(e.getKey());
            text(g, name, fx, fy, have ? VALUE : DIM);
            right(g, have ? "✔" : "✘", fx + w, fy, have ? GOOD : BAD);
            if (in(mx, my, fx, fy, w, 10) && fo.has("pet")) tip(line(name, ChatFormatting.GOLD), line("Pet: " + titleCase(fo.get("pet").getAsString()), ChatFormatting.GRAY));
            fy += 10;
        }
    }

    private void foraging(GuiGraphicsExtractor g, ProfileExtras.Foraging fo, int rx, int rw, int mx, int my) {
        if (!fo.present()) {
            center(g, "No Heart of the Forest data.", rx, rw, DIM);
            return;
        }
        int x = rx + 6, w = 150, y = cy + 6;
        s.bar(g, x, y, w, "Heart of the Forest", thresholdLevel(ProfileTables.longs(ProfileTables.bundled().get("hotf_levels")), fo.hotfXp()),
            new ItemStack(Items.OAK_SAPLING), mx, my);
        y += 20;
        title(g, "Whispers", x, y);
        right(g, "Available / Total", x + w, y, DIM);
        y += 11;
        y = stat(g, x, y, w, "Forest", shorten(fo.forestAvailable()) + " / " + shorten(fo.forestTotal()));
        y = stat(g, x, y, w, "Desert", shorten(fo.desertAvailable()) + " / " + shorten(fo.desertTotal()));
        if (!fo.treeGifts().isEmpty()) {
            y += 4;
            title(g, "Tree Gifts", x, y);
            y += 11;
            for (Map.Entry<String, Integer> e : fo.treeGifts().entrySet()) y = stat(g, x, y, w, titleCase(e.getKey()), commas(e.getValue()));
        }
        int px = rx + 6 + w + 12;
        perks(g, fo.nodes(), px, cy + 6, rx + rw - 6 - px);
    }

    // ------------------------------------------------------------------------------------------------ bestiary

    private void bestiary(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras ex = ex();
        if (ex.bestiaryKills == null) {
            center(g, "No bestiary data on this profile.", cx, cw, DIM);
            return;
        }
        CompletableFuture<List<ProfileTables.BestiaryCategory>> tf = ProfileTables.bestiary();
        List<ProfileTables.BestiaryCategory> cats = ProfileTables.now(tf);
        if (cats == null) {
            center(g, tf.isDone() ? "Couldn't load the bestiary." : "Loading the bestiary...", cx, cw, tf.isDone() ? BAD : LABEL);
            return;
        }
        int totalTiers = 0, maxed = 0, families = 0;
        long kills = 0;
        for (long k : ex.bestiaryKills.values()) kills += k;
        for (ProfileTables.BestiaryCategory c : cats) {
            for (ProfileTables.BestiaryFamily fam : c.families()) {
                families++;
                int tier = countAtOrBelow(toLongs(fam.tiers()), familyKills(ex, fam));
                totalTiers += tier;
                if (fam.tiers().length > 0 && tier >= fam.tiers().length) maxed++;
            }
        }
        // A list of the areas to click, like Collections, instead of paging through them.
        int lw = 100;
        inset(g, cx, cy, lw, ch);
        String[] names = cats.stream().map(ProfileTables.BestiaryCategory::name).toArray(String[]::new);
        // Many areas: the list scrolls with the stats under it when it doesn't fit.
        int listRows = Math.max(1, (ch - 52) / 17);
        int first = Math.clamp(s.subView - listRows / 2, 0, Math.max(0, names.length - listRows));
        s.subView = Math.clamp(s.subView, 0, names.length - 1);
        for (int i = first; i < names.length && i - first < listRows; i++) {
            int index = i;
            button(g, cx + 4, cy + 4 + (i - first) * 17, lw - 8, 15, names[i], mx, my, i == s.subView, () -> {
                if (s.subView != index) {
                    s.subView = index;
                    s.pageScroll = 0;
                }
            });
        }
        int x = cx + 6, y = cy + 8 + Math.min(listRows, names.length) * 17, w = lw - 12;
        y = stat(g, x, y, w, "Milestone", String.valueOf(totalTiers / 10));
        y = stat(g, x, y, w, "Maxed", maxed + "/" + families);
        stat(g, x, y, w, "Kills", shorten(kills));

        ProfileTables.BestiaryCategory cat = cats.get(s.subView);
        int rx = cx + lw + 4, rw = cw - lw - 4;
        inset(g, rx, cy, rw, ch);
        title(g, cat.name(), rx + 6, cy + 5);
        int cell = 20, cols = Math.max(1, (rw - 16) / cell), top = cy + 18, visible = Math.max(1, (ch - 22) / cell);
        int rows = (cat.families().size() + cols - 1) / cols;
        int scroll = clampScroll(rows, visible);
        int startX = rx + (rw - cols * cell) / 2;
        for (int i = scroll * cols; i < cat.families().size(); i++) {
            int row = i / cols - scroll;
            if (row >= visible) break;
            ProfileTables.BestiaryFamily fam = cat.families().get(i);
            int ix = startX + (i % cols) * cell, iy = top + row * cell;
            long k = familyKills(ex, fam);
            long[] tiers = toLongs(fam.tiers());
            int tier = countAtOrBelow(tiers, k);
            boolean isMaxed = tiers.length > 0 && tier >= tiers.length;
            ItemStack icon = k == 0 ? new ItemStack(Items.DYE.gray()) : familyIcon(fam);
            g.fill(ix + 1, iy + 1, ix + cell - 1, iy + cell - 1, SLOT_BG);
            g.outline(ix + 1, iy + 1, cell - 2, cell - 2, isMaxed ? GOLD : INSET_EDGE);
            g.item(icon, ix + 2, iy + 2);
            g.itemDecorations(f(), icon, ix + 2, iy + 2, (isMaxed ? "§6" : "§f") + tier);
            if (in(mx, my, ix, iy, cell, cell)) {
                List<Component> t = new ArrayList<>();
                t.add(line(fam.name(), ChatFormatting.GOLD));
                t.add(line("Tier: " + tier + " / " + tiers.length, ChatFormatting.GRAY));
                if (isMaxed || tiers.length == 0) {
                    t.add(line("Kills: " + commas(k) + (isMaxed ? "  MAXED" : ""), isMaxed ? ChatFormatting.GOLD : ChatFormatting.GRAY));
                } else {
                    t.add(line("Kills: " + commas(k) + " / " + commas(tiers[tiers.length - 1]), ChatFormatting.GRAY));
                    t.add(line("Next tier: " + commas(k) + " / " + commas(tiers[tier]), ChatFormatting.DARK_GRAY));
                }
                tip(t);
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * cell, rows, visible);
    }

    private static long familyKills(ProfileExtras ex, ProfileTables.BestiaryFamily fam) {
        long k = 0;
        for (String mob : fam.mobs()) k += ex.bestiaryKills.getOrDefault(mob, 0L);
        return k;
    }

    private static long[] toLongs(int[] arr) {
        long[] out = new long[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i];
        return out;
    }

    private ItemStack familyIcon(ProfileTables.BestiaryFamily fam) {
        if (fam.texture() != null) return icons.computeIfAbsent("#skull:" + fam.texture(), k -> Compat.createSkull(fam.texture()));
        return fam.icon() == null ? new ItemStack(Items.ZOMBIE_HEAD) : icon(fam.icon());
    }

    // ------------------------------------------------------------------------------------------------ crimson isle

    private void crimson(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras.Crimson c = ex().crimson;
        if (!c.present()) {
            center(g, "No Crimson Isle data on this profile.", cx, cw, DIM);
            return;
        }
        JsonObject tables = ProfileTables.section("crimson_isle");
        int colW = (cw - 8) / 3;

        // Reputation
        inset(g, cx, cy, colW, ch);
        int x = cx + 6, w = colW - 12, y = cy + 5;
        title(g, "Reputation", x, y);
        y += 12;
        JsonObject factionNames = obj(tables, "faction_names");
        String faction = c.selectedFaction().isEmpty() ? "None"
            : factionNames.has(c.selectedFaction()) ? factionNames.get(c.selectedFaction()).getAsString() : titleCase(c.selectedFaction());
        y = stat(g, x, y, w, "Faction", faction);
        y += 2;
        int[] reps = {c.magesRep(), c.barbariansRep()};
        String[] names = {"Mage", "Barbarian"};
        int[] colours = {0xFFAA00AA, 0xFFFF5555};
        for (int i = 0; i < 2; i++) {
            text(g, names[i], x, y, colours[i]);
            right(g, commas(reps[i]), x + w, y, VALUE);
            y += 10;
            right(g, ProfileTables.threshold(tables.get("faction_reputation"), "name", reps[i]), x + w, y, DIM);
            y += 11;
        }
        int best = Math.max(reps[0], reps[1]);
        String highest = "None";
        JsonObject req = obj(tables, "kuudra_requirements"), kNames = obj(tables, "kuudra_names");
        int bestReq = -1;
        for (Map.Entry<String, JsonElement> e : req.entrySet()) {
            int r = e.getValue().getAsInt();
            if (r <= best && r > bestReq) {
                bestReq = r;
                highest = kNames.has(e.getKey()) ? kNames.get(e.getKey()).getAsString() : titleCase(e.getKey());
            }
        }
        y = stat(g, x, y, w, "Kuudra Unlocked", highest);
        y += 6;
        title(g, "Matriarch", x, y);
        y += 12;
        y = stat(g, x, y, w, "Pearls", commas(c.matriarchPearls()));
        stat(g, x, y, w, "Last Attempt", date(c.matriarchLastAttempt()));

        // Kuudra
        int kx = cx + colW + 4;
        inset(g, kx, cy, colW, ch);
        x = kx + 6;
        y = cy + 5;
        title(g, "Kuudra", x, y);
        y += 12;
        int total = 0, tierIndex = 0, bestWave = 0;
        long collection = 0;
        for (Map.Entry<String, Integer> e : c.kuudraCompletions().entrySet()) {
            tierIndex++;
            total += e.getValue();
            collection += (long) e.getValue() * tierIndex;
            int wave = c.kuudraHighestWave().getOrDefault(e.getKey(), 0);
            bestWave = Math.max(bestWave, wave);
            String name = kNames.has(e.getKey()) ? kNames.get(e.getKey()).getAsString() : titleCase(e.getKey());
            if (in(mx, my, x, y, w, 10)) tip(line(name + " Kuudra", ChatFormatting.GOLD), line("Completions: " + commas(e.getValue()), ChatFormatting.GRAY),
                line("Highest Wave: " + commas(wave), ChatFormatting.DARK_GRAY));
            y = stat(g, x, y, w, name, commas(e.getValue()));
        }
        y += 4;
        y = stat(g, x, y, w, "Total Runs", commas(total));
        long[] colTiers = ProfileTables.longs(tables.get("kuudra_collection"));
        y = stat(g, x, y, w, "Collection", commas(collection));
        y = stat(g, x, y, w, "Collection Tier", countAtOrBelow(colTiers, collection) + " / " + colTiers.length);
        stat(g, x, y, w, "Highest Wave", commas(bestWave));

        // Dojo
        int dx = kx + colW + 4, dw = cx + cw - dx;
        inset(g, dx, cy, dw, ch);
        x = dx + 6;
        w = dw - 12;
        y = cy + 5;
        title(g, "Dojo", x, y);
        y += 12;
        JsonObject dojoNames = obj(tables, "dojo_names");
        int points = 0;
        boolean any = false;
        for (Map.Entry<String, Integer> e : c.dojoPoints().entrySet()) {
            int pts = e.getValue();
            String name = dojoNames.has(e.getKey()) ? dojoNames.get(e.getKey()).getAsString() : titleCase(e.getKey());
            String grade = pts < 0 ? "-" : ProfileTables.threshold(tables.get("dojo_grades"), "grade", pts);
            text(g, name, x, y, pts < 0 ? DIM : LABEL);
            right(g, grade, x + w, y, gradeColour(grade));
            right(g, pts < 0 ? "-" : commas(pts), x + w - 12, y, VALUE);
            if (pts >= 0) {
                any = true;
                points += pts;
                int time = c.dojoTimes().getOrDefault(e.getKey(), -1);
                if (in(mx, my, x, y, w, 10)) tip(line("Test of " + name, ChatFormatting.GOLD), line("Points: " + commas(pts) + " (" + grade + ")", ChatFormatting.GRAY),
                    line("Time: " + (time < 0 ? "-" : String.format(Locale.ROOT, "%.1fs", time / 1000.0)), ChatFormatting.DARK_GRAY));
            }
            y += 10;
        }
        y += 4;
        y = stat(g, x, y, w, "Total Points", any ? commas(points) : "-");
        stat(g, x, y, w, "Belt", any ? ProfileTables.threshold(tables.get("dojo_belts"), "belt", points) : "None");
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonObject v = ProfileExtras.asObj(o.get(key));
        return v == null ? new JsonObject() : v;
    }

    private static int gradeColour(String grade) {
        return switch (grade) {
            case "S" -> 0xFFFFAA00;
            case "A" -> 0xFFFF55FF;
            case "B" -> 0xFF5555FF;
            case "C", "D" -> 0xFF55FF55;
            case "F" -> 0xFFFF5555;
            default -> DIM;
        };
    }

    // ------------------------------------------------------------------------------------------------ museum

    /** The museum, or null after drawing why it isn't there. */
    private MuseumView museumView(GuiGraphicsExtractor g) {
        ProfileApi.Profile p = s.profile();
        CompletableFuture<JsonObject> f = ProfileApi.aux(ProfileApi.Aux.MUSEUM, p.id());
        if (!f.isDone()) {
            if (g != null) center(g, "Loading the museum...", cx, cw, LABEL);
            return null;
        }
        if (f.isCompletedExceptionally()) {
            if (g != null) {
                center(g, "Couldn't load the museum: " + ProfileApi.reason(f.handle((v, e) -> e).join()), cx, cw, BAD);
                button(g, cx + cw / 2 - 30, cy + ch / 2 + 10, 60, 16, "Retry", -1, -1, false, () -> ProfileApi.forgetAux(ProfileApi.Aux.MUSEUM, p.id()));
            }
            return null;
        }
        JsonObject all = f.getNow(null);
        UUID uuid = s.uuid();
        JsonObject member = all == null || uuid == null ? null : Json.obj(all, uuid.toString().replace("-", ""));
        if (member == null) {
            if (g != null) center(g, "Their Museum API is off, or nothing's donated.", cx, cw, DIM);
            return null;
        }
        return museums.computeIfAbsent(member, m -> {
            Map<String, List<PvItem>> items = new LinkedHashMap<>();
            Set<String> borrowing = new LinkedHashSet<>();
            JsonObject itemsObj = Json.obj(m, "items");
            if (itemsObj != null) for (String id : itemsObj.keySet()) {
                JsonObject entry = Json.obj(itemsObj, id);
                if (entry == null) continue;
                List<PvItem> stacks = new ArrayList<>();
                for (PvItem item : ProfileItems.decode(Json.obj(entry, "items"))) if (item != null) stacks.add(item);
                items.put(id, stacks);
                if (Json.bool(entry, "borrowing")) borrowing.add(id);
            }
            List<PvItem> special = new ArrayList<>();
            for (JsonElement el : Json.arr(m, "special")) {
                if (!el.isJsonObject()) continue;
                for (PvItem item : ProfileItems.decode(Json.obj(el.getAsJsonObject(), "items"))) if (item != null) special.add(item);
            }
            return new MuseumView(Json.num(m, "value"), Json.bool(m, "appraisal"), items, borrowing, special);
        });
    }

    private void museum(GuiGraphicsExtractor g, int mx, int my) {
        MuseumView view = museumView(g);
        if (view == null) return;
        ProfileTables.MuseumTables tables = ProfileTables.now(ProfileTables.museum());
        List<String> catIds = new ArrayList<>();
        if (tables != null) {
            catIds.addAll(tables.categories().keySet());
            catIds.remove("special");
        } else {
            catIds.add("all");
        }
        catIds.add("special");

        int lw = 130;
        inset(g, cx, cy, lw, ch);
        int x = cx + 6, w = lw - 12, y = cy + 5;
        y = stat(g, x, y, w, "Value", shorten(view.value()), GOLD);
        y = stat(g, x, y, w, "Appraised", view.appraised() ? "Yes" : "No");
        y = stat(g, x, y, w, "Donated", commas(view.items().size()));
        y = stat(g, x, y, w, "Special", commas(view.special().size()));
        if (!view.borrowing().isEmpty()) y = stat(g, x, y, w, "Borrowing", commas(view.borrowing().size()));
        y += 3;
        s.subView = Math.clamp(s.subView, 0, catIds.size() - 1);
        for (int i = 0; i < catIds.size() && y + 15 <= cy + ch; i++) {
            String id = catIds.get(i);
            Integer max = tables == null ? null : tables.maxValues().get(id);
            String label = titleCase(id) + " " + countDonated(view, tables, id) + (max != null && !id.equals("special") ? "/" + max : "");
            int index = i;
            button(g, x - 2, y, w + 4, 14, label, mx, my, i == s.subView, () -> {
                s.subView = index;
                s.pageScroll = 0;
            });
            y += 16;
        }

        int rx = cx + lw + 4, rw = cw - lw - 4;
        inset(g, rx, cy, rw, ch);
        String cat = catIds.get(s.subView);
        List<PvItem> stacks = new ArrayList<>();
        if (cat.equals("special")) stacks.addAll(view.special());
        else if (tables == null) for (List<PvItem> l : view.items().values()) stacks.addAll(l);
        else for (String id : tables.categories().getOrDefault(cat, List.of())) {
            List<PvItem> l = view.items().get(id);
            if (l != null) stacks.addAll(l);
        }
        title(g, titleCase(cat), rx + 6, cy + 5);
        right(g, stacks.size() + " items", rx + rw - 8, cy + 5, VALUE);
        if (stacks.isEmpty()) {
            center(g, "Nothing donated.", rx, rw, DIM);
            return;
        }
        int cols = Math.max(1, (rw - 16) / SLOT), top = cy + 18, visible = Math.max(1, (ch - 22) / SLOT);
        int rows = (stacks.size() + cols - 1) / cols;
        int scroll = clampScroll(rows, visible);
        int startX = rx + (rw - cols * SLOT) / 2;
        for (int i = scroll * cols; i < stacks.size(); i++) {
            int row = i / cols - scroll;
            if (row >= visible) break;
            slot(g, stacks.get(i), startX + (i % cols) * SLOT, top + row * SLOT, mx, my);
        }
        scrollbar(g, rx + rw - 5, top, visible * SLOT, rows, visible);
    }

    private static int countDonated(MuseumView view, ProfileTables.MuseumTables tables, String cat) {
        if (cat.equals("special")) return view.special().size();
        if (tables == null) return view.items().size();
        int n = 0;
        for (String id : tables.categories().getOrDefault(cat, List.of())) if (view.items().containsKey(id)) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------------ rift

    private void rift(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras.Rift r = ex().rift;
        if (!r.present()) {
            center(g, "They haven't been to the Rift.", cx, cw, DIM);
            return;
        }
        int navW = 84;
        inset(g, cx, cy, navW, ch);
        int view = nav(g, new String[]{"Information", "Inventory", "Ender Chest"}, cx + 4, cy + 4, navW - 8, mx, my);
        int rx = cx + navW + 4, rw = cw - navW - 4;
        inset(g, rx, cy, rw, ch);
        JsonObject tables = ProfileTables.section("rift");
        if (view == 0) {
            int x = rx + 6, w = 160, y = cy + 6;
            y = stat(g, x, y, w, "Motes", commas(r.motes()), 0xFFFF55FF);
            y = stat(g, x, y, w, "Lifetime Motes", commas(r.lifetimeMotes()));
            y = stat(g, x, y, w, "Visits", commas(r.visits()));
            y = stat(g, x, y, w, "Time Sitting", duration(r.secondsSitting()));
            int souls = tables.has("enigma_souls") ? tables.get("enigma_souls").getAsInt() : 52;
            y = stat(g, x, y, w, "Enigma Souls", r.souls().size() + " / " + souls);
            y = listStat(g, x, y, w, "Montezuma Cats", r.cats(), ProfileTables.strings(tables.get("montezuma")), "Missing", mx, my);
            y = listStat(g, x, y, w, "Unlocked Eyes", r.eyes(), ProfileTables.strings(tables.get("eyes")), "Locked", mx, my);
            int grub = tables.has("grubber_max") ? tables.get("grubber_max").getAsInt() : 5;
            stat(g, x, y, w, "Grubber Stacks", r.grubberStacks() + " / " + grub);

            int tx = x + w + 14, tw = rx + rw - 6 - tx, ty = cy + 6;
            JsonArray trophies = tables.has("trophies") ? tables.getAsJsonArray("trophies") : new JsonArray();
            title(g, "Timecharms", tx, ty);
            right(g, r.trophies().size() + " / " + trophies.size(), tx + tw, ty, VALUE);
            ty += 12;
            for (JsonElement el : trophies) {
                JsonObject t = el.getAsJsonObject();
                String id = t.get("id").getAsString(), name = t.get("name").getAsString();
                ProfileExtras.Trophy got = null;
                for (ProfileExtras.Trophy tr : r.trophies()) if (tr.type().equals(id)) got = tr;
                g.item(new ItemStack(got != null ? Items.CLOCK : Items.DYE.gray()), tx, ty);
                text(g, f().plainSubstrByWidth(name, tw - 20), tx + 19, ty + 4, got != null ? VALUE : DIM);
                if (in(mx, my, tx, ty, tw, 17)) {
                    if (got == null) tip(line(name, ChatFormatting.GRAY), line("Not found", ChatFormatting.RED));
                    else tip(line(name, ChatFormatting.GOLD), line("Found after " + got.visits() + " visits", ChatFormatting.GRAY), line(dateTime(got.timestamp()), ChatFormatting.DARK_GRAY));
                }
                ty += 18;
            }
            return;
        }
        JsonObject inv = r.inventory();
        if (inv == null) {
            center(g, "Their Rift inventory API is off.", rx, rw, DIM);
            return;
        }
        int gridX = rx + (rw - 9 * SLOT) / 2, top = cy + 20;
        if (view == 1) {
            title(g, "Rift Inventory", rx + 6, cy + 6);
            List<PvItem> armour = ProfileItems.decode(Json.obj(inv, "inv_armor")), equipment = ProfileItems.decode(Json.obj(inv, "equipment_contents"));
            List<PvItem> items = ProfileItems.decode(Json.obj(inv, "inv_contents"));
            int sideX = rx + 8;
            for (int i = 0; i < 4; i++) {
                slot(g, armour.size() == 4 ? armour.get(3 - i) : null, sideX, top + i * SLOT, mx, my);
                slot(g, i < equipment.size() ? equipment.get(i) : null, sideX + 20, top + i * SLOT, mx, my);
            }
            int mainX = Math.max(sideX + 46, gridX);
            for (int i = 9; i < 36; i++) slot(g, i < items.size() ? items.get(i) : null, mainX + (i - 9) % 9 * SLOT, top + (i - 9) / 9 * SLOT, mx, my);
            for (int i = 0; i < 9; i++) slot(g, i < items.size() ? items.get(i) : null, mainX + i * SLOT, top + 3 * SLOT + 4, mx, my);
            return;
        }
        List<List<PvItem>> pages = new ArrayList<>();
        ProfileViewerScreen.split(pages, ProfileItems.decode(Json.obj(inv, "ender_chest_contents")), 45);
        if (pages.isEmpty()) {
            title(g, "Rift Ender Chest", rx + 6, cy + 6);
            center(g, "Empty.", rx, rw, DIM);
            return;
        }
        s.subPage = Math.floorMod(s.subPage, pages.size());
        title(g, "Rift Ender Chest" + (pages.size() > 1 ? " (" + (s.subPage + 1) + "/" + pages.size() + ")" : ""), rx + 6, cy + 6);
        if (pages.size() > 1) {
            button(g, rx + rw - 40, cy + 3, 16, 13, "<", mx, my, false, () -> s.subPage--);
            button(g, rx + rw - 22, cy + 3, 16, 13, ">", mx, my, false, () -> s.subPage++);
        }
        List<PvItem> page = pages.get(s.subPage);
        for (int i = 0; i < 45; i++) slot(g, i < page.size() ? page.get(i) : null, gridX + i % 9 * SLOT, top + i / 9 * SLOT, mx, my);
    }

    private int listStat(GuiGraphicsExtractor g, int x, int y, int w, String label, List<String> have, List<String> all, String missingLabel, int mx, int my) {
        if (in(mx, my, x, y, w, 10)) {
            List<String> missing = new ArrayList<>();
            for (String a : all) if (!have.contains(a)) missing.add(a);
            List<Component> t = new ArrayList<>();
            t.add(line(missing.isEmpty() ? "All found" : missingLabel + " (" + missing.size() + ")", missing.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            for (String m : missing) t.add(line(titleCase(m), ChatFormatting.DARK_GRAY));
            tip(t);
        }
        return stat(g, x, y, w, label, have.size() + " / " + all.size());
    }

    // ------------------------------------------------------------------------------------------------ farming

    private static final Map<String, String> CROP_NAMES = Map.of("INK_SACK:3", "Cocoa Beans", "CARROT_ITEM", "Carrot",
        "POTATO_ITEM", "Potato", "MUSHROOM_COLLECTION", "Mushroom", "NETHER_STALK", "Nether Wart", "DOUBLE_PLANT", "Sunflower", "MELON", "Melon");
    private static final List<String> MEDALS = List.of("diamond", "platinum", "gold", "silver", "bronze");

    private void farming(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras.Farming fa = ex().farming;
        JsonObject gardenTables = ProfileTables.section("garden");
        ProfileApi.Profile p = s.profile();
        CompletableFuture<JsonObject> gf = ProfileApi.aux(ProfileApi.Aux.GARDEN, p.id());
        JsonObject garden = gf.isDone() && !gf.isCompletedExceptionally() ? gf.getNow(null) : null;

        int lw = 150;
        inset(g, cx, cy, lw, ch);
        int x = cx + 6, w = lw - 12, y = cy + 5;
        title(g, "Garden", x, y);
        y += 12;
        if (garden != null) {
            long xp = (long) Json.num(garden, "garden_experience");
            long[] levels = ProfileTables.cumulative(ProfileTables.longs(gardenTables.get("garden_level")));
            s.bar(g, x, y, w, "Garden Level", gardenLevel(levels, xp), new ItemStack(Items.SUNFLOWER), mx, my);
            y += 18;
            JsonObject comm = Json.obj(garden, "commission_data");
            y = stat(g, x, y, w, "Visitors Served", commas(Json.num(comm, "unique_npcs_served")));
            y = stat(g, x, y, w, "Offers Accepted", commas(Json.num(comm, "total_completed")));
        } else if (!gf.isDone()) {
            text(g, "Loading the garden...", x, y, LABEL);
            y += 11;
        } else {
            boolean failed = gf.isCompletedExceptionally();
            text(g, failed ? "Garden unavailable (click to retry)" : "No garden", x, y, DIM);
            if (failed) s.hotspots.add(new ProfileViewerScreen.Hotspot(x, y, w, 10, () -> ProfileApi.forgetAux(ProfileApi.Aux.GARDEN, p.id())));
            y += 11;
        }
        y = stat(g, x, y, w, "Copper", commas(fa.copper()), 0xFFFF5555);
        int larvaMax = gardenTables.has("max_larva_consumed") ? gardenTables.get("max_larva_consumed").getAsInt() : 5;
        y = stat(g, x, y, w, "Larva Consumed", fa.larvaConsumed() + " / " + larvaMax);
        y += 4;
        title(g, "Jacob's Contests", x, y);
        y += 12;
        if (!fa.present()) {
            text(g, "No contest data", x, y, DIM);
        } else {
            y = stat(g, x, y, w, "Contests", commas(fa.contests()));
            if (in(mx, my, x, y, w, 10)) {
                List<Component> t = new ArrayList<>();
                t.add(line("Medal inventory", ChatFormatting.GOLD));
                for (Map.Entry<String, Integer> e : fa.medalInventory().entrySet()) t.add(line(titleCase(e.getKey()) + ": " + e.getValue(), ChatFormatting.GRAY));
                t.add(line("Medals earned", ChatFormatting.GOLD));
                for (Map.Entry<String, Integer> e : fa.medalsEarned().entrySet()) t.add(line(titleCase(e.getKey()) + ": " + commas(e.getValue()), ChatFormatting.DARK_GRAY));
                tip(t);
            }
            y = stat(g, x, y, w, "Medals", fa.medalInventory().get("gold") + "G " + fa.medalInventory().get("silver") + "S " + fa.medalInventory().get("bronze") + "B");
            y = stat(g, x, y, w, "Double Drops", fa.doubleDrops() + " / 15");
            y = stat(g, x, y, w, "Level Cap", fa.levelCapPerk() + " / 10");
            stat(g, x, y, w, "Personal Bests", fa.personalBestsPerk() ? "Unlocked" : "Locked");
        }

        int rx = cx + lw + 4, rw = cw - lw - 4;
        inset(g, rx, cy, rw, ch);
        JsonObject milestones = obj(gardenTables, "crop_milestones");
        JsonObject collected = garden == null ? null : Json.obj(garden, "resources_collected");
        int[] cols = {rx + 22, rx + (int) (rw * 0.36), rx + (int) (rw * 0.54), rx + (int) (rw * 0.70), rx + (int) (rw * 0.86)};
        int ty = cy + 5;
        String[] headers = {"Crop", "Milestone", "Contests", "Bracket", "Best"};
        for (int i = 0; i < headers.length; i++) text(g, headers[i], cols[i], ty, GOLD);
        ty += 12;
        int rowH = Math.max(11, Math.min(14, (ch - 20) / Math.max(1, milestones.size())));
        int row = 0;
        for (Map.Entry<String, JsonElement> e : milestones.entrySet()) {
            String crop = e.getKey();
            if (ty + rowH > cy + ch) break;
            if (row++ % 2 == 0) g.fill(rx + 2, ty - 2, rx + rw - 2, ty + rowH - 2, 0xFF1C1C1C);
            String mapped = CROP_NAMES.get(crop);
            String name = mapped != null ? mapped : titleCase(crop);
            g.pose().pushMatrix();
            g.pose().translate(rx + 5, ty - 2);
            g.pose().scale(0.7f, 0.7f);
            g.item(icon(crop), 0, 0);
            g.pose().popMatrix();
            text(g, f().plainSubstrByWidth(name, cols[1] - cols[0] - 4), cols[0], ty, VALUE);
            long[] cum = ProfileTables.cumulative(ProfileTables.longs(e.getValue()));
            long amount = collected == null ? -1 : (long) Json.num(collected, crop);
            long[] ms = amount < 0 ? null : cropMilestone(cum, amount);
            int milestone = ms == null ? -1 : (int) ms[0];
            boolean overflowed = milestone > cum.length && cum.length > 0;
            boolean maxed = milestone >= cum.length && cum.length > 0 && ms[2] == 0;
            text(g, milestone < 0 ? "-" : overflowed ? String.valueOf(milestone) : milestone + "/" + cum.length, cols[1], ty,
                milestone < 0 ? DIM : overflowed ? 0xFFC77DFF : milestone >= cum.length && cum.length > 0 ? GOLD : VALUE);
            text(g, commas(fa.contestsPerCrop().getOrDefault(crop, 0)), cols[2], ty, VALUE);
            String bracket = "-";
            for (String medal : MEDALS) {
                List<String> crops = fa.uniqueBrackets().get(medal);
                if (crops != null && crops.contains(crop)) {
                    bracket = titleCase(medal);
                    break;
                }
            }
            text(g, bracket, cols[3], ty, medalColour(bracket));
            long pb = fa.personalBests().getOrDefault(crop, 0L);
            text(g, pb > 0 ? shorten(pb) : "-", cols[4], ty, pb > 0 ? VALUE : DIM);
            if (in(mx, my, rx + 2, ty - 2, rw - 4, rowH)) {
                List<Component> t = new ArrayList<>();
                t.add(line(name, ChatFormatting.GOLD));
                if (amount >= 0) {
                    t.add(line("Collected: " + commas(amount), ChatFormatting.GRAY));
                    if (overflowed) t.add(line("Overflow milestone, past the max of " + cum.length, ChatFormatting.LIGHT_PURPLE));
                    if (!maxed && ms != null && ms[2] > 0) {
                        t.add(line("Next milestone: " + commas(ms[1]) + " / " + commas(ms[2]), ChatFormatting.DARK_GRAY));
                    }
                }
                t.add(line("Personal best: " + commas(pb), ChatFormatting.DARK_GRAY));
                tip(t);
            }
            ty += rowH;
        }
    }

    private static int medalColour(String medal) {
        return switch (medal) {
            case "Diamond" -> 0xFF55FFFF;
            case "Platinum" -> 0xFF00AAAA;
            case "Gold" -> 0xFFFFAA00;
            case "Silver" -> 0xFFAAAAAA;
            case "Bronze" -> 0xFFFF5555;
            default -> DIM;
        };
    }

    // ------------------------------------------------------------------------------------------------ networth

    /**
     * The networth of the open profile, started (off the render thread) once their museum has loaded or failed; null
     * until then. Started again with the museum if it was worked out without it and the museum has since loaded.
     */
    private CompletableFuture<Networth.Result> networthFuture() {
        JsonObject m = member();
        ProfileApi.Profile p = s.profile();
        if (m == null || p == null) return null;
        CompletableFuture<JsonObject> museumFuture = ProfileApi.aux(ProfileApi.Aux.MUSEUM, p.id());
        if (!museumFuture.isDone()) return null;
        JsonObject museumAll = museumFuture.isCompletedExceptionally() ? null : museumFuture.getNow(null);
        UUID uuid = s.uuid();
        // Whether their museum is there (what museumView finds), without decoding its items here.
        boolean museum = museumAll != null && uuid != null && Json.obj(museumAll, uuid.toString().replace("-", "")) != null;
        CompletableFuture<Networth.Result> nf = networth.get(m);
        if (nf == null || (museum && !networthWithMuseum.getOrDefault(m, false))) {
            // SkyHelper's networth (Sky2M's port of SkyHelper-Networth), so it matches the bots and sites that use it.
            nf = Networth.skyHelper(p.profile(), m, museumAll, uuid).exceptionally(t -> null);
            networth.put(m, nf);
            networthWithMuseum.put(m, museum);
        }
        return nf;
    }

    /** Starts the networth in the background as soon as it can be, so the page is ready when it's opened. */
    void warmNetworth() {
        networthFuture();
    }

    private void networth(GuiGraphicsExtractor g, int mx, int my) {
        CompletableFuture<Networth.Result> nf = networthFuture();
        if (nf == null) {
            center(g, "Working out their networth...", cx, cw, LABEL);
            return;
        }
        JsonObject m = member();
        Networth.Result r = ProfileTables.now(nf);
        if (r == null) {
            center(g, nf.isDone() ? "Couldn't work out their networth." : "Working out their networth...", cx, cw, nf.isDone() ? BAD : LABEL);
            return;
        }

        int lw = 150;
        inset(g, cx, cy, lw, ch);
        int x = cx + 6, w = lw - 12, y = cy + 6;
        title(g, "Networth", x, y);
        y += 13;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.5f, 1.5f);
        g.text(f(), shorten(r.total()), 0, 0, GOLD, true);
        g.pose().popMatrix();
        if (in(mx, my, x, y, w, 13)) tip(line(commas(r.total()) + " coins", ChatFormatting.GOLD));
        y += 18;
        y = stat(g, x, y, w, "Museum", r.museumIncluded() ? "Included" : "Not included");
        y = stat(g, x, y, w, "Inventory API", Json.obj(m, "inventory") != null ? "On" : "Off");
        y += 4;
        for (net.minecraft.util.FormattedCharSequence note : f().split(Component.literal("Worked out like SkyHelper (SkyHelper-Networth), with its prices, so it matches the bots and sites that use it."), w)) {
            g.text(f(), note, x, y, DIM, false);
            y += 9;
        }

        int rx = cx + lw + 4, rw = cw - lw - 4;
        inset(g, rx, cy, rw, ch);
        title(g, "Categories", rx + 6, cy + 5);
        int top = cy + 18, rowH = 16, visible = Math.max(1, (ch - 22) / rowH);
        List<Networth.Category> cats = r.categories();
        int scroll = clampScroll(cats.size(), visible);
        long maxCat = cats.isEmpty() ? 1 : Math.max(1, cats.getFirst().total());
        for (int i = scroll; i < cats.size() && i - scroll < visible; i++) {
            Networth.Category c = cats.get(i);
            int ry = top + (i - scroll) * rowH, rowW = rw - 20;
            text(g, c.name(), rx + 6, ry, c.total() > 0 ? VALUE : DIM);
            right(g, shorten(c.total()) + (r.total() > 0 ? String.format(Locale.ROOT, "  %.1f%%", 100.0 * c.total() / r.total()) : ""), rx + 6 + rowW, ry, GOLD);
            progress(g, rx + 6, ry + 10, (int) Math.max(0, (long) rowW * c.total() / maxCat), 1, false);
            if (in(mx, my, rx + 4, ry - 1, rowW + 4, rowH)) {
                List<Component> t = new ArrayList<>();
                t.add(line(c.name() + ": " + commas(c.total()), ChatFormatting.GOLD));
                for (Map.Entry<String, Long> e : c.top()) t.add(line(e.getKey() + "  " + shorten(e.getValue()), ChatFormatting.GRAY));
                tip(t);
            }
        }
        scrollbar(g, rx + rw - 5, top, visible * rowH, cats.size(), visible);
    }

    // ------------------------------------------------------------------------------------------------ weight

    private void weight(GuiGraphicsExtractor g, int mx, int my) {
        SkyblockWeight.Both both = weights.computeIfAbsent(member(), SkyblockWeight::compute);
        int colW = (cw - 4) / 2, top = cy + 76, visible = Math.max(1, (cy + ch - 4 - top) / 10);
        int rows = Math.max(weightRows(both.senither()).size(), weightRows(both.lily()).size());
        s.pageScroll = Math.clamp(s.pageScroll, 0, Math.max(0, rows - visible));
        weightColumn(g, both.senither(), cx, colW, top, visible, mx, my, "Senither's weight (hypixel-skyblock-facade).",
            "Overflow: skill XP past level 50/60, slayer XP past 1M, dungeon XP past level 50.");
        weightColumn(g, both.lily(), cx + colW + 4, cw - colW - 4, top, visible, mx, my, "LilyWeight by Antonio32A (lilyweight).",
            "Overflow: skill XP past level 60. Dungeons: Catacombs XP and floor completions.");
    }

    private static String weightNum(double v) {
        return String.format(Locale.ROOT, "%,.1f", v);
    }

    private static String weightExact(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    private void withOverflow(GuiGraphicsExtractor g, double weight, double overflow, int rightX, int y, int colour) {
        String ovf = overflow > 0 ? " +" + weightNum(overflow) : "";
        int ovfW = f().width(ovf);
        if (!ovf.isEmpty()) g.text(f(), ovf, rightX - ovfW, y, DIM, true);
        String val = weightNum(weight);
        g.text(f(), val, rightX - ovfW - f().width(val), y, colour, true);
    }

    private void weightColumn(GuiGraphicsExtractor g, SkyblockWeight.Result r, int x, int w, int listTop, int visible, int mx, int my, String about1, String about2) {
        inset(g, x, cy, w, ch);
        int lx = x + 6, rightX = x + w - 8, y = cy + 5;
        String heading = r.system() + " Weight";
        title(g, heading, lx, y);
        if (in(mx, my, lx, y - 1, f().width(heading), 10)) tip(line(heading, ChatFormatting.GOLD), line(about1, ChatFormatting.GRAY),
            line(about2, ChatFormatting.GRAY), line("Worked out from this profile's API data.", ChatFormatting.DARK_GRAY));
        y += 12;
        String total = weightNum(r.total());
        g.pose().pushMatrix();
        g.pose().translate(lx, y);
        g.pose().scale(1.5f, 1.5f);
        g.text(f(), total, 0, 0, GOLD, true);
        g.pose().popMatrix();
        if (in(mx, my, lx, y - 1, w - 12, 14)) tip(weightSummary(r));
        y += 14;
        g.text(f(), f().plainSubstrByWidth(weightNum(r.weight()) + " + " + weightNum(r.overflow()) + " overflow", w - 12), lx, y, DIM, true);
        y += 12;
        for (SkyblockWeight.Group grp : List.of(r.skills(), r.slayers(), r.dungeons())) {
            text(g, grp.name(), lx, y, LABEL);
            if (grp == r.skills() && !r.skillsApi()) right(g, "API off", rightX, y, DIM);
            else withOverflow(g, grp.weight(), grp.overflow(), rightX, y, VALUE);
            if (in(mx, my, lx - 2, y - 1, w - 8, 11)) tip(weightGroup(r, grp));
            y += 10;
        }
        g.fill(x + 4, listTop - 4, x + w - 4, listTop - 3, INSET_EDGE);
        List<Object[]> rows = weightRows(r);
        int start = s.pageScroll;
        for (int i = start; i < rows.size() && i - start < visible; i++) {
            Object[] row = rows.get(i);
            SkyblockWeight.Group grp = (SkyblockWeight.Group) row[0];
            SkyblockWeight.Part part = (SkyblockWeight.Part) row[1];
            int ry = listTop + (i - start) * 10;
            if (part == null) {
                title(g, grp.name(), lx, ry);
                if (grp == r.skills() && !r.skillsApi()) right(g, "Skills API off", rightX, ry, DIM);
                else withOverflow(g, grp.weight(), grp.overflow(), rightX, ry, VALUE);
                if (in(mx, my, lx - 2, ry - 1, w - 8, 10)) tip(weightGroup(r, grp));
            } else {
                text(g, f().plainSubstrByWidth(part.name(), w / 2), lx + 6, ry, part.total() > 0 ? VALUE : DIM);
                withOverflow(g, part.weight(), part.overflow(), rightX, ry, part.total() > 0 ? VALUE : DIM);
                if (in(mx, my, lx - 2, ry - 1, w - 8, 10)) {
                    List<Component> t = new ArrayList<>();
                    t.add(line(r.system() + " · " + part.name(), ChatFormatting.GOLD));
                    t.add(line("Weight: " + weightExact(part.weight()), ChatFormatting.GRAY));
                    if (part.overflow() > 0) {
                        t.add(line("Overflow: " + weightExact(part.overflow()), ChatFormatting.DARK_GRAY));
                        t.add(line("Total: " + weightExact(part.total()), ChatFormatting.GRAY));
                    }
                    for (String d : part.detail()) t.add(line(d, ChatFormatting.DARK_GRAY));
                    tip(t);
                }
            }
        }
        scrollbar(g, x + w - 4, listTop, visible * 10, rows.size(), visible);
    }

    /** Each group, then its parts: {group, part or null}. */
    private static List<Object[]> weightRows(SkyblockWeight.Result r) {
        List<Object[]> out = new ArrayList<>();
        for (SkyblockWeight.Group grp : List.of(r.skills(), r.slayers(), r.dungeons())) {
            out.add(new Object[]{grp, null});
            for (SkyblockWeight.Part p : grp.parts()) out.add(new Object[]{grp, p});
        }
        return out;
    }

    private static List<Component> weightSummary(SkyblockWeight.Result r) {
        List<Component> t = new ArrayList<>();
        t.add(line(r.system() + " Weight: " + weightExact(r.total()), ChatFormatting.GOLD));
        t.add(line("Base: " + weightExact(r.weight()) + "   Overflow: " + weightExact(r.overflow()), ChatFormatting.DARK_GRAY));
        for (SkyblockWeight.Group grp : List.of(r.skills(), r.slayers(), r.dungeons())) {
            if (grp == r.skills() && !r.skillsApi()) t.add(line(grp.name() + ": Skills API off", ChatFormatting.DARK_GRAY));
            else t.add(line(grp.name() + ": " + weightExact(grp.weight()) + (grp.overflow() > 0 ? "  (+" + weightExact(grp.overflow()) + " overflow)" : ""), ChatFormatting.GRAY));
        }
        return t;
    }

    private static List<Component> weightGroup(SkyblockWeight.Result r, SkyblockWeight.Group grp) {
        List<Component> t = new ArrayList<>();
        t.add(line(r.system() + " · " + grp.name() + ": " + weightExact(grp.total()), ChatFormatting.GOLD));
        if (grp == r.skills() && !r.skillsApi()) {
            t.add(line("Their Skills API is off.", ChatFormatting.DARK_GRAY));
            return t;
        }
        if (grp.overflow() > 0) t.add(line("Base " + weightExact(grp.weight()) + " + overflow " + weightExact(grp.overflow()), ChatFormatting.DARK_GRAY));
        for (SkyblockWeight.Part p : grp.parts()) {
            t.add(line("  " + p.name() + ": " + weightExact(p.weight()) + (p.overflow() > 0 ? " (+" + weightExact(p.overflow()) + ")" : ""),
                p.total() > 0 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
        }
        return t;
    }

    // ------------------------------------------------------------------------------------------------ misc

    private void misc(GuiGraphicsExtractor g, int mx, int my) {
        ProfileExtras.Misc m = ex().misc;
        int navW = 84;
        inset(g, cx, cy, navW, ch);
        int view = nav(g, new String[]{"General", "Kills", "Deaths"}, cx + 4, cy + 4, navW - 8, mx, my);
        int rx = cx + navW + 4, rw = cw - navW - 4;
        inset(g, rx, cy, rw, ch);
        switch (view) {
            case 0 -> general(g, m, rx, rw, mx, my);
            case 1 -> counts(g, "Kills", m.kills(), rx, rw, mx, my);
            default -> counts(g, "Deaths", m.deaths(), rx, rw, mx, my);
        }
    }

    private static long totalOrMax(Map<String, Long> counts) {
        Long total = counts.get("total");
        if (total != null) return total;
        long best = 0;
        for (Long v : counts.values()) if (v != null && v > best) best = v;
        return best;
    }

    private void general(GuiGraphicsExtractor g, ProfileExtras.Misc m, int rx, int rw, int mx, int my) {
        int x = rx + 6, w = (rw - 24) / 2, y = cy + 5;
        title(g, "Player", x, y);
        y += 12;
        text(g, "Rank", x, y, LABEL);
        UUID uuid = s.uuid();
        CompletableFuture<JsonObject> pf = uuid == null ? null : ProfileApi.aux(ProfileApi.Aux.PLAYER, uuid.toString());
        if (pf == null || !pf.isDone()) right(g, "...", x + w, y, DIM);
        else if (pf.isCompletedExceptionally() || pf.getNow(null) == null) right(g, "Unavailable", x + w, y, DIM);
        else {
            String rank = rank(pf.getNow(null));
            g.text(f(), rank, x + w - f().width(rank), y, 0xFFFFFFFF, true);
        }
        y += 10;
        y = stat(g, x, y, w, "First Join", date((long) Json.num(Json.obj(member(), "profile"), "first_join")));
        y = stat(g, x, y, w, "SkyBlock XP", commas(m.sbXp()));
        y = stat(g, x, y, w, "Cookie Buff", m.cookieBuff() ? "Active" : "Inactive");
        long kills = totalOrMax(m.kills()), deaths = totalOrMax(m.deaths());
        y = stat(g, x, y, w, "Kills", commas(kills));
        y = stat(g, x, y, w, "Deaths", commas(deaths));
        y = stat(g, x, y, w, "K/D", deaths == 0 ? "-" : String.format(Locale.ROOT, "%.2f", (double) kills / deaths));
        y = stat(g, x, y, w, "Highest Crit", shorten(m.highestCrit()));
        y = stat(g, x, y, w, "Items Fished", commas(m.itemsFished()));
        y = stat(g, x, y, w, "Mythos Kills", commas(m.mythosKills()));
        y = stat(g, x, y, w, "Minions Crafted", commas(m.minionsCrafted()));
        y = stat(g, x, y, w, "Fairy Exchanges", commas(m.fairyExchanges()));
        if (in(mx, my, x, y, w, 10)) tip(line("Gifts received: " + commas(m.giftsReceived()), ChatFormatting.GRAY));
        stat(g, x, y, w, "Gifts Given", commas(m.giftsGiven()));

        int ex = x + w + 12, ey = cy + 5;
        title(g, "Essence", ex, ey);
        ey += 12;
        if (m.essence().isEmpty()) {
            text(g, "None", ex, ey, DIM);
            ey += 11;
        }
        for (Map.Entry<String, Long> e : m.essence().entrySet()) ey = stat(g, ex, ey, w, titleCase(e.getKey()), commas(e.getValue()));
        ey += 4;
        title(g, "Auctions", ex, ey);
        ey += 12;
        if (m.auctions().isEmpty()) text(g, "None", ex, ey, DIM);
        for (String key : List.of("created", "completed", "bids", "won", "gold_spent", "gold_earned", "highest_bid")) {
            if (ey > cy + ch - 11) break;
            Long v = m.auctions().get(key);
            if (v != null) ey = stat(g, ex, ey, w, titleCase(key.replace("gold", "coins")), key.startsWith("gold") || key.equals("highest_bid") ? shorten(v) : commas(v));
        }
    }

    private void counts(GuiGraphicsExtractor g, String label, Map<String, Long> counts, int rx, int rw, int mx, int my) {
        List<Map.Entry<String, Long>> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) if (!e.getKey().equals("total") && e.getValue() > 0) list.add(e);
        title(g, label, rx + 6, cy + 5);
        Long total = counts.get("total");
        right(g, total != null ? "Total " + commas(total) : list.size() + " kinds", rx + rw - 8, cy + 5, VALUE);
        if (list.isEmpty()) {
            center(g, "None.", rx, rw, DIM);
            return;
        }
        int rowH = 11, top = cy + 18, visible = Math.max(1, (ch - 22) / rowH);
        int rows = (list.size() + 1) / 2;
        int scroll = clampScroll(rows, visible);
        int colW = (rw - 24) / 2;
        for (int i = scroll * 2; i < list.size(); i++) {
            int row = i / 2 - scroll;
            if (row >= visible) break;
            Map.Entry<String, Long> e = list.get(i);
            int x = rx + 6 + (i % 2) * (colW + 8), y = top + row * rowH;
            String val = shorten(e.getValue()), name = titleCase(e.getKey());
            text(g, f().plainSubstrByWidth(name, colW - f().width(val) - 6), x, y, LABEL);
            right(g, val, x + colW, y, VALUE);
            if (in(mx, my, x, y, colW, rowH)) tip(line(name, ChatFormatting.GOLD), line(commas(e.getValue()), ChatFormatting.GRAY));
        }
        scrollbar(g, rx + rw - 5, top, visible * rowH, rows, visible);
    }

    /** Their Hypixel rank, with colour codes, from /v2/player (the same order Hypixel picks them in). */
    private static String rank(JsonObject player) {
        String prefix = Json.str(player, "prefix");
        if (!prefix.isBlank()) return prefix;
        String special = Json.str(player, "rank");
        if (!special.isEmpty() && !special.equals("NORMAL")) {
            return switch (special) {
                case "ADMIN" -> "§c[ADMIN]";
                case "GAME_MASTER" -> "§2[GM]";
                case "MODERATOR" -> "§2[MOD]";
                case "HELPER" -> "§9[HELPER]";
                case "YOUTUBER" -> "§c[§fYOUTUBE§c]";
                default -> "§7[" + special + "]";
            };
        }
        String plus = colourCode(Json.str(player, "rankPlusColor"));
        if ("SUPERSTAR".equals(Json.str(player, "monthlyPackageRank"))) {
            String base = "AQUA".equals(Json.str(player, "monthlyRankColor")) ? "§b" : "§6";
            return base + "[MVP" + plus + "++" + base + "]";
        }
        String pkg = Json.str(player, "newPackageRank");
        if (pkg.isEmpty() || pkg.equals("NONE")) pkg = Json.str(player, "packageRank");
        return switch (pkg) {
            case "MVP_PLUS" -> "§b[MVP" + plus + "+§b]";
            case "MVP" -> "§b[MVP]";
            case "VIP_PLUS" -> "§a[VIP§6+§a]";
            case "VIP" -> "§a[VIP]";
            default -> "§7None";
        };
    }

    private static String colourCode(String name) {
        try {
            ChatFormatting fmt = ChatFormatting.valueOf(name.toUpperCase(Locale.ROOT));
            // The first 16 are the colours; the rest are bold, italic and so on.
            return fmt.ordinal() < 16 ? fmt.toString() : "§c";
        } catch (IllegalArgumentException e) {
            return "§c";
        }
    }
}
