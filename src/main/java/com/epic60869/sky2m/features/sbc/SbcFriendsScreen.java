package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MNick;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /s2 friends: your Sky2M friends (online first, where they are if they share it, when they were last seen), friend
 * requests with Accept / Deny, and who may see your location, friend requests and online notifications.
 */
public final class SbcFriendsScreen extends Screen {
    private enum Tab { FRIENDS, REQUESTS, SETTINGS }

    private record RowButton(String label, int x, int width, Runnable action) {}

    private static final int ROW = 22;
    private static Tab tab = Tab.FRIENDS;

    private EditBox addBox;
    private int scroll;
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Button> settingButtons = new ArrayList<>();
    private final List<RowButton> rowButtons = new ArrayList<>();
    private int rowButtonsY = -1;
    private String confirmRemove = "";
    private long confirmRemoveAt;
    private long refreshedAt;

    public SbcFriendsScreen() {
        super(Component.literal("Sky2M Friends"));
    }

    private int panelW() {
        return Math.min(420, width - 20);
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
        tabButtons.clear();
        settingButtons.clear();
        int x = width / 2 - 153;
        for (Tab t : Tab.values()) {
            Button b = addRenderableWidget(Button.builder(Component.literal(tabName(t)), btn -> {
                tab = t;
                scroll = 0;
                updateVisibility();
            }).bounds(x, 28, 100, 20).build());
            tabButtons.add(b);
            x += 103;
        }
        addBox = new EditBox(font, width / 2 - 154, height - 26, 150, 20, Component.literal("Player"));
        addBox.setHint(Component.literal("Add a friend..."));
        addBox.setMaxLength(16);
        addRenderableWidget(addBox);
        addRenderableWidget(Button.builder(Component.literal("Send request"), b -> {
            String name = addBox.getValue().trim();
            if (!name.isEmpty()) {
                SbcSocial.friend("friendRequest", name);
                addBox.setValue("");
            }
        }).bounds(width / 2, height - 26, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 94, height - 26, 60, 20).build());

        int y = top() + 10;
        settingButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> {
            SbcConfig.Social c = Sbc.config().social;
            c.location = SbcConfig.LocationSharing.values()[(c.location.ordinal() + 1) % SbcConfig.LocationSharing.values().length];
        }).bounds(width / 2 - 120, y, 240, 20).build()));
        settingButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> Sbc.config().social.friendRequests ^= true)
            .bounds(width / 2 - 120, y + 24, 240, 20).build()));
        settingButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> Sbc.config().social.notifyFriendsOnline ^= true)
            .bounds(width / 2 - 120, y + 48, 240, 20).build()));
        settingButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> Sbc.config().social.friendToasts ^= true)
            .bounds(width / 2 - 120, y + 72, 240, 20).build()));
        settingButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> Sbc.config().social.presence ^= true)
            .bounds(width / 2 - 120, y + 96, 240, 20).build()));
        updateVisibility();
    }

    private static String tabName(Tab t) {
        return switch (t) {
            case FRIENDS -> "Friends";
            case REQUESTS -> "Requests" + (SbcSocial.incoming.isEmpty() ? "" : " (" + SbcSocial.incoming.size() + ")");
            case SETTINGS -> "Settings";
        };
    }

    private void updateVisibility() {
        for (Button b : settingButtons) b.visible = tab == Tab.SETTINGS;
        for (int i = 0; i < tabButtons.size(); i++) tabButtons.get(i).active = Tab.values()[i] != tab;
    }

    @Override
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt > 30_000L && SbcNet.online()) {
            refreshedAt = now;
            SbcNet.sendAuthed(Sbc.packet("friendList"));
        }
        for (int i = 0; i < tabButtons.size(); i++) tabButtons.get(i).setMessage(Component.literal(tabName(Tab.values()[i])));
        SbcConfig.Social c = Sbc.config().social;
        if (settingButtons.size() == 5) {
            settingButtons.get(0).setMessage(Component.literal("Who can see where I am: " + c.location));
            settingButtons.get(1).setMessage(Component.literal("Friend requests: " + (c.friendRequests ? "Allowed" : "Off")));
            settingButtons.get(2).setMessage(Component.literal("Tell friends when I'm online: " + (c.notifyFriendsOnline ? "On" : "Off")));
            settingButtons.get(3).setMessage(Component.literal("Popup when a friend comes online: " + (c.friendToasts ? "On" : "Off")));
            settingButtons.get(4).setMessage(Component.literal("Share my area and server: " + (c.presence ? "On" : "Off")));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        long online = SbcSocial.friends.stream().filter(SbcSocial.Friend::online).count();
        g.centeredText(font, "Sky2M Friends" + (SbcSocial.friendsLoaded ? " (" + online + "/" + SbcSocial.friends.size() + " online)" : ""), width / 2, 12, 0xFFFFD35A);
        rowButtons.clear();
        rowButtonsY = -1;
        if (tab == Tab.SETTINGS) {
            g.centeredText(font, "These are saved on the Sky2M server.", width / 2, top() + 130, 0xFF777777);
            return;
        }
        int left = left();
        int right = left + panelW();
        int top = top();
        int bottom = bottom();
        g.fill(left, top, right, bottom, 0xC0121722);
        g.outline(left, top, panelW(), bottom - top, 0xFF3B465B);
        if (!SbcNet.online()) {
            g.centeredText(font, "S2C offline: can't reach the Sky2M server.", width / 2, top + 20, 0xFFFF5555);
            return;
        }
        if (!SbcSocial.friendsLoaded) {
            g.centeredText(font, "Logging in...", width / 2, top + 20, 0xFFAAAAAA);
            return;
        }

        List<Row> rows = rows();
        int visible = (bottom - top - 4) / ROW;
        scroll = Math.max(0, Math.min(scroll, rows.size() - visible));
        g.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible + 1); i++) {
            Row row = rows.get(i);
            int y = top + 2 + (i - scroll) * ROW;
            if (row.header()) {
                g.text(font, row.title(), left + 6, y + 8, 0xFFFFD35A, true);
                continue;
            }
            boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + ROW && mouseY < bottom;
            if (hover) g.fill(left + 1, y, right - 1, y + ROW, 0x20FFFFFF);
            g.text(font, row.name(), left + 8, y + 2, 0xFFFFFFFF, true);
            g.text(font, row.detail(), left + 8, y + 12, row.detailColour(), false);
            int bx = right - 6;
            for (int b = row.buttons().size() - 1; b >= 0; b--) {
                RowButton button = row.buttons().get(b);
                if (!hover && !row.alwaysButtons()) continue;
                bx -= button.width();
                RowButton placed = new RowButton(button.label(), bx, button.width(), button.action());
                boolean over = mouseX >= bx && mouseX < bx + button.width() && mouseY >= y + 4 && mouseY < y + 18;
                g.fill(bx, y + 4, bx + button.width(), y + 18, over ? 0xFF3B5B8B : 0xFF2A3345);
                g.centeredText(font, button.label(), bx + button.width() / 2, y + 7, 0xFFFFFFFF);
                if (hover) {
                    rowButtons.add(placed);
                    rowButtonsY = y;
                }
                bx -= 3;
            }
        }
        g.disableScissor();
        if (rows.isEmpty()) {
            g.centeredText(font, tab == Tab.FRIENDS ? "No friends yet. Add someone below!" : "No friend requests.", width / 2, top + 20, 0xFFAAAAAA);
        }
    }

    private record Row(boolean header, String title, Component name, String detail, int detailColour, List<RowButton> buttons, boolean alwaysButtons) {}

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        if (tab == Tab.FRIENDS) {
            for (SbcSocial.Friend f : SbcSocial.friends) {
                String where = SbcSocial.where(f.area(), f.server());
                String detail = f.online() ? "Online" + (where.isEmpty() ? "" : " · " + where) + (f.afk() ? " · AFK" : "")
                    : "Last seen " + Sbc.ago(f.lastSeen());
                List<RowButton> buttons = new ArrayList<>();
                boolean confirming = confirmRemove.equals(f.username()) && System.currentTimeMillis() - confirmRemoveAt < 4000;
                buttons.add(new RowButton(confirming ? "Sure?" : "Remove", 0, 46, () -> {
                    if (confirmRemove.equals(f.username()) && System.currentTimeMillis() - confirmRemoveAt < 4000) {
                        confirmRemove = "";
                        SbcSocial.friend("friendRemove", f.username());
                    } else {
                        confirmRemove = f.username();
                        confirmRemoveAt = System.currentTimeMillis();
                    }
                }));
                Component name = Component.empty().append(Component.literal(f.online() ? "● " : "○ ").withStyle(s -> s.withColor(f.online() ? 0x55FF55 : 0x555555)))
                    .append(Sky2MNick.displayName(f.uuid(), f.username()));
                rows.add(new Row(false, "", name, detail, f.online() ? 0xFF9AA5B8 : 0xFF666666, buttons, false));
            }
        } else {
            if (!SbcSocial.incoming.isEmpty()) rows.add(new Row(true, "Incoming", null, "", 0, List.of(), false));
            for (SbcSocial.Request r : SbcSocial.incoming) {
                rows.add(new Row(false, "", Component.literal(r.username()), r.at() > 0 ? "Sent " + Sbc.ago(r.at()) : "", 0xFF9AA5B8, List.of(
                    new RowButton("Accept", 0, 46, () -> SbcSocial.friend("friendAccept", r.username())),
                    new RowButton("Deny", 0, 40, () -> SbcSocial.friend("friendDeny", r.username()))), true));
            }
            if (!SbcSocial.outgoing.isEmpty()) rows.add(new Row(true, "Sent", null, "", 0, List.of(), false));
            for (SbcSocial.Request r : SbcSocial.outgoing) {
                rows.add(new Row(false, "", Component.literal(r.username()), r.at() > 0 ? "Sent " + Sbc.ago(r.at()) : "", 0xFF9AA5B8, List.of(
                    new RowButton("Cancel", 0, 46, () -> SbcSocial.friend("friendRemove", r.username()))), true));
            }
        }
        return rows;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (rowButtonsY >= 0 && event.y() >= rowButtonsY + 4 && event.y() < rowButtonsY + 18) {
            for (RowButton b : rowButtons) {
                if (event.x() >= b.x() && event.x() < b.x() + b.width()) {
                    b.action().run();
                    return true;
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
