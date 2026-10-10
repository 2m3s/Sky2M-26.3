package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.Sky2MRecipeCommand;
import com.epic60869.sky2m.custom.RepoItems;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Bazaar and Auction House search, like Skyblocker's search overlay: when you click Search and Hypixel opens its sign,
 * a search box opens instead, suggesting item names as you type (only bazaar items in the Bazaar) and your last
 * searches. Picking one writes it on the sign and sends it, as if you'd typed it there.
 */
public final class SearchOverlay extends Screen {
    private static final Path HISTORY = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("search-history.json");
    private static final int MAX_HISTORY = 5, MAX_SUGGESTIONS = 8, ROW = 18, WIDTH = 220;

    public enum Mode { BAZAAR, AUCTION }

    private static String lastContainerTitle = "";
    private static long lastContainerAt;

    private final Mode mode;
    private final Consumer<String> submit;
    private EditBox box;
    private List<String[]> rows = List.of(); // {name, id or "", "history"}
    private int selected;
    private boolean done;

    private SearchOverlay(Mode mode, Consumer<String> submit) {
        super(Component.literal(mode == Mode.BAZAAR ? "Search the Bazaar" : "Search the Auction House"));
        this.mode = mode;
        this.submit = submit;
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                lastContainerTitle = ChatFormatting.stripFormatting(screen.getTitle().getString());
                lastContainerAt = System.currentTimeMillis();
            }
        });
    }

    /** Hypixel's search sign: "^^^^^^^^^^^^^^^" under the line you type on, and "Enter query" (or similar) below. */
    private static Mode searchSign(String[] lines) {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || !c.misc.searchOverlay || lines.length < 3) return null;
        String rest = (lines[2] + " " + (lines.length > 3 ? lines[3] : "")).toLowerCase(Locale.ROOT);
        if (!lines[1].contains("^^^") || !(rest.contains("query") || rest.contains("search"))) return null;
        // The menu Search was clicked in, a moment ago.
        if (System.currentTimeMillis() - lastContainerAt > 3000) return null;
        if (lastContainerTitle.contains("Bazaar")) return Mode.BAZAAR;
        if (lastContainerTitle.contains("Auction")) return Mode.AUCTION;
        return null;
    }

    /** From the sign screen's init: opens the search box over a Bazaar or Auction House search sign. */
    public static boolean open(String[] lines, Consumer<String> submit) {
        Mode mode = searchSign(lines);
        if (mode == null) return false;
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new SearchOverlay(mode, submit)));
        return true;
    }

    @Override
    protected void init() {
        int x = (width - WIDTH) / 2;
        box = new EditBox(font, x + 6, height / 4 + 22, WIDTH - 12, 18, Component.literal("Search"));
        box.setMaxLength(60);
        box.setResponder(text -> refresh());
        addRenderableWidget(box);
        setInitialFocus(box);
        refresh();
    }

    private void refresh() {
        String query = box == null ? "" : box.getValue().trim().toLowerCase(Locale.ROOT);
        List<String[]> out = new ArrayList<>();
        if (query.isEmpty()) {
            for (String h : history(mode)) out.add(new String[]{h, "", "history"});
        } else {
            List<String[]> starts = new ArrayList<>(), contains = new ArrayList<>();
            for (String[] item : Sky2MRecipeCommand.items()) {
                if (mode == Mode.BAZAAR && Sky2MPriceTooltip.bazaarBuyPrice(item[0]) == null && Sky2MPriceTooltip.bazaarSellPrice(item[0]) == null) continue;
                String name = item[1].toLowerCase(Locale.ROOT);
                if (name.startsWith(query)) starts.add(new String[]{item[1], item[0], ""});
                else if (name.contains(query)) contains.add(new String[]{item[1], item[0], ""});
                if (starts.size() >= MAX_SUGGESTIONS) break;
            }
            out.addAll(starts);
            for (String[] c : contains) if (out.size() < MAX_SUGGESTIONS) out.add(c);
        }
        rows = out.size() > MAX_SUGGESTIONS ? out.subList(0, MAX_SUGGESTIONS) : out;
        selected = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = (width - WIDTH) / 2, top = height / 4;
        int h = 48 + Math.max(1, rows.size()) * ROW;
        // Sky2M's panel colours.
        g.fill(x, top, x + WIDTH, top + h, 0xE0121722);
        g.outline(x, top, WIDTH, h, 0xFF3B465B);
        g.text(font, title, x + 6, top + 8, 0xFFFFD35A, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        int y = top + 46;
        if (rows.isEmpty()) {
            g.text(font, box.getValue().isBlank() ? "Type to search. Enter searches what you typed." : "No items match: Enter searches it anyway.",
                x + 8, y + 4, 0xFF777777, false);
        }
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            boolean hover = mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + ROW;
            if (hover) selected = i;
            if (i == selected) g.fill(x + 2, y, x + WIDTH - 2, y + ROW, 0x403B5B8B);
            if (!row[1].isEmpty()) g.item(RepoItems.itemStack(row[1]), x + 6, y + 1);
            g.text(font, row[2].isEmpty() ? row[0] : "⟲ " + row[0], x + 26, y + 5, row[2].isEmpty() ? 0xFFFFFFFF : 0xFFAAAAAA, true);
            y += ROW;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        switch (event.key()) {
            case com.mojang.blaze3d.platform.InputConstants.KEY_DOWN -> {
                if (!rows.isEmpty()) selected = (selected + 1) % rows.size();
                return true;
            }
            case com.mojang.blaze3d.platform.InputConstants.KEY_UP -> {
                if (!rows.isEmpty()) selected = (selected + rows.size() - 1) % rows.size();
                return true;
            }
            case com.mojang.blaze3d.platform.InputConstants.KEY_TAB -> {
                if (!rows.isEmpty()) box.setValue(rows.get(selected)[0]);
                return true;
            }
            case com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER -> {
                // Enter searches the highlighted suggestion; with none, what you typed.
                finish(rows.isEmpty() ? box.getValue() : rows.get(selected)[0]);
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int x = (width - WIDTH) / 2, y = height / 4 + 46;
        if (event.button() == 0 && event.x() >= x && event.x() < x + WIDTH && event.y() >= y && event.y() < y + rows.size() * ROW) {
            finish(rows.get((int) ((event.y() - y) / ROW))[0]);
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Escape closes the sign without searching. */
    @Override
    public void onClose() {
        finish("");
    }

    private void finish(String text) {
        if (done) return;
        done = true;
        String query = text.trim();
        if (!query.isEmpty()) remember(mode, query);
        submit.accept(query);
    }

    // ------------------------------------------------------------------------------------------------ history

    private static List<String> history(Mode mode) {
        try {
            if (!Files.exists(HISTORY)) return List.of();
            JsonObject root = new Gson().fromJson(Files.readString(HISTORY), JsonObject.class);
            List<String> out = new ArrayList<>();
            if (root != null && root.has(mode.name())) root.getAsJsonArray(mode.name()).forEach(e -> out.add(e.getAsString()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void remember(Mode mode, String query) {
        try {
            JsonObject root = Files.exists(HISTORY) ? new Gson().fromJson(Files.readString(HISTORY), JsonObject.class) : null;
            if (root == null) root = new JsonObject();
            List<String> list = new ArrayList<>(history(mode));
            list.removeIf(h -> h.equalsIgnoreCase(query));
            list.addFirst(query);
            while (list.size() > MAX_HISTORY) list.removeLast();
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            list.forEach(array::add);
            root.add(mode.name(), array);
            Files.createDirectories(HISTORY.getParent());
            Files.writeString(HISTORY, root.toString());
        } catch (Exception ignored) {}
    }
}
