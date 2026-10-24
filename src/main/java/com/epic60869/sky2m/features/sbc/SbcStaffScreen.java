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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /s2 staff: Sky2M's staff tools in game, in the settings' look. Chat: mute (for some minutes, or permanently), ban and
 * unmute, with the active mutes and bans. Features: every remote feature switch the mod has, grouped and searchable,
 * for admins and owners to turn off (or on) for every player. Audit: the staff audit log with filters and pages.
 * Player: one player's cosmetics, mute status, last seen and reports, with quick mute/ban/unmute. Online: who is
 * connected with their mod version, plus a few counts. Announce: a message to every connected player (admins and
 * owners). Everything goes to the server, which checks the staff role again (staff-mod.js, staff-extra.js).
 */
public final class SbcStaffScreen extends Screen {
    private enum Tab {
        CHAT("Chat"), FEATURES("Features"), AUDIT("Audit"), PLAYER("Player"), ONLINE("Online"), ANNOUNCE("Announce");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    /** Used only while the server hasn't sent its flag list yet. */
    private static final Map<String, String> FEATURES = new LinkedHashMap<>();

    static {
        FEATURES.put("chat", "Sky2M chat");
        FEATURES.put("chat.replies", "Chat replies");
        FEATURES.put("chat.reactions", "Chat reactions");
        FEATURES.put("chat.items", "Chat item sharing");
        FEATURES.put("chat.rooms", "Private chat rooms");
        FEATURES.put("nicknames", "Nicknames and name tags");
        FEATURES.put("friends", "Friends");
        FEATURES.put("who", "Who's online");
        FEATURES.put("cosmetics", "Cosmetics (all)");
        FEATURES.put("cosmetics.emblems", "Emblems and symbols");
        FEATURES.put("cosmetics.capes", "Capes");
        FEATURES.put("cosmetics.models", "Play as Mob looks (mobs, models)");
        FEATURES.put("leaderboards", "Leaderboards and rivals");
        FEATURES.put("leaderboards.slayerPbs", "Slayer personal bests");
        FEATURES.put("partyFinder", "Party finder");
        FEATURES.put("partyFinder.stats", "Party finder stats");
        FEATURES.put("calendar", "SkyBlock event calendar");
        FEATURES.put("pestHighlight", "Pest highlight");
        FEATURES.put("itemCooldowns", "Item cooldowns");
        FEATURES.put("museumTooltip", "Museum tooltips");
        FEATURES.put("screenshots", "Screenshot sharing");
        FEATURES.put("settingsSync", "Settings cloud sync");
        FEATURES.put("crashReports", "Crash reports");
        FEATURES.put("reportThreads", "Bug reports & suggestions");
    }

    private static final int ROW = 14;
    private static final int AUDIT_PAGE = 12;
    private static final String[] STYLES = {"chat", "title", "toast"};

    private record Row(boolean header, String id, String name, String desc, String group) {}

    private Tab tab = Tab.CHAT;
    private EditBox player, minutes, reason, flagMessage, search, auditText, auditBy, auditTarget, announceText;
    private int scroll;
    private long lastRefresh;
    private final List<Hit> hits = new ArrayList<>();

    // Audit paging: cursors.get(page) = the "before" id that page was asked with.
    private final List<Long> cursors = new ArrayList<>(List.of(0L));
    private int auditPage;
    private int kindIndex;
    private String auditSig = null, typedSig = "";
    private long typedAt;

