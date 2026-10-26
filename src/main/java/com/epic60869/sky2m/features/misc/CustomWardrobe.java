package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.FeatureConfigs.CustomWardrobe.WardrobeTheme;
import com.epic60869.sky2m.mixin.Sky2MInventoryScreenInvoker;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sky2M's Custom Wardrobe: the Wardrobe menu drawn as cards, each with you wearing that armour set, in a theme of your
 * choice. Click a card to equip it, its star to favourite it, a page tab to switch pages. The idea is SkyHanni's
 * CustomWardrobe (hannibal002/SkyHanni, LGPL-2.1: the menu's slot layout and the player models); the layout, page tabs,
 * themes and buttons are Sky2M's own. Drawn in place of the chest by Sky2MCustomWardrobeMixin.
 */
public final class CustomWardrobe {
    private static final Pattern TITLE = Pattern.compile("^(?:Wardrobe \\((\\d+)/(\\d+)\\)|\\((\\d+)/(\\d+)\\) Armor Sets)$");
    private static final Pattern SLOT_NAME = Pattern.compile("^Slot (\\d+): (.+)$");
    private static final int FIRST_BUTTON = 36;
    private static final int PREVIOUS_PAGE = 45, BACK = 48, CLOSE = 49, NEXT_PAGE = 53;
    private static final int GAP = 6, PAD = 8, HEADER = 20, FOOTER = 24, BUTTON_H = 16;

    /** A theme's colours (ARGB). */
    private record Theme(int background, int card, int hover, int border, int equipped, int favourite, int accent, int text) {
        /** Light backgrounds read better without text shadows. */
        boolean shadow() {
            int r = (background >> 16) & 0xFF, g = (background >> 8) & 0xFF, b = background & 0xFF;
            return r + g + b < 3 * 150;
        }
    }

    /** One of the open page's nine slots. Pieces are helmet, chestplate, leggings, boots; null when there is none. */
    private record SlotView(int index, int number, ItemStack[] pieces, boolean equipped, boolean locked, boolean empty) {}

    /** A clickable area drawn this frame. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final List<Hit> HITS = new ArrayList<>();
    private static final RemotePlayer[] MODELS = new RemotePlayer[9];
    /** Lets the client game test show it outside SkyBlock. */
    public static boolean forceForTests;
    private static int targetPage = -1;
    private static long lastPageClick;

    private CustomWardrobe() {}

    private static FeatureConfigs.CustomWardrobe config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.inventory.customWardrobe;
    }

    public static void init() {
        // Page tabs more than one page away step one page at a time, as each page loads.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (targetPage < 0) return;
            if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !isActive(screen)) {
                targetPage = -1;
                return;
            }
            int[] page = page(screen);
            if (page[0] == targetPage) {
                targetPage = -1;
            } else if (System.currentTimeMillis() - lastPageClick > 350) {
                lastPageClick = System.currentTimeMillis();
                click(screen, targetPage < page[0] ? PREVIOUS_PAGE : NEXT_PAGE);
            }
        });
    }

    /** Whether the custom wardrobe replaces this screen. */
    public static boolean isActive(AbstractContainerScreen<?> screen) {
        FeatureConfigs.CustomWardrobe c = config();
        if (c == null || !c.enabled || !(Compat.isOnSkyblock() || forceForTests)) return false;
        return TITLE.matcher(ChatFormatting.stripFormatting(screen.getTitle().getString()).trim()).matches()
            && screen.getMenu().slots.size() > NEXT_PAGE;
    }

    /** {current page, page count}. */
    private static int[] page(AbstractContainerScreen<?> screen) {
        Matcher m = TITLE.matcher(ChatFormatting.stripFormatting(screen.getTitle().getString()).trim());
        if (!m.matches()) return new int[]{1, 1};
        String current = m.group(1) != null ? m.group(1) : m.group(3);
        String total = m.group(2) != null ? m.group(2) : m.group(4);
        return new int[]{Integer.parseInt(current), Integer.parseInt(total)};
    }

    // ------------------------------------------------------------------------------------------------ themes

    private static Theme theme(FeatureConfigs.CustomWardrobe c) {
        return switch (c.theme) {
            case SKY2M -> new Theme(0xE0101218, 0xC8222630, 0xDC343A4A, 0xFF464E60, 0xFF55FF55, 0xFFFFC83C, 0xFFFFAA00, 0xFFE6E6E6);
            case MIDNIGHT -> new Theme(0xE80A0C1E, 0xC8161A3A, 0xDC242A5C, 0xFF3A3F8A, 0xFF7CF5FF, 0xFFE0B0FF, 0xFFB48CFF, 0xFFDCDCFF);
            case FOREST -> new Theme(0xE00C1A10, 0xC8162A1C, 0xDC22402A, 0xFF3C6A44, 0xFFB4FF5A, 0xFFFFE07A, 0xFF8EE07A, 0xFFE4F2E0);
            case SUNSET -> new Theme(0xE0281018, 0xC8401C24, 0xDC5A2830, 0xFFC0603C, 0xFFFFE066, 0xFFFF8AC8, 0xFFFF9A3C, 0xFFFFEAD8);
            case OCEAN -> new Theme(0xE0081822, 0xC80E2A3A, 0xDC144058, 0xFF2A7A96, 0xFF6CFFD2, 0xFFFFE27A, 0xFF4CD2FF, 0xFFDCF4FF);
            case CRIMSON -> new Theme(0xE0180808, 0xC8301010, 0xDC4A1818, 0xFF8A2A2A, 0xFFFFD24C, 0xFFFF9A9A, 0xFFFF5050, 0xFFF2DCDC);
            case CANDY -> new Theme(0xE0FFE4F2, 0xD8FFF4FA, 0xE8FFD6EC, 0xFFF08CC0, 0xFF3CC878, 0xFFFFB400, 0xFFE0509A, 0xFF5A2A46);
            case LIGHT -> new Theme(0xE8EEF0F4, 0xE0FFFFFF, 0xF0E2E6EE, 0xFFB4BCCA, 0xFF22A844, 0xFFE09A00, 0xFF2A64C8, 0xFF2A2E36);
            case GLASS -> new Theme(0x50000000, 0x30FFFFFF, 0x50FFFFFF, 0x80FFFFFF, 0xFF55FF55, 0xFFFFD25A, 0xFFFFFFFF, 0xFFFFFFFF);
            case CUSTOM -> new Theme(colour(c.background), colour(c.card), colour(c.hover), colour(c.border), colour(c.equipped),
                colour(c.favourite), colour(c.accent), colour(c.text));
        };
    }

    private static int colour(String value) {
        return ChromaColours.parse(value).getEffectiveColourRGB();
    }

    // ------------------------------------------------------------------------------------------------ reading the menu

    private static List<SlotView> slots(AbstractContainerScreen<?> screen, int page) {
        var menu = screen.getMenu();
        List<SlotView> out = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack button = menu.slots.get(FIRST_BUTTON + i).getItem();
            String name = ChatFormatting.stripFormatting(button.getHoverName().getString()).trim();
            Matcher m = SLOT_NAME.matcher(name);
            int number = m.matches() ? Integer.parseInt(m.group(1)) : (page - 1) * 9 + i + 1;
            String state = m.matches() ? m.group(2) : "";
            ItemStack[] pieces = new ItemStack[4];
            boolean empty = true;
            for (int row = 0; row < 4; row++) {
                ItemStack stack = menu.slots.get(row * 9 + i).getItem();
                if (stack.isEmpty() || id(stack).endsWith("stained_glass_pane")) continue;
                pieces[row] = stack;
                empty = false;
            }
            boolean locked = state.contains("Locked") || id(button).equals("red_dye");
            out.add(new SlotView(i, number, pieces, state.contains("Equipped"), locked, empty));
        }
        return out;
    }

    private static String id(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static List<SlotView> shown(FeatureConfigs.CustomWardrobe c, List<SlotView> slots) {
        List<SlotView> out = new ArrayList<>();
        for (SlotView s : slots) {
            if (s.equipped()) {
                out.add(s);
                continue;
            }
            if (c.hideLocked && s.locked()) continue;
            if (c.hideEmpty && s.empty()) continue;
            if (c.onlyFavourites && !c.favourites.contains(s.number())) continue;
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    public static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        FeatureConfigs.CustomWardrobe c = config();
        if (c == null) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Theme t = theme(c);
        HITS.clear();
        int width = g.guiWidth(), height = g.guiHeight();
        g.fill(0, 0, width, height, 0x70000000);

        int[] page = page(screen);
        List<SlotView> slots = shown(c, slots(screen, page[0]));
        int perRow = Math.max(1, Math.min(c.perRow, Math.max(1, slots.size())));
        int rows = Math.max(1, (slots.size() + perRow - 1) / perRow);

        // Card size: as big as fits, up to 72 wide, 7:4 tall.
        float cardW = Math.min(72, (width * 0.92f - 2 * PAD - (perRow - 1) * GAP) / perRow);
        float cardH = cardW * 1.75f;
        float maxH = (height * 0.92f - 2 * PAD - HEADER - FOOTER - (rows - 1) * GAP) / rows;
        if (cardH > maxH) {
            cardH = maxH;
            cardW = cardH / 1.75f;
        }
        int cw = Math.max(24, (int) cardW), ch = Math.max(42, (int) cardH);

        String[] buttonLabels = {"Back", "Close", (c.onlyFavourites ? "★ " : "☆ ") + "Favourites", "Theme: " + c.theme};
        int[] buttonWidths = new int[buttonLabels.length];
        int buttonsW = 0;
        for (int i = 0; i < buttonLabels.length; i++) {
            buttonWidths[i] = font.width(buttonLabels[i]) + 12;
            buttonsW += buttonWidths[i] + (i > 0 ? 4 : 0);
        }
        int gridW = perRow * cw + (perRow - 1) * GAP;
        int panelW = Math.max(Math.max(gridW, buttonsW), 160) + 2 * PAD;
        int panelH = 2 * PAD + HEADER + rows * ch + (rows - 1) * GAP + FOOTER;
        int px = (width - panelW) / 2, py = (height - panelH) / 2;
        g.fill(px, py, px + panelW, py + panelH, t.background());
        g.outline(px, py, panelW, panelH, t.border());

        // Header: the title and a tab per page.
        g.text(font, "Wardrobe", px + PAD, py + PAD + 2, t.accent(), t.shadow());
        int tabX = px + panelW - PAD;
        for (int p = page[1]; p >= 1; p--) {
            String label = String.valueOf(p);
            int tw = font.width(label) + 10;
            tabX -= tw;
            boolean current = p == page[0], over = in(mouseX, mouseY, tabX, py + PAD, tw, 13);
            g.fill(tabX, py + PAD, tabX + tw, py + PAD + 13, current ? t.hover() : over ? t.hover() : t.card());
            g.outline(tabX, py + PAD, tw, 13, current ? t.accent() : t.border());
            centred(g, font, t, label, tabX + tw / 2, py + PAD + 3, current ? t.accent() : t.text());
            int target = p;
            HITS.add(new Hit(tabX, py + PAD, tw, 13, () -> {
                if (target != page[0]) targetPage = target;
            }));
            tabX -= 3;
        }
        if (page[1] > 1) {
            String label = "Page";
            g.text(font, label, tabX - font.width(label) - 2, py + PAD + 3, t.text(), t.shadow());
        }

        // The cards.
        int gridX = px + (panelW - gridW) / 2, gridY = py + PAD + HEADER;
        List<Component> tooltip = null;
        if (slots.isEmpty()) {
            centred(g, font, t, c.onlyFavourites ? "No favourites on this page" : "Nothing to show on this page", px + panelW / 2,
                gridY + ch / 2 - 4, t.text());
        }
        for (int n = 0; n < slots.size(); n++) {
            SlotView s = slots.get(n);
            int x = gridX + (n % perRow) * (cw + GAP), y = gridY + (n / perRow) * (ch + GAP);
            List<Component> tip = card(g, font, t, c, screen, s, x, y, cw, ch, mouseX, mouseY);
            if (tip != null) tooltip = tip;
        }

        // Buttons along the bottom.
        int bx = px + (panelW - buttonsW) / 2, by = py + panelH - PAD - BUTTON_H;
        Runnable[] actions = {
            () -> click(screen, BACK),
            () -> click(screen, CLOSE),
            () -> {
                c.onlyFavourites = !c.onlyFavourites;
                save();
            },
            () -> {
                WardrobeTheme[] all = WardrobeTheme.values();
                c.theme = all[(c.theme.ordinal() + 1) % all.length];
                save();
            },
        };
        for (int i = 0; i < buttonLabels.length; i++) {
            boolean over = in(mouseX, mouseY, bx, by, buttonWidths[i], BUTTON_H);
            boolean on = i == 2 && c.onlyFavourites;
            g.fill(bx, by, bx + buttonWidths[i], by + BUTTON_H, over ? t.hover() : t.card());
            g.outline(bx, by, buttonWidths[i], BUTTON_H, on ? t.favourite() : t.border());
            centred(g, font, t, buttonLabels[i], bx + buttonWidths[i] / 2, by + 4, on ? t.favourite() : t.text());
            HITS.add(new Hit(bx, by, buttonWidths[i], BUTTON_H, actions[i]));
            bx += buttonWidths[i] + 4;
        }

        if (tooltip != null) g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
    }

    /** One armour set's card; returns the tooltip of the piece under the mouse, if any. */
    private static List<Component> card(GuiGraphicsExtractor g, Font font, Theme t, FeatureConfigs.CustomWardrobe c,
                                        AbstractContainerScreen<?> screen, SlotView s, int x, int y, int w, int h, int mouseX, int mouseY) {
        boolean over = in(mouseX, mouseY, x, y, w, h);
        boolean favourite = c.favourites.contains(s.number());
        g.fill(x, y, x + w, y + h, over && !s.locked() ? t.hover() : t.card());
        int edge = s.equipped() ? t.equipped() : favourite ? t.favourite() : t.border();
        g.outline(x, y, w, h, edge);
        if (s.equipped()) g.outline(x + 1, y + 1, w - 2, h - 2, edge);

        g.text(font, "#" + s.number(), x + 3, y + 3, t.accent(), t.shadow());
        if (!s.locked()) {
            int sx = x + w - 11, sy = y + 2;
            boolean overStar = in(mouseX, mouseY, sx - 1, sy - 1, 11, 11);
            g.text(font, "★", sx, sy, favourite ? t.favourite() : overStar ? t.text() : dim(t.text()), t.shadow());
            HITS.add(new Hit(sx - 1, sy - 1, 11, 11, () -> {
                if (favourite) c.favourites.remove(Integer.valueOf(s.number()));
                else c.favourites.add(s.number());
                save();
            }));
        }

        int modelTop = y + 13, modelH = h - 13 - 12;
        if (s.locked()) {
            centred(g, font, t, "Locked", x + w / 2, y + h / 2 - 4, dim(t.text()));
            return null;
        }
        if (!s.empty()) drawModel(g, s, x + 2, modelTop, w - 4, modelH, mouseX, mouseY);
        if (s.empty()) centred(g, font, t, "Empty", x + w / 2, y + h / 2 - 4, dim(t.text()));
        String label = s.equipped() ? "Equipped" : !s.empty() && over ? "Equip" : "";
        // Small cards (a narrow window) get a tick instead of a word that wouldn't fit.
        if (font.width(label) > w - 4) label = s.equipped() ? "✔" : "";
        if (!label.isEmpty()) {
            centred(g, font, t, label, x + w / 2, y + h - 10, s.equipped() ? t.equipped() : s.empty() ? dim(t.text()) : t.text());
        }
        if (!s.empty() || s.equipped()) HITS.add(new Hit(x, y, w, h, () -> click(screen, FIRST_BUTTON + s.index())));

        // The piece under the mouse: the model's height in four parts, helmet at the top.
        if (over && in(mouseX, mouseY, x, modelTop, w, modelH)) {
            int piece = Mth.clamp((mouseY - modelTop) * 4 / Math.max(1, modelH), 0, 3);
            ItemStack stack = s.pieces()[piece];
            if (stack != null) return Screen.getTooltipFromItem(Minecraft.getInstance(), stack);
        }
        return null;
    }

    private static void centred(GuiGraphicsExtractor g, Font font, Theme t, String text, int x, int y, int colour) {
        g.text(font, text, x - font.width(text) / 2, y, colour, t.shadow());
    }

    private static int dim(int argb) {
        return (argb & 0x00FFFFFF) | ((((argb >>> 24) & 0xFF) * 6 / 10) << 24);
    }

    private static void drawModel(GuiGraphicsExtractor g, SlotView s, int x, int y, int w, int h, int mouseX, int mouseY) {
        RemotePlayer model = model(s.index());
        if (model == null) return;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) model.setItemSlot(slots[i], s.pieces()[i] == null ? ItemStack.EMPTY : s.pieces()[i]);
        // Facing you (180, as the Profile Viewer's model), turned a little towards the mouse.
        float yaw = 180 + Mth.clamp((mouseX - (x + w / 2f)) / 4f, -35, 35);
        float pitch = Mth.clamp((mouseY - (y + h / 3f)) / 10f, -12, 12);
        Quaternionf rotation = new Quaternionf().rotationXYZ(-pitch * Mth.DEG_TO_RAD, -yaw * Mth.DEG_TO_RAD, (float) Math.PI);
        EntityRenderState state = Sky2MInventoryScreenInvoker.invokeExtractRenderState(model);
        float scale = Math.min(h * 0.41f, w * 0.9f);
        g.entity(state, scale, new Vector3f(0, model.getBbHeight() / 2f + 0.0625f, 0), rotation, null, x, y, x + w, y + h);
    }

    /** A model of you for card {@code index}, built once and reused. */
    private static RemotePlayer model(int index) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        RemotePlayer existing = MODELS[index];
        if (existing != null && existing.level() == mc.level) return existing;
        GameProfile profile = mc.player.getGameProfile();
        RemotePlayer model = new RemotePlayer(mc.level, profile) {
            @Override
            public boolean isInvisibleTo(Player other) {
                return true;
            }

            @Override
            public void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack) {}

            @Override
            public boolean isModelPartShown(PlayerModelPart part) {
                return part != PlayerModelPart.CAPE;
            }

            @Override
            public PlayerSkin getSkin() {
                var self = Minecraft.getInstance().player;
                return self != null ? self.getSkin() : super.getSkin();
            }

            @Override
            public int getId() {
                return Compat.PLACEHOLDER_ID;
            }
        };
        model.yHeadRot = model.yHeadRotO = 0;
        MODELS[index] = model;
        return model;
    }

    // ------------------------------------------------------------------------------------------------ input

    public static void click(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        for (Hit hit : List.copyOf(HITS)) {
            if (hit.contains(mouseX, mouseY)) {
                hit.action().run();
                return;
            }
        }
    }

    private static void click(AbstractContainerScreen<?> screen, int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || slot >= screen.getMenu().slots.size()) return;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private static void save() {
        Sky2MConfig c = Sky2MConfig.current();
        if (c != null) Sky2MConfig.saveCurrent(c);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
