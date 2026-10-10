package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Bestiary Data overlay (https://github.com/hannibal002/SkyHanni, LGPL-2.1: BestiaryData, BestiaryApi,
 * BestiaryConfig). In the Bestiary menu it lists each family's kills and progress to max or to the next tier, sorted
 * by the chosen display type, and each category's families found and completed, with clickable Number Format, Display
 * Type, Number Type and Hide Maxed lines. Maxed families and categories are highlighted green; with Overall Progress
 * hidden the overlay asks you to turn it on and its Eye of Ender is highlighted red.
 */
public final class BestiaryOverlay {
    private static final String HUD_ID = "bestiary_overlay";

    // SkyHanni's repo patterns (combat.bestiary, combat.bestiary.data), on colourless text.
    private static final Pattern TIER_PROGRESS = Pattern.compile("Progress to Tier [\\dIVXC]+: [\\d.]+%");
    private static final Pattern OVERALL_PROGRESS = Pattern.compile("Overall Progress: [\\d.]+%(?: \\(MAX!\\))?");
    private static final Pattern PROGRESS = Pattern.compile("(?<current>[0-9kKmMbB,.]+)/(?<needed>[0-9kKmMbB,.]+)$");
    private static final Pattern TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?(?<parent>[^➜]+) ➜ (?<category>.+)$");
    private static final Pattern SEARCH_RESULTS = Pattern.compile("^Search Results$");
    private static final Pattern CATEGORY_OF_CATEGORIES = Pattern.compile("^(?:Bestiary|Bestiary ➜ Fishing|Bestiary ➜ Critter Safari)$");
    private static final Pattern MOB_LEVEL = Pattern.compile("^(?<name>.+?)(?: (?<level>[IVX0-9]+))?$");
    private static final Pattern KILLS_LINE = Pattern.compile("Kills: (?<kills>[0-9,.]+)");
    private static final Pattern PROGRESS_BAR_LINE = Pattern.compile(" {20}.*");
    private static final Pattern NOT_UNLOCKED_FAMILY = Pattern.compile("You haven't unlocked this Family yet!");
    private static final Pattern OVERALL_PROGRESS_SHOWN = Pattern.compile("Overall Progress: SHOWN");
    private static final Pattern FAMILY_FOUND = Pattern.compile("\\s*Families Found.*");
    private static final Pattern FAMILY_COMPLETED = Pattern.compile("\\s*Families Completed.*");
    private static final Pattern COMPLETED = Pattern.compile("Overall Progress: 100% \\(MAX!\\)|Families Completed: 100%");
    private static final Pattern HIDDEN_PROGRESS = Pattern.compile("Overall Progress: HIDDEN");

