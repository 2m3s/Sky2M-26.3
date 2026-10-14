package com.epic60869.sky2m.reports;

import com.epic60869.sky2m.Sky2MGlobalChat;
import com.epic60869.sky2m.Sky2MLogin;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.epic60869.sky2m.features.sbc.SbcNet;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * One report thread: the conversation (who wrote what, admins marked, how long ago), a reply box, and for admins Mark
 * Read, Answered, Close / Reopen and Delete. Opening it marks its replies as read on the server.
 */
public final class ReportThreadScreen extends Screen {
    private static final int LINE = 10;
    private static final int GAP = 6;

    private final String id;
    private final Screen parent;
    private MultiLineEditBox reply;
    private Button send;
    private Button markRead;
    private Button answered;
    private Button close;
    private Button delete;
    private String kept = "";
    private String lastSent = "";
    private long lastSentAt;
    private long confirmDeleteAt;
    private double scroll;
    private int lastMessageCount = -1;
    private Object askedOn;

    public ReportThreadScreen(String id, Screen parent) {
        super(Component.literal("Report"));
        this.id = id;
        this.parent = parent;
        if (Reports.current != null && !Reports.current.id().equals(id)) Reports.current = null;
        Reports.deletedId = "";
    }

    private Reports.ReportThread thread() {
        Reports.ReportThread t = Reports.current;
        return t != null && t.id().equals(id) ? t : null;
    }

