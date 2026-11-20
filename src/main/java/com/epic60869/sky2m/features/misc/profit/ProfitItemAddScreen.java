package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adding an item to a profit tracker by hand (the tracker's [+ Add Item] over a menu): type part of its name, or a book
 * like "pyroclasm 6", and pick it; the item's editor opens to set how many. Drawn like {@link ProfitItemEditScreen}.
 */
final class ProfitItemAddScreen extends Screen {
    private static final int W = 240, H = 196, ROWS = 7, ROW_H = 16;
    private static final Pattern BOOK = Pattern.compile("^(?:enchanted book\\s*\\(?)?([a-z][a-z' _-]*?)\\s+([0-9]+|[ivx]+)\\)?$");

    private final Screen parent;
    private final String trackerName;
    private final Consumer<String> picked;
    private EditBox box;
    private String lastQuery;
    /** Tracker id -> label, for what's typed. */
    private Map<String, String> results = new LinkedHashMap<>();

    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {}

    private final List<Hit> hits = new ArrayList<>();

    ProfitItemAddScreen(Screen parent, String trackerName, Consumer<String> picked) {
        super(Component.literal("Add Tracked Item"));
        this.parent = parent;
        this.trackerName = trackerName;
        this.picked = picked;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        String typed = box == null ? "" : box.getValue();
        box = new EditBox(font, 0, 0, 80, 10, Component.literal("Item"));
        box.setMaxLength(64);
        box.setValue(typed);
        Sky2MGuiStyle.placeSearch(box, left() + 10, top() + 30, W - 20);
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    /** The tracker's id for a NEU repo id: books, runes and pets are named as they are in your inventory. */
    static String trackerId(String neuId) {
        int semi = neuId.indexOf(';');
        if (semi <= 0 || semi == neuId.length() - 1) return neuId;
        String base = neuId.substring(0, semi), level = neuId.substring(semi + 1);
        if (base.endsWith("_RUNE")) return base + "_" + level;
        if (base.startsWith("ATTRIBUTE_SHARD_")) return base;
        String name = RepoItems.displayName(neuId);
        if (name != null && ChatFormatting.stripFormatting(name).contains("[Lvl")) {
            List<String> tiers = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC");
            try {
                int tier = Integer.parseInt(level);
                if (tier >= 0 && tier < tiers.size()) return "LVL_1_" + tiers.get(tier) + "_" + base;
            } catch (NumberFormatException ignored) {}
            return neuId;
        }
        return "ENCHANTMENT_" + base + "_" + level;
    }

    private static int roman(String s) {
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) { case 'i' -> 1; case 'v' -> 5; case 'x' -> 10; default -> 0; };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    private void search(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) {
            Matcher book = BOOK.matcher(q);
            if (book.matches()) {
                String level = book.group(2).chars().allMatch(Character::isDigit) ? book.group(2) : Integer.toString(roman(book.group(2)));
                String enchant = book.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("[ '-]+", "_");
                if (!level.equals("0")) out.put("ENCHANTMENT_" + enchant + "_" + level, "Enchanted Book (" + titleCase(book.group(1).trim()) + " " + level + ")");
            }
            for (String id : RepoItems.allIds()) {
                if (out.size() >= ROWS) break;
                String name = RepoItems.displayName(id);
                String plain = name == null ? "" : ChatFormatting.stripFormatting(name);
                if (plain == null) continue;
                if (plain.toLowerCase(Locale.ROOT).contains(q) || id.toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)) {
                    // Every book is "Enchanted Book" in the repo: say which.
                    out.putIfAbsent(trackerId(id), plain.equals("Enchanted Book") ? "§fEnchanted Book §7(" + id + ")" : name);
                }
            }
            if (out.isEmpty() && q.matches("[a-z0-9_;:]+")) out.put(q.toUpperCase(Locale.ROOT), q.toUpperCase(Locale.ROOT));
        }
        results = out;
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) if (!w.isEmpty()) b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        if (!box.getValue().equals(lastQuery)) {
            lastQuery = box.getValue();
            search(lastQuery);
        }
        int x = left(), y = top();
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, x, y, W, H, true);
        Sky2MGuiStyle.header(g, font, x + 4, y + 4, W - 8, Sky2MGuiStyle.title("Add to " + trackerName));
        Sky2MGuiStyle.field(g, box);
        hits.clear();
        int ry = y + 48;
        if (results.isEmpty()) {
            g.text(font, box.getValue().isBlank() ? "Type an item's name, or a book like pyroclasm 6." : "No items found.",
                x + 10, ry + 4, Sky2MGuiStyle.DESCRIPTION, false);
        }
        for (Map.Entry<String, String> e : results.entrySet()) {
            String id = e.getKey();
            boolean over = mouseX >= x + 8 && mouseX < x + W - 8 && mouseY >= ry && mouseY < ry + ROW_H;
            if (over) g.fill(x + 8, ry, x + W - 8, ry + ROW_H, 0x30FFFFFF);
            g.item(RepoItems.itemStack(id), x + 10, ry);
            String label = e.getValue() == null ? id : e.getValue();
            g.text(font, com.epic60869.sky2m.features.sbc.SbcItems.parseLegacy(font.plainSubstrByWidth(label, W - 44)), x + 30, ry + 4, Sky2MGuiStyle.TEXT, true);
            hits.add(new Hit(x + 8, ry, x + W - 8, ry + ROW_H, () -> picked.accept(id)));
            ry += ROW_H + 1;
        }
        int by = y + H - 24;
        Hit cancel = new Hit(x + W - 90, by, x + W - 10, by + 16, this::onClose);
        hits.add(cancel);
        Sky2MGuiStyle.button(g, font, Component.translatable("gui.cancel").getString(), x + W - 90, by, 80, 16,
            mouseX >= cancel.x0() && mouseX < cancel.x1() && mouseY >= cancel.y0() && mouseY < cancel.y1());
        g.text(font, "Tip: carry an item and click Add Item.", x + 10, by + 4, 0xFF808080, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) return false;
        if (Sky2MGuiStyle.inField(box, event.x(), event.y())) {
            setFocused(box);
            return true;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (event.x() >= hit.x0() && event.x() < hit.x1() && event.y() >= hit.y0() && event.y() < hit.y1()) {
                Sky2MGuiStyle.click();
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if ((key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) && !results.isEmpty()) {
            picked.accept(results.keySet().iterator().next());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
