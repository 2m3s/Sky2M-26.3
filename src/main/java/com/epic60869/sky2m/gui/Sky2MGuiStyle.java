package com.epic60869.sky2m.gui;

import io.github.notenoughupdates.moulconfig.GuiTextures;
import io.github.notenoughupdates.moulconfig.platform.MoulConfigRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The look of the /s2 settings (MoulConfig's editor) for Sky2M's own screens, so /s2 leaderboard and /s2 collections
 * sit next to the settings without looking like a different mod.
 *
 * <p>Panels, buttons and the search icon are drawn by MoulConfig itself ({@link MoulConfigRenderContext#drawDarkRect}
 * and its {@link GuiTextures}), so a MoulConfig theme or texture change reaches these screens too. The colours MoulConfig
 * only has inline (in {@code MoulConfigEditor.render}, {@code Config.formatCategoryName} and {@code TextFieldComponent})
 * are copied here, named after what they're for there.
 */
public final class Sky2MGuiStyle {
    private Sky2MGuiStyle() {}

    /** The editor's title text (header bar). */
    public static final int TITLE = 0xFFA0A0A0;
    /** The purple "Categories" heading above the category list. */
    public static final int HEADING = 0xFFA368EF;
    /** A category's description, above the options. */
    public static final int DESCRIPTION = 0xFFB0B0B0;
    /** An option's name, and an accordion's title. */
    public static final int TEXT = 0xFFE0E0E0;
    public static final int TEXT_DIM = 0xFFC0C0C0;
    /** Category names: selected is aqua and underlined, the others grey (sub-categories dark grey). */
    public static final int SELECTED = 0xFF55FFFF;
    public static final int UNSELECTED = 0xFFAAAAAA;
    public static final int MUTED = 0xFF555555;
    /** The line beside an open category's sub-categories; used for separators. */
    public static final int LINE = 0xFF444444;
    /** {@code drawDarkRect}'s body and light edge: rows are striped and hovered with these. */
    public static final int ROW = 0xFF202026;
    public static final int ROW_HOVER = 0xFF303036;
    /** MoulConfig's button texture is light, so its label is dark. */
    public static final int BUTTON_TEXT = 0xFFE0E0E0;

    public static MoulConfigRenderContext context(GuiGraphicsExtractor g) {
        return new MoulConfigRenderContext(g);
    }

    /** The dimmed gradient MoulConfig lays over the world behind the settings. */
    public static void backdrop(GuiGraphicsExtractor g, int width, int height) {
        g.fillGradient(0, 0, width, height, 0x80101010, 0x90101010);
    }

    /** MoulConfig's panel: a dark body with a light top-left edge and a dark bottom-right one (and a drop shadow). */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean shadow) {
        context(g).drawDarkRect(x, y, w, h, shadow);
    }

    /** The sunken box the category list and the options sit in. */
    public static void inset(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        g.fill(x0, y0, x0 + 1, y1, 0xFF08080E);
        g.fill(x0 + 1, y0, x1, y0 + 1, 0xFF08080E);
        g.fill(x1 - 1, y0 + 1, x1, y1, 0xFF303036);
        g.fill(x0 + 1, y1 - 1, x1 - 1, y1, 0xFF303036);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0x6008080E);
    }

    /** The settings' header bar with the title in the middle. */
    public static void header(GuiGraphicsExtractor g, Font font, int x, int y, int w, Component title) {
        panel(g, x, y, w, 20, false);
        g.text(font, title, x + (w - font.width(title)) / 2, y + 6, TITLE, false);
    }

    /** "Sky2M <name>" the way the settings title colours "Sky2M Mod". */
    public static Component title(String name) {
        return Component.literal("Sky2M ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(name).withStyle(ChatFormatting.GRAY));
    }

    /** A purple heading centred over {@code w}, like the settings' "Categories". */
    public static void heading(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w) {
        String fit = font.plainSubstrByWidth(text, w);
        g.text(font, fit, x + (w - font.width(fit)) / 2, y, HEADING, false);
    }

    /**
     * A category-style entry (a tab, a filter, a board): aqua and underlined when selected, else grey (white while
     * hovered, which the settings don't do but small text needs). Returns the width drawn.
     */
    public static int category(GuiGraphicsExtractor g, Font font, String text, int x, int y, boolean selected, boolean hover) {
        Component label = selected ? Component.literal(text).withStyle(ChatFormatting.UNDERLINE) : Component.literal(text);
        g.text(font, label, x, y, selected ? SELECTED : hover ? 0xFFFFFFFF : UNSELECTED, false);
        return font.width(text);
    }

    /**
     * Sky2M's button (2026-11-04, replacing MoulConfig's light texture, which looked out of place on the dark panels): a
     * box a shade above the panel with a soft border, a faint highlight along the top and a shadow along the bottom.
     * Hovered, it brightens and its border turns the headings' purple. Labels are light and keep their own colour codes.
     */
    public static void button(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int h, boolean hover) {
        button(g, font, text, x, y, w, h, hover, false, true);
    }

    /** {@link #button}, marked as the chosen option or the screen's main action ({@code chosen}), or greyed out. */
    public static void button(GuiGraphicsExtractor g, Font font, String text, int x, int y, int w, int h, boolean hover,
                              boolean chosen, boolean enabled) {
        buttonBox(g, x, y, w, h, hover && enabled, chosen, enabled);
        String fit = font.plainSubstrByWidth(enabled ? text : ChatFormatting.stripFormatting(text), w - 6);
        int colour = !enabled ? BUTTON_TEXT_DISABLED : chosen ? BUTTON_TEXT_CHOSEN : hover ? BUTTON_TEXT_HOVER : BUTTON_TEXT;
        g.text(font, Component.literal(fit), x + (w - font.width(fit)) / 2, y + (h - 8) / 2, colour, false);
    }

    /** {@link #button}'s box without a label, for a widget that draws its own (coloured or disabled) text on it. */
    public static void buttonFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover) {
        buttonBox(g, x, y, w, h, hover, false, true);
    }

    /**
     * The button's box: purple-tinted with a purple border and a purple bar down the left when {@code chosen}, dimmer
     * with a quiet border when not {@code enabled}.
     */
    public static void buttonBox(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, boolean chosen,
                                 boolean enabled) {
        int fill = !enabled ? BUTTON_FILL_OFF : chosen ? BUTTON_FILL_CHOSEN : hover ? BUTTON_FILL_HOVER : BUTTON_FILL;
        int edge = !enabled ? BUTTON_EDGE_OFF : chosen || hover ? HEADING : BUTTON_EDGE;
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0x1CFFFFFF);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0x50000000);
        g.outline(x, y, w, h, edge);
        if (chosen && enabled) g.fill(x + 1, y + 1, x + 3, y + h - 1, HEADING);
    }

    public static final int BUTTON_FILL = 0xFF2A2A33, BUTTON_FILL_HOVER = 0xFF35353F, BUTTON_FILL_CHOSEN = 0xFF30273F,
        BUTTON_FILL_OFF = 0xFF222228, BUTTON_EDGE = 0xFF44444F, BUTTON_EDGE_OFF = 0xFF303036;
    /** A button's label while hovered, when chosen, and when it can't be pressed. */
    public static final int BUTTON_TEXT_HOVER = 0xFFFFFFFF, BUTTON_TEXT_CHOSEN = 0xFFD0A8FF, BUTTON_TEXT_DISABLED = 0xFF6E6E76;
    /** The width a {@link #button} needs for {@code text}. */
    public static int buttonWidth(Font font, String text) {
        return Math.max(48, font.width(text) + 12);
    }

    /**
     * The settings' search: the magnifier icon and, left of it, a black field with a white border (green while
     * typing). {@code box} is an unbordered {@link EditBox} placed with {@link #placeSearch}; this draws around it.
     */
    public static void search(GuiGraphicsExtractor g, EditBox box) {
        field(g, box);
        int y0 = box.getY() - (SEARCH_HEIGHT - 8) / 2, x1 = box.getX() + box.getWidth() + 4;
        context(g).drawTexturedRect(GuiTextures.SEARCH, x1 + 2, y0 + (SEARCH_HEIGHT - 16) / 2, 16, 16);
    }

    /**
     * The search's field without the icon, for typing anything else (an amount): MoulConfig's text fields look the
     * same. {@code box} is placed with {@link #placeSearch}.
     */
    public static void field(GuiGraphicsExtractor g, EditBox box) {
        int x0 = box.getX() - 4, y0 = box.getY() - (SEARCH_HEIGHT - 8) / 2, x1 = box.getX() + box.getWidth() + 4;
        g.fill(x0, y0, x1, y0 + SEARCH_HEIGHT, box.isFocused() ? 0xFF00FF00 : 0xFFFFFFFF);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + SEARCH_HEIGHT - 1, 0xFF000000);
    }

    /** Whether a click at mx, my lands on a {@link #field} (including the padding the EditBox doesn't cover). */
    public static boolean inField(EditBox box, double mx, double my) {
        int y0 = box.getY() - (SEARCH_HEIGHT - 8) / 2;
        return mx >= box.getX() - 4 && mx < box.getX() + box.getWidth() + 4 && my >= y0 && my < y0 + SEARCH_HEIGHT;
    }

    public static final int SEARCH_HEIGHT = 16;
    /** Room the icon takes right of the field. */
    public static final int SEARCH_ICON = 18;

    /** Puts {@code box} inside a search field whose outer box (without the icon) is at x, y, w wide. */
    public static void placeSearch(EditBox box, int x, int y, int w) {
        box.setBordered(false);
        box.setTextColor(TEXT);
        box.setX(x + 4);
        box.setY(y + (SEARCH_HEIGHT - 8) / 2);
        box.setWidth(Math.max(8, w - 8));
        box.setHeight(10);
    }

    /** Whether a click at mx, my lands on {@code box}'s field (including the padding the EditBox doesn't cover). */
    public static boolean inSearch(EditBox box, double mx, double my) {
        int y0 = box.getY() - (SEARCH_HEIGHT - 8) / 2;
        return mx >= box.getX() - 4 && mx < box.getX() + box.getWidth() + 4 + SEARCH_ICON && my >= y0 && my < y0 + SEARCH_HEIGHT;
    }

    /** The vanilla button click, for the drawn tabs and buttons that replaced vanilla buttons. */
    public static void click() {
        net.minecraft.client.Minecraft.getInstance().getSoundManager().play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    /** The settings' scroll bar: a 5px dark track with a 3px grey thumb. Draws nothing when everything fits. */
    public static void scrollbar(GuiGraphicsExtractor g, int x, int top, int bottom, int scroll, int visible, int total) {
        if (total <= visible || bottom - top < 6) return;
        g.fill(x, top, x + 5, bottom, 0xFF101010);
        int track = bottom - top - 2;
        int thumb = Math.max(8, track * visible / total);
        int thumbY = top + 1 + (track - thumb) * scroll / Math.max(1, total - visible);
        g.fill(x + 1, thumbY, x + 4, thumbY + thumb, 0xFF303030);
    }
}