    private long lookupAt;
    private int styleIndex;
    private long confirmAnnounceUntil;

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
        search = box(search, "Search", 40);
        auditText = box(auditText, "Search the log", 60);
        auditBy = box(auditBy, "Staff", 32);
        auditTarget = box(auditTarget, "Target", 32);
        announceText = box(announceText, "Announcement for every connected player", 300);
        int x = left() + 12, y = top() + 50, w = panelWidth() - 24;
        int playerW = Math.min(110, w / 4), minutesW = 60;
        Sky2MGuiStyle.placeSearch(player, x, y, playerW);
        Sky2MGuiStyle.placeSearch(minutes, x + playerW + 6, y, minutesW);
        Sky2MGuiStyle.placeSearch(reason, x + playerW + minutesW + 12, y, w - playerW - minutesW - 12);
        Sky2MGuiStyle.placeSearch(search, x, y, 120);
        Sky2MGuiStyle.placeSearch(flagMessage, x + 126, y, w - 126);
        Sky2MGuiStyle.placeSearch(auditText, x, y, 120);
        Sky2MGuiStyle.placeSearch(auditBy, x + 126, y, 90);
        Sky2MGuiStyle.placeSearch(auditTarget, x + 222, y, w - 222);
        Sky2MGuiStyle.placeSearch(announceText, x, y, w);
        for (EditBox b : new EditBox[]{player, minutes, reason, flagMessage, search, auditText, auditBy, auditTarget, announceText}) {
            addRenderableWidget(b);
        }
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
        boolean staff = SbcStaff.isStaff();
        player.visible = tab == Tab.CHAT && staff || tab == Tab.PLAYER && staff;
        minutes.visible = reason.visible = player.visible;
        search.visible = staff && (tab == Tab.FEATURES || tab == Tab.ONLINE);
        flagMessage.visible = staff && tab == Tab.FEATURES && SbcStaff.canEditFlags();
        auditText.visible = auditBy.visible = auditTarget.visible = staff && tab == Tab.AUDIT;
        announceText.visible = staff && tab == Tab.ANNOUNCE && SbcStaff.canEditFlags();
    }

    private void setTab(Tab next) {
        tab = next;
        scroll = 0;
        if (next == Tab.AUDIT) auditSig = null;
        if (next == Tab.ONLINE) SbcStaff.requestOnline();
        showTab();
    }

    private String auditKind() {
        List<String> kinds = SbcStaff.auditKinds();
        return kindIndex <= 0 || kindIndex > kinds.size() ? "" : kinds.get(kindIndex - 1);
    }

