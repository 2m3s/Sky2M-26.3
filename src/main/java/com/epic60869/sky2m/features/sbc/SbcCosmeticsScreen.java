package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.gui.Sky2MGuiStyle;
import com.epic60869.sky2m.features.misc.playasmob.PlayAsMobs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 cosmetics, in the settings' look: an overview with your supporter tier and what you wear, and an Emblem, a Cape
 * and a Model button, each opening a page that lists everything (yours first, then the locked ones with how to get them).
 * Choices go to the Sky2M server, which checks you own them (cosmetics.js).
 */
public final class SbcCosmeticsScreen extends Screen {
    private enum Page { HOME, EMBLEM, CAPE, MODEL }

    /** {@code image}: an uploaded emblem's picture URL (drawn instead of {@code symbol}). */
    private record Option(String id, String name, String symbol, int colour, String url, String description, boolean owned,
                          String image, boolean header) {
        static Option header(String text) {
            return new Option(null, text, "", 0, "", "", false, "", true);
        }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final int ROW = 14;

    private final List<Option> badges = new ArrayList<>();
    private final List<Option> capes = new ArrayList<>();
    private final List<Option> models = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private String shownBadge = "";
    private String cape = "";
    private String look = "";
    private String tier = "";
    private int tierColour = 0xFFFFFF;
    private Page page = Page.HOME;
    private int scroll;
    private EditBox search;

    public SbcCosmeticsScreen() {
        super(Component.literal("Sky2M Cosmetics"));
        refresh();
    }

    /** Opens straight on one page (/s2 playasmob opens the Model page). */
    public SbcCosmeticsScreen(String startPage) {
        this();
        if ("model".equals(startPage)) page = Page.MODEL;
    }

    /** Re-reads your cosmetics (after the server sent them or confirmed a change). */
    void refresh() {
        badges.clear();
        capes.clear();
        models.clear();
        JsonObject p = SbcCosmetics.profile;
        // A mob picked here is kept in your settings (mobs are free and work without the server); otherwise the server's.
        String localMob = localMob();
        look = !localMob.isEmpty() ? localMob : p == null ? "" : idOf(p.get("look"));
        if (p == null) return;
        JsonObject catalog = Sbc.obj(p, "catalog");
        read(Sbc.arr(p, "badges"), Sbc.arr(catalog, "badges"), badges);
        read(Sbc.arr(p, "capes"), Sbc.arr(catalog, "capes"), capes);
        read(Sbc.arr(p, "models"), Sbc.arr(catalog, "models"), models);
        shownBadge = idOf(p.get("shownBadge"));
        cape = idOf(p.get("cape"));
        SbcCosmetics.Symbol t = SbcCosmetics.symbol(Sbc.obj(p, "tier"));
        tier = t == null ? "" : (t.symbol() + " " + t.name()).trim();
        tierColour = t == null ? 0xFFFFFF : t.colour();
    }

    private static String idOf(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonObject()) return Sbc.str(e.getAsJsonObject(), "id");
        return e.getAsString();
    }

