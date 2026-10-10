package com.epic60869.sky2m;

import com.mojang.blaze3d.platform.InputConstants;

import com.epic60869.sky2m.Sky2MPartyFinder.Category;
import com.epic60869.sky2m.Sky2MPartyFinder.Listing;
import com.epic60869.sky2m.Sky2MPartyFinder.Member;
import com.epic60869.sky2m.Sky2MPartyFinder.Mine;
import com.epic60869.sky2m.Sky2MPartyFinder.Request;
import com.epic60869.sky2m.features.sbc.Sbc;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /s2 pf: Browse (open listings with Join buttons, filtered by category) and My Group (post or edit your listing, your
 * members and join requests). Everything shown comes from the pfListings / pfMine packets the server pushes; the
 * screen rebuilds its buttons when one arrives.
 */
public final class Sky2MPartyFinderScreen extends Screen {
    enum Tab { BROWSE, GROUP }

    private static final int W = 400;
    private static final int H = 240;
    private static final int ROW_H = 24;
    private static final int PANEL = 0xFF121722;
    private static final int ROW = 0xFF1A2130;
    private static final int BORDER = 0xFF3B465B;
    private static final int GOLD = 0xFFFFD35A;
    private static final int TEXT = 0xFFF3F6FF;
    private static final int MUTED = 0xFF9AA5B8;
    private static final int AQUA = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;

    static Tab tab = Tab.BROWSE;
    private static String filter = "";
    private static int scroll;
    // The create/edit form, kept while the screen is closed.
    private static String formCategory = "dungeons";
    private static int formDetail = 7; // F7
    private static String formText = "";
    private static int formSize = 5;
    private static String formNote = "";
    private static String joinNote = "";
    /** The listing the form was last filled from, so editing doesn't get overwritten by every pfMine. */
    private static String prefilledFrom = "";

    private EditBox detailBox;
    private EditBox noteBox;
    private EditBox joinBox;
    private int seenVersion = -1;

    public Sky2MPartyFinderScreen() {
        super(Component.literal("Party Finder"));
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2 + 10;
    }

    private int listTop() {
        return top() + 30;
    }

    private int visibleRows() {
        return (top() + H - 34 - listTop()) / ROW_H;
    }

    @Override
    protected void init() {
        seenVersion = Sky2MPartyFinder.version;
        int left = left();
        int top = top();
        Button browse = addRenderableWidget(Button.builder(Component.literal("Browse"), b -> switchTo(Tab.BROWSE))
            .bounds(left, top - 24, 80, 18).build());
        Button group = addRenderableWidget(Button.builder(Component.literal("My Group"), b -> switchTo(Tab.GROUP))
            .bounds(left + 82, top - 24, 80, 18).build());
        browse.active = tab != Tab.BROWSE;
        group.active = tab != Tab.GROUP;
        if (tab == Tab.BROWSE) initBrowse(); else initGroup();
    }

    private void switchTo(Tab t) {
        tab = t;
        rebuildWidgets();
    }

    // ---------------------------------------------------------------- browse

    private List<Listing> shownListings() {
        List<Listing> out = new ArrayList<>();
        for (Listing l : Sky2MPartyFinder.listings) {
            if (filter.isEmpty() || l.category().equalsIgnoreCase(filter)) out.add(l);
        }
        return out;
    }

    private void initBrowse() {
        int left = left();
        int top = top();
        List<String> names = new ArrayList<>(Sky2MPartyFinder.categories.keySet());
        if (!filter.isEmpty() && !names.contains(filter)) filter = "";
        names.addFirst("");
        int bw = (W - 16) / names.size();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Button b = addRenderableWidget(Button.builder(Component.literal(name.isEmpty() ? "All" : Sky2MPartyFinder.categoryLabel(name)), btn -> {
                filter = name;
                scroll = 0;
                rebuildWidgets();
            }).bounds(left + 8 + i * bw, top + 6, bw - 2, 18).build());
            b.active = !name.equals(filter);
        }

        List<Listing> shown = shownListings();
        scroll = Math.max(0, Math.min(scroll, shown.size() - visibleRows()));
        Mine mine = Sky2MPartyFinder.mine;
        for (int i = 0; i < visibleRows() && scroll + i < shown.size(); i++) {
            Listing l = shown.get(scroll + i);
            int y = listTop() + i * ROW_H + 2;
            boolean ours = mine.listing() != null && mine.listing().id().equals(l.id());
            boolean pending = mine.pending().contains(l.id());
            Button b;
            if (pending) {
                b = Button.builder(Component.literal("Requested"), btn -> Sky2MPartyFinder.cancel(l.id()))
                    .tooltip(Tooltip.create(Component.literal("Click to withdraw your request"))).bounds(left + W - 76, y, 68, 20).build();
            } else {
                b = Button.builder(Component.literal(ours ? (mine.leader() ? "Yours" : "Joined") : l.full() ? "Full" : "Join"),
                    btn -> Sky2MPartyFinder.join(l.id(), joinNote)).bounds(left + W - 76, y, 68, 20).build();
                b.active = !ours && !l.full();
            }
            addRenderableWidget(b);
        }

