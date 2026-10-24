package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * Bazaar and Auction House search, ported from Skyblocker's search overlay (SearchOverManager and OverlayScreen,
 * LGPL-3.0, https://github.com/SkyblockerMod/Skyblocker): when you click Search and Hypixel opens its "Enter query"
 * sign, a search box opens instead. It suggests what's sold there as you type (bazaar products and enchantments in
 * the Bazaar; auction items and pets in the Auction House), lists your last searches, and in the Auction House can
 * add a max pet level or dungeon stars to the search. /ahs and /bzs open it from anywhere on SkyBlock.
 */
public final class SearchOverlay extends Screen {
    private static final Path HISTORY = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("search-history.json");
    private static final int MAX_HISTORY = 5, MAX_SUGGESTIONS = 8, ROW = 18, WIDTH = 240;
    private static final String[] STAR_NUMBERS = {"➊", "➋", "➌", "➍", "➎"};

    public enum Mode { BAZAAR, AUCTION }

    // Pets aren't in Hypixel's item list or the price feed, so their names come from NEU's repo (like Skyblocker).
    private static volatile Map<String, String> petNames = Map.of(); // lower-case name -> name
    private static volatile Set<String> level200Pets = Set.of();      // lower-case names
    private static boolean petsRequested;
    // Suggestion lists, rebuilt when the item list or prices change size.
    private static List<Entry> bazaarEntries = List.of(), auctionEntries = List.of();
    private static int bazaarKey = -1, auctionKey = -1;

    // Remembered between searches, like Skyblocker.
    private static boolean maxPetLevel;
    private static int dungeonStars;

    /** A suggestion: its name, and the item id for its icon ("" for none, "#book" for an enchanted book). */
    private record Entry(String name, String id, boolean pet) {}

    private final Mode mode;
    private final String initial;
    private final Consumer<String> submit;
    private EditBox box;
    private List<Entry> suggestions = List.of();
    private List<String> history = List.of();
    private int selected = -1; // -1: Enter searches what you typed
    private boolean done;

    private SearchOverlay(Mode mode, String initial, Consumer<String> submit) {
        super(Component.literal(mode == Mode.BAZAAR ? "Search the Bazaar" : "Search the Auction House"));
        this.mode = mode;
        this.initial = initial;
        this.submit = submit;
    }