    private static final int OVERALL_PROGRESS_SLOT = 52;
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;
    private static final int GREEN = 0x8055FF55;
    private static final int RED = 0x80FF5555;
    private static final int CONTROL_HOVER = 0x20FFFFFF;
    private static final ThreadLocal<DecimalFormat> SHORT = ThreadLocal.withInitial(() -> new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final ThreadLocal<DecimalFormat> PERCENT = ThreadLocal.withInitial(() -> new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US)));

    private enum Control { NUMBER_FORMAT, DISPLAY_TYPE, NUMBER_TYPE, HIDE_MAXED }

    private record Category(Component name, long familiesFound, long totalFamilies, long familiesCompleted) {}

    private record Mob(Component name, int level, long killToMax, long totalKills, long killNeededForNextLevel,
                       long currentKillToNextLevel, long actualRealTotalKill) {
        long killNeededToMax() {
            return Math.max(0, killToMax - actualRealTotalKill);
        }

        long killNeededToNextLevel() {
            return Math.max(0, killNeededForNextLevel - currentKillToNextLevel);
        }

        double percentToMax() {
            return killToMax == 0 ? 0 : (double) actualRealTotalKill / killToMax;
        }

        double percentToTier() {
            return killNeededForNextLevel == 0 ? 1 : (double) currentKillToNextLevel / killNeededForNextLevel;
        }
    }

    private record Row(Component text, List<Component> hover, Control control) {}

    private record Area(Control control, List<Component> hover, int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** The open Bestiary menu's state, read from its items every other tick. */
    private static boolean inBestiary;
    private static boolean overallProgressEnabled;
    private static List<Category> categories = List.of();
    private static List<Mob> mobs = List.of();
    /** Chest slot index -> highlight colour (ARGB). */
    private static Map<Integer, Integer> highlights = Map.of();
    private static List<Row> rows = List.of();
    private static final List<Area> areas = new ArrayList<>();
    private static int ticks;

    private BestiaryOverlay() {}

    private static FeatureConfigs.Bestiary config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.combat.bestiary;
    }

    private static boolean enabled() {
        FeatureConfigs.Bestiary config = config();
        return config != null && config.enabled;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 2 == 0) update(mc);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> renderInScreen(graphics, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event.x(), event.y()));
        });
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> clear());

        Sky2MHuds.registerCustom(HUD_ID, "Bestiary Overlay", BestiaryOverlay::enabled, new Sky2MHuds.CustomHud() {
            @Override
            public int width() {
                return layoutWidth(shownRows());
            }

            @Override
            public int height() {
                return layoutHeight(shownRows());
            }

            /** Only drawn over the Bestiary menu, never on the HUD. */
            @Override
            public boolean visible() {
                return false;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                draw(graphics, shownRows(), null, null);
            }
        }, 8, 8);
    }

    private static void clear() {
        inBestiary = false;
        categories = List.of();
        mobs = List.of();
        if (!highlights.isEmpty()) highlights = Map.of();
        rows = List.of();
    }

    // ------------------------------------------------------------------------------------------------ reading the menu

    private static void update(Minecraft mc) {
        if (!enabled() || !Compat.isOnSkyblock() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            if (inBestiary) clear();
            return;
        }
        String title = Sky2MLocation.strip(screen.getTitle().getString()).trim();
        Map<Integer, ItemStack> items = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || slot.getItem().isEmpty()) continue;
            items.put(slot.index, slot.getItem());
        }
        ItemStack header = items.get(4);
        boolean categoryOfCategories = CATEGORY_OF_CATEGORIES.matcher(title).matches();
        if (header == null || (!categoryOfCategories && !isBestiaryGui(header, title))) {
            if (inBestiary) clear();
            return;
        }

        inBestiary = true;
        overallProgressEnabled = isOverallProgressEnabled(items);
        List<String> headerLore = cleanLore(header);
        boolean hasFamilies = headerLore.stream().anyMatch(l -> FAMILY_FOUND.matcher(l).matches() || FAMILY_COMPLETED.matcher(l).matches());
        if (categoryOfCategories) {
            categories = parseCategories(items);
            mobs = List.of();
        } else if (hasFamilies || SEARCH_RESULTS.matcher(title).matches()) {
            categories = List.of();
            mobs = parseMobs(items);
        } else {
            // A family's variants: SkyHanni shows nothing for those yet.
            categories = List.of();
            mobs = List.of();
        }

        Map<Integer, Integer> next = new HashMap<>();
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            List<String> lore = cleanLore(entry.getValue());
            if (lore.stream().anyMatch(l -> COMPLETED.matcher(l).matches())) next.put(entry.getKey(), GREEN);
            if (!overallProgressEnabled && lore.stream().anyMatch(l -> HIDDEN_PROGRESS.matcher(l).matches())) next.put(entry.getKey(), RED);
        }
        highlights = next;
        rows = buildRows();
    }

    private static boolean isBestiaryGui(ItemStack header, String title) {
        Matcher m = TITLE.matcher(title);
        if (m.matches()) {
            if (!"Bestiary".equals(m.group("parent"))) {
                List<String> lore = cleanLore(header);
                boolean familiesFound = lore.stream().anyMatch(l -> FAMILY_FOUND.matcher(l).matches());
                boolean kills = lore.stream().anyMatch(l -> KILLS_LINE.matcher(l).find());
                return familiesFound || kills;
            }
            return true;
        }
        if (SEARCH_RESULTS.matcher(title).matches()) {
            List<String> lore = cleanLore(header);
            return lore.size() >= 2 && lore.get(0).startsWith("Query: ") && lore.get(1).startsWith("Results: ");
        }
        return false;
    }

    private static boolean isOverallProgressEnabled(Map<Integer, ItemStack> items) {
        ItemStack eye = items.get(OVERALL_PROGRESS_SLOT);
        if (eye != null && eye.is(Items.ENDER_EYE)) {
            return cleanLore(eye).stream().anyMatch(l -> OVERALL_PROGRESS_SHOWN.matcher(l).matches());
        }
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            if (!isInnerSlot(entry.getKey())) continue;
            List<String> lore = cleanLore(entry.getValue());
            boolean tier = lore.stream().anyMatch(l -> TIER_PROGRESS.matcher(l).matches());
            boolean overall = lore.stream().anyMatch(l -> OVERALL_PROGRESS.matcher(l).matches());
            if (tier && !overall) return false;
        }
        return true;
    }

    /** The chest's slots inside its glass border (rows 2-5, columns 2-8). */
    private static boolean isInnerSlot(int index) {
        int row = index / 9, column = index % 9;
        return row >= 1 && row <= 4 && column >= 1 && column <= 7;
    }

    private static List<Category> parseCategories(Map<Integer, ItemStack> items) {
        List<Category> list = new ArrayList<>();
        items.entrySet().stream().filter(e -> isInnerSlot(e.getKey())).sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ItemStack stack = entry.getValue();
            if (plainName(stack).isBlank()) return;
            long found = 0, total = 0, completed = 0;
            List<String> lore = cleanLore(stack);
            for (int i = 1; i < lore.size(); i++) {
                String line = lore.get(i);
                if (!line.startsWith("                    ")) continue;
                Matcher progress = PROGRESS.matcher(line.substring(line.lastIndexOf(' ') + 1));
                if (!progress.matches()) continue;
                String previous = lore.get(i - 1);
                if (FAMILY_FOUND.matcher(previous).matches()) {
                    found = parseNumber(progress.group("current"));
                    total = parseNumber(progress.group("needed"));
                } else if (FAMILY_COMPLETED.matcher(previous).matches()) {
                    completed = parseNumber(progress.group("current"));
                }
            }
            if (total > 0) list.add(new Category(styledName(stack, plainName(stack)), found, total, completed));
        });
        return list;
    }

    private static List<Mob> parseMobs(Map<Integer, ItemStack> items) {
        List<Mob> list = new ArrayList<>();
        items.entrySet().stream().filter(e -> isInnerSlot(e.getKey())).sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Mob mob = parseMob(entry.getValue());
            if (mob != null) list.add(mob);
        });
        return list;
    }

    private static Mob parseMob(ItemStack stack) {
        String plain = plainName(stack);
        if (plain.isBlank()) return null;
        Matcher nameMatcher = MOB_LEVEL.matcher(plain);
        if (!nameMatcher.matches()) return null;
        String level = nameMatcher.group("level") == null ? "0" : nameMatcher.group("level");

        long killToMax = 0, totalKills = 0, killsForTier = 0, currentKillsToTier = 0, realKills = 0;
        boolean unlocked = true;
        List<String> lore = cleanLore(stack);
        for (int i = 0; i < lore.size(); i++) {
            String line = lore.get(i);
            if (NOT_UNLOCKED_FAMILY.matcher(line).matches()) unlocked = false;
            Matcher kills = KILLS_LINE.matcher(line);
            if (kills.find()) realKills = parseNumber(kills.group("kills"));

            if (i == 0 || !PROGRESS_BAR_LINE.matcher(line).matches()) continue;
            Matcher progress = PROGRESS.matcher(line.substring(line.lastIndexOf(' ') + 1));
            if (!progress.matches()) continue;
            String previous = lore.get(i - 1);
            if (TIER_PROGRESS.matcher(previous).matches()) {
                killsForTier = parseNumber(progress.group("needed"));
                currentKillsToTier = parseNumber(progress.group("current"));
            } else if (OVERALL_PROGRESS.matcher(previous).matches()) {
                killToMax = parseNumber(progress.group("needed"));
                totalKills = parseNumber(progress.group("current"));
            }
        }
        if (killToMax == 0 && killsForTier == 0 && unlocked) return null;
        return new Mob(styledName(stack, nameMatcher.group("name")), romanToDecimalIfNecessary(level),
            killToMax, totalKills, killsForTier, currentKillsToTier, realKills);
    }

    // ------------------------------------------------------------------------------------------------ rows

    private static List<Row> shownRows() {
        return inBestiary ? rows : previewRows();
    }

    /** SkyHanni's drawDisplay. */
    private static List<Row> buildRows() {
        FeatureConfigs.Bestiary config = config();
        List<Row> list = new ArrayList<>();
        if (config == null) return list;
        if (!overallProgressEnabled) {
            list.add(text("§7Bestiary Data"));
            list.add(text(" §cPlease enable Overall Progress"));
            list.add(text(" §cUsing the Eye of Ender highlighted in red."));
            return list;
        }

        if (!categories.isEmpty()) {
            list.add(text("§7Category"));
            for (Category category : categories) {
                String info;
                if (category.familiesCompleted() == category.totalFamilies()) {
                    info = "§c§lCompleted!";
                } else if (category.familiesFound() == category.totalFamilies()) {
                    info = "§b" + category.familiesCompleted() + "§7/§b" + category.totalFamilies() + " §7completed";
                } else if (category.familiesFound() < category.totalFamilies()) {
                    info = "§b" + category.familiesFound() + "§7/§b" + category.totalFamilies() + " §7found, §b"
                        + category.familiesCompleted() + "§7/§b" + category.totalFamilies() + " §7completed";
                } else {
                    continue;
                }
                list.add(new Row(Component.literal(" - ").withStyle(ChatFormatting.GRAY).append(category.name())
                    .append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(legacy(info)), null, null));
            }
        }

        if (mobs.isEmpty()) return list;
        list.add(text("§7Bestiary Data"));
        for (Mob mob : sorted(mobs, config.displayType)) {
            boolean maxed = mob.percentToMax() >= 1;
            if (mob.actualRealTotalKill() == 0) {
                list.add(new Row(Component.literal(" - ").withStyle(ChatFormatting.GRAY).append(mob.name())
                    .append(Component.literal(": Not unlocked!").withStyle(ChatFormatting.RED)), null, null));
                continue;
            }
            if (maxed && config.hideMaxed) continue;
            list.add(new Row(mobLine(mob, maxed, config), mobHover(mob, config), null));
        }

        list.add(control("§7Number Format: §e[" + config.numberFormat + "]", Control.NUMBER_FORMAT));
        list.add(control("§7Display Type: §e[" + config.displayType + "]", Control.DISPLAY_TYPE));
        list.add(control("§7Number Type: §e[" + (config.replaceRoman ? "Normal (1, 2, 3)" : "Roman (I, II, III)") + "]", Control.NUMBER_TYPE));
        list.add(control("§7Hide Maxed: §e[" + (config.hideMaxed ? "Hide" : "Show") + "]", Control.HIDE_MAXED));
        return list;
    }

    private static List<Mob> sorted(List<Mob> list, FeatureConfigs.Bestiary.DisplayType type) {
        Comparator<Mob> order = switch (type) {
            case GLOBAL_MAX -> Comparator.comparingDouble(Mob::percentToMax);
            case GLOBAL_NEXT -> Comparator.comparingDouble(Mob::percentToTier);
            case LOWEST_TOTAL -> Comparator.comparingLong(Mob::actualRealTotalKill);
            case HIGHEST_TOTAL -> Comparator.comparingLong(Mob::actualRealTotalKill).reversed();
            case LOWEST_MAX -> Comparator.comparingLong(Mob::killNeededToMax);
            case HIGHEST_MAX -> Comparator.comparingLong(Mob::killNeededToMax).reversed();
            case LOWEST_NEXT -> Comparator.comparingLong(Mob::killNeededToNextLevel);
            case HIGHEST_NEXT -> Comparator.comparingLong(Mob::killNeededToNextLevel).reversed();
        };
        return list.stream().sorted(order).toList();
    }

    private static Component mobLine(Mob mob, boolean maxed, FeatureConfigs.Bestiary config) {
        FeatureConfigs.Bestiary.DisplayType type = config.displayType;
        String info;
        if (maxed) {
            info = "§c§lMAXED! §7(§b" + format(mob.actualRealTotalKill(), config) + "§7 kills)";
        } else {
            info = switch (type) {
                case GLOBAL_MAX, GLOBAL_NEXT -> {
                    boolean max = type == FeatureConfigs.Bestiary.DisplayType.GLOBAL_MAX;
                    long current = max ? mob.totalKills() : mob.currentKillToNextLevel();
                    long needed = max ? mob.killToMax() : mob.killNeededForNextLevel();
                    double percentage = needed == 0 ? 100 : current * 100d / needed;
                    String suffix = max ? "" : " §ato level " + romanOrInt(mob.level() + 1, config);
                    yield "§7(§b" + format(current, config) + "§7/§b" + format(needed, config) + "§7) §a"
                        + PERCENT.get().format(percentage) + "§6%" + suffix;
                }
                case LOWEST_TOTAL, HIGHEST_TOTAL -> "§6" + format(mob.actualRealTotalKill(), config) + " §7total kills";
                case LOWEST_MAX, HIGHEST_MAX -> "§6" + format(mob.killNeededToMax(), config) + " §7kills needed";
                case LOWEST_NEXT, HIGHEST_NEXT -> "§6" + format(mob.killNeededToNextLevel(), config) + " §7kills needed";
            };
        }
        return Component.literal(" - ").withStyle(ChatFormatting.GRAY).append(mob.name())
            .append(Component.literal(" " + romanOrInt(mob.level(), config) + ": ")).append(legacy(info));
    }

    private static List<Component> mobHover(Mob mob, FeatureConfigs.Bestiary config) {
        String roman = mob.level() == 0 ? "0" : toRoman(mob.level());
        return List.of(
            legacy("§6Name: ").append(mob.name()),
            legacy("§6Level: §b" + roman + (config.replaceRoman ? "" : " §7(" + mob.level() + ")")),
            legacy("§6Total Kills: §b" + format(mob.actualRealTotalKill(), config)),
            legacy("§6Kills needed to max: §b" + format(mob.killNeededToMax(), config)),
            legacy("§6Kills needed to next lvl: §b" + format(mob.killNeededToNextLevel(), config)),
            legacy("§6Current kill to next level: §b" + format(mob.currentKillToNextLevel(), config)),
            legacy("§6Kill needed for next level: §b" + format(mob.killNeededForNextLevel(), config)),
            legacy("§6Current kill to max: §b" + format(mob.killToMax(), config)),
            legacy("§6Percent to max: §b" + PERCENT.get().format(mob.percentToMax() * 100) + "%"),
            legacy("§6Percent to tier: §b" + PERCENT.get().format(mob.percentToTier() * 100) + "%"));
    }

    /** Sample rows for /s2 gui. */
    private static List<Row> previewRows() {
        return List.of(
            text("§7Bestiary Data"),
            text(" §7- §aZombie VIII: §7(§b3.2k§7/§b10k§7) §a32§6%"),
            text(" §7- §aSkeleton XII: §7(§b8.9k§7/§b10k§7) §a89§6%"),
            text(" §7- §aSpider XV: §c§lMAXED! §7(§b12.4k§7 kills)"),
            text("§7Number Format: §e[Short]"),
            text("§7Display Type: §e[Global to max]"));
    }

    private static Row text(String legacy) {
        return new Row(legacy(legacy), null, null);
    }

    private static Row control(String legacy, Control control) {
        return new Row(legacy(legacy), null, control);
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static boolean background() {
        return Sky2MHuds.placement(HUD_ID).background;
    }

    private static int layoutWidth(List<Row> list) {
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (Row row : list) width = Math.max(width, font.width(row.text()));
        return width + (background() ? PADDING * 2 : 0);
    }

    private static int layoutHeight(List<Row> list) {
        return list.size() * LINE_HEIGHT + (background() ? PADDING * 2 : 0);
    }

    /** Draws the rows at (0, 0); with {@code mouseX} given, hovered controls light up. Returns the hoverable areas. */
    private static List<Area> draw(GuiGraphicsExtractor g, List<Row> list, Integer mouseX, Integer mouseY) {
        Font font = Minecraft.getInstance().font;
        int padding = background() ? PADDING : 0;
        int width = layoutWidth(list);
        if (padding > 0) g.fill(0, 0, width, layoutHeight(list), 0x80000000);
        List<Area> drawn = new ArrayList<>();
        int y = padding;
        for (Row row : list) {
            if (row.control() != null || row.hover() != null) {
                Area area = new Area(row.control(), row.hover(), padding, y, font.width(row.text()), LINE_HEIGHT);
                drawn.add(area);
                if (row.control() != null && mouseX != null && area.contains(mouseX, mouseY)) {
                    g.fill(area.x(), area.y() - 1, area.x() + area.w(), area.y() + area.h() - 1, CONTROL_HOVER);
                }
            }
            g.text(font, row.text(), padding, y, 0xFFFFFFFF, true);
            y += LINE_HEIGHT;
        }
        return drawn;
    }

    private static void renderInScreen(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        areas.clear();
        if (!inBestiary || !enabled() || rows.isEmpty()) return;
        Sky2MHuds.Placement p = Sky2MHuds.placement(HUD_ID);
        int w = layoutWidth(rows), h = layoutHeight(rows);
        int x = Sky2MHuds.mapX(p.x, Math.round(w * p.scale));
        int y = Sky2MHuds.mapY(p.y, Math.round(h * p.scale));
        int localX = Math.round((mouseX - x) / p.scale), localY = Math.round((mouseY - y) / p.scale);
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(p.scale, p.scale);
        List<Area> drawn = draw(g, rows, localX, localY);
        g.pose().popMatrix();
        for (Area area : drawn) {
            Area scaled = new Area(area.control(), area.hover(), x + Math.round(area.x() * p.scale), y + Math.round(area.y() * p.scale),
                Math.round(area.w() * p.scale), Math.round(area.h() * p.scale));
            areas.add(scaled);
            if (scaled.hover() != null && scaled.contains(mouseX, mouseY)) {
                g.setComponentTooltipForNextFrame(Minecraft.getInstance().font, scaled.hover(), mouseX, mouseY);
            }
        }
    }

    /** A click on one of the overlay's buttons; true if it was one (the menu doesn't get it). */
    private static boolean click(double mouseX, double mouseY) {
        FeatureConfigs.Bestiary config = config();
        if (config == null || !inBestiary) return false;
        for (Area area : areas) {
            if (area.control() == null || !area.contains(mouseX, mouseY)) continue;
            switch (area.control()) {
                case NUMBER_FORMAT -> config.numberFormat = next(FeatureConfigs.Bestiary.NumberFormat.values(), config.numberFormat);
                case DISPLAY_TYPE -> config.displayType = next(FeatureConfigs.Bestiary.DisplayType.values(), config.displayType);
                case NUMBER_TYPE -> config.replaceRoman = !config.replaceRoman;
                case HIDE_MAXED -> config.hideMaxed = !config.hideMaxed;
            }
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
            rows = buildRows();
            return true;
        }
        return false;
    }

    private static <E extends Enum<E>> E next(E[] values, E current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    /** Drawn behind the slot's item, from Sky2MSlotBackgroundMixin. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        if (highlights.isEmpty() || !inBestiary || slot.container instanceof Inventory) return;
        Integer colour = highlights.get(slot.index);
        if (colour != null) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour);
    }

    // ------------------------------------------------------------------------------------------------ text

    private static MutableComponent legacy(String text) {
        return SbcItems.parseLegacy(text);
    }

    private static String plainName(ItemStack stack) {
        return Sky2MLocation.strip(Compat.realName(stack).getString()).trim();
    }

    private static List<String> cleanLore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) lines.add(Sky2MLocation.strip(line.getString()));
        return lines;
    }

    /** The item's name text (without its tier) in the colour of the name. */
    private static Component styledName(ItemStack stack, String text) {
        Component name = Compat.realName(stack);
        String raw = name.getString();
        if (raw.indexOf('§') >= 0) {
            int i = 0;
            while (i + 1 < raw.length() && raw.charAt(i) == '§') i += 2;
            return legacy(raw.substring(0, i) + text);
        }
        Style[] style = {Style.EMPTY};
        name.visit((s, segment) -> {
            if (segment.isBlank()) return Optional.empty();
            style[0] = s;
            return Optional.of(Boolean.TRUE);
        }, Style.EMPTY);
        return Component.literal(text).withStyle(style[0]);
    }

    /** "1,234", "1.2k", "3M". */
    private static long parseNumber(String text) {
        String clean = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double multiplier = 1;
        if (clean.endsWith("k")) multiplier = 1_000;
        else if (clean.endsWith("m")) multiplier = 1_000_000;
        else if (clean.endsWith("b")) multiplier = 1_000_000_000;
        if (multiplier != 1) clean = clean.substring(0, clean.length() - 1);
        try {
            return Math.round(Double.parseDouble(clean) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String format(long value, FeatureConfigs.Bestiary config) {
        if (config.numberFormat == FeatureConfigs.Bestiary.NumberFormat.LONG) return String.format(Locale.US, "%,d", value);
        // SkyHanni's shortFormat: 950, 1.1k, 12.3k, 4.5M, 1.2B.
        if (value < 1_000) return Long.toString(value);
        if (value < 1_000_000) return SHORT.get().format(value / 1_000d) + "k";
        if (value < 1_000_000_000) return SHORT.get().format(value / 1_000_000d) + "M";
        return SHORT.get().format(value / 1_000_000_000d) + "B";
    }

    private static String romanOrInt(int level, FeatureConfigs.Bestiary config) {
        return config.replaceRoman || level == 0 ? Integer.toString(level) : toRoman(level);
    }

    private static int romanToDecimalIfNecessary(String text) {
        if (text.matches("\\d+")) return Integer.parseInt(text);
        Map<Character, Integer> values = Map.of('I', 1, 'V', 5, 'X', 10, 'L', 50, 'C', 100, 'D', 500, 'M', 1000);
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            Integer v = values.get(text.charAt(i));
            if (v == null) return 0;
            Integer next = i + 1 < text.length() ? values.get(text.charAt(i + 1)) : null;
            total += next != null && next > v ? -v : v;
        }
        return total;
    }

    private static String toRoman(int number) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                number -= values[i];
                out.append(symbols[i]);
            }
        }
        return out.toString();
    }
}