    /** Owned entries first (ids or objects, completed from the catalog), then the catalog's locked ones. */
    private static void read(Iterable<JsonElement> owned, Iterable<JsonElement> catalog, List<Option> out) {
        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : catalog) if (e.isJsonObject()) all.add(e.getAsJsonObject());
        List<String> ownedIds = new ArrayList<>();
        for (JsonElement e : owned) {
            JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : find(all, e.getAsString());
            String id = e.isJsonObject() ? Sbc.str(o, "id") : e.getAsString();
            JsonObject full = find(all, id);
            if (o == null) o = full == null ? new JsonObject() : full;
            if (full != null && Sbc.str(o, "description").isBlank()) o.addProperty("description", Sbc.str(full, "description"));
            ownedIds.add(id);
            out.add(option(id, o, true));
        }
        for (JsonObject o : all) {
            String id = Sbc.str(o, "id");
            if (!ownedIds.contains(id)) out.add(option(id, o, false));
        }
    }

    private static JsonObject find(List<JsonObject> all, String id) {
        for (JsonObject o : all) if (Sbc.str(o, "id").equals(id)) return o;
        return null;
    }

    private static Option option(String id, JsonObject o, boolean owned) {
        String name = Sbc.str(o, "name");
        String description = Sbc.str(o, "description");
        if (description.isBlank()) description = Sbc.str(o, "unlock");
        return new Option(id, name.isBlank() ? id : name, Sbc.str(o, "symbol"), Sbc.hex(Sbc.str(o, "color"), 0xFFFFFF),
            Sbc.str(o, "url"), description, owned, Sbc.str(o, "image"), false);
    }

    // ------------------------------------------------------------------------------------------------ layout

    private int panelWidth() {
        return Math.min(width - 20, 460);
    }

    private int panelHeight() {
        return Math.min(height - 20, 280);
    }

    private int left() {
        return (width - panelWidth()) / 2;
    }

    private int top() {
        return (height - panelHeight()) / 2;
    }

    private int listTop() {
        return top() + (page == Page.MODEL ? 74 : 54);
    }

    private int listBottom() {
        return top() + panelHeight() - 20;
    }

    /** Room right of the list for the cape preview. */
    private int listRight() {
        return left() + panelWidth() - (page == Page.CAPE ? 70 : 8);
    }

    @Override
    protected void init() {
        String old = search == null ? "" : search.getValue();
        search = new EditBox(font, 0, 0, 80, 10, Component.literal("Search"));
        search.setHint(Component.literal("Search models and mobs").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(40);
        search.setValue(old);
        search.setResponder(text -> scroll = 0);
        Sky2MGuiStyle.placeSearch(search, left() + 12, top() + 52, Math.min(200, panelWidth() - 24 - Sky2MGuiStyle.SEARCH_ICON));
        addRenderableWidget(search);
        showPage();
    }

    private void showPage() {
        search.visible = page == Page.MODEL;
        if (!search.visible) search.setFocused(false);
    }

    private void open(Page next) {
        page = next;
        scroll = 0;
        showPage();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelWidth(), panelHeight(), true);
        String title = switch (page) {
            case HOME -> "Cosmetics";
            case EMBLEM -> "Cosmetics  ·  Emblem";
            case CAPE -> "Cosmetics  ·  Cape";
            case MODEL -> "Cosmetics  ·  Model";
        };
        Sky2MGuiStyle.header(g, font, left() + 4, top() + 5, panelWidth() - 9, Sky2MGuiStyle.title(title));

        if (SbcCosmetics.profile == null && page != Page.MODEL) {
            // Emblems, capes and models need the server; mobs don't.
            String text = SbcNet.online() ? "Loading..." : "S2C offline: emblems, capes and models need the Sky2M server.";
            g.text(font, text, width / 2 - font.width(text) / 2, top() + panelHeight() / 2 - 14, Sky2MGuiStyle.DESCRIPTION, false);
            int w = Sky2MGuiStyle.buttonWidth(font, "Choose a Mob");
            button(g, "Choose a Mob", width / 2 - w / 2, top() + panelHeight() / 2 + 2, mouseX, mouseY, () -> open(Page.MODEL));
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }
        if (page != Page.HOME) button(g, "Back", left() + 12, top() + 30, mouseX, mouseY, () -> open(Page.HOME));
        switch (page) {
            case HOME -> drawHome(g, mouseX, mouseY);
            case EMBLEM -> drawList(g, mouseX, mouseY, badges, shownBadge, "None", "Emblems go before your name in chat, the tab list and name tags.");
            case CAPE -> drawCapes(g, mouseX, mouseY);
            case MODEL -> drawModels(g, mouseX, mouseY);
        }
        if (page == Page.MODEL) Sky2MGuiStyle.search(g, search);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawHome(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = left() + 12, w = panelWidth() - 24;
        String tierLine = tier.isEmpty() ? "No supporter tier" : "Supporter tier: " + tier;
        g.text(font, tierLine, x + (w - font.width(tierLine)) / 2, top() + 32, 0xFF000000 | (tier.isEmpty() ? 0x777777 : tierColour), false);

        int gap = 8, cardW = (w - gap * 2) / 3, cardTop = top() + 48, cardBottom = listBottom();
        Option emblem = selected(badges, shownBadge), capeOption = selected(capes, cape);
        card(g, mouseX, mouseY, x, cardTop, cardW, cardBottom, "Emblem", emblem == null ? Component.literal("None") : label(emblem),
            count(badges), Page.EMBLEM);
        card(g, mouseX, mouseY, x + cardW + gap, cardTop, cardW, cardBottom, "Cape",
            Component.literal(capeOption == null ? "None" : capeOption.name()), count(capes), Page.CAPE);
        card(g, mouseX, mouseY, x + (cardW + gap) * 2, cardTop, cardW, cardBottom, "Model", Component.literal(lookName(look)),
            count(models) + (models.isEmpty() ? "" : "") + " · every mob", Page.MODEL);

        // The cape you wear, big, inside its card.
        if (capeOption != null) drawCape(g, capeOption, x + cardW + gap + cardW / 2 - 20, cardTop + 44);
        String hint = "Click a card to change it. Emblems and capes are earned or given by Sky2M staff.";
        g.text(font, font.plainSubstrByWidth(hint, w), x, top() + panelHeight() - 14, Sky2MGuiStyle.MUTED, false);
    }

    private void card(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int w, int bottom, String title,
                      Component current, String sub, Page target) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < bottom;
        Sky2MGuiStyle.inset(g, x, y, x + w, bottom);
        if (hover) g.fill(x + 1, y + 1, x + w - 1, bottom - 1, 0x18FFFFFF);
        Sky2MGuiStyle.heading(g, font, title, x, y + 6, w);
        Component fit = font.width(current) > w - 8 ? Component.literal(font.plainSubstrByWidth(current.getString(), w - 8)) : current;
        g.text(font, fit, x + (w - font.width(fit)) / 2, y + 20, Sky2MGuiStyle.TEXT, false);
        String s = font.plainSubstrByWidth(sub, w - 8);
        g.text(font, s, x + (w - font.width(s)) / 2, y + 31, Sky2MGuiStyle.TEXT_DIM, false);
        int bw = Math.min(w - 16, Sky2MGuiStyle.buttonWidth(font, "Change " + title));
        Sky2MGuiStyle.button(g, font, "Change " + title, x + (w - bw) / 2, bottom - 22, bw, 16, hover);
        hits.add(new Hit(x, y, w, bottom - y, () -> open(target)));
    }

    private static String count(List<Option> options) {
        int owned = 0;
        for (Option o : options) if (o.owned()) owned++;
        return owned + " of " + options.size() + " unlocked";
    }

    private static Option selected(List<Option> options, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Option o : options) if (id.equals(o.id())) return o;
        return null;
    }

    private MutableComponent label(Option o) {
        MutableComponent label = Component.empty();
        if (!o.image().isBlank()) label.append(SbcEmblems.component(o.image(), Style.EMPTY)).append(" ");
        else if (!o.symbol().isBlank()) label.append(Component.literal(o.symbol() + " ").withStyle(Style.EMPTY.withColor(o.colour())));
        label.append(Component.literal(o.name()).withStyle(Style.EMPTY.withColor(o.owned() ? o.colour() : 0x555555)));
        return label;
    }

    /** "Player", a mob's name, or a model's name, for the stored look ("", a mob id, or "model:<id>"). */
    private String lookName(String value) {
        if (value == null || value.isEmpty()) return "Player";
        if (value.startsWith(com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.PREFIX)) {
            return com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.displayName(value);
        }
        if (value.startsWith("model:")) {
            Option o = selected(models, value.substring(6));
            return o == null ? value.substring(6) : o.name();
        }
        return PlayAsMobs.displayName(value);
    }

    /** A page's list: "None" (or "Player"), then the options; owned ones are picked by clicking. */
    private void drawList(GuiGraphicsExtractor g, int mouseX, int mouseY, List<Option> options, String current, String none, String help) {
        String fit = font.plainSubstrByWidth(help, panelWidth() - 80);
        g.text(font, fit, left() + 70, top() + 34, Sky2MGuiStyle.TEXT_DIM, false);
        List<Option> rows = new ArrayList<>();
        rows.add(new Option("", none, "", 0xFFFFFF, "", "", true, "", false));
        rows.addAll(options);
        drawRows(g, mouseX, mouseY, rows, current, id -> {
            if (page == Page.EMBLEM) shownBadge = id;
            else cape = id;
            SbcCosmetics.select(shownBadge, cape);
        });
    }

    private Option hoveredCape;

    private void drawCapes(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        hoveredCape = null;
        drawList(g, mouseX, mouseY, capes, cape, "None", "Capes show on your back to every Sky2M player.");
        Option shown = hoveredCape != null ? hoveredCape : selected(capes, cape);
        if (shown != null) drawCape(g, shown, listRight() + 11, listTop() + 6);
    }

    /** The front of a cape, 40x64. */
    private void drawCape(GuiGraphicsExtractor g, Option o, int x, int y) {
        if (o.url().isBlank()) return;
        SbcCosmetics.Cape texture = SbcCosmetics.cape(o.url());
        if (texture == null) {
            g.text(font, "...", x + 16, y + 26, Sky2MGuiStyle.MUTED, false);
            return;
        }
        float scale = texture.width() / 64f;
        g.blit(RenderPipelines.GUI_TEXTURED, texture.texture(), x, y, Math.round(1 * scale), Math.round(1 * scale), 40, 64,
            Math.round(10 * scale), Math.round(16 * scale), texture.width(), texture.height());
    }

    private void drawModels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String help = "Only Sky2M players see it. Settings: Misc > Visual > Play as Mob.";
        g.text(font, font.plainSubstrByWidth(help, panelWidth() - 80), left() + 70, top() + 34, Sky2MGuiStyle.TEXT_DIM, false);
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Option> rows = new ArrayList<>();
        if (q.isEmpty()) rows.add(new Option("", "Player", "", 0xFFFFFF, "", "Your normal look", true, "", false));
        List<Option> modelRows = new ArrayList<>();
        for (Option o : models) {
            if (matches(q, o.name(), o.id())) {
                modelRows.add(new Option("model:" + o.id(), o.name(), "", 0xFFFFFF, "", o.description(), o.owned(), "", false));
            }
        }
        // Models that ship with the mod: free, no download, kept in your settings.
        List<Option> builtinRows = new ArrayList<>();
        for (com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.Entry b : com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.ALL) {
            if (matches(q, b.name(), b.id())) {
                builtinRows.add(new Option(com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.PREFIX + b.id(), b.name(), "", 0xFFFFFF, "", "Built in", true, "", false));
            }
        }
        if (!builtinRows.isEmpty()) {
            rows.add(Option.header("Built-in models"));
            rows.addAll(builtinRows);
        }
        if (!modelRows.isEmpty() || q.isEmpty()) {
            rows.add(Option.header("Models" + (models.isEmpty() ? ": none yet. Sky2M staff give these out." : "")));
            rows.addAll(modelRows);
        }
        List<Option> mobRows = new ArrayList<>();
        for (String id : PlayAsMobs.available()) {
            String name = PlayAsMobs.displayName(id);
            if (matches(q, name, id)) mobRows.add(new Option(id, name, "", 0xFFFFFF, "", "", true, "", false));
        }
        if (!mobRows.isEmpty()) {
            rows.add(Option.header("Mobs"));
            rows.addAll(mobRows);
        }
        if (rows.isEmpty()) {
            g.text(font, "Nothing matches \"" + search.getValue().trim() + "\".", left() + 14, listTop() + 4, Sky2MGuiStyle.MUTED, false);
            return;
        }
        drawRows(g, mouseX, mouseY, rows, look, id -> {
            look = id;
            // Mobs are kept in your settings so they show straight away, server or not; a model or Player clears it.
            com.epic60869.sky2m.config.Sky2MConfig config = com.epic60869.sky2m.config.Sky2MConfig.current();
            if (config != null) {
                config.misc.playAsMob.mob = id.isEmpty() || id.startsWith("model:") ? "" : id;
                com.epic60869.sky2m.config.Sky2MConfig.saveCurrent(config);
            }
            // Sent too, so other Sky2M players see it (a built-in model only exists on your own client).
            if (SbcNet.online()) SbcCosmetics.selectLook(id.startsWith(com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.PREFIX) ? "" : id);
        });
    }

    private static String localMob() {
        com.epic60869.sky2m.config.Sky2MConfig config = com.epic60869.sky2m.config.Sky2MConfig.current();
        String mob = config == null ? null : config.misc.playAsMob.mob;
        return PlayAsMobs.sanitize(mob) == null && com.epic60869.sky2m.features.misc.playasmob.BuiltinModels.sanitize(mob) == null ? "" : mob;
    }

    private static boolean matches(String q, String name, String id) {
        return q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q) || id.replace('_', ' ').contains(q);
    }

    private void drawRows(GuiGraphicsExtractor g, int mouseX, int mouseY, List<Option> rows, String current,
                          java.util.function.Consumer<String> pick) {
        int x0 = left() + 8, x1 = listRight(), top = listTop(), bottom = listBottom();
        Sky2MGuiStyle.inset(g, x0, top, x1, bottom);
        int visible = Math.max(1, (bottom - top - 4) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() - visible));
        for (int i = scroll; i < rows.size() && i < scroll + visible; i++) {
            Option o = rows.get(i);
            int ry = top + 2 + (i - scroll) * ROW;
            if (o.header()) {
                g.text(font, font.plainSubstrByWidth(o.name(), x1 - x0 - 12), x0 + 6, ry + 3, Sky2MGuiStyle.HEADING, false);
                continue;
            }
            boolean hover = mouseX >= x0 && mouseX < x1 - 6 && mouseY >= ry && mouseY < ry + ROW;
            boolean picked = o.id().equals(current == null ? "" : current);
            if (hover && o.owned()) g.fill(x0 + 1, ry, x1 - 6, ry + ROW, Sky2MGuiStyle.ROW_HOVER);
            if (picked) g.fill(x0 + 1, ry, x0 + 3, ry + ROW, 0xFF55FF55);
            MutableComponent label = page == Page.EMBLEM && !o.id().isEmpty() ? label(o)
                : Component.literal(o.name()).withStyle(Style.EMPTY.withColor(o.owned() ? (picked ? 0x55FF55 : 0xE0E0E0) : 0x555555));
            g.text(font, label, x0 + 8, ry + 3, 0xFFFFFFFF, false);
            String right = !o.owned() ? "locked" : picked ? "wearing" : "";
            if (!right.isEmpty()) {
                g.text(font, right, x1 - 10 - font.width(right), ry + 3, picked ? 0xFF55FF55 : Sky2MGuiStyle.MUTED, false);
            }
            if (hover) {
                if (page == Page.CAPE) hoveredCape = o.id().isEmpty() ? null : o;
                String tip = o.owned() ? o.description() : (o.description().isBlank() ? "Locked." : "Locked: " + o.description());
                if (!tip.isBlank()) g.setTooltipForNextFrame(font, Component.literal(tip), mouseX, mouseY);
            }
            if (o.owned()) {
                String id = o.id();
                hits.add(new Hit(x0, ry, x1 - 6 - x0, ROW, () -> {
                    if (!id.equals(current)) pick.accept(id);
                }));
            }
        }
        Sky2MGuiStyle.scrollbar(g, x1 - 6, top + 1, bottom - 1, scroll, visible, rows.size());
    }

    private void button(GuiGraphicsExtractor g, String label, int x, int y, int mouseX, int mouseY, Runnable action) {
        int w = Sky2MGuiStyle.buttonWidth(font, label), h = 16;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        Sky2MGuiStyle.button(g, font, label, x, y, w, h, hover);
        hits.add(new Hit(x, y, w, h, action));
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (page == Page.MODEL && Sky2MGuiStyle.inSearch(search, event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hit hit : List.copyOf(hits)) {
                if (hit.contains(event.x(), event.y())) {
                    Sky2MGuiStyle.click();
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll = Math.max(0, scroll - (int) Math.signum(scrollY) * 3);
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE && page != Page.HOME) {
            open(Page.HOME);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