    public static void register() {
        // Skyblocker has the same commands; let it keep them.
        if (FabricLoader.getInstance().isModLoaded("skyblocker")) return;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> {
            for (String root : new String[]{"ahs", "ahsearch", "bzs"}) {
                Mode mode = root.equals("bzs") ? Mode.BAZAAR : Mode.AUCTION;
                dispatcher.register(literal(root)
                    .executes(_ -> command(root, mode, ""))
                    .then(argument("item", StringArgumentType.greedyString())
                        .executes(context -> command(root, mode, StringArgumentType.getString(context, "item")))));
            }
        });
    }

    /**
     * /ahs, /ahsearch and /bzs: with an item ("/ahs grand") it searches for it straight away; on its own, the search
     * box (or Hypixel's own command when the feature is off).
     */
    private static int command(String root, Mode mode, String item) {
        Minecraft mc = Minecraft.getInstance();
        Sky2MConfig c = Sky2MConfig.current();
        if (!item.isBlank()) {
            // Straight to the server: sendCommand would run this client command again, forever.
            String query = item.trim();
            if (mc.getConnection() != null) mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(
                (mode == Mode.BAZAAR ? "bz " : "auctionsearch ") + query));
            return 1;
        }
        if (c == null || !c.inventory.searchOverlay) {
            // Straight to the server: sendCommand would run this client command again, forever.
            if (mc.getConnection() != null) mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundChatCommandPacket(item.isEmpty() ? root : root + " " + item));
            return 1;
        }
        mc.execute(() -> mc.gui.setScreen(new SearchOverlay(mode, item, query -> {
            mc.gui.setScreen(null);
            if (query.isEmpty() || mc.getConnection() == null) return;
            mc.getConnection().sendCommand((mode == Mode.BAZAAR ? "bz " : "auctionsearch ") + query);
        })));
        return 1;
    }

    /**
     * From {@code LocalPlayer.openTextEdit}: opens the search box over Hypixel's search sign ("Enter query" on the
     * last line) when the Bazaar or Auction House is the open menu. {@code submit} gets the two lines to write.
     */
    public static boolean open(String[] lines, Screen current, Consumer<String[]> submit) {
        Sky2MConfig c = Sky2MConfig.current();
        if (c == null || !c.inventory.searchOverlay || current == null || lines.length < 4) return false;
        if (!lines[3].trim().equalsIgnoreCase("enter query")) return false;
        String title = ChatFormatting.stripFormatting(current.getTitle().getString());
        Mode mode = title.contains("Bazaar") ? Mode.BAZAAR : title.contains("Auction") ? Mode.AUCTION : null;
        if (mode == null) return false;
        // Hypixel puts your last search back on the sign, over its first two lines.
        String previous = (lines[0].trim() + " " + lines[1].trim()).trim();
        Minecraft.getInstance().gui.setScreen(new SearchOverlay(mode, previous, query -> {
            // Search doesn't work if the sign starts with "null" (Null Ovoid, ...), so it's quoted.
            if (query.toLowerCase(Locale.ROOT).startsWith("null")) query = "\"" + query + "\"";
            submit.accept(splitLines(query));
        }));
        return true;
    }

    /** The search on the sign's two 15-character lines, split between words. */
    static String[] splitLines(String s) {
        if (s.length() <= 15) return new String[]{s, ""};
        int space = s.lastIndexOf(' ', 15);
        if (space == -1) return new String[]{s.substring(0, 15), s.substring(15, Math.min(30, s.length()))};
        return new String[]{s.substring(0, space), s.substring(space + 1, Math.min(space + 16, s.length())).trim()};
    }

    // ------------------------------------------------------------------------------------------------ screen

    @Override
    protected void init() {
        loadPets();
        box = new EditBox(font, left() + 6, boxY(), WIDTH - 12, 16, Component.literal("Search"));
        box.setMaxLength(30);
        box.setValue(initial);
        // Selected, so typing replaces the last search and Enter searches it again.
        box.setCursorPosition(initial.length());
        box.setHighlightPos(0);
        box.setResponder(_ -> refresh());
        addRenderableWidget(box);
        setInitialFocus(box);
        history = history(mode);
        refresh();
    }

    private int left() {
        return (width - WIDTH) / 2;
    }

    private int top() {
        return height / 5;
    }

    private int boxY() {
        return top() + (mode == Mode.AUCTION ? 44 : 22);
    }

    private int suggestionsY() {
        return boxY() + 22;
    }

    private int historyY() {
        return suggestionsY() + Math.max(1, suggestions.size()) * ROW + 14;
    }

    private void refresh() {
        String query = box.getValue().trim().toLowerCase(Locale.ROOT);
        selected = -1;
        if (query.isEmpty()) {
            suggestions = List.of();
            return;
        }
        List<Entry> starts = new ArrayList<>(), contains = new ArrayList<>(), pets = new ArrayList<>();
        for (Entry entry : entries(mode)) {
            String name = entry.name().toLowerCase(Locale.ROOT);
            if (!name.contains(query)) continue;
            // Like Skyblocker, pets go first once most of their name is typed, so they don't crowd out items.
            if (entry.pet() && (double) query.length() / name.length() > 0.5) pets.add(entry);
            else if (name.startsWith(query)) starts.add(entry);
            else contains.add(entry);
        }
        List<Entry> out = new ArrayList<>(pets);
        out.addAll(starts);
        out.addAll(contains);
        suggestions = out.size() > MAX_SUGGESTIONS ? List.copyOf(out.subList(0, MAX_SUGGESTIONS)) : out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = left(), top = top();
        int bottom = historyY() + (history.isEmpty() ? 0 : 4 + history.size() * ROW) + 4;
        // Sky2M's panel colours.
        g.fill(x, top, x + WIDTH, bottom, 0xE0121722);
        g.outline(x, top, WIDTH, bottom - top, 0xFF3B465B);
        g.text(font, title, x + 6, top + 7, 0xFFFFD35A, true);

        if (mode == Mode.AUCTION) {
            int y = top + 20, half = (WIDTH - 18) / 2;
            boolean petHover = inside(mouseX, mouseY, x + 6, y, half, 18);
            g.fill(x + 6, y, x + 6 + half, y + 18, petHover ? 0xFF2A3446 : 0xFF1C2330);
            g.outline(x + 6, y, half, 18, 0xFF3B465B);
            String pet = "Max Pet Level " + (maxPetLevel ? "✔" : "✘");
            g.text(font, pet, x + 6 + (half - font.width(pet)) / 2, y + 5, maxPetLevel ? 0xFF55FF55 : 0xFFFF5555, true);
            int sx = x + 12 + half;
            g.fill(sx, y, sx + half, y + 18, inside(mouseX, mouseY, sx, y, half, 18) ? 0xFF2A3446 : 0xFF1C2330);
            g.outline(sx, y, half, 18, 0xFF3B465B);
            int starsWidth = font.width("✪") * 10, starX = sx + (half - starsWidth) / 2;
            for (int i = 0; i < 10; i++) {
                int colour = i < dungeonStars ? (i < 5 ? 0xFFFFAA00 : 0xFFFF5555) : 0xFF555555;
                g.text(font, "✪", starX + i * font.width("✪"), y + 5, colour, true);
            }
            if (petHover) g.setTooltipForNextFrame(font, Component.literal("Pets you search for are max level (100, or 200 for the dragons that go that far). Off: only pets are found."), mouseX, mouseY);
            if (inside(mouseX, mouseY, sx, y, half, 18)) g.setTooltipForNextFrame(font, Component.literal("Dungeon items you search for have this many stars. Click the same star again to clear."), mouseX, mouseY);
        }

        super.extractRenderState(g, mouseX, mouseY, delta);

        int y = suggestionsY();
        if (suggestions.isEmpty()) {
            String hint = box.getValue().isBlank() ? "Type to search." : "Nothing matches: Enter searches it anyway.";
            g.text(font, hint, x + 8, y + 5, 0xFF777777, false);
        }
        ItemStack tooltip = null;
        for (int i = 0; i < suggestions.size(); i++, y += ROW) {
            Entry entry = suggestions.get(i);
            boolean hover = inside(mouseX, mouseY, x, y, WIDTH, ROW);
            if (hover || i == selected) g.fill(x + 2, y, x + WIDTH - 2, y + ROW, 0x403B5B8B);
            ItemStack icon = icon(entry);
            if (!icon.isEmpty()) {
                g.item(icon, x + 6, y + 1);
                if (hover) tooltip = icon;
            }
            g.text(font, entry.name(), x + 26, y + 5, 0xFFFFFFFF, true);
        }

        if (!history.isEmpty()) {
            y = historyY();
            g.text(font, "Last searches", x + 8, y - 10, 0xFFAAAAAA, false);
            for (int i = 0; i < history.size(); i++, y += ROW) {
                String name = history.get(i);
                boolean hover = inside(mouseX, mouseY, x, y, WIDTH - 20, ROW);
                if (hover || selected == suggestions.size() + i) g.fill(x + 2, y, x + WIDTH - 22, y + ROW, 0x403B5B8B);
                String id = RepoItems.idByName(name);
                if (id != null) {
                    ItemStack icon = RepoItems.itemStack(id);
                    g.item(icon, x + 6, y + 1);
                    if (hover) tooltip = icon;
                }
                g.text(font, "⟲ " + name, x + 26, y + 5, 0xFFAAAAAA, true);
                boolean deleteHover = inside(mouseX, mouseY, x + WIDTH - 20, y, 18, ROW);
                g.text(font, "✕", x + WIDTH - 14, y + 5, deleteHover ? 0xFFFF5555 : 0xFF777777, true);
                if (deleteHover) g.setTooltipForNextFrame(font, Component.literal("Remove from your last searches"), mouseX, mouseY);
            }
        }
        if (tooltip != null) g.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static ItemStack icon(Entry entry) {
        if (entry.id().isEmpty()) return ItemStack.EMPTY;
        if (entry.id().equals("#book")) return new ItemStack(Items.ENCHANTED_BOOK);
        return RepoItems.itemStack(entry.id());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int count = suggestions.size() + history.size();
        switch (event.key()) {
            case InputConstants.KEY_DOWN -> {
                if (count > 0) selected = (selected + 1) % count;
                return true;
            }
            case InputConstants.KEY_UP -> {
                if (count > 0) selected = selected <= 0 ? count - 1 : selected - 1;
                return true;
            }
            case InputConstants.KEY_TAB -> {
                String pick = picked(Math.max(selected, 0));
                if (pick != null) box.setValue(pick);
                return true;
            }
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> {
                // Enter searches what you typed, or the row picked with the arrow keys.
                String pick = selected >= 0 ? picked(selected) : null;
                finish(pick != null ? pick : box.getValue());
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    /** The suggestion or last search at a row index (suggestions first), or null. */
    private String picked(int index) {
        if (index < suggestions.size()) return suggestions.get(index).name();
        index -= suggestions.size();
        return index < history.size() ? history.get(index) : null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            int x = left();
            double mx = event.x(), my = event.y();
            if (mode == Mode.AUCTION) {
                int y = top() + 20, half = (WIDTH - 18) / 2, sx = x + 12 + half;
                if (inside(mx, my, x + 6, y, half, 18)) {
                    maxPetLevel = !maxPetLevel;
                    return true;
                }
                if (inside(mx, my, sx, y, half, 18)) {
                    int starsWidth = font.width("✪") * 10, starX = sx + (half - starsWidth) / 2;
                    int stars = Math.clamp((int) ((mx - starX) / font.width("✪")) + 1, 0, 10);
                    dungeonStars = stars == dungeonStars ? 0 : stars;
                    return true;
                }
            }
            int y = suggestionsY();
            if (inside(mx, my, x, y, WIDTH, suggestions.size() * ROW)) {
                finish(suggestions.get((int) ((my - y) / ROW)).name());
                return true;
            }
            y = historyY();
            if (inside(mx, my, x, y, WIDTH, history.size() * ROW)) {
                int index = (int) ((my - y) / ROW);
                if (mx >= x + WIDTH - 20) {
                    forget(mode, history.get(index));
                    history = history(mode);
                    selected = -1;
                } else {
                    finish(history.get(index));
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
        if (!query.isEmpty()) {
            remember(mode, query);
            if (mode == Mode.AUCTION) query = addExtras(query);
        }
        submit.accept(query);
    }

    /** Skyblocker's extras: a pet's max level (or "] " so only pets are found) and a dungeon item's stars. */
    private static String addExtras(String search) {
        String lower = search.toLowerCase(Locale.ROOT);
        if (petNames.containsKey(lower)) {
            search = maxPetLevel ? "[Lvl " + (level200Pets.contains(lower) ? 200 : 100) + "] " + search : "] " + search;
        }
        if (dungeonStars > 0) {
            String id = RepoItems.idByName(search);
            if (id != null && RepoItems.isStarable(id)) {
                search += " " + "✪".repeat(Math.min(dungeonStars, 5)) + (dungeonStars > 5 ? STAR_NUMBERS[dungeonStars - 6] : "");
            }
        }
        return search;
    }

    // ------------------------------------------------------------------------------------------------ items

    private static synchronized List<Entry> entries(Mode mode) {
        Set<String> ids = mode == Mode.BAZAAR ? Sky2MPriceTooltip.bazaarIds() : Sky2MPriceTooltip.auctionIds();
        int key = ids.size() * 31 + RepoItems.allIds().size() * 7 + petNames.size();
        if (mode == Mode.BAZAAR && key == bazaarKey) return bazaarEntries;
        if (mode == Mode.AUCTION && key == auctionKey) return auctionEntries;

        Map<String, Entry> byName = new HashMap<>();
        for (String id : ids) {
            if (mode == Mode.BAZAAR && id.startsWith("ENCHANTMENT_")) {
                String name = enchantName(id);
                if (name != null) byName.putIfAbsent(name.toLowerCase(Locale.ROOT), new Entry(name, "#book", false));
                continue;
            }
            String name = RepoItems.displayName(id);
            if (name == null) name = RepoItems.displayName(id.replace('-', ':'));
            if (name == null) continue;
            name = ChatFormatting.stripFormatting(name).trim();
            if (!name.isEmpty()) byName.putIfAbsent(name.toLowerCase(Locale.ROOT), new Entry(name, id, false));
        }
        if (mode == Mode.AUCTION) {
            for (Map.Entry<String, String> pet : petNames.entrySet()) byName.put(pet.getKey(), new Entry(pet.getValue(), "", true));
        }
        List<Entry> list = new ArrayList<>(byName.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        if (mode == Mode.BAZAAR) {
            bazaarEntries = list;
            bazaarKey = key;
        } else {
            auctionEntries = list;
            auctionKey = key;
        }
        return list;
    }

    /** "ENCHANTMENT_ULTIMATE_WISE_5" is "Ultimate Wise V". */
    private static String enchantName(String id) {
        String rest = id.substring("ENCHANTMENT_".length());
        int last = rest.lastIndexOf('_');
        if (last <= 0) return null;
        int level;
        try {
            level = Integer.parseInt(rest.substring(last + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return titleCase(rest.substring(0, last)) + " " + roman(level);
    }

    private static String titleCase(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    /** Pet names and the pets that go to level 200, from NEU's constants/pets.json (loaded once). */
    private static void loadPets() {
        if (petsRequested) return;
        petsRequested = true;
        RepoItems.runAsync(() -> {
            try {
                JsonObject root = JsonParser.parseString(RepoItems.neuRepoFile("constants/pets.json")).getAsJsonObject();
                JsonObject overrides = root.has("id_to_display_name") ? root.getAsJsonObject("id_to_display_name") : new JsonObject();
                Map<String, String> names = new HashMap<>();
                Map<String, String> idToName = new HashMap<>();
                for (String id : root.getAsJsonObject("pet_types").keySet()) {
                    String name = overrides.has(id) ? overrides.get(id).getAsString() : titleCase(id);
                    names.put(name.toLowerCase(Locale.ROOT), name);
                    idToName.put(id, name.toLowerCase(Locale.ROOT));
                }
                Set<String> level200 = new HashSet<>();
                if (root.has("custom_pet_leveling")) {
                    for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("custom_pet_leveling").entrySet()) {
                        JsonObject leveling = e.getValue().getAsJsonObject();
                        if (leveling.has("max_level") && leveling.get("max_level").getAsInt() >= 200 && idToName.containsKey(e.getKey())) {
                            level200.add(idToName.get(e.getKey()));
                        }
                    }
                }
                petNames = names;
                level200Pets = level200;
            } catch (Exception e) {
                petsRequested = false;
            }
        });
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
        List<String> list = new ArrayList<>(history(mode));
        list.removeIf(h -> h.equalsIgnoreCase(query));
        list.addFirst(query);
        while (list.size() > MAX_HISTORY) list.removeLast();
        saveHistory(mode, list);
    }

    private static void forget(Mode mode, String query) {
        List<String> list = new ArrayList<>(history(mode));
        list.removeIf(h -> h.equalsIgnoreCase(query));
        saveHistory(mode, list);
    }

    private static void saveHistory(Mode mode, List<String> list) {
        try {
            JsonObject root = Files.exists(HISTORY) ? new Gson().fromJson(Files.readString(HISTORY), JsonObject.class) : null;
            if (root == null) root = new JsonObject();
            JsonArray array = new JsonArray();
            list.forEach(array::add);
            root.add(mode.name(), array);
            Files.createDirectories(HISTORY.getParent());
            Files.writeString(HISTORY, root.toString());
        } catch (Exception ignored) {}
    }
}
