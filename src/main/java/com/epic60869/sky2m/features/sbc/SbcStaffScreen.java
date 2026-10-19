package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MGuiStyle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /s2 staff: Sky2M's staff tools in game, in the settings' look. Chat: mute (for some minutes, or permanently), ban and
 * unmute, with the active mutes and bans. Features: switch a mod feature off (or on) for every player, for admins and
 * owners. Everything goes to the server, which checks the staff role again (staff-mod.js).
 */
public final class SbcStaffScreen extends Screen {
    private enum Tab { CHAT, FEATURES }

    /** The features the mod checks a server flag for (Flags), in a readable order with their names. */
    private static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        FEATURES.put("chat.replies", "Chat replies");
        FEATURES.put("chat.reactions", "Chat reactions");
        FEATURES.put("chat.items", "Chat item sharing");
        FEATURES.put("friends", "Friends");
        FEATURES.put("cosmetics", "Cosmetics (emblems, symbols)");
        FEATURES.put("cosmetics.capes", "Capes");
        FEATURES.put("calendar", "SkyBlock event calendar");
        FEATURES.put("pestHighlight", "Pest highlight");
        FEATURES.put("itemCooldowns", "Item cooldowns");
        FEATURES.put("settingsSync", "Settings cloud sync");
        FEATURES.put("crashReports", "Crash reports");
        FEATURES.put("reportThreads", "Bug reports & suggestions");
    }

    private static final int ROW = 14;

    private Tab tab = Tab.CHAT;
    private EditBox player, minutes, reason, flagMessage;
    private int scroll;
    private long lastRefresh;
    private final List<Hit> hits = new ArrayList<>();

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public SbcStaffScreen() {
        super(Component.literal("Sky2M Staff"));
    }

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

    @Override
    protected void init() {
        player = box(player, "Player", 16);
        minutes = box(minutes, "Minutes", 6);
        reason = box(reason, "Reason", 200);
        flagMessage = box(flagMessage, "Message players see when it's off", 200);
        int x = left() + 12, y = top() + 50, w = panelWidth() - 24;
        int playerW = Math.min(110, w / 4), minutesW = 60;
        Sky2MGuiStyle.placeSearch(player, x, y, playerW);
        Sky2MGuiStyle.placeSearch(minutes, x + playerW + 6, y, minutesW);
        Sky2MGuiStyle.placeSearch(reason, x + playerW + minutesW + 12, y, w - playerW - minutesW - 12);
        Sky2MGuiStyle.placeSearch(flagMessage, x, y, w);
        addRenderableWidget(player);
        addRenderableWidget(minutes);
        addRenderableWidget(reason);
        addRenderableWidget(flagMessage);
        showTab();
    }

    private EditBox box(EditBox old, String hint, int max) {
        EditBox box = new EditBox(font, 0, 0, 80, 10, Component.literal(hint));
        box.setMaxLength(max);
        box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        if (old != null) box.setValue(old.getValue());
        return box;
    }

    private void showTab() {
        boolean chat = tab == Tab.CHAT;
        player.visible = minutes.visible = reason.visible = chat && SbcStaff.isStaff();
        flagMessage.visible = !chat && SbcStaff.isStaff() && SbcStaff.canEditFlags();
    }

    @Override
    public void tick() {
        // Others may mute or switch features meanwhile: keep the lists fresh while the screen is open.
        if (System.currentTimeMillis() - lastRefresh > 10_000) {
            lastRefresh = System.currentTimeMillis();
            SbcStaff.refresh();
        }
        showTab();
    }

    // ------------------------------------------------------------------------------------------------ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sky2MGuiStyle.backdrop(g, width, height);
        Sky2MGuiStyle.panel(g, left(), top(), panelWidth(), panelHeight(), true);
        String who = SbcStaff.isStaff() ? SbcStaff.username() + " · " + SbcStaff.role() : "not logged in";
        Sky2MGuiStyle.header(g, font, left() + 4, top() + 5, panelWidth() - 9, Sky2MGuiStyle.title("Staff  ·  " + who));

        if (!SbcStaff.isStaff()) {
            String[] lines = SbcStaff.resuming()
                ? new String[]{"Logging in with your saved staff login..."}
                : new String[]{"You aren't logged in as staff.", "Get a code on shadowisabot.com (Staff > Mod login),", "then type /s2 stafflogin <code>."};
            int y = top() + panelHeight() / 2 - lines.length * 6;
            for (String line : lines) {
                g.text(font, line, width / 2 - font.width(line) / 2, y, Sky2MGuiStyle.DESCRIPTION, false);
                y += 12;
            }
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }

        int x = left() + 12, tabsY = top() + 32;
        for (Tab t : Tab.values()) {
            String label = t == Tab.CHAT ? "Chat" : "Features";
            boolean hover = mouseX >= x && mouseX < x + font.width(label) && mouseY >= tabsY - 2 && mouseY < tabsY + 10;
            int w = Sky2MGuiStyle.category(g, font, label, x, tabsY, t == tab, hover);
            hits.add(new Hit(x, tabsY - 2, w, 12, () -> {
                tab = t;
                scroll = 0;
                showTab();
            }));
            x += w + 14;
        }

        if (tab == Tab.CHAT) drawChat(g, mouseX, mouseY);
        else drawFeatures(g, mouseX, mouseY);

        String status = SbcStaff.status();
        if (!status.isEmpty()) {
            String fit = font.plainSubstrByWidth(status, panelWidth() - 24);
            g.text(font, fit, left() + 12, top() + panelHeight() - 14, SbcStaff.statusOk() ? 0xFF55FF55 : 0xFFFF5555, false);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawChat(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.field(g, player);
        Sky2MGuiStyle.field(g, minutes);
        Sky2MGuiStyle.field(g, reason);
        int y = top() + 72, x = left() + 12;
        x = button(g, "Mute", x, y, mouseX, mouseY, () -> {
            int mins = parseMinutes();
            if (mins < 0) {
                SbcStaff.handle("staffResult", result("Minutes must be a whole number (or blank for permanent)."));
            } else if (needPlayer()) {
                SbcStaff.mute(player.getValue(), mins, reason.getValue(), false);
            }
        });
        x = button(g, "Ban", x, y, mouseX, mouseY, () -> {
            if (needPlayer()) SbcStaff.mute(player.getValue(), 0, reason.getValue(), true);
        });
        button(g, "Unmute", x, y, mouseX, mouseY, () -> {
            if (needPlayer()) SbcStaff.unmute(player.getValue());
        });
        String help = "No minutes = permanent. A ban also hides Sky2M chat from them.";
        g.text(font, font.plainSubstrByWidth(help, panelWidth() - 24), left() + 12, y + 20, Sky2MGuiStyle.TEXT_DIM, false);

        int listTop = y + 34, listBottom = top() + panelHeight() - 20;
        Sky2MGuiStyle.inset(g, left() + 8, listTop, left() + panelWidth() - 8, listBottom);
        List<JsonObject> mutes = SbcStaff.mutes();
        Sky2MGuiStyle.heading(g, font, "Muted and banned (" + mutes.size() + ")", left() + 8, listTop + 4, panelWidth() - 16);
        int rowsTop = listTop + 16, visible = Math.max(1, (listBottom - rowsTop - 2) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, mutes.size() - visible));
        if (mutes.isEmpty()) {
            g.text(font, "Nobody is muted.", left() + 14, rowsTop + 2, Sky2MGuiStyle.MUTED, false);
        }
        for (int i = scroll; i < mutes.size() && i - scroll < visible; i++) {
            JsonObject m = mutes.get(i);
            int ry = rowsTop + (i - scroll) * ROW;
            boolean hover = mouseX >= left() + 10 && mouseX < left() + panelWidth() - 10 && mouseY >= ry && mouseY < ry + ROW;
            if (hover) g.fill(left() + 10, ry, left() + panelWidth() - 10, ry + ROW, Sky2MGuiStyle.ROW_HOVER);
            String name = Sbc.str(m, "username");
            long until = Sbc.lng(m, "until", 0);
            String length = Sbc.bool(m, "ban") ? "§cBANNED" : until == 0 ? "§6permanent" : "§e" + Sbc.duration(until - System.currentTimeMillis()) + " left";
            String reasonText = Sbc.str(m, "reason");
            String line = "§f" + name + "  " + length + (reasonText.isEmpty() ? "" : "  §7" + reasonText) + "  §8by " + Sbc.str(m, "by");
            int unmuteW = Sky2MGuiStyle.buttonWidth(font, "Unmute");
            g.text(font, font.plainSubstrByWidth(line, panelWidth() - 40 - unmuteW), left() + 14, ry + 3, 0xFFFFFFFF, false);
            int bx = left() + panelWidth() - 12 - unmuteW;
            boolean bHover = mouseX >= bx && mouseX < bx + unmuteW && mouseY >= ry && mouseY < ry + ROW - 1;
            Sky2MGuiStyle.button(g, font, "Unmute", bx, ry, unmuteW, ROW - 1, bHover);
            hits.add(new Hit(bx, ry, unmuteW, ROW - 1, () -> SbcStaff.unmute(name)));
            // Clicking the row fills the player box, to mute them again or for longer.
            hits.add(new Hit(left() + 10, ry, bx - left() - 12, ROW, () -> player.setValue(name)));
        }
        Sky2MGuiStyle.scrollbar(g, left() + panelWidth() - 14, rowsTop, listBottom - 2, scroll, visible, mutes.size());
    }

    private void drawFeatures(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean canEdit = SbcStaff.canEditFlags();
        int rowsTop;
        if (canEdit) {
            Sky2MGuiStyle.field(g, flagMessage);
            rowsTop = top() + 72;
        } else {
            g.text(font, "Only admins and owners can switch features. You can see what's off.", left() + 12, top() + 52, Sky2MGuiStyle.TEXT_DIM, false);
            rowsTop = top() + 68;
        }
        int listBottom = top() + panelHeight() - 20;
        Sky2MGuiStyle.inset(g, left() + 8, rowsTop, left() + panelWidth() - 8, listBottom);

        JsonObject flags = SbcStaff.flags();
        Set<String> names = new LinkedHashSet<>(FEATURES.keySet());
        names.addAll(flags.keySet());
        List<String> list = new ArrayList<>(names);
        int visible = Math.max(1, (listBottom - rowsTop - 4) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - visible));
        for (int i = scroll; i < list.size() && i - scroll < visible; i++) {
            String name = list.get(i);
            int ry = rowsTop + 2 + (i - scroll) * ROW;
            JsonElement value = flags.get(name);
            JsonObject flag = value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
            boolean on = flag == null || !flag.has("enabled") || Sbc.bool(flag, "enabled");
            String message = flag == null ? "" : Sbc.str(flag, "message");
            String label = FEATURES.getOrDefault(name, name);
            String line = (on ? "§a● " : "§c● ") + "§f" + label + " §8" + name + (!on && !message.isEmpty() ? "  §7" + message : "");
            int bw = Sky2MGuiStyle.buttonWidth(font, "Turn off");
            g.text(font, font.plainSubstrByWidth(line, panelWidth() - 40 - (canEdit ? bw : 0)), left() + 14, ry + 3, 0xFFFFFFFF, false);
            if (canEdit) {
                int bx = left() + panelWidth() - 12 - bw;
                boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= ry && mouseY < ry + ROW - 1;
                Sky2MGuiStyle.button(g, font, on ? "Turn off" : "Turn on", bx, ry, bw, ROW - 1, hover);
                hits.add(new Hit(bx, ry, bw, ROW - 1, () -> SbcStaff.setFlag(name, !on, flagMessage.getValue())));
            }
        }
        Sky2MGuiStyle.scrollbar(g, left() + panelWidth() - 14, rowsTop + 2, listBottom - 2, scroll, visible, list.size());
    }

    private int button(GuiGraphicsExtractor g, String label, int x, int y, int mouseX, int mouseY, Runnable action) {
        int w = Sky2MGuiStyle.buttonWidth(font, label), h = 16;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        Sky2MGuiStyle.button(g, font, label, x, y, w, h, hover);
        hits.add(new Hit(x, y, w, h, action));
        return x + w + 6;
    }

    private boolean needPlayer() {
        if (!player.getValue().isBlank()) return true;
        SbcStaff.handle("staffResult", result("Type a player's name first."));
        return false;
    }

    private static JsonObject result(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("message", message);
        return o;
    }

    /** The minutes typed, 0 for blank (permanent), or -1 when it isn't a whole number (so a typo never mutes forever). */
    private int parseMinutes() {
        String text = minutes.getValue().trim();
        if (text.isEmpty()) return 0;
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, scroll - (int) Math.signum(vertical));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
