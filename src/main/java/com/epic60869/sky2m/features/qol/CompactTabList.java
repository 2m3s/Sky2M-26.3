package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.mixin.Sky2MPlayerTabOverlayAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact Tab List, ported from SkyHanni's compact tab list (https://github.com/hannibal002/SkyHanni, LGPL-2.1,
 * features/misc/compacttablist: TabListReader, TabListRenderer, TabColumn, TabSection, RenderColumn, TabLine and
 * TabStringType). Hypixel's tab list is read as columns of 20 lines, split into sections at its blank lines, and the
 * footer becomes an "Other" column; the sections are then packed into columns of at most 22 lines with each column's
 * title once, and drawn side by side in place of the vanilla tab list (Sky2MCompactTabMixin). Display only.
 */
public final class CompactTabList {
    private static final int MAX_LINES = 22;
    private static final int LINE_HEIGHT = 8 + 1;
    private static final int TAB_PADDING = 3;
    private static final int COLUMN_SPACING = 6;
    private static final String HYPIXEL_ADVERT = "HYPIXEL.NET";

    private static final Pattern USERNAME = Pattern.compile("^\\[(?<sblevel>\\d+)] (?:\\[\\w+] )?(?<username>\\w+)");
    private static final Pattern GOD_POT = Pattern.compile("You have a God Potion active! (?<timer>[\\w ]+)");
    private static final Pattern ACTIVE_EFFECTS = Pattern.compile("Active Effects");
    private static final Pattern NO_ACTIVE_EFFECTS = Pattern.compile("No effects active\\. Drink Potions or splash them on the|ground to buff yourself!");
    private static final Pattern EFFECT_COUNT = Pattern.compile("You have (?<effectCount>[0-9]+) (?:active|non-god) effects?.*");
    private static final Pattern COOKIE = Pattern.compile("Cookie Buff");
    private static final Pattern NO_COOKIE = Pattern.compile("Not active! Obtain booster cookies from the community|shop in the hub\\.");
    private static final Pattern DUNGEON_BUFF = Pattern.compile("Dungeon Buffs");
    private static final Pattern EFFECTS_USE_COMMAND = Pattern.compile("Use \"/effects\".*");
    private static final Pattern UPGRADES_HEADER = Pattern.compile("Upgrades");
    private static final Pattern UPGRADES = Pattern.compile("(?<firstPart>[A-Za-z ]+)(?<secondPart> [\\w ]+)");
    private static final Pattern WINTER_POWER_UPS = Pattern.compile("Active Power Ups");
    private static final Pattern FIRE_SALE_TITLE = Pattern.compile("Fire Sales: \\([0-9]+\\)");

    private enum Type { TITLE, SUB_TITLE, TEXT, PLAYER }

    private record TabLine(Component component, Type type) {
        int width(Font font) {
            int width = font.width(component);
            if (type == Type.PLAYER) width += 8 + 2;
            if (type == Type.TEXT) width += 4;
            return width;
        }