    private void requestAudit() {
        SbcStaff.audit(auditKind(), auditText.getValue(), auditBy.getValue(), auditTarget.getValue(), cursors.get(auditPage), AUDIT_PAGE);
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        // Others may mute or switch features meanwhile: keep the lists fresh while the screen is open.
        if (now - lastRefresh > 10_000) {
            lastRefresh = now;
            SbcStaff.refresh();
            if (tab == Tab.ONLINE) SbcStaff.requestOnline();
        }
        if (tab == Tab.AUDIT && SbcStaff.isStaff()) {
            String sig = auditKind() + "|" + auditText.getValue() + "|" + auditBy.getValue() + "|" + auditTarget.getValue();
            if (!sig.equals(typedSig)) {
                typedSig = sig;
                typedAt = now;
            } else if (!sig.equals(auditSig) && now - typedAt > 350) {
                auditSig = sig;
                cursors.clear();
                cursors.add(0L);
                auditPage = 0;
                scroll = 0;
                requestAudit();
            }
        }
        if (lookupAt != 0 && now >= lookupAt) {
            lookupAt = 0;
            if (!player.getValue().isBlank()) SbcStaff.lookup(player.getValue());
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
            if (t == Tab.ANNOUNCE && !SbcStaff.canEditFlags()) continue;
            boolean hover = mouseX >= x && mouseX < x + font.width(t.label) && mouseY >= tabsY - 2 && mouseY < tabsY + 10;
            int w = Sky2MGuiStyle.category(g, font, t.label, x, tabsY, t == tab, hover);
            hits.add(new Hit(x, tabsY - 2, w, 12, () -> setTab(t)));
            x += w + 12;
        }

        switch (tab) {
            case CHAT -> drawChat(g, mouseX, mouseY);
            case FEATURES -> drawFeatures(g, mouseX, mouseY);
            case AUDIT -> drawAudit(g, mouseX, mouseY);
            case PLAYER -> drawPlayer(g, mouseX, mouseY);
            case ONLINE -> drawOnline(g, mouseX, mouseY);
            case ANNOUNCE -> drawAnnounce(g, mouseX, mouseY);
        }

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

    // ------------------------------------------------------------------------------------------------ features

    /** The flags to show: the server's list (or the built-in one before it arrives), grouped, filtered by the search box. */
    private List<Row> featureRows(JsonObject flags) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        List<JsonObject> catalog = SbcStaff.catalog();
        if (catalog.isEmpty()) {
            for (Map.Entry<String, String> e : FEATURES.entrySet()) {
                groups.computeIfAbsent("Features", k -> new ArrayList<>()).add(new Row(false, e.getKey(), e.getValue(), "", "Features"));
                seen.add(e.getKey());
            }
        } else {
            for (JsonObject f : catalog) {
                String id = Sbc.str(f, "id"), group = Sbc.str(f, "group");
                groups.computeIfAbsent(group.isEmpty() ? "Other" : group, k -> new ArrayList<>())
                    .add(new Row(false, id, Sbc.str(f, "name"), Sbc.str(f, "description"), group));
                seen.add(id);
            }
        }
        for (String key : flags.keySet()) {
            if (seen.add(key)) groups.computeIfAbsent("Other", k -> new ArrayList<>()).add(new Row(false, key, key, "Set by staff; the mod may not use it.", "Other"));
        }
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, List<Row>> group : groups.entrySet()) {
            List<Row> rows = new ArrayList<>();
            for (Row r : group.getValue()) {
                if (q.isEmpty() || (r.id + " " + r.name + " " + r.desc + " " + r.group).toLowerCase(Locale.ROOT).contains(q)) rows.add(r);
            }
            if (rows.isEmpty()) continue;
            out.add(new Row(true, "", group.getKey(), "", group.getKey()));
            out.addAll(rows);
        }
        return out;
    }

    private static boolean flagOn(JsonObject flags, String id) {
        JsonElement value = flags.get(id);
        if (value == null || !value.isJsonObject()) return true;
        JsonObject flag = value.getAsJsonObject();
        return !flag.has("enabled") || Sbc.bool(flag, "enabled");
    }

    /** Off itself, or because a parent ("chat" for "chat.replies") is off. */
    private static boolean effectivelyOn(JsonObject flags, String id) {
        for (String key = id; ; key = key.substring(0, key.lastIndexOf('.'))) {
            if (!flagOn(flags, key)) return false;
            if (key.lastIndexOf('.') < 0) return true;
        }
    }

    private void drawFeatures(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean canEdit = SbcStaff.canEditFlags();
        Sky2MGuiStyle.field(g, search);
        int rowsTop;
        if (canEdit) {
            Sky2MGuiStyle.field(g, flagMessage);
            rowsTop = top() + 72;
        } else {
            g.text(font, "Only admins and owners can switch features. You can see what's off.", left() + 134, top() + 54, Sky2MGuiStyle.TEXT_DIM, false);
            rowsTop = top() + 72;
        }
        int listBottom = top() + panelHeight() - 20;
        Sky2MGuiStyle.inset(g, left() + 8, rowsTop, left() + panelWidth() - 8, listBottom);

        JsonObject flags = SbcStaff.flags();
        List<Row> list = featureRows(flags);
        int visible = Math.max(1, (listBottom - rowsTop - 4) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - visible));
        if (list.isEmpty()) g.text(font, "No feature matches.", left() + 14, rowsTop + 5, Sky2MGuiStyle.MUTED, false);
        int bw = Sky2MGuiStyle.buttonWidth(font, "Turn off");
        for (int i = scroll; i < list.size() && i - scroll < visible; i++) {
            Row row = list.get(i);
            int ry = rowsTop + 2 + (i - scroll) * ROW;
            if (row.header) {
                g.text(font, "§6" + row.name, left() + 14, ry + 3, 0xFFFFFFFF, false);
                continue;
            }
            boolean on = flagOn(flags, row.id), effective = effectivelyOn(flags, row.id);
            JsonElement value = flags.get(row.id);
            String message = value != null && value.isJsonObject() ? Sbc.str(value.getAsJsonObject(), "message") : "";
            String dot = !on ? "§c● " : effective ? "§a● " : "§e● ";
            String detail = !on && !message.isEmpty() ? message : row.desc;
            String line = dot + "§f" + row.name + " §8" + row.id + (detail.isEmpty() ? "" : "  §7" + detail);
            g.text(font, font.plainSubstrByWidth(line, panelWidth() - 44 - (canEdit ? bw : 0)), left() + 20, ry + 3, 0xFFFFFFFF, false);
            if (canEdit) {
                int bx = left() + panelWidth() - 12 - bw;
                boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= ry && mouseY < ry + ROW - 1;
                Sky2MGuiStyle.button(g, font, on ? "Turn off" : "Turn on", bx, ry, bw, ROW - 1, hover);
                hits.add(new Hit(bx, ry, bw, ROW - 1, () -> SbcStaff.setFlag(row.id, !on, flagMessage.getValue())));
            }
        }
        Sky2MGuiStyle.scrollbar(g, left() + panelWidth() - 14, rowsTop + 2, listBottom - 2, scroll, visible, list.size());
        if (SbcStaff.status().isEmpty()) {
            g.text(font, "§a● on  §e● off through its parent  §c● off", left() + 12, top() + panelHeight() - 14, 0xFFFFFFFF, false);
        }
    }

    // ------------------------------------------------------------------------------------------------ audit

    private void drawAudit(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.field(g, auditText);
        Sky2MGuiStyle.field(g, auditBy);
        Sky2MGuiStyle.field(g, auditTarget);
        int y = top() + 72, x = left() + 12;
        String kind = auditKind();
        x = button(g, "Action: " + (kind.isEmpty() ? "all" : kind), x, y, mouseX, mouseY, () -> {
            int count = SbcStaff.auditKinds().size() + 1;
            kindIndex = (kindIndex + 1) % Math.max(1, count);
        });
        x = button(g, "< Newer", x, y, mouseX, mouseY, () -> {
            if (auditPage > 0) {
                auditPage--;
                scroll = 0;
                requestAudit();
            }
        });
        x = button(g, "Older >", x, y, mouseX, mouseY, () -> {
            List<JsonObject> entries = SbcStaff.auditEntries();
            if (SbcStaff.auditMore() && !entries.isEmpty()) {
                long last = Sbc.lng(entries.get(entries.size() - 1), "id", 0);
                while (cursors.size() > auditPage + 1) cursors.remove(cursors.size() - 1);
                cursors.add(last);
                auditPage++;
                scroll = 0;
                requestAudit();
            }
        });
        String page = "Page " + (auditPage + 1) + "  ·  " + SbcStaff.auditTotal() + " entries logged";
        g.text(font, font.plainSubstrByWidth(page, left() + panelWidth() - 12 - x), x, y + 4, Sky2MGuiStyle.TEXT_DIM, false);

        int listTop = y + 20, listBottom = top() + panelHeight() - 20;
        Sky2MGuiStyle.inset(g, left() + 8, listTop, left() + panelWidth() - 8, listBottom);
        List<JsonObject> entries = SbcStaff.auditEntries();
        int rowsTop = listTop + 2, visible = Math.max(1, (listBottom - rowsTop - 2) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, entries.size() - visible));
        if (entries.isEmpty()) g.text(font, "No entries match.", left() + 14, rowsTop + 3, Sky2MGuiStyle.MUTED, false);
        for (int i = scroll; i < entries.size() && i - scroll < visible; i++) {
            JsonObject e = entries.get(i);
            int ry = rowsTop + (i - scroll) * ROW;
            boolean hover = mouseX >= left() + 10 && mouseX < left() + panelWidth() - 10 && mouseY >= ry && mouseY < ry + ROW;
            if (hover) g.fill(left() + 10, ry, left() + panelWidth() - 10, ry + ROW, Sky2MGuiStyle.ROW_HOVER);
            String target = Sbc.str(e, "target");
            String line = "§8" + SbcStaff.time(Sbc.lng(e, "at", 0)) + " §e" + Sbc.str(e, "kind") + " §f" + Sbc.str(e, "by")
                + (target.isEmpty() ? "" : " §7→ §f" + target) + " §7" + Sbc.str(e, "summary");
            g.text(font, font.plainSubstrByWidth(line, panelWidth() - 40), left() + 14, ry + 3, 0xFFFFFFFF, false);
            // Clicking a row with a target opens that player.
            if (!target.isEmpty()) hits.add(new Hit(left() + 10, ry, panelWidth() - 30, ROW, () -> openPlayer(target)));
        }
        Sky2MGuiStyle.scrollbar(g, left() + panelWidth() - 14, rowsTop, listBottom - 2, scroll, visible, entries.size());
    }

    // ------------------------------------------------------------------------------------------------ player

    private void openPlayer(String name) {
        player.setValue(name);
        setTab(Tab.PLAYER);
        SbcStaff.lookup(name);
    }

    private void drawPlayer(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.field(g, player);
        Sky2MGuiStyle.field(g, minutes);
        Sky2MGuiStyle.field(g, reason);
        int y = top() + 72, x = left() + 12;
        x = button(g, "Look up", x, y, mouseX, mouseY, () -> {
            if (needPlayer()) SbcStaff.lookup(player.getValue());
        });
        x = button(g, "Mute", x, y, mouseX, mouseY, () -> {
            int mins = parseMinutes();
            if (mins < 0) {
                SbcStaff.handle("staffResult", result("Minutes must be a whole number (or blank for permanent)."));
            } else if (needPlayer()) {
                SbcStaff.mute(player.getValue(), mins, reason.getValue(), false);
                lookupAt = System.currentTimeMillis() + 700;
            }
        });
        x = button(g, "Ban", x, y, mouseX, mouseY, () -> {
            if (needPlayer()) {
                SbcStaff.mute(player.getValue(), 0, reason.getValue(), true);
                lookupAt = System.currentTimeMillis() + 700;
            }
        });
        x = button(g, "Unmute", x, y, mouseX, mouseY, () -> {
            if (needPlayer()) {
                SbcStaff.unmute(player.getValue());
                lookupAt = System.currentTimeMillis() + 700;
            }
        });
        button(g, "Their log", x, y, mouseX, mouseY, () -> {
            if (needPlayer()) {
                auditTarget.setValue(player.getValue().trim());
                auditText.setValue("");
                auditBy.setValue("");
                kindIndex = 0;
                setTab(Tab.AUDIT);
            }
        });

        int listTop = y + 20, listBottom = top() + panelHeight() - 20;
        Sky2MGuiStyle.inset(g, left() + 8, listTop, left() + panelWidth() - 8, listBottom);
        List<String> lines = lookupLines();
        int rowsTop = listTop + 3, visible = Math.max(1, (listBottom - rowsTop - 2) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, lines.size() - visible));
        for (int i = scroll; i < lines.size() && i - scroll < visible; i++) {
            g.text(font, font.plainSubstrByWidth(lines.get(i), panelWidth() - 40), left() + 14, rowsTop + (i - scroll) * ROW + 2, 0xFFFFFFFF, false);
        }
        Sky2MGuiStyle.scrollbar(g, left() + panelWidth() - 14, rowsTop, listBottom - 2, scroll, visible, lines.size());
    }

    private List<String> lookupLines() {
        List<String> lines = new ArrayList<>();
        JsonObject l = SbcStaff.lookup();
        if (l == null) {
            lines.add("§7Type a player's name and press Look up.");
            return lines;
        }
        if (!Sbc.bool(l, "found")) {
            lines.add("§c" + Sbc.str(l, "query") + " hasn't used Sky2M yet.");
            return lines;
        }
        boolean online = Sbc.bool(l, "online");
        String version = Sbc.str(l, "version");
        lines.add("§f§l" + Sbc.str(l, "username") + "  " + (online ? "§aonline" : "§7last seen " + SbcStaff.time(Sbc.lng(l, "lastSeen", 0)))
            + (version.isEmpty() ? "" : "  §8mod " + version));
        lines.add("§8" + Sbc.str(l, "uuid"));
        String rank = Sbc.str(l, "rank"), nick = Sbc.str(l, "nickname");
        lines.add("§7Rank: §f" + (rank.isEmpty() ? "none" : rank) + "   §7Nickname: §f" + (nick.isEmpty() ? "none" : nick));
        JsonObject mute = l.has("mute") && l.get("mute").isJsonObject() ? l.getAsJsonObject("mute") : null;
        if (mute == null) {
            lines.add("§7Chat: §anot muted");
        } else {
            long until = Sbc.lng(mute, "until", 0);
            String reasonText = Sbc.str(mute, "reason");
            lines.add("§7Chat: " + (Sbc.bool(mute, "ban") ? "§cBANNED" : until == 0 ? "§cmuted permanently" : "§cmuted, " + Sbc.duration(until - System.currentTimeMillis()) + " left")
                + (reasonText.isEmpty() ? "" : " §7(" + reasonText + ")") + " §8by " + Sbc.str(mute, "by"));
        }
        JsonObject c = Sbc.obj(l, "cosmetics");
        lines.add("§7Emblems: §f" + joined(c, "emblems") + "   §7Tier: §f" + orNone(Sbc.str(c, "tier")));
        lines.add("§7Capes: §f" + joined(c, "capes") + "   §7Models: §f" + joined(c, "models") + "   §7Unlocks: §f" + joined(c, "unlocks"));
        JsonObject r = Sbc.obj(l, "reports");
        lines.add("§7Reports: §f" + Sbc.lng(r, "total", 0) + " §7(" + Sbc.lng(r, "open", 0) + " open)");
        lines.add("§6Recent staff actions");
        var recent = Sbc.arr(l, "recent");
        if (recent.size() == 0) lines.add("  §8none");
        for (JsonElement e : recent) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            lines.add("  §8" + SbcStaff.time(Sbc.lng(a, "at", 0)) + " §e" + Sbc.str(a, "kind") + " §7" + Sbc.str(a, "by") + ": " + Sbc.str(a, "summary"));
        }
        return lines;
    }

    private static String orNone(String text) {
        return text.isEmpty() ? "none" : text;
    }

    private static String joined(JsonObject o, String key) {
        List<String> parts = new ArrayList<>();
        for (JsonElement e : Sbc.arr(o, key)) parts.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    // ------------------------------------------------------------------------------------------------ online

    private void drawOnline(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Sky2MGuiStyle.field(g, search);
        JsonObject c = SbcStaff.counts();
        int x = left() + 12, y = top() + 70;
        List<String> summary = new ArrayList<>();
        summary.add("§f" + Sbc.lng(c, "players", 0) + " §7players online (" + Sbc.lng(c, "connections", 0) + " connections)   §f"
            + Sbc.lng(c, "muted", 0) + " §7muted   §f" + Sbc.lng(c, "banned", 0) + " §7banned   §f" + Sbc.lng(c, "auditEntries", 0) + " §7log entries");
        StringBuilder reports = new StringBuilder("§7Reports: ");
        JsonObject byType = Sbc.obj(c, "reports");
        if (byType.size() == 0) reports.append("§8none");
        for (Map.Entry<String, JsonElement> e : byType.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject t = e.getValue().getAsJsonObject();
            reports.append("§f").append(e.getKey()).append(" §7").append(Sbc.lng(t, "open", 0)).append(" open");
            if (Sbc.lng(t, "unread", 0) > 0) reports.append(" §e(").append(Sbc.lng(t, "unread", 0)).append(" new)");
            reports.append("   ");
        }
        summary.add(reports.toString());
        List<String> off = new ArrayList<>();
        for (JsonElement e : Sbc.arr(c, "flagsOff")) off.add(e.getAsString());
        summary.add("§7Features off: " + (off.isEmpty() ? "§anone" : "§c" + String.join(", ", off)));
        for (String line : summary) {
            g.text(font, font.plainSubstrByWidth(line, panelWidth() - 24), x, y, 0xFFFFFFFF, false);
            y += 12;
        }

        int listTop = y + 2, listBottom = top() + panelHeight() - 20;
        Sky2MGuiStyle.inset(g, left() + 8, listTop, left() + panelWidth() - 8, listBottom);
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<JsonObject> players = new ArrayList<>();
        for (JsonObject p : SbcStaff.onlinePlayers()) {
            if (q.isEmpty() || (Sbc.str(p, "username") + " " + Sbc.str(p, "version")).toLowerCase(Locale.ROOT).contains(q)) players.add(p);
        }
        int rowsTop = listTop + 2, visible = Math.max(1, (listBottom - rowsTop - 2) / ROW);
        scroll = Math.clamp(scroll, 0, Math.max(0, players.size() - visible));
        if (players.isEmpty()) g.text(font, "Nobody matches.", left() + 14, rowsTop + 3, Sky2MGuiStyle.MUTED, false);
        for (int i = scroll; i < players.size() && i - scroll < visible; i++) {
            JsonObject p = players.get(i);
            int ry = rowsTop + (i - scroll) * ROW;
            boolean hover = mouseX >= left() + 10 && mouseX < left() + panelWidth() - 10 && mouseY >= ry && mouseY < ry + ROW;
            if (hover) g.fill(left() + 10, ry, left() + panelWidth() - 10, ry + ROW, Sky2MGuiStyle.ROW_HOVER);
            String name = Sbc.str(p, "username");
            long since = Sbc.lng(p, "since", 0);
            String line = "§f" + name + "  §8mod §7" + Sbc.str(p, "version") + (since > 0 ? "  §8online " + Sbc.duration(System.currentTimeMillis() - since) : "");
            g.text(font, font.plainSubstrByWidth(line, panelWidth() - 40), left() + 14, ry + 3, 0xFFFFFFFF, false);
            hits.add(new Hit(left() + 10, ry, panelWidth() - 30, ROW, () -> openPlayer(name)));
        }
        Sky2MGuiStyle.scrollbar(g, left() + panelWidth() - 14, rowsTop, listBottom - 2, scroll, visible, players.size());
    }

    // ------------------------------------------------------------------------------------------------ announce

    private void drawAnnounce(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!SbcStaff.canEditFlags()) {
            g.text(font, "Only admins and owners can send announcements.", left() + 12, top() + 54, Sky2MGuiStyle.TEXT_DIM, false);
            return;
        }
        Sky2MGuiStyle.field(g, announceText);
        int y = top() + 72, x = left() + 12;
        x = button(g, "Style: " + STYLES[styleIndex], x, y, mouseX, mouseY, () -> styleIndex = (styleIndex + 1) % STYLES.length);
        boolean confirming = System.currentTimeMillis() < confirmAnnounceUntil;
        button(g, confirming ? "Click again to send" : "Send to everyone", x, y, mouseX, mouseY, () -> {
            if (announceText.getValue().isBlank()) {
                SbcStaff.handle("staffResult", result("Write the announcement first."));
            } else if (System.currentTimeMillis() < confirmAnnounceUntil) {
                confirmAnnounceUntil = 0;
                SbcStaff.announce(announceText.getValue(), STYLES[styleIndex]);
                announceText.setValue("");
            } else {
                confirmAnnounceUntil = System.currentTimeMillis() + 4000;
            }
        });
        String[] help = {
            "Goes to every player connected to the Sky2M server right now (older mods get it as a chat line).",
            "chat = a line in Sky2M chat, title = big text on screen, toast = a pop-up. It is saved in the audit log."
        };
        for (int i = 0; i < help.length; i++) {
            g.text(font, font.plainSubstrByWidth(help[i], panelWidth() - 24), left() + 12, y + 26 + i * 12, Sky2MGuiStyle.TEXT_DIM, false);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

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