        if (joinBox == null) {
            joinBox = new EditBox(font, 0, 0, 200, 18, Component.literal("Join note"));
            joinBox.setMaxLength(100);
            joinBox.setValue(joinNote);
            joinBox.setHint(Component.literal("Note sent with Join (optional)"));
            joinBox.setResponder(v -> joinNote = v);
        }
        joinBox.setX(left + 8);
        joinBox.setY(top + H - 26);
        addRenderableWidget(joinBox);
    }

    // ---------------------------------------------------------------- my group

    private Category formCategory() {
        Map<String, Category> categories = Sky2MPartyFinder.categories;
        Category c = categories.get(formCategory);
        if (c == null) {
            c = categories.values().iterator().next();
            formCategory = c.name();
        }
        return c;
    }

    private void prefill(Mine mine) {
        if (!mine.leader() || mine.listing().id().equals(prefilledFrom)) return;
        Listing l = mine.listing();
        prefilledFrom = l.id();
        formCategory = l.category();
        Category c = formCategory();
        int index = c.details().indexOf(l.detail());
        if (index >= 0) formDetail = index; else formText = l.detail();
        formSize = l.size();
        formNote = l.note();
        if (detailBox != null) detailBox.setValue(formText);
        if (noteBox != null) noteBox.setValue(formNote);
    }

    private void initGroup() {
        int left = left();
        int top = top();
        int bottom = top + H;
        Mine mine = Sky2MPartyFinder.mine;
        prefill(mine);
        boolean form = !"member".equals(mine.role()) || mine.listing() == null;
        int fx = left + 60;

        if (form) {
            Category c = formCategory();
            List<String> names = new ArrayList<>(Sky2MPartyFinder.categories.keySet());
            addRenderableWidget(new CycleButton(fx, top + 26, 130, 18, Component.literal(Sky2MPartyFinder.categoryLabel(c.name())), step -> {
                formCategory = names.get(Math.floorMod(names.indexOf(formCategory) + step, names.size()));
                formDetail = 0;
                formSize = Math.min(formSize, formCategory().maxSize());
                rebuildWidgets();
            })).setTooltip(Tooltip.create(Component.literal("Left click: next category. Right click: previous.")));

            if (c.freeText()) {
                if (detailBox == null) {
                    detailBox = new EditBox(font, 0, 0, 130, 18, Component.literal("Detail"));
                    detailBox.setMaxLength(24);
                    detailBox.setValue(formText);
                    detailBox.setHint(Component.literal("e.g. Voidgloom T4"));
                    detailBox.setResponder(v -> formText = v);
                }
                detailBox.setX(fx);
                detailBox.setY(top + 50);
                addRenderableWidget(detailBox);
            } else {
                formDetail = Math.max(0, Math.min(formDetail, c.details().size() - 1));
                addRenderableWidget(new CycleButton(fx, top + 50, 130, 18, Component.literal(c.details().get(formDetail)), step -> {
                    formDetail = Math.floorMod(formDetail + step, c.details().size());
                    rebuildWidgets();
                })).setTooltip(Tooltip.create(Component.literal("Left click: next (M5 → M6). Right click: previous (M5 → M4).")));
            }

            formSize = Math.max(2, Math.min(formSize, c.maxSize()));
            Button minus = addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                formSize--;
                rebuildWidgets();
            }).bounds(fx, top + 74, 20, 18).build());
            Button plus = addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                formSize++;
                rebuildWidgets();
            }).bounds(fx + 50, top + 74, 20, 18).build());
            minus.active = formSize > 2;
            plus.active = formSize < c.maxSize();

            if (noteBox == null) {
                noteBox = new EditBox(font, 0, 0, 130, 18, Component.literal("Note"));
                noteBox.setMaxLength(100);
                noteBox.setValue(formNote);
                noteBox.setHint(Component.literal("e.g. need mage"));
                noteBox.setResponder(v -> formNote = v);
            }
            noteBox.setX(fx);
            noteBox.setY(top + 98);
            addRenderableWidget(noteBox);

            addRenderableWidget(Button.builder(Component.literal(mine.leader() ? "Update listing" : "Post listing"), b -> {
                String detail = c.freeText() ? formText.trim() : c.details().get(formDetail);
                Sky2MPartyFinder.create(c.name(), detail, formSize, formNote.trim());
            }).bounds(left + 8, top + 124, 182, 20).build());
        }

        // Members (Kick for the leader) and requests (Accept / Decline).
        int rx = left + 206;
        int y = top + 38;
        Listing l = mine.listing();
        if (l != null) {
            for (Member m : l.members()) {
                if (mine.leader() && !Sbc.isSelf(m.uuid()) && !m.uuid().isEmpty()) {
                    addRenderableWidget(Button.builder(Component.literal("Kick"), b -> Sky2MPartyFinder.byUuid("pfKick", m.uuid()))
                        .bounds(left + W - 48, y - 2, 40, 12).build());
                }
                y += 14;
            }
            if (mine.leader()) {
                y += 16;
                for (int i = 0; i < Math.min(5, l.requests().size()); i++) {
                    Request r = l.requests().get(i);
                    addRenderableWidget(Button.builder(Component.literal("✔"), b -> Sky2MPartyFinder.byUuid("pfAccept", r.uuid()))
                        .tooltip(Tooltip.create(Component.literal("Accept " + r.username()))).bounds(left + W - 48, y - 2, 19, 12).build());
                    addRenderableWidget(Button.builder(Component.literal("✖"), b -> Sky2MPartyFinder.byUuid("pfDecline", r.uuid()))
                        .tooltip(Tooltip.create(Component.literal("Decline " + r.username()))).bounds(left + W - 27, y - 2, 19, 12).build());
                    y += 14;
                }
            }
            addRenderableWidget(Button.builder(Component.literal(mine.leader() ? "Close listing" : "Leave group"),
                b -> { if (mine.leader()) Sky2MPartyFinder.close(); else Sky2MPartyFinder.leave(); })
                .bounds(rx, bottom - 26, 90, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> Sky2MPartyFinder.requestMine())
            .bounds(left + W - 70, bottom - 26, 62, 20).build());
    }

    // ---------------------------------------------------------------- updates and drawing

    @Override
    public void tick() {
        Sky2MPartyFinder.ensureReady();
        if (seenVersion != Sky2MPartyFinder.version) {
            var focused = getFocused();
            rebuildWidgets();
            // Keep typing in a text box when a push arrives.
            if (focused instanceof EditBox box && children().contains(box)) setFocused(box);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == Tab.BROWSE) {
            int max = Math.max(0, shownListings().size() - visibleRows());
            int next = Math.max(0, Math.min(max, scroll - (int) Math.signum(verticalAmount)));
            if (next != scroll) {
                scroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, 0xB005070B);
        int left = left();
        int top = top();
        int right = left + W;
        int bottom = top + H;
        g.fill(left, top, right, bottom, PANEL);
        outline(g, left, top, right, bottom, BORDER);
        g.text(font, "Party Finder", right - 8 - font.width("Party Finder"), top - 19, GOLD, true);

        String status = Sky2MPartyFinder.status();
        if (tab == Tab.BROWSE) drawBrowse(g, left, top, right, bottom, status);
        else drawGroup(g, left, top, right, bottom);

        String error = Sky2MPartyFinder.error;
        if (!error.isEmpty() && System.currentTimeMillis() - Sky2MPartyFinder.errorAt < 5_000L) {
            g.centeredText(font, error, (left + right) / 2, bottom + 4, RED);
        }
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawBrowse(GuiGraphicsExtractor g, int left, int top, int right, int bottom, String status) {
        List<Listing> shown = shownListings();
        if (!status.isEmpty()) {
            g.centeredText(font, status, (left + right) / 2, top + 100, MUTED);
        } else if (shown.isEmpty()) {
            g.centeredText(font, "No open listings" + (filter.isEmpty() ? "." : " for " + Sky2MPartyFinder.categoryLabel(filter) + "."), (left + right) / 2, top + 90, MUTED);
            g.centeredText(font, "Post one from the My Group tab.", (left + right) / 2, top + 102, MUTED);
        }
        for (int i = 0; status.isEmpty() && i < visibleRows() && scroll + i < shown.size(); i++) {
            Listing l = shown.get(scroll + i);
            int y = listTop() + i * ROW_H;
            g.fill(left + 6, y, right - 6, y + ROW_H - 1, ROW);
            String title = Sky2MPartyFinder.categoryLabel(l.category()) + " " + l.detail();
            g.text(font, title, left + 10, y + 3, AQUA, false);
            int x = left + 14 + font.width(title);
            g.text(font, trim(l.leader(), 110), x, y + 3, TEXT, false);
            String count = l.members().size() + "/" + l.size();
            g.text(font, count, right - 84 - font.width(count), y + 3, l.full() ? RED : GREEN, false);
            String time = Sky2MPartyFinder.timeLeft(l);
            g.text(font, time, right - 84 - font.width(time), y + 13, MUTED, false);
            g.text(font, trim(l.note().isEmpty() ? "No note" : l.note(), W - 150), left + 10, y + 13, MUTED, false);
        }
        int max = shown.size() - visibleRows();
        if (max > 0) {
            String more = (scroll + 1) + "-" + Math.min(shown.size(), scroll + visibleRows()) + " of " + shown.size();
            g.text(font, more, right - 8 - font.width(more), bottom - 21, MUTED, false);
        }
    }

    private void drawGroup(GuiGraphicsExtractor g, int left, int top, int right, int bottom) {
        Mine mine = Sky2MPartyFinder.mine;
        String status = Sky2MPartyFinder.online() ? "" : "S2C offline. Reconnecting...";
        if (!status.isEmpty()) g.centeredText(font, status, (left + right) / 2, bottom - 20, MUTED);
        boolean form = !"member".equals(mine.role()) || mine.listing() == null;
        g.text(font, mine.leader() ? "Edit your listing" : form ? "Post a listing" : "Your group", left + 8, top + 8, GOLD, true);
        if (form) {
            g.text(font, "Category", left + 8, top + 31, MUTED, false);
            g.text(font, "Detail", left + 8, top + 55, MUTED, false);
            g.text(font, "Size", left + 8, top + 79, MUTED, false);
            g.centeredText(font, String.valueOf(formSize), left + 60 + 35, top + 79, TEXT);
            g.text(font, "Note", left + 8, top + 103, MUTED, false);
            if (!mine.leader()) {
                g.text(font, "Posting again while you lead", left + 8, top + 152, MUTED, false);
                g.text(font, "updates it and resets its expiry.", left + 8, top + 162, MUTED, false);
                if (!mine.pending().isEmpty()) {
                    g.text(font, "Waiting on " + mine.pending().size() + " join request" + (mine.pending().size() == 1 ? "" : "s") + ".", left + 8, top + 180, AQUA, false);
                }
            }
        } else {
            Listing l = mine.listing();
            g.text(font, Sky2MPartyFinder.categoryLabel(l.category()) + " " + l.detail(), left + 8, top + 30, AQUA, false);
            g.text(font, "Leader: " + l.leader(), left + 8, top + 44, TEXT, false);
            if (!l.note().isEmpty()) g.text(font, trim(l.note(), 185), left + 8, top + 58, MUTED, false);
            g.text(font, Sky2MPartyFinder.timeLeft(l), left + 8, top + 72, MUTED, false);
        }

        int rx = left + 206;
        g.fill(rx - 8, top + 6, rx - 7, bottom - 32, BORDER);
        Listing l = mine.listing();
        if (l == null) {
            g.text(font, "You're not in a group.", rx, top + 24, MUTED, false);
            if (!Sky2MPartyFinder.mineReceived) g.text(font, "Loading...", rx, top + 36, MUTED, false);
            return;
        }
        g.text(font, "Members " + l.members().size() + "/" + l.size(), rx, top + 24, GOLD, false);
        int y = top + 38;
        for (Member m : l.members()) {
            boolean leader = m.username().equalsIgnoreCase(l.leader());
            g.text(font, (leader ? "★ " : "• ") + m.username(), rx, y, Sbc.isSelf(m.uuid()) ? GREEN : TEXT, false);
            y += 14;
        }
        if (!mine.leader()) return;
        y += 2;
        g.text(font, "Requests " + (l.requests().isEmpty() ? "(none)" : "(" + l.requests().size() + ")"), rx, y, GOLD, false);
        y += 14;
        for (int i = 0; i < Math.min(5, l.requests().size()); i++) {
            Request r = l.requests().get(i);
            String text = r.username() + (r.note().isEmpty() ? "" : " - " + r.note());
            g.text(font, trim(text, right - rx - 56), rx, y, TEXT, false);
            y += 14;
        }
        if (l.requests().size() > 5) g.text(font, "+" + (l.requests().size() - 5) + " more (/s2 pf mine)", rx, y, MUTED, false);
    }

    private String trim(String text, int width) {
        if (font.width(text) <= width) return text;
        while (text.length() > 1 && font.width(text + "…") > width) text = text.substring(0, text.length() - 1);
        return text + "…";
    }

    private static void outline(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int colour) {
        g.fill(left, top, right, top + 1, colour);
        g.fill(left, bottom - 1, right, bottom, colour);
        g.fill(left, top, left + 1, bottom, colour);
        g.fill(right - 1, top, right, bottom, colour);
    }

    /** A button that steps forward on left click and backward on right click. */
    private static final class CycleButton extends Button.Plain {
        private final java.util.function.IntConsumer step;

        CycleButton(int x, int y, int w, int h, Component message, java.util.function.IntConsumer step) {
            super(x, y, w, h, message, b -> {}, DEFAULT_NARRATION);
            this.step = step;
        }

        @Override
        protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo info) {
            return info.button() == InputConstants.MOUSE_BUTTON_LEFT || info.button() == InputConstants.MOUSE_BUTTON_RIGHT;
        }

        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            step.accept(event.button() == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
