package com.epic60869.sky2m.features.sbc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * /s2 who: Sky2M players, online ones at the top (friends first) with their styled nickname, rank, badge, where they
 * are (when they share it), AFK, how long they've been on and their Sky2M version. Hover a row to add them as a
 * friend. Offline players follow, most recently seen first, when the server sends them.
 */
public final class SbcWhoScreen extends Screen {
    private static final int ROW = 22;

    private int scroll;
    private long refreshedAt;

    public SbcWhoScreen() {
        super(Component.literal("Sky2M Users"));
    }

    private int panelW() {
        return Math.min(440, width - 20);
    }

    private int left() {
        return (width - panelW()) / 2;
    }

    private int top() {
        return 30;
    }

    private int bottom() {
        return height - 34;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> SbcSocial.requestWho(false))
            .bounds(width / 2 - 154, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Friends"), b -> SbcSocial.openFriends())
            .bounds(width / 2 - 50, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(width / 2 + 54, height - 26, 100, 20).build());
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt > 15_000L) {
            refreshedAt = now;
            SbcSocial.requestWho(false);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        List<SbcSocial.Player> players = SbcSocial.online;
        int left = left();
        int right = left + panelW();
        int top = top();
        int bottom = bottom();
        int onlineCount = SbcSocial.onlineCount(players);
        String title = "Sky2M Users" + (SbcSocial.onlineAt == 0 ? ""
            : onlineCount == players.size() ? " (" + onlineCount + " online)" : " (" + onlineCount + " online, " + players.size() + " total)");
        g.centeredText(font, title, width / 2, 12, 0xFFFFD35A);
        g.fill(left, top, right, bottom, 0xC0121722);
        g.outline(left, top, panelW(), bottom - top, 0xFF3B465B);

        if (!SbcNet.online()) {
            g.centeredText(font, "S2C offline: can't reach the Sky2M server.", width / 2, top + 20, 0xFFFF5555);
            return;
        }
        if (SbcSocial.onlineAt == 0) {
            boolean noAnswer = SbcSocial.whoUnanswered();
            g.centeredText(font, noAnswer ? "The Sky2M server didn't answer. Press Refresh to try again." : "Loading...",
                width / 2, top + 20, noAnswer ? 0xFFFF5555 : 0xFFAAAAAA);
            return;
        }
        int visible = (bottom - top - 4) / ROW;
        scroll = Math.max(0, Math.min(scroll, players.size() - visible));
        g.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        for (int i = scroll; i < Math.min(players.size(), scroll + visible + 1); i++) {
            SbcSocial.Player p = players.get(i);
            int y = top + 2 + (i - scroll) * ROW;
            boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW && mouseY < bottom;
            if (hover) g.fill(left + 1, y, right - 1, y + ROW, 0x20FFFFFF);
            MutableComponent name = Component.empty();
            if (p.friend()) name.append(Component.literal("★ ").withStyle(s -> s.withColor(0xFFAA00)));
            name.append(p.prefixComponent()).append(p.name());
            g.text(font, name, left + 6, y + 2, 0xFFFFFFFF, true);

            String where = SbcSocial.where(p.area(), p.server());
            String seen = p.lastSeen() > 0 ? Sbc.duration(System.currentTimeMillis() - p.lastSeen()) + " ago" : "";
            if (!p.online()) {
                g.text(font, "Offline" + (seen.isEmpty() ? "" : "  ·  last seen " + seen), left + 6, y + 12, 0xFF666666, false);
            } else {
                String details = (where.isEmpty() ? "Location not shared" : where) + (p.afk() ? "  ·  AFK" : "");
                g.text(font, details, left + 6, y + 12, where.isEmpty() ? 0xFF666666 : 0xFF9AA5B8, false);
            }
            // A line between the online and the offline players.
            if (!p.online() && i > 0 && players.get(i - 1).online()) g.fill(left + 4, y, right - 4, y + 1, 0xFF3B465B);

            String since = p.online() && p.onlineSince() > 0 ? Sbc.duration(System.currentTimeMillis() - p.onlineSince()) : "";
            String version = p.modVersion().isBlank() ? "" : "v" + p.modVersion();
            if (!hover) {
                g.text(font, since, right - 6 - font.width(since), y + 2, 0xFF9AA5B8, false);
                g.text(font, version, right - 6 - font.width(version), y + 12, 0xFF666666, false);
            } else {
                if (!p.friend() && p.uuid() != null && !p.uuid().equals(Sbc.self())) drawButton(g, "+Friend", right - 52, y + 4, mouseX, mouseY);
                if (!p.username().isBlank() && mouseX < right - 52) {
                    List<Component> tip = new java.util.ArrayList<>();
                    tip.add(Component.literal(p.username()));
                    if (!since.isEmpty()) tip.add(Component.literal("Online for " + since).withStyle(s -> s.withColor(0xAAAAAA)));
                    if (!p.online() && !seen.isEmpty()) tip.add(Component.literal("Last seen " + seen).withStyle(s -> s.withColor(0xAAAAAA)));
                    if (!version.isEmpty()) tip.add(Component.literal("Sky2M " + version).withStyle(s -> s.withColor(0xAAAAAA)));
                    g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
                }
            }
        }
        g.disableScissor();
        if (players.isEmpty()) g.centeredText(font, "Nobody else is online with Sky2M.", width / 2, top + 20, 0xFFAAAAAA);
    }

    private void drawButton(GuiGraphicsExtractor g, String label, int x, int y, int mouseX, int mouseY) {
        int w = 46;
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0xFF3B5B8B : 0xFF2A3345);
        g.centeredText(font, label, x + w / 2, y + 3, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        List<SbcSocial.Player> players = SbcSocial.online;
        int right = left() + panelW();
        double x = event.x(), y = event.y();
        if (x >= left() && x < right && y >= top() && y < bottom()) {
            int index = scroll + (int) ((y - top() - 2) / ROW);
            if (index >= 0 && index < players.size()) {
                SbcSocial.Player p = players.get(index);
                int rowY = top() + 2 + (index - scroll) * ROW;
                if (y >= rowY + 4 && y < rowY + 18) {
                    if (x >= right - 52 && x < right - 6 && !p.friend()) {
                        SbcSocial.friend("friendRequest", p.username());
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
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