    private int panelW() {
        return Math.min(460, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    private int top() {
        return 60;
    }

    private int replyTop() {
        return height - 26 - 6 - 44;
    }

    private int bottom() {
        return replyTop() - 6;
    }

    @Override
    protected void init() {
        if (reply != null) kept = reply.getValue();
        int left = left();
        int w = panelW();
        reply = MultiLineEditBox.builder()
            .setX(left).setY(replyTop())
            .setPlaceholder(Component.literal("Write a reply...").withStyle(ChatFormatting.DARK_GRAY))
            .setShowBackground(true)
            .build(font, w, 44, Component.literal("Reply"));
        reply.setCharacterLimit(Reports.MESSAGE_MAX);
        reply.setValue(kept);
        addRenderableWidget(reply);

        int y = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose()).bounds(left, y, 70, 20).build());
        send = addRenderableWidget(Button.builder(Component.literal("Send"), b -> send()).bounds(left + w - 80, y, 80, 20).build());

        int bx = width / 2 - 166;
        markRead = addRenderableWidget(Button.builder(Component.literal("Mark Read"), b -> Reports.markRead(id)).bounds(bx, 36, 80, 18).build());
        answered = addRenderableWidget(Button.builder(Component.literal("Mark Answered"), b -> Reports.setStatus(id, Reports.Status.ANSWERED))
            .bounds(bx + 84, 36, 84, 18).build());
        close = addRenderableWidget(Button.builder(Component.literal("Close"), b -> {
            Reports.ReportThread t = thread();
            if (t != null) Reports.setStatus(id, t.status() == Reports.Status.CLOSED ? Reports.Status.OPEN : Reports.Status.CLOSED);
        }).bounds(bx + 172, 36, 76, 18).build());
        delete = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            if (System.currentTimeMillis() - confirmDeleteAt < 4_000L) {
                confirmDeleteAt = 0;
                Reports.deleteThread(id);
            } else {
                confirmDeleteAt = System.currentTimeMillis();
            }
        }).bounds(bx + 252, 36, 80, 18).build());
        updateButtons();
    }

    private void send() {
        String text = reply.getValue().strip();
        if (text.isEmpty()) return;
        if (Reports.reply(id, text)) {
            lastSent = text;
            lastSentAt = System.currentTimeMillis();
            reply.setValue("");
        }
    }

    private void updateButtons() {
        Reports.ReportThread t = thread();
        boolean admin = Reports.threadsReady() && Reports.admin && t != null;
        for (Button b : List.of(markRead, answered, close, delete)) b.visible = admin;
        if (t != null) {
            markRead.active = t.status() == Reports.Status.OPEN;
            answered.active = t.status() != Reports.Status.ANSWERED;
            close.setMessage(Component.literal(t.status() == Reports.Status.CLOSED ? "Reopen" : "Close"));
        }
        delete.setMessage(Component.literal(System.currentTimeMillis() - confirmDeleteAt < 4_000L ? "Sure?" : "Delete")
            .withStyle(ChatFormatting.RED));
        boolean canReply = t != null && Reports.threadsReady() && (Reports.admin || t.status() != Reports.Status.CLOSED);
        reply.active = canReply;
        send.active = canReply && Reports.sendWait() == 0;
    }

    @Override
    public void tick() {
        if (id.equals(Reports.deletedId)) {
            Reports.deletedId = "";
            Reports.current = null;
            onClose();
            return;
        }
        if (!SbcNet.online()) {
            Sky2MGlobalChat.ensureConnected();
        } else if (Reports.support == Reports.Support.UNKNOWN) {
            Reports.askSupport();
        } else if (Reports.threadsReady()) {
            Object connection = Sky2MGlobalChat.currentConnection();
            if (connection != askedOn) {
                askedOn = connection;
                Reports.requestThread(id);
            }
        }
        // A reply the server refused: put it back in the box.
        if (!lastSent.isEmpty() && Reports.errorAt > lastSentAt && reply.getValue().isEmpty()) {
            reply.setValue(lastSent);
            lastSent = "";
        }
        updateButtons();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent != null ? parent : new ReportsForumScreen(null));
    }

    private String problem() {
        if (!SbcNet.online()) return "S2C offline: can't reach the Sky2M server. It reconnects by itself.";
        if (Reports.support == Reports.Support.NO) return "Replies to reports need the updated Sky2M server.";
        if (!Sky2MLogin.loggedIn() && !Sky2MLogin.problem().isEmpty()) return "Couldn't log in: " + Sky2MLogin.problem();
        if (thread() == null) return "Loading...";
        return "";
    }

    /** A message's place in the conversation. */
    private record Block(Reports.Message message, List<FormattedCharSequence> lines, int height) {}

    private List<Block> blocks(Reports.ReportThread t) {
        List<Block> out = new ArrayList<>();
        int w = panelW() - 20;
        for (Reports.Message m : t.messages()) {
            String text = m.text().isBlank() ? "(no description)" : m.text();
            List<FormattedCharSequence> lines = font.split(Component.literal(text), w);
            out.add(new Block(m, lines, 6 + LINE + lines.size() * LINE + 4));
        }
        return out;
    }

    private int contentHeight(List<Block> blocks) {
        int h = 0;
        for (Block b : blocks) h += b.height() + GAP;
        return h;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        Reports.ReportThread t = thread();
        int left = left();
        int right = left + panelW();
        int top = top();
        int bottom = bottom();
        if (t != null) {
            String title = font.plainSubstrByWidth(t.title(), panelW());
            g.centeredText(font, Component.literal(title).withStyle(ChatFormatting.BOLD), width / 2, 10, 0xFFFFFFFF);
            Component info = Component.literal(t.type().label).withStyle(s -> s.withColor(t.type().colour & 0xFFFFFF))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(t.status().label).withStyle(s -> s.withColor(t.status().colour & 0xFFFFFF)))
                .append(Component.literal(" · by " + (t.mine() ? "you" : t.author().name()) + " · " + Sbc.ago(t.createdAt()))
                    .withStyle(ChatFormatting.GRAY));
            g.centeredText(font, info, width / 2, 22, 0xFFFFFFFF);
        } else {
            g.centeredText(font, Component.literal("Report").withStyle(ChatFormatting.BOLD), width / 2, 10, 0xFFFFFFFF);
        }
        g.fill(left, top, right, bottom, 0xC0121722);
        g.outline(left, top, panelW(), bottom - top, 0xFF3B465B);

        String problem = problem();
        if (!problem.isEmpty()) {
            g.centeredText(font, problem, width / 2, top + 20, 0xFFAAAAAA);
        } else {
            List<Block> blocks = blocks(t);
            int content = contentHeight(blocks);
            int view = bottom - top - 8;
            if (blocks.size() != lastMessageCount) {
                lastMessageCount = blocks.size();
                scroll = Math.max(0, content - view); // newest at the bottom, like a chat
            }
            scroll = Mth.clamp(scroll, 0, Math.max(0, content - view));
            g.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
            int y = top + 4 - (int) scroll;
            for (Block b : blocks) {
                if (y + b.height() >= top && y <= bottom) {
                    Reports.Message m = b.message();
                    boolean admin = m.author().admin();
                    boolean self = m.author().isSelf();
                    g.fill(left + 4, y, right - 4, y + b.height(), admin ? 0x50403010 : self ? 0x40203048 : 0x40262A33);
                    g.fill(left + 4, y, left + 6, y + b.height(), admin ? 0xFFFFAA00 : 0xFF55AAFF);
                    int x = left + 10;
                    String name = self ? m.author().name() + " (you)" : m.author().name();
                    g.text(font, Component.literal(name).withStyle(ChatFormatting.BOLD), x, y + 4, admin ? 0xFFFFD35A : 0xFFFFFFFF, true);
                    x += font.width(Component.literal(name).withStyle(ChatFormatting.BOLD)) + 4;
                    if (admin) {
                        g.text(font, Component.literal("[ADMIN]").withStyle(ChatFormatting.BOLD), x, y + 4, 0xFFFF5555, true);
                        x += font.width(Component.literal("[ADMIN]").withStyle(ChatFormatting.BOLD)) + 4;
                    }
                    g.text(font, Sbc.ago(m.createdAt()), x, y + 4, 0xFF777777, false);
                    int ly = y + 6 + LINE;
                    for (FormattedCharSequence line : b.lines()) {
                        g.text(font, line, left + 10, ly, 0xFFD8D8D8, false);
                        ly += LINE;
                    }
                }
                y += b.height() + GAP;
            }
            g.disableScissor();
        }

        // Under the reply box: what's going on.
        String note = "";
        int noteColour = 0xFF777777;
        if (!Reports.error.isEmpty() && System.currentTimeMillis() - Reports.errorAt < 6_000L) {
            note = Reports.error;
            noteColour = 0xFFFF5555;
        } else if (t != null && t.status() == Reports.Status.CLOSED && !Reports.admin) {
            note = "This thread is closed.";
        } else if (reply != null && !reply.getValue().isEmpty()) {
            note = reply.getValue().length() + "/" + Reports.MESSAGE_MAX;
        }
        if (!note.isEmpty()) g.centeredText(font, font.plainSubstrByWidth(note, panelW() - 170), width / 2, height - 20, noteColour);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (reply != null && reply.isMouseOver(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        scroll -= scrollY * 20;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