        PlayerInfo info() {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection == null) return null;
            Matcher m = USERNAME.matcher(component.getString());
            return connection.getPlayerInfo(m.find() ? m.group("username") : component.getString());
        }
    }

    /** One of Hypixel's columns (merged with any later column of the same title), and its sections. */
    private static final class TabColumn {
        final String title;
        final Component titleComponent;
        final List<Component> lines = new ArrayList<>();
        final List<List<Component>> sections = new ArrayList<>();

        TabColumn(String title) {
            this.title = title;
            this.titleComponent = SbcItems.parseLegacy(title);
        }
    }

    private static final class RenderColumn {
        final List<TabLine> lines;

        RenderColumn(List<TabLine> lines) {
            this.lines = lines;
        }

        RenderColumn() {
            this(new ArrayList<>());
        }

        int size() {
            return lines.size();
        }

        int maxWidth(Font font) {
            int max = 0;
            for (TabLine line : lines) max = Math.max(max, line.width(font));
            return max;
        }
    }

    private static List<RenderColumn> renderColumns = List.of();
    private static long lastBuild;
    private static boolean inUpgrades;
    private static boolean pressed;
    private static boolean toggled;

    private CompactTabList() {}

    private static QolConfigs.TabListExtras config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.gui.tabListExtras;
    }

    public static void init() {
        // Toggle Tab: the list stays open after a Tab press, drawn after the vanilla tab list slot so it covers the HUDs.
        HudElementRegistry.attachElementAfter(VanillaHudElements.PLAYER_LIST, Compat.id("compact_tab_toggle"), (graphics, delta) -> renderToggled(graphics));
    }

    /** Whether the compact tab list replaces the vanilla one (read by Sky2MCompactTabMixin on every frame Tab is held). */
    public static boolean enabled() {
        return TabListExtras.compact();
    }

    /** Called instead of the vanilla tab list while Tab is held. */
    public static void renderHeld(GuiGraphicsExtractor graphics) {
        QolConfigs.TabListExtras c = config();
        if (c == null || c.toggleTab) return;
        draw(graphics, c);
    }

    private static void renderToggled(GuiGraphicsExtractor graphics) {
        QolConfigs.TabListExtras c = config();
        if (c == null || !c.toggleTab || !enabled()) {
            toggled = false;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        boolean down = mc.options.keyPlayerList.isDown();
        if (down && !pressed) {
            pressed = true;
            toggled = !toggled;
        } else if (!down) {
            pressed = false;
        }
        if (toggled) draw(graphics, c);
    }

    // ------------------------------------------------------------------------------------------------ reading

    private static void rebuildIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastBuild < 250L) return;
        lastBuild = now;
        Minecraft mc = Minecraft.getInstance();
        List<Component> tab = readTab(mc);
        if (tab == null) {
            renderColumns = List.of();
            return;
        }
        PlayerTabOverlay overlay = mc.gui.hud.getTabList();
        List<TabColumn> columns = new ArrayList<>(parseColumns(tab));
        TabColumn footer = parseFooterAsColumn(split(((Sky2MPlayerTabOverlayAccessor) overlay).sky2m$getFooter()));
        if (footer != null) columns.add(footer);
        parseSections(columns);
        renderColumns = combineColumnsToRender(columns);
    }

    /** The tab list's lines in the vanilla order, as SkyHanni's TabListData reads them. */
    private static List<Component> readTab(Minecraft mc) {
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return null;
        List<PlayerInfo> players = new ArrayList<>(connection.getListedOnlinePlayers());
        players.sort(Sky2MPlayerTabOverlayAccessor.getOrdering());
        PlayerTabOverlay overlay = mc.gui.hud.getTabList();
        List<Component> result = new ArrayList<>(players.size());
        for (PlayerInfo info : players) result.add(overlay.getNameForDisplay(info));
        return result.size() < 80 ? result.subList(0, Math.max(0, result.size() - 1)) : result.subList(0, 80);
    }

    private static List<TabColumn> parseColumns(List<Component> tab) {
        List<TabColumn> columns = new ArrayList<>();
        for (int entry = 0; entry < tab.size(); entry += 20) {
            String title = SbcItems.legacy(tab.get(entry)).trim();
            TabColumn column = null;
            for (TabColumn existing : columns) {
                if (existing.title.equals(title)) column = existing;
            }
            if (column == null) {
                column = new TabColumn(title);
                columns.add(column);
            }
            for (int i = entry + 1; i < Math.min(tab.size(), entry + 20); i++) column.lines.add(tab.get(i));
        }
        return columns;
    }

    private static TabColumn parseFooterAsColumn(List<Component> lines) {
        if (lines.isEmpty()) return null;
        inUpgrades = false;
        String godPotTimer = null;
        int effectCount = 0;
        for (Component line : lines) {
            Matcher m = GOD_POT.matcher(line.getString());
            if (godPotTimer == null && m.matches()) godPotTimer = m.group("timer");
        }
        for (Component line : lines) {
            Matcher m = EFFECT_COUNT.matcher(line.getString());
            if (m.matches()) {
                effectCount = Integer.parseInt(m.group("effectCount"));
                break;
            }
        }
        TabColumn column = new TabColumn("§2§lOther");
        for (int i = 0; i < lines.size(); i++) {
            matchFooterLine(column, lines.get(i), i > 0 ? lines.get(i - 1) : null, godPotTimer, effectCount);
        }
        while (!column.lines.isEmpty() && column.lines.getLast().getString().trim().isEmpty()) column.lines.removeLast();
        return column.lines.isEmpty() ? null : column;
    }

    private static boolean matches(Pattern pattern, Component component) {
        return component != null && pattern.matcher(component.getString()).matches();
    }

    private static void matchFooterLine(TabColumn column, Component component, Component previous, String godPotTimer, int effectCount) {
        String text = component.getString();
        boolean lastIsDungeons = matches(DUNGEON_BUFF, previous);
        boolean lastIsWinterPowerUps = matches(WINTER_POWER_UPS, previous);
        List<Component> out = column.lines;

        if (text.contains(HYPIXEL_ADVERT)) return;

        // These lines are folded into the Active Effects line.
        if (godPotTimer != null && matches(GOD_POT, component)) return;
        if (matches(EFFECT_COUNT, component)) return;
        if (matches(EFFECTS_USE_COMMAND, component)) return;
        if (matches(ACTIVE_EFFECTS, component)) {
            if (godPotTimer != null) {
                out.add(SbcItems.parseLegacy("§a§lActive Effects:"));
                out.add(SbcItems.parseLegacy(" §cGod Potion§r: " + godPotTimer));
            } else {
                out.add(SbcItems.parseLegacy("§a§lActive Effects: §e" + effectCount));
            }
            return;
        }
        // Already said by the 0 count.
        if (matches(NO_ACTIVE_EFFECTS, component)) return;

        if (matches(COOKIE, component)) {
            out.add(component);
            return;
        }
        if (matches(NO_COOKIE, component)) {
            if (!matches(NO_COOKIE, previous)) out.add(SbcItems.parseLegacy("§7 Not Active"));
            return;
        }

        if (matches(DUNGEON_BUFF, component)) {
            out.add(component);
            return;
        }
        if (lastIsDungeons && text.startsWith("No Buffs active.")) {
            out.add(SbcItems.parseLegacy("§7 None Found"));
            return;
        }

        if (matches(WINTER_POWER_UPS, component)) {
            out.add(component);
            return;
        }
        if (lastIsWinterPowerUps && text.startsWith("No Power Ups active.")) {
            out.add(SbcItems.parseLegacy("§7 None"));
            return;
        }

        if (matches(UPGRADES_HEADER, component)) inUpgrades = true;
        Matcher upgrade = UPGRADES.matcher(text);
        if (inUpgrades && upgrade.matches() && SbcItems.legacy(component).startsWith("§e")) {
            // An upgrade and its time left, on two lines.
            Component first = slice(component, upgrade.start("firstPart"), upgrade.end("firstPart"));
            Component second = slice(component, upgrade.start("secondPart"), upgrade.end("secondPart"));
            boolean bold = component.visit((style, s) -> s.isEmpty() ? Optional.empty() : Optional.of(style.isBold()), Style.EMPTY).orElse(false);
            out.add(!first.getString().startsWith(" ") && !bold ? Component.literal(" ").append(first) : first);
            out.add(second);
            return;
        }

        String formatted = SbcItems.legacy(component);
        if (text.trim().isEmpty()) {
            // Separators are truly emptied.
            out.add(Component.empty());
            inUpgrades = false;
        } else if (!formatted.contains("§l")) {
            out.add(Component.literal(" ").append(component));
        } else {
            out.add(component);
        }
    }

    private static void parseSections(List<TabColumn> columns) {
        for (TabColumn column : columns) {
            List<Component> section = null;
            for (Component line : column.lines) {
                if (line.getString().isBlank()) {
                    section = null;
                    continue;
                }
                if (section == null) {
                    section = new ArrayList<>();
                    column.sections.add(section);
                }
                section.add(line);
            }
        }
    }

    private static List<RenderColumn> combineColumnsToRender(List<TabColumn> columns) {
        List<RenderColumn> out = new ArrayList<>();
        RenderColumn[] current = {new RenderColumn()};
        out.add(current[0]);
        String lastTitle = null;

        for (TabColumn column : columns) {
            for (List<Component> section : column.sections) {
                boolean needsTitle = !column.title.equals(lastTitle);

                // A new column when this one is full, otherwise a blank line between sections.
                if (current[0].size() >= MAX_LINES) {
                    current[0] = new RenderColumn();
                    out.add(current[0]);
                } else if (current[0].size() > 0) {
                    current[0].lines.add(new TabLine(Component.empty(), Type.TEXT));
                }

                if (needsTitle) {
                    lastTitle = column.title;
                    current[0].lines.add(new TabLine(column.titleComponent, Type.TITLE));
                }

                for (Component line : section) {
                    if (current[0].size() >= MAX_LINES) {
                        current[0] = new RenderColumn();
                        out.add(current[0]);
                    }
                    current[0].lines.add(new TabLine(line, typeOf(line)));
                }
            }
        }
        return out;
    }

    private static Type typeOf(Component component) {
        String line = component.getString();
        if (line.startsWith(" ")) return Type.TEXT;
        return USERNAME.matcher(line).find() ? Type.PLAYER : Type.SUB_TITLE;
    }

    /** A component split into its lines, keeping the styles; empty lines are dropped (as SkyHanni's TextHelper.split). */
    private static List<Component> split(Component component) {
        List<Component> out = new ArrayList<>();
        if (component == null) return out;
        MutableComponent[] current = {Component.empty()};
        component.visit((style, string) -> {
            if (string.isEmpty()) return Optional.empty();
            String[] parts = string.split("\n", -1);
            current[0].append(Component.literal(parts[0]).withStyle(style));
            for (int i = 1; i < parts.length; i++) {
                if (!current[0].getString().isEmpty()) out.add(current[0]);
                current[0] = Component.empty();
                current[0].append(Component.literal(parts[i]).withStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (!current[0].getString().isEmpty()) out.add(current[0]);
        return out;
    }

    /** The characters {@code start} to {@code end} of a component's text, keeping their styles. */
    private static Component slice(Component component, int start, int end) {
        MutableComponent out = Component.empty();
        int[] index = {0};
        component.visit((style, string) -> {
            int from = Math.max(start, index[0]), to = Math.min(end, index[0] + string.length());
            if (from < to) out.append(Component.literal(string.substring(from - index[0], to - index[0])).withStyle(style));
            index[0] += string.length();
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void draw(GuiGraphicsExtractor graphics, QolConfigs.TabListExtras c) {
        rebuildIfDue();
        List<RenderColumn> columns = renderColumns;
        if (columns.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        int maxLines = 0;
        int totalWidth = -COLUMN_SPACING;
        for (RenderColumn column : columns) {
            maxLines = Math.max(maxLines, column.size());
            totalWidth += column.maxWidth(font) + COLUMN_SPACING;
        }
        int totalHeight = maxLines * LINE_HEIGHT;

        // Header and footer: only Hypixel's address and store lines (the rest of the footer is the "Other" column).
        PlayerTabOverlay overlay = mc.gui.hud.getTabList();
        List<Component> header = new ArrayList<>(), footer = new ArrayList<>();
        if (!c.hideAdverts) {
            for (Component line : split(((Sky2MPlayerTabOverlayAccessor) overlay).sky2m$getHeader())) {
                if (line.getString().contains(HYPIXEL_ADVERT)) header.add(line);
            }
            totalHeight += header.size() * LINE_HEIGHT + TAB_PADDING;
            for (Component line : split(((Sky2MPlayerTabOverlayAccessor) overlay).sky2m$getFooter())) {
                if (line.getString().contains(HYPIXEL_ADVERT)) footer.add(line);
            }
            totalHeight += footer.size() * LINE_HEIGHT + TAB_PADDING;
        }

        int screenWidth = graphics.guiWidth() / 2;
        int x = screenWidth - totalWidth / 2;
        int y = 10;

        if (!c.hideTabBackground) {
            graphics.fill(x - COLUMN_SPACING, y - TAB_PADDING, screenWidth + totalWidth / 2 + COLUMN_SPACING, 10 + totalHeight + TAB_PADDING, 0x80000000);
        }

        int headerY = y;
        for (Component line : header) {
            graphics.text(font, line, (int) (x + totalWidth / 2f - font.width(line) / 2f), headerY, -1, true);
            headerY += LINE_HEIGHT;
        }

        drawColumns(graphics, font, c, x, headerY, columns);

        if (!footer.isEmpty()) {
            int footerY = y + totalHeight - footer.size() * LINE_HEIGHT + TAB_PADDING / 2 + 1;
            for (Component line : footer) {
                graphics.text(font, line, (int) (x + totalWidth / 2f - font.width(line) / 2f), footerY, -1, true);
                footerY += LINE_HEIGHT;
            }
        }
    }

    private static void drawColumns(GuiGraphicsExtractor graphics, Font font, QolConfigs.TabListExtras c, int x, int headerY, List<RenderColumn> columns) {
        int middleX = x;
        TabLine lastSubTitle = null;
        for (RenderColumn original : columns) {
            int middleY = c.hideAdverts ? headerY : headerY + TAB_PADDING + 2;

            List<TabLine> kept = new ArrayList<>();
            for (TabLine line : original.lines) {
                if (line.type() == Type.TITLE) lastSubTitle = null;
                if (line.type() == Type.SUB_TITLE) lastSubTitle = line;
                // Hide Fire Sales: everything under the "Fire Sales: (n)" sub-title, the title included.
                if (c.hideFiresales && lastSubTitle != null && FIRE_SALE_TITLE.matcher(lastSubTitle.component().getString()).matches()) continue;
                kept.add(line);
            }
            RenderColumn column = new RenderColumn(kept);
            int width = column.maxWidth(font);

            graphics.fill(middleX - TAB_PADDING + 1, middleY - TAB_PADDING + 1, middleX + width + TAB_PADDING - 2,
                middleY + column.size() * LINE_HEIGHT + TAB_PADDING - 2, c.hideTabBackground ? 0x8F262626 : 0x20AAAAAA);

            for (TabLine line : column.lines) {
                int savedX = middleX;
                if (line.type() == Type.PLAYER && !c.hidePlayerIcons) {
                    PlayerInfo info = line.info();
                    if (info != null) PlayerFaceExtractor.extractRenderState(graphics, info.getSkin(), middleX, middleY, 8);
                    middleX += 8 + 2;
                }
                float drawX = middleX + (line.type() == Type.TITLE ? width / 2f - line.width(font) / 2f : 0f);
                graphics.text(font, line.component(), (int) drawX, middleY, -1, true);
                middleY += LINE_HEIGHT;
                middleX = savedX;
            }
            middleX += width + COLUMN_SPACING;
        }
    }
}
