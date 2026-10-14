package com.epic60869.sky2m.reports;

import com.epic60869.sky2m.Sky2MGlobalChat;
import com.epic60869.sky2m.Sky2MLogin;
import com.epic60869.sky2m.features.sbc.Flags;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * /s2 reports: for a player, their own bug reports, suggestions and feedback; for an admin, everyone's, filtered by
 * type and status. Unread threads first, with a dot. Click one to open the conversation.
 */
public final class ReportsForumScreen extends Screen {
    private static final int ROW = 24;

    private Reports.Type filter;
    private Reports.Status statusFilter;
    private Button typeButton;
    private Button statusButton;
    private Button oldViewButton;
    private int scroll;
    private int seenVersion = -1;
    /** The connection and admin state the list was asked for; asked again when either changes. */
    private Object askedOn;
    private boolean askedAsAdmin;

    public ReportsForumScreen(Reports.Type filter) {
        super(Component.literal("Sky2M Reports"));
        this.filter = filter;
    }

    private int panelW() {
        return Math.min(460, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    private int top() {
        return 54;
    }

    private int bottom() {
        return height - 34;
    }

    @Override
    protected void init() {
        typeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            filter = filter == null ? Reports.Type.BUG : filter.ordinal() + 1 < Reports.Type.values().length
                ? Reports.Type.values()[filter.ordinal() + 1] : null;
            reload();
        }).bounds(width / 2 - 152, 28, 150, 20).build());
        statusButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            statusFilter = statusFilter == null ? Reports.Status.OPEN : statusFilter.ordinal() + 1 < Reports.Status.values().length
                ? Reports.Status.values()[statusFilter.ordinal() + 1] : null;
            reload();
        }).bounds(width / 2 + 2, 28, 150, 20).build());

        int y = height - 26;
        int x = width / 2 - 230;
        addRenderableWidget(Button.builder(Component.literal("New Bug Report"), b -> minecraft.gui.setScreen(new ReportScreen(Reports.Type.BUG)))
            .bounds(x, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("New Suggestion"), b -> minecraft.gui.setScreen(new ReportScreen(Reports.Type.SUGGESTION)))
            .bounds(x + 104, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Feedback"), b -> minecraft.gui.setScreen(new ReportScreen(Reports.Type.FEEDBACK)))
            .bounds(x + 208, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> reload()).bounds(x + 282, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + 356, y, 104, 20).build());
        // The owner's old view of the site, while the server can't do threads.
        oldViewButton = addRenderableWidget(Button.builder(Component.literal("Open the old report view"),
            b -> minecraft.gui.setScreen(new ReportsViewScreen(ReportsViewScreen.Filter.ALL))).bounds(width / 2 - 90, top() + 60, 180, 20).build());
        updateButtons();
    }

    private void reload() {
        scroll = 0;
        askedOn = null;
        Reports.threadsLoaded = false;
        ensureLoaded();
        updateButtons();
    }

    /** Asks for the list once per connection (and again when the server says this player is an admin). */
    private void ensureLoaded() {
        if (!SbcNet.online()) {
            Sky2MGlobalChat.ensureConnected();
            return;
        }
        if (Reports.support == Reports.Support.UNKNOWN) {
            Reports.askSupport();
            return;
        }
        if (!Reports.threadsReady()) return;
        Object connection = Sky2MGlobalChat.currentConnection();
        if (connection == askedOn && askedAsAdmin == Reports.admin) return;
        askedOn = connection;
        askedAsAdmin = Reports.admin;
        Reports.requestList(filter, statusFilter);
    }

    private void updateButtons() {
        boolean admin = Reports.threadsReady() && Reports.admin;
        typeButton.visible = admin;
        statusButton.visible = admin;
        typeButton.setMessage(Component.literal("Type: " + (filter == null ? "All" : filter.label)));
        statusButton.setMessage(Component.literal("Status: " + (statusFilter == null ? "Any" : statusFilter.label)));
        oldViewButton.visible = Reports.isOwner() && Reports.support == Reports.Support.NO;
    }

    @Override
    public void tick() {
        ensureLoaded();
        if (seenVersion != Reports.version) {
            seenVersion = Reports.version;
            updateButtons();
        }
    }

    /** Why the list can't show, or "" when it can. */
    private String problem() {
        if (!Flags.isEnabled(Reports.FLAG)) return Flags.message(Reports.FLAG);
        if (!SbcNet.online()) return "S2C offline: can't reach the Sky2M server. It reconnects by itself.";
        if (Reports.support == Reports.Support.NO) {
            return "Replies to reports need the updated Sky2M server. Reports you send still arrive.";
        }
        if (!Sky2MLogin.loggedIn() && !Sky2MLogin.problem().isEmpty()) return "Couldn't log in: " + Sky2MLogin.problem();
        if (Reports.support == Reports.Support.UNKNOWN || !Reports.threadsLoaded) return "Loading...";
        return "";
    }

    /** The threads this screen shows (an admin's filters are applied by the server, and again here). */
    private List<Reports.ReportThread> shown() {
        return Reports.threads.stream()
            .filter(t -> !Reports.admin || filter == null || t.type() == filter)
            .filter(t -> !Reports.admin || statusFilter == null || t.status() == statusFilter)
            .toList();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        boolean admin = Reports.threadsReady() && Reports.admin;
        String heading = admin ? "Sky2M Reports" : "My Reports";
        List<Reports.ReportThread> rows = problem().isEmpty() ? shown() : List.of();
        long unread = rows.stream().filter(Reports.ReportThread::unread).count();
        g.centeredText(font, heading + (unread > 0 ? " (" + unread + " new)" : ""), width / 2, 12, 0xFFFFD35A);
        if (!admin) {
            g.centeredText(font, Component.literal("Your bug reports, suggestions and feedback, and the Sky2M team's replies.")
                .withStyle(ChatFormatting.GRAY), width / 2, 34, 0xFFAAAAAA);
        }
        int left = left();
        int right = left + panelW();
        int top = top();
        int bottom = bottom();
        g.fill(left, top, right, bottom, 0xC0121722);
        g.outline(left, top, panelW(), bottom - top, 0xFF3B465B);
        String problem = problem();
        if (!problem.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.literal(problem), panelW() - 20);
            for (int i = 0; i < 3 && i < lines.size(); i++) {
                g.text(font, lines.get(i), width / 2 - font.width(lines.get(i)) / 2, top + 20 + i * 10, 0xFFAAAAAA, false);
            }
            return;
        }
        if (rows.isEmpty()) {
            g.centeredText(font, admin ? "Nothing here. All caught up!" : "You haven't sent any reports yet.", width / 2, top + 20, 0xFFAAAAAA);
            return;
        }
        int visible = (bottom - top - 4) / ROW;
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible));
        g.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible + 1); i++) {
            Reports.ReportThread t = rows.get(i);
            int y = top + 2 + (i - scroll) * ROW;
            boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW && mouseY < bottom;
            if (hover) g.fill(left + 1, y, right - 1, y + ROW, 0x20FFFFFF);
            int x = left + 6;
            if (t.unread()) g.text(font, "●", x, y + 3, 0xFF55FF55, false);
            x += 10;
            g.text(font, Component.literal(t.type().tag).withStyle(ChatFormatting.BOLD), x, y + 3, t.type().colour, true);
            x += font.width(Component.literal("FEEDBACK").withStyle(ChatFormatting.BOLD)) + 6;
            String status = t.status().label;
            int statusX = right - 8 - font.width(status);
            g.text(font, status, statusX, y + 3, t.status().colour, false);
            g.text(font, font.plainSubstrByWidth(t.title(), statusX - x - 8), x, y + 3, t.unread() ? 0xFFFFFFFF : 0xFFD0D0D0, true);
            StringBuilder detail = new StringBuilder();
            if (admin && !t.mine()) detail.append("by ").append(t.author().name()).append(" · ");
            detail.append(t.messageCount()).append(t.messageCount() == 1 ? " message" : " messages");
            if (t.last() != null && t.messageCount() > 1) {
                detail.append(" · last from ").append(t.last().author().isSelf() ? "you" : t.last().author().name());
            }
            detail.append(" · ").append(Sbc.ago(t.updatedAt()));
            g.text(font, font.plainSubstrByWidth(detail.toString(), right - x - 8), x, y + 13, 0xFF9AA5B8, false);
        }
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0 || !problem().isEmpty()) return false;
        int left = left();
        int top = top();
        if (event.x() < left || event.x() >= left + panelW() || event.y() < top + 2 || event.y() >= bottom()) return false;
        int index = scroll + (int) ((event.y() - top - 2) / ROW);
        List<Reports.ReportThread> rows = shown();
        if (index < 0 || index >= rows.size()) return false;
        minecraft.gui.setScreen(new ReportThreadScreen(rows.get(index).id(), this));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
