package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.dungeons.ChestHistory.Chest;
import com.epic60869.sky2m.features.dungeons.ChestHistory.Drop;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /s2 chesthistory: the reward chests you've opened (ChestHistory), filtered by floor, chest type and where they were
 * opened (end of a run or Croesus), with the totals at the top. The list shows each chest with its best item, or a
 * table per floor or per chest type; hover a chest for its contents and costs. In Run History's colours.
 */
public final class ChestHistoryScreen extends Screen {
    private static final int W = 420, ROW = 20, BG = 0xC0121722, EDGE = 0xFF3B465B, GOLD = 0xFFFFD35A, TEXT = 0xFF9AA5B8,
        BUTTON = 0xFF2A3345, BUTTON_HOVER = 0xFF3B5B8B, GREEN = 0xFF55FF55, RED = 0xFFFF5555;
    private static final String[] FLOORS = {"All", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"};
    private static final String[] TYPES = {"All", "Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock"};
    private static final String[] SOURCES = {"All", "Run", "Croesus"};
    private static final String[] SORTS = {"Newest", "Best profit", "Worst profit"};
    private static final String[] VIEWS = {"Chests", "Per floor", "Per chest"};

    private int floor, type, source, sort, view;
    private int scroll;
    private final List<int[]> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();

    /** Recomputed when a filter changes, not every frame. */
    private List<Chest> shown = List.of();
    private Totals total = new Totals();
    private Map<String, Totals> byFloor = Map.of(), byType = Map.of();
    private final Map<Chest, ItemStack> icons = new HashMap<>();
    private final Map<Chest, List<Component>> tooltips = new HashMap<>();

    private static final class Totals {
        int opened;
        double spent, value, profit;

        void add(Chest chest) {
            opened++;
            spent += chest.cost;
            value += chest.value;
            profit += chest.profit;
        }
    }

    public ChestHistoryScreen() {
        super(Component.literal("Chest History"));
        refresh();
    }

    private void refresh() {
        List<Chest> out = new ArrayList<>();
        Totals t = new Totals();
        Map<String, Totals> floors = new LinkedHashMap<>(), types = new LinkedHashMap<>();
        for (String f : FLOORS) if (!f.equals("All")) floors.put(f, new Totals());
        for (String c : TYPES) if (!c.equals("All")) types.put(c, new Totals());
        for (Chest chest : ChestHistory.chests()) {
            if (floor != 0 && !FLOORS[floor].equals(chest.floor)) continue;
            if (type != 0 && !TYPES[type].equals(chest.type)) continue;
            if (source != 0 && !SOURCES[source].equals(chest.source)) continue;
            out.add(chest);
            t.add(chest);
            floors.computeIfAbsent(chest.floor == null ? "?" : chest.floor, k -> new Totals()).add(chest);
            types.computeIfAbsent(chest.type == null ? "?" : chest.type, k -> new Totals()).add(chest);
        }
        floors.values().removeIf(x -> x.opened == 0);
        types.values().removeIf(x -> x.opened == 0);
        if (sort == 1) out.sort(Comparator.comparingDouble((Chest c) -> c.profit).reversed());
        else if (sort == 2) out.sort(Comparator.comparingDouble((Chest c) -> c.profit));
        shown = out;
        total = t;
        byFloor = floors;
        byType = types;
        scroll = 0;
    }

    private ItemStack icon(Chest chest) {
        return icons.computeIfAbsent(chest, c -> {
            Drop top = c.items.stream().max(Comparator.comparingDouble(Drop::value)).orElse(null);
            // Items the repo doesn't know (essences, some books) would be a barrier: show a chest instead.
            if (top == null) return ItemStack.EMPTY;
            return RepoItems.displayName(top.id()) == null ? new ItemStack(net.minecraft.world.item.Items.CHEST) : RepoItems.itemStack(top.id());
        });
    }

    private List<Component> tooltip(Chest chest) {
        return tooltips.computeIfAbsent(chest, c -> {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal((c.floor == null ? "?" : c.floor) + " " + c.type + " Chest").withStyle(ChatFormatting.GOLD));
            lines.add(Component.literal(c.date() + " (" + ("Croesus".equals(c.source) ? "Croesus" : "end of run") + ")").withStyle(ChatFormatting.DARK_GRAY));
            List<Drop> drops = new ArrayList<>(c.items);
            drops.sort(Comparator.comparingDouble(Drop::value).reversed());
            for (Drop d : drops) {
                String name = d.name() + (d.count() > 1 && !d.name().endsWith("x" + d.count()) ? " x" + d.count() : "");
                lines.add(Component.literal(name + " ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(CombatFeatures.formatCoins(d.value())).withStyle(ChatFormatting.GOLD)));
            }
            lines.add(Component.literal("Coins: " + CombatFeatures.formatCoins(c.coins)).withStyle(ChatFormatting.GRAY));
            if (c.key) lines.add(Component.literal("Dungeon Chest Key: " + CombatFeatures.formatCoins(c.keyCost)).withStyle(ChatFormatting.GRAY));
            if (c.kismet) lines.add(Component.literal("Kismet Feather: " + CombatFeatures.formatCoins(c.kismetCost)).withStyle(ChatFormatting.GRAY));
            lines.add(Component.literal("Value " + CombatFeatures.formatCoins(c.value) + " - Cost " + CombatFeatures.formatCoins(c.cost) + " = ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(signed(c.profit)).withStyle(c.profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED)));
            return lines;
        });
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return 30;
    }

    private int bottom() {
        return height - 20;
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, boolean selected, int mouseX, int mouseY, Runnable action) {
        boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, selected ? BUTTON_HOVER : over ? 0xFF33405A : BUTTON);
        g.outline(x, y, w, h, EDGE);
        g.centeredText(font, label, x + w / 2, y + (h - 8) / 2, selected ? GOLD : 0xFFFFFFFF);
        buttons.add(new int[]{x, y, w, h});
        actions.add(action);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        buttons.clear();
        actions.clear();
        int left = left(), top = top(), bottom = bottom();
        g.centeredText(font, "Chest Spending History", width / 2, 12, GOLD);
        g.fill(left, top, left + W, bottom, BG);
        g.outline(left, top, W, bottom - top, EDGE);

        // Filters: each button cycles through its options.
        int bw = (W - 8) / 5;
        button(g, left + 4, top + 4, bw - 2, 14, "Floor: " + FLOORS[floor], floor != 0, mouseX, mouseY, () -> {
            floor = (floor + 1) % FLOORS.length;
            refresh();
        });
        button(g, left + 4 + bw, top + 4, bw - 2, 14, "Chest: " + TYPES[type], type != 0, mouseX, mouseY, () -> {
            type = (type + 1) % TYPES.length;
            refresh();
        });
        button(g, left + 4 + bw * 2, top + 4, bw - 2, 14, "From: " + SOURCES[source], source != 0, mouseX, mouseY, () -> {
            source = (source + 1) % SOURCES.length;
            refresh();
        });
        button(g, left + 4 + bw * 3, top + 4, bw - 2, 14, SORTS[sort], sort != 0, mouseX, mouseY, () -> {
            sort = (sort + 1) % SORTS.length;
            refresh();
        });
        button(g, left + 4 + bw * 4, top + 4, bw - 2, 14, VIEWS[view], view != 0, mouseX, mouseY, () -> {
            view = (view + 1) % VIEWS.length;
            scroll = 0;
        });

        int y = top + 24;
        if (shown.isEmpty()) {
            boolean on = ChestHistory.enabled();
            g.centeredText(font, ChestHistory.chests().isEmpty()
                    ? on ? "No reward chests recorded yet. Open one at the end of a run or at Croesus!" : "Turn on Chest Spending History (Dungeons > Chest Profit)."
                    : "No chests match these filters.",
                width / 2, y + 30, TEXT);
            return;
        }
        // Totals for the filter.
        int x = left + 6;
        x = stat(g, x, y, "Opened ", String.valueOf(total.opened), 0xFFFFFFFF);
        x = stat(g, x, y, "Spent ", CombatFeatures.formatCoins(total.spent), RED);
        x = stat(g, x, y, "Value ", CombatFeatures.formatCoins(total.value), GOLD);
        x = stat(g, x, y, "Profit ", signed(total.profit), total.profit >= 0 ? GREEN : RED);
        double average = total.profit / total.opened;
        stat(g, x, y, "Avg ", signed(average), average >= 0 ? GREEN : RED);
        y += 14;

        if (view != 0) {
            table(g, left, y, view == 1 ? byFloor : byType, view == 1 ? "Floor" : "Chest");
            return;
        }
        int[] cols = {left + 26, left + 86, left + 116, left + 176, left + 246, left + 316};
        String[] heads = {"When", "Floor", "Chest", "Cost", "Value", "Profit"};
        for (int i = 0; i < heads.length; i++) g.text(font, heads[i], cols[i], y, GOLD, true);
        y += 12;
        int visible = Math.max(1, (bottom - y - 4) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, shown.size() - visible));
        Chest hovered = null;
        long now = System.currentTimeMillis();
        for (int i = scroll; i < shown.size() && i - scroll < visible; i++) {
            Chest chest = shown.get(i);
            int ry = y + (i - scroll) * ROW;
            boolean over = mouseX >= left + 2 && mouseX < left + W - 2 && mouseY >= ry && mouseY < ry + ROW;
            if (over) {
                g.fill(left + 1, ry, left + W - 1, ry + ROW, 0x20FFFFFF);
                hovered = chest;
            }
            ItemStack icon = icon(chest);
            if (!icon.isEmpty()) g.item(icon, left + 6, ry + 2);
            int ty = ry + 6;
            g.text(font, ago(now - chest.time), cols[0], ty, TEXT, true);
            g.text(font, chest.floor == null ? "?" : chest.floor, cols[1], ty, chest.floor != null && chest.floor.startsWith("M") ? RED : GREEN, true);
            g.text(font, chest.type + (chest.key ? " §bK" : "") + (chest.kismet ? " §dR" : ""), cols[2], ty, 0xFFFFFFFF, true);
            g.text(font, CombatFeatures.formatCoins(chest.cost), cols[3], ty, RED, true);
            g.text(font, CombatFeatures.formatCoins(chest.value), cols[4], ty, GOLD, true);
            g.text(font, signed(chest.profit), cols[5], ty, chest.profit >= 0 ? GREEN : RED, true);
        }
        if (shown.size() > visible) {
            int trackH = visible * ROW, thumb = Math.max(10, trackH * visible / shown.size());
            int thumbY = y + (trackH - thumb) * scroll / Math.max(1, shown.size() - visible);
            g.fill(left + W - 4, y, left + W - 2, y + trackH, 0xFF0C0F16);
            g.fill(left + W - 4, thumbY, left + W - 2, thumbY + thumb, EDGE);
        }
        if (hovered != null) g.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
    }

    /** One row per floor or chest type: opened, spent, value, profit and average profit. */
    private void table(GuiGraphicsExtractor g, int left, int y, Map<String, Totals> rows, String label) {
        int[] cols = {left + 6, left + 76, left + 136, left + 206, left + 276, left + 346};
        String[] heads = {label, "Opened", "Spent", "Value", "Profit", "Avg"};
        for (int i = 0; i < heads.length; i++) g.text(font, heads[i], cols[i], y, GOLD, true);
        y += 12;
        List<Map.Entry<String, Totals>> entries = new ArrayList<>(rows.entrySet());
        int visible = Math.max(1, (bottom() - y - 4) / 12);
        scroll = Math.clamp(scroll, 0, Math.max(0, entries.size() - visible));
        for (int i = scroll; i < entries.size() && i - scroll < visible; i++) {
            Totals t = entries.get(i).getValue();
            double avg = t.profit / t.opened;
            g.text(font, entries.get(i).getKey(), cols[0], y, 0xFFFFFFFF, true);
            g.text(font, String.valueOf(t.opened), cols[1], y, 0xFFFFFFFF, true);
            g.text(font, CombatFeatures.formatCoins(t.spent), cols[2], y, RED, true);
            g.text(font, CombatFeatures.formatCoins(t.value), cols[3], y, GOLD, true);
            g.text(font, signed(t.profit), cols[4], y, t.profit >= 0 ? GREEN : RED, true);
            g.text(font, signed(avg), cols[5], y, avg >= 0 ? GREEN : RED, true);
            y += 12;
        }
    }

    private int stat(GuiGraphicsExtractor g, int x, int y, String label, String value, int colour) {
        g.text(font, label, x, y, TEXT, true);
        x += font.width(label);
        g.text(font, value, x, y, colour, true);
        return x + font.width(value) + 12;
    }

    static String signed(double value) {
        return (value < 0 ? "-" : "+") + CombatFeatures.formatCoins(Math.abs(value));
    }

    /** "5m ago", "3h ago", "2d ago". */
    private static String ago(long ms) {
        long minutes = Math.max(0, ms / 60_000);
        if (minutes < 60) return minutes + "m ago";
        if (minutes < 60 * 24) return minutes / 60 + "h ago";
        return minutes / (60 * 24) + "d ago";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        for (int i = 0; i < buttons.size(); i++) {
            int[] b = buttons.get(i);
            if (event.x() >= b[0] && event.x() < b[0] + b[2] && event.y() >= b[1] && event.y() < b[1] + b[3]) {
                actions.get(i).run();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
