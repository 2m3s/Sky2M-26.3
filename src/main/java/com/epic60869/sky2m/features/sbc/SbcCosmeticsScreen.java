package com.epic60869.sky2m.features.sbc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** /s2 cosmetics: pick the badge shown before your name and the cape you wear, from the ones you've unlocked. */
public final class SbcCosmeticsScreen extends Screen {
    private record Option(String id, String name, String symbol, int colour, String url, String description, boolean owned) {}

    private static final int PANEL_W = 170;
    private static final int ROW = 14;

    private final List<Option> badges = new ArrayList<>();
    private final List<Option> capes = new ArrayList<>();
    private String shownBadge = "";
    private String cape = "";
    private String tier = "";
    private int tierColour = 0xFFFFFF;
    private Option previewCape;

    public SbcCosmeticsScreen() {
        super(Component.literal("Sky2M Cosmetics"));
        refresh();
    }

    /** Re-reads your cosmetics (after the server sent them or confirmed a change). */
    void refresh() {
        badges.clear();
        capes.clear();
        JsonObject p = SbcCosmetics.profile;
        if (p == null) return;
        JsonObject catalog = Sbc.obj(p, "catalog");
        read(Sbc.arr(p, "badges"), Sbc.arr(catalog, "badges"), badges);
        read(Sbc.arr(p, "capes"), Sbc.arr(catalog, "capes"), capes);
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
            if (o == null) o = new JsonObject();
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
            Sbc.str(o, "url"), description, owned);
    }

    private int left() {
        return (width - PANEL_W * 2 - 10) / 2;
    }

    private int top() {
        return Math.max(20, height / 2 - 110);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int left = left();
        int top = top();
        g.centeredText(font, "Sky2M Cosmetics", width / 2, top - 14, 0xFFFFD35A);
        if (SbcCosmetics.profile == null) {
            g.centeredText(font, SbcNet.online() ? "Loading..." : "S2C offline", width / 2, top + 40, 0xFFAAAAAA);
            return;
        }
        g.centeredText(font, tier.isEmpty() ? "No supporter tier" : "Supporter tier: " + tier, width / 2, top, 0xFF000000 | (tier.isEmpty() ? 0x777777 : tierColour));

        int y0 = top + 16;
        drawPanel(g, "Badge", badges, shownBadge, left, y0, mouseX, mouseY, false);
        drawPanel(g, "Cape", capes, cape, left + PANEL_W + 10, y0, mouseX, mouseY, true);

        // Cape preview: the front of the cape, big.
        Option shown = previewCape;
        if (shown == null) for (Option o : capes) if (o.id().equals(cape)) shown = o;
        if (shown != null && !shown.url().isBlank()) {
            SbcCosmetics.Cape texture = SbcCosmetics.cape(shown.url());
            int x = left + PANEL_W * 2 + 20;
            if (x + 44 > width) x = width - 48;
            if (texture != null) {
                float scale = texture.width() / 64f;
                g.blit(RenderPipelines.GUI_TEXTURED, texture.texture(), x, y0 + 14, Math.round(1 * scale), Math.round(1 * scale), 40, 64,
                    Math.round(10 * scale), Math.round(16 * scale), texture.width(), texture.height());
            } else {
                g.text(font, "...", x + 16, y0 + 40, 0xFFAAAAAA, false);
            }
        }
        g.centeredText(font, "Click to choose. Locked ones say how to get them.", width / 2, y0 + 16 + ROW * Math.max(6, Math.max(badges.size(), capes.size()) + 1) + 8, 0xFF777777);
    }

    private void drawPanel(GuiGraphicsExtractor g, String title, List<Option> options, String selected, int x, int y,
                           int mouseX, int mouseY, boolean capePanel) {
        int rows = Math.max(6, Math.max(badges.size(), capes.size()) + 1);
        g.fill(x, y, x + PANEL_W, y + 16 + rows * ROW, 0xC0121722);
        g.outline(x, y, PANEL_W, 16 + rows * ROW, 0xFF3B465B);
        g.text(font, title, x + 6, y + 4, 0xFFFFD35A, true);
        if (capePanel) previewCape = null;
        for (int i = 0; i <= options.size(); i++) {
            Option o = i == 0 ? null : options.get(i - 1);
            int ry = y + 16 + i * ROW;
            boolean hover = mouseX >= x && mouseX < x + PANEL_W && mouseY >= ry && mouseY < ry + ROW;
            boolean isSelected = o == null ? selected.isEmpty() : o.id().equals(selected);
            if (hover) g.fill(x + 1, ry, x + PANEL_W - 1, ry + ROW, 0x30FFFFFF);
            if (isSelected) g.fill(x + 1, ry, x + 3, ry + ROW, 0xFF55FF55);
            if (o == null) {
                g.text(font, "None", x + 8, ry + 3, 0xFFCCCCCC, false);
                continue;
            }
            int colour = o.owned() ? 0xFF000000 | o.colour() : 0xFF555555;
            String label = (o.symbol().isBlank() ? "" : o.symbol() + " ") + o.name() + (o.owned() ? "" : " (locked)");
            g.text(font, label, x + 8, ry + 3, colour, false);
            if (hover) {
                if (capePanel) previewCape = o;
                if (!o.description().isBlank()) g.setTooltipForNextFrame(font, Component.literal(o.description()), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (SbcCosmetics.profile != null) {
            int y0 = top() + 16;
            if (click(badges, left(), y0, event.x(), event.y(), true)) return true;
            if (click(capes, left() + PANEL_W + 10, y0, event.x(), event.y(), false)) return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean click(List<Option> options, int x, int y, double mouseX, double mouseY, boolean badge) {
        if (mouseX < x || mouseX >= x + PANEL_W) return false;
        int index = (int) Math.floor((mouseY - y - 16) / ROW);
        if (index < 0 || index > options.size()) return false;
        Option o = index == 0 ? null : options.get(index - 1);
        if (o != null && !o.owned()) return true;
        String id = o == null ? "" : o.id();
        if (badge) shownBadge = id;
        else cape = id;
        SbcCosmetics.select(shownBadge, cape);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
